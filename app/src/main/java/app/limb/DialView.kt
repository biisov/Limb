package app.limb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A safe-style rotary dial with a numbered scale.
 *
 * Every time the dial passes a division [DetentListener.onDetent] fires with the direction of
 * travel. It is turned by touch (with fling inertia) or one division at a time with [step].
 *
 * Drawing is split in two layers, so the lighting stays put while the dial turns under it:
 *  - ROTATES: the diamond knurling on the outer ring (pre-rendered once into a bitmap), the scale
 *    ticks and the numbers;
 *  - FIXED: everything else - shadows, the top highlight / bottom shade on the knurled ring, the
 *    shading at the edge of the scale, the pointer and the glossy centre cap.
 *
 * All drawing is done in "design units" (the geometry of the original 1080x1920 design, centred on
 * the dial) and scaled to the view.
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface DetentListener {
        /** [clockwise] is the direction of travel, [divisionsPerSecond] how fast the dial is moving. */
        fun onDetent(clockwise: Boolean, divisionsPerSecond: Float)
    }

    var detentListener: DetentListener? = null

    // --- physics state -------------------------------------------------------------------------
    private var angle = 0f // degrees, clockwise positive, kept in [0, 360)
    private var velocity = 0f // degrees per second
    private var dragging = false
    private var lastTouchAngle = 0f
    private var lastMoveTime = 0L
    private var running = false
    private var lastFrameNanos = 0L
    private var lastClickNanos = 0L

    // --- view geometry -------------------------------------------------------------------------
    private var cx = 0f
    private var cy = 0f
    private var scale = 0f // pixels per design unit
    private val radius get() = R_KN * scale // outer radius of the dial in pixels

    private class Layer(val bitmap: Bitmap, val half: Float) // half = half-extent in design units

    private var knurl: Layer? = null
    private var bigShadow: Layer? = null
    private var capShadow: Layer? = null

    private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

    // --- paints (design units, built once) -----------------------------------------------------
    private val bgPaint = Paint()
    private val bgShader = RadialGradient(0f, 0f, 1f, opaque(0x151515), opaque(0x070707), Shader.TileMode.CLAMP)
    private val bgMatrix = Matrix()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val pointerPath = Path().apply {
        val tipY = -R_KN - POINTER_GAP
        moveTo(-20f, tipY - POINTER_HEIGHT)
        lineTo(20f, tipY - POINTER_HEIGHT)
        lineTo(0f, tipY)
        close()
    }
    private val pointerPaint = fill(color = opaque(0x8A8A8A))

    private val knBasePaint = fill(diagonal(R_KN, KN_BASE_COLORS, KN_BASE_POS))

    // lighting over the knurled ring: bright top-left, dark bottom-right (does not rotate)
    private val knShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = R_KN - R_KI
        shader = diagonal(
            R_KN,
            intArrayOf(rgba(0xFFFFFF, 0.34f), rgba(0xFFFFFF, 0f), rgba(0x000000, 0f), rgba(0x000000, 0.55f)),
            floatArrayOf(0f, 0.5f, 0.5001f, 1f),
        )
    }

    private val knOuterEdge = stroke(3f, color = rgba(0x9A9B9F, 0.7f))
    private val knInnerEdge = stroke(4f, color = opaque(0x111111))
    private val knRimTop = stroke(
        5f,
        shader = vertical(
            R_KN - 2f,
            intArrayOf(rgba(0xD0D1D4, 0.9f), rgba(0x777777, 0.25f), rgba(0x000000, 0.8f)),
            floatArrayOf(0f, 0.5f, 1f),
        ),
    )
    private val knRimIn = stroke(
        7f,
        shader = vertical(
            R_KI + 3f,
            intArrayOf(rgba(0x000000, 0.9f), rgba(0x000000, 0.2f), rgba(0xAAAAAA, 0.55f)),
            floatArrayOf(0f, 0.6f, 1f),
        ),
    )

    private val facePaint = fill(
        LinearGradient(
            -R_SC, -R_SC, R_SC, R_SC,
            intArrayOf(opaque(0x2F2F31), opaque(0x1B1B1D), opaque(0x262628)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        ),
    )
    private val faceEdge = stroke(2f, color = rgba(0x5A5A5E, 0.6f))
    private val recessPaint = fill(
        RadialGradient(
            0f, -R_SC + 0.38f * 2 * R_SC, 0.55f * 2 * R_SC,
            intArrayOf(rgba(0x000000, 0f), rgba(0x000000, 0f), rgba(0x000000, 0.75f)),
            floatArrayOf(0f, 0.82f, 1f), Shader.TileMode.CLAMP,
        ),
    )

    private val capBodyPaint = fill(color = opaque(0x0D0D0E))
    private val capBezel = stroke(5f, shader = diagonal(R_CAP + 3f, KN_BASE_COLORS, KN_BASE_POS))
    private val capPaint = fill(
        RadialGradient(
            -R_CAP + 0.38f * 2 * R_CAP, -R_CAP + 0.28f * 2 * R_CAP, 0.85f * 2 * R_CAP,
            intArrayOf(opaque(0x55575C), opaque(0x26272A), opaque(0x0C0C0D), opaque(0x050505)),
            floatArrayOf(0f, 0.35f, 0.8f, 1f), Shader.TileMode.CLAMP,
        ),
    )
    private val glossPaint = fill(
        RadialGradient(0f, 0f, 1f, rgba(0xFFFFFF, 0.22f), rgba(0xFFFFFF, 0f), Shader.TileMode.CLAMP),
    )
    private val glossClip = Path().apply {
        addCircle(0f, 0f, R_CAP, Path.Direction.CW)
    }
    private val glossEllipse = Path().apply {
        addOval(RectF(-30f - 150f, -95f - 90f, -30f + 150f, -95f + 90f), Path.Direction.CW)
    }
    private val capRim = stroke(
        3f,
        shader = vertical(
            R_CAP - 1f,
            intArrayOf(rgba(0xD0D1D4, 0.45f), rgba(0x777777, 0.125f), rgba(0x000000, 0.4f)),
            floatArrayOf(0f, 0.5f, 1f),
        ),
    )

    // rotating scale: 60 divisions, a longer one (with a number) every 5th
    private val longTicks = FloatArray(12 * 4)
    private val shortTicks = FloatArray(48 * 4)
    private val longTickPaint = tickPaint(4f, opaque(0xD6D6D6))
    private val shortTickPaint = tickPaint(2.5f, opaque(0x8C8C8C))
    private val numbers = Array(12) { (it * 5).toString() }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = opaque(0xCFCFCF)
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SANS_SERIF
    }
    private val numberBaseline: Float

    init {
        var l = 0
        var s = 0
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0)
            val sn = sin(a).toFloat()
            val cs = -cos(a).toFloat()
            val isLong = i % 5 == 0
            val r1 = R_SC - 10f
            val r2 = r1 - if (isLong) 26f else 13f
            val dst = if (isLong) longTicks else shortTicks
            val o = if (isLong) l else s
            dst[o] = sn * r1
            dst[o + 1] = cs * r1
            dst[o + 2] = sn * r2
            dst[o + 3] = cs * r2
            if (isLong) l += 4 else s += 4
        }
        val fm = numberPaint.fontMetrics
        numberBaseline = -(fm.ascent + fm.descent) / 2f // vertically centre the digits on their radius
    }

    // --- public API ----------------------------------------------------------------------------

    /**
     * Turns the dial exactly one division (clockwise if [direction] > 0), with exactly one detent
     * event. Any coasting is stopped, so a tap is always one clean click.
     */
    fun step(direction: Int) {
        val clockwise = direction > 0
        velocity = 0f
        angle = (angle + (if (clockwise) STEP else -STEP) + 360f) % 360f
        emitDetent(clockwise, 0f, force = true) // a single step is slow by definition
        invalidate()
    }

    // --- animation -----------------------------------------------------------------------------

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(now: Long) {
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = now

        if (!dragging) {
            velocity *= exp(-FRICTION * dt)
            if (abs(velocity) < MIN_VELOCITY) velocity = 0f
            if (velocity != 0f) moveBy(velocity * dt, abs(velocity))
        }

        invalidate()
        if (dragging || velocity != 0f) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            running = false
        }
    }

    /** Rotates the dial by [delta] degrees and fires one detent event per division passed. */
    private fun moveBy(delta: Float, speed: Float) {
        if (delta == 0f) return
        val oldIndex = floor(angle / STEP).toInt()
        angle += delta
        val crossings = floor(angle / STEP).toInt() - oldIndex
        while (angle >= 360f) angle -= 360f
        while (angle < 0f) angle += 360f
        if (crossings != 0) emitDetent(crossings > 0, speed, force = false)
    }

    private fun emitDetent(clockwise: Boolean, speed: Float, force: Boolean) {
        val now = System.nanoTime()
        // Very fast flings pass divisions faster than clicks can be told apart: skip, don't machine-gun.
        if (!force && now - lastClickNanos < MIN_CLICK_GAP_NANOS) return
        lastClickNanos = now
        detentListener?.onDetent(clockwise, speed / STEP) // speed is in degrees per second
    }

    // --- touch ---------------------------------------------------------------------------------

    private fun touchAngle(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.x - cx).toDouble(), (cy - e.y).toDouble())).toFloat()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dist = hypot(e.x - cx, e.y - cy)
                if (dist > radius * 1.1f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                velocity = 0f
                lastTouchAngle = touchAngle(e)
                lastMoveTime = e.eventTime
                ensureRunning()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                if (hypot(e.x - cx, e.y - cy) < radius * 0.08f) return true // angle is noise near the centre
                val a = touchAngle(e)
                var d = a - lastTouchAngle
                if (d > 180f) d -= 360f
                if (d <= -180f) d += 360f
                lastTouchAngle = a
                val dtSec = (e.eventTime - lastMoveTime) / 1000f
                lastMoveTime = e.eventTime
                if (dtSec > 0f) {
                    val instant = d / dtSec
                    velocity = velocity * 0.6f + instant * 0.4f
                }
                moveBy(d, abs(velocity))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                if (e.eventTime - lastMoveTime > 80) velocity = 0f // finger rested before lifting
                velocity = velocity.coerceIn(-MAX_FLING_SPEED, MAX_FLING_SPEED)
                ensureRunning()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        running = false
        super.onDetachedFromWindow()
    }

    // --- pre-rendered layers -------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        scale = scaleFor(w, h)

        bgMatrix.setScale(w * 0.75f, h * 0.75f)
        bgMatrix.postTranslate(w * 0.5f, h * 0.45f)
        bgShader.setLocalMatrix(bgMatrix)
        bgPaint.shader = bgShader

        if (w <= 0 || h <= 0) return
        knurl = renderKnurl()
        bigShadow = renderShadow(R_KN, dy = 40f, sigma = 34f, opacity = 0.95f)
        capShadow = renderShadow(R_CAP + 6f, dy = 14f, sigma = 12f, opacity = 0.9f)
    }

    /**
     * The diamond knurling, drawn once. Face brightness is baked in the dial's own frame (as if the
     * facets were at 12 o'clock); the position-dependent lighting is the fixed overlay [knShadePaint].
     */
    private fun renderKnurl(): Layer {
        val size = ceil(2 * R_KN * scale).toInt()
        val half = size / (2f * scale)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.translate(half, half)

        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addCircle(0f, 0f, R_KN, Path.Direction.CW)
            addCircle(0f, 0f, R_KI, Path.Direction.CW)
        }
        c.clipPath(ring)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 0.6f
            color = opaque(0x3A3B3E)
        }
        c.drawRect(-half, -half, half, half, p)

        // brightness of the four faces of a diamond, lit from the top-left, in the dial's own frame
        val faceDirs = arrayOf(floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(-1f, -1f))
        val faceColor = IntArray(4) { k ->
            val dx = faceDirs[k][0]
            val dy = faceDirs[k][1]
            val d = hypot(dx, dy)
            val v = dx / d * LIGHT_X + dy / d * LIGHT_Y
            val g = (if (v > 0f) 58f + 70f * v else 58f + 40f * v).roundToInt().coerceIn(14, 200)
            opaque((g shl 16) or (g shl 8) or min(255, g + 3))
        }

        fun pt(ai: Float, ri: Float, out: FloatArray) {
            val a = 2.0 * Math.PI * ai / KNURL_COLUMNS
            val r = R_KI + (R_KN - R_KI) * ri / KNURL_ROWS
            out[0] = (r * sin(a)).toFloat()
            out[1] = (-r * cos(a)).toFloat()
        }

        val top = FloatArray(2)
        val bot = FloatArray(2)
        val lft = FloatArray(2)
        val rgt = FloatArray(2)
        val apex = FloatArray(2)
        val tri = Path()
        for (ri2 in 0..2 * KNURL_ROWS) {
            for (ai2 in -1..2 * KNURL_COLUMNS) {
                if ((ri2 + ai2) and 1 != 0) continue
                val ai = ai2 / 2f
                val ri = ri2 / 2f
                pt(ai, ri + 0.5f, top)
                pt(ai, ri - 0.5f, bot)
                pt(ai - 0.5f, ri, lft)
                pt(ai + 0.5f, ri, rgt)
                pt(ai, ri, apex)
                val pairs = arrayOf(top to rgt, rgt to bot, bot to lft, lft to top)
                for (k in 0 until 4) {
                    val (q1, q2) = pairs[k]
                    tri.rewind()
                    tri.moveTo(q1[0], q1[1])
                    tri.lineTo(q2[0], q2[1])
                    tri.lineTo(apex[0], apex[1])
                    tri.close()
                    p.color = faceColor[k]
                    c.drawPath(tri, p)
                }
            }
        }
        return Layer(bmp, half)
    }

    /** A blurred, offset black disc: the equivalent of the design's drop-shadow filters. */
    private fun renderShadow(r: Float, dy: Float, sigma: Float, opacity: Float): Layer {
        val half = r + sigma * 3f + abs(dy)
        val size = ceil(2 * half * scale).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = rgba(0x000000, opacity)
            // Skia: sigma = 0.57735 * radius + 0.5
            maskFilter = BlurMaskFilter(max(1f, (sigma * scale - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
        }
        val mid = size / 2f
        Canvas(bmp).drawCircle(mid, mid + dy * scale, r * scale, p)
        return Layer(bmp, size / (2f * scale))
    }

    // --- drawing -------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val knurl = knurl ?: return
        val bigShadow = bigShadow ?: return
        val capShadow = capShadow ?: return

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)

        // ---- fixed: pointer and the shadow under the dial
        canvas.drawPath(pointerPath, pointerPaint)
        drawLayer(canvas, bigShadow)

        // ---- fixed base of the knurled ring, then ROTATING knurling, then fixed lighting on top
        canvas.drawCircle(0f, 0f, R_KN, knBasePaint)
        canvas.save()
        canvas.rotate(angle)
        drawLayer(canvas, knurl)
        canvas.restore()
        canvas.drawCircle(0f, 0f, (R_KN + R_KI) / 2f, knShadePaint)

        // ---- fixed: edges of the knurled ring (top highlight / bottom shade)
        canvas.drawCircle(0f, 0f, R_KN, knOuterEdge)
        canvas.drawCircle(0f, 0f, R_KI, knInnerEdge)
        canvas.drawCircle(0f, 0f, R_KN - 2f, knRimTop)
        canvas.drawCircle(0f, 0f, R_KI + 3f, knRimIn)

        // ---- fixed scale face, ROTATING ticks and numbers, fixed shading at the edge of the face
        canvas.drawCircle(0f, 0f, R_SC, facePaint)
        canvas.drawCircle(0f, 0f, R_SC - 1f, faceEdge)
        canvas.save()
        canvas.rotate(angle)
        canvas.drawLines(shortTicks, shortTickPaint)
        canvas.drawLines(longTicks, longTickPaint)
        for (i in numbers.indices) {
            canvas.save()
            canvas.rotate(i * 30f)
            canvas.drawText(numbers[i], 0f, -(R_SC - 60f) + numberBaseline, numberPaint)
            canvas.restore()
        }
        canvas.restore()
        canvas.drawCircle(0f, 0f, R_SC, recessPaint)

        // ---- fixed: glossy centre cap
        drawLayer(canvas, capShadow)
        canvas.drawCircle(0f, 0f, R_CAP + 6f, capBodyPaint)
        canvas.drawCircle(0f, 0f, R_CAP + 3f, capBezel)
        canvas.drawCircle(0f, 0f, R_CAP, capPaint)
        canvas.save()
        canvas.clipPath(glossClip)
        canvas.clipPath(glossEllipse)
        canvas.translate(-30f - 150f + 0.38f * 300f, -95f - 90f + 0.22f * 180f)
        canvas.scale(0.55f * 300f, 0.55f * 180f) // the gloss gradient is elliptical
        canvas.drawRect(-4f, -4f, 4f, 4f, glossPaint)
        canvas.restore()
        canvas.drawCircle(0f, 0f, R_CAP - 1f, capRim)

        canvas.restore()
    }

    private val layerRect = RectF()

    private fun drawLayer(canvas: Canvas, layer: Layer) {
        layerRect.set(-layer.half, -layer.half, layer.half, layer.half)
        canvas.drawBitmap(layer.bitmap, null, layerRect, bitmapPaint)
    }

    // --- paint helpers -------------------------------------------------------------------------

    private fun fill(shader: Shader? = null, color: Int = 0) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        if (shader != null) this.shader = shader else this.color = color
    }

    private fun stroke(width: Float, shader: Shader? = null, color: Int = 0) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        if (shader != null) this.shader = shader else this.color = color
    }

    private fun tickPaint(width: Float, color: Int) = stroke(width, color = color).apply { strokeCap = Paint.Cap.ROUND }

    /** Top-left to bottom-right gradient over the bounding box of a circle of radius [r]. */
    private fun diagonal(r: Float, colors: IntArray, pos: FloatArray) =
        LinearGradient(-0.7f * r, -r, 0.7f * r, r, colors, pos, Shader.TileMode.CLAMP)

    /** Top-to-bottom gradient over the bounding box of a circle of radius [r]. */
    private fun vertical(r: Float, colors: IntArray, pos: FloatArray) =
        LinearGradient(0f, -r, 0f, r, colors, pos, Shader.TileMode.CLAMP)

    companion object {
        /** Divisions around the dial: one per tick mark, one click each. */
        const val DETENTS = 60
        const val STEP = 360f / DETENTS

        // geometry of the design, in design units
        private const val R_KN = 392f // knurled ring, outer
        private const val R_KI = 333f // knurled ring, inner
        private const val R_SC = 325f // scale face
        private const val R_CAP = 208f // glossy centre cap
        private const val POINTER_GAP = 24f // pointer tip to the knurled ring
        private const val POINTER_HEIGHT = 30f
        private const val KNURL_COLUMNS = 84
        private const val KNURL_ROWS = 3
        private const val LIGHT_X = -0.55f
        private const val LIGHT_Y = -0.83f

        private val KN_BASE_COLORS = intArrayOf(opaque(0x7C7D80), opaque(0x3A3B3E), opaque(0x232325), opaque(0x55565A))
        private val KN_BASE_POS = floatArrayOf(0f, 0.45f, 0.7f, 1f)

        private const val FRICTION = 7f
        private const val MIN_VELOCITY = 6f
        private const val MAX_FLING_SPEED = 1500f
        private const val MIN_CLICK_GAP_NANOS = 12_000_000L

        private fun scaleFor(width: Int, height: Int): Float = min(width, height) * 0.38f / R_KN

        /** Y (in pixels) of the top of the fixed pointer for a view of this size. The dial is centred. */
        fun pointerTop(width: Int, height: Int): Float =
            height / 2f - (R_KN + POINTER_GAP + POINTER_HEIGHT) * scaleFor(width, height)

        private fun opaque(rgb: Int): Int = rgb or (0xFF shl 24)
        private fun rgba(rgb: Int, a: Float): Int = ((a * 255f + 0.5f).toInt() shl 24) or (rgb and 0xFFFFFF)
    }
}
