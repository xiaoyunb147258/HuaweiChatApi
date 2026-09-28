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
        dev.huawei2api.service.Logs.attach(java.io.File(filesDir, "gateway.log"))
        dev.huawei2api.service.Logs.restore()
        installCrashHandler()
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

    /**
     * 崩溃日志落盘。内存日志在进程死亡时一并消失，
     * 所以闪退必须写文件，下次启动时读回来，否则永远看不到原因。
     */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(filesDir, "crash.log")
                f.appendText("\n===== ${java.util.Date()} =====\n")
                f.appendText("${t.name}: ${e.javaClass.name}: ${e.message}\n")
                f.appendText(e.stackTraceToString().take(4000) + "\n")
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 读出上次崩溃记录，供日志页展示；读完即清，避免重复刷屏 */
    fun takeCrashLog(): String? {
        val f = java.io.File(filesDir, "crash.log")
        if (!f.exists() || f.length() == 0L) return null
        val text = try { f.readText() } catch (_: Exception) { return null }
        try { f.delete() } catch (_: Exception) {}
        return text
    }

    companion object {
        const val CH_GW = "gateway"
        lateinit var instance: App
            private set
        lateinit var prefs: Prefs
            private set
    }
}
