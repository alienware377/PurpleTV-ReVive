package tv.purple.xp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Live captions over the stream, optionally translated into your language.
 *
 * The text comes from the stream's own captions when the streamer sends them, otherwise from
 * speech to text on the phone (the same offline recognizer as the ad break captions, with the
 * speech model of the "Stream language" setting). Translation uses the offline translator and the
 * "Translate into" language.
 * During a Picture-by-Picture ad break the recognizer goes back to the ad break captions.
 *
 * Nothing leaves the phone.
 */
object LiveCaptions {

    const val KEY = "live_captions"
    const val KEY_TR = "live_captions_translate"
    private const val TAG = "ptv_live_captions"
    private const val TICK_MS = 500L
    /** A finished line stays this long. */
    private const val LINE_MS = 6_000L
    private const val KEEP = 2
    private const val CC_HOLD_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("ptv-live-tr").apply { start() }.looper) }

    /** Set on main by the tick, read by Stt on its thread. */
    @Volatile private var showing = false
    @Volatile private var lastCcAt = 0L
    @Volatile private var onCached = false

    private class Line(val text: String, val at: Long, var shown: String, val fromStt: Boolean)
    // Main thread only.
    private val lines = ArrayDeque<Line>()
    private var partial = ""
    private var ccRow = ""
    private var view: WeakReference<TextView>? = null
    private var installed = false
    private var lastText: CharSequence = ""

    fun on() = Settings.isReady() && Settings.get(KEY, false)

    fun install() {
        if (installed) return
        installed = true
        main.postDelayed(tick, TICK_MS)
        log("LC live captions installed")
    }

    /** Stt: listen to the main stream now. Any thread, cheap. */
    fun sttWanted(): Boolean = showing && onCached

    /** Captions: read the main player's own captions. Any thread, cheap. */
    fun wantsCc(): Boolean = showing && onCached

    fun ccRecent(): Boolean {
        val t = lastCcAt
        return t != 0L && SystemClock.uptimeMillis() - t < CC_HOLD_MS
    }

    private fun translating() = Settings.get(KEY_TR, true) && Translator.isReady(ChatTranslate.target())

    // ------------------------------------------------------------ input

    /** IVS thread: one caption event of the main player. */
    fun onCcJson(s: String) {
        lastCcAt = SystemClock.uptimeMillis()
        main.post {
            runCatching {
                val c = JSONObject(s).optJSONObject("caption") ?: return@runCatching
                val rows = c.optString("text").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (rows.isEmpty()) { if (ccRow.isNotEmpty()) finish(ccRow, false); ccRow = ""; return@runCatching }
                // Rows above the last have scrolled up: finished. The last one may still grow.
                for (r in rows.dropLast(1)) if (lines.none { it.text == r }) finish(r, false)
                val last = rows.last()
                if (ccRow.isNotEmpty() && !last.startsWith(ccRow.take(12)) && lines.none { it.text == ccRow }) finish(ccRow, false)
                ccRow = last
                partial = last
                render()
            }.onFailure { log("LC cc: $it") }
        }
    }

    /** Stt thread: a live guess or a finished line. */
    fun onSttText(text: String, fin: Boolean) {
        main.post {
            val t = text.trim().replaceFirstChar { it.uppercase() }
            if (fin) { partial = ""; if (t.isNotEmpty()) finish(t, true) } else partial = t
            render()
        }
    }

    private fun finish(t: String, fromStt: Boolean) {
        val l = Line(t, SystemClock.uptimeMillis(), t, fromStt)
        // Speech to text speaks the stream language picked in settings; read it now, on main, so
        // a line already in the queue keeps the language it was heard in.
        val sttLang = if (fromStt) SttModels.chosen().lang else null
        lines.addLast(l)
        while (lines.size > KEEP) lines.removeFirst()
        if (translating()) {
            val to = ChatTranslate.target()
            l.shown = ""
            worker.post {
                // The recognizer's language is known; the stream's own captions may be in any language.
                val from = sttLang
                val out = runCatching { Translator.translate(t, from, to) }.getOrNull()
                main.post { l.shown = out ?: t; render() }
            }
        }
    }

    // ------------------------------------------------------------ view

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (t: Throwable) { log("LC tick: $t") }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        onCached = on()
        if (!onCached && !showing) { view?.get()?.visibility = View.GONE; return }
        val act = ViewHider.currentActivity()
        val root = act?.window?.peekDecorView()
        val vid = root?.let { r -> Res.id(r.context, "playback_view_container").takeIf { it != 0 }?.let { r.findViewById<View>(it) } }
        val now = onCached && vid != null && vid.isShown && RefreshButton.liveLogin() != null &&
            !PbypWatch.active && !PbypWatch.visible && act?.hasWindowFocus() != false
        if (now != showing) {
            showing = now
            // Cleared on screen too, or the last words come back with the overlay.
            if (!now) { lines.clear(); partial = ""; ccRow = ""; lastText = ""; view?.get()?.text = "" }
            Stt.poke()
        }
        var tv = view?.get()
        if (!now || vid == null || root == null) { tv?.visibility = View.GONE; return }
        if (tv == null || tv.rootView !== root) {
            tv?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val content = root.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
            tv = make(content)
            lastText = ""
            view = WeakReference(tv)
        }
        val cut = SystemClock.uptimeMillis() - LINE_MS
        val before = lines.size
        while (lines.isNotEmpty() && lines.first().at < cut) lines.removeFirst()
        if (lines.size != before) render()
        place(tv, vid)
    }

    private fun make(content: FrameLayout): TextView {
        val ctx = content.context
        fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics).toInt()
        val tv = TextView(ctx).apply {
            tag = TAG
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dp(6f).toFloat() }
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            maxLines = 4
            visibility = View.GONE
            // Taps go through to the player.
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        content.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return tv
    }

    /** Low in the video, centred, never wider than 90% of it. */
    private fun place(tv: TextView, vid: View) {
        if (tv.text.isNullOrEmpty()) { tv.visibility = View.GONE; return }
        val v = IntArray(2); vid.getLocationInWindow(v)
        val p = IntArray(2); (tv.parent as View).getLocationInWindow(p)
        val maxW = (vid.width * 0.9f).toInt()
        if (tv.maxWidth != maxW) tv.maxWidth = maxW
        tv.visibility = View.VISIBLE
        val w = if (tv.width > 0) tv.width else maxW
        val h = if (tv.height > 0) tv.height else 0
        val bottom = vid.height * 0.06f
        tv.x = v[0] - p[0] + (vid.width - w) / 2f
        tv.y = v[1] - p[1] + vid.height - h - bottom
    }

    private fun render() {
        val tv = view?.get() ?: return
        val out = SpannableStringBuilder()
        for (l in lines) {
            if (l.shown.isEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(l.shown)
        }
        val tr = translating()
        if (partial.isNotEmpty() && lines.none { it.text == partial }) {
            if (out.isNotEmpty()) out.append('\n')
            val s = out.length
            out.append(partial)
            // While translating, the words still being heard are the original: shown smaller and grey.
            if (tr) {
                out.setSpan(ForegroundColorSpan(0xFFBFBFC8.toInt()), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.85f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (out.toString() == lastText.toString()) return
        lastText = out
        tv.text = out
        tv.visibility = if (out.isEmpty()) View.GONE else View.VISIBLE
        tv.requestLayout()
    }
}
