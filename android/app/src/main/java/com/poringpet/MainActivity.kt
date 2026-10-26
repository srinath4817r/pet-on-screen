package com.poringpet

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.app.ActivityManager
import android.os.StatFs
import android.net.TrafficStats
import android.os.Environment
import android.media.AudioManager
import android.provider.Settings.System as SystemSettings
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = WebChromeClient()
            PoringWebViews.add(this)
            addJavascriptInterface(PoringBridge(this@MainActivity), "PoringBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript("document.body.classList.add('mobile-app');window.setupMobile&&window.setupMobile()", null)
                }
            }
            loadUrl("file:///android_asset/poring-capsule.html")
        }
        setContentView(web)
        if (intent?.getBooleanExtra("request_weather_permission", false) == true) {
            web.postDelayed({ PoringBridge(this).requestWeatherPermission() }, 700)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 44 && ::web.isInitialized) {
            web.evaluateJavascript("window.mobileWeatherPermissionChanged&&window.mobileWeatherPermissionChanged()", null)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("request_weather_permission", false) == true) {
            web.postDelayed({ PoringBridge(this).requestWeatherPermission() }, 450)
        }
    }

    override fun onDestroy() { PoringWebViews.remove(web); web.destroy(); super.onDestroy() }

}

class PoringBridge(private val context: Context) {
    private val nearbyBluetooth = ConcurrentHashMap<String, String>()
    private var lastBluetoothScan = 0L
    private var lastWifiScan = 0L
    private var activeScanner: BluetoothLeScanner? = null
    @JavascriptInterface fun getTrafficBytes(): String = JSONObject()
        .put("rx", TrafficStats.getTotalRxBytes()).put("tx", TrafficStats.getTotalTxBytes()).toString()
    @JavascriptInterface fun getNextAlarmTime(): Long = try {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime ?: 0L
    } catch (_: Exception) { 0L }
    @JavascriptInterface fun getDndFilter(): Int = try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.isNotificationPolicyAccessGranted) manager.currentInterruptionFilter else -1
    } catch (_: Exception) { -1 }
    @JavascriptInterface fun setPetTimerEnd(endTime: Long) {
        context.getSharedPreferences("poring_pet_live", Context.MODE_PRIVATE).edit().putLong("timer_end", endTime).apply()
    }
    @JavascriptInterface fun getPetTimerEnd(): Long = context.getSharedPreferences("poring_pet_live", Context.MODE_PRIVATE).getLong("timer_end", 0L)
    private val scanHandler = Handler(Looper.getMainLooper())
    @JavascriptInterface fun getMobileConfig(): String = context.getSharedPreferences("poring_mobile_settings", Context.MODE_PRIVATE)
        .getString("config", "{}").orEmpty()
    @JavascriptInterface fun saveMobileConfig(config: String): Boolean {
        if (config.length > 20_000) return false
        return try {
            val parsed = JSONObject(config)
            context.getSharedPreferences("poring_mobile_settings", Context.MODE_PRIVATE).edit().putString("config", parsed.toString()).apply()
            true
        } catch (_: Exception) { false }
    }
    @JavascriptInterface fun dynamicBarRunning(): Boolean = SystemDynamicBarService.running
    @JavascriptInterface fun startDynamicBar(): Boolean {
        val activity = context as? Activity ?: return false
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.runOnUiThread {
                android.app.AlertDialog.Builder(activity).setTitle("Allow Poring Pet notifications")
                    .setMessage("XOS can show Poring Pet in Dynamic Bar through an ongoing notification. The phone controls how it looks there.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Allow notifications") { _, _ -> activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43) }.show()
            }
            return false
        }
        if (!context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
            activity.runOnUiThread {
                android.app.AlertDialog.Builder(activity).setTitle("Enable Poring Pet notifications")
                    .setMessage("Enable notifications for Poring Pet, then turn on More Services for it in XOS Dynamic Bar settings.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open notification settings") { _, _ -> activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }.show()
            }
            return false
        }
        return try {
            context.stopService(Intent(context, DynamicBarService::class.java))
            val intent = Intent(context, SystemDynamicBarService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            true
        } catch (_: Exception) { false }
    }
    @JavascriptInterface fun stopDynamicBar() {
        context.stopService(Intent(context, SystemDynamicBarService::class.java))
        context.stopService(Intent(context, DynamicBarService::class.java))
    }
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (android.os.Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val name = result.device.name?.trim().orEmpty()
            if (name.isNotBlank()) nearbyBluetooth[result.device.address] = name
        }
    }
    @JavascriptInterface fun getInstalledApps(): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        val apps = JSONArray()
        pm.queryIntentActivities(intent, 0).map { it.activityInfo }.distinctBy { it.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }.forEach { info ->
                apps.put(JSONObject().put("name", info.loadLabel(pm).toString()).put("package", info.packageName))
            }
        return apps.toString()
    }
    @JavascriptInterface fun launchApp(packageName: String): Boolean {
        if (packageName.isBlank() || packageName.length > 255) return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) { false }
    }
    @JavascriptInterface fun getDeviceStats(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memory)
        val stat = StatFs(Environment.getDataDirectory().path)
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            try { (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus.toString() } catch (_: Exception) { "unsupported" }
        } else "unsupported"
        return JSONObject().put("ramTotal", memory.totalMem).put("ramAvailable", memory.availMem)
            .put("storageTotal", stat.totalBytes).put("storageAvailable", stat.availableBytes)
            .put("thermalStatus", thermal).put("processors", Runtime.getRuntime().availableProcessors()).toString()
    }
    @JavascriptInterface fun getDisplaySound(): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val brightness = try { SystemSettings.getInt(context.contentResolver, SystemSettings.SCREEN_BRIGHTNESS) } catch (_: Exception) { -1 }
        return JSONObject().put("brightness", brightness).put("volume", volume).put("volumeMax", max).toString()
    }
    @JavascriptInterface fun openDeviceSettings(section: String) {
        val action = when(section) {
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "wifi" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "usage" -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launch(Intent(action))
    }
    @JavascriptInterface fun resizeCapsule(width: Int, height: Int) {
        if (PetOverlayService.isEnabled(context)) PetOverlayService.requestResize(context, width, height) else DynamicBarService.resize(width, height)
    }
    @JavascriptInterface fun floatingPetRunning(): Boolean = PetOverlayService.isEnabled(context)
    @JavascriptInterface fun getPetLook(): String = context.getSharedPreferences("poring_pet_look", Context.MODE_PRIVATE)
        .getString("look", "{}").orEmpty()
    @JavascriptInterface fun savePetLook(size: Int, gear: String, hat: String): Boolean {
        val gears = setOf("", "glasses", "sunglasses", "bow", "phones", "wings")
        val hats = setOf("", "flowers", "halo", "crown", "wizard", "tophat", "party", "catears", "bunny", "horns")
        if (gear !in gears || hat !in hats) return false
        return try {
            val look = JSONObject().put("pk", size.coerceIn(40, 240)).put("acc", gear).put("hat", hat)
            context.getSharedPreferences("poring_pet_look", Context.MODE_PRIVATE).edit().putString("look", look.toString()).apply()
            true
        } catch (_: Exception) { false }
    }
    @JavascriptInterface fun previewPetPosition(): Boolean {
        if (!startFloatingPet()) return false
        return try {
            Handler(Looper.getMainLooper()).postDelayed({
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(home)
            }, 450)
            true
        } catch (_: Exception) { false }
    }
    @JavascriptInterface fun weatherLocationGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface fun requestWeatherPermission(): Boolean {
        if (weatherLocationGranted()) return true
        val activity = context as? Activity
        if (activity == null) {
            return try {
                launch(Intent(context, MainActivity::class.java).putExtra("request_weather_permission", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                false
            } catch (_: Exception) { false }
        }
        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Use your location for local weather?")
                .setMessage("Poring Pet uses your approximate location to show current weather. Your location is only used for this weather request.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Allow location") { _, _ ->
                    if (android.os.Build.VERSION.SDK_INT >= 23) activity.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 44)
                }.show()
        }
        return false
    }

    @JavascriptInterface fun getCurrentWeather(): String {
        if (!weatherLocationGranted()) return JSONObject().put("ok", false).put("permission", true).toString()
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val now = System.currentTimeMillis()
            val providers = manager.getProviders(true)
            var location: Location? = providers.mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
            }.maxByOrNull { it.time }
            if (location == null || now - location.time > 15 * 60_000L) {
                val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER }
                    ?: providers.firstOrNull { it == LocationManager.GPS_PROVIDER }
                if (provider != null && android.os.Build.VERSION.SDK_INT >= 30) {
                    val latch = CountDownLatch(1)
                    val fresh = AtomicReference<Location?>(null)
                    try {
                        manager.getCurrentLocation(provider, CancellationSignal(), context.mainExecutor) {
                            fresh.set(it); latch.countDown()
                        }
                        latch.await(5, TimeUnit.SECONDS)
                        location = fresh.get() ?: location
                    } catch (_: Exception) {}
                }
            }
            if (location == null) return JSONObject().put("ok", false).put("message", "Waiting for a location fix").toString()
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,weather_code&timezone=auto")
            val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 7000 }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) throw IllegalStateException("Weather service returned ${connection.responseCode}")
            val current = JSONObject(body).getJSONObject("current")
            JSONObject().put("ok", true).put("temperature", current.getDouble("temperature_2m"))
                .put("code", current.getInt("weather_code")).toString()
        } catch (_: SecurityException) { JSONObject().put("ok", false).put("permission", true).toString() }
        catch (_: Exception) { JSONObject().put("ok", false).put("message", "Weather unavailable right now").toString() }
    }
    @JavascriptInterface fun homeOnlyAccessGranted(): Boolean {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        return try { usage.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, System.currentTimeMillis() - 60_000, System.currentTimeMillis()).isNotEmpty() }
        catch (_: Exception) { false }
    }
    @JavascriptInterface fun openHomeOnlyAccess() { launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    @JavascriptInterface fun startFloatingPet(): Boolean {
        if (!homeOnlyAccessGranted()) {
            (context as? Activity)?.runOnUiThread {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Allow home screen detection")
                    .setMessage("To keep your pet off other apps, allow Usage Access for Poring Pet in Android settings. Return here and tap Start floating pet again.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open Usage Access") { _, _ -> openHomeOnlyAccess() }
                    .show()
            }
            return false
        }
        if (!Settings.canDrawOverlays(context)) {
            (context as? Activity)?.runOnUiThread {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Allow your floating pet")
                    .setMessage("Allow Poring Pet to display over other apps, then return here and tap Start floating pet.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open permission") { _, _ -> launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }
                    .show()
            }
            return false
        }
        return try {
            val intent = Intent(context, PetOverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            true
        } catch (_: Exception) { false }
    }
    @JavascriptInterface fun stopFloatingPet() { PetOverlayService.stop(context) }
    @JavascriptInterface fun getNotifications(appId: String): String {
        if (!notificationAccessEnabled()) {
            (context as? Activity)?.runOnUiThread {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Allow notification access")
                    .setMessage("Poring Pet needs notification access to show recent notifications and current media. Notification text stays on this device in memory.")
                    .setNegativeButton("Not now", null)
                    .setPositiveButton("Open settings") { _, _ -> launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .show()
            }
            return JSONObject().put("permission", false).toString()
        }
        return NotificationCache.json(appId)
    }
    @JavascriptInterface fun getGithub(): String {
        val token = getStoredCredential("github")
        if (token.isBlank()) return JSONObject().put("note", "Connect GitHub with a read-only token").put("items", JSONArray()).toString()
        return try {
            val request: (String) -> JSONArray = { endpoint ->
                val connection = (URL("https://api.github.com$endpoint").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 7000; setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                if (connection.responseCode !in 200..299) throw IllegalStateException("GitHub: " + JSONObject(body).optString("message", connection.responseCode.toString()))
                JSONArray(body)
            }
            val notifications = request("/notifications?per_page=10")
            val issues = request("/issues?filter=assigned&state=open&per_page=10")
            val items = JSONArray()
            for (index in 0 until notifications.length()) {
                val row = notifications.getJSONObject(index); val repo = row.optJSONObject("repository")
                items.put(JSONObject().put("title", row.optJSONObject("subject")?.optString("title").orEmpty())
                    .put("subtitle", repo?.optString("full_name").orEmpty() + " · notification")
                    .put("time", row.optString("updated_at"))
                    .put("url", row.optJSONObject("subject")?.optString("url").orEmpty().replace("api.github.com/repos/", "github.com/")))
            }
            for (index in 0 until issues.length()) {
                val row = issues.getJSONObject(index)
                items.put(JSONObject().put("title", row.optString("title")).put("subtitle", "Assigned issue")
                    .put("time", row.optString("updated_at")).put("url", row.optString("html_url")))
            }
            JSONObject().put("ok", true).put("note", "Live GitHub").put("items", items).toString()
        } catch (error: Exception) { JSONObject().put("ok", false).put("note", error.message ?: "GitHub is unavailable").put("items", JSONArray()).toString() }
    }
    @JavascriptInterface fun getCredential(service: String): String = if (service in listOf("github", "vercel", "ai", "weather")) getStoredCredential(service) else ""
    @JavascriptInterface fun validateCredential(service: String, token: String): String {
        if (service !in listOf("github", "vercel") || token.length !in 8..4096) return JSONObject().put("ok", false).put("message", "Enter a valid token.").toString()
        return try {
            val endpoint = if (service == "github") "https://api.github.com/user" else "https://api.vercel.com/v2/user"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 8000
                setRequestProperty("Authorization", "Bearer $token")
                if (service == "github") { setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("X-GitHub-Api-Version", "2022-11-28") }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) JSONObject().put("ok", true).put("message", "Credential validated.").toString()
            else JSONObject().put("ok", false).put("message", try { JSONObject(body).optString("message").ifBlank { "HTTP $code" } } catch (_: Exception) { "HTTP $code" }).toString()
        } catch (e: Exception) { JSONObject().put("ok", false).put("message", e.message ?: "Connection failed.").toString() }
    }
    @JavascriptInterface fun setCredential(service: String, token: String): Boolean {
        if (service !in listOf("github", "vercel", "ai", "weather") || token.length !in 8..4096) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val packed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            context.getSharedPreferences("poring_secure", Context.MODE_PRIVATE).edit().putString(service, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
            true
        } catch (_: Exception) { false }
    }
    @JavascriptInterface fun deleteCredential(service: String): Boolean {
        if (service !in listOf("github", "vercel", "ai", "weather")) return false
        context.getSharedPreferences("poring_secure", Context.MODE_PRIVATE).edit().remove(service).apply()
        return true
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "PoringPetCredentials"
        if (!store.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generator.generateKey()
        }
        return store.getKey(alias, null) as SecretKey
    }
    private fun getStoredCredential(service: String): String {
        return try {
            val packed = Base64.decode(context.getSharedPreferences("poring_secure", Context.MODE_PRIVATE).getString(service, null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
    @SuppressLint("MissingPermission")
    @JavascriptInterface fun getNetwork(): String {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val connected = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val wifiPermission = if (android.os.Build.VERSION.SDK_INT >= 33)
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        else context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        var ssid = ""; var signal: Int? = null; var available = JSONArray()
        try {
            if (connected) {
                val info = wifi.connectionInfo
                ssid = info.ssid?.removePrefix("\"")?.removeSuffix("\"")?.takeUnless { it == "<unknown ssid>" }.orEmpty()
                signal = WifiManager.calculateSignalLevel(info.rssi, 101)
            }
            if (wifiPermission) {
                if (System.currentTimeMillis() - lastWifiScan > 30000) { wifi.startScan(); lastWifiScan = System.currentTimeMillis() }
                available = JSONArray().apply {
                    wifi.scanResults.sortedByDescending { it.level }.filter { it.SSID.isNotBlank() && it.SSID != ssid }
                        .distinctBy { it.SSID }.take(8).forEach { put(JSONObject().put("name", it.SSID).put("signal", WifiManager.calculateSignalLevel(it.level, 101))) }
                }
            }
        } catch (_: SecurityException) {}
        val result = JSONObject().put("wifi", JSONObject().put("on", wifi.isWifiEnabled).put("connected", connected)
            .put("ssid", ssid).put("signal", signal).put("available", available).put("permission", if (wifiPermission) "" else "Nearby Wi-Fi permission needed"))
        val connectPermission = android.os.Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val scanPermission = android.os.Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connectedNames = JSONArray(); val nearbyNames = JSONArray()
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            if (adapter != null && connectPermission) {
                val connectedSet = mutableSetOf<String>()
                for (device in adapter.bondedDevices) {
                    val name = device.name?.trim().orEmpty()
                    if (name.isNotBlank() && listOf(BluetoothProfile.GATT, BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
                        .any { profile -> try { manager.getConnectionState(device, profile) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false } }) connectedSet.add(name)
                }
                connectedSet.forEach(connectedNames::put)
                if (adapter.isEnabled && scanPermission && System.currentTimeMillis() - lastBluetoothScan > 15000) {
                    lastBluetoothScan = System.currentTimeMillis()
                    activeScanner = adapter.bluetoothLeScanner
                    try {
                        activeScanner?.startScan(scanCallback)
                        scanHandler.postDelayed({ try { activeScanner?.stopScan(scanCallback) } catch (_: Exception) {} }, 5000)
                    } catch (_: Exception) {}
                }
                nearbyBluetooth.values.toSet().filter { it !in connectedSet }.forEach(nearbyNames::put)
            }
            result.put("bluetooth", JSONObject().put("on", adapter?.isEnabled == true).put("known", connectPermission && scanPermission)
                .put("connected", connectedNames).put("available", nearbyNames))
        } catch (_: SecurityException) {
            result.put("bluetooth", JSONObject().put("on", false).put("known", false).put("connected", connectedNames).put("available", nearbyNames))
        }
        return result.toString()
    }
    @JavascriptInterface fun getBattery(): String {
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        return JSONObject().put("level", if (level >= 0 && scale > 0) level.toDouble() / scale else 0.0)
            .put("charging", charging).put("chargingTime", -1).put("dischargingTime", -1).toString()
    }
    @JavascriptInterface fun getMedia(): String {
        if (!notificationAccessEnabled()) return JSONObject().put("available", false).toString()
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(context, PoringNotificationListener::class.java))
            val controller = sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
            if (controller == null) JSONObject().put("available", true).put("title", "").put("artist", "").put("playing", false).toString()
            else {
                val metadata = controller.metadata
                JSONObject().put("available", true)
                    .put("title", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty())
                    .put("artist", metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
                    .put("playing", controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING).toString()
            }
        } catch (_: SecurityException) { JSONObject().put("available", false).toString() }
    }
    @JavascriptInterface fun setWifi(@Suppress("UNUSED_PARAMETER") on: Boolean) = launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
    @JavascriptInterface fun setBluetooth(@Suppress("UNUSED_PARAMETER") on: Boolean) = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    @JavascriptInterface fun openNetworkSettings(kind: String) {
        val activity = context as? Activity ?: return
        val required = when {
            kind == "bluetooth" && android.os.Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            kind == "bluetooth" -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        activity.runOnUiThread {
            if (missing.isNotEmpty()) android.app.AlertDialog.Builder(activity)
                .setTitle("Allow nearby device access")
                .setMessage(if (kind == "wifi") "Poring Pet needs nearby Wi-Fi access to show the connected network name and visible networks with signal strength." else "Poring Pet needs nearby device access to show connected Bluetooth devices and scan names.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Allow") { _, _ -> activity.requestPermissions(missing.toTypedArray(), if (kind == "wifi") 41 else 42) }
                .show()
            else launch(if (kind == "bluetooth") Intent(Settings.ACTION_BLUETOOTH_SETTINGS) else Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        }
    }
    @JavascriptInterface fun openExternal(url: String) {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme in listOf("http", "https", "mailto")) launch(Intent(Intent.ACTION_VIEW, uri))
    }
    @JavascriptInterface fun openPath(@Suppress("UNUSED_PARAMETER") path: String) {}
    @JavascriptInterface fun pickApp(): String? = null
    @JavascriptInterface fun mediaControl(command: String): Boolean {
        if (!notificationAccessEnabled() || command !in listOf("toggle", "next", "prev")) return false
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(context, PoringNotificationListener::class.java))
            val controller = sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull() ?: return false
            when (command) {
                "toggle" -> if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) controller.transportControls.pause() else controller.transportControls.play()
                "next" -> controller.transportControls.skipToNext()
                "prev" -> controller.transportControls.skipToPrevious()
            }
            true
        } catch (_: SecurityException) { false }
    }
    @JavascriptInterface fun setIgnoreMouse(@Suppress("UNUSED_PARAMETER") ignored: Boolean) {}
    @JavascriptInterface fun movePetBy(dx: Float, dy: Float) { PetOverlayService.moveBy(dx, dy) }
    @JavascriptInterface fun desktopPlay() {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            if (Settings.canDrawOverlays(context)) {
                val intent = Intent(context, PetOverlayService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } else android.app.AlertDialog.Builder(activity)
                .setTitle("Allow the floating pet")
                .setMessage("Poring Pet needs permission to appear over other apps. You can leave it off and keep using the Capsule here.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open settings") { _, _ ->
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")))
                }.show()
        }
    }
    @JavascriptInterface fun returnToCapsule() { PetOverlayService.stop(context) }
    private fun notificationAccessEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it.contains(context.packageName) }
    }
    private fun launch(intent: Intent) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent) }
}
