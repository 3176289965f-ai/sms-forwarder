package com.example.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 接收端本地收件箱：最近收到的短信落盘，供 App 内列表展示。
 * 上限 100 条，超了丢最旧的。
 */
object InboxStore {
    private const val FILE = "relay_inbox.json"
    private const val MAX = 100

    data class Item(
        val id: Long,
        val sender: String,
        val body: String,
        val receivedAt: Long,
        val device: String,
        val note: String
    )

    private fun file(context: Context): File = File(context.filesDir, FILE)

    @Synchronized
    fun add(context: Context, msg: Relay.Msg) {
        try {
            val items = readAll(context).toMutableList()
            if (items.any { it.id == msg.id }) return // 去重
            items.add(
                Item(msg.id, msg.sender, msg.body, msg.receivedAt, msg.device, msg.note)
            )
            while (items.size > MAX) items.removeAt(0)
            writeAll(context, items)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun all(context: Context): List<Item> = try {
        readAll(context).sortedByDescending { it.id }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readAll(context: Context): List<Item> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Item(
                        o.optLong("id"), o.optString("sender"), o.optString("body"),
                        o.optLong("received_at"), o.optString("device"), o.optString("note")
                    )
                )
            }
        }
    }

    private fun writeAll(context: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("sender", it.sender)
                    .put("body", it.body)
                    .put("received_at", it.receivedAt)
                    .put("device", it.device)
                    .put("note", it.note)
            )
        }
        file(context).writeText(arr.toString(), Charsets.UTF_8)
    }
}
