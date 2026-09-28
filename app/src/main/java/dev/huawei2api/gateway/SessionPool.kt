package dev.huawei2api.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * OpenAI 会话 → 华为云 sessionId 映射。
 * 华为云要求 sessionId 必须先通过 /v1/cloudagent/sessions 创建，不能自造。
 */
object SessionPool {
    private val map = ConcurrentHashMap<String, String>()

    /** 取或建：首次访问时真实创建华为云会话 */
    fun resolve(key: String?, create: () -> String): String {
        if (key.isNullOrBlank()) return create()
        map[key]?.let { return it }
        return synchronized(this) {
            map[key] ?: create().also { map[key] = it }
        }
    }

    fun invalidate(key: String) {
        map.remove(key)
    }

    fun clear() = map.clear()
}
