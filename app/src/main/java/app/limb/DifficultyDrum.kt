package app.limb

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The difficulty selector: a horizontal ribbed drum set into a recessed slot. Only its front shows.
 * EASY · NORMAL · HARD are engraved on it in English: the chosen one in the middle in gold, the others
 * grey, squeezed and fading towards the edges as on a cylinder. Swipe to turn it (it settles with a
 * springy overshoot) or tap a neighbouring word.
 *
 * The view is a little taller than the drum (room above for the small gold marker).
 */
class DifficultyDrum @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** The selection changes while turning: for the ratchet click and the light vibration. */
    var onTick: (() -> Unit)? = null

    /** The drum was let go on a level (also when it is the same one). */
    var onSelected: ((Difficulty) -> Unit)? = null

    private val levels = Difficulty.values()
    private val labels = arrayOf("EASY", "NORMAL", "HARD") // engraved: always English
    private var pos = 1f // 0 = EASY in the middle, 1 = NORMAL, 2 = HARD
    private var tickedIndex = 1
    private var chosen = 1
    private var animator: ValueAnimator? = null

    private var downX = 0f
    private var startPos = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // geometry (pixels), set in onSizeChanged
    private var bodyW = 0f
    private var bodyH = 0f
    private var bodyTop = 0f
    private var winX = 0f
    private var winY = 0f
    private var winW = 0f
    private var winH = 0f
    private var radius = 0f // of the drum: half the window

    private val housing = RectF()
    private val windowPath = Path()
    private val rect = RectF()
    private val marker = Path()

    private val housingFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF08080A.toInt() }
    private val housingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val flat = Paint()
    private val ridge = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var strokeShader: Shader? = null
    private var middleShader: Shader? = null
    private var metalShader: Shader? = null
    private var bandShadeShader: Shader? = null
    private var vignetteShader: Shader? = null
    private var recessShader: Shader? = null

    // --- public API ----------------------------------------------------------------------------

    fun setLevel(level: Difficulty, animate: Boolean) {
        chosen = level.ordinal
        if (animate) settle(chosen, notify = false) else {
            animator?.cancel()
            pos = chosen.toFloat()
            tickedIndex = chosen
            invalidate()
        }
        contentDescription = context.getString(R.string.drum_description, labels[chosen])
    }

    // --- touch ---------------------------------------------------------------------------------

    private val pxPerLevel get() = max(1f, radius * LABEL_SPACING_RAD)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                animator?.cancel()
                downX = e.x
                startPos = pos
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (!dragging && abs(dx) > touchSlop) dragging = true
                if (dragging) setPos((startPos - dx / pxPerLevel).coerceIn(-OVERDRAG, 2f + OVERDRAG))
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    settle(pos.roundToInt().coerceIn(0, 2), notify = true)
                } else {
                    performClick()
                    settle(labelAt(e.x), notify = true)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                settle(pos.roundToInt().coerceIn(0, 2), notify = true)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    /** The word nearest to a tap. */
    private fun labelAt(x: Float): Int {
        val cx = winX + winW / 2f
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (j in 0..2) {
            val d = abs(x - (cx + radius * sin((j - pos) * LABEL_SPACING_RAD)))
            if (d < bestDistance) {
                bestDistance = d
                best = j
            }
        }
        return best
    }

    private fun setPos(value: Float) {
        pos = value
        val index = value.roundToInt().coerceIn(0, 2)
        if (index != tickedIndex) {
            tickedIndex = index
            onTick?.invoke()
        }
        invalidate()
    }

    /** Settles onto [target] with a springy overshoot. */
    private fun settle(target: Int, notify: Boolean) {
        if (notify) {
            chosen = target
            contentDescription = context.getString(R.string.drum_description, labels[target])
            onSelected?.invoke(levels[target])
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(pos, target.toFloat()).apply {
            duration = SETTLE_MS
            interpolator = OvershootInterpolator(1.8f)
            addUpdateListener { setPos(it.animatedValue as Float) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // --- geometry ------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bodyW = w.toFloat()
        bodyH = h / (1f + MARKER_ROOM)
        bodyTop = h - bodyH
        val inset = INSET * bodyH
        winX = inset
        winY = inset
        winW = bodyW - 2 * inset
        winH = bodyH - 2 * inset
        radius = winW / 2f

        val strokeWidth = max(2f, STROKE * bodyH)
        housing.set(strokeWidth / 2f, strokeWidth / 2f, bodyW - strokeWidth / 2f, bodyH - strokeWidth / 2f)
        housingStroke.strokeWidth = strokeWidth
        strokeShader = LinearGradient(
            0f, 0f, 0f, bodyH,
            intArrayOf(0xFF2A2A2C.toInt(), 0xFF4A4A4D.toInt(), 0xFF707074.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        housingStroke.shader = strokeShader

        windowPath.reset()
        windowPath.addRoundRect(RectF(winX, winY, winX + winW, winY + winH), 0.02f * bodyH, 0.02f * bodyH, Path.Direction.CW)

        // shaders in window coordinates (the window is translated to the origin when drawing)
        middleShader = LinearGradient(
            0f, 0f, winW, 0f,
            intArrayOf(0xFF020203.toInt(), 0xFF0A0A0C.toInt(), 0xFF212125.toInt(), 0xFF0A0A0C.toInt(), 0xFF020203.toInt()),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP,
        )
        metalShader = LinearGradient(
            0f, 0f, winW, 0f,
            intArrayOf(0xFF060607.toInt(), 0xFF1A1A1D.toInt(), 0xFF5C5C60.toInt(), 0xFF1A1A1D.toInt(), 0xFF060607.toInt()),
            floatArrayOf(0f, 0.15f, 0.5f, 0.85f, 1f), Shader.TileMode.CLAMP,
        )
        val band = BAND * winH
        bandShadeShader = LinearGradient(
            0f, 0f, 0f, band,
            intArrayOf(0x73000000, 0x00000000, 0x73000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        vignetteShader = LinearGradient(
            0f, 0f, winW, 0f,
            intArrayOf(0xEB000000.toInt(), 0x59000000, 0x00000000, 0x00000000, 0x59000000, 0xEB000000.toInt()),
            floatArrayOf(0f, 0.16f, 0.34f, 0.66f, 0.84f, 1f), Shader.TileMode.CLAMP,
        )
        recessShader = LinearGradient(
            0f, 0f, 0f, winH,
            intArrayOf(0x8C000000.toInt(), 0x00000000, 0x00000000, 0x8C000000.toInt()),
            floatArrayOf(0f, 0.07f, 0.93f, 1f), Shader.TileMode.CLAMP,
        )

        // the small gold marker above the middle, its tip a little inside the housing's top edge
        val mw = 0.23f * bodyH
        val mh = 0.14f * bodyH
        val tipY = bodyTop + 0.045f * bodyH
        marker.reset()
        marker.moveTo(bodyW / 2f - mw / 2f, tipY - mh - bodyTop)
        marker.lineTo(bodyW / 2f + mw / 2f, tipY - mh - bodyTop)
        marker.lineTo(bodyW / 2f, tipY - bodyTop)
        marker.close()
        markerPaint.shader = LinearGradient(
            0f, tipY - mh - bodyTop, 0f, tipY - bodyTop,
            0xFFF3DA9A.toInt(), 0xFFC99A3C.toInt(), Shader.TileMode.CLAMP,
        )
    }

    // --- drawing -------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (winW <= 0f) return
        canvas.save()
        canvas.translate(0f, bodyTop)

        // housing
        val r = 0.12f * bodyH
        canvas.drawRoundRect(0f, 0f, bodyW, bodyH, r, r, housingFill)
        canvas.drawRoundRect(housing, r, r, housingStroke)

        // the window onto the drum
        canvas.save()
        canvas.clipPath(windowPath)
        canvas.translate(winX, winY)
        flat.color = 0xFF050506.toInt()
        flat.shader = null
        canvas.drawRect(0f, 0f, winW, winH, flat)

        val band = BAND * winH
        // the middle band: dark, a little lit in the centre
        flat.shader = middleShader
        canvas.drawRect(0f, band, winW, winH - band, flat)
        flat.shader = null

        drawRibbed(canvas, 0f, band)
        drawRibbed(canvas, winH - band, band)
        drawLabels(canvas)

        // the edges fall away into shadow, and so do the top and bottom of the slot
        flat.shader = vignetteShader
        canvas.drawRect(0f, 0f, winW, winH, flat)
        flat.shader = recessShader
        canvas.drawRect(0f, 0f, winW, winH, flat)
        flat.shader = null
        canvas.restore()

        canvas.drawPath(marker, markerPaint)
        canvas.restore()
    }

    /** A band of vertical ridges; they roll with the drum, denser and darker towards the edges. */
    private fun drawRibbed(canvas: Canvas, y: Float, height: Float) {
        flat.shader = metalShader
        canvas.drawRect(0f, y, winW, y + height, flat)
        flat.shader = null

        val cx = winW / 2f
        val rotation = pos * LABEL_SPACING_DEG
        val offset = rotation % RIDGE_STEP_DEG
        val stepRad = Math.toRadians(RIDGE_STEP_DEG.toDouble()).toFloat()
        for (k in -RIDGES..RIDGES) {
            val angle = Math.toRadians((k * RIDGE_STEP_DEG - offset).toDouble()).toFloat()
            if (abs(angle) >= HALF_PI) continue
            val cs = cos(angle)
            val x = cx + radius * sin(angle)
            val w = max(1f, stepRad * radius * cs * RIDGE_FILL)
            val v = (40 + 190 * cs.pow(1.4f)).roundToInt().coerceIn(0, 255)
            ridge.color = (0xFF shl 24) or (v shl 16) or (v shl 8) or min(255, v + 2)
            canvas.drawRect(x - w / 2f, y, x + w / 2f, y + height, ridge)
            ridge.color = 0x8C000000.toInt() // a dark groove beside each ridge
            canvas.drawRect(x + w / 2f, y, x + w / 2f + max(1f, w * 0.35f), y + height, ridge)
        }
        flat.shader = bandShadeShader
        canvas.save()
        canvas.translate(0f, y)
        canvas.drawRect(0f, 0f, winW, height, flat)
        canvas.restore()
        flat.shader = null
    }

    private fun drawLabels(canvas: Canvas) {
        val cx = winW / 2f
        val midY = winH / 2f
        text.textSize = LABEL_TEXT * winH
        val baseline = midY - (text.ascent() + text.descent()) / 2f
        for (j in 0..2) {
            val phi = (j - pos) * LABEL_SPACING_RAD
            if (abs(phi) >= HALF_PI) continue
            val cs = cos(phi)
            val near = max(0f, 1f - abs(j - pos))
            canvas.save()
            canvas.translate(cx + radius * sin(phi), 0f)
            canvas.scale(cs, 1f) // squeezed as it turns away
            if (near > 0f) {
                text.color = (((near * 255f).roundToInt()) shl 24) or GOLD_TEXT
                canvas.drawText(labels[j], 0f, baseline, text)
            }
            val grey = (1f - near) * 0.55f * cs.pow(3)
            text.color = (((grey * 255f).roundToInt().coerceIn(0, 255)) shl 24) or GREY_TEXT
            canvas.drawText(labels[j], 0f, baseline, text)
            canvas.restore()
        }
    }

    private companion object {
        const val MARKER_ROOM = 0.10f // of the drum's height, above it
        const val INSET = 0.12f // of the height: housing wall
        const val STROKE = 0.02f
        const val BAND = 0.21f // ribbed bands, of the window's height
        const val LABEL_TEXT = 0.30f
        const val LABEL_SPACING_DEG = 55f // how far round the drum the words are
        val LABEL_SPACING_RAD = Math.toRadians(LABEL_SPACING_DEG.toDouble()).toFloat()
        val HALF_PI = (Math.PI / 2).toFloat()
        const val RIDGE_STEP_DEG = 4f
        const val RIDGES = 80
        const val RIDGE_FILL = 0.55f
        const val OVERDRAG = 0.3f
        const val SETTLE_MS = 380L
        const val GOLD_TEXT = 0xEBCB82
        const val GREY_TEXT = 0x96969B
    }
}
