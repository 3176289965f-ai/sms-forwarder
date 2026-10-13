package com.example.smsforward

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * v3 入口：角色路由。
 * 首次打开先选角色（发送端 = 收短信的备用机 / 接收端 = 看通知的主力机），
 * 之后直接进对应页面。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v3 升级迁移：微信通道已折叠停用，老配置自动切到直连中转
        val migrated = Settings.migrateWechatTargetIfNeeded(this)
        // 顺手把断网期间积压的消息发出
        Thread { RelayOutbox.drain(applicationContext) }.start()

        when (Settings.getRole(this)) {
            Settings.ROLE_SENDER -> {
                startActivity(Intent(this, SenderActivity::class.java))
                finish()
                return
            }
            Settings.ROLE_RECEIVER -> {
                startActivity(Intent(this, ReceiverActivity::class.java))
                finish()
                return
            }
        }

        setContentView(R.layout.activity_role)
        Settings.applyHideFromRecents(this)
        if (migrated) {
            findViewById<TextView>(R.id.tvRoleNotice).text =
                "ℹ️ v3 更新：微信 ClawBot 通道已折叠停用（iLink 限制），" +
                    "你的转发目标已自动切换为「直连中转」，网关配置原样保留。"
        }

        findViewById<Button>(R.id.btnRoleSender).setOnClickListener {
            Settings.setRole(this, Settings.ROLE_SENDER)
            startActivity(Intent(this, SenderActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btnRoleReceiver).setOnClickListener {
            Settings.setRole(this, Settings.ROLE_RECEIVER)
            startActivity(Intent(this, ReceiverActivity::class.java))
            finish()
        }
        LiquidHeader.attach(this)
    }
}
