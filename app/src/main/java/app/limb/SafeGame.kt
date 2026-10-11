package app.limb

import kotlin.math.abs

/** How much help the hint ("on hearing and touch") gives. */
enum class Difficulty(
    /** The hint also fires this many numbers either side of the right one. */
    val margin: Int,
    /** Whether the hint has its own sound (otherwise: vibration only). */
    val hitSound: Boolean,
) {
    EASY(1, true),
    NORMAL(0, true),
    HARD(0, false),
}

/**
 * The rules of cracking a safe, with no Android in it: the dial is turned CLOCKWISE to the 1st number,
 * COUNTER-CLOCKWISE to the 2nd and CLOCKWISE to the 3rd.
 *
 * A number is dialed when the dial comes to rest on it (under the pointer) for [DWELL_MS], having
 * arrived in the right direction. Missing it (wrong number, direction changed) just means "not yet".
 * Starting to turn the wrong way right after dialing the 1st or 2nd number sends you back to the start.
 *
 * Inputs: [onRotated] for every movement of the dial (degrees, clockwise positive), [tick] now and then
 * with the number under the pointer. Times are in any steady millisecond clock.
 */
class SafeGame(val code: IntArray, startStep: Int = 0, nowMs: Long = 0L) {

    sealed interface Event {
        /** Number [index] (0-based) of the code was dialed. */
        data class StepDialed(val index: Int) : Event

        /** The last number was dialed: the safe is open. */
        object Opened : Event

        /** Started turning the wrong way after a dialed number: back to the 1st number. */
        object Reset : Event
    }

    var step = startStep.coerceIn(0, code.size - 1)
        private set
    var opened = false
        private set

    private var unwrapped = 0f // total angle turned, degrees
    private var dir = 0 // committed direction of travel: +1 clockwise, -1 counter-clockwise, 0 not yet
    private var extreme = 0f // furthest point reached in the committed direction
    private var stepStarted = true // false right after dialing a number, until the dial moves again
    private var anchor = 0f // where the dial last came to a rest
    private var stillSince = nowMs
    private var dwellConsumed = false

    /** The direction the current number has to be approached from. */
    val clockwiseNow: Boolean get() = DIRECTIONS[step] > 0

    fun onRotated(deltaDegrees: Float, nowMs: Long): Event? {
        if (opened || deltaDegrees == 0f) return null
        unwrapped += deltaDegrees
        if (abs(unwrapped - anchor) > STILL_TOLERANCE_DEG) {
            anchor = unwrapped // moved: the "standing still" clock starts again
            stillSince = nowMs
            dwellConsumed = false
        }

        // Direction of travel with hysteresis, so finger jitter and tiny back-and-forth don't flip it.
        var turned = 0
        when {
            dir == 0 -> {
                val d = unwrapped - extreme
                if (abs(d) >= TURN_DEG) turned = if (d > 0f) 1 else -1
            }
            dir > 0 -> if (unwrapped > extreme) {
                extreme = unwrapped
            } else if (extreme - unwrapped >= TURN_DEG) {
                turned = -1
            }
            else -> if (unwrapped < extreme) {
                extreme = unwrapped
            } else if (unwrapped - extreme >= TURN_DEG) {
                turned = 1
            }
        }
        if (turned == 0) return null

        dir = turned
        extreme = unwrapped
        if (step > 0 && !stepStarted) {
            stepStarted = true
            if (turned != DIRECTIONS[step]) return reset(nowMs)
        }
        return null
    }

    /** Call regularly. [number] is the number under the pointer. */
    fun tick(number: Int, nowMs: Long): Event? {
        if (opened || dwellConsumed || nowMs - stillSince < DWELL_MS) return null
        dwellConsumed = true
        if (dir != DIRECTIONS[step] || number != code[step]) return null
        if (step == code.size - 1) {
            opened = true
            return Event.Opened
        }
        val done = step
        step++
        stepStarted = false
        dir = 0
        extreme = unwrapped
        return Event.StepDialed(done)
    }

    /**
     * Should the dial give the hint for this click? [number] is the number now under the pointer,
     * [crossings] how many divisions were passed since the last click (they are all checked).
     */
    fun isHint(number: Int, clockwise: Boolean, crossings: Int, margin: Int): Boolean {
        if (opened || clockwise != clockwiseNow) return false
        val target = code[step]
        for (k in 0 until maxOf(1, crossings)) {
            // turning clockwise lowers the number under the pointer by one per division
            val passed = Math.floorMod(if (clockwise) number + k else number - k, SafeCode.NUMBERS)
            if (SafeCode.distance(passed, target) <= margin) return true
        }
        return false
    }

    private fun reset(nowMs: Long): Event {
        step = 0
        dir = 0
        extreme = unwrapped
        stepStarted = true
        stillSince = nowMs
        dwellConsumed = false
        return Event.Reset
    }

    companion object {
        /** Clockwise, counter-clockwise, clockwise. */
        private val DIRECTIONS = intArrayOf(1, -1, 1)

        const val DWELL_MS = 600L
        const val TURN_DEG = 6f // one division: this much travel against the direction turns it around
        const val STILL_TOLERANCE_DEG = 2f // drift smaller than this still counts as standing still
    }
}
