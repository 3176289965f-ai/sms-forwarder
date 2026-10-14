package com.example.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 发送端事件日志：每条短信的接收 / 过滤 / 发送 / 入队都记一笔（最多保留 100 条）。
 *
 * 下次再漏短信，直接打开发送端看这里：
 * - 有"收到"但没"已发送" -> 死在过滤或发送环节，看原因
 * - 连"收到"都没有 -> 系统广播根本没送达（被 ROM 强杀/深度清理）
 */
object SendLog {
    private const val FILE = "send_log.json"
    private const val MAX = 100

    data class Event(val time: Long, val kind: String, val detail: String)

    @Synchronized
    fun log(context: Context, kind: String, detail: String) {
        try {
            val list = readAll(context).toMutableList()
            list.add(0, Event(System.currentTimeMillis(), kind, detail))
            while (list.size > MAX) list.removeAt(list.size - 1)
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject()
                        .put("t", e.time)
                        .put("k", e.kind)
                        .put("d", e.detail)
                )
            }
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            tmp.renameTo(File(context.filesDir, FILE))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun list(context: Context): List<Event> = try {
        readAll(context)
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun readAll(context: Context): List<Event> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(Event(o.optLong("t"), o.optString("k"), o.optString("d")))
            }
        }
    }
}
