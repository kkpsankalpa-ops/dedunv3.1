package com.example

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews

class DedunWidgetSmallProvider : AppWidgetProvider() {

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
        fun updateAppWidgets(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
            val prefs = context.getSharedPreferences(DedunWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            val isInitialized = prefs.getBoolean("is_initialized", false)
            val balance = prefs.getFloat("balance", 0f).toDouble()
            val safeToSpend = prefs.getFloat("safe_to_spend", 0f).toDouble()

            val themeAccent = prefs.getString("theme_accent", "#c9a66b") ?: "#c9a66b"
            val themeBg = prefs.getString("theme_bg", "#1c1b17") ?: "#1c1b17"
            val themeAccentColor = DedunWidgetProvider.parseColor(themeAccent, 0xFFC9A66B.toInt())

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_small)

                // 1. Tapping widget opens MainActivity
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val mainPendingIntent = PendingIntent.getActivity(context, appWidgetId, mainIntent, pendingIntentFlags)
                views.setOnClickPendingIntent(R.id.widget_small_root, mainPendingIntent)

                // 2. Tapping settings icon opens WidgetConfigActivity
                val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val configPendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId + 30000,
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

                val bgBitmap = DedunWidgetProvider.renderWidgetBackgroundBitmap(
                    context = context,
                    widthDp = 140,
                    heightDp = 70,
                    photoFile = photoFile,
                    themeBgHex = themeBg,
                    themeBorderHex = themeAccent
                )
                if (bgBitmap != null) {
                    views.setImageViewBitmap(R.id.iv_small_widget_bg, bgBitmap)
                    views.setViewVisibility(R.id.iv_small_widget_bg, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.iv_small_widget_bg, View.GONE)
                }

                // 4. Content states
                if (!isInitialized) {
                    views.setViewVisibility(R.id.widget_small_content_container, View.GONE)
                    views.setViewVisibility(R.id.widget_small_fallback_container, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_small_fallback_container, View.GONE)
                    views.setViewVisibility(R.id.widget_small_content_container, View.VISIBLE)

                    val netCashFlow = prefs.getFloat(
                        "net_cash_flow_today",
                        (prefs.getFloat("daily_income", 0f) - prefs.getFloat("daily_spent", 0f))
                    ).toDouble()

                    views.setTextColor(R.id.tv_small_brand_name, themeAccentColor)

                    // Primary headline: Current Balance
                    views.setTextViewText(R.id.tv_small_balance, DedunWidgetProvider.formatLkr(balance))
                    views.setTextColor(R.id.tv_small_balance, themeAccentColor)

                    // Secondary line: Net Cash Flow Today
                    val formattedFlow = if (netCashFlow >= 0) {
                        "+${DedunWidgetProvider.formatLkr(netCashFlow)} today"
                    } else {
                        "−${DedunWidgetProvider.formatLkr(Math.abs(netCashFlow))} today"
                    }
                    views.setTextViewText(R.id.tv_small_net_cash_flow, formattedFlow)
                    views.setTextColor(
                        R.id.tv_small_net_cash_flow,
                        if (netCashFlow >= 0) 0xFF728C69.toInt() else 0xFFD95F5F.toInt()
                    )

                    // Smaller secondary section: Safe to Spend
                    views.setTextViewText(
                        R.id.tv_small_safe_to_spend,
                        "Safe: ${DedunWidgetProvider.formatLkr(safeToSpend)}"
                    )
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, DedunWidgetSmallProvider::class.java))
            if (ids.isNotEmpty()) {
                updateAppWidgets(context, appWidgetManager, ids)
            }
        }
    }
}
