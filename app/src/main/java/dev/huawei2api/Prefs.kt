package dev.huawei2api

import android.content.Context
import android.content.SharedPreferences

/** 全局配置存储 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("huawei2api", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 8321)
        set(v) = sp.edit().putInt("port", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var lanAccess: Boolean
        get() = sp.getBoolean("lanAccess", false)
        set(v) = sp.edit().putBoolean("lanAccess", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autoStart", true)
        set(v) = sp.edit().putBoolean("autoStart", v).apply()

    var defaultModel: String
        get() = sp.getString("defaultModel", "deepseek-v4-flash-0731") ?: "deepseek-v4-flash-0731"
        set(v) = sp.edit().putString("defaultModel", v).apply()

    var autoClaim: Boolean
        get() = sp.getBoolean("autoClaim", false)
        set(v) = sp.edit().putBoolean("autoClaim", v).apply()

    var cookie: String
        get() = sp.getString("cookie", "") ?: ""
        set(v) = sp.edit().putString("cookie", v).apply()

    var userName: String
        get() = sp.getString("userName", "") ?: ""
        set(v) = sp.edit().putString("userName", v).apply()

    companion object {
        const val BASE = "https://devcloud.cn-north-4.huaweicloud.com"
    }
}
