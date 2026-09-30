package dev.huawei2api.gateway

import dev.huawei2api.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 本地 OpenAI 兼容 HTTP 服务。
 * 路由：
 *   GET  /v1/models
 *   POST /v1/chat/completions   (stream / 非 stream)
 *   POST /api/claim             签到
 *   GET  /api/balance           余额
 */
class GatewayServer(
    private val port: Int,
    private val bindLan: Boolean,
    private val apiKey: String,
    private val log: (String) -> Unit,
) {
    private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val upstream = Upstream { App.prefs.cookie }

    val isRunning: Boolean get() = server?.isClosed == false

    fun start() {
        val bindAddr = if (bindLan) "0.0.0.0" else "127.0.0.1"
        val ss = ServerSocket(port, 50, java.net.InetAddress.getByName(bindAddr))
        server = ss
        log("监听 $bindAddr:$port")
        Thread {
            while (isRunning) {
                val sock = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { handle(sock) }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        log("已停止")
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val input = s.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')

                var contentLength = 0
                var auth = ""
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim().lowercase()
                        val v = line.substring(idx + 1).trim()
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                        if (k == "authorization") auth = v
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                // 全量请求日志：只要连上就记，方便区分「请求没到」和「请求到了但出错」
                log("› $method $path")

                // 鉴权
                if (apiKey.isNotEmpty()) {
                    val token = auth.removePrefix("Bearer ").trim()
                    if (token != apiKey) {
                        jsonError(s, 401, "invalid_api_key", "Missing or invalid API key.")
                        log("$method $path → 401 密钥错误")
                        return
                    }
                }

                // 容忍客户端省略 /v1 或 /v1/ 前缀
                val route = path.removeSuffix("/")
                when {
                    (route == "/v1/models" || route == "/models") && method == "GET" -> handleModels(s)
                    (route.endsWith("/chat/completions")) && method == "POST" -> handleChat(s, body)
                    (route == "/api/claim" || route == "/v1/claim") && method == "POST" -> handleClaim(s)
                    (route == "/api/balance" || route == "/v1/balance") && method == "GET" -> handleBalance(s)
                    else -> {
                        jsonError(s, 404, "not_found", "No such route: $path")
                        log("$method $path → 404 路由不存在")
                    }
                }
            }
        } catch (e: Exception) {
            log("请求异常: ${e.message}")
        }
    }

    private fun handleModels(s: Socket) {
        try {
            val models = upstream.listModels()
            val arr = JSONArray()
            models.forEach { m ->
                arr.put(JSONObject().put("id", m["id"]).put("object", "model")
                    .put("created", m["created"]).put("owned_by", m["owned_by"]))
            }
            val resp = JSONObject().put("object", "list").put("data", arr).toString()
            write(s, 200, "application/json", resp)
            log("GET /v1/models → ${models.size} 个模型")
        } catch (e: Exception) {
            jsonError(s, 502, "upstream_error", e.message ?: "failed")
        }
    }

    private fun handleChat(s: Socket, body: String) {
        val req = try { JSONObject(body) } catch (e: Exception) {
            jsonError(s, 400, "invalid_request", "Invalid JSON body."); return
        }
        val messages = req.optJSONArray("messages")
        if (messages == null || messages.length() == 0) {
            jsonError(s, 400, "invalid_request", "'messages' is required."); return
        }

        // 客户端模型名绝不能透传：上游只认固定 ID，
        // 传 gpt-4 / gpt-3.5-turbo 等会直接回 unsupported model 导致整条流失败
        val cliModel = req.optString("model").ifBlank { App.prefs.defaultModel }
        val model = App.prefs.defaultModel.ifBlank { "deepseek-v4-flash-0731" }
        val stream = req.optBoolean("stream", false)

        // 取最后一条 user 消息作为本次输入
        var prompt = ""
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.getJSONObject(i)
            if (m.optString("role") == "user") { prompt = m.optString("content"); break }
        }
        if (prompt.isEmpty()) {
            jsonError(s, 400, "invalid_request", "No user message found."); return
        }

        // 会话绑定：优先 client 传入的 session_id，否则用首条消息 hash。
        // 华为云要求 sessionId 必须先真实创建，不能自造 UUID。
        val sessionKey = req.optString("session_id").ifBlank {
            req.optString("user").ifBlank { messages.getJSONObject(0).optString("content").take(64) }
        }
        val sessionId = SessionPool.resolve(sessionKey) {
            // 真实创建优先；失败则自造 UUID（华为云允许前端直接生成）
            val newId = try {
                upstream.createSession()
            } catch (e: Exception) {
                log("创建会话失败(${e.message})，回退自造 UUID")
                java.util.UUID.randomUUID().toString()
            }
            log("上游会话 ${newId.take(8)}")
            newId
        }
        val id = "chatcmpl-" + System.currentTimeMillis().toString(36)
        if (cliModel != model) log("模型映射 $cliModel → $model")
        log("→ model=$model stream=$stream 会话=${sessionId.take(8)}")

        if (stream) {
            val mapper = SseMapper(id, model)
            // 上游原始行前若干条记入日志，转出异常时可直接看到原因
            var rawLeft = 12
            upstream.rawLogger = { line ->
                if (rawLeft > 0) { rawLeft--; log("  ‹ ${line.take(110)}") }
            }
            writeSseHeader(s)
            val out = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            // 客户端常按「首字节超时」判死；上游思考可能十几秒，故先发一帧再等
            out.write(": connected\n\n"); out.flush()

            val stopBeat = java.util.concurrent.atomic.AtomicBoolean(false)
            val beat = Thread {
                try {
                    while (!stopBeat.get()) {
                        Thread.sleep(5000)
                        if (stopBeat.get()) break
                        synchronized(out) { out.write(": ping\n\n"); out.flush() }
                    }
                } catch (_: Exception) {
                }
            }
            beat.isDaemon = true
            beat.start()

            try {
                upstream.sendMessage(sessionId, prompt, model) { ev, data ->
                    val chunk = mapper.map(ev, data)
                    if (chunk.isNotEmpty()) synchronized(out) { out.write(chunk); out.flush() }
                }
                val tail = mapper.finish()
                if (tail.isNotEmpty()) synchronized(out) { out.write(tail); out.flush() }
                log("← 流式完成")
            } catch (e: Exception) {
                log("流异常: ${e.message}")
                try {
                    synchronized(out) {
                        out.write("data: {\"error\":{\"message\":\"upstream_error: ${e.message?.take(120)}\"}}\n\n")
                        out.write("data: [DONE]\n\n"); out.flush()
                    }
                } catch (_: Exception) {}
            } finally {
                stopBeat.set(true)
                upstream.rawLogger = null
            }
        } else {
            val collector = SseMapper.Collector(id, model)
            try {
                upstream.sendMessage(sessionId, prompt, model) { ev, data -> collector.feed(ev, data) }
                write(s, 200, "application/json", collector.toJson())
            } catch (e: Exception) {
                jsonError(s, 502, "upstream_error", e.message ?: "failed")
            }
        }
    }

    private fun handleClaim(s: Socket) {
        try {
            write(s, 200, "application/json", upstream.claim())
            log("POST /api/claim → 签到")
        } catch (e: Exception) {
            jsonError(s, 502, "upstream_error", e.message ?: "failed")
        }
    }

    private fun handleBalance(s: Socket) {
        try {
            write(s, 200, "application/json", upstream.balance())
        } catch (e: Exception) {
            jsonError(s, 502, "upstream_error", e.message ?: "failed")
        }
    }

    private fun writeSseHeader(s: Socket) {
        val hdr = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n" +
            "X-Accel-Buffering: no\r\n\r\n"
        s.getOutputStream().apply { write(hdr.toByteArray()); flush() }
    }

    private fun write(s: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 $code OK\r\nContent-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(hdr.toByteArray()); write(bytes); flush() }
    }

    private fun jsonError(s: Socket, code: Int, type: String, msg: String) {
        val body = JSONObject().put("error", JSONObject().put("message", msg).put("type", type)).toString()
        write(s, code, "application/json", body)
    }
}
