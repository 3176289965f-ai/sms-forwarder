package com.example.smsforward

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 液态玻璃顶栏上的水波纹。
 *
 * 点左上角气泡时，以气泡为中心泛起横向波浪（椭圆波环 + 正弦起伏，
 * 模拟水波在玻璃上荡开），约 1.5 秒后淡出。
 *
 * 盖在顶栏最上层，只负责画，不吃点击（触摸会透下去给气泡）。
 */
class WaveRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val clipPath = Path()
    private var animator: ValueAnimator? = null
    private var cx = 0f
    private var cy = 0f

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 裁在顶栏圆角里（与 liquid_glass_base 的 28dp 圆角一致）
        val r = 28f * density
        clipPath.reset()
        clipPath.addRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            r, r, Path.Direction.CW
        )
    }

    /** 以 (cx, cy) 为中心泛起波浪 */
    fun startRipple(cx: Float, cy: Float) {
        this.cx = cx
        this.cy = cy
        animator?.cancel()
        visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            interpolator = DecelerateInterpolator()
            addUpdateListener { invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = (animator?.animatedValue as? Float) ?: return
        canvas.save()
        canvas.clipPath(clipPath)
        // 石头落水式涟漪：一圈圈细密、等间距向外荡开，振幅随距离衰减
        val maxR = width * 0.85f
        val count = 10
        val wavelength = maxR / (count - 1)
        val travel = maxR * 1.15f // 整个动画期间波前走过的距离
        for (i in 0 until count + 2) {
            val r = p * travel - i * wavelength
            if (r <= 4f * density || r > maxR) continue
            val fade = 1f - r / maxR // 离中心越远越淡
            val alpha = (fade * 170).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, 90, 110, 255)
            paint.strokeWidth = 2.5f * density
            val ry = r * 0.36f
            // 轻微正弦起伏，有机水波感
            val wobbleAmp = 6f * density * fade
            val phase = p * 10f + i * 1.3f
            path.reset()
            val steps = 90
            for (k in 0..steps) {
                val a = k.toFloat() / steps * (Math.PI * 2).toFloat()
                val w = 1f + wobbleAmp * sin(7f * a + phase) / r.coerceAtLeast(1f)
                val px = cx + r * w * cos(a)
                val py = cy + ry * w * sin(a)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
        canvas.restore()
    }
}
