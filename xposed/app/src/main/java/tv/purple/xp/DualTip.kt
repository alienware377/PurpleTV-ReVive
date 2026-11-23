package tv.purple.xp

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A one-time speech bubble under the two streams button, the same as the one for the filters. It
 * lives in the control bar, so it only shows while the player controls do. Its X hides it for
 * good, and so does opening the picker from the bubble or the button.
 */
object DualTip {

    const val KEY = "dual_tip_dismissed"
    private const val TAG = "ptv_dual_tip"

    /** The bubble is still to be seen. The filters' bubble waits, so the two never cover each other. */
    fun pending(): Boolean = Settings.get(TwoStreams.KEY, true) && !Settings.get(KEY, false)

    fun done() {
        if (!Settings.get(KEY, false)) Settings.set(KEY, true)
    }

    fun remove(bar: ViewGroup) {
        bar.findViewWithTag<View>(TAG)?.let { bar.removeView(it) }
    }

    fun hide(bar: ViewGroup) {
        bar.findViewWithTag<View>(TAG)?.visibility = View.GONE
    }

    /** From the pass that places [btn] in [bar]. [open] opens the channel picker. */
    fun place(bar: ViewGroup, btn: View, open: () -> Unit) {
        var tip = bar.findViewWithTag<View>(TAG)
        if (Settings.get(KEY, false)) { if (tip != null) bar.removeView(tip); return }
        val ctx = bar.context
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
        if (tip == null) {
            val box = LinearLayout(ctx)
            tip = box.apply {
                tag = TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = VideoFilters.BubbleDrawable(0xF2241B31.toInt(), FilterIcons.PURPLE, dp(12f).toFloat(),
                    dp(1.5f).toFloat(), dp(9f).toFloat())
                setPadding(dp(12f), dp(9f) + dp(9f), dp(4f), dp(9f))
                elevation = dp(6f).toFloat()
                addView(TextView(ctx).apply {
                    text = I18n.t("New: two streams! Tap here to watch a second live channel side by side, each with its own chat, and keep both playing in picture-in-picture.")
                    setTextColor(Color.WHITE); textSize = 13f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(ctx).apply {
                    text = "✕"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                    contentDescription = I18n.t("Dismiss tip")
                    setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
                    setOnClickListener {
                        done()
                        (box.parent as? ViewGroup)?.removeView(box)
                    }
                })
                setOnClickListener {
                    done()
                    (box.parent as? ViewGroup)?.removeView(box)
                    open()
                }
            }
            bar.addView(tip, ViewGroup.LayoutParams(dp(250f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (tip.visibility != View.VISIBLE) tip.visibility = View.VISIBLE
        move(tip, btn.x, btn.width.toFloat(), btn.y + btn.height)
        tip.bringToFront()
    }

    /** Under the button drawn at [left] with [width]: BarFit may draw it smaller or on a second line. */
    fun moveTip(bar: ViewGroup, left: Float, width: Float, top: Float) {
        val tip = bar.findViewWithTag<View>(TAG)?.takeIf { it.visibility == View.VISIBLE } ?: return
        move(tip, left, width, top)
    }

    private fun move(tip: View, left: Float, width: Float, top: Float) {
        val tw = tip.layoutParams.width.toFloat()
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, tip.resources.displayMetrics)
        val tx = (left + width - tw).coerceAtLeast(margin)
        (tip.background as? VideoFilters.BubbleDrawable)?.let { b ->
            val ax = left + width / 2f - tx
            if (b.arrowX != ax) { b.arrowX = ax; b.invalidateSelf() }
        }
        if (tip.x != tx) tip.x = tx
        if (tip.y != top) tip.y = top
    }
}
