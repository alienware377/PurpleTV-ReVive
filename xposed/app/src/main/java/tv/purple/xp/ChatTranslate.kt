package tv.purple.xp

import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

/**
 * Chat in your language: a line written in another language gets its translation under it, made
 * on the phone (see Translator). The original stays as it is; the translation is a small grey
 * line below, marked with an arrow.
 *
 * Translating takes about half a second a line, so it happens on a worker thread after the line is
 * shown, and the line is filled in once it is done, both in Twitch's line model (for when the row is
 * drawn again) and on screen. Lines are taken newest first, so a busy chat never falls behind:
 * anything still waiting when newer lines pile up is dropped.
 */
object ChatTranslate {

    const val KEY = "chat_translate"
    /** The place in Translator.languages() of the language to translate into. */
    const val KEY_TO = "translate_to_i"
    /** Only lines this long or longer: "gg", "lol" and single emotes stay as they are. */
    private const val MIN_CHARS = 6
    private const val MAX_QUEUE = 12

    /** Marks a body that already carries its translation, so a rebind never adds a second one. */
    private class Marker

    private class Job(val id: String, val text: String, val from: String, val model: WeakReference<Any>)

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("ptv-translate").apply { start() }.looper) }
    private val queue = ArrayDeque<Job>()
    @Volatile private var running = false
    /** Message id to its translation ("" when there was none), for rebinds. */
    private val done = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 1500
    }

    fun on() = Settings.get(KEY, false)

    /** Forget every line's translation, after a better model arrived. */
    fun clearCache() = synchronized(done) { done.clear() }

    /** Until one is picked: the phone's language when it is offered, else English. */
    val DEF_TO: Int by lazy {
        val phone = java.util.Locale.getDefault().language
        Translator.languages().indexOfFirst { it.code == phone }.coerceAtLeast(0)
    }

    /** The language to translate into, as a code. */
    fun target(): String {
        val l = Translator.languages()
        return (l.getOrNull(Settings.getInt(KEY_TO, DEF_TO)) ?: l.getOrNull(DEF_TO) ?: l.first()).code
    }

    /** From the chat assembler hook, before the deleted-message carrier is planted. */
    fun onLine(model: Any?, args: Array<Any?>?) {
        model ?: return
        if (!on()) return
        val msg = ChatTokens.message(args) ?: return
        val to = target()
        val id = to + "\u0000" + (ChatTokens.messageId(msg) ?: return)
        val known = synchronized(done) { done[id] }
        if (known != null) { if (known.isNotEmpty()) append(model, known); return }
        if (!Translator.isReady(to)) return
        val text = ChatTokens.textOf(msg).joinToString("").trim()
        val g = if (text.length < MIN_CHARS) null else LangGuess.guess(text)
        if (g == null || !g.known || g.confidence < Translator.MIN_CONFIDENCE || g.lang == to) {
            // Nothing to translate: remembered, so a rebind does not guess again.
            synchronized(done) { done[id] = "" }
            return
        }
        synchronized(queue) {
            queue.addLast(Job(id, text, g.lang, WeakReference(model)))
            while (queue.size > MAX_QUEUE) queue.removeFirst()
        }
        if (!running) { running = true; worker.post(drain) }
    }

    private val drain = object : Runnable {
        override fun run() {
            while (true) {
                val j = synchronized(queue) { queue.removeLastOrNull() } ?: break
                if (j.model.get() == null) continue
                val to = j.id.substringBefore('\u0000')
                val out = runCatching { Translator.translate(j.text, j.from, to) }.getOrNull()
                    ?.trim()?.takeIf { it.isNotEmpty() && !it.equals(j.text, ignoreCase = true) }
                synchronized(done) { done[j.id] = out ?: "" }
                if (out != null) main.post { runCatching { fill(j, out) }.onFailure { log("CT fill: $it") } }
            }
            running = false
            // A line queued between the last look and the flag going down.
            if (synchronized(queue) { queue.isNotEmpty() } && !running) { running = true; worker.post(this) }
        }
    }

    private fun body(m: Any): CharSequence? =
        runCatching { XposedHelpers.getObjectField(m, Names.BODY_FIELD) as? CharSequence }.getOrNull()

    /** The body with the translation under it, or null when it already has one. */
    private fun withTranslation(body: CharSequence, tr: String): CharSequence? {
        if (body is Spanned && body.getSpans(0, body.length, Marker::class.java).isNotEmpty()) return null
        val out = SpannableStringBuilder(body)
        val s = out.length
        out.append("\n↳ ").append(tr)
        val e = out.length
        out.setSpan(ForegroundColorSpan(0xFFADADB8.toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.9f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(Marker(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private fun append(model: Any, tr: String): Pair<CharSequence, CharSequence>? {
        val b = body(model) ?: return null
        val nb = withTranslation(b, tr) ?: return null
        runCatching { XposedHelpers.setObjectField(model, Names.BODY_FIELD, nb) }.getOrElse { return null }
        return b to nb
    }

    /** Main thread: into the line model, then onto the row on screen that shows it. */
    private fun fill(j: Job, tr: String) {
        val m = j.model.get() ?: return
        val (old, new) = append(m, tr) ?: return
        val bs = old.toString()
        if (bs.isEmpty()) return
        val root = ViewHider.currentActivity()?.window?.decorView ?: return
        val ids = listOf("chat_message_item", "chat_message").map { Res.id(root.context, it) }.filter { it != 0 }
        if (ids.isEmpty()) return
        val stack = ArrayDeque<View>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is TextView && v.id in ids) { patch(v, bs, new); continue }
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
    }

    private fun patch(tv: TextView, bs: String, new: CharSequence) {
        val t = tv.text ?: return
        if (t is Spanned && t.getSpans(0, t.length, Marker::class.java).isNotEmpty()) return
        val ts = t.toString()
        // Only a row whose text ends with this body: the body is the last part of a chat row.
        if (!ts.trimEnd { it.isWhitespace() || it.category == CharCategory.FORMAT }.endsWith(bs.trimEnd())) return
        val at = ts.lastIndexOf(bs)
        if (at < 0) return
        tv.text = SpannableStringBuilder(t).replace(at, at + bs.length, new)
    }
}
