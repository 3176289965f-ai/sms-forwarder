package com.example.smsforward

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/**
 * v3 发送端配置页（装在收短信的备用机上）：
 * 转发目标（直连中转 / Telegram）→ 中转设置 → Telegram 设置 →
 * 微信 ClawBot（折叠隐藏，灰度保留）→ Hachimi AI → 关键词过滤 → 保活 → 测试。
 * 网络操作都在后台线程，主线程只更新 UI。
 */
class SenderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)

        val rgTarget = findViewById<RadioGroup>(R.id.rgTarget)
        val rbRelay = findViewById<RadioButton>(R.id.rbRelay)
        val rbTelegram = findViewById<RadioButton>(R.id.rbTelegram)
        val etRelayUrl = findViewById<EditText>(R.id.etRelayUrl)
        val etRelayToken = findViewById<EditText>(R.id.etRelayToken)
        val etDeviceName = findViewById<EditText>(R.id.etDeviceName)
        val etToken = findViewById<EditText>(R.id.etToken)
        val etChatId = findViewById<EditText>(R.id.etChatId)
        val etOcBaseUrl = findViewById<EditText>(R.id.etOcBaseUrl)
        val etOcToken = findViewById<EditText>(R.id.etOcToken)
        val etOcSendPath = findViewById<EditText>(R.id.etOcSendPath)
        val etWechatTarget = findViewById<EditText>(R.id.etWechatTarget)
        val etHachimiKey = findViewById<EditText>(R.id.etHachimiKey)
        val etModel = findViewById<EditText>(R.id.etModel)
        val etKeywords = findViewById<EditText>(R.id.etKeywords)
        val swAi = findViewById<Switch>(R.id.swAi)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnTestRelay = findViewById<Button>(R.id.btnTestRelay)
        val btnTestTelegram = findViewById<Button>(R.id.btnTestTelegram)
        val btnTestAi = findViewById<Button>(R.id.btnTestAi)
        val btnWechatExpand = findViewById<Button>(R.id.btnWechatExpand)
        val wechatCollapsed = findViewById<LinearLayout>(R.id.wechatCollapsed)
        val btnSwitchRole = findViewById<Button>(R.id.btnSwitchRole)
        val btnBatteryWhitelist = findViewById<Button>(R.id.btnBatteryWhitelist)

        // 读取已保存的配置
        when (Settings.getForwardTarget(this)) {
            Settings.TARGET_TELEGRAM -> rbTelegram.isChecked = true
            else -> rbRelay.isChecked = true // 默认直连中转（含从微信迁移过来的）
        }
        etRelayUrl.setText(Settings.getRelayUrl(this))
        etRelayToken.setText(Settings.getRelayToken(this))
        etDeviceName.setText(Settings.getDeviceName(this))
        etToken.setText(Settings.getTelegramToken(this))
        etChatId.setText(Settings.getTelegramChatId(this))
        // 微信配置只读展示（灰度保留）
        etOcBaseUrl.setText(Settings.getOcBaseUrl(this))
        etOcToken.setText(Settings.getOcToken(this))
        etOcSendPath.setText(Settings.getOcSendPath(this))
        etWechatTarget.setText(Settings.getWechatTarget(this))
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

        // 断网积压重试
        Thread {
            val left = RelayOutbox.drain(applicationContext)
            val pending = RelayOutbox.pendingCount(applicationContext)
            runOnUiThread {
                if (pending > 0) {
                    tvStatus.text = "⚠️ 还有 $pending 条短信未发出（网络恢复后自动重试）"
                } else if (left == 0 && pending == 0) {
                    // 保持默认状态文字
                }
            }
        }.start()

        btnWechatExpand.setOnClickListener {
            val show = wechatCollapsed.visibility != View.VISIBLE
            wechatCollapsed.visibility = if (show) View.VISIBLE else View.GONE
            btnWechatExpand.text = if (show) {
                "微信 ClawBot 设置（已暂停，点击收起）"
            } else {
                "微信 ClawBot 设置（已暂停，点击展开查看）"
            }
        }

        btnSave.setOnClickListener {
            val target = when (rgTarget.checkedRadioButtonId) {
                R.id.rbTelegram -> Settings.TARGET_TELEGRAM
                else -> Settings.TARGET_RELAY
            }
            Settings.setForwardTarget(this, target)
            Settings.setRelayUrl(this, etRelayUrl.text.toString().trim().trimEnd('/'))
            Settings.setRelayToken(this, etRelayToken.text.toString().trim())
            val devName = etDeviceName.text.toString().trim().ifEmpty { "sender" }
            Settings.setDeviceName(this, devName)
            Settings.setTelegramToken(this, etToken.text.toString().trim())
            Settings.setTelegramChatId(this, etChatId.text.toString().trim())
            Settings.setHachimiKey(this, etHachimiKey.text.toString().trim())
            val model = etModel.text.toString().trim().ifEmpty { "gpt-6-astra" }
            Settings.setHachimiModel(this, model)
            Settings.setKeywords(this, etKeywords.text.toString().trim())
            Settings.setAiEnabled(this, swAi.isChecked)
            tvStatus.text = "✅ 设置已保存（存放在本机私有目录）"
        }

        btnTestRelay.setOnClickListener {
            val baseUrl = etRelayUrl.text.toString().trim().trimEnd('/')
            val token = etRelayToken.text.toString().trim()
            val devName = etDeviceName.text.toString().trim().ifEmpty { "sender" }
            tvStatus.text = "正在经由 EC2 中转发送测试消息…"
            Thread {
                val result = Relay.test(baseUrl, token, devName)
                runOnUiThread { tvStatus.text = result }
            }.start()
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
                    "✅ 短信转发测试：Telegram 配置成功！之后这台手机收到的短信会转发到这里。"
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

        btnSwitchRole.setOnClickListener {
            Settings.setRole(this, Settings.ROLE_UNSET)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        btnBatteryWhitelist.setOnClickListener {
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply { data = android.net.Uri.parse("package:$packageName") }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                } catch (e2: Exception) {
                    tvStatus.text = "无法自动打开，请手动前往：设置 → 电池 → 短信转发 → 无限制"
                }
            }
        }

        LiquidHeader.attach(this)
    }
}
