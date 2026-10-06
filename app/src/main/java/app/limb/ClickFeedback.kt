package app.limb

import kotlin.random.Random

/**
 * Turns dial divisions into sound + haptic ticks, and keeps fast spinning from turning into noise.
 *
 *  - At most one click per [SOUND_INTERVAL_MS] and one haptic tick per [HAPTIC_INTERVAL_MS]. If the
 *    dial passes several divisions in that time, only the first one is played; the others are skipped.
 *  - Volume depends on speed: 100% up to [SLOW_RATE] divisions/s, then smoothly down to
 *    [MIN_VOLUME] at [FAST_RATE] divisions/s and above.
 *  - A small random volume variation (+-[VOLUME_JITTER]) so repeated clicks don't sound identical.
 *
 * A tick always goes together with a click (the ticks are just sparser at high speed).
 */
class ClickFeedback(private val sounds: ClickSounds, private val haptics: Haptics) {

    private var lastSoundNanos = System.nanoTime() - SOUND_INTERVAL_NANOS
    private var lastHapticNanos = System.nanoTime() - HAPTIC_INTERVAL_NANOS

    /** [divisionsPerSecond] is how fast the dial is turning when it passes the division. */
    fun onDetent(clockwise: Boolean, divisionsPerSecond: Float) {
        val now = System.nanoTime()
        if (now - lastSoundNanos < SOUND_INTERVAL_NANOS) return
        lastSoundNanos = now
        sounds.play(clockwise, volumeFor(divisionsPerSecond))
        if (now - lastHapticNanos >= HAPTIC_INTERVAL_NANOS) {
            lastHapticNanos = now
            haptics.tick(clockwise)
        }
    }

    private fun volumeFor(divisionsPerSecond: Float): Float {
        val t = ((divisionsPerSecond - SLOW_RATE) / (FAST_RATE - SLOW_RATE)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        val base = 1f - smooth * (1f - MIN_VOLUME)
        val jitter = 1f + (Random.nextFloat() * 2f - 1f) * VOLUME_JITTER
        return (base * jitter).coerceIn(0f, 1f) // a stream cannot be louder than 100%
    }

    private companion object {
        const val SOUND_INTERVAL_MS = 35L // ~28 clicks per second at most
        const val SOUND_INTERVAL_NANOS = SOUND_INTERVAL_MS * 1_000_000L
        const val HAPTIC_INTERVAL_MS = 70L // ~14 ticks per second at most
        const val HAPTIC_INTERVAL_NANOS = HAPTIC_INTERVAL_MS * 1_000_000L
        const val SLOW_RATE = 15f // divisions per second: full volume up to here
        const val FAST_RATE = 45f // divisions per second: minimum volume from here
        const val MIN_VOLUME = 0.55f
        const val VOLUME_JITTER = 0.10f
    }
}
