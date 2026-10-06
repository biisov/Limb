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
}
