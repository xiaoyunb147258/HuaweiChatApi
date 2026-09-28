package dev.huawei2api.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * OpenAI 会话 → 华为云 sessionId 映射。
 * OpenAI 的 session 概念由客户端自带（无则用首条消息 hash），
 * 这里把两者绑定，实现多轮上下文。
 */
object SessionPool {
    private val map = ConcurrentHashMap<String, String>()

    fun resolve(openAiSessionKey: String?): String {
        if (openAiSessionKey.isNullOrBlank()) return Upstream.newSessionId()
        return map.getOrPut(openAiSessionKey) { Upstream.newSessionId() }
    }

    fun bind(key: String, huaweiSessionId: String) {
        map[key] = huaweiSessionId
    }

    fun clear() = map.clear()
}
