package dev.huawei2api

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_GW, "网关服务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "本地 OpenAI 兼容网关运行状态"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val CH_GW = "gateway"
        lateinit var instance: App
            private set
        lateinit var prefs: Prefs
            private set
    }
}
