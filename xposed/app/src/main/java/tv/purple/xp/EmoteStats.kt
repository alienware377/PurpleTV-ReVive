package tv.purple.xp

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Who used an emote the most in this chat, counted from the lines seen since you opened it.
 *
 * Shown under our own emote preview (7TV, BTTV, FFZ) and added to the bottom of Twitch's own emote
 * card for Twitch and sub emotes. Twitch's card is kept as it is; ours is only an extra section.
 *
 * Each message counts once per emote, so a line with the same emote ten times is one use. Only
 * this channel's lines count, and the counts start over when you open another channel. Nothing is
 * stored: it lives in memory only.
 */
object EmoteStats {

    const val KEY = "emote_stats"
    private const val TOP = 5
    private const val MAX_EMOTES = 3000
    private const val MAX_USERS_PER_EMOTE = 400

    private val lock = Any()
    private var channel: String? = null
    private var since = 0L
    /** emote name to (login to messages with it). */
    private val counts = HashMap<String, HashMap<String, Int>>()

    fun on() = Settings.get(KEY, true)

    fun install() {
        ChatFeed.addListener { onLine(it) }
        hookDialogs()
        log("ES emote stats installed")
    }

    private fun onLine(l: ChatFeed.Line) {
        if (l.emotes.isEmpty() || !on()) return
        synchronized(lock) {
            if (l.channel != channel) { counts.clear(); channel = l.channel; since = l.at }
            for (e in l.emotes.toHashSet()) {
                val m = counts.getOrPut(e) {
                    if (counts.size >= MAX_EMOTES) return@synchronized
                    HashMap()
                }
                m[l.login] = (m[l.login] ?: 0) + 1
                if (m.size > MAX_USERS_PER_EMOTE) prune(m)
            }
        }
    }

    /** Drops the one-time users, so a busy emote keeps its regulars and stays small. */
    private fun prune(m: HashMap<String, Int>) {
        val it = m.entries.iterator()
        while (it.hasNext()) if (it.next().value <= 1) it.remove()
    }

    /** The top users of [name] in this chat, most uses first, and the total uses. */
    fun top(name: String): Pair<List<Pair<String, Int>>, Int> = synchronized(lock) {
        val m = counts[name] ?: return@synchronized emptyList<Pair<String, Int>>() to 0
        m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP).map { it.key to it.value } to m.values.sum()
    }

    private fun minutesSince(): Long = synchronized(lock) {
        if (since == 0L) 0 else (System.currentTimeMillis() - since) / 60_000
    }

    /** The section for a preview or card, or null when the feature is off. */
    fun section(ctx: Context, name: String): View? {
        if (!on()) return null
        val (top, total) = top(name)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(ctx, 10f), 0, 0)
            tag = TAG
        }
        val mins = minutesSince()
        val head = if (top.isEmpty()) "Nobody used $name since you opened this chat"
            else "Used most in this chat" + if (mins >= 1) " (last ${fmtMins(mins)})" else ""
        box.addView(text(ctx, head, "#ADADB8", 13f))
        top.forEachIndexed { i, (who, n) ->
            box.addView(text(ctx, "${i + 1}. $who  ×$n", if (i == 0) "#BF94FF" else "#EFEFF1", 14f))
        }
        if (top.size > 1 && total > 0) box.addView(text(ctx, "$total uses in all", "#ADADB8", 12f))
        return box
    }

    private fun fmtMins(m: Long) = if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"

    private fun text(ctx: Context, s: String, color: String, sp: Float) = TextView(ctx).apply {
        text = s
        setTextColor(Color.parseColor(color))
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(dp(ctx, 8f), dp(ctx, 2f), dp(ctx, 8f), 0)
    }

    // ------------------------------------------------------------ Twitch's own emote card

    private const val TAG = "ptv_emote_stats"
    @Volatile private var tapName: String? = null
    @Volatile private var tapAt = 0L

    /** A tap on a Twitch emote in chat, which Twitch answers with its emote card. */
    fun noteNativeTap(name: String) {
        tapName = name; tapAt = SystemClock.uptimeMillis()
    }

    /**
     * Twitch's card opens as a dialog (a bottom sheet) a moment after the tap. Any dialog shown
     * within that moment gets our section added below its content, once. The card's own content is
     * moved into a column with ours under it, so whatever Twitch draws it with, it stays as it is.
     */
    private fun hookDialogs() {
        runCatching {
            XposedBridge.hookAllMethods(Dialog::class.java, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val name = tapName ?: return
                    if (SystemClock.uptimeMillis() - tapAt > 2500) return
                    val d = p.thisObject as? Dialog ?: return
                    tapName = null
                    val decor = d.window?.decorView ?: return
                    // Twitch fills the sheet after show; look once it has laid out.
                    decor.post { runCatching { addTo(d, name) }.onFailure { log("ES card: $it") } }
                }
            })
        }.onFailure { log("ES dialog hook failed: $it") }
    }

    private fun addTo(d: Dialog, name: String) {
        val decor = d.window?.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(TAG) != null) return
        val ctx = d.context
        val sheetId = Res.id(ctx, "design_bottom_sheet")
        val host = (if (sheetId != 0) decor.findViewById<ViewGroup>(sheetId) else null)
            ?: decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        val content = (0 until host.childCount).map { host.getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE }
            ?: return
        val section = section(ctx, name) ?: return
        log("ES card ${d.javaClass.name} host=${host.javaClass.simpleName} content=${content.javaClass.simpleName}")
        val bg = GradientDrawable().apply { setColor(Color.parseColor("#F21F1925")) }
        section.background = bg
        section.setPadding(0, dp(ctx, 8f), 0, dp(ctx, 12f))
        // Twitch's card is never taken apart: moving its view (a Compose view, most likely) would
        // throw its content away. Ours goes into a column Twitch already has, or not at all.
        val col = findColumn(content, name) ?: run { log("ES card: no column to add to, skipped"); return }
        col.addView(section, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * A vertical LinearLayout of the card that shows this emote's name, so a dialog that only
     * happened to open after the tap (not the emote card) is left alone.
     */
    private fun findColumn(v: View, name: String): LinearLayout? {
        if (!hasText(v, name)) return null
        var best: LinearLayout? = null
        val stack = ArrayDeque<View>().apply { add(v) }
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (x is LinearLayout && x.orientation == LinearLayout.VERTICAL && hasText(x, name)) best = x
            if (x is ViewGroup) for (i in 0 until x.childCount) stack.add(x.getChildAt(i))
        }
        return best?.let { b ->
            // The outermost column that holds the name: walk up while the parent is a column too.
            var c: LinearLayout = b
            while (true) { val p = c.parent as? LinearLayout ?: break; if (p.orientation != LinearLayout.VERTICAL || p === v.parent) break; c = p }
            c
        }
    }

    private fun hasText(v: View, s: String): Boolean {
        val stack = ArrayDeque<View>().apply { add(v) }
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (x is TextView && x.text?.toString()?.contains(s) == true) return true
            if (x is ViewGroup) for (i in 0 until x.childCount) stack.add(x.getChildAt(i))
        }
        return false
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
