package com.poringpet

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

data class LiveNotification(val title: String, val subtitle: String, val time: Long, val packageName: String)

object NotificationCache {
    private val rows = CopyOnWriteArrayList<LiveNotification>()
    fun add(row: LiveNotification) {
        rows.removeAll { it.packageName == row.packageName && it.title == row.title && it.subtitle == row.subtitle }
        rows.add(0, row)
        while (rows.size > 30) rows.removeAt(rows.lastIndex)
    }
    fun json(appId: String = ""): String = JSONArray().apply {
        val packageFilter = when (appId) { "wa" -> "com.whatsapp"; else -> appId }
        rows.filter { packageFilter.isBlank() || it.packageName == packageFilter }
            .forEach { row -> put(JSONObject().put("title", row.title).put("subtitle", row.subtitle).put("time", row.time)) }
    }.toString()
}

object PoringWebViews {
    private val views = CopyOnWriteArrayList<WebView>()
    fun add(view: WebView) { views.addIfAbsent(view) }
    fun remove(view: WebView) { views.remove(view) }
    fun publish(row: LiveNotification) {
        if (row.packageName != "com.whatsapp") return
        val event = JSONObject().put("app", "wa").put("title", row.title).put("subtitle", row.subtitle)
        views.forEach { view -> view.post {
            view.evaluateJavascript("window.dispatchEvent(new CustomEvent('poring-notification',{detail:$event}))", null)
        } }
    }
}

class PoringNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.take(200).orEmpty()
        val text = (extras.getCharSequence("android.bigText") ?: extras.getCharSequence("android.text"))?.toString()?.take(500).orEmpty()
        if (title.isNotBlank() || text.isNotBlank()) {
            val row = LiveNotification(title, text, sbn.postTime, sbn.packageName)
            NotificationCache.add(row)
            PoringWebViews.publish(row)
        }
    }
}
