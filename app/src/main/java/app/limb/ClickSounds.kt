package app.limb

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Low-latency click playback. The two recordings are played untouched: no pitch or volume changes.
 * Clockwise -> res/raw/click_cw, counter-clockwise -> res/raw/click_ccw.
 */
class ClickSounds(context: Context) {

    // Few streams on purpose: when the dial spins fast, old clicks are cut instead of piling up.
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val clockwiseSound = pool.load(context, R.raw.click_cw, 1)
    private val counterClockwiseSound = pool.load(context, R.raw.click_ccw, 1)

    fun play(clockwise: Boolean) {
        pool.play(if (clockwise) clockwiseSound else counterClockwiseSound, 1f, 1f, 1, 0, 1f)
    }

    fun release() = pool.release()
}
