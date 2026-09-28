package dev.huawei2api.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.huawei2api.App

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && App.prefs.autoStart) {
            try {
                context.startForegroundService(Intent(context, GatewayService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
