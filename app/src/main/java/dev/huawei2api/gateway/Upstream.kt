package dev.huawei2api.gateway

import dev.huawei2api.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 华为云码道上游接口封装 */
class Upstream(private val cookieProvider: () -> String) {

    private fun cftk(): String {
        val c = cookieProvider()
        return Regex("devclouddevuibjtcftk=([^;]*)").find(c)?.groupValues?.get(1) ?: ""
    }

    private fun baseHeaders(sse: Boolean = false, agentType: String? = null): MutableMap<String, String> {
        val h = mutableMapOf(
            "Content-Type" to "application/json",
            "x-requested-with" to "XMLHttpRequest",
            "x-codearts-client-type" to "web",
            "cftk" to cftk(),
            "maas-type" to "benefit",
            "Cookie" to cookieProvider(),
        )
        if (sse) {
            h["Accept"] = "text/event-stream"
            h["x-auth-token"] = ""
        }
        if (agentType != null) h["Agent-Type"] = agentType
        return h
    }

    private fun open(method: String, path: String, sse: Boolean = false, agentType: String? = null): HttpURLConnection {
        val conn = URL(Prefs.BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 180000
        baseHeaders(sse, agentType).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (method == "POST") {
            conn.doOutput = true
            conn.setChunkedStreamingMode(0)
        }
        return conn
    }

    /** 发消息（SSE 流），回调每条 (event, data) */
    fun sendMessage(sessionId: String, content: String, modelId: String, onEvent: (String, String) -> Unit) {
        val body = JSONObject()
            .put("content", content)
            .put("model_id", modelId)
            .put("repos", JSONArray())
            .toString()

        val conn = open("POST", "/chat/codebaseservice/v1/cloudagent/sessions/$sessionId/messages", sse = true, agentType = "CodeBase")
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        if (code != 200) {
            val err = (conn.errorStream?.bufferedReader() ?: conn.inputStream.bufferedReader()).readText()
            throw RuntimeException("Upstream $code: ${err.take(300)}")
        }

        conn.inputStream.bufferedReader().use { br ->
            var event = ""
            var data: StringBuilder? = null
            while (true) {
                val line = br.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data == null) data = StringBuilder()
                        data.append(line.substring(5).trim())
                    }
                    line.isEmpty() -> {
                        data?.let { onEvent(event, it.toString()) }
                        event = ""; data = null
                    }
                }
            }
        }
    }

    /** 创建会话，返回 sessionId */
    fun createSession(): String {
        val conn = open("POST", "/chat/v1/cloudagent/sessions", agentType = "CodeBase")
        conn.outputStream.use { it.write("{}".toByteArray()) }
        val code = conn.responseCode
        val text = (if (code == 200) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code != 200) throw RuntimeException("CreateSession $code: ${text.take(300)}")
        return JSONObject(text).getJSONObject("result").getString("session_id")
    }

    /** 拉取可用模型 */
    fun listModels(): List<Map<String, Any>> {
        val conn = open("GET", "/chat/codebaseservice/v1/cloudagent/benefit/gateway/config")
        val text = conn.inputStream.bufferedReader().readText()
        val root = JSONObject(text)
        val arr = root.getJSONObject("result").optJSONArray("models") ?: return emptyList()
        val out = ArrayList<Map<String, Any>>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            out.add(
                mapOf(
                    "id" to m.optString("model_id"),
                    "owned_by" to "huawei-codearts",
                    "created" to System.currentTimeMillis() / 1000,
                )
            )
        }
        return out
    }

    /** 签到 */
    fun claim(): String {
        val conn = open("POST", "/chat/codebaseservice/v1/cloudagent/benefit/claim")
        conn.outputStream.use { it.write("{}".toByteArray()) }
        return conn.inputStream.bufferedReader().readText()
    }

    /** 余额 */
    fun balance(): String {
        val conn = open("GET", "/chat/codebaseservice/v1/cloudagent/benefit/tokens/balance")
        return conn.inputStream.bufferedReader().readText()
    }

    companion object {
    }
}
