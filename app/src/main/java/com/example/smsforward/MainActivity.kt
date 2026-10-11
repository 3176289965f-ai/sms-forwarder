package com.example.smsforward

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/**
 * 配置页：Telegram Bot Token / Chat ID、Hachimi Key、AI 开关、关键词过滤，
 * 以及两个一键测试按钮。网络操作都在后台线程，主线程只更新 UI。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etToken = findViewById<EditText>(R.id.etToken)
        val etChatId = findViewById<EditText>(R.id.etChatId)
        val etHachimiKey = findViewById<EditText>(R.id.etHachimiKey)
        val etModel = findViewById<EditText>(R.id.etModel)
        val etKeywords = findViewById<EditText>(R.id.etKeywords)
        val swAi = findViewById<Switch>(R.id.swAi)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnTestTelegram = findViewById<Button>(R.id.btnTestTelegram)
        val btnTestAi = findViewById<Button>(R.id.btnTestAi)

        // 读取已保存的配置
        etToken.setText(Settings.getTelegramToken(this))
        etChatId.setText(Settings.getTelegramChatId(this))
        etHachimiKey.setText(Settings.getHachimiKey(this))
        etModel.setText(Settings.getHachimiModel(this))
        etKeywords.setText(Settings.getKeywords(this))
        swAi.isChecked = Settings.isAiEnabled(this)

        // 首次打开申请短信权限
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 100)
        }

        btnSave.setOnClickListener {
            Settings.setTelegramToken(this, etToken.text.toString().trim())
            Settings.setTelegramChatId(this, etChatId.text.toString().trim())
            Settings.setHachimiKey(this, etHachimiKey.text.toString().trim())
            val model = etModel.text.toString().trim().ifEmpty { "gpt-6-astra" }
            Settings.setHachimiModel(this, model)
            Settings.setKeywords(this, etKeywords.text.toString().trim())
            Settings.setAiEnabled(this, swAi.isChecked)
            tvStatus.text = "✅ 设置已保存（存放在本机私有目录）"
        }

        btnTestTelegram.setOnClickListener {
            val token = etToken.text.toString().trim()
            val chatId = etChatId.text.toString().trim()
            if (token.isBlank() || chatId.isBlank()) {
                tvStatus.text = "请先填写 Bot Token 和 Chat ID"
                return@setOnClickListener
            }
            tvStatus.text = "正在发送测试消息…"
            Thread {
                val ok = Forwarder.sendTelegram(
                    token,
                    chatId,
                    "✅ 短信转发测试：配置成功！之后这台手机收到的短信会转发到这里。"
                )
                runOnUiThread {
                    tvStatus.text = if (ok) {
                        "✅ Telegram 测试成功，去聊天里看看吧"
                    } else {
                        "❌ Telegram 发送失败：检查 Token / Chat ID / 网络（Telegram 在国内需境外网络）"
                    }
                }
            }.start()
        }

        btnTestAi.setOnClickListener {
            tvStatus.text = "正在请求 Hachimi…"
            val key = etHachimiKey.text.toString().trim()
            val model = etModel.text.toString().trim().ifEmpty { "gpt-6-astra" }
            Thread {
                val result = Forwarder.testHachimi(key, model)
                runOnUiThread { tvStatus.text = result }
            }.start()
        }
    }
}
