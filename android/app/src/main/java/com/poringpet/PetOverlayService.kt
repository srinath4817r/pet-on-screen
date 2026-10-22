package com.poringpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView

class PetOverlayService : Service() {
    companion object { @Volatile var running = false }
    private var window: WindowManager? = null
    private var pet: WebView? = null
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val channel = "poring-pet-overlay"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Poring Pet", NotificationManager.IMPORTANCE_LOW))
        val note = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel).setContentTitle("Poring Pet is running").setSmallIcon(android.R.drawable.star_on).build()
                   else Notification.Builder(this).setContentTitle("Poring Pet is running").setSmallIcon(android.R.drawable.star_on).build()
        startForeground(7, note)
        running = true
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        pet = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            PoringWebViews.add(this)
            addJavascriptInterface(PoringBridge(this@PetOverlayService), "PoringBridge")
            loadUrl("file:///android_asset/poring-capsule.html?mode=desktop")
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(300, 250, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        window?.addView(pet, params)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { running = false; pet?.let { PoringWebViews.remove(it); window?.removeView(it); it.destroy() }; pet = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
