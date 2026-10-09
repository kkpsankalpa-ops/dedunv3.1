package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.io.File
import java.util.Locale

class DedunWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAppWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            WidgetPhotoHelper.deleteWidgetPhoto(context, appWidgetId)
        }
    }

    companion object {
        const val PREFS_NAME = "widget_prefs"

        fun updateAppWidgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isInitialized = prefs.getBoolean("is_initialized", false)
            val balance = prefs.getFloat("balance", 0f).toDouble()
            val safeToSpend = prefs.getFloat("safe_to_spend", 0f).toDouble()
            val streak = prefs.getInt("streak", 0)
            val daysToAllowance = prefs.getInt("days_to_allowance", 0)
            val dailySpent = prefs.getFloat("daily_spent", 0f).toDouble()
            val dailyLimit = prefs.getFloat("daily_limit", 2500f).toDouble()
            val dailyIncome = prefs.getFloat("daily_income", 0f).toDouble()
            val netCashFlow = prefs.getFloat("net_cash_flow_today", (dailyIncome - dailySpent).toFloat()).toDouble()

            // Synced theme values
            val themeAccent = prefs.getString("theme_accent", "#c9a66b") ?: "#c9a66b"
            val themeBg = prefs.getString("theme_bg", "#1c1b17") ?: "#1c1b17"
            val themeAccentColor = parseColor(themeAccent, 0xFFC9A66B.toInt())

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_large)

                // 1. Tapping anywhere on widget launches MainActivity
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val mainPendingIntent = PendingIntent.getActivity(context, appWidgetId, mainIntent, pendingIntentFlags)
                views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)

                // 2. Tapping settings icon launches WidgetConfigActivity
                val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val configPendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId + 20000,
                    configIntent,
                    pendingIntentFlags
                )
                views.setOnClickPendingIntent(R.id.btn_widget_settings, configPendingIntent)

                // 3. Background: custom photo (if set) OR synchronized active theme background
                val photoFile = if (WidgetPhotoHelper.hasCustomPhoto(context, appWidgetId)) {
                    WidgetPhotoHelper.getPhotoFile(context, appWidgetId)
                } else {
                    null
                }

                val bgBitmap = renderWidgetBackgroundBitmap(
                    context = context,
                    widthDp = 280,
                    heightDp = 140,
                    photoFile = photoFile,
                    themeBgHex = themeBg,
                    themeBorderHex = themeAccent
                )
                if (bgBitmap != null) {
                    views.setImageViewBitmap(R.id.iv_widget_bg, bgBitmap)
                    views.setViewVisibility(R.id.iv_widget_bg, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.iv_widget_bg, View.GONE)
                }

                // 4. Content states
                if (!isInitialized) {
                    views.setViewVisibility(R.id.widget_content_container, View.GONE)
                    views.setViewVisibility(R.id.widget_fallback_container, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_fallback_container, View.GONE)
                    views.setViewVisibility(R.id.widget_content_container, View.VISIBLE)

                    // Brand & Theme accents
                    views.setTextColor(R.id.tv_brand_name, themeAccentColor)
                    views.setTextColor(R.id.tv_streak, themeAccentColor)
                    views.setInt(R.id.iv_streak_icon, "setColorFilter", themeAccentColor)

                    // 1. Primary Headline: Current Balance
                    views.setTextViewText(R.id.tv_balance, formatLkr(balance))
                    views.setTextColor(R.id.tv_balance, themeAccentColor)

                    // 2. Secondary Line: Net Cash Flow Today (+/- Rs. X today)
                    val flowText = if (netCashFlow >= 0) {
                        "+${formatLkr(netCashFlow)} today"
                    } else {
                        "−${formatLkr(Math.abs(netCashFlow))} today"
                    }
                    views.setTextViewText(R.id.tv_net_cash_flow, flowText)
                    views.setTextColor(
                        R.id.tv_net_cash_flow,
                        if (netCashFlow >= 0) 0xFF728C69.toInt() else 0xFFD95F5F.toInt()
                    )

                    // 3. Smaller Secondary Section: Safe-to-Spend
                    views.setTextViewText(R.id.tv_safe_to_spend, "Safe to spend: ${formatLkr(safeToSpend)}")

                    // Streak Count
                    views.setTextViewText(R.id.tv_streak, "$streak")

                    // Built-in Quick Action Buttons on Large Widget
                    val spendIntent = QuickEntryActivity.createIntent(context, QuickEntryActivity.MODE_SPEND)
                    val spendPendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId + 90000,
                        spendIntent,
                        pendingIntentFlags
                    )
                    views.setOnClickPendingIntent(R.id.btn_large_spend, spendPendingIntent)

                    val incomeIntent = QuickEntryActivity.createIntent(context, QuickEntryActivity.MODE_INCOME)
                    val incomePendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId + 91000,
                        incomeIntent,
                        pendingIntentFlags
                    )
                    views.setOnClickPendingIntent(R.id.btn_large_income, incomePendingIntent)

                    // Daily Spend Progress Bar & Caption
                    val limit = if (dailyLimit > 0) dailyLimit else 2500.0
                    val pct = if (limit > 0) ((dailySpent / limit) * 100).toInt() else 0

                    // Status thresholds: green < 70%, amber 70-100%, red > 100%
                    val statusColor = when {
                        pct > 100 -> 0xFFD95F5F.toInt() // Red (over budget)
                        pct >= 70 -> 0xFFE2B255.toInt() // Amber (70-100%)
                        else -> 0xFF728C69.toInt()      // Green (< 70%)
                    }

                    val progressBitmap = renderProgressBarBitmap(
                        context = context,
                        widthDp = 180,
                        heightDp = 5,
                        pct = pct.coerceIn(0, 100),
                        statusColor = statusColor
                    )
                    if (progressBitmap != null) {
                        views.setImageViewBitmap(R.id.iv_widget_progress_bar, progressBitmap)
                    }

                    // Caption: "Rs. X left today" or "Rs. Y over limit today"
                    val leftAmount = limit - dailySpent
                    val captionText = if (leftAmount >= 0) {
                        "${formatLkr(leftAmount)} left today"
                    } else {
                        "${formatLkr(-leftAmount)} over limit today"
                    }
                    views.setTextViewText(R.id.tv_progress_caption, captionText)
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, DedunWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateAppWidgets(context, appWidgetManager, ids)
            }
            DedunWidgetSmallProvider.updateAllWidgets(context)
            QuickSpendWidgetProvider.updateAllWidgets(context)
            QuickIncomeWidgetProvider.updateAllWidgets(context)
        }

        fun formatLkr(amount: Double): String {
            val isNegative = amount < 0
            val absVal = Math.abs(amount)
            val formatted = String.format(Locale.US, "%,.2f", absVal)
            return if (isNegative) "-Rs. $formatted" else "Rs. $formatted"
        }

        fun parseColor(hex: String?, fallback: Int): Int {
            if (hex.isNullOrBlank()) return fallback
            return try {
                val clean = hex.trim()
                if (clean.startsWith("#")) {
                    Color.parseColor(clean)
                } else if (clean.startsWith("rgba", ignoreCase = true)) {
                    val parts = clean.substringAfter("(").substringBefore(")").split(",")
                    if (parts.size >= 4) {
                        val r = parts[0].trim().toInt()
                        val g = parts[1].trim().toInt()
                        val b = parts[2].trim().toInt()
                        val a = (parts[3].trim().toFloat() * 255).toInt().coerceIn(0, 255)
                        Color.argb(a, r, g, b)
                    } else {
                        fallback
                    }
                } else {
                    fallback
                }
            } catch (_: Exception) {
                fallback
            }
        }

        fun renderWidgetBackgroundBitmap(
            context: Context,
            widthDp: Int,
            heightDp: Int,
            photoFile: File?,
            themeBgHex: String?,
            themeBorderHex: String?
        ): Bitmap? {
            return try {
                val density = context.resources.displayMetrics.density
                val w = (widthDp * density).toInt().coerceAtLeast(200)
                val h = (heightDp * density).toInt().coerceAtLeast(100)
                val cornerRadius = 18f * density

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

                val path = Path().apply {
                    addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
                }
                canvas.clipPath(path)

                var hasCustomPhoto = false
                if (photoFile != null && photoFile.exists() && photoFile.length() > 0) {
                    val photo = BitmapFactory.decodeFile(photoFile.absolutePath)
                    if (photo != null) {
                        val matrix = Matrix()
                        val scale = Math.max(w.toFloat() / photo.width, h.toFloat() / photo.height)
                        val dx = (w - photo.width * scale) * 0.5f
                        val dy = (h - photo.height * scale) * 0.5f
                        matrix.setScale(scale, scale)
                        matrix.postTranslate(dx, dy)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                        canvas.drawBitmap(photo, matrix, paint)
                        photo.recycle()
                        hasCustomPhoto = true
                    }
                }

                if (!hasCustomPhoto) {
                    val bgColor = parseColor(themeBgHex, 0xFF1C1B17.toInt())
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = bgColor
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(rect, bgPaint)
                }

                // Outer border
                val borderColor = if (hasCustomPhoto) {
                    Color.argb(60, 255, 255, 255)
                } else {
                    val accent = parseColor(themeBorderHex, 0xFFC9A66B.toInt())
                    Color.argb(80, Color.red(accent), Color.green(accent), Color.blue(accent))
                }
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f * density
                    color = borderColor
                }
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

                bitmap
            } catch (e: Exception) {
                Log.e("DedunWidget", "Error rendering background", e)
                null
            }
        }

        fun renderProgressBarBitmap(
            context: Context,
            widthDp: Int,
            heightDp: Int,
            pct: Int,
            statusColor: Int
        ): Bitmap? {
            return try {
                val density = context.resources.displayMetrics.density
                val w = (widthDp * density).toInt().coerceAtLeast(150)
                val h = (heightDp * density).toInt().coerceAtLeast(6)
                val cornerRadius = h / 2f

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                // Track
                val trackRect = RectF(0f, 0f, w.toFloat(), h.toFloat())
                val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x33FFFFFF // 20% white track
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

                // Progress Fill
                val fillWidth = (w * (pct.coerceIn(0, 100) / 100f)).coerceAtLeast(0f)
                if (fillWidth > 0) {
                    val fillRect = RectF(0f, 0f, fillWidth, h.toFloat())
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = statusColor
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, fillPaint)
                }
                bitmap
            } catch (e: Exception) {
                null
            }
        }
    }
}
