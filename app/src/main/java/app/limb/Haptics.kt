package app.limb

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * One short haptic tick per dial division, in step with the sound.
 *
 * Counter-clockwise is the strong, crisp one; clockwise is weaker and softer. The strength follows the
 * direction the dial actually turned (the `clockwise` flag of the detent event), never the button.
 *  - Android 11+: CLICK primitive at full scale vs. TICK primitive at low scale
 *  - Android 10:  EFFECT_CLICK vs. EFFECT_TICK (the lightest built-in effect)
 *  - older, or a motor without these: a short full-amplitude / low-amplitude pulse, and finally
 *    [HapticFeedbackConstants] (KEYBOARD_TAP vs. CLOCK_TICK).
 */
class Haptics(private val view: View) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (view.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val strong: VibrationEffect? = build(crisp = true)
    private val soft: VibrationEffect? = build(crisp = false)

    // Safe-cracking hint: roughly 1.5x the normal tick of the same direction.
    private val hitClockwise: VibrationEffect? = buildHit(clockwise = true)
    private val hitCounterClockwise: VibrationEffect? = buildHit(clockwise = false)

    private fun build(crisp: Boolean): VibrationEffect? {
        val v = vibrator ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            val primitive =
                if (crisp) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK
            if (v.areAllPrimitivesSupported(primitive)) {
                return VibrationEffect.startComposition()
                    .addPrimitive(primitive, if (crisp) 1.0f else 0.35f)
                    .compose()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return VibrationEffect.createPredefined(
                if (crisp) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK,
            )
        }
        if (v.hasAmplitudeControl()) {
            return if (crisp) VibrationEffect.createOneShot(12, 255) else VibrationEffect.createOneShot(6, 50)
        }
        return null
    }

    private fun buildHit(clockwise: Boolean): VibrationEffect? {
        val v = vibrator ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            val click = VibrationEffect.Composition.PRIMITIVE_CLICK
            val thud = VibrationEffect.Composition.PRIMITIVE_THUD
            if (clockwise && v.areAllPrimitivesSupported(click)) {
                // the normal clockwise tick is a TICK at 0.35: a CLICK at 0.55 is clearly firmer
                return VibrationEffect.startComposition().addPrimitive(click, 0.55f).compose()
            }
            if (!clockwise && v.areAllPrimitivesSupported(click, thud)) {
                // the normal counter-clockwise tick is already a full CLICK: add some weight to it
                return VibrationEffect.startComposition()
                    .addPrimitive(click, 1.0f)
                    .addPrimitive(thud, 0.5f)
                    .compose()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return VibrationEffect.createPredefined(
                if (clockwise) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_HEAVY_CLICK,
            )
        }
        if (v.hasAmplitudeControl()) {
            return if (clockwise) VibrationEffect.createOneShot(8, 75) else VibrationEffect.createOneShot(18, 255)
        }
        return null
    }

    /** The "right number" tick: stronger than the normal one of the same direction. */
    fun hit(clockwise: Boolean) {
        val v = vibrator
        val effect = if (clockwise) hitClockwise else hitCounterClockwise
        if (v != null && effect != null && v.hasVibrator()) {
            v.vibrate(effect)
        } else {
            view.performHapticFeedback(
                if (clockwise) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.LONG_PRESS,
            )
        }
    }

    /** Two short, sharp pulses: the safe-cracking progress was lost. */
    fun doubleTick() {
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 70, 25), -1))
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * The safe opens, in step with res/raw/unlock.ogg: a run of very light, short ticks over the first
     * half second (the gears), one crisp tick at [UNLOCK_CRISP_MS] and, 0.1 s later, one strong long
     * pulse at [UNLOCK_FINAL_HIT_MS] (the final knock).
     */
    fun unlockSequence() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) return
        // (start ms, length ms, strength 1..255)
        val pulses = ArrayList<LongArray>()
        if (v.hasAmplitudeControl()) {
            for (start in UNLOCK_GEAR_TICKS_MS) pulses += longArrayOf(start, 8, 45)
        }
        pulses += longArrayOf(UNLOCK_CRISP_MS, 14, 200)
        pulses += longArrayOf(UNLOCK_FINAL_HIT_MS, 80, 255)

        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        var t = 0L
        for (p in pulses) {
            if (p[0] > t) {
                timings += p[0] - t // a pause
                amplitudes += 0
            }
            timings += p[1]
            amplitudes += p[2].toInt()
            t = p[0] + p[1]
        }
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
        } else {
            VibrationEffect.createWaveform(timings.toLongArray(), -1) // alternating off / on, full strength
        }
        v.vibrate(effect)
    }

    /** Counter-clockwise: strong, crisp tick. Clockwise: weak, soft tick. */
    fun tick(clockwise: Boolean) {
        val strongTick = !clockwise
        val v = vibrator
        val effect = if (strongTick) strong else soft
        if (v != null && effect != null && v.hasVibrator()) {
            v.vibrate(effect)
        } else {
            view.performHapticFeedback(
                if (strongTick) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.CLOCK_TICK,
            )
        }
    }

    companion object {
        /** When the gear ticks of the unlock sound start (ms), as in the recording. */
        private val UNLOCK_GEAR_TICKS_MS = longArrayOf(0, 30, 60, 90, 130, 160, 200, 240, 280, 330, 370, 420, 470, 530)

        /** The smaller knock in the unlock sound, 0.1 s before the final one. */
        const val UNLOCK_CRISP_MS = 610L

        /** The final knock of the unlock sound: when the dots turn green and the dial turns over. */
        const val UNLOCK_FINAL_HIT_MS = 710L
    }
}
