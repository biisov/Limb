package app.limb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** The warm gold used for the accent of the safe-cracking mode (pointer and dots). */
internal const val GOLD_RGB = 0xE0A93B

/** Three small dots: a dialed number is a gold dot, a number still to find an outline. No digits. */
class ProgressDots @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dot = DOT_DP * density
    private val gap = GAP_DP * density

    /** How many numbers are dialed, 0..3. */
    var filled = 0
        set(value) {
            field = value.coerceIn(0, COUNT)
            invalidate()
        }

    /** 0..1: the flash when the safe opens. */
    var flash = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_DP * density
        color = RING_COLOR
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val extra = (dot * FLASH_GROWTH).roundToInt() // room for the dots to swell during the flash
        setMeasuredDimension(
            (COUNT * dot + (COUNT - 1) * gap).roundToInt() + 2 * extra,
            dot.roundToInt() + 2 * extra,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val extra = dot * FLASH_GROWTH
        val cy = height / 2f
        for (i in 0 until COUNT) {
            val cx = extra + dot / 2f + i * (dot + gap)
            if (i < filled) {
                val r = dot / 2f * (1f + FLASH_GROWTH * flash)
                fillPaint.color = blend(opaque(GOLD_RGB), FLASH_COLOR, flash)
                canvas.drawCircle(cx, cy, r, fillPaint)
            } else {
                canvas.drawCircle(cx, cy, dot / 2f - ringPaint.strokeWidth / 2f, ringPaint)
            }
        }
    }

    private fun opaque(rgb: Int) = rgb or (0xFF shl 24)

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) = ((from shr shift and 0xFF) + ((to shr shift and 0xFF) - (from shr shift and 0xFF)) * t).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        const val COUNT = 3
        const val DOT_DP = 5f
        private const val GAP_DP = 9f
        private const val RING_DP = 1.2f
        private const val RING_COLOR = 0xFF4A4A4A.toInt()
        private const val FLASH_COLOR = 0xFFFFD98A.toInt()
        private const val FLASH_GROWTH = 0.35f
    }
}
