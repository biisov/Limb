package app.limb

import kotlin.math.abs

/** What a mistake costs. A mistake: passing the right number by more than one division and then turning back. */
enum class Mistake {
    /** Nothing is lost, and the number can be dialed by turning back to it. */
    FORGIVE,

    /** Only the number being looked for is lost; the numbers already dialed are kept. */
    RESTART_STEP,

    /** The whole progress is lost: start again with the 1st number. */
    RESTART_ALL,
}

/** How much help the hint ("on hearing and touch") gives, and what a mistake costs. */
enum class Difficulty(
    /** The hint also fires this many numbers either side of the right one. */
    val margin: Int,
    val onMistake: Mistake,
) {
    EASY(1, Mistake.FORGIVE),
    NORMAL(0, Mistake.RESTART_STEP),
    HARD(0, Mistake.RESTART_ALL),
}

/**
 * The rules of cracking a safe, with no Android in it: the dial is turned COUNTER-CLOCKWISE to the 1st
 * number (the numbers under the pointer go up), CLOCKWISE to the 2nd and COUNTER-CLOCKWISE to the 3rd.
 *
 * A number is dialed when the dial comes to rest on it (under the pointer) for [DWELL_MS], having
 * arrived in the right direction. Stopping elsewhere, or arriving the wrong way, just means "not yet".
 *
 * A *mistake* is passing the number being looked for by more than one division while turning the right
 * way, and then turning back without having dialed it. What it costs depends on [difficulty].
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

        /** A mistake that costs something: [wholeSafe] = back to the 1st number, else only the current number. */
        data class Reset(val wholeSafe: Boolean) : Event
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

        // The run that just ended: did it pass the number being looked for by more than a division?
        val missed = dir == DIRECTIONS[step] && passedByMoreThanADivision(runStart, extreme, dir)
        runStart = extreme // the turning point
        dir = turned
        extreme = unwrapped
        return if (missed) mistake(nowMs) else null
    }

    /** Call regularly. [number] is the number under the pointer. */
    fun tick(number: Int, nowMs: Long): Event? {
        if (opened || dwellConsumed || nowMs - stillSince < DWELL_MS) return null
        dwellConsumed = true
        if (number != code[step] || (dir != DIRECTIONS[step] && !forgiven)) return null
        if (step == code.size - 1) {
            opened = true
            return Event.Opened
        }
        val done = step
        step++
        forgiven = false
        dir = 0
        extreme = unwrapped
        runStart = unwrapped
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

    /** Was the number being looked for passed by more than one division during this run (from [start] to [end])? */
    private fun passedByMoreThanADivision(start: Float, end: Float, direction: Int): Boolean {
        val base = -STEP * code[step] // angles at which the number is under the pointer: base + 360 k
        return if (direction > 0) {
            val first = base + 360f * kotlin.math.ceil((start - base) / 360f) // the first one at or after the start
            end - first > STEP + EPS
        } else {
            val first = base + 360f * kotlin.math.floor((start - base) / 360f) // the last one at or before the start
            first - end > STEP + EPS
        }
    }

    private fun mistake(nowMs: Long): Event? = when (difficulty.onMistake) {
        Mistake.FORGIVE -> {
            forgiven = true // you can come back and dial it
            null
        }
        Mistake.RESTART_STEP -> {
            restartRun(nowMs)
            Event.Reset(wholeSafe = false)
        }
        Mistake.RESTART_ALL -> {
            step = 0
            restartRun(nowMs)
            Event.Reset(wholeSafe = true)
        }
    }

    private fun restartRun(nowMs: Long) {
        forgiven = false
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
