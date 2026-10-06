package app.limb

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.math.roundToLong

class MainActivity : Activity() {

    private lateinit var dial: DialView
    private lateinit var sounds: ClickSounds
    private lateinit var haptics: Haptics

    private val handler = Handler(Looper.getMainLooper())
    private var holdDirection = 0 // +1 clockwise / -1 counter-clockwise while a volume button is held, else 0
    private var repeatStartedAt = 0L // uptime (ms) of the first continuous step of this hold
    private val repeatStep = object : Runnable {
        override fun run() {
            if (holdDirection == 0) return
            val now = SystemClock.uptimeMillis()
            if (repeatStartedAt == 0L) repeatStartedAt = now
            // Speed up from HOLD_START_RATE to HOLD_MAX_RATE divisions/s over HOLD_RAMP_MS.
            val t = ((now - repeatStartedAt) / HOLD_RAMP_MS).coerceIn(0f, 1f)
            val rate = HOLD_START_RATE + (HOLD_MAX_RATE - HOLD_START_RATE) * t
            dial.step(holdDirection, rate)
            handler.postDelayed(this, (1000f / rate).roundToLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sounds = ClickSounds(this)
        setContentView(R.layout.activity_main)
        dial = findViewById(R.id.dial)
        haptics = Haptics(dial)
        // Sound and haptic tick are always played together, rate-limited - touch, fling or volume keys.
        val feedback = ClickFeedback(sounds, haptics)
        dial.detentListener = DialView.DetentListener { clockwise, divisionsPerSecond ->
            feedback.onDetent(clockwise, divisionsPerSecond)
        }
    }

    // Volume buttons turn the dial: + counter-clockwise, - clockwise. A tap is exactly one division;
    // holding (after a short pause) keeps stepping on our own timer. The system key auto-repeat is
    // ignored, and returning true keeps the system volume from changing.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val direction = directionFor(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) startHold(direction)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val direction = directionFor(keyCode) ?: return super.onKeyUp(keyCode, event)
        if (holdDirection == direction) stopHold()
        return true
    }

    /** Dial direction for a volume key: +1 clockwise, -1 counter-clockwise, null for other keys. */
    private fun directionFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> -1
        KeyEvent.KEYCODE_VOLUME_DOWN -> 1
        else -> null
    }

    private fun startHold(direction: Int) {
        handler.removeCallbacks(repeatStep)
        holdDirection = direction
        repeatStartedAt = 0L
        dial.step(direction)
        handler.postDelayed(repeatStep, HOLD_DELAY_MS)
    }

    private fun stopHold() {
        holdDirection = 0
        handler.removeCallbacks(repeatStep)
    }

    override fun onPause() {
        // A key-up can be lost when we leave the screen; never keep spinning in the background.
        stopHold()
        super.onPause()
    }

    override fun onDestroy() {
        stopHold()
        sounds.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private companion object {
        /** Pause before a held volume button starts turning the dial continuously. */
        const val HOLD_DELAY_MS = 400L

        /** Held button: divisions per second at the start of the continuous turning... */
        const val HOLD_START_RATE = 12f

        /** ...and after [HOLD_RAMP_MS]. Kept just under 1000/35 (the click interval) so every click is heard. */
        const val HOLD_MAX_RATE = 28f
        const val HOLD_RAMP_MS = 1500f
    }
}
