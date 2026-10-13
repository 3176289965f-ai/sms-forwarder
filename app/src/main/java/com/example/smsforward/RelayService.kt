package com.example.smsforward

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3 接收端前台服务：每 10 秒轮询一次 EC2 中转，有新短信就发通知栏通知。
 *
 * 前台服务 + 常驻通知是国产 ROM 下最稳的保活方式；
 * 另有 BootReceiver 在开机后自动恢复（如果停止前是运行状态）。
 */
class RelayService : Service() {

    companion object {
        const val ACTION_START = "com.example.smsforward.relay.START"
        const val ACTION_STOP = "com.example.smsforward.relay.STOP"
        private const val POLL_MS = 10_000L
        private const val CH_STATUS = "relay_status"
        private const val CH_SMS = "sms_incoming"
        private const val NOTIF_ID_STATUS = 1001
    }

    @Volatile
    private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (running) return START_STICKY
        running = true
        Settings.setReceiverRunning(this, true)
        createChannels()
        startForeground(NOTIF_ID_STATUS, statusNotification("正在连接中转…"))
        worker = Thread(::loop, "relay-poll").apply { start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        Settings.setReceiverRunning(this, false)
        super.onDestroy()
    }

    private fun loop() {
        var lastId = Settings.getLastRelayId(this)
        var okStreak = 0
        while (running) {
            try {
                val baseUrl = Settings.getRelayUrl(this).trim().trimEnd('/')
                val token = Settings.getRelayToken(this).trim()
                val msgs = Relay.poll(baseUrl, token, lastId)
                if (msgs != null) {
                    okStreak++
                    if (msgs.isNotEmpty()) {
                        for (m in msgs) {
                            if (m.id > lastId) {
                                InboxStore.add(this, m)
                                postSmsNotification(m)
                                lastId = m.id
                            }
                        }
                        Settings.setLastRelayId(this, lastId)
                    }
                    updateStatus("运行中 · 已同步到 #$lastId")
                } else {
                    okStreak = 0
                    updateStatus("连接中转失败，重试中…")
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
                updateStatus("轮询异常，重试中…")
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CH_STATUS, "中转服务状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "短信中转接收服务的常驻状态通知" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_SMS, "收到的短信",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "从发送端同步过来的短信" }
        )
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, ReceiverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, i, flags)
    }

    private fun statusNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CH_STATUS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("短信转发 · 短信接收中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()
    }

    private fun updateStatus(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_STATUS, statusNotification(text))
    }

    private fun postSmsNotification(m: Relay.Msg) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val time = if (m.receivedAt > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(m.receivedAt))
        } else {
            ""
        }
        val title = buildString {
            append("📩 ")
            append(if (m.sender.isNotBlank()) m.sender else "未知号码")
            if (time.isNotBlank()) append(" · ").append(time)
        }
        val content = buildString {
            if (m.note.isNotBlank()) append("🤖 ").append(m.note).append('\n')
            append(m.body)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CH_SMS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // 用消息 id 做通知 id，避免互相覆盖；int 范围截断足够用
        val notifId = 2000 + (m.id % 100000).toInt()
        nm.notify(
            notifId,
            b.setContentTitle(title)
                .setContentText(m.body.take(200))
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        )
    }
}
