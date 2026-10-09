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

class QuickSpendWidgetProvider : AppWidgetProvider() {

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
            val themeAccent = prefs.getString("theme_accent", "#c9a66b") ?: "#c9a66b"
            val themeBg = prefs.getString("theme_bg", "#1c1b17") ?: "#1c1b17"
            val themeAccentColor = DedunWidgetProvider.parseColor(themeAccent, 0xFFC9A66B.toInt())

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_quick_spend)

                // 1. Tapping anywhere on the pill or "+" button launches QuickEntryActivity in Spend mode
                val spendIntent = QuickEntryActivity.createIntent(context, QuickEntryActivity.MODE_SPEND)
                val spendPendingIntent = PendingIntent.getActivity(
                    context,
                    appWidgetId + 50000,
                    spendIntent,
                    pendingIntentFlags
                )
                views.setOnClickPendingIntent(R.id.widget_quick_root, spendPendingIntent)
                views.setOnClickPendingIntent(R.id.btn_quick_pill, spendPendingIntent)
                views.setOnClickPendingIntent(R.id.btn_quick_add, spendPendingIntent)

                // 2. Middle text: Small uppercase label and live running total for today in bold white text
                val dailySpent = prefs.getFloat("daily_spent", 0f).toDouble()
                val formattedAmount = DedunWidgetProvider.formatLkr(Math.abs(dailySpent))

                views.setTextViewText(R.id.tv_quick_label, "TODAY · SPENT")
                views.setTextColor(R.id.tv_quick_label, themeAccentColor)
                views.setTextViewText(R.id.tv_quick_amount, formattedAmount)
                views.setTextColor(R.id.tv_quick_amount, android.graphics.Color.WHITE)

                // 3. Left side icon badge: arrow-up icon tinted with active theme accent color
                views.setInt(R.id.iv_quick_icon, "setColorFilter", themeAccentColor)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, QuickSpendWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                updateAppWidgets(context, appWidgetManager, ids)
            }
        }
    }
}
