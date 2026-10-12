package com.example.smsforward

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.sin

/**
 * v2.2 配置页（液态玻璃顶栏 + 通透玻璃 UI）：
 * 顶栏固定，内容从下方穿过时做折射；转发目标（Telegram / 微信 ClawBot / 两者）→
 * 各通道配置 → Hachimi AI → 关键词过滤 → 保活提示 → 测试按钮。
 * 网络操作都在后台线程，主线程只更新 UI。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rgTarget = findViewById<RadioGroup>(R.id.rgTarget)
        val rbTelegram = findViewById<RadioButton>(R.id.rbTelegram)
        val rbWechat = findViewById<RadioButton>(R.id.rbWechat)
        val rbBoth = findViewById<RadioButton>(R.id.rbBoth)
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
        val btnTestTelegram = findViewById<Button>(R.id.btnTestTelegram)
        val btnTestWechat = findViewById<Button>(R.id.btnTestWechat)
        val btnTestAi = findViewById<Button>(R.id.btnTestAi)

        // 读取已保存的配置
        when (Settings.getForwardTarget(this)) {
            Settings.TARGET_WECHAT -> rbWechat.isChecked = true
            Settings.TARGET_BOTH -> rbBoth.isChecked = true
            else -> rbTelegram.isChecked = true
        }
        etToken.setText(Settings.getTelegramToken(this))
        etChatId.setText(Settings.getTelegramChatId(this))
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

        btnSave.setOnClickListener {
            val target = when (rgTarget.checkedRadioButtonId) {
                R.id.rbWechat -> Settings.TARGET_WECHAT
                R.id.rbBoth -> Settings.TARGET_BOTH
                else -> Settings.TARGET_TELEGRAM
            }
            Settings.setForwardTarget(this, target)
            Settings.setTelegramToken(this, etToken.text.toString().trim())
            Settings.setTelegramChatId(this, etChatId.text.toString().trim())
            Settings.setOcBaseUrl(this, etOcBaseUrl.text.toString().trim().trimEnd('/'))
            Settings.setOcToken(this, etOcToken.text.toString().trim())
            val sendPath = etOcSendPath.text.toString().trim().ifEmpty { "/rpc" }
            Settings.setOcSendPath(this, sendPath)
            Settings.setWechatTarget(this, etWechatTarget.text.toString().trim())
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

        btnTestWechat.setOnClickListener {
            val baseUrl = etOcBaseUrl.text.toString().trim().trimEnd('/')
            val token = etOcToken.text.toString().trim()
            val sendPath = etOcSendPath.text.toString().trim().ifEmpty { "/rpc" }
            val target = etWechatTarget.text.toString().trim()
            tvStatus.text = "正在经由 OpenClaw 网关发送测试消息…"
            Thread {
                val result = Forwarder.testWechat(baseUrl, sendPath, token, target)
                runOnUiThread { tvStatus.text = result }
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

        // ---- 液态玻璃顶栏：只显示玻璃处理过的那一层内容 ----
        // 顶栏区域完全由"玻璃层"覆盖：先画与背后一模一样的背景，再把内容做固定
        // 的荡漾折射画上去——原字被盖住不重影；无持续动画，静止时也保持玻璃感。
        val scrollView = findViewById<ScrollView>(R.id.scrollView)
        val contentLayout = findViewById<LinearLayout>(R.id.contentLayout)
        val liquidHeader = findViewById<FrameLayout>(R.id.liquidHeader)
        val refractionView = findViewById<ImageView>(R.id.refractionView)
        // 圆角裁剪 + 极轻微纵向拉伸
        refractionView.clipToOutline = true
        refractionView.pivotY = 0f
        refractionView.scaleY = 1.04f
        val bgDrawable = resources.getDrawable(R.drawable.glass_bg, theme)
        var lastBmp: Bitmap? = null
        var lastUpdate = 0L
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect()
        val dstRect = RectF()
        fun updateRefraction() {
            val w = liquidHeader.width
            val h = liquidHeader.height
            if (w <= 0 || h <= 0) return
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 33) return // 节流：约 30fps
            lastUpdate = now
            val s = 0.33f // 低分辨率抓取，放大即天然柔化
            val bw = (w * s).toInt().coerceAtLeast(1)
            val bh = (h * s).toInt().coerceAtLeast(1)
            val offsetY = (liquidHeader.top - scrollView.top).toFloat()
            // 1) 抓取顶栏后方的内容区域
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(s, s)
            c.translate(0f, -(offsetY + scrollView.scrollY))
            contentLayout.draw(c)
            // 2) 不透明合成：背景 + 固定荡漾折射条带（原字被完全盖住，只留玻璃层）
            val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val oc = Canvas(out)
            oc.save()
            oc.scale(s, s)
            bgDrawable.setBounds(0, 0, scrollView.width, scrollView.height)
            oc.translate(0f, -offsetY)
            bgDrawable.draw(oc)
            oc.restore()
            val strips = 12
            val sh = bh / strips.toFloat()
            for (i in 0 until strips) {
                val top = (i * sh).toInt()
                val bottom = ((i + 1) * sh).toInt().coerceAtMost(bh)
                if (bottom <= top) continue
                srcRect.set(0, top, bw, bottom)
                val dx = sin(i * 1.7f) * 2.5f * s
                dstRect.set(dx, top.toFloat(), bw + dx, bottom.toFloat())
                oc.drawBitmap(bmp, srcRect, dstRect, paint)
            }
            bmp.recycle()
            refractionView.setImageBitmap(out)
            lastBmp?.recycle()
            lastBmp = out
        }
        scrollView.viewTreeObserver.addOnScrollChangedListener { updateRefraction() }
        liquidHeader.post { updateRefraction() }
    }
}
