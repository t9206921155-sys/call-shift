package fi.callshift.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import fi.callshift.app.R
import kotlin.math.abs

/**
 * «Проведите, чтобы ответить»: круглая ручка в центре дорожки.
 * Вправо — ответить (зелёный), влево — отклонить (красный).
 * Короткое нажатие на значки по краям тоже работает.
 */
class SwipeCallView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var onAnswer: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val green = 0xFF43A047.toInt()
    private val red = 0xFFE53935.toInt()
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt(); textSize = 13 * dp; textAlign = Paint.Align.CENTER
    }
    private val icCall = ContextCompat.getDrawable(ctx, R.drawable.ic_call)!!.mutate()
    private val icEnd = ContextCompat.getDrawable(ctx, R.drawable.ic_call_end)!!.mutate()
    private val icHandle = ContextCompat.getDrawable(ctx, R.drawable.ic_call)!!.mutate()

    private var offset = 0f          // смещение ручки от центра
    private var dragging = false
    private var downX = 0f
    private var fired = false
    private var pulse = 0f
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    private val radius get() = height / 2f - 6 * dp
    private val maxOffset get() = width / 2f - height / 2f

    override fun onAttachedToWindow() { super.onAttachedToWindow(); pulseAnim.start() }
    override fun onDetachedFromWindow() { pulseAnim.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (84 * dp).toInt())
    }

    fun reset() { if (!fired) return; fired = false; animateBack() }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat(); val cy = h / 2
        val frac = if (maxOffset > 0) (offset / maxOffset).coerceIn(-1f, 1f) else 0f
        // дорожка, подкрашивается в сторону движения
        track.color = when {
            frac > 0 -> blend(0x33FFFFFF, (green and 0x00FFFFFF) or 0x99000000.toInt(), frac)
            frac < 0 -> blend(0x33FFFFFF, (red and 0x00FFFFFF) or 0x99000000.toInt(), -frac)
            else -> 0x33FFFFFF
        }
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, track)
        // кнопки по краям
        val er = radius * 0.8f
        endPaint.color = red; c.drawCircle(h / 2, cy, er, endPaint)
        endPaint.color = green; c.drawCircle(w - h / 2, cy, er, endPaint)
        drawIcon(c, icEnd, h / 2, cy, er * 0.55f, 0xFFFFFFFF.toInt())
        drawIcon(c, icCall, w - h / 2, cy, er * 0.55f, 0xFFFFFFFF.toInt())
        if (!dragging && offset == 0f) {
            c.drawText("‹ отклонить", w / 2 - radius * 2.6f, cy + 5 * dp, hintPaint)
            c.drawText("ответить ›", w / 2 + radius * 2.6f, cy + 5 * dp, hintPaint)
        }
        // ручка с пульсацией
        val hx = w / 2 + offset
        if (!dragging) {
            pulsePaint.alpha = ((1 - pulse) * 90).toInt()
            c.drawCircle(hx, cy, radius * (1 + pulse * 0.35f), pulsePaint)
        }
        handlePaint.color = when {
            frac > 0.05f -> green
            frac < -0.05f -> red
            else -> 0xFFFFFFFF.toInt()
        }
        c.drawCircle(hx, cy, radius, handlePaint)
        val iconColor = if (abs(frac) > 0.05f) 0xFFFFFFFF.toInt() else green
        drawIcon(c, if (frac < -0.05f) icEnd else icHandle, hx, cy, radius * 0.5f, iconColor)
    }

    private fun drawIcon(c: Canvas, d: android.graphics.drawable.Drawable, x: Float, y: Float, half: Float, color: Int) {
        d.setTint(color)
        d.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
        d.draw(c)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (fired) return true
        val cx = width / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                dragging = abs(e.x - cx) <= radius * 1.3f
                if (dragging) { parent?.requestDisallowInterceptTouchEvent(true); invalidate() }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                offset = (e.x - downX).coerceIn(-maxOffset, maxOffset)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val frac = offset / maxOffset
                    when {
                        frac > 0.7f -> fire(true)
                        frac < -0.7f -> fire(false)
                        else -> animateBack()
                    }
                } else if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - downX) < 10 * dp) {
                    // нажатие на значок у края
                    if (e.x < height * 1.2f) fire(false) else if (e.x > width - height * 1.2f) fire(true)
                }
            }
        }
        return true
    }

    private fun fire(answer: Boolean) {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        offset = if (answer) maxOffset else -maxOffset
        invalidate()
        if (answer) onAnswer?.invoke() else onReject?.invoke()
    }

    private fun animateBack() {
        ValueAnimator.ofFloat(offset, 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { offset = it.animatedValue as Float; invalidate() }
        }.start()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt() shl s
        return mix(24) or mix(16) or mix(8) or mix(0)
    }
}
