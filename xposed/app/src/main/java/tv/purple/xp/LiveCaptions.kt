package tv.purple.xp

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Live captions over the stream, optionally translated into your language.
 *
 * The text comes from the stream's own captions when the streamer sends them, otherwise from
 * speech to text on the phone (the same offline recognizer as the ad break captions, with the
 * speech model of the "Stream language" setting). Translation uses the offline translator and the
 * "Translate into" language; a line already in that language is shown as it is.
 * During a Picture-by-Picture ad break the recognizer goes back to the ad break captions.
 *
 * Two looks ([KEY_STYLE]): a few lines over the video that come and go, each kept on screen for
 * at least [KEY_SECONDS] before the next ones push it out, or a box with every line, which can be
 * scrolled back through. Either can be put anywhere on the screen, over the chat too: the box by
 * its handle, the lines through "Move the live captions" in the menu ([startMove]). The place is
 * kept as a share of the screen, so it fits after a rotation.
 *
 * Nothing leaves the phone.
 */
object LiveCaptions {

    const val KEY = "live_captions"
    const val KEY_TR = "live_captions_translate"
    /** 0 lines over the video, 1 a box with the whole history. */
    const val KEY_STYLE = "live_captions_style"
    /** Seconds each line stays on screen at least, in the lines look. */
    const val KEY_SECONDS = "live_captions_seconds"
    const val DEF_SECONDS = 6
    /**
     * "x,y": where the captions are. x is the box's middle as a share of the screen's width, y its
     * top as a share of the room it can move in. Empty: the default place.
     */
    const val KEY_POS = "live_captions_pos"

    private const val TAG = "ptv_live_captions"
    private const val TICK_MS = 500L
    /** Lines on screen at once in the lines look. */
    private const val KEEP = 2
    /** Lines waiting to come on screen. Past this the oldest one on screen goes early, so the captions never fall far behind. */
    private const val MAX_WAITING = 3
    /** Lines the history box keeps. */
    private const val HISTORY = 300
    private const val CC_HOLD_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("ptv-live-tr").apply { start() }.looper) }

    /** Set on main by the tick, read by Stt on its thread. */
    @Volatile private var showing = false
    @Volatile private var lastCcAt = 0L
    @Volatile private var onCached = false

    /** [shown] is the text to show (the translation once it is in); [since] when it came on screen. */
    private class Line(val text: String, val at: Long, var shown: String, val fromStt: Boolean) {
        var since = 0L
        val ready: Boolean get() = shown.isNotEmpty()
    }

    // Main thread only.
    /** Lines on screen now (lines look). */
    private val lines = ArrayDeque<Line>()
    /** Finished lines waiting for room on screen (lines look). */
    private val waiting = ArrayDeque<Line>()
    /** Every recent line (history box). */
    private val history = ArrayDeque<Line>()
    private var partial = ""
    private var ccRow = ""
    private var installed = false
    private var lastText: CharSequence = ""
    private var builtStyle = -1
    private var moving = false
    /** The channel the history box's lines are from. */
    private var historyOf: String? = null

    // The views: a box (handle row + text), added to the activity's content.
    private var boxRef: WeakReference<LinearLayout>? = null
    private var textRef: WeakReference<TextView>? = null
    private var scrollRef: WeakReference<ScrollView>? = null
    private var handleRef: WeakReference<View>? = null

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

    private fun style() = Settings.getInt(KEY_STYLE, 0).coerceIn(0, 1)

    private fun keepMs() = Settings.getInt(KEY_SECONDS, DEF_SECONDS).coerceIn(2, 60) * 1000L

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
                for (r in rows.dropLast(1)) if (!seen(r)) finish(r, false)
                val last = rows.last()
                if (ccRow.isNotEmpty() && !last.startsWith(ccRow.take(12)) && !seen(ccRow)) finish(ccRow, false)
                ccRow = last
                partial = last
                render()
            }.onFailure { log("LC cc: $it") }
        }
    }

    private fun seen(r: String) = lines.any { it.text == r } || waiting.any { it.text == r } ||
        history.reversed().take(4).any { it.text == r }

    /** Stt thread: a live guess or a finished line. [lang]: the language the model heard, if it says. */
    fun onSttText(text: String, fin: Boolean, lang: String? = null) {
        main.post {
            val t = text.trim().replaceFirstChar { it.uppercase() }
            if (fin) { partial = ""; if (t.isNotEmpty()) finish(t, true, lang) } else partial = t
            render()
        }
    }

    private fun finish(t: String, fromStt: Boolean, heard: String? = null) {
        val l = Line(t, SystemClock.uptimeMillis(), t, fromStt)
        // Speech to text speaks the stream language picked in settings; read it now, on main, so
        // a line already in the queue keeps the language it was heard in. Auto names none: the
        // translator guesses each line's language then.
        val sttLang = if (fromStt) heard ?: SttModels.chosen().sourceLang else null
        waiting.addLast(l)
        history.addLast(l)
        while (history.size > HISTORY) history.removeFirst()
        if (translating()) {
            val to = ChatTranslate.target()
            // A line already in the language to translate into is shown as it is: a streamer who
            // switches to it now and then, or the stream's own captions in it.
            val g = LangGuess.guess(t)
            val same = sttLang == to || (sttLang == null && g.known && g.confidence >= Translator.MIN_CONFIDENCE && g.lang == to) ||
                (heard == null && g.known && g.confidence >= Translator.MIN_CONFIDENCE && g.lang == to)
            if (!same) {
                l.shown = ""
                worker.post {
                    val out = runCatching { Translator.translate(t, sttLang, to) }.getOrNull()
                    main.post { l.shown = out ?: t; pace(); render() }
                }
            }
        }
        pace()
    }

    /**
     * Lines look: brings waiting lines on screen in order. One comes on when there is room, or
     * when the oldest on screen has been up long enough; with too many waiting the oldest goes
     * early, so the captions stay close to what is said.
     */
    private fun pace() {
        val now = SystemClock.uptimeMillis()
        val keep = keepMs()
        while (waiting.isNotEmpty() && waiting.first().ready) {
            val full = lines.size >= KEEP
            if (full) {
                val old = lines.first()
                if (now - old.since < keep && waiting.size <= MAX_WAITING) break
                lines.removeFirst()
            }
            val l = waiting.removeFirst()
            l.since = now
            lines.addLast(l)
        }
        // A line still being translated holds up the ones after it, but never for long.
        val stuck = waiting.firstOrNull()
        if (stuck != null && !stuck.ready && now - stuck.at > STUCK_MS) { stuck.shown = stuck.text; pace() }
    }

    private const val STUCK_MS = 8_000L

    // ------------------------------------------------------------ moving

    /**
     * From the menu: shows the captions with a handle and Done, so they can be dragged anywhere.
     * The menu lives in Twitch's settings, away from the stream, so the move starts the next
     * time the captions are on screen (within [MOVE_ASK_MS]). False when live captions are off.
     */
    fun startMove(): Boolean {
        if (!on()) return false
        moveAsked = SystemClock.uptimeMillis()
        main.post { if (showing) beginMove() }
        return true
    }

    private fun beginMove() {
        moveAsked = 0L
        moving = true
        builtStyle = -1
        render()
    }

    @Volatile private var moveAsked = 0L
    private const val MOVE_ASK_MS = 10 * 60_000L

    /** True while a finger is dragging the box: [place] leaves it where the finger has it. */
    private var dragging = false

    private fun endMove() {
        moving = false
        builtStyle = -1
        render()
    }

    /** Forget the place picked: the captions go back low in the video. */
    fun resetPlace() {
        Settings.setString(KEY_POS, "")
        main.post { builtStyle = -1; render() }
    }

    private fun storedPlace(): Pair<Float, Float>? {
        val s = Settings.getString(KEY_POS, "")
        val p = s.split(',')
        if (p.size != 2) return null
        val x = p[0].toFloatOrNull() ?: return null
        val y = p[1].toFloatOrNull() ?: return null
        return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
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
        if (!onCached && !showing) { boxRef?.get()?.visibility = View.GONE; moving = false; return }
        val act = ViewHider.currentActivity()
        val root = act?.window?.peekDecorView()
        val vid = root?.let { r -> Res.id(r.context, "playback_view_container").takeIf { it != 0 }?.let { r.findViewById<View>(it) } }
        val now = onCached && vid != null && vid.isShown && RefreshButton.liveLogin() != null &&
            !PbypWatch.active && !PbypWatch.visible && act?.hasWindowFocus() != false
        if (now != showing) {
            showing = now
            // Cleared on screen too, or the last words come back with the overlay.
            // The history stays while a dialog or the menu is up; it starts over with another channel.
            if (!now) { lines.clear(); waiting.clear(); partial = ""; ccRow = ""; lastText = ""; moving = false; textRef?.get()?.text = "" }
            Stt.poke()
        }
        if (now && moveAsked != 0L) {
            if (SystemClock.uptimeMillis() - moveAsked < MOVE_ASK_MS) beginMove() else moveAsked = 0L
        }
        val login = RefreshButton.liveLogin()
        if (login != null && login != historyOf) { history.clear(); historyOf = login }
        var box = boxRef?.get()
        if (!now || vid == null || root == null) { box?.visibility = View.GONE; return }
        val content = root.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        if (box == null || box.parent !== content || builtStyle != style()) {
            box?.let { (it.parent as? ViewGroup)?.removeView(it) }
            box = make(content)
            lastText = ""
            boxRef = WeakReference(box)
        }
        // Lines that have been up long enough go, and waiting ones come on.
        val cut = SystemClock.uptimeMillis() - keepMs()
        val before = lines.size
        while (lines.isNotEmpty() && lines.first().since < cut && waiting.isEmpty()) lines.removeFirst()
        pace()
        if (lines.size != before || waiting.isNotEmpty()) render()
        place(box, vid, content)
    }

    private fun dp(v: View, x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, v.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun make(content: FrameLayout): LinearLayout {
        val ctx = content.context
        fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics).toInt()
        val st = style()
        builtStyle = st
        val box = LinearLayout(ctx).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = dp(6f).toFloat() }
            visibility = View.GONE
        }
        // The handle: always there on the box, on the lines only while moving them.
        val handle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(4f), dp(2f))
            visibility = if (st == 1 || moving) View.VISIBLE else View.GONE
        }
        handle.addView(TextView(ctx).apply {
            text = "≡  " + I18n.t(if (moving) "Drag to move" else "Captions")
            setTextColor(0xFFBFBFC8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The label keeps its own width, so a short caption cannot squeeze the chips off the box;
        // in the history box, which is wider, the gap pushes the chips to the right.
        handle.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        if (moving) {
            handle.addView(chip(ctx, "Reset") { Settings.setString(KEY_POS, ""); builtStyle = -1; render() })
            handle.addView(chip(ctx, "Done") { endMove() })
        }
        handle.setOnTouchListener(dragger(box, content))
        box.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        if (st == 1) {
            tv.gravity = Gravity.START
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = true; addView(tv) }
            box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            scrollRef = WeakReference(sv)
        } else {
            tv.gravity = Gravity.CENTER
            tv.maxLines = 4
            // Taps go through to the player, unless the captions are being moved.
            box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            if (moving) tv.setOnTouchListener(dragger(box, content))
            scrollRef = null
        }
        textRef = WeakReference(tv)
        handleRef = WeakReference(handle)
        content.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        render()
        return box
    }

    private fun chip(ctx: android.content.Context, label: String, onTap: () -> Unit) = TextView(ctx).apply {
        val d = { x: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics).toInt() }
        text = I18n.t(label)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(d(10f), d(6f), d(10f), d(6f))
        background = GradientDrawable().apply { setColor(0xFF9147FF.toInt()); cornerRadius = d(12f).toFloat() }
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = d(6f) }
    }

    /** Drags [box] around [content]; the place is kept when the finger lifts. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragger(box: View, content: View) = object : View.OnTouchListener {
        var dx = 0f
        var dy = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; dx = box.x - e.rawX; dy = box.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    box.x = (e.rawX + dx).coerceIn(0f, (content.width - box.width).coerceAtLeast(0).toFloat())
                    box.y = (e.rawY + dy).coerceIn(0f, (content.height - box.height).coerceAtLeast(0).toFloat())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    // Across, the middle of the box is kept, so longer and shorter lines stay
                    // centred where it was dropped; down, its top as a share of the room.
                    val fx = (box.x + box.width / 2f) / content.width.coerceAtLeast(1)
                    val fy = box.y / (content.height - box.height).coerceAtLeast(1)
                    Settings.setString(KEY_POS, String.format(Locale.US, "%.4f,%.4f", fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f)))
                }
            }
            return true
        }
    }

    /**
     * Where the box goes: the place picked (see [KEY_POS]), kept on screen, or else low in the
     * video, centred. The lines are never wider than 90% of the video; the box takes most of
     * the video's width and about a third of the screen's height.
     */
    private fun place(box: LinearLayout, vid: View, content: View) {
        val tv = textRef?.get() ?: return
        val st = builtStyle
        if (st == 0 && !moving && tv.text.isNullOrEmpty()) { box.visibility = View.GONE; return }
        if (st == 1) {
            val w = minOf((content.width * 0.95f).toInt(), maxOf((vid.width * 0.9f).toInt(), dp(box, 280f)))
            val h = (content.height * 0.33f).toInt().coerceIn(dp(box, 120f), dp(box, 360f))
            val lp = box.layoutParams
            if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; box.layoutParams = lp }
        } else {
            val maxW = (vid.width * 0.9f).toInt()
            if (tv.maxWidth != maxW) tv.maxWidth = maxW
        }
        box.visibility = View.VISIBLE
        if (dragging) return
        val w = if (box.width > 0) box.width else box.layoutParams.width.coerceAtLeast(0)
        val h = if (box.height > 0) box.height else 0
        val pos = storedPlace()
        if (pos != null) {
            box.x = (pos.first * content.width - w / 2f).coerceIn(0f, (content.width - w).coerceAtLeast(0).toFloat())
            box.y = pos.second * (content.height - h).coerceAtLeast(0)
            return
        }
        val v = IntArray(2); vid.getLocationInWindow(v)
        val p = IntArray(2); content.getLocationInWindow(p)
        val bottom = vid.height * 0.06f
        box.x = v[0] - p[0] + (vid.width - w) / 2f
        box.y = (v[1] - p[1] + vid.height - h - bottom).coerceAtLeast(0f)
    }

    private fun render() {
        val tv = textRef?.get() ?: return
        val out = SpannableStringBuilder()
        val tr = translating()
        if (builtStyle == 1) {
            for (l in history) {
                if (out.isNotEmpty()) out.append('\n')
                if (!l.ready) { grey(out, l.text); continue }
                out.append(l.shown)
                // Under a translation, the words as they were said, small and grey.
                if (l.shown != l.text) { out.append('\n'); grey(out, l.text) }
            }
        } else {
            for (l in lines) {
                if (!l.ready) continue
                if (out.isNotEmpty()) out.append('\n')
                out.append(l.shown)
            }
            if (moving && out.isEmpty() && partial.isEmpty()) out.append(I18n.t("Captions show here"))
        }
        if (partial.isNotEmpty() && lines.none { it.text == partial } && history.lastOrNull()?.text != partial) {
            if (out.isNotEmpty()) out.append('\n')
            // While translating, the words still being heard are the original: shown smaller and grey.
            if (tr) grey(out, partial) else out.append(partial)
        }
        if (out.toString() == lastText.toString()) return
        lastText = out
        val sv = scrollRef?.get()
        // The history box follows new lines unless it was scrolled back.
        val follow = sv == null || sv.scrollY + sv.height >= tv.height - dp(tv, 24f)
        tv.text = out
        if (builtStyle == 0) boxRef?.get()?.visibility = if (out.isEmpty() && !moving) View.GONE else View.VISIBLE
        tv.requestLayout()
        if (sv != null && follow) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun grey(out: SpannableStringBuilder, s: String) {
        val a = out.length
        out.append(s)
        out.setSpan(ForegroundColorSpan(0xFFBFBFC8.toInt()), a, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.85f), a, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
