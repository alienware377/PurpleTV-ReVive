package tv.purple.xp

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
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
 * its handle, the lines through "Move the live captions" in the menu ([startMove]). The box is
 * resized by its corner grip, the lines (how wide they may get) by theirs while they are moved.
 * The place and size are kept as shares of the screen, so they fit after a rotation.
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
    /**
     * "w,h": how big the captions are. w is the width as a share of the screen's width (the box's
     * width, or the most the lines may take), h the box's height as a share of the screen's
     * height. Empty: the default size. Kept on this phone only (see SettingsSync.local).
     */
    const val KEY_SIZE = "live_captions_size"

    private const val TAG = "ptv_live_captions"
    private const val TICK_MS = 500L
    /** Lines on screen at once in the lines look. */
    private const val KEEP = 2
    /** Rows of text the lines look shows; a note too long for them adds some while it is up (see [render]). */
    private const val LINES = 4
    /** Lines waiting to come on screen. Past this the oldest one on screen goes early, so the captions never fall far behind. */
    private const val MAX_WAITING = 3
    /** Lines the history box keeps. */
    private const val HISTORY = 300
    private const val CC_HOLD_MS = 10_000L
    /** The smallest the box and the lines can be made, in dp. */
    private const val MIN_BOX_W = 200f
    private const val MIN_BOX_H = 96f
    private const val MIN_LINES_W = 120f

    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("ptv-live-tr").apply { start() }.looper) }

    /** Set on main by the tick, read by Stt on its thread. */
    @Volatile private var showing = false
    @Volatile private var lastCcAt = 0L
    @Volatile private var onCached = false

    /**
     * [shown] is the text to show (the translation once it is in); [since] when it came on screen.
     * [text] can still grow by a piece heard after the pause that ended it (see [attach]).
     */
    private class Line(var text: String, val at: Long, var shown: String, val fromStt: Boolean) {
        var since = 0L
        val ready: Boolean get() = shown.isNotEmpty()
        /** The language the translator starts from (null: it guesses). */
        var src: String? = null
        /** Bumped by every translation asked for, so only the newest one is shown. */
        var gen = 0
    }

    // Main thread only.
    /** Lines on screen now (lines look). */
    private val lines = ArrayDeque<Line>()
    /** Finished lines waiting for room on screen (lines look). */
    private val waiting = ArrayDeque<Line>()
    /** The note last put in the box (see [note]). */
    private var holdShown: String? = null
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
    private var handleRef: WeakReference<View>? = null
    /** How wide the handle row is with nothing squeezed: the moved lines are never narrower, or Done would be cut off. */
    private var handleNeeds = 0

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

    /**
     * Stt thread, whole sentence models: a live guess or a finished line. [lang]: the language the
     * model heard, if it says. [cased]: the model writes capitals (see [cap]).
     */
    fun onSttText(text: String, fin: Boolean, lang: String? = null, cased: Boolean = true) {
        main.post {
            val t = cap(text, cased)
            if (fin) { partial = ""; if (t.isNotEmpty()) finish(t, true, lang) } else partial = t
            render()
        }
    }

    /**
     * Stt thread, streaming models: [done] the lines finished now, in order, [join] a piece that
     * goes on the end of the line finished before them (see [attach]), and [now] the words heard
     * after them. All in one go, so no frame shows a finished line gone and the words after it
     * not there yet. [cased]: the model writes capitals (see [cap]).
     */
    fun onSttLines(done: List<String>, join: String, now: String, cased: Boolean = true) {
        main.post {
            if (join.isNotEmpty()) attach(join, cased)
            for (s in done) cap(s, cased).takeIf { it.isNotEmpty() }?.let { finish(it, true) }
            partial = cap(now, cased)
            render()
        }
    }

    /**
     * Speech-to-text text as shown: trimmed, with a capital first letter when the model writes none
     * ([cased] false, see SttModel.cased). A model that writes capitals is left as it is: a long
     * line cut in two goes on in the middle of a sentence there, and its small first letter is right.
     */
    private fun cap(s: String, cased: Boolean): String {
        val t = s.trim()
        return if (cased) t else t.replaceFirstChar { it.uppercase() }
    }

    /**
     * A piece heard after the pause that ended the last line, which belongs on its end: its full
     * stop or question mark, or the rest of its last word. It goes onto that line when speech to
     * text made the newest line, and a translation of it is done again, the old one staying up
     * until the new one is in. With no such line, a piece with letters is a line of its own.
     */
    private fun attach(piece: String, cased: Boolean) {
        val l = history.lastOrNull()?.takeIf { it.fromStt }
        if (l == null) {
            if (piece.any { it.isLetterOrDigit() }) finish(cap(piece, cased), true)
            return
        }
        val was = l.text
        l.text = was + piece
        when {
            // Shown as it was said: not translated, or already in the language to translate into.
            l.shown == was -> l.shown = l.text
            translating() -> translate(l, ChatTranslate.target())
            else -> l.shown = l.text
        }
    }

    private fun finish(t: String, fromStt: Boolean, heard: String? = null) {
        val l = Line(t, SystemClock.uptimeMillis(), t, fromStt)
        // Speech to text speaks the stream language picked in settings, or with Auto the one found
        // for the stream; read it now, on main, so a line already in the queue keeps the language
        // it was heard in. Whisper with no language says each line's own ([heard]); with none at
        // all, the translator guesses.
        val sttLang = if (fromStt) heard ?: SttModels.chosen().sourceLang else null
        l.src = sttLang
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
                translate(l, to)
            }
        }
        pace()
    }

    /** [l]'s text into [to] on the worker. A line asked for again shows only the newest answer. */
    private fun translate(l: Line, to: String) {
        val g = ++l.gen
        val t = l.text
        val from = l.src
        worker.post {
            val out = runCatching { Translator.translate(t, from, to) }.getOrNull()
            main.post { if (l.gen == g) { l.shown = out ?: t; pace(); render() } }
        }
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

    /** The history box shows its last line, so it scrolls on with new ones. */
    private var following = true

    /** True while a finger is dragging or resizing the box: [place] leaves it as the finger has it. */
    private var dragging = false

    private fun endMove() {
        moving = false
        builtStyle = -1
        render()
    }

    /** Forget the place and size picked: the captions go back low in the video, at their first size. */
    fun resetPlace() {
        Settings.setString(KEY_POS, "")
        Settings.setString(KEY_SIZE, "")
        main.post { builtStyle = -1; render() }
    }

    private fun storedPlace(): Pair<Float, Float>? = pair(KEY_POS)?.let { it.first.coerceIn(0f, 1f) to it.second.coerceIn(0f, 1f) }

    private fun storedSize(): Pair<Float, Float>? = pair(KEY_SIZE)?.takeIf { it.first > 0f && it.second > 0f }

    private fun pair(key: String): Pair<Float, Float>? {
        val p = Settings.getString(key, "").split(',')
        if (p.size != 2) return null
        val x = p[0].toFloatOrNull() ?: return null
        val y = p[1].toFloatOrNull() ?: return null
        return x to y
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
        // Twitch's player stays "shown" under the two-streams screen, only paused there, so it
        // has nothing to caption and the box would sit on top of that screen.
        val now = onCached && vid != null && vid.isShown && RefreshButton.liveLogin() != null &&
            !PbypWatch.active && !PbypWatch.visible && act?.hasWindowFocus() != false && !TwoStreams.isOpen()
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
        if (lines.size != before || waiting.isNotEmpty() || lastText.isEmpty() || note() != holdShown) render()
        place(box, vid, content)
    }

    private fun dp(v: View, x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, v.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun make(content: FrameLayout): LinearLayout {
        val ctx = content.context
        fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics).toInt()
        val st = style()
        builtStyle = st
        // A drag on the box this replaces is over, even without its finger lifting.
        dragging = false
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
            handle.addView(chip(ctx, "Reset") { Settings.setString(KEY_POS, ""); Settings.setString(KEY_SIZE, ""); builtStyle = -1; render() })
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
        // The text with the corner grip over it: always on the box, on the lines only while moving them.
        val body = FrameLayout(ctx)
        if (st == 1) {
            tv.gravity = Gravity.START
            // No smooth scrolling: a line that came in while it slid would not be followed.
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = true; isSmoothScrollingEnabled = false; addView(tv) }
            // The box follows new lines unless it was scrolled back. Whether it shows its last
            // line is only looked at when it scrolls, so text that grows (a new line, or a
            // narrower box wrapping into more lines) is followed when the last line was in view.
            following = true
            sv.setOnScrollChangeListener { _, _, y, _, _ -> following = y + sv.height >= tv.height - dp(24f) }
            val follow = View.OnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
                if (following && b - t != ob - ot) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            }
            tv.addOnLayoutChangeListener(follow)
            sv.addOnLayoutChangeListener(follow)
            body.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            box.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            tv.gravity = Gravity.CENTER
            tv.maxLines = LINES
            // Taps go through to the player, unless the captions are being moved.
            // The lines set the box's width (the handle stretches to it), so it keeps its own.
            body.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            if (moving) tv.setOnTouchListener(dragger(box, content))
        }
        if (st == 1 || moving) {
            body.addView(grip(ctx).apply { setOnTouchListener(resizer(box, content, tv)) },
                FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.BOTTOM or Gravity.END))
        }
        textRef = WeakReference(tv)
        handleRef = WeakReference(handle)
        handle.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        handleNeeds = if (handle.visibility == View.VISIBLE) handle.measuredWidth else 0
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
                    keepPlace(box.x, box.y, box.width, box.height, content)
                }
            }
            return true
        }
    }

    /**
     * Across, the middle of the box is kept, so longer and shorter lines stay centred where it was
     * dropped; down, its top as a share of the room.
     */
    private fun keepPlace(x: Float, y: Float, w: Int, h: Int, content: View) {
        val fx = (x + w / 2f) / content.width.coerceAtLeast(1)
        val fy = y / (content.height - h).coerceAtLeast(1)
        Settings.setString(KEY_POS, String.format(Locale.US, "%.4f,%.4f", fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f)))
    }

    /** The corner grip: three short slanted strokes in the bottom corner, with room around them for a thumb. */
    private fun grip(ctx: android.content.Context): View = object : View(ctx) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBFBFC8.toInt()
            strokeWidth = 1.5f * d
            strokeCap = Paint.Cap.ROUND
        }
        init { contentDescription = I18n.t("Drag to resize") }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            // At the screen's edge the swipe would be the system's back gesture instead.
            if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
        override fun onDraw(c: Canvas) {
            val r = width - 5f * d
            val b = height - 5f * d
            for (k in 1..3) c.drawLine(r - 4f * d * k, b, r, b - 4f * d * k, paint)
        }
    }

    /**
     * Resizes [box] from its bottom corner while its top stays put, never past the screen's edge;
     * the lines only get wider or narrower. The size is kept when the finger lifts, and the place
     * with it, so the box stays where it is seen even when it was in its default place.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun resizer(box: LinearLayout, content: View, tv: TextView) = object : View.OnTouchListener {
        var x0 = 0f
        var y0 = 0f
        var w0 = 0
        var h0 = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lines = builtStyle == 0
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; x0 = e.rawX; y0 = e.rawY; w0 = box.width; h0 = box.height }
                MotionEvent.ACTION_MOVE -> {
                    val least = if (lines) maxOf(dp(box, MIN_LINES_W), handleNeeds) else dp(box, MIN_BOX_W)
                    val w = fit((w0 + e.rawX - x0).toInt(), least, (content.width - box.x).toInt())
                    if (lines) {
                        tv.maxWidth = w
                        tv.minWidth = w
                    } else {
                        val h = fit((h0 + e.rawY - y0).toInt(), dp(box, MIN_BOX_H), (content.height - box.y).toInt())
                        val lp = box.layoutParams
                        lp.width = w
                        lp.height = h
                        box.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val cw = content.width.coerceAtLeast(1).toFloat()
                    val ch = content.height.coerceAtLeast(1).toFloat()
                    val w = if (lines) tv.maxWidth else box.layoutParams.width
                    // The lines have no height of their own to keep: the box's stays as it was.
                    val h = if (lines) ((storedSize()?.second ?: (defaultBox(content).second / ch)) * ch).toInt() else box.layoutParams.height
                    if (w > 0 && h > 0) Settings.setString(KEY_SIZE, String.format(Locale.US, "%.4f,%.4f", w / cw, h / ch))
                    if (lines) {
                        // The handle may be wider than the lines now: the box is as wide as the wider.
                        val any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        box.measure(View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.AT_MOST), any)
                        keepPlace(box.x, box.y, box.measuredWidth, box.measuredHeight, content)
                    } else keepPlace(box.x, box.y, w, h, content)
                }
            }
            return true
        }
    }

    /** [v] kept between [min] and [max]; [max] wins on a screen too small for [min]. */
    private fun fit(v: Int, min: Int, max: Int) = if (min >= max) max.coerceAtLeast(0) else v.coerceIn(min, max)

    /**
     * The box's size before it is resized: most of the video's width and about a third of the
     * screen's height on a phone; on a big screen (a tablet or an unfolded Fold) about half the
     * width and a fifth of the height, since lines that long are hard to read.
     */
    private fun defaultBox(content: View, vid: View? = null): Pair<Int, Int> {
        val big = content.resources.configuration.smallestScreenWidthDp >= 600
        val vw = vid?.width ?: content.width
        val w = if (big) minOf((content.width * 0.55f).toInt(), dp(content, 420f)).coerceAtLeast(dp(content, 280f))
            else minOf((content.width * 0.95f).toInt(), maxOf((vw * 0.9f).toInt(), dp(content, 280f)))
        val h = if (big) (content.height * 0.22f).toInt().coerceIn(dp(content, 120f), dp(content, 220f))
            else (content.height * 0.33f).toInt().coerceIn(dp(content, 120f), dp(content, 360f))
        return w.coerceAtMost(content.width) to h.coerceAtMost(content.height)
    }

    /** The box's size: the one picked (see [KEY_SIZE]), never smaller than the least or bigger than the screen. */
    private fun boxSize(vid: View, content: View): Pair<Int, Int> {
        val s = storedSize() ?: return defaultBox(content, vid)
        return fit((s.first * content.width).toInt(), dp(content, MIN_BOX_W), content.width) to
            fit((s.second * content.height).toInt(), dp(content, MIN_BOX_H), content.height)
    }

    /** The most the lines may take across: the width picked, or else 90% of the video. */
    private fun linesWidth(vid: View, content: View): Int {
        val s = storedSize() ?: return (vid.width * 0.9f).toInt()
        return fit((s.first * content.width).toInt(), dp(content, MIN_LINES_W), content.width)
    }

    /**
     * Where the box goes: the place picked (see [KEY_POS]), kept on screen, or else low in the
     * video, centred. Its size is the one picked (see [KEY_SIZE]) or else [defaultBox]; the lines
     * are never wider than the width picked or else 90% of the video, and while they are moved
     * they show that whole width, so the grip has something to resize.
     */
    private fun place(box: LinearLayout, vid: View, content: View) {
        val tv = textRef?.get() ?: return
        val st = builtStyle
        if (st == 0 && !moving && tv.text.isNullOrEmpty()) { box.visibility = View.GONE; return }
        box.visibility = View.VISIBLE
        if (dragging) return
        if (st == 1) {
            val (w, h) = boxSize(vid, content)
            val lp = box.layoutParams
            if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; box.layoutParams = lp }
        } else {
            val maxW = linesWidth(vid, content).let { if (moving) maxOf(it, handleNeeds).coerceAtMost(content.width) else it }
            if (tv.maxWidth != maxW) tv.maxWidth = maxW
            val minW = if (moving) maxW else 0
            if (tv.minWidth != minW) tv.minWidth = minW
        }
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
        // Speech to text held back (heat or memory) or not downloaded: say so, or the box just goes quiet.
        val n = note()
        holdShown = n
        val noteText = n?.let { I18n.t(it) }
        if (builtStyle == 1) {
            for (l in history) {
                if (out.isNotEmpty()) out.append('\n')
                if (!l.ready) { grey(out, l.text); continue }
                out.append(l.shown)
                // Under a translation, the words as they were said, small and grey.
                if (l.shown != l.text) { out.append('\n'); grey(out, l.text) }
            }
        } else {
            // The note takes the rows it needs as it is drawn, small and grey, often two or three.
            val noteRows = noteText?.let { s -> maxOf(1, rows(tv, SpannableStringBuilder().also { grey(it, s) })) } ?: 0
            // A note too long for the rows there are makes the box taller while it is up, with a
            // row left for the captions, rather than being cut off before it says where to download.
            val maxLines = maxOf(LINES, noteRows + 1)
            if (tv.maxLines != maxLines) tv.maxLines = maxLines
            linesLook(tv, out, tr, maxLines - noteRows)
            if (moving && out.isEmpty()) out.append(I18n.t("Captions show here"))
        }
        if (builtStyle == 1 && livePartial()) {
            if (out.isNotEmpty()) out.append('\n')
            // While translating, the words still being heard are the original: shown smaller and grey.
            if (tr) grey(out, partial) else out.append(partial)
        }
        noteText?.let { if (out.isNotEmpty()) out.append('\n'); grey(out, it) }
        if (out.toString() == lastText.toString()) return
        lastText = out
        tv.text = out
        if (builtStyle == 0) boxRef?.get()?.visibility = if (out.isEmpty() && !moving) View.GONE else View.VISIBLE
        tv.requestLayout()
    }

    /** The partial line, unless it is a finished line already (a stream's own captions repeat their rows). */
    private fun livePartial() = partial.isNotEmpty() && lines.none { it.text == partial } && history.lastOrNull()?.text != partial

    /**
     * The lines look: the lines on screen, and under them what is still being said: finished lines
     * not on screen yet (waiting for room, or for their translation) and the partial line, as they
     * looked while being said, so a line that is finished never blinks out and back in. When it
     * all takes more rows than [room] (the captions' rows less the note's), the oldest lines on
     * screen give way first, so the newest words always stay in view.
     */
    private fun linesLook(tv: TextView, out: SpannableStringBuilder, tr: Boolean, room: Int) {
        val said = waiting.mapTo(ArrayList()) { it.text }
        if (livePartial()) said.add(partial)
        var live = SpeechLines.tail(SpeechLines.glue(said), LIVE_CHARS)
        val up = lines.filter { it.ready }.map { it.shown }
        var skip = 0
        while (true) {
            out.clear()
            out.clearSpans()
            for (k in skip until up.size) { if (out.isNotEmpty()) out.append('\n'); out.append(up[k]) }
            if (live.isNotEmpty()) {
                if (out.isNotEmpty()) out.append('\n')
                // While translating, the words still being heard are the original: shown smaller and grey.
                if (tr) grey(out, live) else out.append(live)
            }
            if (rows(tv, out) <= room) return
            if (skip < up.size) skip++
            else if (live.length > MIN_LIVE) live = SpeechLines.tail(live, live.length * 2 / 3)
            else return
        }
    }

    /** The most characters of what is still being said that the lines look shows (its newest ones). */
    private const val LIVE_CHARS = 160
    /** To fit the rows, it is never cut shorter than this. */
    private const val MIN_LIVE = 40

    /** Rows [s] takes at the width the lines may take now; 0 while that is not known yet. */
    private fun rows(tv: TextView, s: CharSequence): Int {
        val max = tv.maxWidth
        if (s.isEmpty() || max <= 0 || max == Int.MAX_VALUE) return 0
        val w = max - tv.compoundPaddingLeft - tv.compoundPaddingRight
        if (w <= 0) return 0
        return StaticLayout.Builder.obtain(s, 0, s.length, tv.paint, w)
            .setIncludePad(tv.includeFontPadding)
            .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
            .setBreakStrategy(tv.breakStrategy)
            .setHyphenationFrequency(tv.hyphenationFrequency)
            .build().lineCount
    }

    /**
     * The grey note under the captions: speech to text is held back, or its downloads are missing,
     * or (Auto, for a few seconds) the stream's language has a better model to download. None of
     * the download notes while the stream sends its own captions, which need nothing downloaded.
     * The better model's note is only asked for while the captions are on screen, since asking
     * starts its few seconds (see StreamLang.tip).
     */
    private fun note(): String? = Stt.holdNote()
        ?: textRef?.get()?.context?.takeIf { !ccRecent() }?.let { Stt.setupNote(it) ?: (if (showing) StreamLang.tip() else null) }

    private fun grey(out: SpannableStringBuilder, s: String) {
        val a = out.length
        out.append(s)
        out.setSpan(ForegroundColorSpan(0xFFBFBFC8.toInt()), a, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.85f), a, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
