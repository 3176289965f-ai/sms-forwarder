package com.example.smsforward

import java.net.HttpURLConnection
import java.net.URL

/**
 * 微信 ClawBot 发送通道：把短信经由用户自己的 OpenClaw 网关发到微信。
 *
 * 原理：用户在自己的一台设备（电脑/服务器）上运行 OpenClaw 网关，
 * 安装微信 ClawBot 插件并扫码登录微信：
 *   npx -y @tencent-weixin/openclaw-weixin-cli@latest install
 *   openclaw channels login --channel openclaw-weixin
 * 本 App 通过网关的 HTTP 接口调用 message.send，
 * 网关再经由已登录的微信账号把消息发给指定目标。
 *
 * 默认使用网关的 JSON-RPC 接口：
 *   POST {baseUrl}/rpc
 *   Authorization: Bearer {gatewayToken}
 *   {"jsonrpc":"2.0","id":1,"method":"message.send",
 *    "params":{"channel":"openclaw-weixin","target":"{target}","message":"{text}"}}
 *
 * 说明：网关版本差异可能导致接口路径/参数名不同，因此 baseUrl/sendPath
 * 都做成设置项可配。本文件不依赖任何 Android API，可在 JVM 上直接单测。
 *
 * 注意：所有网络调用都在后台线程执行，切勿在主线程调用。
 */
object OpenClaw {

    const val WECHAT_CHANNEL = "openclaw-weixin"
    const val DEFAULT_SEND_PATH = "/rpc"

    private var rpcId = 0L

    @Synchronized
    private fun nextId(): Long {
        rpcId += 1
        return rpcId
    }

    /**
     * 发送一条短信文本到微信目标。成功返回 true。
     *
     * @param baseUrl  网关地址，如 http://192.168.1.10:18789（末尾斜杠会被自动去掉）
     * @param sendPath 发送路径，默认 /rpc
     * @param token    网关 API Token
     * @param target   微信目标 ID（openclaw message channel list 查得）
     * @param text     要发送的文本
     */
    fun send(
        baseUrl: String,
        sendPath: String,
        token: String,
        target: String,
        text: String
    ): Boolean {
        if (baseUrl.isBlank() || target.isBlank()) return false
        val path = sendPath.ifBlank { DEFAULT_SEND_PATH }
        val url = baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val payload = buildRpcPayload(target, text, nextId())
        val (code, body) = postRaw(url, payload, token) ?: return false
        return isRpcSuccess(code, body)
    }

    /** 构造 JSON-RPC message.send 请求体（纯函数，可单测） */
    fun buildRpcPayload(target: String, message: String, id: Long): String {
        return "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"message.send\"," +
            "\"params\":{\"channel\":\"${jsonEscape(WECHAT_CHANNEL)}\"," +
            "\"target\":\"${jsonEscape(target)}\"," +
            "\"message\":\"${jsonEscape(message)}\"}}"
    }

    /**
     * 判断网关响应是否表示发送成功（纯函数，可单测）：
     * HTTP 2xx 且响应体不是 JSON-RPC 错误。
     */
    fun isRpcSuccess(httpCode: Int, body: String?): Boolean {
        if (httpCode !in 200..299) return false
        val b = body?.trim() ?: return false
        if (b.isEmpty()) return false
        // 宽松判断：出现 "error" 成员视为失败；否则 2xx 即视为成功
        //（不同网关版本的成功体字段可能不同，避免过度校验）
        return !b.contains("\"error\"")
    }

    /** JSON 字符串转义（不依赖 org.json，保证 JVM/单测可用） */
    fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') {
                    sb.append(String.format("\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    /** 拼接后的网关 URL（纯函数，可单测）：自动处理末尾斜杠与 path 开头斜杠 */
    fun joinUrl(baseUrl: String, sendPath: String): String {
        val path = sendPath.ifBlank { DEFAULT_SEND_PATH }
        return baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
    }

    private fun postRaw(url: String, payload: String, token: String): Pair<Int, String?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "SmsForwarder/2.0")
            if (token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = try {
                stream?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                null
            }
            Pair(code, body)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
