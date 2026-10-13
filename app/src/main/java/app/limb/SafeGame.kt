package app.limb

import kotlin.math.abs
import kotlin.math.roundToInt

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
 * Mistakes are counted on the dial's position in whole divisions, [pos] (counter-clockwise = up, not
 * wrapped at 60). Each step has an *anchor*: the position [startPos] where the step began (or where the
 * dial last turned back past it), and [dist], how far the number being looked for is from there in the
 * direction of the step. [progress] = how far the dial has gone from the anchor in that direction:
 *  - below 0: it went the other way - the anchor is just moved there, no mistake
 *  - [dist]: on the number;  [dist] + 1: one division past it, which is forgiven (turn back onto it)
 *  - above [dist] + 1: past it by more than a division - a mistake right then, another lap or not.
 * What a mistake costs depends on [difficulty]:
 *  - EASY:   nothing; the number can be dialed by coming back to it from either side
 *  - NORMAL: one step back (the number dialed last is lost); on the 1st step you stay there, but have
 *            to turn around and approach again - another lap the same way does not count
 *  - HARD:   everything is lost, back to the 1st number (again with the turn-around on the 1st step)
 * After a mistake, as after a dialed number, the anchor is set again for the step that is now current.
 *
 * Inputs: [onRotated] for every movement of the dial (degrees, clockwise positive), [tick] now and then
 * with the number under the pointer. [startAngle] is the dial's angle when the game begins (clockwise
 * positive, as in the dial: the number under the pointer is floorMod(-round(angle / 6), 60)).
 * Times are in any steady millisecond clock.
 */
class SafeGame(
    val code: IntArray,
    startStep: Int = 0,
    nowMs: Long = 0L,
    var difficulty: Difficulty = Difficulty.NORMAL,
    startAngle: Float = 0f,
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

    private var angle = startAngle // the dial's angle, degrees, clockwise positive, not wrapped
    private var dir = 0 // committed direction of travel: +1 clockwise, -1 counter-clockwise, 0 not yet
    private var extreme = startAngle // furthest point reached in the committed direction
    private var forgiven = false // easy level: after a mistake the number may be dialed from either side
    private var lock = Lock.NONE // after a mistake on the same step: must turn around and approach again
    private var restAngle = startAngle // where the dial last came to a rest
    private var stillSince = nowMs
    private var dwellConsumed = false

    // The anchor of the current step (see above).
    private var startPos = 0
    private var dist = 0
    private var maxProgress = 0

    private enum class Lock { NONE, NEEDS_REVERSE, NEEDS_RETURN }

    /** Whole divisions turned, counter-clockwise up: the number under the pointer is floorMod(pos, 60). */
    private val pos: Int get() = posOf(angle)

    /** The direction of the current step in [pos] terms: +1 counter-clockwise, -1 clockwise. */
    private val stepDir: Int get() = -DIRECTIONS[step]

    init {
        anchorHere()
    }

    /** The direction the current number has to be approached from. */
    val clockwiseNow: Boolean get() = DIRECTIONS[step] > 0

    fun onRotated(deltaDegrees: Float, nowMs: Long): Event? {
        if (opened || deltaDegrees == 0f) return null
        angle += deltaDegrees
        if (abs(angle - restAngle) > STILL_TOLERANCE_DEG) {
            restAngle = angle // moved: the "standing still" clock starts again
            stillSince = nowMs
            dwellConsumed = false
        }

        // Direction of travel with hysteresis, so finger jitter and tiny back-and-forth don't flip it.
        var turned = 0
        when {
            dir == 0 -> {
                val d = angle - extreme
                if (abs(d) >= TURN_DEG) turned = if (d > 0f) 1 else -1
            }
            dir > 0 -> if (angle > extreme) {
                extreme = angle
            } else if (extreme - angle >= TURN_DEG) {
                turned = -1
            }
            else -> if (angle < extreme) {
                extreme = angle
            } else if (angle - extreme >= TURN_DEG) {
                turned = 1
            }
        }

        if (turned != 0) {
            // Turn-around needed after a mistake on this same step: first the other way, then back again.
            val required = DIRECTIONS[step]
            if (lock == Lock.NEEDS_REVERSE && turned == -required) lock = Lock.NEEDS_RETURN
            else if (lock == Lock.NEEDS_RETURN && turned == required) lock = Lock.NONE

            dir = turned
            extreme = angle
        }

        val progress = (pos - startPos) * stepDir
        if (progress < 0) {
            anchorHere() // went the other way: measure from here
        } else {
            if (progress > maxProgress) maxProgress = progress
            // Past the number by more than a division, the right way: a mistake, right now.
            if (progress > dist + 1) return mistake(nowMs)
        }
        return null
    }

    /** Call regularly. [number] is the number under the pointer. */
    fun tick(number: Int, nowMs: Long): Event? {
        if (opened || dwellConsumed || nowMs - stillSince < DWELL_MS) return null
        dwellConsumed = true
        if (number != Math.floorMod(pos, SafeCode.NUMBERS)) {
            // Out of step with the dial (it moved without telling us): take its position.
            follow(number)
            return null
        }
        val progress = (pos - startPos) * stepDir
        val onNumber = progress == dist
        // Arrived the right way - or one division past it and turned back onto it (forgiven).
        val arrivedRight = forgiven || (onNumber && (dir == DIRECTIONS[step] || maxProgress == dist + 1))
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
     * The dial is at [dialAngle] (it may have been turned while the game was not listening): follow it
     * and start measuring the current step from there. Does nothing if the position already agrees.
     */
    fun resync(dialAngle: Float, nowMs: Long) {
        // (the dial keeps its angle within one turn, the game does not: compare the numbers)
        if (opened || Math.floorMod(posOf(dialAngle), SafeCode.NUMBERS) == Math.floorMod(pos, SafeCode.NUMBERS)) return
        angle = dialAngle
        restartRun(nowMs, keepLock = true)
    }

    private fun follow(number: Int) {
        val diff = Math.floorMod(number - Math.floorMod(pos, SafeCode.NUMBERS) + 30, SafeCode.NUMBERS) - 30
        angle -= diff * STEP
        restartRun(stillSince, keepLock = true)
        dwellConsumed = true
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

    /** Sets the anchor of the current step here: the distance to its number in the direction of the step. */
    private fun anchorHere() {
        startPos = pos
        dist = Math.floorMod((code[step] - Math.floorMod(startPos, SafeCode.NUMBERS)) * stepDir, SafeCode.NUMBERS)
        maxProgress = 0
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

    private fun restartRun(nowMs: Long, keepLock: Boolean = false) {
        forgiven = false
        if (!keepLock) lock = Lock.NONE
        dir = 0
        extreme = angle
        restAngle = angle
        stillSince = nowMs
        dwellConsumed = false
        anchorHere() // the anchor for the step that is current now
    }

    private fun posOf(a: Float) = -(a / STEP).roundToInt()

    companion object {
        /** Counter-clockwise, clockwise, counter-clockwise. */
        private val DIRECTIONS = intArrayOf(-1, 1, -1)

        const val DWELL_MS = 600L
        const val TURN_DEG = 6f // one division: this much travel against the direction turns it around
        const val STILL_TOLERANCE_DEG = 2f // drift smaller than this still counts as standing still
        private const val STEP = 6f // degrees per division (360 / 60)
    }
}
