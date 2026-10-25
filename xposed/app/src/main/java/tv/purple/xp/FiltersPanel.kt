package tv.purple.xp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.HorizontalScrollView
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
 * every change is seen live on the stream behind it. Filters are grouped (Light, Detail, Colour,
 * Style, Effects); each has an icon, a name, a slider with its value, a "?" that explains it in
 * plain words, an optional tone range drawn as a shadows-to-highlights bar, and any extra
 * controls it needs (colours, gradients, textures, blend modes).
 */
object FiltersPanel {

    const val BG = 0xF2181221.toInt()
    const val CARD = 0xFF241B31.toInt()
    const val TEXT = 0xFFF1ECFF.toInt()
    const val MUTED = 0xFFA79FC0.toInt()
    const val PURPLE = FilterIcons.PURPLE

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetTextI18n")
    fun show(anchor: View) {
        val ctx = anchor.context
        val d = Dialog(ctx)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val changed = { VideoFilters.refresh(anchor) }

        val rows = ArrayList<Row>()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(ctx, 22f).toFloat() }
            setPadding(dp(ctx, 18f), dp(ctx, 16f), dp(ctx, 18f), dp(ctx, 14f))
        }

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
            setPadding(0, dp(ctx, 4f), 0, dp(ctx, 8f))
        })

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (g in FilterDefs.Group.values()) {
            val members = FilterDefs.FILTERS.indices.filter { FilterDefs.FILTERS[it].group == g }
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val openKey = "vf_ui_open_${g.name}"
            val defOpen = g == FilterDefs.Group.LIGHT || g == FilterDefs.Group.COLOR
            val chevron = TextView(ctx).apply { setTextColor(MUTED); textSize = 14f }
            val count = TextView(ctx).apply { setTextColor(PURPLE); textSize = 12.5f }
            fun syncHead() {
                val open = Settings.get(openKey, defOpen)
                body.visibility = if (open) View.VISIBLE else View.GONE
                chevron.text = if (open) "▴" else "▾"
                val n = members.count { FilterDefs.amount(it) != 0 }
                count.text = if (n > 0) "$n on" else ""
            }
            list.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 6f))
                addView(TextView(ctx).apply {
                    text = g.title.uppercase(); setTextColor(MUTED); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(ctx, 10f) })
                addView(chevron)
                setOnClickListener { Settings.set(openKey, !Settings.get(openKey, defOpen)); syncHead() }
            })
            for (i in members) {
                val r = Row(ctx, i) { changed(); syncHead() }
                rows.add(r); body.addView(r.view)
            }
            list.addView(body)
            syncHead()
        }
        root.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (!VideoFilters.fullSupport()) {
            root.addView(TextView(ctx).apply {
                text = "Some filters need Android 13 or newer and are greyed out on this device."
                setTextColor(MUTED); textSize = 12f; setPadding(0, dp(ctx, 8f), 0, 0)
            })
        }

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 12f), 0, 0)
            addView(TextView(ctx).apply {
                text = "Reset all"; setTextColor(PURPLE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
                setOnClickListener { FilterDefs.resetAll(); rows.forEach { it.load() }; changed() }
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
            Settings.set(VideoFilters.KEY_ON, v); syncEnabled(); changed()
        }
        syncEnabled()

        d.setContentView(root)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.12f)
            val dm = ctx.resources.displayMetrics
            val lp = w.attributes
            if (landscape) {
                lp.width = dp(ctx, 420f).coerceAtMost((dm.widthPixels * 0.46f).toInt())
                lp.height = (dm.heightPixels * 0.92f).toInt()
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

    /** One filter: header line, slider, tone range, and any extra controls. */
    private class Row(val ctx: Context, val i: Int, val changed: () -> Unit) {
        val f = FilterDefs.FILTERS[i]
        val usable = VideoFilters.usable(i)
        val value = TextView(ctx).apply { setTextColor(MUTED); textSize = 13f; gravity = Gravity.END }
        val icon = FilterIcons.forKey(f.key)
        val seek = slider(ctx)
        val rangeLabel = TextView(ctx).apply { setTextColor(MUTED); textSize = 12.5f }
        val range = RangeBar(ctx)
        val rangeBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val rangeToggle = TextView(ctx).apply {
            setTextColor(PURPLE); textSize = 12.5f
            setPadding(0, dp(ctx, 2f), 0, dp(ctx, 2f))
        }
        val extras = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val view: View

        fun syncToggle() {
            rangeToggle.text = (if (rangeBox.visibility == View.VISIBLE) "Hide tone range" else "Tone range") +
                "  ·  " + rangeText(FilterDefs.lo(i), FilterDefs.hi(i))
        }

        val onExtra = { icon.invalidateSelf(); changed() }

        init {
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply { setImageDrawable(icon) },
                    LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)).apply { rightMargin = dp(ctx, 10f) })
                addView(TextView(ctx).apply {
                    text = f.name; setTextColor(TEXT); textSize = 15.5f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // Tap the number to put this filter back to 0.
                value.setOnClickListener { if (usable) { FilterDefs.setAmount(i, 0); load(); changed() } }
                addView(value, LinearLayout.LayoutParams(dp(ctx, 52f), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(helpChip(ctx, f.name, f.help), LinearLayout.LayoutParams(dp(ctx, 26f), dp(ctx, 26f))
                    .apply { leftMargin = dp(ctx, 8f) })
            }
            seek.max = if (f.bipolar) 200 else 100
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    // A soft snap at zero, so "off" is easy to land on.
                    val raw = if (f.bipolar) p - 100 else p
                    val v = if (abs(raw) <= 3) 0 else raw
                    FilterDefs.setAmount(i, v); showValue(v); changed()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { load() }
            })
            rangeToggle.setOnClickListener {
                rangeBox.visibility = if (rangeBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                syncToggle()
            }
            range.onChange = { l, h ->
                FilterDefs.setRange(i, l, h); rangeLabel.text = "Affects: " + rangeText(l, h); syncToggle(); changed()
            }
            rangeBox.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply { text = "Shadows"; setTextColor(MUTED); textSize = 11f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(rangeLabel)
                addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                addView(TextView(ctx).apply { text = "Highlights"; setTextColor(MUTED); textSize = 11f })
                addView(helpChip(ctx, "Tone range", FilterDefs.RANGE_HELP),
                    LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)).apply { leftMargin = dp(ctx, 8f) })
            })
            rangeBox.addView(range, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 34f)))

            if (usable) buildExtras()

            view = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(ctx, 14f).toFloat() }
                setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 8f))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8f) }
                addView(head)
                addView(seek)
                addView(extras)
                if (usable && f.ranged && VideoFilters.fullSupport()) { addView(rangeToggle); addView(rangeBox) }
                if (!usable) addView(TextView(ctx).apply {
                    text = "Needs Android 13 or newer"; setTextColor(MUTED); textSize = 12f
                })
            }
            load()
        }

        private fun buildExtras() {
            when (f.key) {
                "mono" -> extras.addView(colorRow(ctx, "Colour", { FilterDefs.monoColor() }) {
                    FilterDefs.setExtraInt("mono", "color", it); onExtra()
                })
                "gmap" -> buildGradientPicker()
                "ovl" -> buildOverlayControls()
                "grain" -> {
                    extras.addView(miniSlider(ctx, "Grain size", 1, 8, FilterDefs.extraInt("grain", "size", 2)) {
                        FilterDefs.setExtraInt("grain", "size", it); onExtra()
                    })
                    extras.addView(switchRow(ctx, "Colour noise", FilterDefs.extraBool("grain", "color", false)) {
                        FilterDefs.setExtraBool("grain", "color", it); onExtra()
                    })
                    extras.addView(switchRow(ctx, "Moving grain", FilterDefs.extraBool("grain", "animated", true)) {
                        FilterDefs.setExtraBool("grain", "animated", it); onExtra()
                    })
                }
                "vignette" -> extras.addView(miniSlider(ctx, "Size", 0, 100, FilterDefs.extraInt("vignette", "size", 50)) {
                    FilterDefs.setExtraInt("vignette", "size", it); onExtra()
                })
            }
        }

        private fun buildGradientPicker() {
            val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val custom = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val customIdx = FilterDefs.GRADIENTS.indexOfFirst { it.name == "Custom" }
            fun sync() {
                val cur = FilterDefs.extraInt("gmap", "preset", 3)
                for (k in 0 until chips.childCount) {
                    val c = chips.getChildAt(k)
                    c.alpha = if (k == cur) 1f else 0.65f
                    ((c as? LinearLayout)?.getChildAt(0)?.background as? GradientDrawable)
                        ?.setStroke(dp(ctx, if (k == cur) 2f else 0f), Color.WHITE)
                }
                custom.visibility = if (cur == customIdx) View.VISIBLE else View.GONE
            }
            FilterDefs.GRADIENTS.forEachIndexed { k, g ->
                chips.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(ctx, 3f), 0, dp(ctx, 3f), 0)
                    addView(View(ctx).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                            if (g.name == "Custom") FilterDefs.currentGradientFor(k) else g.colors).apply {
                            cornerRadius = dp(ctx, 8f).toFloat()
                        }
                    }, LinearLayout.LayoutParams(dp(ctx, 64f), dp(ctx, 26f)))
                    addView(TextView(ctx).apply { text = g.name; setTextColor(MUTED); textSize = 10.5f; gravity = Gravity.CENTER })
                    setOnClickListener { FilterDefs.setExtraInt("gmap", "preset", k); sync(); onExtra() }
                })
            }
            extras.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 4f); bottomMargin = dp(ctx, 4f) })
            val refreshCustomChip = {
                val chip = (chips.getChildAt(customIdx) as? LinearLayout)?.getChildAt(0)
                (chip?.background as? GradientDrawable)?.colors = FilterDefs.currentGradientFor(customIdx)
            }
            custom.addView(colorRow(ctx, "Dark colour", { FilterDefs.currentGradientFor(customIdx)[0] }) {
                FilterDefs.setExtraInt("gmap", "c1", it); refreshCustomChip(); onExtra()
            })
            custom.addView(colorRow(ctx, "Light colour", { FilterDefs.currentGradientFor(customIdx)[1] }) {
                FilterDefs.setExtraInt("gmap", "c2", it); refreshCustomChip(); onExtra()
            })
            extras.addView(custom)
            sync()
        }

        private fun buildOverlayControls() {
            val preview = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { setColor(0xFF120C1A.toInt()); cornerRadius = dp(ctx, 8f).toFloat() }
                clipToOutline = true
            }
            val srcName = TextView(ctx).apply { setTextColor(TEXT); textSize = 13.5f }
            val blendBtn = TextView(ctx).apply { setTextColor(PURPLE); textSize = 13.5f }
            var tileSwitch: Switch? = null
            fun syncSrc() {
                val src = VideoFilters.overlaySource()
                srcName.text = describeSource(src)
                if (src.startsWith("color:")) {
                    preview.setImageDrawable(null)
                    (preview.background as GradientDrawable).setColor(src.removePrefix("color:").toIntOrNull() ?: Color.MAGENTA)
                } else {
                    (preview.background as GradientDrawable).setColor(0xFF120C1A.toInt())
                    loadThumb(src) { preview.setImageBitmap(it) }
                }
                blendBtn.text = "Blend: " + FilterDefs.BLEND_MODES[FilterDefs.extraInt("ovl", "blend", 12).coerceIn(0, 26)] + "  ▾"
                tileSwitch?.isChecked = VideoFilters.overlayTiled()
            }
            extras.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
                addView(preview, LinearLayout.LayoutParams(dp(ctx, 48f), dp(ctx, 48f)).apply { rightMargin = dp(ctx, 10f) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(srcName)
                    addView(TextView(ctx).apply { text = "Tap to choose a texture, picture or colour"; setTextColor(MUTED); textSize = 11.5f })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { TexturePicker.show(ctx) { src, tex ->
                    FilterDefs.setExtraStr("ovl", TextureLibrary.KEY_SRC, src)
                    if (tex != null) {
                        // Built-in textures come with sensible defaults; start from those.
                        FilterDefs.setExtraInt("ovl", "blend", tex.blend)
                        FilterDefs.setExtraBool("ovl", "tile", tex.tile)
                        if (FilterDefs.amount(i) == 0) FilterDefs.setAmount(i, tex.opacity)
                    } else {
                        FilterDefs.setExtraBool("ovl", "tile", false)
                        if (FilterDefs.amount(i) == 0) FilterDefs.setAmount(i, 50)
                    }
                    syncSrc(); load(); onExtra()
                } }
            })
            blendBtn.setPadding(0, dp(ctx, 6f), 0, dp(ctx, 6f))
            blendBtn.setOnClickListener {
                BlendList.show(ctx, FilterDefs.extraInt("ovl", "blend", 12)) {
                    FilterDefs.setExtraInt("ovl", "blend", it); syncSrc(); onExtra()
                }
            }
            extras.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(blendBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(helpChip(ctx, "Blend modes", "How the overlay mixes with the video, like Photoshop's layer blend modes. Open the list to read what each one does."),
                    LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)))
            })
            extras.addView(miniSlider(ctx, "Size", 10, 400, FilterDefs.extraInt("ovl", "scale", 100), "%") {
                FilterDefs.setExtraInt("ovl", "scale", it); onExtra()
            })
            tileSwitch = Switch(ctx)
            extras.addView(switchRow(ctx, "Repeat as a pattern", VideoFilters.overlayTiled(), tileSwitch) {
                FilterDefs.setExtraBool("ovl", "tile", it); onExtra()
            })
            syncSrc()
        }

        fun load() {
            val v = FilterDefs.amount(i)
            seek.progress = if (f.bipolar) v + 100 else v
            showValue(v)
            range.set(FilterDefs.lo(i), FilterDefs.hi(i))
            rangeLabel.text = "Affects: " + rangeText(FilterDefs.lo(i), FilterDefs.hi(i))
            syncToggle()
        }

        fun showValue(v: Int) {
            value.text = if (v > 0 && f.bipolar) "+$v" else "$v"
            value.setTextColor(if (v == 0) MUTED else PURPLE)
        }

        fun setEnabled(on: Boolean) {
            val e = on && usable
            seek.isEnabled = e; range.isEnabled = e
            view.alpha = if (e) 1f else 0.45f
        }
    }

    fun describeSource(src: String): String = when {
        src.startsWith("tex:") -> TextureLibrary.find(src.removePrefix("tex:"))?.name ?: "Texture"
        src.startsWith("img:") -> "Your picture"
        src.startsWith("color:") -> "Solid colour"
        else -> "Nothing chosen"
    }

    fun loadThumb(src: String, done: (Bitmap) -> Unit) {
        Thread({
            val b = TextureLibrary.thumb(src)
            if (b != null) handler.post { done(b) }
        }, "ptv-thumb").start()
    }

    // ---- small building blocks ----

    fun slider(ctx: Context) = SeekBar(ctx).apply {
        progressTintList = ColorStateList.valueOf(PURPLE)
        thumbTintList = ColorStateList.valueOf(PURPLE)
        progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
    }

    /** A labelled small slider for a filter's secondary setting. */
    fun miniSlider(ctx: Context, label: String, min: Int, max: Int, cur: Int, unit: String = "", set: (Int) -> Unit): View {
        val v = TextView(ctx).apply { setTextColor(MUTED); textSize = 12f; text = "$cur$unit" }
        val sb = slider(ctx).apply { this.max = max - min; progress = (cur - min).coerceIn(0, max - min) }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                v.text = "${p + min}$unit"; set(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply { text = label; setTextColor(MUTED); textSize = 12f },
                LinearLayout.LayoutParams(dp(ctx, 86f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(v, LinearLayout.LayoutParams(dp(ctx, 44f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun switchRow(ctx: Context, label: String, on: Boolean, sw: Switch = Switch(ctx), set: (Boolean) -> Unit): View {
        sw.isChecked = on
        sw.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(PURPLE, 0xFFCCCCCC.toInt()))
        sw.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x88A970FF.toInt(), 0x55FFFFFF))
        sw.setOnCheckedChangeListener { _, v -> set(v) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply { text = label; setTextColor(MUTED); textSize = 12.5f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
    }

    /** A colour swatch with a label; tapping opens the colour picker. */
    fun colorRow(ctx: Context, label: String, get: () -> Int, set: (Int) -> Unit): View {
        val sw = View(ctx)
        fun paint() {
            sw.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(get()); setStroke(dp(ctx, 2f), Color.WHITE)
            }
        }
        paint()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
            addView(sw, LinearLayout.LayoutParams(dp(ctx, 26f), dp(ctx, 26f)).apply { rightMargin = dp(ctx, 10f) })
            addView(TextView(ctx).apply { text = label; setTextColor(TEXT); textSize = 13.5f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply { text = "Change"; setTextColor(PURPLE); textSize = 13f })
            setOnClickListener { ColorPicker.show(ctx, get()) { c -> set(c); paint() } }
        }
    }

    /** Words for a tone range, so the numbers mean something. */
    fun rangeText(lo: Int, hi: Int): String {
        if (lo <= 2 && hi >= 98) return "All tones"
        fun zone(v: Int) = when { v < 34 -> "shadows"; v < 67 -> "midtones"; else -> "highlights" }
        val a = zone(lo); val b = zone(hi.coerceAtLeast(lo))
        return if (a == b) a.replaceFirstChar { it.uppercase() } else "${a.replaceFirstChar { it.uppercase() }} to $b"
    }

    /** A round "?" that opens a short explanation bubble next to it. */
    fun helpChip(ctx: Context, title: String, body: String): View = TextView(ctx).apply {
        text = "?"; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(0x33FFFFFF); setStroke(dp(ctx, 1f), 0x55FFFFFF)
        }
        contentDescription = "What is $title?"
        setOnClickListener { v -> tooltip(v, title, body) }
    }

    fun tooltip(anchor: View, title: String, body: String) {
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

    /** A rounded dark dialog window, used by the pickers. */
    fun sheet(ctx: Context, title: String, content: View, widthDp: Float = 420f): Dialog {
        val d = Dialog(ctx)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFA1E1629.toInt()); cornerRadius = dp(ctx, 20f).toFloat() }
            setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 16f), dp(ctx, 12f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply { text = title; setTextColor(TEXT); textSize = 17f; typeface = Typeface.DEFAULT_BOLD },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(ctx).apply {
                    text = "✕"; setTextColor(TEXT); textSize = 17f
                    setPadding(dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
                    setOnClickListener { d.dismiss() }
                })
            })
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        d.setContentView(root)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = ctx.resources.displayMetrics
            val lp = w.attributes
            lp.width = dp(ctx, widthDp).coerceAtMost(dm.widthPixels - dp(ctx, 24f))
            lp.height = (dm.heightPixels * 0.82f).toInt()
            w.attributes = lp
        }
        return d
    }

    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    /**
     * Shadows-to-highlights bar with two handles. The chosen span is shown at full strength and
     * the rest of the bar is dimmed, so it reads as "this filter works here".
     */
    class RangeBar(ctx: Context) : View(ctx) {
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

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    @Suppress("unused") private val keepGrid = GridLayout::class.java
}
