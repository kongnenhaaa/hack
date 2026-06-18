package com.example.hack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConfigBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REQUEST = "com.example.hack.REQUEST_CONFIG"
        const val ACTION_RESPONSE = "com.example.hack.CONFIG_RESPONSE"
        private const val TAG = "ConfigReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ACTION_REQUEST == intent.action) {
            try {
                val prefs = context.getSharedPreferences("autopee_prefs", Context.MODE_PRIVATE)
                val savedAppsJson = prefs.getString("saved_apps", "[]") ?: "[]"
                
                // Parse saved apps and convert to autopee expected fields
                val srcArray = JSONArray(savedAppsJson)
                val destArray = JSONArray()
                for (i in 0 until srcArray.length()) {
                    val o = srcArray.getJSONObject(i)
                    val appId = o.optString("app_id").ifEmpty { o.optString("appId") }
                    val destObj = JSONObject().apply {
                        put("id", o.optString("id", UUID.randomUUID().toString()))
                        put("name", o.optString("name", ""))
                        put("appId", appId)
                        put("adbPrefix", o.optString("adbPrefix", "com.autopee.$appId"))
                        put("deeplink", o.optString("deeplink", "zalo://zaloapp.com/qr/s/$appId/"))
                    }
                    destArray.put(destObj)
                }
                
                val configsJson = destArray.toString()
                val pendingAppId = prefs.getString("pending_trigger_appid", null)
                
                if (pendingAppId != null) {
                    prefs.edit().remove("pending_trigger_appid").apply()
                    Log.d(TAG, "Config request from Zalo -> ${destArray.length()} app(s) [pending: $pendingAppId]")
                } else {
                    Log.d(TAG, "Config request from Zalo -> ${destArray.length()} app(s)")
                }
                
                val response = Intent(ACTION_RESPONSE).apply {
                    `package` = "com.zing.zalo"
                    putExtra("configs", configsJson)
                    if (pendingAppId != null) {
                        putExtra("pendingAppId", pendingAppId)
                    }
                }
                context.sendBroadcast(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error in ConfigBroadcastReceiver", e)
            }
        }
    }
}
