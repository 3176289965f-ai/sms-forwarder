package com.example.smsforward

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 对账闹钟触发：在后台线程跑一次对账，然后链式安排下一次 */
class ReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Reconciler.run(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
