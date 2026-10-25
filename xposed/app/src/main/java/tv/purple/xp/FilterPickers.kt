package tv.purple.xp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import tv.purple.xp.FiltersPanel.MUTED
import tv.purple.xp.FiltersPanel.PURPLE
import tv.purple.xp.FiltersPanel.TEXT
import tv.purple.xp.FiltersPanel.dp

/** A friendly colour picker: preset swatches plus hue, colourfulness and brightness bars. */
object ColorPicker {

    private val SWATCHES = intArrayOf(
        0xFFA970FF.toInt(), 0xFFFF8FD8.toInt(), 0xFFFF4D6D.toInt(), 0xFFFF8A3D.toInt(), 0xFFFFD24D.toInt(),
        0xFF7BE35A.toInt(), 0xFF35D6B0.toInt(), 0xFF4DB8FF.toInt(), 0xFF3D5AFE.toInt(), 0xFF8A6BFF.toInt(),
        0xFFFFFFFF.toInt(), 0xFFB0B0B0.toInt(), 0xFF5A5A5A.toInt(), 0xFF000000.toInt(), 0xFF7A4A2A.toInt()
    )

    fun show(ctx: Context, initial: Int, picked: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        val preview = View(ctx)
        val hex = TextView(ctx).apply { setTextColor(MUTED); textSize = 13f }
        lateinit var bars: List<Bar>
        fun cur() = Color.HSVToColor(hsv)
        fun sync(fromBar: Boolean) {
            preview.background = GradientDrawable().apply { setColor(cur()); cornerRadius = dp(ctx, 12f).toFloat() }
            hex.text = String.format("#%06X", 0xFFFFFF and cur())
            bars.forEach { it.refresh(hsv) }
            if (fromBar) picked(cur())
        }
        bars = listOf(Bar(ctx, 0, hsv) { sync(true) }, Bar(ctx, 1, hsv) { sync(true) }, Bar(ctx, 2, hsv) { sync(true) })
        val grid = GridLayout(ctx).apply { columnCount = 5 }
        for (c in SWATCHES) grid.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(ctx, 1f), 0x66FFFFFF) }
            setOnClickListener { Color.colorToHSV(c, hsv); sync(true) }
        }, GridLayout.LayoutParams().apply {
            width = dp(ctx, 36f); height = dp(ctx, 36f); setMargins(dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f))
        })
        val content = ScrollView(ctx).apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(ctx, 10f), 0, 0)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(preview, LinearLayout.LayoutParams(dp(ctx, 56f), dp(ctx, 40f)).apply { rightMargin = dp(ctx, 12f) })
                    addView(hex)
                })
                addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(ctx, 8f) })
                for ((k, b) in bars.withIndex()) {
                    addView(TextView(ctx).apply {
                        text = arrayOf("Colour", "Colourfulness", "Brightness")[k]; setTextColor(MUTED); textSize = 12f
                        setPadding(0, dp(ctx, 10f), 0, dp(ctx, 4f))
                    })
                    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 30f)))
                }
            })
        }
        val d = FiltersPanel.sheet(ctx, "Pick a colour", content, 360f)
        sync(false)
        d.show()
    }

    /** A gradient bar with a round handle: 0 hue, 1 saturation, 2 value. */
    @SuppressLint("ViewConstructor")
    private class Bar(ctx: Context, val which: Int, val hsv: FloatArray, val moved: () -> Unit) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(ctx, 3f).toFloat() }
        private val r = dp(ctx, 11f).toFloat()
        fun refresh(h: FloatArray) { invalidate() }
        private fun frac() = when (which) { 0 -> hsv[0] / 360f; 1 -> hsv[1]; else -> hsv[2] }
        override fun onDraw(c: Canvas) {
            val cy = height / 2f
            val rect = RectF(r, cy - dp(context, 6f), width - r, cy + dp(context, 6f))
            val cols = when (which) {
                0 -> IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
                1 -> intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
                else -> intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            }
            p.shader = LinearGradient(rect.left, 0f, rect.right, 0f, cols, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, p)
            val x = rect.left + rect.width() * frac()
            p.shader = null; p.color = Color.HSVToColor(hsv)
            c.drawCircle(x, cy, r, p); c.drawCircle(x, cy, r, ring)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val f = ((e.x - r) / (width - 2 * r)).coerceIn(0f, 1f)
            when (which) { 0 -> hsv[0] = f * 359.9f; 1 -> hsv[1] = f; else -> hsv[2] = f }
            invalidate(); moved()
            return true
        }
    }
}

/** Choose the Overlay layer: a built-in texture, one of your pictures, or a solid colour. */
object TexturePicker {

    /** [picked] gets the source and, for built-ins, its suggested settings. */
    fun show(ctx: Context, picked: (String, TextureLibrary.Tex?) -> Unit) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val content = ScrollView(ctx).apply { addView(col) }
        val d = FiltersPanel.sheet(ctx, "Choose an overlay", content, 460f)
        val cell = dp(ctx, 92f)

        fun header(t: String) = TextView(ctx).apply {
            text = t.uppercase(); setTextColor(MUTED); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f; setPadding(dp(ctx, 2f), dp(ctx, 14f), 0, dp(ctx, 6f))
        }
        fun grid() = GridLayout(ctx).apply { columnCount = 4 }
        fun tile(label: String, setup: (ImageView) -> Unit, click: () -> Unit, long: (() -> Unit)? = null) =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val img = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { setColor(0xFF120C1A.toInt()); cornerRadius = dp(ctx, 10f).toFloat() }
                    clipToOutline = true
                }
                setup(img)
                addView(img, LinearLayout.LayoutParams(cell - dp(ctx, 10f), cell - dp(ctx, 10f)))
                addView(TextView(ctx).apply {
                    text = label; setTextColor(TEXT); textSize = 11f; gravity = Gravity.CENTER; maxLines = 2
                }, LinearLayout.LayoutParams(cell - dp(ctx, 6f), ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener { click() }
                if (long != null) setOnLongClickListener { long(); true }
            }
        fun lp() = GridLayout.LayoutParams().apply { setMargins(dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 3f), dp(ctx, 6f)) }

        // Your pictures first, with the add button.
        col.addView(header("Your pictures"))
        val mine = grid()
        fun fillMine() {
            mine.removeAllViews()
            mine.addView(tile("Add from device", { it.setImageDrawable(PlusIcon()) }, {
                val act = FiltersPanel.activityOf(ctx) ?: return@tile
                TextureLibrary.pickFromDevice(act) { src -> d.dismiss(); picked(src, null) }
            }), lp())
            for (src in TextureLibrary.userImages(ctx)) {
                mine.addView(tile("Your picture", { iv -> FiltersPanel.loadThumb(src) { iv.setImageBitmap(it) } },
                    { d.dismiss(); picked(src, null) },
                    { TextureLibrary.delete(src); fillMine() }), lp())
            }
        }
        fillMine()
        col.addView(mine)
        col.addView(TextView(ctx).apply {
            text = "Long-press one of your pictures to remove it."; setTextColor(MUTED); textSize = 11f
        })

        col.addView(header("Solid colour"))
        col.addView(grid().apply {
            addView(tile("Pick a colour", { it.setImageDrawable(SwatchIcon()) }, {
                ColorPicker.show(ctx, FilterDefs.extraInt("ovl", "lastcolor", 0xFFA970FF.toInt())) { c ->
                    FilterDefs.setExtraInt("ovl", "lastcolor", c)
                    picked("color:$c", null)
                }
            }), lp())
        })

        for ((cat, items) in TextureLibrary.builtIns().groupBy { it.category }) {
            col.addView(header(cat))
            val g = grid()
            for (t in items) {
                val src = "tex:" + t.file
                g.addView(tile(t.name, { iv -> FiltersPanel.loadThumb(src) { iv.setImageBitmap(it) } },
                    { d.dismiss(); picked(src, t) }), lp())
            }
            col.addView(g)
        }
        if (TextureLibrary.builtIns().isEmpty()) col.addView(TextView(ctx).apply {
            text = "No built-in textures in this build."; setTextColor(MUTED); textSize = 12f
        })
        d.show()
    }

    private class PlusIcon : android.graphics.drawable.Drawable() {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PURPLE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND }
        override fun draw(c: Canvas) {
            val b = bounds; val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = b.width() * 0.18f
            p.strokeWidth = b.width() * 0.07f
            c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class SwatchIcon : android.graphics.drawable.Drawable() {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            p.shader = android.graphics.SweepGradient(b.exactCenterX(), b.exactCenterY(),
                IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.8f, 1f)) }, null)
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), b.width() * 0.28f, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

/** Photoshop's blend mode list, grouped the same way, each with a one-line explanation. */
object BlendList {
    fun show(ctx: Context, current: Int, picked: (Int) -> Unit) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val d = FiltersPanel.sheet(ctx, "Blend mode", ScrollView(ctx).apply { addView(col) }, 380f)
        FilterDefs.BLEND_MODES.forEachIndexed { k, name ->
            if (k in FilterDefs.BLEND_GROUP_STARTS) col.addView(View(ctx).apply { setBackgroundColor(0x33FFFFFF) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).apply {
                    topMargin = dp(ctx, 6f); bottomMargin = dp(ctx, 6f)
                })
            col.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 10f), dp(ctx, 7f), dp(ctx, 10f), dp(ctx, 7f))
                if (k == current) background = GradientDrawable().apply { setColor(0x33A970FF); cornerRadius = dp(ctx, 10f).toFloat() }
                addView(TextView(ctx).apply {
                    text = name; setTextColor(if (k == current) PURPLE else TEXT); textSize = 15f
                    if (k == current) typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(ctx).apply { text = FilterDefs.BLEND_HELP[name] ?: ""; setTextColor(MUTED); textSize = 12f })
                setOnClickListener { d.dismiss(); picked(k) }
            })
        }
        d.show()
    }
}
