package app.limb

import java.util.Random

/** Safe combinations: 3 numbers 0..59, neighbouring numbers at least [MIN_GAP] apart on the dial. */
object SafeCode {
    const val NUMBERS = 60
    const val LENGTH = 3
    const val MIN_GAP = 5

    /** Shortest distance between two numbers around the dial (0 and 59 are 1 apart). */
    fun distance(a: Int, b: Int): Int {
        val d = Math.floorMod(a - b, NUMBERS)
        return minOf(d, NUMBERS - d)
    }

    fun random(rnd: Random): IntArray {
        val code = IntArray(LENGTH)
        code[0] = rnd.nextInt(NUMBERS)
        for (i in 1 until LENGTH) {
            do {
                code[i] = rnd.nextInt(NUMBERS)
            } while (distance(code[i], code[i - 1]) < MIN_GAP)
        }
        return code
    }

    /** The "safe of the day": the same code for everyone on the same (local) date. */
    fun forDay(epochDay: Long): IntArray = random(Random(epochDay * 1_000_003L + 0x5AFEL))

    fun isValid(code: IntArray): Boolean =
        code.size == LENGTH &&
            code.all { it in 0 until NUMBERS } &&
            (1 until LENGTH).all { distance(code[it], code[it - 1]) >= MIN_GAP }
}
