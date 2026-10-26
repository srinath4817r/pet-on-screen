package com.poringpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView

class DynamicBarService : Service() {
    companion object {
        @Volatile var running = false
            private set
        @Volatile private var manager: WindowManager? = null
        @Volatile private var params: WindowManager.LayoutParams? = null
        @Volatile private var view: WebView? = null
        @Synchronized fun resize(widthDp: Int, heightDp: Int) {
            val target = view ?: return
            Handler(Looper.getMainLooper()).post {
                val wm = manager ?: return@post
                val lp = params ?: return@post
                if (view !== target) return@post
                val density = target.resources.displayMetrics.density
                val screenWidthDp = (target.resources.displayMetrics.widthPixels / density).toInt()
                lp.width = (widthDp.coerceAtMost(screenWidthDp - 8) * density).toInt().coerceAtLeast(220)
                lp.height = (heightDp * density).toInt().coerceAtLeast(72)
                try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
            }
        }
    }

    private var web: WebView? = null
    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val channel = "poring-dynamic-bar"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Poring Dynamic Bar", NotificationManager.IMPORTANCE_LOW))
        val notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, channel).setContentTitle("Poring Dynamic Bar is active").setSmallIcon(R.drawable.ic_poring_tile).build()
        else Notification.Builder(this).setContentTitle("Poring Dynamic Bar is active").setSmallIcon(R.drawable.ic_poring_tile).build()
        startForeground(8, notification)
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        DynamicBarService.manager = wm
        running = true
        web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            PoringWebViews.add(this)
            addJavascriptInterface(PoringBridge(this@DynamicBarService), "PoringBridge")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("document.body.classList.add('mobile-app','native','mobile-overlay');window.setupMobile&&window.setupMobile();window.syncCapsuleBounds&&window.syncCapsuleBounds()", null)
                }
            }
            loadUrl("file:///android_asset/poring-capsule.html?mode=overlay")
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val density = resources.displayMetrics.density
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else (24 * density).toInt()
        val lp = WindowManager.LayoutParams((320 * density).toInt(), (110 * density).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBar + (4 * density).toInt()
        }
        DynamicBarService.params = lp
        DynamicBarService.view = web
        wm.addView(web, lp)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() {
        running = false
        val old = web
        web = null
        DynamicBarService.view = null
        DynamicBarService.params = null
        val wm = DynamicBarService.manager
        DynamicBarService.manager = null
        old?.let { PoringWebViews.remove(it); try { wm?.removeView(it) } catch (_: Exception) {}; it.destroy() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
