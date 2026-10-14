package com.example.smsforward

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony

/**
 * v3.1 对账补发：发送端每 10 分钟扫一遍系统短信库，
 * 把广播没送达 / 进程被杀等原因漏掉的短信捞回来。
 *
 * 去重靠 ForwardedStore：广播路径处理过的，对账会跳过；
 * 对账捞回的走同一条 Forwarder.handleSms（同样的过滤规则）。
 */
object Reconciler {
    const val INTERVAL_MS = 10 * 60 * 1000L
    private const val REQ_CODE = 31001

    private fun pendingIntent(context: Context): PendingIntent {
        val i = Intent(context, ReconcileReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQ_CODE, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 安排下一次对账（每次执行完链式续约；开关关闭/重启后由调用方管理） */
    fun scheduleNext(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pendingIntent(context)
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 执行一次对账：在后台线程调用 */
    fun run(context: Context) {
        try {
            if (Settings.getRole(context) != Settings.ROLE_SENDER &&
                Settings.getRole(context) != Settings.ROLE_UNSET
            ) return
            if (!Settings.isReconcileEnabled(context)) return
            if (context.checkSelfPermission(Manifest.permission.READ_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) return

            // 记一笔：这次扫描的时间，底部小字展示用
            Settings.setLastScanTime(context, System.currentTimeMillis())

            var watermark = Settings.getReconcileWatermark(context)
            if (watermark == 0L) {
                // 首次对账：水位设为现在，只捞以后的短信，不翻旧账
                watermark = System.currentTimeMillis()
                Settings.setReconcileWatermark(context, watermark)
                return
            }

            var maxDate = watermark
            val cur = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?",
                arrayOf(watermark.toString()),
                "${Telephony.Sms.DATE} ASC LIMIT 100"
            )
            cur?.use {
                val iAddr = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (it.moveToNext()) {
                    val addr = it.getString(iAddr) ?: continue
                    val body = it.getString(iBody) ?: ""
                    val date = it.getLong(iDate)
                    if (date > maxDate) maxDate = date
                    if (body.isBlank()) continue
                    try {
                        Forwarder.handleSms(context, addr, body, date, isReconcile = true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            Settings.setReconcileWatermark(context, maxDate)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (Settings.isReconcileEnabled(context)) scheduleNext(context)
        }
    }
}
