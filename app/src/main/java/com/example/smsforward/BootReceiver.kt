package com.example.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启：如果停止前接收服务在运行，开机后自动恢复。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // v3.1 发送端：重启后恢复对账闹钟
        if (Settings.getRole(context) == Settings.ROLE_SENDER &&
            Settings.isReconcileEnabled(context)
        ) {
            Reconciler.scheduleNext(context)
        }
        if (Settings.getRole(context) != Settings.ROLE_RECEIVER) return
        if (!Settings.isReceiverRunning(context)) return
        val svc = Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
