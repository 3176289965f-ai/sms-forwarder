package com.example.smsforward

import android.content.Context

/**
 * App 配置。Token / Key 只存放在本机私有目录，不会上传到任何地方。
 *
 * v2 新增：转发目标（Telegram / 微信 ClawBot / 两者），以及 OpenClaw 网关连接参数。
 */
object Settings {
    private const val FILE = "smsforward_prefs"

    /** 转发目标：0=Telegram，1=微信 ClawBot，2=两者都发 */
    const val TARGET_TELEGRAM = 0
    const val TARGET_WECHAT = 1
    const val TARGET_BOTH = 2

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getTelegramToken(c: Context): String =
        prefs(c).getString("tg_token", "") ?: ""

    fun setTelegramToken(c: Context, v: String) {
        prefs(c).edit().putString("tg_token", v).apply()
    }

    fun getTelegramChatId(c: Context): String =
        prefs(c).getString("tg_chat_id", "") ?: ""

    fun setTelegramChatId(c: Context, v: String) {
        prefs(c).edit().putString("tg_chat_id", v).apply()
    }

    fun getForwardTarget(c: Context): Int =
        prefs(c).getInt("forward_target", TARGET_TELEGRAM)

    fun setForwardTarget(c: Context, v: Int) {
        prefs(c).edit().putInt("forward_target", v).apply()
    }

    // ---- 微信 ClawBot（经由用户自己的 OpenClaw 网关）----

    /** OpenClaw 网关地址，例如 http://192.168.1.10:18789 */
    fun getOcBaseUrl(c: Context): String =
        prefs(c).getString("oc_base_url", "") ?: ""

    fun setOcBaseUrl(c: Context, v: String) {
        prefs(c).edit().putString("oc_base_url", v).apply()
    }

    /** 网关 API Token（OpenClaw 配置里的 gateway token） */
    fun getOcToken(c: Context): String =
        prefs(c).getString("oc_token", "") ?: ""

    fun setOcToken(c: Context, v: String) {
        prefs(c).edit().putString("oc_token", v).apply()
    }

    /** 消息发送路径，默认 /rpc（JSON-RPC message.send）；网关版本不同可自行修改 */
    fun getOcSendPath(c: Context): String =
        prefs(c).getString("oc_send_path", "/rpc") ?: "/rpc"

    fun setOcSendPath(c: Context, v: String) {
        prefs(c).edit().putString("oc_send_path", v).apply()
    }

    /** 微信目标 ID（用 openclaw message channel list / channels resolve 查得） */
    fun getWechatTarget(c: Context): String =
        prefs(c).getString("wechat_target", "") ?: ""

    fun setWechatTarget(c: Context, v: String) {
        prefs(c).edit().putString("wechat_target", v).apply()
    }

    // ---- Hachimi AI ----

    fun getHachimiKey(c: Context): String =
        prefs(c).getString("hachimi_key", "") ?: ""

    fun setHachimiKey(c: Context, v: String) {
        prefs(c).edit().putString("hachimi_key", v).apply()
    }

    fun getHachimiModel(c: Context): String =
        prefs(c).getString("hachimi_model", "gpt-6-astra") ?: "gpt-6-astra"

    fun setHachimiModel(c: Context, v: String) {
        prefs(c).edit().putString("hachimi_model", v).apply()
    }

    fun isAiEnabled(c: Context): Boolean =
        prefs(c).getBoolean("ai_enabled", false)

    fun setAiEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("ai_enabled", v).apply()
    }

    fun getKeywords(c: Context): String =
        prefs(c).getString("keywords", "") ?: ""

    fun setKeywords(c: Context, v: String) {
        prefs(c).edit().putString("keywords", v).apply()
    }

    /** 逗号/顿号分隔的关键词列表，留空表示不过滤 */
    fun getKeywordList(c: Context): List<String> =
        getKeywords(c).split("[,，、]".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
