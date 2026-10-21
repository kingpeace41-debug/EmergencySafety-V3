package com.example.emergencysafety

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class EmergencyWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            // Widget'a tıklandığında MainActivity'yi açacak Intent
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Görünümü oluştur ve tıklama olayını bağla
            val views = RemoteViews(context.packageName, R.layout.widget_emergency)
            views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)

            // Widget'ı güncelle
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
