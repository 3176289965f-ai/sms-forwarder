package com.example.smsforward

import android.content.Context

/**
 * App 配置。Token / Key 只存放在本机私有目录，不会上传到任何地方。
 */
object Settings {
    private const val FILE = "smsforward_prefs"

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
