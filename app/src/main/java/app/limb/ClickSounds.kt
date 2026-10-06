package app.limb

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.random.Random

/** Low-latency, polyphonic click playback. Clockwise = plain click, counter-clockwise = ratchet. */
class ClickSounds(context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val forward = intArrayOf(R.raw.click_fwd_0, R.raw.click_fwd_1, R.raw.click_fwd_2)
        .map { pool.load(context, it, 1) }
    private val backward = intArrayOf(R.raw.click_back_0, R.raw.click_back_1, R.raw.click_back_2)
        .map { pool.load(context, it, 1) }

    fun play(clockwise: Boolean, intensity: Float) {
        val variants = if (clockwise) forward else backward
        val volume = (0.55f + 0.45f * intensity).coerceIn(0f, 1f)
        val rate = 0.97f + Random.nextFloat() * 0.06f // tiny pitch jitter so it does not sound sampled
        pool.play(variants[Random.nextInt(variants.size)], volume, volume, 1, 0, rate)
    }

    fun release() = pool.release()
}
