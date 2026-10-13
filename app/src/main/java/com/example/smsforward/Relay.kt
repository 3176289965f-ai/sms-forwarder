package com.example.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v3 直连中转客户端：与 EC2 上的 relay_server.py 对话。
 *
 *   POST {base}/send  {sender, body, received_at, device, note}  -> 消息 id（失败 null）
 *   GET  {base}/poll?after=N -> List<Msg>（失败 null）
 *   GET  {base}/health -> 中文诊断字符串
 *
 * 鉴权：请求头 X-Relay-Token。
 * 所有网络调用都在后台线程执行，切勿在主线程调用。
 */
object Relay {

    data class Msg(
        val id: Long,
        val sender: String,
        val body: String,
        val receivedAt: Long,
        val device: String,
        val note: String
    )

    private const val TOKEN_HEADER = "X-Relay-Token"

    /** 发送一条短信到中转，成功返回消息 id，失败返回 null */
    fun send(
        baseUrl: String,
        token: String,
        sender: String,
        body: String,
        receivedAt: Long,
        device: String,
        note: String?
    ): Long? {
        if (baseUrl.isBlank() || token.isBlank() || body.isBlank()) return null
        return try {
            val payload = JSONObject()
                .put("sender", sender)
                .put("body", body)
                .put("received_at", receivedAt)
                .put("device", device)
                .put("note", note ?: "")
                .toString()
            val resp = postJson("$baseUrl/send", payload, token, 15000) ?: return null
            val obj = JSONObject(resp)
            if (obj.optBoolean("ok", false)) obj.optLong("id", -1).takeIf { it > 0 } else null
        } catch (e: Exception) {
            null
        }
    }

    /** 拉取 after 之后的新消息，失败返回 null（注意与空列表区分） */
    fun poll(baseUrl: String, token: String, after: Long): List<Msg>? {
        if (baseUrl.isBlank() || token.isBlank()) return null
        return try {
            val resp = get("$baseUrl/poll?after=$after", token, 15000) ?: return null
            val obj = JSONObject(resp)
            if (!obj.optBoolean("ok", false)) return null
            val arr = obj.optJSONArray("messages") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    add(
                        Msg(
                            id = m.optLong("id", 0),
                            sender = m.optString("sender", ""),
                            body = m.optString("body", ""),
                            receivedAt = m.optLong("received_at", 0),
                            device = m.optString("device", ""),
                            note = m.optString("note", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 中转连通性自检：发一条测试消息，返回中文结论 */
    fun test(baseUrl: String, token: String, device: String): String {
        if (baseUrl.isBlank()) return "请先填写中转地址"
        if (token.isBlank()) return "请先填写中转 Token"
        return try {
            val id = send(
                baseUrl, token, "测试", "✅ 短信转发测试：直连中转配置成功！",
                System.currentTimeMillis(), device, null
            )
            when {
                id != null -> "✅ 中转测试成功（消息 id=$id）。接收端 App 应该能收到这条测试。"
                else -> "❌ 发送失败：检查中转地址 / Token / 网络，" +
                    "并确认 EC2 上的 relay 服务和 Caddy 路由已按指南配好"
            }
        } catch (e: Exception) {
            "请求异常：${e.message}"
        }
    }

    /** 接收端连通性自检：只 poll 不消费，返回中文结论 */
    fun testPoll(baseUrl: String, token: String): String {
        if (baseUrl.isBlank()) return "请先填写中转地址"
        if (token.isBlank()) return "请先填写中转 Token"
        return try {
            val msgs = poll(baseUrl, token, 0)
            when {
                msgs == null -> "❌ 连接失败：检查中转地址 / Token / 网络"
                msgs.isEmpty() -> "✅ 中转连通正常（当前队列为空，发一条短信或点发送端测试试试）"
                else -> "✅ 中转连通正常，队列里有 ${msgs.size} 条历史消息（最新 id=${msgs.maxOf { it.id }}）"
            }
        } catch (e: Exception) {
            "请求异常：${e.message}"
        }
    }

    private fun postJson(url: String, payload: String, token: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty(TOKEN_HEADER, token)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun get(url: String, token: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty(TOKEN_HEADER, token)
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

}

/**
 * 发送失败时的本地 outbox：短信先落盘，下次有短信到达或打开 App 时重试。
 * 避免"断网那几分钟的验证码丢了"。
 */
object RelayOutbox {
    private const val FILE = "relay_outbox.json"
    private const val MAX = 100

    private fun file(context: Context): File = File(context.filesDir, FILE)

    @Synchronized
    fun enqueue(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long,
        device: String,
        note: String?
    ) {
        try {
            val arr = readAll(context).toMutableList()
            arr.add(
                JSONObject()
                    .put("sender", sender)
                    .put("body", body)
                    .put("received_at", receivedAt)
                    .put("device", device)
                    .put("note", note ?: "")
            )
            while (arr.size > MAX) arr.removeAt(0)
            writeAll(context, arr)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 重试发送积压消息，返回仍未发出的数量 */
    @Synchronized
    fun drain(context: Context): Int {
        val baseUrl = Settings.getRelayUrl(context).trim().trimEnd('/')
        val token = Settings.getRelayToken(context).trim()
        if (baseUrl.isBlank() || token.isBlank()) return pendingCount(context)
        val pending = readAll(context).toMutableList()
        if (pending.isEmpty()) return 0
        val failed = mutableListOf<JSONObject>()
        for (o in pending) {
            val id = Relay.send(
                baseUrl, token,
                o.optString("sender"), o.optString("body"), o.optLong("received_at"),
                o.optString("device"), o.optString("note").ifEmpty { null }
            )
            if (id == null) failed.add(o)
        }
        writeAll(context, failed)
        return failed.size
    }

    fun pendingCount(context: Context): Int = try {
        readAll(context).size
    } catch (e: Exception) {
        0
    }

    private fun readAll(context: Context): List<JSONObject> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText(Charsets.UTF_8))
        return buildList {
            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
        }
    }

    private fun writeAll(context: Context, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        file(context).writeText(arr.toString(), Charsets.UTF_8)
    }
}
