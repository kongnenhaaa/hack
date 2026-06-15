package com.example.hack.hook

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainXposed : IXposedHookLoadPackage {

    companion object {
        private const val TARGET_PACKAGE = "com.zing.zalo"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        Log.d("VDLogger", "Attaching to Zalo...")
        try {
            ZaloHooker.install(lpparam)
        } catch (e: Exception) {
            Log.e("VDLogger", "Failed to install Zalo hooks", e)
        }
    }
}
