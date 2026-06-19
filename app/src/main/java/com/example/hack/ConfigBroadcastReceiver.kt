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
        const val ACTION_REQUEST = "com.autopee.REQUEST_CONFIG"
        const val ACTION_RESPONSE = "com.autopee.CONFIG_RESPONSE"
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
                val pendingTime = prefs.getLong("pending_trigger_time", 0L)
                val now = System.currentTimeMillis()

                // Keep the trigger valid for a 15-second window so multiple processes and retries can query it
                val isValidTrigger = pendingAppId != null && (now - pendingTime < 15000)
                val finalPendingAppId = if (isValidTrigger) pendingAppId else null

                if (pendingAppId != null && !isValidTrigger) {
                    // Clean up expired trigger session
                    prefs.edit().remove("pending_trigger_appid").remove("pending_trigger_time").apply()
                }

                if (finalPendingAppId != null) {
                    Log.d(TAG, "Config request from Zalo -> ${destArray.length()} app(s) [pending: $finalPendingAppId]")
                } else {
                    Log.d(TAG, "Config request from Zalo -> ${destArray.length()} app(s)")
                }
                
                val webhookUrl = prefs.getString("webhook_url", "http://192.168.29.108:5000/token")
                val response = Intent(ACTION_RESPONSE).apply {
                    `package` = "com.zing.zalo"
                    putExtra("configs", configsJson)
                    putExtra("webhookUrl", webhookUrl)
                    if (finalPendingAppId != null) {
                        putExtra("pendingAppId", finalPendingAppId)
                    }
                }
                context.sendBroadcast(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error in ConfigBroadcastReceiver", e)
            }
        }
    }
}
