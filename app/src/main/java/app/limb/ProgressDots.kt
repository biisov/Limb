package app.limb

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt

/** The warm gold of the safe-cracking mode: dialed numbers (the dots) and the difficulty selection. */
internal const val GOLD_RGB = 0xE0A93B

/** The deep emerald of an opened safe. */
internal const val EMERALD_RGB = 0x3FBF7F

/**
 * Three small dots: a dialed number is a gold dot, a number still to find an outline. No digits.
 * When the safe opens the dots light up emerald with a soft glow, one after the other.
 */
class ProgressDots @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dot = DOT_DP * density
    private val gap = GAP_DP * density

    private val lit = FloatArray(COUNT) // gold fill of each dot, 0..1
    private val green = FloatArray(COUNT) // emerald fill (and glow) of each dot, 0..1
    private var fade = 1f // everything fades with this while the dots go out
    private val animators = ArrayList<Animator>()

    /** How many numbers are dialed, 0..3 (gold dots, set at once). */
    var filled = 0
        set(value) {
            cancelAnimations()
            field = value.coerceIn(0, COUNT)
            for (i in 0 until COUNT) {
                lit[i] = if (i < field) 1f else 0f
                green[i] = 0f
            }
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(withAlpha(EMERALD_RGB, 0.65f), withAlpha(EMERALD_RGB, 0.22f), withAlpha(EMERALD_RGB, 0f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_DP * density
        color = RING_COLOR
    }

    /** The safe opened: all three dots light up emerald, left to right, [chainDelayMs] apart. */
    fun celebrate(chainDelayMs: Long) {
        cancelAnimations()
        for (i in 0 until COUNT) {
            lit[i] = 1f
            green[i] = 0f
            ramp(0f, 1f, GREEN_RISE_MS, i * chainDelayMs) {
                green[i] = it
                invalidate()
            }
        }
        invalidate()
    }

    /** The lit dots fade away over [durationMs] and the dots are empty again; then [onEnd]. */
    fun fadeOut(durationMs: Long, onEnd: () -> Unit = {}) {
        for (a in animators) a.cancel()
        animators.clear()
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = durationMs
            addUpdateListener {
                fade = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    animators.remove(animation)
                    filled = 0
                    onEnd()
                }
            })
            animators += this
            start()
        }
    }

    /** Stops whatever is animating and shows the plain dots again. */
    fun cancelAnimations() {
        for (a in animators.toList()) a.cancel()
        animators.clear()
        fade = 1f
    }

    private fun ramp(from: Float, to: Float, durationMs: Long, delayMs: Long, onValue: (Float) -> Unit) {
        ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { onValue(it.animatedValue as Float) }
            animators += this
            start()
        }
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val room = (dot * ROOM).roundToInt() // for the glow
        setMeasuredDimension(
            (COUNT * dot + (COUNT - 1) * gap).roundToInt() + 2 * room,
            dot.roundToInt() + 2 * room,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val room = dot * ROOM
        val cy = height / 2f
        for (i in 0 until COUNT) {
            val cx = room + dot / 2f + i * (dot + gap)
            canvas.drawCircle(cx, cy, dot / 2f - ringPaint.strokeWidth / 2f, ringPaint)
            val g = green[i] * fade
            if (g > 0f) {
                val r = dot * GLOW_RADIUS
                glowPaint.alpha = (g * 255f).roundToInt()
                canvas.save()
                canvas.translate(cx, cy)
                canvas.scale(r, r)
                canvas.drawCircle(0f, 0f, 1f, glowPaint)
                canvas.restore()
            }
            val l = lit[i] * fade
            if (l > 0f) {
                fillPaint.color = withAlpha(GOLD_RGB, l)
                canvas.drawCircle(cx, cy, dot / 2f, fillPaint)
            }
            if (g > 0f) {
                fillPaint.color = withAlpha(EMERALD_RGB, g)
                canvas.drawCircle(cx, cy, dot / 2f, fillPaint)
            }
        }
    }

    companion object {
        const val COUNT = 3
        const val DOT_DP = 5f

        /** Room around the dots (in dot sizes) so the glow is not cut off. */
        const val ROOM = 1.0f
        private const val GLOW_RADIUS = 1.6f
        private const val GAP_DP = 9f
        private const val RING_DP = 1.2f
        private const val RING_COLOR = 0xFF4A4A4A.toInt()
        private const val GREEN_RISE_MS = 160L

        private fun withAlpha(rgb: Int, alpha: Float): Int =
            ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (rgb and 0xFFFFFF)
    }
}
