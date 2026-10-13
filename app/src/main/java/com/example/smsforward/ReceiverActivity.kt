package com.example.smsforward

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v3 接收端页（装在看通知的主力机上）：
 * 中转设置 → 接收服务开关 → 收件箱。
 */
class ReceiverActivity : Activity() {

    private lateinit var tvServiceState: TextView
    private lateinit var btnToggleService: Button
    private lateinit var tvRxStatus: TextView
    private lateinit var llInbox: LinearLayout
    private var drawerOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)
        Settings.applyHideFromRecents(this)

        val etRelayUrl = findViewById<EditText>(R.id.etRelayUrl)
        val etRelayToken = findViewById<EditText>(R.id.etRelayToken)
        tvServiceState = findViewById(R.id.tvServiceState)
        btnToggleService = findViewById(R.id.btnToggleService)
        tvRxStatus = findViewById(R.id.tvRxStatus)
        llInbox = findViewById(R.id.llInbox)
        val btnSaveRelay = findViewById<Button>(R.id.btnSaveRelay)
        val btnTestRelayRx = findViewById<Button>(R.id.btnTestRelayRx)
        val btnRefreshInbox = findViewById<Button>(R.id.btnRefreshInbox)
        val btnClearInbox = findViewById<Button>(R.id.btnClearInbox)
        val btnSwitchRole = findViewById<Button>(R.id.btnSwitchRole)
        val btnBatteryWhitelist = findViewById<Button>(R.id.btnBatteryWhitelist)

        etRelayUrl.setText(Settings.getRelayUrl(this))
        etRelayToken.setText(Settings.getRelayToken(this))

        // Android 13+ 需要动态申请通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        btnSaveRelay.setOnClickListener {
            Settings.setRelayUrl(this, etRelayUrl.text.toString().trim().trimEnd('/'))
            Settings.setRelayToken(this, etRelayToken.text.toString().trim())
            tvRxStatus.text = "✅ 中转设置已保存"
        }

        btnTestRelayRx.setOnClickListener {
            val baseUrl = etRelayUrl.text.toString().trim().trimEnd('/')
            val token = etRelayToken.text.toString().trim()
            tvRxStatus.text = "正在连接中转…"
            Thread {
                val result = Relay.testPoll(baseUrl, token)
                runOnUiThread { tvRxStatus.text = result }
            }.start()
        }

        btnToggleService.setOnClickListener {
            if (isServiceRunning()) {
                stopService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
                // 给 onDestroy 一点时间再刷新状态
                btnToggleService.postDelayed({ refreshServiceState() }, 500)
            } else {
                val baseUrl = Settings.getRelayUrl(this).trim()
                val token = Settings.getRelayToken(this).trim()
                if (baseUrl.isBlank() || token.isBlank()) {
                    tvRxStatus.text = "请先填写并保存中转地址和 Token"
                    return@setOnClickListener
                }
                val svc = Intent(this, RelayService::class.java).setAction(RelayService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
                btnToggleService.postDelayed({ refreshServiceState() }, 800)
            }
        }

        btnRefreshInbox.setOnClickListener { renderInbox() }
        btnClearInbox.setOnClickListener {
            InboxStore.clear(this)
            renderInbox()
            tvRxStatus.text = "收件箱已清空（不影响中转队列）"
        }

        btnSwitchRole.setOnClickListener {
            if (isServiceRunning()) {
                stopService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
            }
            Settings.setRole(this, Settings.ROLE_UNSET)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        btnBatteryWhitelist.setOnClickListener { requestBatteryWhitelist() }

        val swHideRecents = findViewById<Switch>(R.id.swHideRecents)
        swHideRecents.isChecked = Settings.isHideFromRecents(this)
        swHideRecents.setOnCheckedChangeListener { _, checked ->
            Settings.setHideFromRecents(this, checked)
            Settings.applyHideFromRecents(this)
        }

        val swHideNotif = findViewById<Switch>(R.id.swHideNotif)
        swHideNotif.isChecked = Settings.isHideStatusNotif(this)
        swHideNotif.setOnCheckedChangeListener { _, checked ->
            Settings.setHideStatusNotif(this, checked)
            // 服务运行时立即重建通知通道并刷新，实时生效
            if (isServiceRunning()) {
                startService(
                    Intent(this, RelayService::class.java)
                        .setAction(RelayService.ACTION_UPDATE_CHANNEL)
                )
            }
        }

        // ---- 保活设置侧边栏 ----
        val drawerPanel = findViewById<LinearLayout>(R.id.drawerPanel)
        val drawerScrim = findViewById<View>(R.id.drawerScrim)
        // 首帧先藏到屏幕左侧，避免闪现
        drawerPanel.post { drawerPanel.translationX = -drawerPanel.width.toFloat() }
        findViewById<Button>(R.id.btnKeepAlive).setOnClickListener { setDrawer(true) }
        drawerScrim.setOnClickListener { setDrawer(false) }

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

    override fun onResume() {
        super.onResume()
        refreshServiceState()
        renderInbox()
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == RelayService::class.java.name }
    }

    /**
     * 一键跳转电池优化白名单（注意：本文件的 Settings 是 App 自己的配置类，
     * 这里必须用全限定的 android.provider.Settings）。
     */
    private fun requestBatteryWhitelist() {
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
                tvRxStatus.text = "无法自动打开，请手动前往：设置 → 电池 → 短信转发 → 无限制"
            }
        }
    }

    private fun refreshServiceState() {
        val running = isServiceRunning()
        tvServiceState.text = if (running) {
            val last = Settings.getLastRelayId(this)
            "● 服务运行中（已同步到 #$last）"
        } else {
            "○ 服务未运行"
        }
        btnToggleService.text = if (running) "停止接收服务" else "启动接收服务"
    }

    private fun renderInbox() {
        llInbox.removeAllViews()
        val items = InboxStore.all(this).take(30)
        if (items.isEmpty()) {
            val tv = TextView(this).apply {
                text = "暂无消息。启动接收服务后，发送端的短信会显示在这里。"
                setTextColor(getColor(R.color.glass_text_hint))
                textSize = 14f
                setPadding(4, 12, 4, 12)
            }
            llInbox.addView(tv)
            return
        }
        val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (it in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.glass_input)
                setPadding(16, 12, 16, 12)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 8
                layoutParams = lp
            }
            val head = TextView(this).apply {
                text = buildString {
                    append(if (it.sender.isNotBlank()) it.sender else "未知号码")
                    if (it.receivedAt > 0) {
                        append(" · ").append(timeFmt.format(Date(it.receivedAt)))
                    }
                    if (it.device.isNotBlank()) append(" · ").append(it.device)
                }
                setTextColor(getColor(R.color.glass_text_secondary))
                textSize = 12f
            }
            val body = TextView(this).apply {
                text = if (it.note.isNotBlank()) "🤖 ${it.note}\n${it.body}" else it.body
                setTextColor(getColor(R.color.glass_text_primary))
                textSize = 15f
                setPadding(0, 4, 0, 0)
            }
            card.addView(head)
            card.addView(body)
            card.gravity = Gravity.START
            llInbox.addView(card)
        }
    }
}
