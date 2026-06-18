package com.example.hack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.io.DataOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class TokenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != "com.autopee.TOKEN_CAPTURED") return

        val token = intent.getStringExtra("access_token")
        if (token == null) {
            Log.d("TokenReceiver", "Duplicate token detected, closing Zalo...")
            handleCaptureSuccess(context)
            return
        }

        val refreshToken = intent.getStringExtra("refresh_token") ?: token
        val appId = intent.getStringExtra("app_id") ?: ""
        val source = intent.getStringExtra("source") ?: "Unknown"

        Log.d("TokenReceiver", "Token captured from Zalo ($source). Sending to server...")
        sendTokenToServer(context, token, refreshToken, appId, source)
    }

    private fun getAppName(context: Context, appId: String): String {
        val defaults = mapOf(
            "3151270984274302494" to "7up",
            "2284259347200678918" to "Coca"
        )
        if (defaults.containsKey(appId)) {
            return defaults[appId]!!
        }
        
        try {
            val prefs = context.getSharedPreferences("autopee_prefs", Context.MODE_PRIVATE)
            val savedAppsJson = prefs.getString("saved_apps", null)
            if (!savedAppsJson.isNullOrEmpty()) {
                val array = org.json.JSONArray(savedAppsJson)
                for (i in 0 until array.length()) {
                    val app = array.getJSONObject(i)
                    if (app.optString("app_id") == appId) {
                        return app.optString("name")
                    }
                }
            }
        } catch (_: Exception) {}

        return "MiniApp_$appId"
    }

    private fun sendTokenToServer(context: Context, token: String, refreshToken: String, appId: String, source: String) {
        val name = getAppName(context, appId)
        val payload = JSONObject().apply {
            put("status", true)
            put("app_id", appId)
            put("name", name)
            put("access_token", token)
            put("refresh_token", refreshToken)
            put("expires_in", 3600)
            put("captured_at", System.currentTimeMillis())
            put("source", source)
        }

        Thread {
            val prefs = context.getSharedPreferences("autopee_prefs", Context.MODE_PRIVATE)
            try {
                val targetUrl = prefs.getString("webhook_url", "http://127.0.0.1:5000/token") ?: "http://127.0.0.1:5000/token"

                val conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                val code = conn.responseCode
                Log.d("TokenReceiver", "Token sent successfully to $targetUrl (HTTP $code)")
                
                // Save state to prefs
                prefs.edit().putString("last_status", "SUCCESS").apply()

                // Broadcast success log
                try {
                    context.sendBroadcast(Intent("com.autopee.LOG_EVENT").apply {
                        setPackage(context.packageName)
                        putExtra("log", "[SUCCESS] Gửi webhook thành công ($targetUrl). HTTP $code")
                    })
                } catch (_: Exception) {}

                // Show toast or log
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Lấy Token & Gửi thành công!", Toast.LENGTH_LONG).show()
                }
                
                handleCaptureSuccess(context)
            } catch (e: Exception) {
                Log.e("TokenReceiver", "Error sending token to server", e)
                
                // Save state to prefs
                prefs.edit().putString("last_status", "ERROR").apply()

                // Broadcast error log
                try {
                    context.sendBroadcast(Intent("com.autopee.LOG_EVENT").apply {
                        setPackage(context.packageName)
                        putExtra("log", "[ERROR] Lỗi gửi token lên server: ${e.message}")
                    })
                } catch (_: Exception) {}

                handleCaptureSuccess(context)
            }
        }.start()
    }

    private fun handleCaptureSuccess(context: Context) {
        Thread {
            try {
                // Force stop Zalo and launch MainActivity in one root shell to bypass background start restriction
                val stopProcess = Runtime.getRuntime().exec("su")
                val osStop = DataOutputStream(stopProcess.outputStream)
                osStop.writeBytes("am force-stop com.zing.zalo\n")
                osStop.writeBytes("am start -n com.example.hack/.MainActivity\n")
                osStop.writeBytes("exit\n")
                osStop.flush()
                stopProcess.waitFor()
            } catch (e: Exception) {
                Log.e("TokenReceiver", "Error returning to app", e)
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    }
                } catch (ex: Exception) {
                    Log.e("TokenReceiver", "Fallback launch failed", ex)
                }
            }
        }.start()
    }
}
