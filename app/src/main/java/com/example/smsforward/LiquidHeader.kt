package com.example.smsforward

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.sin

/**
 * 液态玻璃顶栏（v2.2 定稿效果，零模糊滤镜）：
 * 顶栏固定，内容从下方穿过时做折射；顶栏区域完全由"玻璃层"覆盖——
 * 先画与背后一模一样的背景，再把内容做固定的荡漾折射画上去，
 * 原字被盖住不重影；无持续动画，静止时也保持玻璃感。
 *
 * 调用方布局里需要：ScrollView(id=scrollView) > LinearLayout(id=contentLayout)，
 * 以及 FrameLayout(id=liquidHeader) > ImageView(id=refractionView)。
 */
object LiquidHeader {

    fun attach(activity: Activity) {
        val scrollView = activity.findViewById<ScrollView>(R.id.scrollView) ?: return
        val contentLayout = activity.findViewById<LinearLayout>(R.id.contentLayout) ?: return
        val liquidHeader = activity.findViewById<FrameLayout>(R.id.liquidHeader) ?: return
        val refractionView = activity.findViewById<ImageView>(R.id.refractionView) ?: return

        refractionView.clipToOutline = true
        refractionView.pivotY = 0f
        refractionView.scaleY = 1.04f
        val bgDrawable = activity.resources.getDrawable(R.drawable.glass_bg, activity.theme)
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
