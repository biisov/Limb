package app.limb

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The screen: the dial fills it, and the slot (`@id/ad_container`) sits above the dial.
 *
 * The slot is ~72% of the screen wide and ~13% high, centred, its top at ~15% of the screen. The dial
 * is never resized or moved: if the slot would run into the pointer above the dial, the slot is
 * moved up instead.
 */
class MainLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val slot: View? = findViewById(R.id.ad_container)
        if (slot != null && w > 0 && h > 0) {
            val slotHeight = (h * SLOT_HEIGHT).roundToInt()
            val gap = w * SLOT_GAP
            val lowestBottom = DialView.pointerTop(w, h) - gap
            val top = max(0f, minOf(h * SLOT_TOP, lowestBottom - slotHeight))

            val lp = slot.layoutParams as LayoutParams
            lp.width = (w * SLOT_WIDTH).roundToInt()
            lp.height = slotHeight
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = top.roundToInt()
            slot.layoutParams = lp
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val SLOT_WIDTH = 0.72f // of the screen width
        const val SLOT_HEIGHT = 0.13f // of the screen height
        const val SLOT_TOP = 0.15f // of the screen height
        const val SLOT_GAP = 0.03f // of the screen width, minimum space between the slot and the pointer
    }
}
