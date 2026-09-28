package dev.huawei2api.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import android.app.Activity
import android.app.AlertDialog
import dev.huawei2api.App
import dev.huawei2api.gateway.Upstream
import dev.huawei2api.service.GatewayService
import dev.huawei2api.service.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : android.app.Activity() {

    private lateinit var content: FrameLayout
    private lateinit var tabs: LinearLayout
    private val scope = CoroutineScope(Dispatchers.Main)
    private val prefs by lazy { App.prefs }

    private val TAB_NAMES = arrayOf("状态", "登录", "模型", "权益", "日志")
    private var current = 0
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        selectTab(0)
        // 同步已有 Cookie
        syncCookie()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F5F9"))
        }

        // 顶部标题
        root.addView(TextView(this).apply {
            text = "码道网关"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            setPadding(dp(16), dp(16), dp(16), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "本地 OpenAI 兼容网关 · 华为云码道"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        // Tab 栏
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        TAB_NAMES.forEachIndexed { i, name ->
            tabs.addView(TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                textSize = 14f
                setPadding(0, dp(12), 0, dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(i) }
            })
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun selectTab(i: Int) {
        current = i
        for (t in 0 until tabs.childCount) {
            val tv = tabs.getChildAt(t) as TextView
            val sel = t == i
            tv.setTextColor(if (sel) Color.parseColor("#2563EB") else Color.parseColor("#64748B"))
            tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        content.removeAllViews()
        when (i) {
            0 -> content.addView(pageStatus())
            1 -> content.addView(pageLogin())
            2 -> content.addView(pageModels())
            3 -> content.addView(pageBenefit())
            4 -> content.addView(pageLogs())
        }
    }

    // ---------- 通用组件 ----------

    private fun card(title: String, body: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(12), dp(6), dp(12), dp(6))
            layoutParams = lp
            if (title.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 13f
                setTextColor(Color.parseColor("#64748B"))
                setPadding(0, 0, 0, dp(8))
            })
            addView(body)
        }
    }

    private fun scroll(vararg views: View): ScrollView {
        val sc = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(24))
        }
        views.forEach { col.addView(it) }
        sc.addView(col)
        return sc
    }

    private fun row(label: String, value: () -> String, action: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#0F172A"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = value()
                textSize = 14f
                setTextColor(Color.parseColor("#64748B"))
            })
            if (action != null && onClick != null) {
                addView(Button(this@MainActivity).apply {
                    text = action
                    textSize = 13f
                    setOnClickListener { onClick() }
                })
            }
        }
    }

    private fun bigButton(text: String, color: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        background.setTint(Color.parseColor(color))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(8), 0, 0)
        }
        setOnClickListener { onClick() }
    }

    private fun switchRow(label: String, checked: Boolean, hint: String? = null, onToggle: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Switch(this@MainActivity).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, v -> onToggle(v) }
                })
            })
            if (hint != null) addView(TextView(this@MainActivity).apply {
                text = hint
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
            })
        }
    }

    private fun editRow(label: String, value: String, hint: String, onSave: (String) -> Unit): LinearLayout {
        val et = EditText(this).apply {
            setText(value)
            textSize = 14f
            this.hint = hint
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 14f; setTextColor(Color.parseColor("#0F172A"))
            })
            addView(et)
            addView(Button(this@MainActivity).apply {
                text = "保存"; textSize = 13f
                setOnClickListener { onSave(et.text.toString().trim()) }
            })
        }
    }

    // ---------- 状态页 ----------
    private fun pageStatus(): View {
        val addr = { "http://127.0.0.1:${prefs.port}/v1" }
        val statusTv = TextView(this).apply {
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
        }
        fun refresh() {
            val running = GatewayService.isRunning()
            statusTv.text = if (running) "● 运行中" else "○ 未运行"
            statusTv.setTextColor(Color.parseColor(if (running) "#16A34A" else "#94A3B8"))
        }
        refresh()

        val card1 = card("网关状态", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusTv)
            addView(TextView(this@MainActivity).apply {
                text = "监听地址：${if (prefs.lanAccess) "0.0.0.0" else "127.0.0.1"}:${prefs.port}"
                textSize = 13f; setTextColor(Color.parseColor("#64748B")); setPadding(0, dp(6), 0, 0)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(bigButton("启动网关", "#2563EB") {
                    startGateway(); refresh(); selectTab(0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(bigButton("停止", "#DC2626") {
                    GatewayService.instance?.let {
                        startService(Intent(this@MainActivity, GatewayService::class.java).setAction(GatewayService.ACTION_STOP))
                    }
                    refresh(); selectTab(0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        })

        val card2 = card("接入地址", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = addr()
                textSize = 15f
                setTextColor(Color.parseColor("#2563EB"))
                setPadding(0, 0, 0, dp(8))
            })
            addView(Button(this@MainActivity).apply {
                text = "复制 base_url"
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", addr()))
                    toast("已复制")
                }
            })
        })

        val card3 = card("使用说明", TextView(this).apply {
            text = "在任意支持自定义 base_url 的 OpenAI 客户端中：\n" +
                "• base_url：${addr()}\n" +
                "• api_key：${if (prefs.apiKey.isEmpty()) "未设置（不校验）" else "已设置的密钥"}\n" +
                "• model：见「模型」页\n\n" +
                "首次使用请先到「登录」页登录华为云账号。"
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setLineSpacing(0f, 1.4f)
        })

        return scroll(card1, card2, pageSettings(), card3)
    }

    // ---------- 登录页 ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun pageLogin(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
        }

        val statusTv = TextView(this).apply {
            textSize = 14f
            text = if (prefs.cookie.isEmpty()) "未登录" else "已登录：${prefs.userName.ifEmpty { "已保存凭证" }}"
            setTextColor(Color.parseColor(if (prefs.cookie.isEmpty()) "#DC2626" else "#16A34A"))
        }
        col.addView(statusTv)
        col.addView(Button(this).apply {
            text = "打开华为云登录页"
            setOnClickListener { loadLogin() }
        })
        col.addView(Button(this).apply {
            text = "保存当前登录状态"
            setOnClickListener {
                syncCookie()
                toast(if (prefs.cookie.isEmpty()) "未获取到登录态" else "已保存")
                selectTab(1)
            }
        })
        col.addView(Button(this).apply {
            text = "退出登录"
            setOnClickListener {
                CookieManager.getInstance().removeAllCookies(null)
                prefs.cookie = ""; prefs.userName = ""
                toast("已退出"); selectTab(1)
            }
        })

        val hint = TextView(this).apply {
            text = "登录后 App 会保存 Cookie，网关凭此调用码道接口。凭证仅存本机。"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(hint)

        return col
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadLogin() {
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && url.contains("devcloud.cn-north-4.huaweicloud.com/chat")) {
                        syncCookie()
                    }
                }
            }
        }
        webView = wv
        wv.loadUrl("https://devcloud.cn-north-4.huaweicloud.com/chat/home")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = "← 返回"
                setOnClickListener { selectTab(1) }
            })
            addView(TextView(this@MainActivity).apply {
                text = "登录华为云后返回"
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, 0, 0)
            })
        })
        container.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        content.removeAllViews()
        content.addView(container)
    }

    private fun syncCookie() {
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie("https://devcloud.cn-north-4.huaweicloud.com") ?: ""
        if (cookies.contains("devclouddevuibjtcftk")) {
            prefs.cookie = cookies
            Logs.add("已同步登录态")
            // 拉一次用户名
            scope.launch {
                try {
                    val b = withContext(Dispatchers.IO) { Upstream { prefs.cookie }.balance() }
                    val name = JSONObject(b).optJSONObject("result")?.optString("user_name") ?: ""
                    if (name.isNotEmpty()) prefs.userName = name
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------- 模型页 ----------
    private fun pageModels(): View {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card1 = card("可用模型", list)
        scope.launch {
            try {
                val models = withContext(Dispatchers.IO) { Upstream { prefs.cookie }.listModels() }
                list.removeAllViews()
                if (models.isEmpty()) {
                    list.addView(TextView(this@MainActivity).apply { text = "未获取到模型，请确认已登录" })
                }
                models.forEach { m ->
                    val id = m["id"].toString()
                    list.addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(10), 0, dp(10))
                        addView(TextView(this@MainActivity).apply {
                            text = id
                            textSize = 15f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        if (prefs.defaultModel == id) addView(TextView(this@MainActivity).apply {
                            text = "默认"; textSize = 12f; setTextColor(Color.parseColor("#16A34A"))
                        })
                        addView(Button(this@MainActivity).apply {
                            text = "设为默认"; textSize = 12f
                            setOnClickListener {
                                prefs.defaultModel = id
                                toast("默认模型：$id")
                                selectTab(2)
                            }
                        })
                    })
                }
            } catch (e: Exception) {
                list.removeAllViews()
                list.addView(TextView(this@MainActivity).apply { text = "加载失败：${e.message}" })
            }
        }
        return scroll(card1)
    }

    // ---------- 权益页 ----------
    private fun pageBenefit(): View {
        val tv = TextView(this).apply {
            text = "加载中…"
            textSize = 14f
            setLineSpacing(0f, 1.5f)
        }
        fun load() {
            tv.text = "加载中…"
            scope.launch {
                try {
                    val b = withContext(Dispatchers.IO) { Upstream { prefs.cookie }.balance() }
                    val r = JSONObject(b).optJSONObject("result")
                    if (r == null) { tv.text = "未登录或接口异常"; return@launch }
                    val daily = r.optLong("daily_token_limit")
                    val used = r.optLong("daily_tokens_used")
                    val bal = r.optLong("total_balance")
                    val quota = r.optLong("total_quota")
                    val left = (daily - used).coerceAtLeast(0)
                    tv.text = buildString {
                        append("账号：").append(r.optString("user_name", "—")).append("\n\n")
                        append("每日额度：").append(fmt(daily)).append("\n")
                        append("今日已用：").append(fmt(used)).append("\n")
                        append("今日剩余：").append(fmt(left)).append("\n\n")
                        append("总额度：").append(fmt(quota)).append("\n")
                        append("剩余额度：").append(fmt(bal))
                    }
                } catch (e: Exception) {
                    tv.text = "加载失败：${e.message}"
                }
            }
        }
        load()

        val card1 = card("额度信息", tv)

        val claimResult = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, dp(8), 0, 0)
        }
        val card2 = card("每日签到", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bigButton("立即签到领取额度", "#16A34A") {
                claimResult.text = "签到中…"
                scope.launch {
                    try {
                        val res = withContext(Dispatchers.IO) { Upstream { prefs.cookie }.claim() }
                        val o = JSONObject(res)
                        claimResult.text = if (o.optString("error_code") == "0000") "✓ 签到成功" else "签到失败：${o.optString("error_msg")}"
                        load()
                    } catch (e: Exception) {
                        claimResult.text = "签到异常：${e.message}"
                    }
                }
            })
            addView(switchRow("打开 App 时自动签到", prefs.autoClaim) { prefs.autoClaim = it })
            addView(claimResult)
        })

        val card3 = card("其他", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bigButton("刷新", "#2563EB") { load() })
        })

        return scroll(card1, card2, card3)
    }

    private fun fmt(v: Long): String = when {
        v >= 10_000_000 -> "%.1fM".format(v / 1_000_000.0)
        v >= 10_000 -> "%.1fK".format(v / 1_000.0)
        else -> v.toString()
    }

    // ---------- 日志页 ----------
    private fun pageLogs(): View {
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#334155"))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            typeface = Typeface.MONOSPACE
            text = if (Logs.list().isEmpty()) "暂无日志" else Logs.list().joinToString("\n")
        }
        val card1 = card("请求日志", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@MainActivity).apply {
                text = "清空日志"
                setOnClickListener { Logs.clear(); selectTab(4) }
            })
            addView(Button(this@MainActivity).apply {
                text = "刷新"
                setOnClickListener { selectTab(4) }
            })
            addView(tv)
        })
        return scroll(card1)
    }

    // ---------- 设置（并入状态页底部） ----------
    private fun pageSettings(): View {
        val c = card("设置", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(editRow("监听端口", prefs.port.toString(), "1024-65535") { v ->
                val p = v.toIntOrNull()
                if (p == null || p < 1024 || p > 65535) toast("端口需在 1024-65535 之间") else { prefs.port = p; toast("已保存") }
            })
            addView(editRow("访问密钥", prefs.apiKey, "留空则不校验") { v -> prefs.apiKey = v; toast("已保存") })
            addView(switchRow("允许局域网访问（0.0.0.0）", prefs.lanAccess, "不勾选时仅监听 127.0.0.1") { prefs.lanAccess = it })
            addView(switchRow("开机自动启动", prefs.autoStart) { prefs.autoStart = it })
        })
        return c
    }

    // ---------- 工具 ----------

    private fun startGateway() {
        val intent = Intent(this, GatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        toast("网关启动中")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }
}
