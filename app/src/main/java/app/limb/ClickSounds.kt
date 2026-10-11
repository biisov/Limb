package app.limb

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Low-latency click playback. The two recordings are played untouched (no pitch change), only the
 * playback volume varies. Clockwise -> res/raw/click_cw, counter-clockwise -> res/raw/click_ccw.
 */
class ClickSounds(context: Context) {

    // At most 4 clicks at once. A click that is still playing is never stopped by us.
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
    private val hitSound = pool.load(context, R.raw.click_hit, 1)
    private val unlockSound = pool.load(context, R.raw.unlock, 1)

    /** [volume] is 0..1. */
    fun play(clockwise: Boolean, volume: Float) {
        pool.play(if (clockwise) clockwiseSound else counterClockwiseSound, volume, volume, 1, 0, 1f)
    }

    /** The click that says "this is the right number" (safe-cracking mode). */
    fun playHit(volume: Float) {
        pool.play(hitSound, volume, volume, 1, 0, 1f)
    }

    fun playUnlock() {
        pool.play(unlockSound, 1f, 1f, 1, 0, 1f)
    }

    /** One dull click: the safe-cracking progress was lost. */
    fun playDull() {
        pool.play(counterClockwiseSound, DULL_VOLUME, DULL_VOLUME, 1, 0, 1f)
    }

    fun release() = pool.release()

    private companion object {
        const val DULL_VOLUME = 0.5f
    }
}
