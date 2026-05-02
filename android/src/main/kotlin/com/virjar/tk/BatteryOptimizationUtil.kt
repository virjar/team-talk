package com.virjar.tk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.virjar.tk.util.AppLog

/**
 * Requests the system to add this app to the battery optimization whitelist.
 * Only prompts the user once per installation.
 */
fun requestBatteryOptimizationWhitelist(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return

    val prefs = context.getSharedPreferences("teamtalk_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean("battery_opt_prompted", false)) return
    prefs.edit().putBoolean("battery_opt_prompted", true).apply()

    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        AppLog.w("BatteryOpt", "Failed to request battery optimization whitelist", e)
    }
}
