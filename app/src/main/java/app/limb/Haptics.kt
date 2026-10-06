package app.limb

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * One short, crisp "tick" per dial division, like a time-picker wheel.
 *
 * Clockwise is a regular tick; counter-clockwise is a weaker one to match its softer sound.
 *  - Android 10+: [VibrationEffect.EFFECT_TICK]; the weaker one is a TICK primitive at low scale
 *    (Android 11+), or a very short low-amplitude pulse.
 *  - older Android (and devices that can't do the above): [HapticFeedbackConstants.CLOCK_TICK].
 */
class Haptics(private val view: View) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (view.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val strongTick: VibrationEffect? =
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else null

    private val softTick: VibrationEffect? = when {
        Build.VERSION.SDK_INT >= 30 &&
            vibrator?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK) == true ->
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                .compose()
        vibrator?.hasAmplitudeControl() == true -> VibrationEffect.createOneShot(8, 40)
        else -> null
    }

    fun tick(clockwise: Boolean) {
        val v = vibrator
        val effect = if (clockwise) strongTick else softTick
        if (v != null && effect != null && v.hasVibrator()) {
            v.vibrate(effect)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
}
