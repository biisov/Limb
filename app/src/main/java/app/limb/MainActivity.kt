package app.limb

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var dial: DialView
    private lateinit var sounds: ClickSounds
    private lateinit var haptics: Haptics

    private val handler = Handler(Looper.getMainLooper())
    private var holdDirection = 0 // +1 / -1 while a volume button is held, else 0
    private val repeatStep = object : Runnable {
        override fun run() {
            if (holdDirection == 0) return
            dial.step(holdDirection)
            handler.postDelayed(this, HOLD_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sounds = ClickSounds(this)
        setContentView(R.layout.activity_main)
        dial = findViewById(R.id.dial)
        haptics = Haptics(dial)
        // One sound and one haptic tick per division, always together - touch, fling or volume keys.
        dial.detentListener = DialView.DetentListener { clockwise, _ ->
            sounds.play(clockwise)
            haptics.tick(clockwise)
        }
    }

    // Volume buttons turn the dial: + clockwise, - counter-clockwise. A tap is exactly one division;
    // holding (after a short pause) keeps stepping on our own timer. The system key auto-repeat is
    // ignored, and returning true keeps the system volume from changing.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
            if (event.repeatCount == 0) {
                startHold(if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            }
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            if (holdDirection == direction) stopHold()
            true
        }
        else -> super.onKeyUp(keyCode, event)
    }

    private fun startHold(direction: Int) {
        handler.removeCallbacks(repeatStep)
        holdDirection = direction
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

        /** Time between divisions while held: ~13 per second. */
        const val HOLD_INTERVAL_MS = 75L
    }
}
