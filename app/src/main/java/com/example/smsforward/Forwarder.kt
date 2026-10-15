package com.example.smsforward

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 转发核心（仅使用 Android 系统自带 API，零第三方依赖）：
 * 短信到达 -> 关键词过滤 -> [可选] Hachimi AI 分类/摘要 -> Telegram Bot API
 * AI 步骤任何失败都会自动降级为原文转发，不阻塞主流程。
 *
 * 注意：所有网络调用都在后台线程执行，切勿在主线程调用。
 */
object Forwarder {

    private const val HACHIMI_BASE = "https://ai.hachimi.online/v1"

    // ai.hachimi.online 背后有 Cloudflare，需要浏览器 UA 否则可能被 403
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    data class AiResult(val forward: Boolean, val type: String, val summary: String)

    /**
     * 统一的短信处理入口：广播收到短信、对账扫到短信都走这里。
     *
     * @param isReconcile true=对账补发路径（广播没送达时捞回），只影响日志标注
     */
    fun handleSms(context: Context, from: String, body: String, timestamp: Long, isReconcile: Boolean = false) {
        // v3：只有发送端才处理短信
        if (Settings.getRole(context) != Settings.ROLE_SENDER &&
            Settings.getRole(context) != Settings.ROLE_UNSET
        ) return

        // 去重：广播和对账可能撞到同一条短信，只处理一次。
        // 注意：标记必须放在"处理完"（发送成功 / 已入队 / 已过滤 / 目标未配置）之后，
        // 绝不能放在开头——否则进程在发送中途被杀（国产 ROM 常见）会导致
        // "标记了但没发出去"，而对账又会跳过已标记的，短信就静默丢失了。
        // （2026-10-15 工商银行漏转就是这个原因：广播里标记了，发送前进程被杀。）
        val dedupKey = "$from|$timestamp"
        if (ForwardedStore.isDone(context, dedupKey)) return

        val target = Settings.getForwardTarget(context)
        val doTelegram = target == Settings.TARGET_TELEGRAM || target == Settings.TARGET_BOTH
        val doWechat = target == Settings.TARGET_WECHAT || target == Settings.TARGET_BOTH
        val doRelay = target == Settings.TARGET_RELAY

        val tgToken = Settings.getTelegramToken(context)
        val tgChatId = Settings.getTelegramChatId(context)
        val tgReady = tgToken.isNotBlank() && tgChatId.isNotBlank()

        val ocBaseUrl = Settings.getOcBaseUrl(context)
        val ocToken = Settings.getOcToken(context)
        val ocSendPath = Settings.getOcSendPath(context)
        val wechatTarget = Settings.getWechatTarget(context)
        val wxReady = ocBaseUrl.isNotBlank() && wechatTarget.isNotBlank()

        val relayUrl = Settings.getRelayUrl(context).trim().trimEnd('/')
        val relayToken = Settings.getRelayToken(context).trim()
        val relayReady = relayUrl.isNotBlank() && relayToken.isNotBlank()

        // 选中的目标一个都没配好 -> 直接丢弃
        if ((doTelegram && !tgReady && !doWechat && !doRelay) ||
            (doWechat && !wxReady && !doTelegram && !doRelay) ||
            (doRelay && !relayReady && !doTelegram && !doWechat) ||
            (doTelegram && doWechat && !tgReady && !wxReady)
        ) {
            SendLog.log(context, "未发送", "转发目标未配置")
            ForwardedStore.markDone(context, dedupKey)
            return
        }
        val reconcileTag = if (isReconcile) "（对账补发）" else ""
        SendLog.log(context, "收到", "发件人 $from$reconcileTag")

        // 关键词过滤：配置了关键词且一条都没命中 -> 不转发
        val keywords = Settings.getKeywordList(context)
        if (keywords.isNotEmpty() && keywords.none { body.contains(it, ignoreCase = true) }) {
            SendLog.log(context, "已过滤", "关键词未命中")
            ForwardedStore.markDone(context, dedupKey)
            return
        }

        var aiNote: String? = null
        var shouldForward = true

        if (Settings.isAiEnabled(context)) {
            val key = Settings.getHachimiKey(context)
            if (key.isNotBlank()) {
                // 超时或解析失败 -> result 为 null -> 按原文转发
                val result = classifyViaHachimi(key, Settings.getHachimiModel(context), from, body)
                if (result != null) {
                    shouldForward = result.forward
                    if (result.summary.isNotBlank()) {
                        aiNote = "🤖 ${result.type}：${result.summary}"
                    }
                }
            }
        }
        if (!shouldForward) {
            SendLog.log(context, "已过滤", "AI判定不转发")
            ForwardedStore.markDone(context, dedupKey)
            return // AI 判定为垃圾短信
        }

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(timestamp))
        val text = buildString {
            append("📩 新短信\n")
            append("发件人：").append(from).append('\n')
            append("时间：").append(time).append('\n')
            if (aiNote != null) append(aiNote).append('\n')
            append("内容：\n").append(body)
        }
        if (doTelegram && tgReady) {
            sendTelegram(tgToken, tgChatId, text)
        }
        if (doWechat && wxReady) {
            OpenClaw.send(ocBaseUrl, ocSendPath, ocToken, wechatTarget, text)
        }
        if (doRelay && relayReady) {
            // v3：直连中转只发原文 + AI 备注（如果有），接收端负责展示
            val id = Relay.send(
                relayUrl, relayToken, from, body, timestamp,
                Settings.getDeviceName(context), aiNote
            )
            if (id == null) {
                // 发送失败 -> 进本地 outbox，下次重试
                SendLog.log(context, "已入队", "发送失败，等待补发")
                RelayOutbox.enqueue(
                    context, from, body, timestamp,
                    Settings.getDeviceName(context), aiNote
                )
            } else {
                SendLog.log(context, "已发送", "中转#$id")
                // 顺手把之前积压的也发出
                val left = RelayOutbox.drain(context)
                if (left > 0) {
                    SendLog.log(context, "补发", "仍有 $left 条未发出")
                }
            }
        }
        // 所有发送分支走完才标记已处理（成功 / 已入队 / Telegram 发出都算）
        ForwardedStore.markDone(context, dedupKey)
    }

    /**
     * 微信 ClawBot 连通性自检：发一条测试消息到微信目标，
     * 返回一段可直接显示给用户的中文结论。
     */
    fun testWechat(
        baseUrl: String,
        sendPath: String,
        token: String,
        target: String
    ): String {
        if (baseUrl.isBlank()) return "请先填写 OpenClaw 网关地址"
        if (target.isBlank()) return "请先填写微信目标 ID"
        return try {
            val ok = OpenClaw.send(
                baseUrl, sendPath, token, target,
                "✅ 短信转发测试：微信 ClawBot 配置成功！之后这台手机收到的短信会转发到这里。"
            )
            if (ok) {
                "✅ 微信 ClawBot 测试成功，去微信里看看吧"
            } else {
                "❌ 微信发送失败：检查网关地址 / 发送路径 / Token / 目标 ID，" +
                    "并确认网关正在运行、微信已扫码登录（openclaw channels login --channel openclaw-weixin）"
            }
        } catch (e: Exception) {
            "请求异常：${e.message}"
        }
    }

    /** 发一条消息到 Telegram，返回是否成功 */
    fun sendTelegram(token: String, chatId: String, text: String): Boolean {
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .toString()
        val resp = postJson(
            url = "https://api.telegram.org/bot$token/sendMessage",
            payload = payload,
            authKey = null,
            timeoutMs = 15000
        )
        return resp != null && JSONObject(resp).optBoolean("ok", false)
    }

    /**
     * 用 Hachimi (OpenAI 兼容接口) 给短信分类 + 一句话摘要。
     * 严格只要求模型输出 JSON，解析失败返回 null。
     */
    private fun classifyViaHachimi(
        key: String,
        model: String,
        from: String,
        body: String
    ): AiResult? {
        return try {
            val system = "你是短信预处理助手。判断这条短信是否值得转发到用户手机" +
                "(验证码、银行/支付通知、快递物流、政务/运营商重要通知值得转；营销广告、垃圾短信不值得转)，" +
                "并用一句话中文概括内容。严格只输出 JSON，不要输出其他任何内容，格式：" +
                "{\"forward\":true,\"type\":\"验证码\",\"summary\":\"...\"}"
            val payload = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_tokens", 200)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(
                            JSONObject().put("role", "user")
                                .put("content", "发件人：$from\n内容：$body")
                        )
                )
                .toString()
            val resp = postJson(
                url = "$HACHIMI_BASE/chat/completions",
                payload = payload,
                authKey = key,
                timeoutMs = 15000
            ) ?: return null
            val content = JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            // 宽松提取第一段 {...}
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(content.substring(start, end + 1))
            AiResult(
                forward = obj.optBoolean("forward", true),
                type = obj.optString("type", "其他"),
                summary = obj.optString("summary", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 连通性自检：发两个答案不同的极简问题，
     * 如果两次回答一模一样，说明对端是固定回复/假管道。
     * 返回一段可直接显示给用户的中文结论。
     */
    fun testHachimi(key: String, model: String): String {
        if (key.isBlank()) return "请先填写 Hachimi API Key"
        return try {
            val a1 = chatOnce(key, model, "只回复数字 7，不要输出其他任何内容。")
                ?: return "第 1 次请求失败：检查 Key / 模型名 / 网络"
            val a2 = chatOnce(key, model, "只回复数字 3，不要输出其他任何内容。")
                ?: return "第 2 次请求失败：检查 Key / 模型名 / 网络"
            if (a1.trim() == a2.trim()) {
                "⚠️ 两次不同问题的回答完全一样（均为：${a1.trim().take(40)}），" +
                    "这条线路疑似返回固定内容，AI 分类功能可能不可用，建议保持关闭。"
            } else {
                "✅ Hachimi 连通且返回正常（两次回答不同），可以启用 AI 预处理。"
            }
        } catch (e: Exception) {
            "请求异常：${e.message}"
        }
    }

    private fun chatOnce(key: String, model: String, prompt: String): String? {
        return try {
            val payload = JSONObject()
                .put("model", model)
                .put("temperature", 0.0)
                .put("max_tokens", 50)
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "user").put("content", prompt))
                )
                .toString()
            val resp = postJson(
                url = "$HACHIMI_BASE/chat/completions",
                payload = payload,
                authKey = key,
                timeoutMs = 15000
            ) ?: return null
            JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            null
        }
    }

    /** 通用 JSON POST，成功返回响应体字符串，失败返回 null */
    private fun postJson(
        url: String,
        payload: String,
        authKey: String?,
        timeoutMs: Int
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", BROWSER_UA)
            if (authKey != null) conn.setRequestProperty("Authorization", "Bearer $authKey")
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
}
