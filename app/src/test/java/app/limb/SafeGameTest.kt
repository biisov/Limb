package app.limb

import app.limb.SafeGame.Event
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pretend dial: the number under the pointer is floorMod(-round(angle / 6), 60), as in DialView. */
private class SimDial(code: IntArray, step: Int = 0, difficulty: Difficulty = Difficulty.NORMAL) {
    var now = 1000L
    var angle = 0f
    val game = SafeGame(code, step, now, difficulty)
    val events = mutableListOf<Event>()
    val number get() = Math.floorMod(-(angle / 6f).roundToInt(), 60)

    fun turn(divisions: Int, clockwise: Boolean) {
        repeat(divisions) {
            val d = if (clockwise) 6f else -6f
            angle = (angle + d + 360f) % 360f
            now += 50
            game.onRotated(d, now)?.let { events += it }
        }
    }

    fun idle(ms: Long) {
        var t = 0L
        while (t < ms) {
            now += 50
            t += 50
            game.tick(number, now)?.let { events += it }
        }
    }

    fun goTo(n: Int, clockwise: Boolean) {
        var guard = 0
        while (number != n && guard++ < 200) turn(1, clockwise)
    }
}

class SafeGameTest {
    private val code = intArrayOf(10, 30, 50)

    @Test
    fun randomCodesAreValid() {
        val rnd = java.util.Random(1)
        repeat(20000) { assertTrue(SafeCode.isValid(SafeCode.random(rnd))) }
    }

    @Test
    fun codeOfTheDayIsTheSameForEveryone() {
        assertTrue(SafeCode.forDay(20000).contentEquals(SafeCode.forDay(20000)))
        assertFalse(SafeCode.forDay(20000).contentEquals(SafeCode.forDay(20001)))
        for (day in 20000L until 20400L) assertTrue(SafeCode.isValid(SafeCode.forDay(day)))
    }

    @Test
    fun distanceGoesAroundTheDial() {
        assertEquals(1, SafeCode.distance(0, 59))
        assertEquals(30, SafeCode.distance(0, 30))
        assertEquals(5, SafeCode.distance(57, 2))
    }

    // The order is: counter-clockwise (numbers under the pointer go up), clockwise, counter-clockwise.

    @Test
    fun openingTheSafe() {
        val s = SimDial(code)
        s.goTo(10, clockwise = false)
        s.idle(500)
        assertTrue("not yet after 0.5 s", s.events.isEmpty())
        s.idle(200)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
        s.goTo(30, clockwise = true)
        s.idle(700)
        assertEquals(Event.StepDialed(1), s.events.last())
        s.goTo(50, clockwise = false)
        s.idle(700)
        assertEquals(Event.Opened, s.events.last())
        assertTrue(s.game.opened)
        s.turn(5, false)
        s.idle(1000)
        assertEquals(3, s.events.size)
    }

    @Test
    fun theFirstNumberCannotBeDialedClockwise() {
        val s = SimDial(code)
        s.goTo(10, clockwise = true)
        s.idle(2000)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun wrongNumberIsQuietlyIgnored() {
        val s = SimDial(code)
        s.goTo(8, clockwise = false)
        s.idle(2000)
        assertTrue(s.events.isEmpty())
        s.goTo(10, clockwise = false)
        s.idle(700)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun arrivingTheWrongWayDoesNotCount() {
        val s = SimDial(code)
        s.goTo(10, clockwise = false)
        s.turn(1, false) // one past it (no mistake: not more than a division)
        s.turn(1, true) // and back on it, but from the wrong side
        assertEquals(10, s.number)
        s.idle(2000)
        assertTrue(s.events.isEmpty())
        s.goTo(7, clockwise = true)
        s.goTo(10, clockwise = false)
        s.idle(700)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun turningBackInTheMiddleOfAStepIsQuiet() {
        val s = SimDial(code)
        s.goTo(10, false)
        s.idle(700)
        s.goTo(40, true) // on the way to 30
        s.turn(8, false) // turned back before getting there: quiet
        assertEquals(1, s.game.step)
        assertEquals(1, s.events.size)
        s.goTo(30, true)
        s.idle(700)
        assertEquals(Event.StepDialed(1), s.events.last())
    }

    @Test
    fun startingTheWrongWayAfterANumberIsNotPunished() {
        val s = SimDial(code)
        s.goTo(10, false)
        s.idle(700)
        assertEquals(1, s.game.step)
        s.turn(4, false) // step 2 is clockwise, but turning on the same way is no mistake by itself
        assertEquals(1, s.game.step)
        assertEquals(1, s.events.size)
    }

    // ---- mistakes: passing the number by more than one division and then turning back

    @Test
    fun passingByExactlyOneDivisionIsNotAMistake() {
        val s = SimDial(code)
        s.goTo(11, false) // one past 10
        s.turn(1, true) // and back
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun normalLosesOnlyTheCurrentStep() {
        val s = SimDial(code, difficulty = Difficulty.NORMAL)
        s.goTo(10, false)
        s.idle(700) // number 1 dialed
        s.goTo(30, true)
        s.turn(3, true) // too far (27)
        s.turn(1, false) // ...and back: a mistake
        assertEquals(Event.Reset(wholeSafe = false), s.events.last())
        assertEquals("the number already dialed is kept", 1, s.game.step)
    }

    @Test
    fun hardLosesEverything() {
        val s = SimDial(code, difficulty = Difficulty.HARD)
        s.goTo(10, false)
        s.idle(700)
        s.goTo(30, true)
        s.turn(3, true)
        s.turn(1, false)
        assertEquals(Event.Reset(wholeSafe = true), s.events.last())
        assertEquals(0, s.game.step)
    }

    @Test
    fun easyLosesNothingAndYouCanComeBack() {
        val s = SimDial(code, difficulty = Difficulty.EASY)
        s.goTo(10, false)
        s.turn(3, false) // 13
        s.turn(2, true) // back towards it (11): a mistake, but a forgiven one
        assertTrue(s.events.isEmpty())
        s.goTo(10, true) // arrives from the "wrong" side
        s.idle(700)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun normalCanDialTheStepAgainAfterAMistake() {
        val s = SimDial(code, difficulty = Difficulty.NORMAL)
        s.goTo(10, false)
        s.turn(3, false)
        s.turn(1, true)
        assertEquals(Event.Reset(wholeSafe = false), s.events.last())
        s.goTo(7, true)
        s.goTo(10, false) // arrives the right way this time
        s.idle(700)
        assertEquals(Event.StepDialed(0), s.events.last())
    }

    @Test
    fun noMistakeWhenTurningBackFromShortOfTheNumber() {
        val s = SimDial(code, difficulty = Difficulty.HARD)
        s.goTo(8, false) // not there yet
        s.turn(2, true) // turning back from short of it is no mistake
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun theLevelCanBeChangedOnTheFly() {
        val s = SimDial(code, difficulty = Difficulty.EASY)
        s.game.difficulty = Difficulty.HARD
        s.goTo(10, false)
        s.turn(3, false)
        s.turn(1, true)
        assertEquals(Event.Reset(wholeSafe = true), s.events.last())
    }

    @Test
    fun smallWobbleDoesNotSpoilTheApproach() {
        val s = SimDial(code)
        s.goTo(9, clockwise = false)
        s.now += 50
        s.game.onRotated(3f, s.now) // a few degrees the other way (finger jitter)...
        s.now += 50
        s.game.onRotated(-3f, s.now)
        s.turn(1, false)
        s.idle(700)
        assertEquals(10, s.number)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun smallJitterCountsAsStandingStill() {
        val s = SimDial(code)
        s.goTo(10, false)
        repeat(12) {
            s.now += 50
            s.game.onRotated(if (it % 2 == 0) 0.4f else -0.4f, s.now)
            s.game.tick(s.number, s.now)?.let { e -> s.events += e }
        }
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun hints() {
        val g = SafeGame(code) // step 1: counter-clockwise
        assertTrue(g.isHint(10, clockwise = false, crossings = 1, margin = 0))
        assertFalse("wrong direction", g.isHint(10, clockwise = true, crossings = 1, margin = 0))
        assertFalse(g.isHint(11, false, 1, 0))
        assertFalse(g.isHint(9, false, 1, 0))
        assertTrue("easy: neighbours too", g.isHint(11, false, 1, 1) && g.isHint(9, false, 1, 1))
        assertFalse(g.isHint(12, false, 1, 1))
        assertTrue("10 was among the 3 passed (10, 11, 12)", g.isHint(12, false, 3, 0))
        assertFalse("not among the 2 passed (12, 11)", g.isHint(12, false, 2, 0))

        val g2 = SafeGame(code, startStep = 1) // step 2: clockwise
        assertTrue(g2.isHint(30, true, 1, 0))
        assertFalse(g2.isHint(30, false, 1, 0))
        assertTrue("30 was among (30, 29, 28)", g2.isHint(28, true, 3, 0))
        assertFalse("not among (34, 33, 32)", g2.isHint(32, true, 3, 0))

        val g3 = SafeGame(code, startStep = 2) // step 3: counter-clockwise again
        assertTrue(g3.isHint(50, false, 1, 0))
        assertFalse(g3.isHint(50, true, 1, 0))
    }
}
