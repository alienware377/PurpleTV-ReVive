package tv.purple.xp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import tv.purple.xp.FiltersPanel.MUTED
import tv.purple.xp.FiltersPanel.PURPLE
import tv.purple.xp.FiltersPanel.TEXT
import tv.purple.xp.FiltersPanel.dp
import tv.purple.xp.FiltersPanel.miniSlider
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The controls under the colour grading filters (colour mixer, curves, calibration, split
 * toning, LUT) and Glow. Each gets the filter's extras box and [changed], which the panel runs
 * after every change; for the grading filters it also turns the filter's slider up to full the
 * first time something is moved, so moving a control always shows its effect.
 */
object GradeControls {

    fun build(ctx: Context, key: String, box: LinearLayout, changed: () -> Unit) {
        when (key) {
            "hsl" -> hsl(ctx, box, changed)
            "curves" -> curves(ctx, box, changed)
            "calib" -> calib(ctx, box, changed)
            "split" -> split(ctx, box, changed)
            "lut" -> lut(ctx, box, changed)
            "glow" -> box.addView(miniSlider(ctx, "Size", 0, 100, FilterDefs.extraInt("glow", "size", 50)) {
                FilterDefs.setExtraInt("glow", "size", it); changed()
            })
        }
    }

    /** A row of round colour buttons; [selected] is ringed in white. */
    private fun dots(ctx: Context, colors: IntArray, selected: Int, names: List<String>, pick: (Int) -> Unit): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((k, c) in colors.withIndex()) {
            row.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    setStroke(dp(ctx, if (k == selected) 3f else 1f), if (k == selected) Color.WHITE else 0x44FFFFFF)
                }
                contentDescription = names[k]
                setOnClickListener { pick(k) }
            }, LinearLayout.LayoutParams(0, dp(ctx, 30f), 1f).apply { setMargins(dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f)) })
        }
        return row
    }

    private fun label(ctx: Context, s: String) = TextView(ctx).apply {
        text = s; setTextColor(TEXT); textSize = 13f; setPadding(0, dp(ctx, 8f), 0, dp(ctx, 2f))
    }

    private fun chip(ctx: Context, s: String, on: Boolean = false, click: () -> Unit) = TextView(ctx).apply {
        text = s; textSize = 12.5f
        setTextColor(if (on) 0xFF140C1F.toInt() else TEXT)
        background = GradientDrawable().apply { setColor(if (on) PURPLE else 0x22FFFFFF); cornerRadius = dp(ctx, 14f).toFloat() }
        setPadding(dp(ctx, 12f), dp(ctx, 6f), dp(ctx, 12f), dp(ctx, 6f))
        setOnClickListener { click() }
    }

    // ---- colour mixer ----

    private fun hsl(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val sliders = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val dotsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var cur = FilterDefs.extraInt("hsl", "pick", 0).coerceIn(0, 7)
        fun show() {
            dotsBox.removeAllViews()
            dotsBox.addView(dots(ctx, FilterDefs.HSL_SWATCHES, cur, FilterDefs.HSL_COLORS) {
                cur = it; FilterDefs.setExtraInt("hsl", "pick", it); show()
            })
            sliders.removeAllViews()
            val name = FilterDefs.HSL_COLORS[cur]
            for ((f, what) in listOf("h" to "Hue", "s" to "Saturation", "l" to "Luminance")) {
                sliders.addView(miniSlider(ctx, "$name ${what.lowercase()}", -100, 100,
                    FilterDefs.extraInt("hsl", "$f$cur", 0)) { FilterDefs.setExtraInt("hsl", "$f$cur", it); changed() })
            }
        }
        show()
        box.addView(dotsBox)
        box.addView(sliders)
    }

    // ---- calibration ----

    private fun calib(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val labels = listOf("Balance", "Red hue", "Red saturation", "Green hue", "Green saturation", "Blue hue", "Blue saturation")
        for ((k, f) in FilterDefs.CALIB_FIELDS.withIndex()) {
            box.addView(miniSlider(ctx, labels[k], -100, 100, FilterDefs.extraInt("calib", f, 0)) {
                FilterDefs.setExtraInt("calib", f, it); changed()
            })
        }
    }

    // ---- split toning ----

    private val TONE_HUES = intArrayOf(0, 30, 52, 95, 180, 230, 270, 305)

    private fun split(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val colors = IntArray(TONE_HUES.size) { Color.HSVToColor(floatArrayOf(TONE_HUES[it].toFloat(), 0.85f, 0.95f)) }
        val names = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
        for ((title, hueKey, satKey) in listOf(Triple("Shadows", "sh", "ss"), Triple("Highlights", "hh", "hs"))) {
            val part = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            fun show() {
                part.removeAllViews()
                part.addView(label(ctx, title))
                val hue = FilterDefs.extraInt("split", hueKey, if (hueKey == "sh") 200 else 35)
                val near = TONE_HUES.indices.minByOrNull { abs(TONE_HUES[it] - hue) } ?: 0
                part.addView(dots(ctx, colors, if (abs(TONE_HUES[near] - hue) <= 2) near else -1, names) {
                    FilterDefs.setExtraInt("split", hueKey, TONE_HUES[it])
                    // A colour picked with no strength yet gets some, so the pick shows.
                    if (FilterDefs.extraInt("split", satKey, 0) == 0) FilterDefs.setExtraInt("split", satKey, 50)
                    show(); changed()
                })
                part.addView(miniSlider(ctx, "Hue", 0, 360, hue, "°") { FilterDefs.setExtraInt("split", hueKey, it); changed() })
                part.addView(miniSlider(ctx, "Saturation", 0, 100, FilterDefs.extraInt("split", satKey, 0)) {
                    FilterDefs.setExtraInt("split", satKey, it); changed()
                })
            }
            show()
            box.addView(part)
        }
        box.addView(miniSlider(ctx, "Balance", -100, 100, FilterDefs.extraInt("split", "bal", 0)) {
            FilterDefs.setExtraInt("split", "bal", it); changed()
        })
    }

    // ---- LUT ----

    private fun lut(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val now = TextView(ctx).apply { setTextColor(TEXT); textSize = 13f; setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f)) }
        fun show() {
            val cur = FilterDefs.extraStr("lut", "file", "")
            now.text = if (cur.isEmpty() || !java.io.File(cur).isFile) "No LUT chosen yet"
                else "Using: " + LutLibrary.name(cur)
            list.removeAllViews()
            for (f in LutLibrary.list(ctx)) {
                list.addView(chip(ctx, LutLibrary.name(f.path), f.path == cur) {
                    FilterDefs.setExtraStr("lut", "file", f.path); show(); changed()
                }.apply {
                    setOnLongClickListener {
                        TBuilder(ctx, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Delete this LUT?")
                            .setMessage(LutLibrary.name(f.path))
                            .setPositiveButton("Delete") { _, _ ->
                                LutLibrary.delete(f.path)
                                if (f.path == cur) FilterDefs.setExtraStr("lut", "file", "")
                                show(); changed()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(ctx, 6f) })
            }
        }
        box.addView(now)
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(list) })
        box.addView(TextView(ctx).apply {
            text = "Add a .cube file"; setTextColor(PURPLE); textSize = 13.5f
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 6f))
            setOnClickListener {
                val act = FiltersPanel.activityOf(ctx) ?: return@setOnClickListener
                LutLibrary.pick(act) { f, why ->
                    if (f == null) { toast(ctx, why ?: "That file could not be read"); return@pick }
                    FilterDefs.setExtraStr("lut", "file", f.path); show(); changed()
                }
            }
        })
        box.addView(TextView(ctx).apply {
            text = "Long-press a LUT to delete it."; setTextColor(MUTED); textSize = 11.5f
        })
        show()
    }

    // ---- curves ----

    private val CH_COLORS = intArrayOf(0xFFEDE7F6.toInt(), 0xFFFF5370.toInt(), 0xFF3DDC84.toInt(), 0xFF4D8DFF.toInt())
    private val CH_NAMES = listOf("All colours", "Red", "Green", "Blue")

    private fun curves(ctx: Context, box: LinearLayout, changed: () -> Unit) {
        val view = CurveView(ctx) { changed() }
        val chRow = LinearLayout(ctx)
        fun showChannels() {
            chRow.removeAllViews()
            chRow.addView(dots(ctx, CH_COLORS, view.channel, CH_NAMES) { view.setChannel(it); showChannels() })
        }
        showChannels()
        chRow.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        box.addView(chRow)
        box.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 220f)))
        box.addView(TextView(ctx).apply {
            text = "Tap the curve to add a point, drag to move it, drag it off the box to remove it."
            setTextColor(MUTED); textSize = 11.5f; setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
        })
        val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in ToneCurve.PRESETS) presets.addView(chip(ctx, p.name) {
            ToneCurve.apply(p); view.reload(); changed()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(ctx, 6f) })
        box.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(presets) })
        box.addView(TextView(ctx).apply {
            text = "Straighten this curve"; setTextColor(PURPLE); textSize = 13f
            setPadding(0, dp(ctx, 8f), 0, dp(ctx, 4f))
            setOnClickListener { view.straighten(); changed() }
        })
    }

    /** The curve editor: all four curves drawn, the chosen one in front with its points. */
    @SuppressLint("ViewConstructor")
    class CurveView(ctx: Context, private val onChange: () -> Unit) : View(ctx) {
        var channel = 0; private set
        private val pts = Array(4) { ToneCurve.parse(ToneCurve.stored(ToneCurve.CHANNELS[it])) }
        private val grid = Paint().apply { color = 0x33FFFFFF; strokeWidth = dp(ctx, 1f).toFloat() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(ctx, 2f).toFloat() }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pad = dp(ctx, 12f).toFloat()
        private var drag = -1

        fun setChannel(c: Int) { channel = c; invalidate() }
        fun reload() { for (k in 0..3) pts[k] = ToneCurve.parse(ToneCurve.stored(ToneCurve.CHANNELS[k])); invalidate() }
        fun straighten() { pts[channel] = ToneCurve.parse(ToneCurve.LINEAR); save(); invalidate() }

        private fun save() = ToneCurve.store(ToneCurve.CHANNELS[channel], pts[channel])
        private fun sx(x: Float) = pad + (width - 2 * pad) * x / 255f
        private fun sy(y: Float) = height - pad - (height - 2 * pad) * y / 255f
        private fun vx(px: Float) = ((px - pad) / (width - 2 * pad) * 255f)
        private fun vy(py: Float) = ((height - pad - py) / (height - 2 * pad) * 255f)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(0xFF140C1F.toInt())
            for (k in 0..4) {
                val x = pad + (w - 2 * pad) * k / 4f; val y = pad + (h - 2 * pad) * k / 4f
                c.drawLine(x, pad, x, h - pad, grid); c.drawLine(pad, y, w - pad, y, grid)
            }
            for (k in listOf(0, 1, 2, 3).sortedBy { if (it == channel) 1 else 0 }) {
                val t = ToneCurve.table(pts[k])
                val path = Path()
                for (i in 0..255) {
                    val x = sx(i.toFloat()); val y = sy(t[i])
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                line.color = CH_COLORS[k]; line.alpha = if (k == channel) 255 else 70
                c.drawPath(path, line)
            }
            dot.color = CH_COLORS[channel]
            for (p in pts[channel]) c.drawCircle(sx(p.x), sy(p.y), dp(context, 6f).toFloat(), dot)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val list = pts[channel]
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The panel scrolls; a drag here moves a point instead.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val near = list.indices.minByOrNull { hypot(sx(list[it].x) - e.x, sy(list[it].y) - e.y) }
                    drag = if (near != null && hypot(sx(list[near].x) - e.x, sy(list[near].y) - e.y) < dp(context, 24f)) near
                    else {
                        val x = vx(e.x).coerceIn(1f, 254f)
                        if (list.any { abs(it.x - x) < 4f }) -1
                        else {
                            val at = list.indexOfFirst { it.x > x }.let { if (it < 0) list.size else it }
                            list.add(at, ToneCurve.Point(x, vy(e.y).coerceIn(0f, 255f))); at
                        }
                    }
                    if (drag >= 0) { save(); invalidate(); onChange() }
                }
                MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                    val p = list[drag]
                    val end = drag == 0 || drag == list.size - 1
                    // Dragged well off the box: a middle point goes away.
                    val off = e.y < -dp(context, 30f) || e.y > height + dp(context, 30f) || e.x < -dp(context, 30f) || e.x > width + dp(context, 30f)
                    if (off && !end) { list.removeAt(drag); drag = -1 }
                    else {
                        if (!end) p.x = vx(e.x).coerceIn(list[drag - 1].x + 2f, list[drag + 1].x - 2f)
                        p.y = vy(e.y).coerceIn(0f, 255f)
                    }
                    save(); invalidate(); onChange()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; parent?.requestDisallowInterceptTouchEvent(false) }
            }
            return true
        }
    }
}
