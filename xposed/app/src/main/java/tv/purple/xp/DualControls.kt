package tv.purple.xp

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The controls over one two-streams player: play and pause in the middle, a scrollable row of
 * buttons on top. A tap on the video shows them, and they go away by themselves. What each button
 * does is decided by the screen, which hands it to the same feature the normal player uses.
 */
@SuppressLint("ViewConstructor")
internal class DualControls(ctx: Context, private val onControl: (String, View) -> Unit) : FrameLayout(ctx) {

    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { showing(false) }
    private val playIcon = Ctl.PlayPause()

    private val play = ImageView(ctx).apply {
        setImageDrawable(playIcon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
        contentDescription = I18n.t("Pause")
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
        isClickable = true
        setOnClickListener { fire("play", it) }
    }

    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    private val bar = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0))
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val stats = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 10.5f
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply { setColor(0xCC0E0A14.toInt()); cornerRadius = dp(8f).toFloat() }
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        visibility = View.GONE
        isClickable = true
        setOnClickListener { fire("stats", it) }
    }

    init {
        visibility = View.GONE
        // Above the caption box, which lives in the same player.
        translationZ = dp(12f).toFloat()
        for ((key, icon, label) in listOf(
            Triple("captions", Ctl.Cc() as Drawable, "Captions"),
            Triple("quality", DualIcons.Gear(), "Quality"),
            Triple("filters", FilterIcons.Tune(), "Video and audio filters"),
            Triple("stats", Ctl.Bars(), "Stream stats"),
            Triple("refresh", Ctl.Reload(), "Refresh"),
            Triple("clip", Ctl.Clip(), "Clip and save"),
            Triple("share", Ctl.Share(), "Share"),
            Triple("sleep", FilterIcons.Moon(), "Sleep timer"),
        )) row.addView(button(key, icon, label), LinearLayout.LayoutParams(dp(38f), dp(38f)))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        addView(play, LayoutParams(dp(52f), dp(52f), Gravity.CENTER))
        addView(stats, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(6f); bottomMargin = dp(6f)
        })
    }

    private fun button(key: String, icon: Drawable, label: String) = ImageView(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        contentDescription = I18n.t(label)
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null)
        setOnClickListener { fire(key, it) }
    }

    private fun fire(key: String, v: View) {
        keepUp()
        onControl(key, v)
    }

    private fun keepUp() {
        main.removeCallbacks(hide)
        if (!playIcon.paused) main.postDelayed(hide, HIDE_MS)
    }

    /** The full controls are up; the stats box can stay on its own without them. */
    private var full = false

    fun toggle() = showing(!full)

    fun showing(on: Boolean) {
        main.removeCallbacks(hide)
        full = on
        parts()
        if (on) keepUp()
    }

    private fun parts() {
        val v = if (full) View.VISIBLE else View.INVISIBLE
        bar.visibility = v
        play.visibility = v
        visibility = if (full || stats.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    fun setPaused(paused: Boolean) {
        if (playIcon.paused != paused) {
            playIcon.paused = paused
            play.contentDescription = I18n.t(if (paused) "Play" else "Pause")
        }
        if (paused) showing(true) else if (full) keepUp()
    }

    /** The stats box; it stays while the controls fade, so it can be read. */
    fun setStats(text: String?) {
        stats.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null && stats.text?.toString() != text) stats.text = text
        parts()
    }


    /** Whether ([x], [y]) in this view's coordinates is on a button. */
    fun onButton(x: Float, y: Float): Boolean {
        if (visibility != View.VISIBLE) return false
        if (play.visibility == View.VISIBLE && x >= play.left && x < play.right && y >= play.top && y < play.bottom) return true
        if (stats.visibility == View.VISIBLE && x >= stats.left && x < stats.right && y >= stats.top && y < stats.bottom) return true
        return bar.visibility == View.VISIBLE && y < bar.bottom
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(hide)
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private companion object { const val HIDE_MS = 4000L }
}

/** The controls' icons, drawn in code like the other player buttons. */
internal object Ctl {

    class PlayPause : FilterIcons.Icon() {
        var paused = false
            set(v) { if (field != v) { field = v; invalidateSelf() } }

        override fun paint(c: Canvas, s: Float) {
            if (paused) {
                c.drawPath(Path().apply {
                    moveTo(s * 0.30f, s * 0.18f); lineTo(s * 0.82f, s * 0.5f); lineTo(s * 0.30f, s * 0.82f); close()
                }, fill)
            } else {
                val r = s * 0.03f
                c.drawRoundRect(RectF(s * 0.26f, s * 0.18f, s * 0.42f, s * 0.82f), r, r, fill)
                c.drawRoundRect(RectF(s * 0.58f, s * 0.18f, s * 0.74f, s * 0.82f), r, r, fill)
            }
        }
    }

    class Cc : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawRoundRect(RectF(s * 0.12f, s * 0.24f, s * 0.88f, s * 0.76f), s * 0.08f, s * 0.08f, stroke)
            c.drawArc(RectF(s * 0.24f, s * 0.38f, s * 0.46f, s * 0.62f), 45f, 270f, false, stroke)
            c.drawArc(RectF(s * 0.54f, s * 0.38f, s * 0.76f, s * 0.62f), 45f, 270f, false, stroke)
        }
    }

    class Bars : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawLine(s * 0.25f, s * 0.8f, s * 0.25f, s * 0.52f, stroke)
            c.drawLine(s * 0.5f, s * 0.8f, s * 0.5f, s * 0.22f, stroke)
            c.drawLine(s * 0.75f, s * 0.8f, s * 0.75f, s * 0.4f, stroke)
        }
    }

    class Reload : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawArc(RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f), -60f, 300f, false, stroke)
            c.drawPath(Path().apply {
                moveTo(s * 0.66f, s * 0.12f); lineTo(s * 0.67f, s * 0.31f); lineTo(s * 0.86f, s * 0.28f)
            }, stroke)
        }
    }

    class Clip : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawRoundRect(RectF(s * 0.16f, s * 0.36f, s * 0.84f, s * 0.84f), s * 0.06f, s * 0.06f, stroke)
            c.drawLine(s * 0.16f, s * 0.36f, s * 0.8f, s * 0.16f, stroke)
            c.drawLine(s * 0.36f, s * 0.3f, s * 0.3f, s * 0.2f, stroke)
            c.drawLine(s * 0.58f, s * 0.23f, s * 0.52f, s * 0.13f, stroke)
        }
    }

    class Share : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawLine(s * 0.5f, s * 0.14f, s * 0.5f, s * 0.6f, stroke)
            c.drawLine(s * 0.33f, s * 0.3f, s * 0.5f, s * 0.14f, stroke)
            c.drawLine(s * 0.67f, s * 0.3f, s * 0.5f, s * 0.14f, stroke)
            c.drawPath(Path().apply {
                moveTo(s * 0.22f, s * 0.5f); lineTo(s * 0.22f, s * 0.84f); lineTo(s * 0.78f, s * 0.84f); lineTo(s * 0.78f, s * 0.5f)
            }, stroke)
        }
    }
}
