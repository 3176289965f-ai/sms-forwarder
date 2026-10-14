package com.example.smsforward

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/**
 * v3 发送端配置页（装在收短信的备用机上）：
 * 转发目标（直连中转 / Telegram）→ 直连中转设置（点击展开）→ Telegram 设置（点击展开）→
 * 微信 ClawBot（折叠隐藏，灰度保留）→ Hachimi AI 预处理（点击展开）→ 关键词过滤 → 已发送/已补发/已屏蔽记录。
 * 保活设置 + 测试连通性收进左侧折叠栏（顶栏左上角气泡打开，点测试会自动收起折叠栏）。
 * 三个点击展开卡片：已配好之后默认收起（页面清爽），首次未配完的才默认展开。
 * 网络操作都在后台线程，主线程只更新 UI。
 */
class SenderActivity : Activity() {

    private var drawerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)
        Settings.applyHideFromRecents(this)

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
        val target = Settings.getForwardTarget(this)
        when (target) {
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
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
                100
            )
        } else if (Settings.isReconcileEnabled(this)) {
            // 权限齐了且对账开关开着：确保闹钟在跑
            Reconciler.scheduleNext(this)
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

        // 三个点击展开的卡片：已配完默认收起，首次未配完（或 AI 开启中）才默认展开
        setupExpander(
            findViewById(R.id.headerRelay),
            findViewById(R.id.bodyRelay),
            findViewById(R.id.chevronRelay),
            expanded = target == Settings.TARGET_RELAY &&
                    (Settings.getRelayUrl(this).isBlank() ||
                            Settings.getRelayToken(this).isBlank())
        )
        setupExpander(
            findViewById(R.id.headerTelegram),
            findViewById(R.id.bodyTelegram),
            findViewById(R.id.chevronTelegram),
            expanded = target == Settings.TARGET_TELEGRAM &&
                    (Settings.getTelegramToken(this).isBlank() ||
                            Settings.getTelegramChatId(this).isBlank())
        )
        setupExpander(
            findViewById(R.id.headerAi),
            findViewById(R.id.bodyAi),
            findViewById(R.id.chevronAi),
            expanded = Settings.isAiEnabled(this)
        )

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
            setDrawer(false)
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
            setDrawer(false)
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
            setDrawer(false)
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

        val swHideRecents = findViewById<Switch>(R.id.swHideRecents)
        swHideRecents.isChecked = Settings.isHideFromRecents(this)
        swHideRecents.setOnCheckedChangeListener { _, checked ->
            Settings.setHideFromRecents(this, checked)
            Settings.applyHideFromRecents(this)
        }

        // ---- 记录卡：已发送 / 已补发 / 已屏蔽 ----
        findViewById<Button>(R.id.btnSentRefresh).setOnClickListener {
            renderLogCard(R.id.llSentLog, SENT_KINDS)
        }
        findViewById<Button>(R.id.btnResendRefresh).setOnClickListener {
            renderLogCard(R.id.llResendLog, RESEND_KINDS)
        }
        findViewById<Button>(R.id.btnBlockedRefresh).setOnClickListener {
            renderLogCard(R.id.llBlockedLog, BLOCKED_KINDS)
        }
        findViewById<Button>(R.id.btnLogClear).setOnClickListener {
            SendLog.clear(this)
            refreshAllLogCards()
        }
        refreshAllLogCards()
        updateLastScan()

        // ---- v3.1 对账补发开关 ----
        val swReconcile = findViewById<Switch>(R.id.swReconcile)
        swReconcile.isChecked = Settings.isReconcileEnabled(this)
        swReconcile.setOnCheckedChangeListener { _, checked ->
            Settings.setReconcileEnabled(this, checked)
            if (checked) {
                if (checkSelfPermission(Manifest.permission.READ_SMS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    // 首次开启：水位设为现在，不翻旧账
                    if (Settings.getReconcileWatermark(this) == 0L) {
                        Settings.setReconcileWatermark(this, System.currentTimeMillis())
                    }
                    Reconciler.scheduleNext(this)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_SMS), 101)
                }
            } else {
                Reconciler.cancel(this)
            }
        }

        // ---- 左侧折叠栏（保活设置 + 测试连通性）：顶栏左上角气泡打开 ----
        val drawerPanel = findViewById<LinearLayout>(R.id.drawerPanel)
        val drawerScrim = findViewById<View>(R.id.drawerScrim)
        // 首帧先藏到屏幕左侧，避免闪现
        drawerPanel.post { drawerPanel.translationX = -drawerPanel.width.toFloat() }
        drawerScrim.setOnClickListener { setDrawer(false) }

        val bubble = findViewById<ImageView>(R.id.headerBubble)
        bubble.setOnClickListener { setDrawer(true) }

        LiquidHeader.attach(this)
    }

    /** 左侧边栏开关（带遮罩淡入淡出） */
    private fun setDrawer(open: Boolean) {
        drawerOpen = open
        val panel = findViewById<LinearLayout>(R.id.drawerPanel)
        val scrim = findViewById<View>(R.id.drawerScrim)
        if (open) {
            panel.visibility = View.VISIBLE
            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(0.45f).setDuration(180).start()
            panel.animate().translationX(0f).setDuration(240).start()
        } else {
            scrim.animate().alpha(0f).setDuration(180).withEndAction {
                scrim.visibility = View.GONE
            }.start()
            panel.animate().translationX(-panel.width.toFloat()).setDuration(240)
                .withEndAction { panel.visibility = View.INVISIBLE }.start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerOpen) {
            setDrawer(false)
            return
        }
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100 && requestCode != 101) return
        val idx = permissions.indexOf(Manifest.permission.READ_SMS)
        val granted = idx >= 0 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (Settings.isReconcileEnabled(this)) {
                if (Settings.getReconcileWatermark(this) == 0L) {
                    Settings.setReconcileWatermark(this, System.currentTimeMillis())
                }
                Reconciler.scheduleNext(this)
            }
        } else {
            // 拒绝了读取短信权限：对账无法工作，自动关闭开关
            Settings.setReconcileEnabled(this, false)
            findViewById<Switch>(R.id.swReconcile)?.isChecked = false
            findViewById<TextView>(R.id.tvStatus)?.text =
                "⚠️ 未授予「读取短信」权限，对账补发已关闭"
        }
    }

    /** 点击展开/收起一个卡片，同步箭头朝向 */
    private fun setupExpander(
        header: LinearLayout,
        body: LinearLayout,
        chevron: TextView,
        expanded: Boolean
    ) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.text = if (expanded) "▾" else "▸"
        header.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            chevron.text = if (show) "▾" else "▸"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllLogCards()
        updateLastScan()
    }

    companion object {
        private val SENT_KINDS = setOf("已发送", "未发送", "已入队")
        private val RESEND_KINDS = setOf("补发")
        private val BLOCKED_KINDS = setOf("已过滤")
    }

    /** 刷新三张记录卡 */
    private fun refreshAllLogCards() {
        renderLogCard(R.id.llSentLog, SENT_KINDS)
        renderLogCard(R.id.llResendLog, RESEND_KINDS)
        renderLogCard(R.id.llBlockedLog, BLOCKED_KINDS)
    }

    /** 渲染一张记录卡（指定类型，最新 20 条） */
    private fun renderLogCard(containerId: Int, kinds: Set<String>) {
        val ll = findViewById<LinearLayout>(containerId)
        ll.removeAllViews()
        val events = SendLog.list(this).filter { it.kind in kinds }.take(20)
        if (events.isEmpty()) {
            val tv = TextView(this)
            tv.text = "暂无记录"
            tv.setTextColor(resources.getColor(R.color.glass_text_hint, theme))
            tv.textSize = 13f
            tv.setPadding(0, 8, 0, 8)
            ll.addView(tv)
            return
        }
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (e in events) {
            val tv = TextView(this)
            val colorRes = when (e.kind) {
                "已发送" -> R.color.glass_accent
                "已过滤", "未发送" -> android.R.color.holo_red_dark
                "已入队", "补发" -> android.R.color.holo_orange_dark
                else -> R.color.glass_text_secondary
            }
            tv.text = "${sdf.format(java.util.Date(e.time))} · ${e.kind} · ${e.detail}"
            tv.setTextColor(resources.getColor(colorRes, theme))
            tv.textSize = 13f
            tv.setPadding(0, 8, 0, 8)
            ll.addView(tv)
        }
    }

    /** 底部序列号小字：上次扫描短信库时间 */
    private fun updateLastScan() {
        val tv = findViewById<TextView>(R.id.tvLastScan) ?: return
        val t = Settings.getLastScanTime(this)
        tv.text = if (t == 0L) {
            "上次扫描短信库 --"
        } else {
            val sdf = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
            )
            "上次扫描短信库 ${sdf.format(java.util.Date(t))}"
        }
    }
}
