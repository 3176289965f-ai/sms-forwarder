package com.example.smsforward

import android.content.Context

/**
 * 已处理短信去重表：key = "发件人|短信时间戳"。
 *
 * 广播路径和对账路径都走 Forwarder.handleSms，可能短时间内撞到同一条短信，
 * 靠这张表保证一条短信只处理一次（发送成功、发送失败入队、被过滤都算"已处理"，
 * 发送失败的由 outbox 负责重试，对账不再重复捞）。
 */
object ForwardedStore {
    private const val FILE = "forwarded_prefs"
    private const val KEY_SET = "done_set"
    private const val MAX = 500

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun isDone(context: Context, key: String): Boolean =
        prefs(context).getStringSet(KEY_SET, emptySet())?.contains(key) == true

    @Synchronized
    fun markDone(context: Context, key: String) {
        val cur = prefs(context).getStringSet(KEY_SET, emptySet())?.toMutableList()
            ?: mutableListOf()
        if (cur.contains(key)) return
        cur.add(0, key)
        while (cur.size > MAX) cur.removeAt(cur.size - 1)
        prefs(context).edit().putStringSet(KEY_SET, cur.toSet()).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SET).apply()
    }
}
