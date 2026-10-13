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
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 液态玻璃顶栏上的水波纹（尽量符合真实水波物理）。
 *
 * 点左上角气泡时，以气泡为落水点泛起涟漪：
 * - 波前近匀速扩张（石头落水的重力波波前速度近恒定），全程约 3 秒；
 * - 色散：外圈波长更长（长波跑得快），内圈细密；
 * - 振幅按柱面扩散衰减（~1/sqrt(r)），越远越淡；
 * - 开头有一小团落水水花，随即化作涟漪。
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
    private val splashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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

    /** 以 (cx, cy) 为落水点泛起涟漪 */
    fun startRipple(cx: Float, cy: Float) {
        this.cx = cx
        this.cy = cy
        animator?.cancel()
        visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            interpolator = LinearInterpolator()
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
        val tn = (animator?.animatedValue as? Float) ?: return
        canvas.save()
        canvas.clipPath(clipPath)

        val maxR = width * 0.85f
        // 波前：近匀速扩张（指数 0.92 略带初速感）
        val front = maxR * tn.toDouble().pow(0.92).toFloat()

        // 涟漪列：色散，外圈波长更长
        val count = 12
        val lambda0 = maxR / 16f
        var s = 0f
        for (i in 0 until count) {
            val r = front - s
            s += lambda0 * (1f + 0.9f * i / count)
            if (r <= 4f * density || r > maxR) continue
            val decay = sqrt(1f - r / maxR) // 柱面扩散衰减
            val alpha = (decay * 170f * (1f - 0.25f * tn)).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, 90, 110, 255)
            paint.strokeWidth = 2.5f * density
            val ry = r * 0.36f
            // 轻微正弦起伏，有机水波感（真实涟漪接近正圆，起伏要小）
            val wobbleAmp = 4f * density * decay
            val phase = tn * 8f + i * 1.1f
            path.reset()
            val steps = 90
            for (k in 0..steps) {
                val a = k.toFloat() / steps * (Math.PI * 2).toFloat()
                val w = 1f + wobbleAmp * sin(5f * a + phase) / r.coerceAtLeast(1f)
                val px = cx + r * w * cos(a)
                val py = cy + ry * w * sin(a)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        // 落水点的初始水花：小圆盘迅速铺开并消失
        if (tn < 0.12f) {
            val sp = tn / 0.12f
            splashPaint.color = Color.argb((120 * (1f - sp)).toInt(), 90, 110, 255)
            canvas.drawCircle(cx, cy, 36f * density * sp, splashPaint)
        }

        canvas.restore()
    }
}
