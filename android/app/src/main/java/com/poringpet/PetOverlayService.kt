package com.poringpet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.provider.Settings
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class PetOverlayService : Service() {
    companion object {
        private const val PREFS = "poring_pet_lifecycle"
        private const val KEY_ENABLED = "enabled"
        private const val ACTION_RESTORE = "com.poringpet.action.RESTORE_FLOATING_PET"
        private const val ACTION_RESIZE = "com.poringpet.action.RESIZE_FLOATING_PET"

        private fun lifecyclePrefs(context: Context) = context.getSharedPreferences(
            PREFS, Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
        )

        @JvmStatic fun isEnabled(context: Context): Boolean =
            lifecyclePrefs(context).getBoolean(KEY_ENABLED, false)

        @JvmStatic fun requestResize(context: Context, width: Int, height: Int) {
            val intent = Intent(context, PetOverlayService::class.java).setAction(ACTION_RESIZE)
                .putExtra("width", width).putExtra("height", height)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        @JvmStatic fun stop(context: Context) {
            lifecyclePrefs(context).edit().putBoolean(KEY_ENABLED, false).commit()
            context.stopService(Intent(context, PetOverlayService::class.java))
        }

        @Volatile var running = false
            private set
        @Volatile private var activeWindow: WindowManager? = null
        @Volatile private var activeParams: WindowManager.LayoutParams? = null
        @Volatile private var currentPet: WebView? = null
        @Volatile private var homePackages: Set<String> = emptySet()
        private val main = Handler(Looper.getMainLooper())
        private val savePosition = Runnable {
            val view = currentPet ?: return@Runnable
            val params = activeParams ?: return@Runnable
            val density = view.resources.displayMetrics.density
            view.context.getSharedPreferences("poring_pet_position", Context.MODE_PRIVATE).edit()
                .putBoolean("set", true).putInt("x", (params.x / density).toInt())
                .putInt("y", (params.y / density).toInt()).apply()
        }

        @JvmStatic fun moveBy(dx: Float, dy: Float) = main.post {
            val manager = activeWindow ?: return@post
            val params = activeParams ?: return@post
            val view = currentPet ?: return@post
            val metrics = view.resources.displayMetrics
            params.x = (params.x + dx.toInt()).coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
            params.y = (params.y + dy.toInt()).coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
            try { manager.updateViewLayout(view, params) } catch (_: Exception) {}
            main.removeCallbacks(savePosition)
            main.postDelayed(savePosition, 350)
        }

        private fun resizeLocal(widthDp: Int, heightDp: Int) = main.post {
            val manager = activeWindow ?: return@post
            val params = activeParams ?: return@post
            val view = currentPet ?: return@post
            val density = view.resources.displayMetrics.density
            val metrics = view.resources.displayMetrics
            val newWidth = (widthDp.coerceIn(88, (metrics.widthPixels / density).toInt()) * density).toInt()
                .coerceAtMost(metrics.widthPixels).coerceAtLeast((88 * density).toInt())
            val newHeight = (heightDp.coerceIn(88, (metrics.heightPixels / density).toInt()) * density).toInt()
                .coerceAtMost(metrics.heightPixels).coerceAtLeast((88 * density).toInt())
            val centerX = params.x + params.width / 2
            params.width = newWidth
            params.height = newHeight
            params.x = (centerX - newWidth / 2).coerceIn(0, (metrics.widthPixels - newWidth).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (metrics.heightPixels - newHeight).coerceAtLeast(0))
            try { manager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
    }

    private var window: WindowManager? = null
    private var pet: WebView? = null
    private val visibilityHandler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage = ""
    private var isOnHome = false
    private var lastResumeAt = 0L
    private val visibilityCheck = object : Runnable {
        override fun run() {
            val target = pet
            val manager = window
            val params = activeParams
            if (target != null && manager != null && params != null) {
                val usage = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                try {
                    val events = usage.queryEvents(now - 2500, now)
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == 1) {
                            lastForegroundPackage = event.packageName.orEmpty()
                            lastResumeAt = event.timeStamp
                        }
                    }
                } catch (_: Exception) {}
                if (lastResumeAt == 0L || now - lastResumeAt > 20_000) try {
                    val recent = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 20_000, now)
                        .maxByOrNull { it.lastTimeUsed }
                    if (recent != null && now - recent.lastTimeUsed < 15_000) {
                        lastForegroundPackage = recent.packageName
                        lastResumeAt = recent.lastTimeUsed
                    }
                } catch (_: Exception) {}
                val home = lastForegroundPackage in homePackages
                if (home != isOnHome) {
                    isOnHome = home
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        (if (home) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    params.alpha = if (home) 1f else 0f
                    target.alpha = if (home) 1f else 0f
                    try { manager.updateViewLayout(target, params) } catch (_: Exception) {}
                }
            }
            if (running) visibilityHandler.postDelayed(this, 650)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        if (Build.VERSION.SDK_INT >= 28) {
            try { WebView.setDataDirectorySuffix("pet") } catch (_: IllegalStateException) {}
        }
        val channel = "poring-pet-overlay"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Poring Pet", NotificationManager.IMPORTANCE_LOW))
        val note = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, channel).setContentTitle("Poring Pet is floating").setSmallIcon(R.drawable.ic_poring_tile).build()
        else Notification.Builder(this).setContentTitle("Poring Pet is floating").setSmallIcon(R.drawable.ic_poring_tile).build()
        startForeground(7, note)
        lifecyclePrefs(this).edit().putBoolean(KEY_ENABLED, true).commit()
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        activeWindow = window
        running = true
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // Only the selected launcher counts as home. queryIntentActivities() also returns
        // unrelated system UI handlers on some OEM builds (including Settings on this phone).
        homePackages = setOfNotNull(packageManager.resolveActivity(
            homeIntent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName)
        try { lastForegroundPackage = (getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager)
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, System.currentTimeMillis() - 60_000, System.currentTimeMillis())
            .maxByOrNull { it.lastTimeUsed }?.packageName.orEmpty() } catch (_: Exception) {}
        isOnHome = lastForegroundPackage in homePackages
        pet = object : WebView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    evaluateJavascript("window.dismissOverlayMenu&&window.dismissOverlayMenu()", null)
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webChromeClient = WebChromeClient()
            PoringWebViews.add(this)
            addJavascriptInterface(PoringBridge(this@PetOverlayService), "PoringBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript("document.body.classList.add('mobile-app','native','mobile-overlay');window.setupMobile&&window.setupMobile();window.syncCapsuleBounds&&window.syncCapsuleBounds()", null)
                }
            }
            loadUrl("file:///android_asset/poring-capsule.html?mode=overlay")
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val density = resources.displayMetrics.density
        val metrics = resources.displayMetrics
        val width = (240 * density).toInt()
        val height = (120 * density).toInt()
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else (24 * density).toInt()
        val params = WindowManager.LayoutParams(width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = getSharedPreferences("poring_pet_position", MODE_PRIVATE)
            x = if (saved.getBoolean("set", false)) (saved.getInt("x", 0) * density).toInt()
                .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0)) else ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = if (saved.getBoolean("set", false)) (saved.getInt("y", 0) * density).toInt()
                .coerceIn(statusBar, (metrics.heightPixels - height).coerceAtLeast(statusBar)) else statusBar + (96 * density).toInt()
        }
        activeParams = params
        currentPet = pet
        pet?.alpha = if (isOnHome) 1f else 0f
        if (!isOnHome) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = 0f
        }
        window?.addView(pet, params)
        visibilityHandler.post(visibilityCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecyclePrefs(this).edit().putBoolean(KEY_ENABLED, true).commit()
        if (intent?.action == ACTION_RESIZE) resizeLocal(intent.getIntExtra("width", 240), intent.getIntExtra("height", 120))
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!lifecyclePrefs(this).getBoolean(KEY_ENABLED, false)) return
        val restart = Intent(applicationContext, PetOverlayService::class.java).setAction(ACTION_RESTORE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = if (Build.VERSION.SDK_INT >= 26)
            PendingIntent.getForegroundService(applicationContext, 7612, restart, flags)
        else PendingIntent.getService(applicationContext, 7612, restart, flags)
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, pending)
    }
    override fun onDestroy() {
        running = false
        visibilityHandler.removeCallbacks(visibilityCheck)
        val old = pet
        pet = null
        currentPet = null
        activeWindow = null
        activeParams = null
        homePackages = emptySet()
        old?.let { PoringWebViews.remove(it); try { window?.removeView(it) } catch (_: Exception) {}; it.destroy() }
        window = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
