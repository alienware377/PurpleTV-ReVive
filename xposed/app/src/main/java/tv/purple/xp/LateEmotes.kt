package tv.purple.xp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Chat lines that arrive before their emotes do: the messages Twitch loads when a channel opens
 * come in before that channel's 7TV/BTTV/FFZ list, and a line using an emote whose picture is
 * still downloading would stay as plain text. Those lines are kept for a short while and filled
 * in once the emotes land, both in the line itself (for when it is drawn again) and on screen.
 */
object LateEmotes {

    private class Line(val model: WeakReference<Any>, val at: Long)

    private const val MAX = 150
    private const val KEEP_MS = 45_000L
    private const val TICK_MS = 1500L

    private val lines = ArrayDeque<Line>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var scheduled = false

    /** From the chat line builder, on whatever thread Twitch builds lines on. */
    fun add(model: Any) {
        synchronized(lines) {
            lines.addLast(Line(WeakReference(model), SystemClock.uptimeMillis()))
            while (lines.size > MAX) lines.removeFirst()
        }
        if (!scheduled) { scheduled = true; main.postDelayed(::tick, TICK_MS) }
    }

    private fun body(m: Any): CharSequence? =
        runCatching { de.robv.android.xposed.XposedHelpers.getObjectField(m, Names.BODY_FIELD) as? CharSequence }.getOrNull()

    private fun tick() {
        scheduled = false
        val now = SystemClock.uptimeMillis()
        val pending = EmoteRepo.channelPending()
        // A channel list that came in after the channel was shown: keep its early lines until it lands.
        val late = EmoteRepo.listsLate()
        val snapshot = synchronized(lines) { ArrayList(lines) }
        val done = HashSet<Line>()
        val filled = ArrayList<Spanned>()
        for (l in snapshot) {
            val m = l.model.get()
            if (m == null || now - l.at > KEEP_MS) { done += l; continue }
            if (pending) continue
            val before = body(m)
            val waiting = runCatching { EmoteHooks.fill(m, first = false) }.getOrDefault(false)
            val after = body(m)
            if (after !== before && after is Spanned) filled += after
            if (!waiting && !late) done += l
        }
        synchronized(lines) { lines.removeAll(done) }
        if (filled.isNotEmpty()) runCatching { redraw(filled) }.onFailure { log("late emotes redraw: $it") }
        val more = synchronized(lines) { lines.isNotEmpty() }
        if (more && !scheduled) { scheduled = true; main.postDelayed(::tick, TICK_MS) }
    }

    /** Copy the new pictures onto the chat rows already on screen that show these lines. */
    private fun redraw(filled: List<Spanned>) {
        val root = ViewHider.currentActivity()?.window?.decorView ?: return
        val ids = listOf("chat_message_item", "chat_message").map { Res.id(root.context, it) }.filter { it != 0 }
        if (ids.isEmpty()) return
        var n = 0
        val stack = ArrayDeque<View>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is TextView && v.id in ids) { if (patch(v, filled)) n++; continue }
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
        if (n > 0) log("late emotes drawn on $n chat line(s)")
    }

    private fun patch(tv: TextView, filled: List<Spanned>): Boolean {
        val t = tv.text ?: return false
        val ts = t.toString()
        var out: SpannableStringBuilder? = null
        for (b in filled) {
            val bs = b.toString()
            if (bs.isEmpty()) continue
            val at = ts.lastIndexOf(bs)
            if (at < 0) continue
            // The whole message, not a piece of a longer word.
            if (at > 0 && !ts[at - 1].isWhitespace()) continue
            if (at + bs.length < ts.length && !ts[at + bs.length].isWhitespace()) continue
            for (sp in b.getSpans(0, b.length, EmoteHooks.PtvEmoteSpan::class.java)) {
                val s = at + b.getSpanStart(sp); val e = at + b.getSpanEnd(sp)
                val have = (out ?: t as? Spanned)?.getSpans(s, e, EmoteHooks.PtvEmoteSpan::class.java)
                // A row already showing this same picture is left alone; one showing another picture
                // (the global emote of the same name, before the channel's own arrived) is swapped.
                if (!have.isNullOrEmpty() && have.any { it.url == sp.url }) continue
                val o = out ?: SpannableStringBuilder(t).also { out = it }
                for (old in o.getSpans(s, e, EmoteHooks.PtvEmoteSpan::class.java)) o.removeSpan(old)
                o.setSpan(sp, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        out?.let { tv.text = it; return true }
        return false
    }
}
