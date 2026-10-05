package app.limb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A safe-style rotary dial with tick marks and no numbers.
 *
 * Every time the dial passes a tick mark [DetentListener.onDetent] fires, with the direction
 * of travel. It can be turned by touch (with fling inertia) or driven by [setSpin], which spins
 * it at a constant speed for as long as it is held (used for the volume buttons).
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface DetentListener {
        /** [clockwise] is the direction of travel, [intensity] is 0..1 (how fast the dial moves). */
        fun onDetent(clockwise: Boolean, intensity: Float)
    }

    var detentListener: DetentListener? = null

    // --- physics state -------------------------------------------------------------------------
    private var angle = 0f // degrees, clockwise positive, kept in [0, 360)
    private var velocity = 0f // degrees per second
    private var spinDir = 0 // -1, 0, +1: driven by the volume buttons
    private var dragging = false
    private var lastTouchAngle = 0f
    private var lastMoveTime = 0L
    private var running = false
    private var lastFrameNanos = 0L
    private var lastClickNanos = 0L
    private var lastHapticMs = 0L

    // --- geometry ------------------------------------------------------------------------------
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var longTicks = FloatArray(0)
    private var midTicks = FloatArray(0)
    private var shortTicks = FloatArray(0)
    private val indexPath = Path()

    // --- paints --------------------------------------------------------------------------------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val frameCallback = Choreographer.FrameCallback { now -> step(now) }

    // --- public API ----------------------------------------------------------------------------

    /** Spin continuously clockwise (+1), counter-clockwise (-1), or let go (0). */
    fun setSpin(direction: Int) {
        val dir = direction.coerceIn(-1, 1)
        if (dir == spinDir) return
        spinDir = dir
        if (dir != 0) {
            // Give instant feedback so even a short tap of the button produces a click.
            moveBy(dir * STEP, KICK_VELOCITY)
            if (velocity * dir < KICK_VELOCITY) velocity = dir * KICK_VELOCITY
        }
        ensureRunning()
    }

    // --- animation -----------------------------------------------------------------------------

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun step(now: Long) {
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = now

        if (!dragging) {
            if (spinDir != 0) {
                val target = spinDir * MAX_SPIN_SPEED
                val dv = SPIN_ACCEL * dt
                velocity = if (velocity < target) min(velocity + dv, target) else max(velocity - dv, target)
            } else {
                velocity *= exp(-FRICTION * dt)
                if (abs(velocity) < MIN_VELOCITY) velocity = 0f
            }
            if (velocity != 0f) moveBy(velocity * dt, abs(velocity))
        }

        invalidate()
        if (dragging || spinDir != 0 || velocity != 0f) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            running = false
        }
    }

    /** Rotates the dial by [delta] degrees and fires one detent event per tick mark passed. */
    private fun moveBy(delta: Float, speed: Float) {
        if (delta == 0f) return
        val oldIndex = floor(angle / STEP).toInt()
        angle += delta
        val crossings = floor(angle / STEP).toInt() - oldIndex
        while (angle >= 360f) angle -= 360f
        while (angle < 0f) angle += 360f
        if (crossings == 0) return

        val now = System.nanoTime()
        if (now - lastClickNanos < MIN_CLICK_GAP_NANOS) return // very fast flings: skip, don't machine-gun
        lastClickNanos = now
        val intensity = (speed / FULL_INTENSITY_SPEED).coerceIn(0f, 1f)
        detentListener?.onDetent(crossings > 0, intensity)

        val nowMs = now / 1_000_000L
        if (nowMs - lastHapticMs >= HAPTIC_GAP_MS) {
            lastHapticMs = nowMs
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
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

    // --- drawing -------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) * 0.40f

        val long = ArrayList<Float>()
        val mid = ArrayList<Float>()
        val short = ArrayList<Float>()
        for (i in 0 until DETENTS) {
            val a = Math.toRadians(i * STEP.toDouble())
            val s = sin(a).toFloat()
            val c = -cos(a).toFloat()
            val inner = when {
                i % 10 == 0 -> 0.80f
                i % 5 == 0 -> 0.84f
                else -> 0.88f
            }
            val target = when {
                i % 10 == 0 -> long
                i % 5 == 0 -> mid
                else -> short
            }
            target += cx + s * radius * inner
            target += cy + c * radius * inner
            target += cx + s * radius * 0.95f
            target += cy + c * radius * 0.95f
        }
        longTicks = long.toFloatArray()
        midTicks = mid.toFloatArray()
        shortTicks = short.toFloatArray()

        indexPath.reset()
        val tipY = cy - radius * 1.02f
        indexPath.moveTo(cx, tipY)
        indexPath.lineTo(cx - radius * 0.06f, tipY - radius * 0.11f)
        indexPath.lineTo(cx + radius * 0.06f, tipY - radius * 0.11f)
        indexPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        val r = radius

        // soft shadow under the whole dial
        fill.shader = RadialGradient(
            cx, cy + r * 0.06f, r * 1.28f,
            intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy + r * 0.06f, r * 1.28f, fill)

        // fixed bezel ring
        fill.shader = RadialGradient(
            cx - r * 0.4f, cy - r * 0.5f, r * 1.6f,
            intArrayOf(0xFF6B7078.toInt(), 0xFF2B2E34.toInt(), 0xFF15171A.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 1.09f, fill)

        // rotating part
        canvas.save()
        canvas.rotate(angle, cx, cy)

        // brushed-metal disc
        fill.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFF8A9099.toInt(), 0xFFC9CED6.toInt(), 0xFF70757D.toInt(), 0xFFB7BCC4.toInt(),
                0xFF666B73.toInt(), 0xFFD2D6DD.toInt(), 0xFF7B8089.toInt(), 0xFF8A9099.toInt(),
            ),
            floatArrayOf(0f, 0.14f, 0.3f, 0.45f, 0.6f, 0.76f, 0.9f, 1f),
        )
        canvas.drawCircle(cx, cy, r, fill)

        // knurled rim
        stroke.shader = null
        stroke.color = Color.argb(90, 0, 0, 0)
        stroke.strokeWidth = r * 0.012f
        canvas.drawCircle(cx, cy, r * 0.985f, stroke)

        // recessed face
        fill.shader = RadialGradient(
            cx, cy, r * 0.76f,
            intArrayOf(0xFF2A2D33.toInt(), 0xFF202328.toInt(), 0xFF17191D.toInt()),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 0.76f, fill)
        stroke.color = Color.argb(160, 0, 0, 0)
        stroke.strokeWidth = r * 0.02f
        canvas.drawCircle(cx, cy, r * 0.76f, stroke)

        // tick marks
        tick.color = 0xFFD9DDE3.toInt()
        tick.strokeWidth = r * 0.012f
        canvas.drawLines(shortTicks, tick)
        tick.strokeWidth = r * 0.018f
        canvas.drawLines(midTicks, tick)
        tick.color = 0xFFFFFFFF.toInt()
        tick.strokeWidth = r * 0.028f
        canvas.drawLines(longTicks, tick)

        // centre cap with an off-centre dimple so rotation is visible near the middle too
        fill.shader = RadialGradient(
            cx - r * 0.1f, cy - r * 0.12f, r * 0.5f,
            intArrayOf(0xFFB9BEC6.toInt(), 0xFF7A7F88.toInt(), 0xFF454950.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 0.42f, fill)
        stroke.color = Color.argb(140, 0, 0, 0)
        stroke.strokeWidth = r * 0.015f
        canvas.drawCircle(cx, cy, r * 0.42f, stroke)
        fill.shader = null
        fill.color = Color.argb(200, 20, 22, 25)
        canvas.drawCircle(cx, cy - r * 0.27f, r * 0.045f, fill)
        tick.color = Color.argb(200, 20, 22, 25)
        tick.strokeWidth = r * 0.03f
        canvas.drawLine(cx, cy - r * 0.08f, cx, cy + r * 0.08f, tick)
        canvas.drawLine(cx - r * 0.08f, cy, cx + r * 0.08f, cy, tick)

        canvas.restore()

        // fixed lighting highlight over the glass-like dial
        fill.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.45f, r * 1.1f,
            intArrayOf(Color.argb(70, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null

        // fixed index marker
        fill.color = 0xFFE5392F.toInt()
        canvas.drawPath(indexPath, fill)
    }

    companion object {
        /** Tick marks around the dial, like a safe. */
        const val DETENTS = 100
        const val STEP = 360f / DETENTS

        /** Max speed while a volume button is held, degrees/s (~44 clicks per second). */
        private const val MAX_SPIN_SPEED = 160f
        private const val SPIN_ACCEL = 600f
        private const val KICK_VELOCITY = 60f
        private const val FRICTION = 7f
        private const val MIN_VELOCITY = 6f
        private const val MAX_FLING_SPEED = 1500f
        private const val FULL_INTENSITY_SPEED = 500f
        private const val MIN_CLICK_GAP_NANOS = 9_000_000L
        private const val HAPTIC_GAP_MS = 35L
    }
}
