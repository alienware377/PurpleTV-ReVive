package tv.purple.xp

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs

/**
 * The video filters panel, opened from the player's filters button.
 *
 * It docks to the side in landscape and to the bottom in portrait, and barely dims the video, so
 * every change is seen live on the stream behind it. Each filter has an icon, a name, a centred
 * slider with its value, a "?" that explains it in plain words, and an optional tone range drawn
 * as a shadows-to-highlights bar with two handles.
 */
object FiltersPanel {

    private const val BG = 0xF2181221.toInt()
    private const val CARD = 0xFF241B31.toInt()
    private const val TEXT = 0xFFF1ECFF.toInt()
    private const val MUTED = 0xFFA79FC0.toInt()
    private const val PURPLE = FilterIcons.PURPLE

    @SuppressLint("SetTextI18n")
    fun show(anchor: View) {
        val ctx = anchor.context
        val d = Dialog(ctx)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val rows = ArrayList<Row>()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(ctx, 22f).toFloat() }
            setPadding(dp(ctx, 18f), dp(ctx, 16f), dp(ctx, 18f), dp(ctx, 14f))
        }

        // Header: icon, title, master switch.
        val master = Switch(ctx).apply {
            isChecked = Settings.get(VideoFilters.KEY_ON, true)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(PURPLE, 0xFFCCCCCC.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x88A970FF.toInt(), 0x55FFFFFF))
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(ctx).apply { setImageDrawable(FilterIcons.Tune().apply { color = PURPLE }) },
                LinearLayout.LayoutParams(dp(ctx, 26f), dp(ctx, 26f)).apply { rightMargin = dp(ctx, 10f) })
            addView(TextView(ctx).apply {
                text = "Video filters"; setTextColor(TEXT); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(master)
        })
        root.addView(TextView(ctx).apply {
            text = "Only changes how the stream looks on your screen. Works on lives and VODs."
            setTextColor(MUTED); textSize = 12.5f
            setPadding(0, dp(ctx, 4f), 0, dp(ctx, 10f))
        })

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (i in VideoFilters.FILTERS.indices) {
            val r = Row(ctx, i) { VideoFilters.refresh(anchor) }
            rows.add(r); list.addView(r.view)
        }
        root.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (!VideoFilters.shaderSupported()) {
            root.addView(TextView(ctx).apply {
                text = "Clarity, Texture, Vibrance and tone ranges need Android 13 or newer."
                setTextColor(MUTED); textSize = 12f; setPadding(0, dp(ctx, 8f), 0, 0)
            })
        }

        // Footer: reset all, done.
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 12f), 0, 0)
            addView(TextView(ctx).apply {
                text = "Reset all"; setTextColor(PURPLE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
                setOnClickListener {
                    VideoFilters.resetAll(); rows.forEach { it.load() }; VideoFilters.refresh(anchor)
                }
            })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(ctx).apply {
                text = "Done"; setTextColor(0xFF140C1F.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(PURPLE); cornerRadius = dp(ctx, 20f).toFloat() }
                setPadding(dp(ctx, 26f), dp(ctx, 10f), dp(ctx, 26f), dp(ctx, 10f))
                setOnClickListener { d.dismiss() }
            })
        })

        fun syncEnabled() { val on = master.isChecked; rows.forEach { it.setEnabled(on) } }
        master.setOnCheckedChangeListener { _, v ->
            Settings.set(VideoFilters.KEY_ON, v); syncEnabled(); VideoFilters.refresh(anchor)
        }
        syncEnabled()

        d.setContentView(root)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.12f)
            val dm = ctx.resources.displayMetrics
            val lp = w.attributes
            if (landscape) {
                lp.width = dp(ctx, 400f).coerceAtMost((dm.widthPixels * 0.45f).toInt())
                lp.height = (dm.heightPixels * 0.9f).toInt()
                lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                lp.x = dp(ctx, 12f)
            } else {
                lp.width = (dm.widthPixels - dp(ctx, 16f)).coerceAtMost(dp(ctx, 560f))
                lp.height = (dm.heightPixels * 0.55f).toInt()
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = dp(ctx, 8f)
            }
            w.attributes = lp
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.show()
    }

    /** One filter: header line, slider, and the collapsible tone range. */
    private class Row(val ctx: Context, val i: Int, val changed: () -> Unit) {
        val f = VideoFilters.FILTERS[i]
        val usable = VideoFilters.usable(i)
        val value = TextView(ctx).apply { setTextColor(MUTED); textSize = 13f; gravity = Gravity.END }
        val seek = SeekBar(ctx).apply {
            max = 200
            progressTintList = ColorStateList.valueOf(PURPLE)
            thumbTintList = ColorStateList.valueOf(PURPLE)
            progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        }
        val rangeLabel = TextView(ctx).apply { setTextColor(MUTED); textSize = 12.5f }
        val range = RangeBar(ctx)
        val rangeBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val rangeToggle = TextView(ctx).apply {
            setTextColor(PURPLE); textSize = 12.5f
            setPadding(0, dp(ctx, 2f), 0, dp(ctx, 2f))
        }
        val view: View

        fun syncToggle() {
            rangeToggle.text = (if (rangeBox.visibility == View.VISIBLE) "Hide tone range" else "Tone range") +
                "  ·  " + rangeText(VideoFilters.lo(i), VideoFilters.hi(i))
        }

        init {
            val icon = FilterIcons.forFilter(i)
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply { setImageDrawable(icon) },
                    LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)).apply { rightMargin = dp(ctx, 10f) })
                addView(TextView(ctx).apply {
                    text = f.name; setTextColor(TEXT); textSize = 15.5f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // Tap the number to put this filter back to 0.
                value.setOnClickListener { if (usable) { VideoFilters.setAmount(i, 0); load(); changed() } }
                addView(value, LinearLayout.LayoutParams(dp(ctx, 52f), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(helpChip(ctx, f.name, f.help), LinearLayout.LayoutParams(dp(ctx, 26f), dp(ctx, 26f))
                    .apply { leftMargin = dp(ctx, 8f) })
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    // A soft snap at zero, so "off" is easy to land on.
                    val v = (p - 100).let { if (abs(it) <= 3) 0 else it }
                    VideoFilters.setAmount(i, v); showValue(v); changed()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { load() }
            })
            rangeToggle.setOnClickListener {
                rangeBox.visibility = if (rangeBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                syncToggle()
            }
            range.onChange = { l, h ->
                VideoFilters.setRange(i, l, h); rangeLabel.text = "Affects: " + rangeText(l, h); syncToggle(); changed()
            }
            rangeBox.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply { text = "Shadows"; setTextColor(MUTED); textSize = 11f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(rangeLabel)
                addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                addView(TextView(ctx).apply { text = "Highlights"; setTextColor(MUTED); textSize = 11f })
                addView(helpChip(ctx, "Tone range", VideoFilters.RANGE_HELP),
                    LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)).apply { leftMargin = dp(ctx, 8f) })
            })
            rangeBox.addView(range, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 34f)))

            view = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(ctx, 14f).toFloat() }
                setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 8f))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8f) }
                addView(head)
                addView(seek)
                if (usable && VideoFilters.shaderSupported()) { addView(rangeToggle); addView(rangeBox) }
                if (!usable) addView(TextView(ctx).apply {
                    text = "Needs Android 13 or newer"; setTextColor(MUTED); textSize = 12f
                })
            }
            load()
        }

        fun load() {
            val v = VideoFilters.amount(i)
            seek.progress = v + 100
            showValue(v)
            range.set(VideoFilters.lo(i), VideoFilters.hi(i))
            rangeLabel.text = "Affects: " + rangeText(VideoFilters.lo(i), VideoFilters.hi(i))
            syncToggle()
        }

        fun showValue(v: Int) {
            value.text = if (v > 0) "+$v" else "$v"
            value.setTextColor(if (v == 0) MUTED else PURPLE)
        }

        fun setEnabled(on: Boolean) {
            val e = on && usable
            seek.isEnabled = e; range.isEnabled = e
            view.alpha = if (e) 1f else 0.45f
        }
    }

    /** Words for a tone range, so the numbers mean something. */
    private fun rangeText(lo: Int, hi: Int): String {
        if (lo <= 2 && hi >= 98) return "All tones"
        fun zone(v: Int) = when { v < 34 -> "shadows"; v < 67 -> "midtones"; else -> "highlights" }
        val a = zone(lo); val b = zone(hi.coerceAtLeast(lo))
        return if (a == b) a.replaceFirstChar { it.uppercase() } else "${a.replaceFirstChar { it.uppercase() }} to $b"
    }

    /** A round "?" that opens a short explanation bubble next to it. */
    private fun helpChip(ctx: Context, title: String, body: String): View = TextView(ctx).apply {
        text = "?"; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0x33FFFFFF); setStroke(dp(ctx, 1f), 0x55FFFFFF)
        }
        contentDescription = "What is $title?"
        setOnClickListener { v -> tooltip(v, title, body) }
    }

    private fun tooltip(anchor: View, title: String, body: String) {
        val ctx = anchor.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF33264A.toInt()); cornerRadius = dp(ctx, 12f).toFloat(); setStroke(dp(ctx, 1f), 0x66A970FF)
            }
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            addView(TextView(ctx).apply { text = title; setTextColor(TEXT); textSize = 14f; typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(ctx).apply {
                text = body; setTextColor(0xFFDDD5F0.toInt()); textSize = 13f
                setPadding(0, dp(ctx, 4f), 0, 0)
            })
        }
        val w = dp(ctx, 260f)
        val pw = PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(ctx, 8f).toFloat()
        }
        box.setOnClickListener { pw.dismiss() }
        pw.showAsDropDown(anchor, -w + anchor.width, dp(ctx, 4f))
    }

    /**
     * Shadows-to-highlights bar with two handles. The chosen span is shown at full strength and
     * the rest of the bar is dimmed, so it reads as "this filter works here".
     */
    private class RangeBar(ctx: Context) : View(ctx) {
        var onChange: ((Int, Int) -> Unit)? = null
        private var lo = 0
        private var hi = 100
        private var dragging = -1
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0181221.toInt() }
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = PURPLE; strokeWidth = dp(ctx, 3f).toFloat()
        }
        private val r = dp(ctx, 9f).toFloat()

        fun set(l: Int, h: Int) { lo = l; hi = h; invalidate() }

        private fun xOf(v: Int) = r + (width - 2 * r) * v / 100f
        private fun vOf(x: Float) = (((x - r) / (width - 2 * r)) * 100f).toInt().coerceIn(0, 100)

        override fun onDraw(c: Canvas) {
            val cy = height / 2f
            val th = dp(context, 10f).toFloat()
            val track = RectF(r, cy - th / 2, width - r, cy + th / 2)
            trackPaint.shader = LinearGradient(track.left, 0f, track.right, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
            c.drawRoundRect(track, th / 2, th / 2, trackPaint)
            if (lo > 0) c.drawRect(track.left, track.top, xOf(lo), track.bottom, dimPaint)
            if (hi < 100) c.drawRect(xOf(hi), track.top, track.right, track.bottom, dimPaint)
            for (v in intArrayOf(lo, hi)) {
                c.drawCircle(xOf(v), cy, r, thumb)
                c.drawCircle(xOf(v), cy, r, ring)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    dragging = if (abs(e.x - xOf(lo)) <= abs(e.x - xOf(hi))) 0 else 1
                    move(e.x); return true
                }
                MotionEvent.ACTION_MOVE -> { move(e.x); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = -1; return true }
            }
            return false
        }

        private fun move(x: Float) {
            val v = vOf(x)
            if (dragging == 0) lo = v.coerceAtMost(hi - 5) else hi = v.coerceAtLeast(lo + 5)
            invalidate()
            onChange?.invoke(lo, hi)
        }
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
