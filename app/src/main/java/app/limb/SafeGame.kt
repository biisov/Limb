package app.limb

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** What a mistake costs. A mistake: passing the right number by more than one division, turning the right way. */
enum class MistakeCost {
    /** Nothing is lost, and the number can be dialed by turning back to it. */
    FORGIVE,

    /** One step back: the number dialed last is lost and has to be dialed again. */
    ROLL_BACK,

    /** The whole progress is lost: start again with the 1st number. */
    RESTART_ALL,
}

/** How much help the hint ("on hearing and touch") gives, and what a mistake costs. */
enum class Difficulty(
    /** The hint also fires this many numbers either side of the right one. */
    val margin: Int,
    val onMistake: MistakeCost,
) {
    EASY(1, MistakeCost.FORGIVE),
    NORMAL(0, MistakeCost.ROLL_BACK),
    HARD(0, MistakeCost.RESTART_ALL),
}

/**
 * The rules of cracking a safe, with no Android in it: the dial is turned COUNTER-CLOCKWISE to the 1st
 * number (the numbers under the pointer go up), CLOCKWISE to the 2nd and COUNTER-CLOCKWISE to the 3rd.
 *
 * A number is dialed when the dial comes to rest on it (under the pointer) for [DWELL_MS], having
 * arrived in the right direction. Stopping elsewhere, or arriving the wrong way, just means "not yet".
 *
 * A *mistake* happens at the moment the pointer, turning the right way for the current step, has gone
 * past the number being looked for by more than one division - no need to wait for a turn-around, so
 * going on for another lap does not help. Going past by exactly one division is a forgiven
 * correction: the number may be dialed by coming back to it. What a mistake costs depends on
 * [difficulty]:
 *  - EASY:   nothing; the number can be dialed by coming back to it from either side
 *  - NORMAL: one step back (the number dialed last is lost); on the 1st step you stay there, but have
 *            to turn around and approach again - another lap the same way does not count
 *  - HARD:   everything is lost, back to the 1st number (again with the turn-around on the 1st step)
 *
 * Inputs: [onRotated] for every movement of the dial (degrees, clockwise positive), [tick] now and then
 * with the number under the pointer. Times are in any steady millisecond clock.
 */
class SafeGame(
    val code: IntArray,
    startStep: Int = 0,
    nowMs: Long = 0L,
    var difficulty: Difficulty = Difficulty.NORMAL,
) {

    sealed interface Event {
        /** Number [index] (0-based) of the code was dialed. */
        data class StepDialed(val index: Int) : Event

        /** The last number was dialed: the safe is open. */
        object Opened : Event

        /** A mistake that costs something: back from step [from] to step [to] (0-based; equal on the 1st). */
        data class Mistake(val from: Int, val to: Int) : Event
    }

    var step = startStep.coerceIn(0, code.size - 1)
        private set
    var opened = false
        private set

    private var unwrapped = 0f // total angle turned, degrees
    private var dir = 0 // committed direction of travel: +1 clockwise, -1 counter-clockwise, 0 not yet
    private var extreme = 0f // furthest point reached in the committed direction
    private var runStart = 0f // where the current run in the committed direction began
    private var forgiven = false // easy level: after a mistake the number may be dialed from either side
    private var nearMissAngle = Float.NaN // where a one-division overshoot was turned back; NaN = none
    private var lock = Lock.NONE // after a mistake on the same step: must turn around and approach again
    private var anchor = 0f // where the dial last came to a rest
    private var stillSince = nowMs
    private var dwellConsumed = false

    private enum class Lock { NONE, NEEDS_REVERSE, NEEDS_RETURN }

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
        // A correction is only forgiven close to the number: further away it is a new attempt.
        if (!nearMissAngle.isNaN() && abs(unwrapped - nearMissAngle) > STEP + EPS) nearMissAngle = Float.NaN

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

        if (turned != 0) {
            // The run that just ended, and where it turned around.
            val required = DIRECTIONS[step]
            if (dir == required) {
                val over = overshoot(runStart, extreme, dir)
                if (over > EPS && over <= STEP + EPS) nearMissAngle = crossingAfter(runStart, dir) // a small correction
            }
            // Turn-around needed after a mistake on this same step: first the other way, then back again.
            if (lock == Lock.NEEDS_REVERSE && turned == -required) lock = Lock.NEEDS_RETURN
            else if (lock == Lock.NEEDS_RETURN && turned == required) lock = Lock.NONE

            runStart = extreme // the turning point
            dir = turned
            extreme = unwrapped
        }

        // Going on the right way past the number by more than a division: a mistake, right now.
        if (dir == DIRECTIONS[step] && overshoot(runStart, extreme, dir) > STEP + EPS) return mistake(nowMs)
        return null
    }

    /** Call regularly. [number] is the number under the pointer. */
    fun tick(number: Int, nowMs: Long): Event? {
        if (opened || dwellConsumed || nowMs - stillSince < DWELL_MS) return null
        dwellConsumed = true
        val arrivedRight = dir == DIRECTIONS[step] || forgiven || !nearMissAngle.isNaN()
        if (number != code[step] || lock != Lock.NONE || !arrivedRight) return null
        if (step == code.size - 1) {
            opened = true
            return Event.Opened
        }
        val done = step
        step++
        restartRun(nowMs)
        dwellConsumed = true // standing still on the number just dialed is not a new attempt
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

    // --- mistakes ------------------------------------------------------------------------------

    /**
     * The angle at which the number being looked for is under the pointer, first met after [start] when
     * turning in [direction] (a number the dial starts on is not "passed" by moving away from it).
     */
    private fun crossingAfter(start: Float, direction: Int): Float {
        val base = -STEP * code[step] // the number is under the pointer at base + 360 k
        return if (direction > 0) {
            base + 360f * (floor((start + EPS - base) / 360f) + 1f)
        } else {
            base + 360f * (ceil((start - EPS - base) / 360f) - 1f)
        }
    }

    /** How far the run from [start] to [end] has gone past the number: negative while it has not reached it. */
    private fun overshoot(start: Float, end: Float, direction: Int): Float {
        val crossing = crossingAfter(start, direction)
        return if (direction > 0) end - crossing else crossing - end
    }

    private fun mistake(nowMs: Long): Event? {
        val from = step
        return when (difficulty.onMistake) {
            MistakeCost.FORGIVE -> {
                forgiven = true // you can come back and dial it
                null
            }
            MistakeCost.ROLL_BACK -> restart(from, maxOf(0, from - 1), nowMs)
            MistakeCost.RESTART_ALL -> restart(from, 0, nowMs)
        }
    }

    private fun restart(from: Int, to: Int, nowMs: Long): Event {
        step = to
        restartRun(nowMs)
        // The same number has to be found again: turn around first, another lap the same way is no good.
        if (to == from) lock = Lock.NEEDS_REVERSE
        return Event.Mistake(from, to)
    }

    private fun restartRun(nowMs: Long) {
        forgiven = false
        nearMissAngle = Float.NaN
        lock = Lock.NONE
        dir = 0
        extreme = unwrapped
        runStart = unwrapped
        stillSince = nowMs
        dwellConsumed = false
    }

    companion object {
        /** Counter-clockwise, clockwise, counter-clockwise. */
        private val DIRECTIONS = intArrayOf(-1, 1, -1)

        const val DWELL_MS = 600L
        const val TURN_DEG = 6f // one division: this much travel against the direction turns it around
        const val STILL_TOLERANCE_DEG = 2f // drift smaller than this still counts as standing still
        private const val STEP = 6f // degrees per division (360 / 60)
        private const val EPS = 0.001f
    }
}
