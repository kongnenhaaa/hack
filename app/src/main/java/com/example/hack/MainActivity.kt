package com.example.hack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvTerminalLog: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var rvApps: RecyclerView
    private lateinit var tvAppCount: TextView
    private lateinit var tvIpConfig: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSettings: android.widget.ImageView
    private lateinit var btnClearLog: android.widget.ImageView
    private lateinit var fabAdd: FloatingActionButton

    private lateinit var tvLogStatus: TextView

    private val allApps = mutableListOf<JSONObject>()
    private val displayApps = mutableListOf<JSONObject>()
    private lateinit var adapter: AppAdapter

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val logMessage = intent?.getStringExtra("log") ?: return
            appendLog(logMessage)
        }
    }

    private val tokenCapturedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Cancel timeout
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            
            runOnUiThread {
                tvLogStatus.text = "● SUCCESS"
                tvLogStatus.setTextColor(android.graphics.Color.parseColor("#00E676"))
                Toast.makeText(this@MainActivity, "Lấy Token & Gửi thành công!", Toast.LENGTH_LONG).show()
            }
            appendLog("[SUCCESS] Bắt được Token! Đang đóng Zalo và quay lại ứng dụng...")
            Thread {
                try {
                    // Force stop Zalo
                    val stopProcess = Runtime.getRuntime().exec("su")
                    val osStop = java.io.DataOutputStream(stopProcess.outputStream)
                    osStop.writeBytes("am force-stop com.zing.zalo\n")
                    osStop.writeBytes("exit\n")
                    osStop.flush()
                    val completed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        stopProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    } else {
                        stopProcess.waitFor()
                        true
                    }
                    if (!completed) {
                        stopProcess.destroy()
                    }
                    
                    // Launch MainActivity back to foreground
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error returning to app", e)
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTerminalLog = findViewById(R.id.tvTerminalLog)
        logScrollView = findViewById(R.id.logScrollView)
        rvApps = findViewById(R.id.rvApps)
        tvAppCount = findViewById(R.id.tvAppCount)
        tvIpConfig = findViewById(R.id.tvIpConfig)
        etSearch = findViewById(R.id.etSearch)
        btnSettings = findViewById(R.id.btnSettings)
        btnClearLog = findViewById(R.id.btnClearLog)
        fabAdd = findViewById(R.id.fabAdd)
        tvLogStatus = findViewById(R.id.tvLogStatus)

        rvApps.layoutManager = LinearLayoutManager(this)

        // Load apps list
        loadApps()

        adapter = AppAdapter(
            displayApps,
            onTriggerClick = { app ->
                val appId = app.optString("app_id")
                val name = app.optString("name")
                appendLog("[INFO] [$name] Đang gọi lệnh Root Trigger...")
                
                // Cancel existing timeout if any
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                
                tvLogStatus.text = "● RUNNING"
                tvLogStatus.setTextColor(android.graphics.Color.parseColor("#FFEA00"))
                
                // Set timeout for 20 seconds
                val runnable = Runnable {
                    if (tvLogStatus.text == "● RUNNING") {
                        tvLogStatus.text = "● TIMEOUT"
                        tvLogStatus.setTextColor(android.graphics.Color.parseColor("#FF9100"))
                        appendLog("[TIMEOUT] Quá 20 giây không bắt được token. Đang đóng Zalo...")
                        Thread {
                            try {
                                val stopProcess = Runtime.getRuntime().exec("su")
                                val osStop = java.io.DataOutputStream(stopProcess.outputStream)
                                osStop.writeBytes("am force-stop com.zing.zalo\n")
                                osStop.writeBytes("exit\n")
                                osStop.flush()
                                val completed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    stopProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                                } else {
                                    stopProcess.waitFor()
                                    true
                                }
                                if (!completed) {
                                    stopProcess.destroy()
                                }
                            } catch (_: Exception) {}
                        }.start()
                    }
                }
                timeoutRunnable = runnable
                mainHandler.postDelayed(runnable, 20000)
                
                triggerAppRoot(appId)
            },
            onEditClick = { app ->
                showEditAppDialog(app)
            },
            onDeleteClick = { app ->
                showDeleteAppDialog(app)
            }
        )
        rvApps.adapter = adapter

        // Setup search filter
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Request external storage permissions
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val hasWritePermission = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasWritePermission) {
                requestPermissions(arrayOf(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ), 1)
            }
        }

        // Load config file to show current Webhook URL
        refreshConfigDisplay()

        // Settings Button: change Webhook IP/URL
        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Clear log console
        btnClearLog.setOnClickListener {
            tvTerminalLog.text = "> Logs cleared."
        }

        // Add App FAB button
        fabAdd.setOnClickListener {
            showAddAppDialog()
        }

        // Register receivers to run even in background
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("com.autopee.LOG_EVENT"), Context.RECEIVER_EXPORTED)
            registerReceiver(tokenCapturedReceiver, IntentFilter("com.autopee.TOKEN_CAPTURED"), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("com.autopee.LOG_EVENT"))
            registerReceiver(tokenCapturedReceiver, IntentFilter("com.autopee.TOKEN_CAPTURED"))
        }
        
        intent?.let { handleIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val triggerAppId = intent.getStringExtra("trigger_appid")
        if (!triggerAppId.isNullOrEmpty()) {
            val name = getAppName(triggerAppId)
            appendLog("[INFO] [$name] Triggering via Intent AppId: $triggerAppId")
            
            // Cancel existing timeout if any
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            
            tvLogStatus.text = "● RUNNING"
            tvLogStatus.setTextColor(android.graphics.Color.parseColor("#FFEA00"))
            
            // Set timeout for 20 seconds
            val runnable = Runnable {
                if (tvLogStatus.text == "● RUNNING") {
                    tvLogStatus.text = "● TIMEOUT"
                    tvLogStatus.setTextColor(android.graphics.Color.parseColor("#FF9100"))
                    appendLog("[TIMEOUT] Quá 20 giây không bắt được token. Đang đóng Zalo...")
                    Thread {
                        try {
                            val stopProcess = Runtime.getRuntime().exec("su")
                            val osStop = java.io.DataOutputStream(stopProcess.outputStream)
                            osStop.writeBytes("am force-stop com.zing.zalo\n")
                            osStop.writeBytes("exit\n")
                            osStop.flush()
                            val completed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                stopProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                            } else {
                                stopProcess.waitFor()
                                    true
                            }
                            if (!completed) {
                                stopProcess.destroy()
                            }
                        } catch (_: Exception) {}
                    }.start()
                }
            }
            timeoutRunnable = runnable
            mainHandler.postDelayed(runnable, 20000)
            
            triggerAppRoot(triggerAppId)
        }
    }

    private fun getAppName(appId: String): String {
        for (app in allApps) {
            if (app.optString("app_id") == appId) {
                return app.optString("name")
            }
        }
        return "Unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(tokenCapturedReceiver)
        } catch (_: Exception) {}
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentText = tvTerminalLog.text.toString()
        tvTerminalLog.text = "$currentText\n$time $msg"
        
        // Auto scroll to bottom
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun loadApps() {
        val prefs = getSharedPreferences("autopee_prefs", Context.MODE_PRIVATE)
        val savedAppsJson = prefs.getString("saved_apps", null)
        
        allApps.clear()
        if (savedAppsJson.isNullOrEmpty()) {
            // Initialize with default apps: 7up and Coca
            val defaultApps = """
            [
                {"name": "7up", "app_id": "3151270984274302494", "deeplink": "zalo://zaloapp.com/qr/s/3151270984274302494/"},
                {"name": "Coca", "app_id": "2284259347200678918", "deeplink": "zalo://zaloapp.com/qr/s/2284259347200678918/"}
            ]
            """.trimIndent()
            try {
                val array = JSONArray(defaultApps)
                for (i in 0 until array.length()) {
                    allApps.add(array.getJSONObject(i))
                }
                saveApps()
            } catch (e: Exception) {}
        } else {
            try {
                val array = JSONArray(savedAppsJson)
                for (i in 0 until array.length()) {
                    allApps.add(array.getJSONObject(i))
                }
            } catch (e: Exception) {}
        }
        
        displayApps.clear()
        displayApps.addAll(allApps)
        tvAppCount.text = "${allApps.size} apps"
        saveApps()
    }

    private fun saveApps() {
        val prefs = getSharedPreferences("autopee_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        val simpleMapping = StringBuilder()
        for (app in allApps) {
            array.put(app)
            val id = app.optString("app_id")
            val name = app.optString("name")
            if (id.isNotEmpty() && name.isNotEmpty()) {
                simpleMapping.append("$id:$name\n")
            }
        }
        prefs.edit().putString("saved_apps", array.toString()).apply()
        tvAppCount.text = "${allApps.size} apps"

        // Write to public file so ZaloHooker can resolve app names from appIds
        Thread {
            try {
                val appsFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "zalo_hacker_apps.txt")
                appsFile.writeText(simpleMapping.toString())
            } catch (_: Exception) {}
        }.start()
    }

    private fun filterApps(query: String) {
        displayApps.clear()
        if (query.isEmpty()) {
            displayApps.addAll(allApps)
        } else {
            val q = query.lowercase(Locale.getDefault())
            for (app in allApps) {
                val name = app.optString("name").lowercase(Locale.getDefault())
                val id = app.optString("app_id").lowercase(Locale.getDefault())
                if (name.contains(q) || id.contains(q)) {
                    displayApps.add(app)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun refreshConfigDisplay() {
        try {
            val configFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "zalo_hacker_config.txt")
            if (configFile.exists()) {
                val url = configFile.readText().trim()
                tvIpConfig.text = url.replace("http://", "").replace("/token", "")
            } else {
                tvIpConfig.text = "192.168.29.108:5000"
            }
        } catch (e: Exception) {
            tvIpConfig.text = "Error config"
        }
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Configure Server Webhook")
        
        val input = EditText(this)
        input.hint = "http://192.168.29.108:5000/token"
        
        // Load current config to prefill
        try {
            val configFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "zalo_hacker_config.txt")
            if (configFile.exists()) {
                input.setText(configFile.readText().trim())
            } else {
                input.setText("http://192.168.29.108:5000/token")
            }
        } catch (_: Exception) {}
        
        builder.setView(input)
        builder.setPositiveButton("Save") { dialog, _ ->
            val newUrl = input.text.toString().trim()
            if (newUrl.isNotEmpty() && newUrl.startsWith("http")) {
                Thread {
                    try {
                        val configFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "zalo_hacker_config.txt")
                        configFile.writeText(newUrl)
                        runOnUiThread {
                            refreshConfigDisplay()
                            Toast.makeText(this, "Saved webhook URL successfully!", Toast.LENGTH_SHORT).show()
                            appendLog("[CONFIG] Updated server URL: $newUrl")
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "Error saving config: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            } else {
                Toast.makeText(this, "Invalid URL format!", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    private fun showAddAppDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add New Mini App")

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_app_fields, null)
        val etAppName = view.findViewById<EditText>(R.id.etDialogAppName)
        val etAppId = view.findViewById<EditText>(R.id.etDialogAppId)

        builder.setView(view)
        builder.setPositiveButton("Add") { dialog, _ ->
            val name = etAppName.text.toString().trim()
            val appId = etAppId.text.toString().trim()

            if (name.isNotEmpty() && appId.isNotEmpty()) {
                val newApp = JSONObject().apply {
                    put("name", name)
                    put("app_id", appId)
                    put("deeplink", "zalo://zaloapp.com/qr/s/$appId/")
                }
                allApps.add(newApp)
                saveApps()
                filterApps(etSearch.text.toString())
                Toast.makeText(this, "Mini App '$name' added successfully!", Toast.LENGTH_SHORT).show()
                appendLog("[INFO] Added mini app: $name ($appId)")
            } else {
                Toast.makeText(this, "Please enter all fields!", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    private fun showEditAppDialog(app: JSONObject) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Edit Mini App")

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_app_fields, null)
        val etAppName = view.findViewById<EditText>(R.id.etDialogAppName)
        val etAppId = view.findViewById<EditText>(R.id.etDialogAppId)

        etAppName.setText(app.optString("name"))
        etAppId.setText(app.optString("app_id"))

        builder.setView(view)
        builder.setPositiveButton("Save") { dialog, _ ->
            val newName = etAppName.text.toString().trim()
            val newAppId = etAppId.text.toString().trim()

            if (newName.isNotEmpty() && newAppId.isNotEmpty()) {
                try {
                    app.put("name", newName)
                    app.put("app_id", newAppId)
                    app.put("deeplink", "zalo://zaloapp.com/qr/s/$newAppId/")
                    
                    saveApps()
                    filterApps(etSearch.text.toString())
                    Toast.makeText(this, "Mini App updated!", Toast.LENGTH_SHORT).show()
                    appendLog("[INFO] Updated app: $newName ($newAppId)")
                } catch (e: Exception) {}
            } else {
                Toast.makeText(this, "Fields cannot be empty!", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    private fun showDeleteAppDialog(app: JSONObject) {
        val name = app.optString("name", "Unknown")
        AlertDialog.Builder(this)
            .setTitle("Delete Mini App")
            .setMessage("Are you sure you want to delete '$name'?")
            .setPositiveButton("Delete") { dialog, _ ->
                allApps.remove(app)
                saveApps()
                filterApps(etSearch.text.toString())
                Toast.makeText(this, "Mini App deleted!", Toast.LENGTH_SHORT).show()
                appendLog("[INFO] Deleted app: $name")
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun triggerAppRoot(appId: String) {
        val prefs = getSharedPreferences("autopee_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("pending_trigger_appid", appId).apply()

        Thread {
            try {
                // Thoát Zalo
                val stopProcess = Runtime.getRuntime().exec("su")
                val osStop = java.io.DataOutputStream(stopProcess.outputStream)
                osStop.writeBytes("am force-stop com.zing.zalo\n")
                osStop.writeBytes("exit\n")
                osStop.flush()
                
                val completed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    stopProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                } else {
                    stopProcess.waitFor()
                    true
                }
                
                if (!completed) {
                    stopProcess.destroy()
                    runOnUiThread {
                        appendLog("[WARN] Không lấy được quyền Root (su). Vui lòng cấp quyền Root cho app com.example.hack trong Magisk/KernelSU!")
                    }
                }
                
                Thread.sleep(1000)

                // Mở Zalo launcher/main activity
                val pm = packageManager
                val zaloMain = pm.getLaunchIntentForPackage("com.zing.zalo")
                if (zaloMain != null) {
                    zaloMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(zaloMain)
                    runOnUiThread {
                        appendLog("[OK] Đã gửi lệnh force-stop và khởi động lại Zalo.")
                    }
                } else {
                    runOnUiThread {
                        appendLog("[WARN] Không tìm thấy ứng dụng Zalo.")
                        tvLogStatus.text = "● ERROR"
                        tvLogStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("[WARN] Lỗi Root Trigger: ${e.message}")
                    tvLogStatus.text = "● ERROR"
                    tvLogStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
                }
            }
        }.start()
    }
}