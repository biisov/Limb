package app.limb

import app.limb.SafeGame.Event
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pretend dial: the number under the pointer is floorMod(-round(angle / 6), 60), as in DialView. */
private class SimDial(code: IntArray, step: Int = 0) {
    var now = 1000L
    var angle = 0f
    val game = SafeGame(code, step, now)
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

    @Test
    fun openingTheSafe() {
        val s = SimDial(code)
        s.goTo(10, clockwise = true)
        s.idle(500)
        assertTrue("not yet after 0.5 s", s.events.isEmpty())
        s.idle(200)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
        s.goTo(30, clockwise = false)
        s.idle(700)
        assertEquals(Event.StepDialed(1), s.events.last())
        s.goTo(50, clockwise = true)
        s.idle(700)
        assertEquals(Event.Opened, s.events.last())
        assertTrue(s.game.opened)
        s.turn(5, true)
        s.idle(1000)
        assertEquals(3, s.events.size)
    }

    @Test
    fun wrongNumberIsQuietlyIgnored() {
        val s = SimDial(code)
        s.goTo(12, clockwise = true)
        s.idle(2000)
        assertTrue(s.events.isEmpty())
        s.goTo(10, clockwise = true)
        s.idle(700)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun arrivingTheWrongWayDoesNotCount() {
        val s = SimDial(code)
        s.goTo(10, clockwise = true)
        s.turn(3, true)
        s.turn(3, false)
        assertEquals(10, s.number)
        s.idle(2000)
        assertTrue(s.events.isEmpty())
        s.goTo(13, clockwise = false)
        s.goTo(10, clockwise = true)
        s.idle(700)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun startingTheWrongWayAfterANumberResets() {
        val s = SimDial(code)
        s.goTo(10, true)
        s.idle(700)
        assertEquals(1, s.game.step)
        s.turn(2, true)
        assertEquals(Event.Reset, s.events.last())
        assertEquals(0, s.game.step)

        val t = SimDial(code)
        t.goTo(10, true)
        t.idle(700)
        t.goTo(30, false)
        t.idle(700)
        assertEquals(2, t.game.step)
        t.turn(2, false)
        assertEquals(Event.Reset, t.events.last())
        assertEquals(0, t.game.step)
    }

    @Test
    fun turningBackInTheMiddleOfAStepIsQuiet() {
        val s = SimDial(code)
        s.goTo(10, true)
        s.idle(700)
        s.goTo(25, false)
        s.turn(8, true)
        assertEquals(1, s.game.step)
        assertEquals(1, s.events.size)
        s.goTo(30, false)
        s.idle(700)
        assertEquals(Event.StepDialed(1), s.events.last())
    }

    @Test
    fun aRestoredGameDoesNotPunishTheFirstMove() {
        val s = SimDial(code, step = 1)
        s.turn(2, true)
        assertEquals(1, s.game.step)
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun smallJitterCountsAsStandingStill() {
        val s = SimDial(code)
        s.goTo(10, true)
        repeat(12) {
            s.now += 50
            s.game.onRotated(if (it % 2 == 0) 0.4f else -0.4f, s.now)
            s.game.tick(s.number, s.now)?.let { e -> s.events += e }
        }
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun hints() {
        val g = SafeGame(code)
        assertTrue(g.isHint(10, clockwise = true, crossings = 1, margin = 0))
        assertFalse("wrong direction", g.isHint(10, clockwise = false, crossings = 1, margin = 0))
        assertFalse(g.isHint(11, true, 1, 0))
        assertFalse(g.isHint(9, true, 1, 0))
        assertTrue("easy: neighbours too", g.isHint(11, true, 1, 1) && g.isHint(9, true, 1, 1))
        assertFalse(g.isHint(12, true, 1, 1))
        assertTrue("10 was among the 3 passed (10, 9, 8)", g.isHint(8, true, 3, 0))
        assertFalse("not among the 2 passed (9, 8)", g.isHint(8, true, 2, 0))

        val g2 = SafeGame(code, startStep = 1)
        assertTrue(g2.isHint(30, false, 1, 0))
        assertFalse(g2.isHint(30, true, 1, 0))
        assertTrue("30 was among (30, 31, 32)", g2.isHint(32, false, 3, 0))
        assertFalse("not among (26, 27, 28)", g2.isHint(28, false, 3, 0))
    }
}
