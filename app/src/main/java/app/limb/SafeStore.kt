package app.limb

import android.content.Context

/** The safe being cracked, as saved between sessions. */
class SavedSafe(
    val code: IntArray,
    val step: Int,
    val isDaily: Boolean,
    /** Local date (epoch day) the safe was made on; for the safe of the day, the day it belongs to. */
    val day: Long,
    /** Opened already; the next one is made when the game is next shown. */
    val opened: Boolean,
    val elapsedMs: Long,
)

/** Everything the safe-cracking mode keeps on the device. */
class SafeStore(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var difficulty: Difficulty
        get() = Difficulty.values().getOrElse(prefs.getInt(DIFFICULTY, Difficulty.NORMAL.ordinal)) { Difficulty.NORMAL }
        set(value) = prefs.edit().putInt(DIFFICULTY, value.ordinal).apply()

    fun loadSafe(): SavedSafe? {
        if (!prefs.contains(CODE)) return null
        val code = prefs.getString(CODE, "")!!.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
        if (!SafeCode.isValid(code)) return null
        return SavedSafe(
            code = code,
            step = prefs.getInt(STEP, 0).coerceIn(0, code.size - 1),
            isDaily = prefs.getBoolean(IS_DAILY, false),
            day = prefs.getLong(DAY, 0L),
            opened = prefs.getBoolean(OPENED, false),
            elapsedMs = prefs.getLong(ELAPSED, 0L),
        )
    }

    fun saveSafe(safe: SavedSafe) {
        prefs.edit()
            .putString(CODE, safe.code.joinToString(","))
            .putInt(STEP, safe.step)
            .putBoolean(IS_DAILY, safe.isDaily)
            .putLong(DAY, safe.day)
            .putBoolean(OPENED, safe.opened)
            .putLong(ELAPSED, safe.elapsedMs)
            .apply()
    }

    // --- statistics ----------------------------------------------------------------------------

    val openedCount: Int get() = prefs.getInt(COUNT, 0)

    /** Best time in ms, or null if no safe has been opened yet. */
    val bestMs: Long? get() = prefs.getLong(BEST, -1L).takeIf { it >= 0 }

    /** The day (epoch day) the safe of the day was last opened, or -1. */
    val dailyDoneDay: Long get() = prefs.getLong(DAILY_DONE, -1L)

    /** Days in a row with the safe of the day opened, as of [today] (0 once a day has been missed). */
    fun streak(today: Long): Int =
        if (prefs.getLong(LAST_DAILY, -1L) >= today - 1) prefs.getInt(STREAK, 0) else 0

    fun recordOpened(timeMs: Long, wasSafeOfTheDay: Boolean, today: Long) {
        val edit = prefs.edit().putInt(COUNT, openedCount + 1)
        val best = bestMs
        if (best == null || timeMs < best) edit.putLong(BEST, timeMs)
        if (wasSafeOfTheDay) {
            val last = prefs.getLong(LAST_DAILY, -1L)
            edit.putInt(STREAK, if (last == today - 1) prefs.getInt(STREAK, 0) + 1 else 1)
                .putLong(LAST_DAILY, today)
                .putLong(DAILY_DONE, today)
        }
        edit.apply()
    }

    private companion object {
        const val NAME = "safe"
        const val DIFFICULTY = "difficulty"
        const val CODE = "code"
        const val STEP = "step"
        const val IS_DAILY = "is_daily"
        const val DAY = "day"
        const val OPENED = "opened"
        const val ELAPSED = "elapsed_ms"
        const val COUNT = "opened_count"
        const val BEST = "best_ms"
        const val DAILY_DONE = "daily_done_day"
        const val LAST_DAILY = "last_daily_day"
        const val STREAK = "streak"
    }
}
