package tv.purple.xp

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The favorites bar in the emote picker: one scrolling row, or rows that grow as favorites are
 * added, with groups that open and close like folders.
 *
 * Tap a favorite to put it in the chat box. Hold one and drag it onto a group to move it there,
 * onto another favorite to place it before that one, onto an empty part of the bar to take it out
 * of its group, or onto the bin (where the star is while dragging) to remove it. Tap a group to
 * open or close it, hold it to rename it, change its icon or delete it, and tap + for a new one.
 */
object FavBar {

    const val KEY_LAYOUT = "fav_bar_layout"
    const val LAYOUT_ROW = 0
    const val LAYOUT_ROWS = 1
    const val KEY_TIP = "fav_groups_tip_dismissed"

    /** How groups sit in rows mode. */
    const val KEY_GROUP_STYLE = "fav_group_style"
    const val GROUPS_FLOW = 0      // groups and their emotes share lines with everything else
    const val GROUPS_LINES = 1     // each group starts its own line, its emotes right after it
    const val GROUPS_TREE = 2      // folder tree: the group on its own line, its emotes indented below

    private const val BAR_BG = 0xFF18141D.toInt()
    private const val GOLD = 0xFFFFD24A.toInt()
    private const val TEXT = 0xFFEFEFF1.toInt()
    private const val MUTED = 0x99FFFFFF.toInt()
    private val PURPLE = FilterIcons.PURPLE

    private fun rowsMode() = Settings.getInt(KEY_LAYOUT, LAYOUT_ROW) == LAYOUT_ROWS

    private fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    // ---- the strip: an optional tip, then [star or bin][bar] ----

    /** Builds the strip; [host] is where [render] draws the favorites. */
    fun buildStrip(ctx: Context): Pair<LinearLayout, FrameLayout> {
        val host = FrameLayout(ctx)
        val star = TextView(ctx).apply {
            text = "★"; setTextColor(GOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 8f), dp(ctx, 8f))
        }
        // While a favorite is being dragged the star turns into a bin.
        star.setOnDragListener { v, e ->
            val name = (e.localState as? Drag)?.name ?: return@setOnDragListener false
            val t = v as TextView
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    t.text = "🗑"; t.background = round(ctx, 0x55E5484D, 0xFFE5484D.toInt()); true
                }
                DragEvent.ACTION_DRAG_ENTERED -> { t.background = round(ctx, 0xCCE5484D.toInt(), Color.WHITE); true }
                DragEvent.ACTION_DRAG_EXITED -> { t.background = round(ctx, 0x55E5484D, 0xFFE5484D.toInt()); true }
                DragEvent.ACTION_DROP -> { Favorites.remove(name); toast(ctx, "Removed $name"); true }
                DragEvent.ACTION_DRAG_ENDED -> { t.text = "★"; t.background = null; true }
                else -> true
            }
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(star, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP; topMargin = dp(ctx, 4f); leftMargin = dp(ctx, 2f) })
            addView(host, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR_BG)
            addView(bar)
        }
        return strip to host
    }

    /** Fill [host] with the favorites as they are now. Also redraws the tip above the bar. */
    fun render(host: FrameLayout) {
        val ctx = host.context ?: return
        host.removeAllViews()
        syncTip(host)
        val groups = Favorites.groups()
        val loose = Favorites.loose()
        // Pictures not loaded yet (including group icons): fetch them, then draw again.
        val want = (loose + groups.flatMap { it.items } + groups.map { it.icon }.filter { it.isNotEmpty() })
            .filter { Favorites.isAvailable(it) && pic(it) == null }
        if (want.isNotEmpty()) EmoteRepo.wantPictures(want) { host.post { runCatching { render(host) } } }

        if (rowsMode()) renderRows(host, groups, loose) else renderRow(host, groups, loose)
    }

    private fun renderRow(host: FrameLayout, groups: List<Favorites.Group>, loose: List<String>) {
        val ctx = host.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 50f)
        }
        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        for (g in groups) {
            row.addView(groupChip(ctx, g, header = false))
            if (g.open && g.items.isNotEmpty()) {
                val seg = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = round(ctx, 0x22A970FF, 0x55A970FF)
                    setPadding(dp(ctx, 2f), dp(ctx, 2f), dp(ctx, 2f), dp(ctx, 2f))
                }
                for (n in g.items) seg.addView(cell(ctx, n, g.id, scroll))
                row.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(ctx, 6f) })
            }
        }
        for (n in loose) row.addView(cell(ctx, n, null, scroll))
        if (groups.isEmpty() && loose.isEmpty()) row.addView(hint(ctx))
        row.addView(addChip(ctx, short = true))
        dropOnto(row, scroll) { Favorites.move(it, null, null) }
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun renderRows(host: FrameLayout, groups: List<Favorites.Group>, loose: List<String>) {
        val ctx = host.context
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f)) }
        val maxH = minOf(dp(ctx, 240f), (ctx.resources.displayMetrics.heightPixels * 0.35f).toInt())
        val scroll = MaxScroll(ctx, maxH).apply { isVerticalScrollBarEnabled = true; addView(col) }
        val style = Settings.getInt(KEY_GROUP_STYLE, GROUPS_FLOW)

        /** A group's open emotes, tinted so they read as belonging to it. */
        fun groupCells(f: ViewGroup, g: Favorites.Group) {
            for (n in g.items) f.addView(cell(ctx, n, g.id, scroll).apply { background = round(ctx, 0x22A970FF, 0) })
        }

        when (style) {
            GROUPS_TREE -> for (g in groups) {
                col.addView(groupChip(ctx, g, header = true))
                if (g.open) {
                    val f = Flow(ctx).apply { setPadding(dp(ctx, 22f), 0, 0, dp(ctx, 4f)) }
                    for (n in g.items) f.addView(cell(ctx, n, g.id, scroll))
                    if (g.items.isEmpty()) f.addView(TextView(ctx).apply {
                        text = "Drag favorites here"; setTextColor(MUTED); textSize = 12.5f
                        setPadding(dp(ctx, 4f), dp(ctx, 6f), 0, dp(ctx, 6f))
                    })
                    dropOnto(f, scroll) { Favorites.move(it, g.id, null) }
                    col.addView(f)
                }
            }
            GROUPS_LINES -> for (g in groups) {
                val f = Flow(ctx)
                f.addView(groupChip(ctx, g, header = false))
                if (g.open) groupCells(f, g)
                dropOnto(f, scroll) { Favorites.move(it, g.id, null) }
                col.addView(f)
            }
        }
        // Loose favorites, and in the flowing style every group too, wrapping only when a line is full.
        val f = Flow(ctx)
        if (style == GROUPS_FLOW) for (g in groups) {
            f.addView(groupChip(ctx, g, header = false))
            if (g.open) groupCells(f, g)
        }
        for (n in loose) f.addView(cell(ctx, n, null, scroll))
        if (groups.isEmpty() && loose.isEmpty()) f.addView(hint(ctx))
        f.addView(addChip(ctx, short = false))
        dropOnto(f, scroll) { Favorites.move(it, null, null) }
        col.addView(f)
        dropOnto(col, scroll) { Favorites.move(it, null, null) }
        host.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun hint(ctx: Context) = TextView(ctx).apply {
        text = "Long-press an emote to add it here"; setTextColor(MUTED); textSize = 13f
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(ctx, 4f), dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f))
    }

    // ---- pieces ----

    /** One favorite: tap to insert, hold to drag. */
    private fun cell(ctx: Context, name: String, groupId: String?, scroller: View): View {
        val usable = Favorites.isAvailable(name)
        val d = if (usable) pic(name) else null
        val size = dp(ctx, 34f)
        val inner: View = if (d != null) ImageView(ctx).apply {
            setImageDrawable(d)
            if (d is AnimatedImageDrawable) runCatching { d.start() }
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        } else TextView(ctx).apply {
            text = name; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size, Gravity.CENTER)
        }
        return FrameLayout(ctx).apply {
            setPadding(dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f))
            isClickable = true
            addView(inner)
            contentDescription = name
            if (!usable) alpha = 0.35f
            setOnClickListener {
                if (!usable) { toast(ctx, "$name is not available in this chat"); return@setOnClickListener }
                if (!EmoteAutocomplete.insertEmote(name)) toast(ctx, "Tap the chat box first")
            }
            setOnLongClickListener { v ->
                v.startDragAndDrop(ClipData.newPlainText("emote", name), View.DragShadowBuilder(v), Drag(name), 0)
                true
            }
            // Dropped onto another favorite: go just before it, in its group.
            dropOnto(this, scroller) { dragged -> if (dragged != name) Favorites.move(dragged, groupId, name) }
        }
    }

    /** A group: a folder chip in the row, or a header line in rows mode. */
    private fun groupChip(ctx: Context, g: Favorites.Group, header: Boolean): View {
        val iconPic = g.icon.takeIf { it.isNotEmpty() && Favorites.isAvailable(it) }?.let { pic(it) }
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f))
            background = round(ctx, 0x33A970FF, PURPLE)
            contentDescription = "Group " + g.name.ifEmpty { g.icon }
            addView(TextView(ctx).apply {
                text = if (g.open) "▾" else "▸"; setTextColor(PURPLE); textSize = 14f
                setPadding(0, 0, dp(ctx, 6f), 0)
            })
            if (iconPic != null) {
                if (iconPic is AnimatedImageDrawable) runCatching { iconPic.start() }
                addView(ImageView(ctx).apply { setImageDrawable(iconPic) }, LinearLayout.LayoutParams(dp(ctx, 28f), dp(ctx, 28f)))
            }
            // The name, unless an icon stands in for it in the compact row.
            val label = g.name.ifEmpty { if (iconPic == null) g.icon else "" }
            if (label.isNotEmpty() && (header || iconPic == null)) addView(TextView(ctx).apply {
                text = label; setTextColor(TEXT); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setPadding(if (iconPic != null) dp(ctx, 6f) else 0, 0, 0, 0)
            })
            if (header || !g.open) addView(TextView(ctx).apply {
                text = "  ${g.items.size}"; setTextColor(MUTED); textSize = 12f
            })
            setOnClickListener { Favorites.setOpen(g.id, !g.open) }
            setOnLongClickListener { groupMenu(ctx, g); true }
            dropOnto(this, null) { Favorites.move(it, g.id, null) }
        }
        val lp = if (header) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(ctx, 2f), dp(ctx, 3f), 0, dp(ctx, 3f)) }
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38f))
            .apply { setMargins(dp(ctx, 2f), dp(ctx, 3f), dp(ctx, 6f), dp(ctx, 3f)) }
        v.layoutParams = lp
        return v
    }

    private fun addChip(ctx: Context, short: Boolean) = TextView(ctx).apply {
        text = if (short) "+" else "+ New group"
        setTextColor(PURPLE); textSize = if (short) 18f else 13.5f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        contentDescription = "New favorites group"
        setPadding(dp(ctx, 12f), dp(ctx, 4f), dp(ctx, 12f), dp(ctx, 4f))
        background = round(ctx, 0, 0x88A970FF.toInt())
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36f))
            .apply { setMargins(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 8f), dp(ctx, 4f)) }
        setOnClickListener { groupDialog(ctx, null) }
    }

    private fun round(ctx: Context, fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(ctx, 10f).toFloat()
        if (stroke != 0) setStroke(dp(ctx, 1f), stroke)
    }

    // ---- dragging ----

    private class Drag(val name: String)

    /** Make [v] accept a dragged favorite; [scroller] scrolls when the finger nears its edge. */
    private fun dropOnto(v: View, scroller: View?, onDrop: (String) -> Unit) {
        val hi = ColorDrawable(0x55A970FF)
        v.setOnDragListener { view, e ->
            val name = (e.localState as? Drag)?.name ?: return@setOnDragListener false
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> { view.foreground = hi; true }
                DragEvent.ACTION_DRAG_LOCATION -> { edgeScroll(view, e, scroller ?: findScroller(view)); true }
                DragEvent.ACTION_DRAG_EXITED -> { view.foreground = null; true }
                DragEvent.ACTION_DROP -> { view.foreground = null; onDrop(name); true }
                DragEvent.ACTION_DRAG_ENDED -> { view.foreground = null; true }
                else -> true
            }
        }
    }

    private fun findScroller(v: View): View? {
        var p = v.parent
        while (p is View) { if (p is HorizontalScrollView || p is ScrollView) return p; p = p.parent }
        return null
    }

    /** Scroll the bar while a dragged favorite is held near its edge. */
    private fun edgeScroll(view: View, e: DragEvent, scroller: View?) {
        scroller ?: return
        val at = IntArray(2).also { view.getLocationOnScreen(it) }
        val sAt = IntArray(2).also { scroller.getLocationOnScreen(it) }
        val edge = dp(view.context, 36f); val step = dp(view.context, 14f)
        if (scroller is HorizontalScrollView) {
            val x = at[0] + e.x - sAt[0]
            if (x < edge) scroller.scrollBy(-step, 0) else if (x > scroller.width - edge) scroller.scrollBy(step, 0)
        } else if (scroller is ScrollView) {
            val y = at[1] + e.y - sAt[1]
            if (y < edge) scroller.scrollBy(0, -step) else if (y > scroller.height - edge) scroller.scrollBy(0, step)
        }
    }

    // ---- groups ----

    private fun groupMenu(ctx: Context, g: Favorites.Group) {
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(g.name.ifEmpty { "Group" })
            .setItems(arrayOf("Rename or change icon", "Delete group (its emotes stay favorites)")) { _, which ->
                if (which == 0) groupDialog(ctx, g) else Favorites.deleteGroup(g.id)
            }
            .show()
    }

    /** New group ([g] null) or edit one: a name, and/or one of your favorites as its icon. */
    private fun groupDialog(ctx: Context, g: Favorites.Group?) {
        var icon = g?.icon.orEmpty()
        val name = EditText(ctx).apply {
            hint = "Name (optional if you pick an icon)"; setText(g?.name.orEmpty())
            setTextColor(TEXT); setHintTextColor(MUTED); isSingleLine = true
        }
        val picks = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cells = ArrayList<Pair<String, View>>()
        fun sync() { for ((n, v) in cells) v.background = if (n == icon) round(ctx, 0x55A970FF, PURPLE) else null }
        fun pickCell(n: String, view: View) {
            view.setPadding(dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f))
            view.setOnClickListener { icon = n; sync() }
            cells += n to view; picks.addView(view)
        }
        pickCell("", TextView(ctx).apply { text = "No icon"; setTextColor(TEXT); textSize = 13f; gravity = Gravity.CENTER })
        for (n in Favorites.list().distinct()) {
            val d = if (Favorites.isAvailable(n)) pic(n) else null
            pickCell(n, if (d != null) ImageView(ctx).apply {
                setImageDrawable(d); if (d is AnimatedImageDrawable) runCatching { d.start() }
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 46f), dp(ctx, 46f))
            } else TextView(ctx).apply { text = n; setTextColor(TEXT); textSize = 13f })
        }
        sync()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 8f), dp(ctx, 20f), 0)
            addView(name)
            addView(TextView(ctx).apply {
                text = "Icon (optional): tap one of your favorites"; setTextColor(MUTED); textSize = 13f
                setPadding(0, dp(ctx, 12f), 0, dp(ctx, 4f))
            })
            addView(HorizontalScrollView(ctx).apply { addView(picks) })
        }
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (g == null) "New favorites group" else "Edit group")
            .setView(box)
            .setPositiveButton(if (g == null) "Create" else "Save") { _, _ ->
                var n = name.text.toString().trim()
                if (n.isEmpty() && icon.isEmpty()) n = "Group ${Favorites.groups().size + if (g == null) 1 else 0}"
                if (g == null) Favorites.newGroup(n, icon) else Favorites.editGroup(g.id, n, icon)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- the one-time tip ----

    private fun syncTip(host: View) {
        val strip = host.parent?.parent as? LinearLayout ?: return
        val old = strip.findViewWithTag<View>("ptv_fav_tip")
        if (Settings.get(KEY_TIP, false)) { if (old != null) strip.removeView(old); return }
        if (old != null) return
        val ctx = host.context
        val tip = LinearLayout(ctx).apply {
            tag = "ptv_fav_tip"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(ctx, 0xF2241B31.toInt(), PURPLE)
            setPadding(dp(ctx, 12f), dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 8f))
            addView(TextView(ctx).apply {
                text = "New: favorite groups! Tap + to make one, with a name or an emote as its icon, and tap a group to open or close it. " +
                    "Hold a favorite and drag it into a group, onto another to reorder, or onto the bin to remove it. " +
                    "PurpleTV settings › Third Party Services › Emotes can let the bar grow into rows."
                setTextColor(TEXT); textSize = 12.5f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "✕"; setTextColor(TEXT); textSize = 16f; gravity = Gravity.CENTER
                contentDescription = "Close tip"
                setOnClickListener { Settings.set(KEY_TIP, true); strip.findViewWithTag<View>("ptv_fav_tip")?.let { strip.removeView(it) } }
            }, LinearLayout.LayoutParams(dp(ctx, 40f), dp(ctx, 40f)))
        }
        strip.addView(tip, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(ctx, 6f), dp(ctx, 6f), dp(ctx, 6f), 0) })
    }

    // ---- pictures ----

    /** A fresh drawable for a favorite: animated copy if animated, else a copy of the static one. */
    fun pic(name: String): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && EmoteRepo.animBytes.containsKey(name))
            EmoteRepo.makeAnimated(name)?.let { return it }
        EmoteRepo.suggestDrawables[name]?.let { return it.constantState?.newDrawable()?.mutate() ?: it }
        EmoteRepo.drawables[name]?.let { return it.constantState?.newDrawable()?.mutate() ?: it }
        return null
    }

    // ---- layouts ----

    /** A ScrollView that grows with its content up to [maxH], then scrolls. */
    private class MaxScroll(ctx: Context, val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }

    /** Lays children left to right, wrapping onto new lines, so the bar grows into rows. */
    private class Flow(ctx: Context) : ViewGroup(ctx) {
        override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        override fun generateLayoutParams(p: LayoutParams?) = MarginLayoutParams(p)
        override fun checkLayoutParams(p: LayoutParams?) = p is MarginLayoutParams

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
            var x = 0; var y = 0; var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i); if (c.visibility == GONE) continue
                measureChildWithMargins(c, wSpec, 0, hSpec, 0)
                val lp = c.layoutParams as MarginLayoutParams
                val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
                val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
                if (x > 0 && x + cw > maxW) { y += lineH; x = 0; lineH = 0 }
                x += cw; lineH = maxOf(lineH, ch)
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            var x = 0; var y = 0; var lineH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i); if (c.visibility == GONE) continue
                val lp = c.layoutParams as MarginLayoutParams
                val cw = c.measuredWidth + lp.leftMargin + lp.rightMargin
                val ch = c.measuredHeight + lp.topMargin + lp.bottomMargin
                if (x > 0 && x + cw > maxW) { y += lineH; x = 0; lineH = 0 }
                val cx = paddingLeft + x + lp.leftMargin
                val cy = paddingTop + y + lp.topMargin
                c.layout(cx, cy, cx + c.measuredWidth, cy + c.measuredHeight)
                x += cw; lineH = maxOf(lineH, ch)
            }
        }
    }
}
