package com.example.hack.hook

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainXposed : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.zing.zalo") return

        Log.d("ZaloHacker", "✅ Injecting into Zalo: ${lpparam.packageName}")

        // Cache Application context
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.thisObject as? android.content.Context
                        ZaloHooker.applicationContext = ctx
                        Log.d("ZaloHacker", "✅ Application context cached: $ctx")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("ZaloHacker", "Failed to hook Application.onCreate to cache context", e)
        }

        try { ZaloHooker.hookOkHttp(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "OkHttp failed", e) }
        try { ZaloHooker.hookWebView(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "WebView failed", e) }
        try { ZaloHooker.hookSharedPreferences(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "SharedPrefs failed", e) }
        // Disabled to ensure Zalo's native HTTP flow is untouched (prevents login/register issues)
        // try { ZaloHooker.hookHttpUrlConnection(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "HttpURLConn failed", e) }
        try { ZaloHooker.hookJavaScriptInterface(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "JSInterface failed", e) }

        Log.d("ZaloHacker", "✅ All hooks installed for Zalo v26.05.01")
    }
}

