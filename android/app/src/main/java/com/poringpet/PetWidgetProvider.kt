package com.poringpet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PetWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        ids.forEach { id ->
            val text = if (level >= 0) "Poring Pet · $level% · ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}" else "Poring Pet · Battery unavailable"
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.pet_widget).apply { setTextViewText(R.id.pet_widget_text, text) })
        }
    }
}
