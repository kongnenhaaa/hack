package com.example.hack.hook

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainXposed : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.zing.zalo") return

        Log.d("ZaloHacker", "✅ Injecting into Zalo: ${lpparam.packageName}")

        try { ZaloHooker.hookOkHttp(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "OkHttp failed", e) }
        try { ZaloHooker.hookWebView(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "WebView failed", e) }
        try { ZaloHooker.hookSharedPreferences(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "SharedPrefs failed", e) }
        try { ZaloHooker.hookHttpUrlConnection(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "HttpURLConn failed", e) }
        try { ZaloHooker.hookJavaScriptInterface(lpparam.classLoader) } catch (e: Exception) { Log.e("ZaloHacker", "JSInterface failed", e) }

        Log.d("ZaloHacker", "✅ All hooks installed for Zalo v26.05.01")
    }
}

