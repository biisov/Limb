package app.limb

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var dial: DialView
    private lateinit var sounds: ClickSounds
    private var upHeld = false
    private var downHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sounds = ClickSounds(this)
        dial = DialView(this).apply {
            setBackgroundColor(0xFF0E0F12.toInt())
            detentListener = DialView.DetentListener { clockwise, intensity ->
                sounds.play(clockwise, intensity)
            }
        }
        setContentView(dial)
    }

    // Volume buttons drive the dial: + spins it clockwise, - counter-clockwise, for as long as held.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
            upHeld = true
            updateSpin()
            true // consume (also auto-repeats) so the system volume UI stays away
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            downHeld = true
            updateSpin()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
            upHeld = false
            updateSpin()
            true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            downHeld = false
            updateSpin()
            true
        }
        else -> super.onKeyUp(keyCode, event)
    }

    private fun updateSpin() {
        dial.setSpin(
            when {
                upHeld && !downHeld -> 1
                downHeld && !upHeld -> -1
                else -> 0
            },
        )
    }

    override fun onPause() {
        // A key-up can be lost when we leave the screen; never keep spinning in the background.
        upHeld = false
        downHeld = false
        updateSpin()
        super.onPause()
    }

    override fun onDestroy() {
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
}
