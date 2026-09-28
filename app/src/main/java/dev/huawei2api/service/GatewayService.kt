package dev.huawei2api.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.huawei2api.App
import dev.huawei2api.gateway.GatewayServer
import dev.huawei2api.ui.MainActivity

class GatewayService : Service() {

    private var server: GatewayServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startGateway()
        }
        return START_STICKY
    }

    private fun startGateway() {
        if (server?.isRunning == true) return
        startForegroundCompat()
        val p = App.prefs
        val s = GatewayServer(p.port, p.lanAccess, p.apiKey) { msg ->
            Logs.add(msg)
            instance?.onLog?.invoke(msg)
        }
        try {
            s.start()
            server = s
            val host = if (p.lanAccess) "0.0.0.0" else "127.0.0.1"
            Logs.add("网关启动 $host:${p.port}")
            Logs.add("本机地址 http://127.0.0.1:${p.port}/v1")
            if (p.lanAccess) {
                localIpv4()?.let { Logs.add("局域网 http://$it:${p.port}/v1") }
            }
            Logs.add("API Key ${if (p.apiKey.isEmpty()) "未设置(不校验)" else "已设置"}")
            instance?.onState?.invoke(true)
            FloatService.instance?.refresh()
        } catch (e: Exception) {
            Logs.add("启动失败: ${e.message}")
            instance?.onState?.invoke(false)
            FloatService.instance?.refresh()
            stopSelf()
        }
    }

    /** 取本机局域网 IPv4，用于在日志里直接给出可复制的地址 */
    private fun localIpv4(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull()?.hostAddress
        } catch (e: Exception) { null }
    }

    private fun stopGateway() {
        server?.stop()
        server = null
        Logs.add("网关已停止")
        instance?.onState?.invoke(false)
        FloatService.instance?.refresh()
    }

    private fun startForegroundCompat() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, App.CH_GW) else @Suppress("DEPRECATION") Notification.Builder(this)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = builder
            .setContentTitle("码道网关运行中")
            .setContentText("本地 OpenAI 兼容服务已就绪")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.huawei2api.START"
        const val ACTION_STOP = "dev.huawei2api.STOP"

        @Volatile
        var instance: GatewayService? = null
            private set

        fun isRunning(): Boolean = instance?.server?.isRunning == true

        private fun fire(ctx: android.content.Context, action: String) {
            val i = Intent(ctx, GatewayService::class.java).setAction(action)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun start(ctx: android.content.Context) = fire(ctx, ACTION_START)
        fun stop(ctx: android.content.Context) = fire(ctx, ACTION_STOP)
    }

    var onLog: ((String) -> Unit)? = null
    var onState: ((Boolean) -> Unit)? = null
}

/** 内存日志缓冲 */
object Logs {
    private val buf = ArrayDeque<String>()
    private const val MAX = 300

    fun add(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        synchronized(buf) {
            buf.addFirst("[$t] $msg")
            while (buf.size > MAX) buf.removeLast()
        }
    }

    fun list(): List<String> = synchronized(buf) { buf.toList() }
    fun clear() = synchronized(buf) { buf.clear() }
}
