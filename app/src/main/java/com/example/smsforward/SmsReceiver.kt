package com.example.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 短信到达广播接收器。收到短信后起后台线程做过滤/AI/转发，
 * 用 goAsync() 保证处理完成前进程不被杀掉。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        Thread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isNotEmpty()) {
                    // 长短信会被拆成多段，这里拼回完整内容
                    val from = parts[0].originatingAddress ?: "未知号码"
                    val body = parts.joinToString("") { it.messageBody ?: "" }
                    if (body.isNotBlank()) {
                        Forwarder.handleSms(
                            context.applicationContext,
                            from,
                            body,
                            parts[0].timestampMillis
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pending.finish()
            }
        }.start()
    }
}
