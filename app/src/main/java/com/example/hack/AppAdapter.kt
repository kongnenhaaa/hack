package com.example.hack

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class AppAdapter(
    private val apps: List<JSONObject>,
    private val onTriggerClick: (JSONObject) -> Unit,
    private val onEditClick: (JSONObject) -> Unit,
    private val onDeleteClick: (JSONObject) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAppInitial: TextView = view.findViewById(R.id.tvAppInitial)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvAppId: TextView = view.findViewById(R.id.tvAppId)
        val tvDeeplink: TextView = view.findViewById(R.id.tvDeeplink)
        val btnTrigger: Button = view.findViewById(R.id.btnTrigger)
        val btnEditApp: ImageView = view.findViewById(R.id.btnEditApp)
        val btnDeleteApp: ImageView = view.findViewById(R.id.btnDeleteApp)
        val btnCopyCommand: ImageView = view.findViewById(R.id.btnCopyCommand)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_card, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = apps[position]
        val name = app.optString("name", "Unknown")
        val id = app.optString("app_id", "Unknown ID")
        val deeplink = app.optString("deeplink", "")
        val cmd = "adb shell am start -a android.intent.action.VIEW -p com.zing.zalo -f 268435456 -d \"$deeplink\""

        holder.tvAppName.text = name
        holder.tvAppId.text = "ID: $id"
        holder.tvAppInitial.text = if (name.isNotEmpty()) name.substring(0, 1).uppercase() else "?"
        holder.tvDeeplink.text = cmd

        holder.btnTrigger.setOnClickListener {
            onTriggerClick(app)
        }

        holder.btnEditApp.setOnClickListener {
            onEditClick(app)
        }

        holder.btnDeleteApp.setOnClickListener {
            onDeleteClick(app)
        }

        holder.btnCopyCommand.setOnClickListener {
            val clipboard = holder.itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ADB Command", cmd)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(holder.itemView.context, "Copied ADB command to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount() = apps.size
}
