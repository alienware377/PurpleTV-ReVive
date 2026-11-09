package tv.purple.xp

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Fits the player's top row of buttons, run after every one of our buttons has placed itself.
 *
 * Each of our buttons sits left of the one before it, which on a phone in portrait ran them off
 * the left edge and over the back button and the title. Now, when they do not fit beside Twitch's
 * own buttons, ours are first drawn a little smaller, and whatever still does not fit wraps to a
 * second line under Twitch's buttons.
 *
 * The row also keeps clear of a camera hole or notch: when one would cover a button (a tablet's
 * notch at the top in landscape, or a phone's hole once the player reaches the top of the screen),
 * both lines move down just below it.
 */
object BarFit {

    /** Ours, nearest Twitch's buttons first: the ones further left wrap first. */
    private val OURS = listOf("ptv_sleep_timer", "ptv_filters_button", "ptv_refresh_button", "ptv_clip_save", "ptv_stats_button")
    private val TWITCH = arrayOf("create_clip_text_button", "create_clip_button_compose_view", "switch_to_vertical_view",
        "cast_button", "media_route_button", "share_button", "settings_button", "audio_and_subtitles")
    /** Smallest our buttons are drawn before some wrap to the second line. */
    private const val MIN_SCALE = 0.8f

    /** How far we moved each of Twitch's views down to clear a camera hole, to take back later. */
    private val shiftOf = WeakHashMap<View, Float>()

    @Volatile private var lastSig = ""

    fun apply(root: View) {
        val settingsId = Res.id(root.context, "settings_button")
        if (settingsId == 0) return
        val bars = ArrayList<ViewGroup>()
        collect(root, settingsId, bars)
        for (bar in bars) runCatching { fit(bar) }.onFailure { log("bar fit: $it") }
    }

    private fun collect(v: View, settingsId: Int, out: MutableList<ViewGroup>) {
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out && Res.isPlayerBar(it)) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), settingsId, out)
    }

    private fun fit(bar: ViewGroup) {
        val ctx = bar.context
        fun byId(n: String) = Res.id(ctx, n).takeIf { it != 0 }?.let { bar.findViewById<View>(it) }
            ?.takeIf { it.parent === bar && it.visibility == View.VISIBLE && it.width > 0 }
        val all = TWITCH.mapNotNull { byId(it) }
        val ref = sizeRef(bar, all) ?: return
        val size = ref.height.toFloat()
        if (size <= 0f) return
        // Only the buttons on the gear's line: the vertical-view switch sits on a line of its own.
        val twitch = all.filter { abs(baseY(it) - baseY(ref)) < size / 2 }
        val ours = OURS.mapNotNull { t -> bar.findViewWithTag<View>(t)?.takeIf { it.visibility == View.VISIBLE } }
        val back = byId("back_button")
        val label = byId("mature_disclosure_container")
        val start = twitch.minOf { it.x }
        // Twitch stretches the mature content label over the whole gap and cuts its words short,
        // so only the width its words need counts as taken.
        val leftEnd = maxOf(back?.let { it.x + it.width } ?: 0f, label?.let { it.x + labelWidth(it) } ?: 0f)
        val room = start - leftEnd - dp(ctx, 4f)

        val n = ours.size
        var line1 = ours
        var line2 = emptyList<View>()
        var scale1 = 1f
        var scale2 = 1f
        if (n * size > room) {
            if (n * size * MIN_SCALE <= room) scale1 = room / (n * size)
            else {
                val fit = (room / size).toInt().coerceIn(0, n)
                line1 = ours.take(fit); line2 = ours.drop(fit)
            }
        }

        val baseY = baseY(ref)
        val spans = (twitch + listOfNotNull(back, label)).map { it.x to it.x + it.width } +
            List(line1.size) { (start - size * scale1 * (it + 1)) to (start - size * scale1 * it) }
        val need = clearance(bar, spans, baseY, size)
        val y = baseY + need
        line1.forEachIndexed { i, b -> place(b, start - size * scale1 * (i + 1), y, size, scale1) }
        // The second line runs right to left under Twitch's buttons. Where it would reach the
        // play button in the middle, it continues on the other side of it instead.
        if (line2.isNotEmpty()) {
            val end = twitch.maxOf { it.x + it.width }
            val gap = dp(ctx, 8f)
            val play = playRect(bar)?.takeIf { it.top < y + size * 2 && it.bottom > y + size }
            val zones = if (play == null) listOf(gap to end)
                else listOf((play.right + gap) to end, gap to (play.left - gap))
            scale2 = 1f
            while (scale2 > 0.6f && zones.sumOf { ((it.second - it.first) / (size * scale2)).toInt().coerceAtLeast(0) } < line2.size)
                scale2 -= 0.05f
            val y2 = y + size - size * (1f - scale2) / 2f
            var k = 0
            for ((from, to) in zones) {
                var x = to
                while (k < line2.size && x - size * scale2 >= from - 0.5f) {
                    place(line2[k], x - size * scale2, y2, size, scale2)
                    x -= size * scale2; k++
                }
            }
            // Should not all of it fit, the rest still go on the line rather than vanish.
            val rest = k
            while (k < line2.size) { place(line2[k], gap + size * scale2 * (k - rest), y2, size, scale2); k++ }
        }
        for (v in twitch + listOfNotNull(back, label)) shift(v, need)
        // The filters tip goes under its button where that ended up, below a second line if any.
        bar.findViewWithTag<View>("ptv_filters_button")?.takeIf { it.visibility == View.VISIBLE }?.let { b ->
            val s = b.scaleX
            val visTop = b.y + size * (1f - s) / 2f
            val onLine1 = b in line1
            val below = if (onLine1 && line2.isNotEmpty()) y + size + size * scale2 else visTop + size * s
            VideoFilters.moveTip(bar, b.x + size * (1f - s) / 2f, size * s, below)
        }

        val sig = "${bar.width} ${room.toInt()} ${line1.size}+${line2.size} ${need.toInt()}"
        if (sig != lastSig) {
            lastSig = sig
            log("bar fit: width ${bar.width}, room ${room.toInt()}, line one ${line1.size} at ${"%.2f".format(scale1)}, " +
                "line two ${line2.size} at ${"%.2f".format(scale2)}, down ${need.toInt()}")
        }
    }

    /** The player's play button in [bar]'s coordinates, if it shows. */
    private fun playRect(bar: ViewGroup): android.graphics.RectF? {
        val id = Res.id(bar.context, "play_pause_button").takeIf { it != 0 } ?: return null
        var p: View? = bar
        var play: View? = null
        while (p != null && play == null) {
            play = p.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }
            p = p.parent as? View
        }
        play ?: return null
        val a = IntArray(2); bar.getLocationInWindow(a)
        val b = IntArray(2); play.getLocationInWindow(b)
        val l = (b[0] - a[0]).toFloat(); val t = (b[1] - a[1]).toFloat()
        return android.graphics.RectF(l, t, l + play.width, t + play.height)
    }

    /** What the mature content label needs for its words in full. */
    private fun labelWidth(label: View): Float {
        val text = Res.id(label.context, "label_text").takeIf { it != 0 }
            ?.let { label.findViewById<TextView>(it) } ?: return label.width.toFloat()
        val words = text.paint.measureText(text.text?.toString().orEmpty())
        return (label.width - text.width + text.totalPaddingLeft + text.totalPaddingRight + words)
            .coerceAtMost(label.width.toFloat())
    }

    /** Our button [b] drawn [scale] times its size, its visible left edge at [x], its box at [y]. */
    private fun place(b: View, x: Float, y: Float, size: Float, scale: Float) {
        if (b.scaleX != scale) { b.scaleX = scale; b.scaleY = scale }
        val left = x - size * (1f - scale) / 2f
        if (b.x != left) b.x = left
        if (b.y != y) b.y = y
    }

    /** Where [v] sits before any move of ours. */
    private fun baseY(v: View) = v.top + v.translationY - (shiftOf[v] ?: 0f)

    private fun shift(v: View, by: Float) {
        val had = shiftOf[v] ?: 0f
        if (had == by) return
        v.translationY = v.translationY - had + by
        if (by == 0f) shiftOf.remove(v) else shiftOf[v] = by
    }

    /**
     * How far the row must move down so no camera hole or notch covers any of its buttons, or 0.
     * Measured against where the row would be without our move, so it settles instead of climbing.
     */
    private fun clearance(bar: ViewGroup, spans: List<Pair<Float, Float>>, rowY: Float, size: Float): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0f
        val cut = bar.rootWindowInsets?.displayCutout ?: return 0f
        val rects = cut.boundingRects.filter { !it.isEmpty }
        if (rects.isEmpty()) return 0f
        val at = IntArray(2); bar.getLocationInWindow(at)
        val top = at[1] + rowY
        val bottom = top + size
        val half = (bar.rootView?.height ?: return 0f) / 2
        val m = dp(bar.context, 6f)
        var need = 0f
        for (r in rects) {
            if (r.top > half || r.bottom <= top || r.top >= bottom) continue
            val hit = spans.any { (l, rr) -> at[0] + rr + m > r.left && at[0] + l - m < r.right }
            if (hit) need = maxOf(need, r.bottom + m - top)
        }
        return need
    }

    private fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
}
