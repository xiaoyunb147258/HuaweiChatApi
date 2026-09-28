package dev.huawei2api.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.huawei2api.App

/**
 * 悬浮球保活。作用有两个：
 * 1. 把进程提到前台可见优先级，降低被系统回收概率
 * 2. 提供一眼可见的运行状态和快捷启停入口（长按退出）
 */
class FloatService : Service() {

    private var wm: WindowManager? = null
    private var ball: View? = null
    private var label: TextView? = null
    private lateinit var lp: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 必须：由 startForegroundService 启动的服务，5 秒内不调 startForeground 会被系统判定超时并崩溃
        startForegroundCompat()
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) { stopSelf(); return START_NOT_STICKY }
        if (ball == null) showBall()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, App.CH_GW) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setContentTitle("悬浮球已开启")
            .setContentText("拖动移动 · 点击启停网关 · 长按隐藏")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, n)
        }
        Logs.add("悬浮球前台服务已就绪")
    }

    private fun showBall() {
        if (ball != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(App.prefs.floatSize_)

        val root = FrameLayout(this)
        val dot = object : View(this@FloatService) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB") }
            override fun onDraw(canvas: Canvas) {
                val r = (minOf(width, height) / 2f) - dp(2)
                canvas.drawCircle(width / 2f, height / 2f, r, p)
            }
        }
        root.addView(dot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        label = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 9f
            gravity = Gravity.CENTER
        }
        root.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = App.prefs.floatX_
            y = App.prefs.floatY_
        }

        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        var downAt = 0L

        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                    wm?.updateViewLayout(root, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    App.prefs.floatX_ = lp.x
                    App.prefs.floatY_ = lp.y
                    val held = System.currentTimeMillis() - downAt
                    when {
                        moved -> {}                       // 拖动结束，不做动作
                        held > 800 -> {                   // 长按：关闭悬浮球
                            App.prefs.floatOn = false
                            stopSelf()
                        }
                        else -> toggle()                  // 短按：切换网关启停
                    }
                    true
                }
                else -> false
            }
        }

        // 长按监听（独立于拖动逻辑，避免与 move 冲突）
        root.setOnLongClickListener {
            stopSelf(); true
        }

        try {
            wm?.addView(root, lp)
            ball = root
        } catch (e: Exception) {
            Logs.add("悬浮球失败: ${e.message}")
        }
    }

    private fun toggle() {
        if (GatewayService.isRunning()) GatewayService.stop(this)
        else GatewayService.start(this)
        ball?.postDelayed({ refresh() }, 600)
    }

    /** 刷新悬浮球文字：运行中显示端口，停止显示「关」 */
    fun refresh() {
        val running = GatewayService.isRunning()
        val port = App.prefs.port
        label?.post { label?.text = if (running) port.toString() else "关" }
    }

    private fun hideBall() {
        ball?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        ball = null
    }

    override fun onDestroy() {
        hideBall()
        instance = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_HIDE = "dev.huawei2api.FLOAT_HIDE"

        @Volatile
        var instance: FloatService? = null
            private set

        fun show(ctx: android.content.Context) {
            val i = Intent(ctx, FloatService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun hide(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, FloatService::class.java))
        }
    }
}
