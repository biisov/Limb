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
            val density = resources.displayMetrics.density
            val dotsHeight = ProgressDots.DOT_DP * density
            val slotHeight = (h * SLOT_HEIGHT).roundToInt()
            // Room for the three dots between the slot and the pointer (normally the 3% already is).
            val gap = max(w * SLOT_GAP, dotsHeight + DOTS_MARGIN_DP * density)
            val pointerTop = DialView.pointerTop(w, h)
            val lowestBottom = pointerTop - gap
            val top = max(0f, minOf(h * SLOT_TOP, lowestBottom - slotHeight))

            place(slot, (w * SLOT_WIDTH).roundToInt(), slotHeight, top.roundToInt())

            // The safe-cracking dots: just above the pointer, below the slot.
            findViewById<View>(R.id.progress_dots)?.let { dots ->
                val centre = pointerTop - minOf((pointerTop - (top + slotHeight)) / 2f, MAX_DOTS_OFFSET_DP * density)
                // the view has a little room around the dots for the flash: centre it on the dots
                val viewHeight = dotsHeight * (1f + 2f * DOTS_FLASH_ROOM)
                place(dots, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, (centre - viewHeight / 2f).roundToInt())
            }

            // Mode name / "Opened" / statistics: below the dial.
            findViewById<View>(R.id.status_container)?.let { status ->
                val below = (DialView.dialBottom(w, h) + h * STATUS_BELOW_DIAL).roundToInt()
                place(status, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, below)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Top-centres [view] with this size and top margin; touches the layout params only if they change. */
    private fun place(view: View, width: Int, height: Int, top: Int) {
        val lp = view.layoutParams as LayoutParams
        val gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        if (lp.width == width && lp.height == height && lp.topMargin == top && lp.gravity == gravity) return
        lp.width = width
        lp.height = height
        lp.gravity = gravity
        lp.topMargin = top
        view.layoutParams = lp
    }

    private companion object {
        const val SLOT_WIDTH = 0.72f // of the screen width
        const val SLOT_HEIGHT = 0.13f // of the screen height
        const val SLOT_TOP = 0.15f // of the screen height
        const val SLOT_GAP = 0.03f // of the screen width, minimum space between the slot and the pointer
        const val DOTS_MARGIN_DP = 4f // air around the safe-cracking dots, in the gap
        const val MAX_DOTS_OFFSET_DP = 20f // the dots sit at most this far above the pointer
        const val DOTS_FLASH_ROOM = 0.35f // matches ProgressDots' room for the flash
        const val STATUS_BELOW_DIAL = 0.05f // of the screen height
    }
}
