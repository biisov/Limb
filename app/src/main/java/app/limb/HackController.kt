package app.limb

import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import java.time.LocalDate
import java.util.Random

/**
 * The "Safe cracking" mode on top of the free dial: owns the current safe, feeds the dial's movement to
 * [SafeGame], gives the hint for the right number, shows progress and handles opening, statistics and
 * the safe of the day. In free mode ([active] = false) it does nothing at all.
 */
class HackController(
    private val activity: Activity,
    private val dial: DialView,
    private val dots: ProgressDots,
    private val caption: TextView,
    private val statsLine: TextView,
    private val sounds: ClickSounds,
    private val haptics: Haptics,
) {
    private enum class Phase { PLAYING, OPENED }

    private val store = SafeStore(activity)
    private val handler = Handler(Looper.getMainLooper())

    var active = false
        private set

    private var difficulty = store.difficulty
    private var game: SafeGame? = null
    private var phase = Phase.PLAYING
    private var isDaily = false
    private var safeDay = 0L
    private var elapsedMs = 0L // time spent on this safe, not counting what is running now
    private var runningSince = 0L // uptime (ms) the timer last started; 0 while it is not running

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val g = game
            if (g != null && phase == Phase.PLAYING) handle(g.tick(dial.currentNumber, now()))
            handler.postDelayed(this, TICK_MS)
        }
    }
    private var captionFadeOutMs = FADE_MS
    private var openedCaptionShown = false // the "Opened" text (and statistics) is on screen
    private val hideCaption = Runnable { fadeOutCaption(captionFadeOutMs) }
    private val nextSafe = Runnable {
        startNewSafe()
        if (active) dots.filled = 0
    }

    // --- mode ----------------------------------------------------------------------------------

    fun toggle() {
        if (active) leave() else enter()
    }

    private fun enter() {
        active = true
        ensureSafe()
        dots.visibility = android.view.View.VISIBLE
        dots.filled = if (phase == Phase.OPENED) SafeCode.LENGTH else game?.step ?: 0
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        val kind = if (isDaily) " · " + activity.getString(R.string.safe_of_the_day) else ""
        showCaption(activity.getString(R.string.mode_hack) + kind, CAPTION_MS)
    }

    private fun leave() {
        active = false
        stopTimer()
        persist()
        handler.removeCallbacks(tick)
        dots.visibility = android.view.View.GONE
        dial.pointerGlow = 0f
        showCaption(activity.getString(R.string.mode_free), CAPTION_MS)
    }

    fun chooseDifficulty() {
        val names = arrayOf(
            activity.getString(R.string.difficulty_easy),
            activity.getString(R.string.difficulty_normal),
            activity.getString(R.string.difficulty_hard),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.difficulty_title)
            .setSingleChoiceItems(names, difficulty.ordinal) { dialog, which ->
                difficulty = Difficulty.values()[which]
                store.difficulty = difficulty
                dialog.dismiss()
                showCaption(activity.getString(R.string.difficulty_set, names[which]), CAPTION_MS)
            }
            .show()
    }

    fun onPause() {
        if (!active) return
        stopTimer()
        persist()
        handler.removeCallbacks(tick)
    }

    fun onResume() {
        if (!active) return
        convertStaleDaily()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    // --- the dial ------------------------------------------------------------------------------

    fun onRotated(degrees: Float) {
        if (!active) return
        // Turning the dial (finger or volume keys) takes the "Opened" text away at once...
        if (openedCaptionShown) {
            handler.removeCallbacks(hideCaption)
            fadeOutCaption(DISMISS_FADE_MS)
        }
        // ...and that very movement already counts for the new safe (once it exists).
        val g = game
        if (g == null || phase != Phase.PLAYING) return
        if (runningSince == 0L) runningSince = now() // the clock runs from the first movement
        handle(g.onRotated(degrees, now()))
    }

    /** What the click for this division should be. */
    fun hintFor(clockwise: Boolean, number: Int, crossings: Int): Hint {
        val g = game
        if (!active || g == null || phase != Phase.PLAYING) return Hint.NONE
        if (!g.isHint(number, clockwise, crossings, difficulty.margin)) return Hint.NONE
        return if (difficulty.hitSound) Hint.HIT else Hint.HAPTIC_ONLY
    }

    private fun handle(event: SafeGame.Event?) {
        when (event) {
            null -> Unit
            is SafeGame.Event.StepDialed -> {
                dots.filled = event.index + 1
                persist()
            }
            SafeGame.Event.Reset -> {
                sounds.playDull()
                haptics.doubleTick()
                dots.filled = 0
                persist()
            }
            SafeGame.Event.Opened -> open()
        }
    }

    // --- opening -------------------------------------------------------------------------------

    private fun open() {
        phase = Phase.OPENED
        stopTimer()
        val time = elapsedMs
        val today = today()
        store.recordOpened(time, wasSafeOfTheDay = isDaily && safeDay == today, today = today)
        persist()

        sounds.playUnlock()
        haptics.longSoft()
        dial.playUnlockTurn()
        dots.filled = SafeCode.LENGTH
        flash()

        val best = store.bestMs
        showCaption(
            activity.getString(R.string.opened, formatTime(time)),
            OPENED_MS,
            activity.getString(
                R.string.stats,
                store.openedCount,
                if (best != null) formatTime(best) else "–",
                store.streak(today),
            ),
            fadeInMs = OPENED_FADE_IN_MS,
            fadeOutMs = OPENED_FADE_OUT_MS,
        )
        openedCaptionShown = true
        // The new safe is ready as soon as the opening animation is over, text or no text.
        handler.removeCallbacks(nextSafe)
        handler.postDelayed(nextSafe, NEXT_SAFE_MS)
    }

    private fun flash() {
        ValueAnimator.ofPropertyValuesHolder(
            PropertyValuesHolder.ofKeyframe(
                "glow",
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.12f, 1f),
                Keyframe.ofFloat(0.45f, 1f),
                Keyframe.ofFloat(1f, 0f),
            ),
        ).apply {
            duration = FLASH_MS
            addUpdateListener {
                val v = it.getAnimatedValue("glow") as Float
                dial.pointerGlow = v
                dots.flash = v
            }
            start()
        }
    }

    // --- the safe ------------------------------------------------------------------------------

    private fun ensureSafe() {
        if (game != null) {
            convertStaleDaily()
            return
        }
        val saved = store.loadSafe()
        if (saved == null || saved.opened) {
            startNewSafe()
            return
        }
        phase = Phase.PLAYING
        isDaily = saved.isDaily && saved.day == today()
        safeDay = saved.day
        elapsedMs = saved.elapsedMs
        runningSince = 0L
        game = SafeGame(saved.code, saved.step, now())
        if (saved.isDaily && !isDaily) persist()
    }

    /** A safe of the day is only that on its own day; later it is just a safe (and is never swapped). */
    private fun convertStaleDaily() {
        if (isDaily && safeDay != today()) {
            isDaily = false
            persist()
        }
    }

    private fun startNewSafe() {
        val today = today()
        // The first safe of each day is the safe of the day; the rest are random.
        val daily = store.dailyDoneDay != today
        val code = if (daily) SafeCode.forDay(today) else SafeCode.random(Random())
        phase = Phase.PLAYING
        isDaily = daily
        safeDay = today
        elapsedMs = 0L
        runningSince = 0L
        game = SafeGame(code, 0, now())
        persist()
    }

    private fun persist() {
        val g = game ?: return
        store.saveSafe(
            SavedSafe(
                code = g.code,
                step = g.step,
                isDaily = isDaily,
                day = safeDay,
                opened = phase == Phase.OPENED,
                elapsedMs = elapsedNow(),
            ),
        )
    }

    // --- time ----------------------------------------------------------------------------------

    private fun now() = SystemClock.uptimeMillis()

    private fun today() = LocalDate.now().toEpochDay()

    private fun elapsedNow() = elapsedMs + if (runningSince != 0L) now() - runningSince else 0L

    private fun stopTimer() {
        elapsedMs = elapsedNow()
        runningSince = 0L
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    // --- text ----------------------------------------------------------------------------------

    private fun showCaption(
        text: String,
        holdMs: Long,
        stats: String? = null,
        fadeInMs: Long = FADE_MS / 2,
        fadeOutMs: Long = FADE_MS,
    ) {
        handler.removeCallbacks(hideCaption)
        caption.animate().cancel()
        statsLine.animate().cancel()
        openedCaptionShown = false
        captionFadeOutMs = fadeOutMs
        caption.text = text
        statsLine.text = stats ?: ""
        caption.animate().alpha(1f).setDuration(fadeInMs).start()
        statsLine.animate().alpha(if (stats != null) 1f else 0f).setDuration(fadeInMs).start()
        handler.postDelayed(hideCaption, holdMs)
    }

    private fun fadeOutCaption(durationMs: Long) {
        openedCaptionShown = false
        caption.animate().alpha(0f).setDuration(durationMs).start()
        statsLine.animate().alpha(0f).setDuration(durationMs).start()
    }

    private companion object {
        const val TICK_MS = 50L
        const val CAPTION_MS = 1000L
        const val OPENED_MS = 5000L // the "Opened" text stays up to this long
        const val OPENED_FADE_IN_MS = 400L
        const val OPENED_FADE_OUT_MS = 600L
        const val DISMISS_FADE_MS = 200L // ...or fades out this fast once the dial is turned
        const val NEXT_SAFE_MS = 1000L // the new safe appears after the opening animation
        const val FLASH_MS = 1000L
        const val FADE_MS = 300L
    }
}
