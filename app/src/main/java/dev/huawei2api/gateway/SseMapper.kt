package dev.huawei2api.gateway

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 华为云 SSE 事件 → OpenAI chat.completion.chunk 转换。
 *
 * 华为云事件：status / session_title / file_diff / thought / message / step_finish / done
 *   thought → reasoning_content 增量
 *   message → content 增量
 *   done    → finish_reason + usage
 */
class SseMapper(
    private val id: String,
    private val model: String,
    private val created: Long = System.currentTimeMillis() / 1000,
) {
    private val sentFirst = AtomicBoolean(false)
    private var finished = false

    private fun chunk(deltaJson: String, finish: String? = null): String {
        val sb = StringBuilder()
        sb.append("data: {\"id\":\"").append(id)
            .append("\",\"object\":\"chat.completion.chunk\",\"created\":").append(created)
            .append(",\"model\":\"").append(model).append("\",\"choices\":[{")
        if (!sentFirst.getAndSet(true)) {
            sb.append("\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},")
        } else {
            sb.append("\"index\":0,\"delta\":").append(deltaJson).append(',')
        }
        sb.append("\"finish_reason\":").append(if (finish == null) "null" else "\"$finish\"")
            .append("}]}\n\n")
        return sb.toString()
    }

    /** 处理单条上游事件，返回要写给客户端的 SSE 文本（可能为空） */
    fun map(event: String, data: String): String {
        if (finished) return ""
        return try {
            val obj = JSONObject(data)
            when (event) {
                "thought" -> {
                    val c = obj.optString("content")
                    if (c.isEmpty()) "" else chunk("{\"reasoning_content\":${escape(c)}}")
                }
                "message" -> {
                    val c = obj.optString("content")
                    if (c.isEmpty()) "" else chunk("{\"content\":${escape(c)}}")
                }
                "step_finish" -> {
                    // 不结束，等 done；仅记录 reason
                    ""
                }
                "done" -> {
                    finished = true
                    chunk("{}", "stop") + "data: [DONE]\n\n"
                }
                "error" -> {
                    finished = true
                    "data: {\"error\":{\"message\":${escape(obj.optString("message", "upstream error"))}}}\n\ndata: [DONE]\n\n"
                }
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** 流意外结束时的兜底收尾 */
    fun finish(): String {
        if (finished) return ""
        finished = true
        return chunk("{}", "stop") + "data: [DONE]\n\n"
    }

    private fun escape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    /** 非流式：把上游事件累积成完整响应 */
    class Collector(private val id: String, private val model: String) {
        private val content = StringBuilder()
        private val reasoning = StringBuilder()
        private var usage: JSONObject? = null
        private var finishReason = "stop"

        fun feed(event: String, data: String) {
            try {
                val o = JSONObject(data)
                when (event) {
                    "message" -> content.append(o.optString("content"))
                    "thought" -> reasoning.append(o.optString("content"))
                    "step_finish" -> o.optJSONObject("tokens")?.let { usage = it }
                    "done" -> {
                        finishReason = "stop"
                        o.optJSONObject("tokens")?.let { usage = it }
                    }
                    "error" -> finishReason = "error"
                }
            } catch (_: Exception) {
            }
        }

        fun toJson(): String {
            val created = System.currentTimeMillis() / 1000
            val sb = StringBuilder()
            sb.append("{\"id\":\"").append(id)
                .append("\",\"object\":\"chat.completion\",\"created\":").append(created)
                .append(",\"model\":\"").append(model).append("\",\"choices\":[{\"index\":0,\"message\":{")
                .append("\"role\":\"assistant\",\"content\":\"").append(esc(content.toString())).append("\"")
            if (reasoning.isNotEmpty()) {
                sb.append(",\"reasoning_content\":\"").append(esc(reasoning.toString())).append("\"")
            }
            sb.append("},\"finish_reason\":\"").append(finishReason).append("\"}]")
            usage?.let {
                val inp = it.optInt("input", 0)
                val outp = it.optInt("output", 0)
                sb.append(",\"usage\":{\"prompt_tokens\":").append(inp)
                    .append(",\"completion_tokens\":").append(outp)
                    .append(",\"total_tokens\":").append(it.optInt("total", inp + outp)).append("}")
            }
            return sb.append("}").toString()
        }

        private fun esc(s: String) = StringBuilder().also { b ->
            for (c in s) when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
            }
        }.toString()
    }
}
