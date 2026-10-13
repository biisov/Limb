package app.limb

import app.limb.SafeGame.Event
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pretend dial: the number under the pointer is floorMod(-round(angle / 6), 60), as in DialView. */
private class SimDial(
    code: IntArray,
    step: Int = 0,
    difficulty: Difficulty = Difficulty.NORMAL,
    startAngle: Float = 0f,
) {
    var now = 1000L
    var angle = startAngle
    val game = SafeGame(code, step, now, difficulty, startAngle)
    val events = mutableListOf<Event>()
    val number get() = Math.floorMod(-(angle / 6f).roundToInt(), 60)

    fun turn(divisions: Int, clockwise: Boolean) {
        repeat(divisions) {
            val d = if (clockwise) 6f else -6f
            angle += d
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

    /** Dials [n] the way a person would: if already on it, step away first, then come to it, and wait. */
    fun dial(n: Int, clockwise: Boolean) {
        if (number == n) turn(3, !clockwise)
        goTo(n, clockwise)
        idle(700)
    }

    val mistakes get() = events.filterIsInstance<Event.Mistake>()

    /** Dials the code correctly from here: counter-clockwise, clockwise, counter-clockwise. */
    fun dialAll() {
        dial(game.code[0], clockwise = false)
        dial(game.code[1], clockwise = true)
        dial(game.code[2], clockwise = false)
    }
}

/** The angle at which number [n] is under the pointer. */
private fun angleOf(n: Int) = -6f * n

private val ALL_LEVELS = Difficulty.values().toList()

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
    fun arrivingFromTheWrongSideDoesNotCount() {
        val s = SimDial(code)
        s.goTo(13, clockwise = true) // from 0 down to 13: clockwise is the wrong way for the 1st number
        s.goTo(10, clockwise = true) // and on to 10, still the wrong way
        s.idle(2000)
        assertTrue(s.events.isEmpty())
        s.goTo(8, clockwise = true)
        s.goTo(10, clockwise = false) // now the right way
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

    // ---- mistakes: the pointer goes past the number by more than one division, turning the right way

    @Test
    fun passingByExactlyOneDivisionIsForgivenAndCanBeCorrected() {
        val s = SimDial(code, difficulty = Difficulty.HARD)
        s.goTo(10, false)
        s.turn(1, false) // one past it: forgiven
        s.turn(1, true) // a small correction back onto it
        assertEquals(10, s.number)
        assertTrue(s.events.isEmpty())
        s.idle(700)
        assertEquals("the corrected number counts", listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun theMistakeComesAtOnceNotAtTheTurnAround() {
        val s = SimDial(code, difficulty = Difficulty.NORMAL)
        s.goTo(10, false)
        s.turn(1, false) // 11: still forgiven
        assertTrue(s.events.isEmpty())
        s.turn(1, false) // 12: two divisions past it
        assertEquals(listOf<Event>(Event.Mistake(0, 0)), s.events)
    }

    @Test
    fun anotherLapDoesNotHelp() {
        for (level in listOf(Difficulty.NORMAL, Difficulty.HARD)) {
            val s = SimDial(code, difficulty = level)
            s.goTo(10, false)
            s.turn(2, false) // went past: the mistake
            assertEquals(1, s.events.size)
            s.turn(58, false) // on round the dial and back onto 10, the same way
            assertEquals(10, s.number)
            s.idle(2000)
            assertTrue("$level: a second lap is not accepted", s.events.none { it is Event.StepDialed })
        }
    }

    @Test
    fun afterAMistakeOnTheFirstStepYouTurnAroundAndApproachAgain() {
        val s = SimDial(code, difficulty = Difficulty.NORMAL)
        s.goTo(10, false)
        s.turn(2, false) // the mistake (12); stays on step 1
        assertEquals(0, s.game.step)
        s.goTo(8, true) // turn around and go back past it...
        s.goTo(10, false) // ...and approach it the right way again
        s.idle(700)
        assertEquals(Event.StepDialed(0), s.events.last())
    }

    @Test
    fun normalLosesTheLastDialedNumber() {
        val s = SimDial(code, difficulty = Difficulty.NORMAL)
        s.goTo(10, false)
        s.idle(700) // number 1 dialed
        s.goTo(30, true) // on the way: step 2 is clockwise
        s.turn(2, true) // two past 30: a mistake on step 2
        assertEquals(Event.Mistake(1, 0), s.events.last())
        assertEquals("one step back: the 1st number is to be dialed again", 0, s.game.step)
    }

    @Test
    fun normalGoesBackFromTheThirdStepToTheSecond() {
        val s = SimDial(code, difficulty = Difficulty.NORMAL)
        s.goTo(10, false)
        s.idle(700)
        s.goTo(30, true)
        s.idle(700)
        assertEquals(2, s.game.step)
        s.goTo(50, false) // step 3 target is 50 (counter-clockwise)...
        s.turn(2, false) // ...two past it
        assertEquals(Event.Mistake(2, 1), s.events.last())
        assertEquals(1, s.game.step)
    }

    @Test
    fun hardLosesEverything() {
        val s = SimDial(code, difficulty = Difficulty.HARD)
        s.goTo(10, false)
        s.idle(700)
        s.goTo(30, true)
        s.idle(700)
        s.goTo(50, false)
        s.turn(2, false)
        assertEquals(Event.Mistake(2, 0), s.events.last())
        assertEquals(0, s.game.step)
    }

    @Test
    fun hardOnTheFirstStepAlsoNeedsATurnAround() {
        val s = SimDial(code, difficulty = Difficulty.HARD)
        s.goTo(10, false)
        s.turn(2, false)
        assertEquals(Event.Mistake(0, 0), s.events.last())
        s.turn(58, false) // a lap the same way
        s.idle(2000)
        assertTrue(s.events.none { it is Event.StepDialed })
    }

    @Test
    fun easyHasNoMistakesAndYouCanComeBack() {
        val s = SimDial(code, difficulty = Difficulty.EASY)
        s.goTo(10, false)
        s.turn(3, false) // 13: well past it
        s.turn(2, true) // back towards it (11)
        assertTrue(s.events.isEmpty())
        s.goTo(10, true) // arrives from the "wrong" side
        s.idle(700)
        assertEquals(listOf<Event>(Event.StepDialed(0)), s.events)
    }

    @Test
    fun passingTheNumberTheWrongWayIsNoMistake() {
        val s = SimDial(code, difficulty = Difficulty.HARD)
        s.goTo(20, true) // clockwise from 0: through 10 on the way to 20 the wrong way
        s.turn(40, true)
        assertTrue(s.events.isEmpty())
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
        s.turn(2, false)
        assertEquals(Event.Mistake(0, 0), s.events.last())
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

    // ---- the whole dialing, with the dial starting anywhere (code like 10 -> 45 -> 20) ----------------

    private val code2 = intArrayOf(10, 45, 20)

    @Test
    fun dialingAllThreeNumbersOpensTheSafeOnEveryLevelFromAnyStartingNumber() {
        for (level in ALL_LEVELS) for (start in 0 until 60) {
            val s = SimDial(code2, difficulty = level, startAngle = angleOf(start))
            s.dialAll()
            assertTrue("$level from $start: ${s.events}", s.game.opened)
            assertEquals("$level from $start", listOf<Event>(Event.StepDialed(0), Event.StepDialed(1), Event.Opened), s.events)
        }
    }

    @Test
    fun aDialStartingBetweenTwoNumbersIsTreatedTheSame() {
        for (level in ALL_LEVELS) for (start in listOf(17, 30, 37)) {
            val s = SimDial(code2, difficulty = level, startAngle = angleOf(start) + 2.5f)
            s.dialAll()
            assertEquals("$level from $start", listOf<Event>(Event.StepDialed(0), Event.StepDialed(1), Event.Opened), s.events)
        }
    }

    @Test
    fun theSecondNumberAcrossZeroIsNoMistake() {
        val across = intArrayOf(5, 55, 30)
        for (level in ALL_LEVELS) for (start in listOf(0, 1, 17, 30, 55, 59)) {
            val s = SimDial(across, difficulty = level, startAngle = angleOf(start))
            s.dialAll()
            assertTrue("$level from $start: ${s.events}", s.mistakes.isEmpty())
            assertEquals("$level from $start", listOf<Event>(Event.StepDialed(0), Event.StepDialed(1), Event.Opened), s.events)
        }
    }

    @Test
    fun theSecondNumberOverrunByTwoOrMoreRollsBackOrResets() {
        for (past in listOf(2, 3, 7)) for (start in listOf(0, 17, 40)) {
            for (level in listOf(Difficulty.NORMAL, Difficulty.HARD)) {
                val s = SimDial(code2, difficulty = level, startAngle = angleOf(start))
                s.dial(10, clockwise = false)
                s.goTo(45, clockwise = true)
                s.turn(1, clockwise = true)
                assertTrue("$level past $past start $start: one past is still fine: ${s.events}", s.mistakes.isEmpty())
                s.turn(past - 1, clockwise = true)
                assertEquals("$level past $past from $start", listOf(Event.Mistake(1, 0)), s.mistakes)
                assertEquals("the 1st dot goes out: back to the 1st number", 0, s.game.step)
            }
        }
    }

    @Test
    fun theThirdNumberOverrunDiffersBetweenNormalAndHard() {
        for ((level, expected) in listOf(Difficulty.NORMAL to 1, Difficulty.HARD to 0)) {
            val s = SimDial(code2, difficulty = level, startAngle = angleOf(23))
            s.dial(10, false)
            s.dial(45, true)
            s.goTo(20, clockwise = false)
            s.turn(2, clockwise = false)
            assertEquals(listOf(Event.Mistake(2, expected)), s.mistakes)
            assertEquals(expected, s.game.step)
        }
    }

    @Test
    fun overrunningByExactlyOneAndComingBackIsNoMistakeAndTheNumberCounts() {
        for (level in ALL_LEVELS) for (start in listOf(0, 17, 40)) {
            val s = SimDial(code2, difficulty = level, startAngle = angleOf(start))
            s.dial(10, false)
            s.goTo(45, clockwise = true)
            s.turn(1, clockwise = true) // 44
            s.turn(1, clockwise = false) // back onto 45
            s.idle(700)
            assertTrue("$level from $start", s.mistakes.isEmpty())
            assertEquals(Event.StepDialed(1), s.events.last())

            s.goTo(20, clockwise = false)
            s.turn(1, clockwise = false) // 21
            s.turn(1, clockwise = true) // 20
            s.idle(700)
            assertTrue(s.mistakes.isEmpty())
            assertEquals(Event.Opened, s.events.last())
        }
    }

    @Test
    fun anExtraLapToTheSameNumberIsAMistakeBeforeTheNumberIsReached() {
        for (level in listOf(Difficulty.NORMAL, Difficulty.HARD)) for (step in 0..2) {
            val s = SimDial(code2, step, level, angleOf(33))
            val target = code2[step]
            val clockwise = step == 1
            s.goTo(target, clockwise)
            s.turn(2, clockwise) // past it: the mistake fires right here
            val atOnce = s.mistakes.size
            assertEquals("$level step ${step + 1}", 1, atOnce)
            s.turn(58, clockwise) // a whole lap on, the number is under the pointer again
            assertEquals(target, s.number)
            s.idle(2000)
            assertTrue("$level step ${step + 1}: the lap does not dial it", s.events.none { it is Event.StepDialed })
        }
    }

    @Test
    fun easyNeverResetsAndTheNumbersCanStillBeDialed() {
        for (start in listOf(0, 17, 40)) {
            val s = SimDial(code2, difficulty = Difficulty.EASY, startAngle = angleOf(start))
            for ((i, clockwise) in listOf(false, true, false).withIndex()) {
                s.goTo(code2[i], clockwise)
                s.turn(5, clockwise) // well past it...
                s.turn(61, clockwise) // ...and a lap on
                s.goTo(code2[i], clockwise) // ...and back to it
                s.idle(700)
            }
            assertTrue("from $start: ${s.events}", s.mistakes.isEmpty())
            assertTrue(s.game.opened)
        }
    }

    @Test
    fun mistakeOnTheSecondNumberDoesNotSpoilTheNextAttempt() {
        val s = SimDial(code2, difficulty = Difficulty.NORMAL, startAngle = angleOf(30))
        s.dial(10, false)
        s.goTo(45, clockwise = true)
        s.turn(3, clockwise = true) // rolled back to the 1st number
        assertEquals(0, s.game.step)
        s.dialAll() // and then everything done right
        assertTrue(s.game.opened)
        assertEquals(1, s.mistakes.size)
    }

    @Test
    fun theGameFollowsTheDialIfItMovedWithoutTellingIt() {
        val s = SimDial(code2, startAngle = angleOf(0))
        s.angle = angleOf(33) // turned while the mode was off
        s.game.resync(s.angle, s.now)
        s.dialAll()
        assertTrue(s.events.toString(), s.game.opened)
        assertTrue(s.mistakes.isEmpty())
    }

    @Test
    fun aDriftOfTheGameIsCorrectedWhenTheDialIsAtRest() {
        val s = SimDial(code2, startAngle = angleOf(0))
        s.angle = angleOf(17) // moved behind its back
        s.idle(1000)
        assertTrue(s.events.isEmpty())
        s.dialAll()
        assertTrue(s.events.toString(), s.game.opened)
    }
}
