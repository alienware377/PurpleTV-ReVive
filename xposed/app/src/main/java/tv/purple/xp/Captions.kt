package tv.purple.xp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * Live captions for the small live picture Twitch shows beside a Picture-by-Picture (PbyP) ad, and
 * reading those captions aloud.
 *
 *  - CaptionHub collects the text: Twitch's own closed captions from the PbyP player, or speech to
 *    text (Stt) when the stream has none. PbypPreview draws the box; ReadAloud gets finished lines.
 *  - ReadAloud speaks finished lines with an offline voice of the phone, only while the PbyP break
 *    is on screen, and never touches Twitch's sound, the ad or the phone's volume.
 */

/** Log lines that can come in bursts, at most [perMinute] a minute, plus "first time" lines. */
private class LogLimit(private val perMinute: Int) {
    private var window = 0L
    private var count = 0
    private val fired = HashSet<String>()

    @Synchronized fun line(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - window > 60_000) { window = now; count = 0 }
        if (count++ < perMinute) log(msg)
    }

    /** Once per key for the whole session, so a device test shows which path actually ran. */
    fun first(key: String, msg: String) {
        val isNew = synchronized(fired) { fired.add(key) }
        if (isNew) log(msg)
    }
}

private val WS = Regex("\\s+")

/**
 * "Is an ad on right now", and toasts that respect it. A toast sits at the bottom centre of the
 * screen, which in landscape is over the ad video or its info row, and module UI must never cover
 * an ad. So while any ad is on, a message waits and is shown once the ad is over (looked at every
 * few seconds, given up after ten minutes). Used by speech to text, read-aloud and the downloads.
 */
internal object AdTime {
    private const val CHECK_MS = 3_000L
    private const val GIVE_UP_MS = 10 * 60_000L
    /** At most this many messages wait; Android shows them one after another. */
    private const val KEEP = 3

    private val main = Handler(Looper.getMainLooper())
    // Main thread only.
    private val waiting = ArrayList<String>()
    private var since = 0L

    /** A PbyP break, a display ad, or an ad window AdVolume has open. Any thread. */
    fun now(): Boolean =
        PbypWatch.active || PbypWatch.visible || runCatching { ChatTransparency.adActive() }.getOrDefault(false)

    /** Show [msg] now, or as soon as no ad is on. Any thread. */
    fun toast(msg: String) {
        main.post {
            try {
                if (waiting.isEmpty() && !now()) {
                    // The shared helper (EntryHook.kt), not this object's own toast.
                    Settings.ctx()?.let { tv.purple.xp.toast(it, msg) }
                    return@post
                }
                if (msg !in waiting) waiting.add(msg)
                while (waiting.size > KEEP) waiting.removeAt(0)
                log("toast waits until the ad is over: $msg")
                if (since == 0L) {
                    since = SystemClock.uptimeMillis()
                    main.postDelayed(check, CHECK_MS)
                }
            } catch (t: Throwable) {
                log("ad-safe toast failed: $t")
            }
        }
    }

    private val check: Runnable = object : Runnable {
        override fun run() {
            try {
                if (waiting.isEmpty()) { since = 0L; return }
                if (now()) {
                    if (SystemClock.uptimeMillis() - since < GIVE_UP_MS) { main.postDelayed(this, CHECK_MS); return }
                    log("waiting toasts dropped: the ad state never ended")
                } else {
                    val ctx = Settings.ctx()
                    if (ctx != null) for (m in waiting) tv.purple.xp.toast(ctx, m)
                }
                waiting.clear()
                since = 0L
            } catch (t: Throwable) {
                waiting.clear()
                since = 0L
                log("ad-safe toast check failed: $t")
            }
        }
    }
}

/**
 * The caption text for the PbyP break, from two sources:
 *  - Twitch's own closed captions, from the muted PbyP player only. IVS hands every player's timed
 *    metadata to CorePlayerImpl.handleMetadata, a readable name; the PbyP player's caption JSON is
 *    read there, the same {"caption":{"text":...}} Twitch reads for its main player (aoa.j in
 *    31.7.0, tja.i in 31.1.2). Twitch never shows the PbyP player's captions itself, so nothing is
 *    doubled, and the main player's captions are never read.
 *  - Speech to text ([onSttText], from Stt), used only while the stream sends no captions.
 *
 * Twitch's captions have no "finished" signal, so a caption line counts as finished when it
 * scrolls off or has not changed for [CC_SETTLE_MS]. Finished lines go to [ReadAloud] once.
 *
 * All state lives on the main thread. The IVS hook only checks the player, copies a small string
 * and hands it over; the box is told about changes at most four times a second.
 */
object CaptionHub {

    /** The caption box under the live preview during PbyP breaks. Boolean, default on. */
    const val KEY = "pbyp_captions"

    /** A line leaves the box this long after its last change. */
    private const val LINE_MS = 7_000L
    /** A Twitch caption line that has not changed for this long counts as finished. */
    private const val CC_SETTLE_MS = 1_200L
    /** After a Twitch caption, speech to text stays out this long: the stream has real captions. */
    private const val CC_HOLD_MS = 10_000L
    /** At most four box updates a second. */
    private const val PUBLISH_MS = 250L
    /** The text goes this long after the break ends, so it does not vanish mid-word. */
    private const val CLEAR_AFTER_MS = 2_000L
    /** Largest metadata read. IVS caption JSON is a few hundred bytes; bigger is something else. */
    private const val CC_CAP = 8 * 1024
    /** Finished lines kept, for the box and for spotting repeats. */
    private const val KEEP = 6

    /** Characters per box line before wrapping. Set by the box from its width. */
    @Volatile var lineChars = 42
        private set
    /** Box lines shown, newest at the bottom: two finished lines plus the one being spoken. */
    @Volatile var maxLines = 3
        private set

    /** What the box shows now ("" = nothing), newest line last, lines split by '\n'. */
    @Volatile var shown = ""
        private set
    /** False while the last line is still a live speech-to-text guess (draw it dimmer). */
    @Volatile var shownFinal = true
        private set

    private class Line(val text: String, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(String, Boolean) -> Unit>()
    private val logs = LogLimit(30)

    // ---- main thread only ----

    private val lines = ArrayDeque<Line>()
    /** The Twitch caption line on screen now, which may still grow word by word. */
    private var cur = ""
    private var curAt = 0L
    /** How much of [cur] has already gone to read-aloud. */
    private var curSpoken = 0
    /** The live speech-to-text guess, replaced as it improves. */
    private var partial = ""
    private var partialAt = 0L
    private var wasActive = false
    private var pubPending = false
    private var lastPub = 0L

    /** Last Twitch caption, from any thread. Speech to text stays out while it is recent. */
    @Volatile private var lastCcAt = 0L
    @Volatile private var installed = false
    /** Set when the caption hook threw: Twitch captions stay off for this session. */
    @Volatile private var ccOff = false

    // ---- the public side ----

    /**
     * Called on the main thread whenever the box should change. [text] is the whole box content
     * right now ("" means show nothing), newest line last, lines split by '\n' and already wrapped
     * to [lineChars]. [isFinal] is false while the last line is a live speech-to-text guess.
     */
    fun addListener(l: (text: String, isFinal: Boolean) -> Unit) { listeners.addIfAbsent(l) }

    fun removeListener(l: (text: String, isFinal: Boolean) -> Unit) { listeners.remove(l) }

    /** A caption line from Twitch (already taken out of its JSON). Any thread. */
    fun onTwitchCaption(text: String) {
        lastCcAt = SystemClock.uptimeMillis()
        onMain("cc") { cc(text) }
    }

    /** Speech-to-text output: a live guess ([isFinal] false) or a finished line. Any thread. */
    fun onSttText(text: String, isFinal: Boolean) {
        onMain("stt") { stt(text, isFinal) }
    }

    /** Empty the box and forget everything (for example when a break ends). Any thread. */
    fun clear() {
        onMain("clear") { reset(); emit() }
    }

    /** True while the stream sends its own captions, so speech to text can rest. Any thread. */
    fun ccRecent(): Boolean {
        val t = lastCcAt
        return t != 0L && SystemClock.uptimeMillis() - t < CC_HOLD_MS
    }

    /** True when anyone wants caption text: the box, or reading aloud. Speech to text can check it. */
    fun wanted(): Boolean {
        if (!Settings.isReady()) return false
        val r = Settings.rev
        if (r != wRev) {
            wCached = Settings.get(KEY, true) || ReadAloud.on()
            wRev = r
        }
        return wCached
    }

    @Volatile private var wRev = Int.MIN_VALUE
    @Volatile private var wCached = true

    /**
     * The box's own size: [chars] per line and [lines] lines. Long lines are wrapped here so the
     * newest words always land on the last line, whatever the box does with its own wrapping.
     */
    fun layout(chars: Int, count: Int) {
        val c = chars.coerceIn(16, 80)
        val n = count.coerceIn(1, 6)
        if (c == lineChars && n == maxLines) return
        lineChars = c
        maxLines = n
        onMain("layout") { publish() }
    }

    /**
     * The same from the box's width and text size in pixels: an average character of a sans-serif
     * face is about half its size, so this errs towards shorter lines, never towards clipping.
     */
    fun layoutForBox(widthPx: Int, textSizePx: Float, count: Int) {
        if (widthPx <= 0 || textSizePx <= 0f) return
        layout((widthPx / (textSizePx * 0.55f)).toInt(), count)
    }

    // ---- install: Twitch's captions from the PbyP player ----

    fun install(lp: LoadPackageParam) {
        if (installed) return
        installed = true
        runCatching { PbypWatch.addListener { onMain("pbyp") { onPbyp() } } }
            .onFailure { log("CH PbyP listener failed: $it") }
        val core = runCatching { lp.classLoader.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull()
        if (core == null) { log("CH IVS player not found: Twitch's own captions are not read"); return }
        var n = 0
        for (m in core.declaredMethods) {
            if (m.name != "handleMetadata" || Modifier.isNative(m.modifiers)) continue
            val pt = m.parameterTypes
            if (pt.size != 2 || pt[0] != String::class.java || pt[1] != ByteBuffer::class.java) continue
            if (runCatching { XposedBridge.hookMethod(m, metaHook) }.isSuccess) n++
        }
        log("CH hook CorePlayerImpl.handleMetadata: $n")
    }

    /**
     * Every IVS player's metadata passes here, on IVS's own thread: compare the player first, so
     * the main player and every other one return at once. Read from a duplicate, because IVS
     * copies the original buffer right after us and its position must not move.
     */
    private val metaHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            if (ccOff) return
            try {
                val me = p.thisObject ?: return
                if (me !== PbypWatch.player()) return
                if (p.args.getOrNull(0) != "text/json") return
                val buf = p.args.getOrNull(1) as? ByteBuffer ?: return
                val s = captionJson(buf) ?: return
                if (!wanted()) return
                lastCcAt = SystemClock.uptimeMillis()
                logs.first("cc", "CH first Twitch caption from the PbyP player")
                main.post { safely("cc") { ccJson(s) } }
            } catch (t: Throwable) {
                ccOff = true
                log("CH caption hook failed, Twitch captions are off until Twitch restarts: $t")
            }
        }
    }

    private val NEEDLE = "\"caption\"".toByteArray(Charsets.US_ASCII)

    /** The metadata as text when it is small and names a caption; null for everything else. */
    private fun captionJson(buf: ByteBuffer): String? {
        val d = buf.duplicate()
        val from = d.position()
        val to = d.limit()
        val n = to - from
        if (n < NEEDLE.size || n > CC_CAP) return null
        // A byte scan first, so most metadata (ad cues, stream source tags) costs no copy at all.
        var found = false
        var i = from
        val last = to - NEEDLE.size
        while (i <= last && !found) {
            if (d.get(i) == NEEDLE[0]) {
                var k = 1
                while (k < NEEDLE.size && d.get(i + k) == NEEDLE[k]) k++
                found = k == NEEDLE.size
            }
            i++
        }
        if (!found) return null
        val bytes = ByteArray(n)
        d.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** The same reading Twitch does: caption.text when both are there, nothing otherwise. */
    private fun ccJson(s: String) {
        val c = runCatching { JSONObject(s).optJSONObject("caption") }.getOrNull() ?: return
        if (!c.has("text") || c.isNull("text")) return
        cc(c.optString("text"))
    }

    // ---- main thread: the text itself ----

    private fun onPbyp() {
        val a = PbypWatch.active
        if (a == wasActive) return
        wasActive = a
        main.removeCallbacks(clearTask)
        // A new break starts with an empty box, and without the last break's caption hold.
        if (a) { reset(); emit() } else main.postDelayed(clearTask, CLEAR_AFTER_MS)
    }

    private val clearTask = Runnable { safely("clear") { if (!PbypWatch.active) { reset(); emit() } } }

    /**
     * One Twitch caption event. Roll-up captions send the whole visible text each time, the
     * newest row last: rows above the last have scrolled up and are finished, and the last row
     * may still grow. A row already shown, or a shorter copy of one, is not shown twice.
     */
    private fun cc(raw: String) {
        if (!wanted()) return
        val now = SystemClock.uptimeMillis()
        lastCcAt = now
        // The stream's own captions win over a speech-to-text guess.
        partial = ""
        val rows = raw.split('\n').map { tidy(it) }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) {
            // The captions were wiped: whatever was on screen is finished.
            commitCur()
            publish()
            return
        }
        // A new caption that does not carry on the line on screen (pop-on captions replace the
        // whole text): that line is finished first, so lines are read in the order they came.
        if (cur.isNotEmpty() && rows.none { related(it, cur) }) commitCur()
        for (r in rows.dropLast(1)) {
            if (cur.isNotEmpty() && related(r, cur)) {
                if (r.length > cur.length) cur = r
                commitCur()
            } else if (!seen(r)) commit(r, now, speak = true)
        }
        val last = rows.last()
        when {
            cur.isEmpty() -> if (!seen(last)) startCur(last, now)
            last.equals(cur, ignoreCase = true) -> {}
            last.startsWith(cur, ignoreCase = true) -> { cur = last; curAt = now; settleLater() }
            cur.startsWith(last, ignoreCase = true) -> {}
            else -> { commitCur(); if (!seen(last)) startCur(last, now) }
        }
        publish()
    }

    private fun related(a: String, b: String) =
        a.equals(b, ignoreCase = true) || a.startsWith(b, ignoreCase = true) || b.startsWith(a, ignoreCase = true)

    private fun startCur(t: String, now: Long) {
        cur = t
        curAt = now
        curSpoken = 0
        settleLater()
    }

    /** The line on screen is finished: keep it in the box and send what was not read yet. */
    private fun commitCur() {
        val c = cur
        if (c.isEmpty()) return
        val rest = c.substring(curSpoken.coerceIn(0, c.length)).trim()
        cur = ""
        curSpoken = 0
        main.removeCallbacks(settleTask)
        if (seen(c)) return
        commit(c, curAt, speak = false)
        if (rest.isNotEmpty()) ReadAloud.onFinal(rest)
    }

    private fun settleLater() {
        main.removeCallbacks(settleTask)
        main.postDelayed(settleTask, CC_SETTLE_MS)
    }

    /** The line on screen has not changed for a while: read it now rather than at scroll time. */
    private val settleTask = Runnable {
        safely("settle") {
            val c = cur
            if (c.isEmpty() || curSpoken >= c.length) return@safely
            val wait = curAt + CC_SETTLE_MS - SystemClock.uptimeMillis()
            if (wait > 50) { main.postDelayed(settleTask0(), wait); return@safely }
            val rest = c.substring(curSpoken).trim()
            curSpoken = c.length
            if (rest.isNotEmpty()) ReadAloud.onFinal(rest)
        }
    }

    /** The settle task itself, for re-posting from inside its own body. */
    private fun settleTask0(): Runnable = settleTask

    private fun stt(text: String, final: Boolean) {
        if (!wanted()) return
        val now = SystemClock.uptimeMillis()
        if (ccRecent()) {
            if (partial.isNotEmpty()) { partial = ""; publish() }
            return
        }
        val t = tidy(text)
        if (!final) {
            if (t != partial) { partial = t; partialAt = now; publish() }
            return
        }
        partial = ""
        if (t.isNotEmpty() && !filler(t) && !seen(t)) commit(t, now, speak = true)
        publish()
    }

    private fun commit(t: String, at: Long, speak: Boolean) {
        lines.addLast(Line(t, at))
        while (lines.size > KEEP) lines.removeFirst()
        if (speak) ReadAloud.onFinal(t)
    }

    /** Already in one of the last few lines (roll-up captions repeat rows as they scroll). */
    private fun seen(r: String): Boolean {
        var k = 0
        for (i in lines.indices.reversed()) {
            val t = lines[i].text
            if (t.equals(r, ignoreCase = true) || (r.length >= 8 && t.contains(r, ignoreCase = true))) return true
            if (++k >= 3) break
        }
        return false
    }

    private fun reset() {
        lines.clear()
        cur = ""; curAt = 0L; curSpoken = 0
        partial = ""; partialAt = 0L
        lastCcAt = 0L
        main.removeCallbacks(settleTask)
        main.removeCallbacks(expireTask)
        main.removeCallbacks(clearTask)
    }

    // ---- main thread: telling the box ----

    private fun publish() {
        if (pubPending) return
        pubPending = true
        val wait = (lastPub + PUBLISH_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        main.postDelayed(pubTask, wait)
    }

    private val pubTask = Runnable {
        pubPending = false
        lastPub = SystemClock.uptimeMillis()
        safely("publish") { emit() }
    }

    private val expireTask = Runnable { safely("expire") { publish() } }

    private fun emit() {
        val now = SystemClock.uptimeMillis()
        val rows = ArrayList<String>()
        val n = lineChars
        for (l in lines) if (now - l.at < LINE_MS) rows.addAll(wrap(l.text, n))
        var live = false
        if (cur.isNotEmpty() && now - curAt < LINE_MS) rows.addAll(wrap(cur, n))
        else if (partial.isNotEmpty() && now - partialAt < LINE_MS) { rows.addAll(wrap(partial, n)); live = true }
        val m = maxLines
        val text = rows.subList((rows.size - m).coerceAtLeast(0), rows.size).joinToString("\n")
        val fin = !live

        // Wake up again when the next line is due to leave the box.
        var next = Long.MAX_VALUE
        for (l in lines) { val e = l.at + LINE_MS; if (e in (now + 1) until next) next = e }
        if (cur.isNotEmpty()) { val e = curAt + LINE_MS; if (e in (now + 1) until next) next = e }
        if (partial.isNotEmpty()) { val e = partialAt + LINE_MS; if (e in (now + 1) until next) next = e }
        main.removeCallbacks(expireTask)
        if (next != Long.MAX_VALUE) main.postDelayed(expireTask, next - now + 30)

        if (text == shown && fin == shownFinal) return
        shown = text
        shownFinal = fin
        for (l in listeners) {
            try { l(text, fin) } catch (t: Throwable) { logs.line("CH box listener failed: $t") }
        }
    }

    // ---- text tidying ----

    /** Short words captions spell in capitals on purpose, kept that way when all-caps text is tidied. */
    private val ACRONYMS = setOf(
        "GG", "GGS", "LOL", "OK", "TV", "PC", "FPS", "NPC", "NPCS", "MVP", "HP", "XP", "DM", "DMS", "VOD",
        "VODS", "AFK", "BRB", "USA", "UK", "PS5", "GPU", "CPU", "RPG", "MMO", "DLC", "IRL", "OMG", "ASAP",
        "VR", "UI", "TTS", "DJ", "NBA", "NFL", "FBI"
    )

    private val FILLERS = setOf("uh", "um", "uhm", "umm", "hmm", "hm", "mm", "mhm", "ah", "er", "erm", "huh", "uh huh", "oh")

    /**
     * Whitespace collapsed, and all-capital text (usual for broadcast captions) or all-lowercase
     * text (some speech models) put in sentence case, so it reads like normal writing.
     */
    internal fun tidy(raw: String): String {
        val s = WS.replace(raw, " ").trim()
        if (s.isEmpty()) return s
        val lower = s.any { it.isLowerCase() }
        val upper = s.any { it.isUpperCase() }
        return when {
            upper && !lower && s.count { it.isLetter() } >= 2 -> sentenceCase(s, keepAcronyms = true)
            lower && !upper -> sentenceCase(s, keepAcronyms = false)
            else -> s
        }
    }

    private fun sentenceCase(s: String, keepAcronyms: Boolean): String {
        val out = StringBuilder(s.length)
        var start = true
        for ((k, w) in s.split(' ').withIndex()) {
            if (k > 0) out.append(' ')
            val core = w.trim { !it.isLetterOrDigit() && it != '\'' && it != '\u2019' }
            var t = if (keepAcronyms && core.uppercase() in ACRONYMS) w else w.lowercase()
            val lc = core.lowercase()
            if (start || lc == "i" || lc.startsWith("i'") || lc.startsWith("i\u2019")) t = capFirst(t)
            out.append(t)
            val end = w.lastOrNull { it.isLetterOrDigit() || it in ".!?" }
            if (end != null) start = end in ".!?"
        }
        return out.toString()
    }

    private fun capFirst(w: String): String {
        val i = w.indexOfFirst { it.isLetter() }
        if (i < 0) return w
        return w.substring(0, i) + w[i].uppercaseChar() + w.substring(i + 1)
    }

    private fun filler(t: String): Boolean =
        t.lowercase().filter { it.isLetter() || it == ' ' }.trim() in FILLERS

    /** Word wrap to [n] characters; a word longer than a line is split. */
    private fun wrap(t: String, n: Int): List<String> {
        if (t.length <= n) return listOf(t)
        val out = ArrayList<String>()
        val line = StringBuilder()
        for (w0 in t.split(' ')) {
            var w = w0
            while (w.length > n) {
                if (line.isNotEmpty()) { out.add(line.toString()); line.setLength(0) }
                out.add(w.substring(0, n))
                w = w.substring(n)
            }
            when {
                w.isEmpty() -> {}
                line.isEmpty() -> line.append(w)
                line.length + 1 + w.length <= n -> line.append(' ').append(w)
                else -> { out.add(line.toString()); line.setLength(0); line.append(w) }
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return out
    }

    // ---- helpers ----

    private fun onMain(what: String, block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) safely(what, block)
        else main.post { safely(what, block) }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { logs.line("CH $what failed: $t") }
    }

    // ---- test commands (DevBridge) ----

    /**
     * op=cc   --es json J            J goes through the same parser as the PbyP player's metadata
     * op=cc   --es text T            the same, with T wrapped as {"caption":{"text":T}}
     * op=stt  --es text T [--ez final true|false]   T as speech-to-text output (final by default)
     * op=demo [--es src stt]         12 sample events 1.5 s apart, as roll-up captions or as STT
     * op=clear, op=status
     * Without op: json means cc, demo=1 means demo, text means stt (or cc with --es src cc).
     * None of these needs a real break; the box shows them only while PbypPreview shows one.
     */
    fun dev(i: Intent) {
        val text = i.getStringExtra("text")
        val json = i.getStringExtra("json")
        val src = i.getStringExtra("src")
        val op = i.getStringExtra("op") ?: when {
            json != null -> "cc"
            i.getIntExtra("demo", 0) == 1 -> "demo"
            text != null -> if (src == "cc") "cc" else "stt"
            else -> "status"
        }
        when (op) {
            "cc" -> {
                val j = json ?: JSONObject().put("caption", JSONObject().put("text", text.orEmpty())).toString()
                lastCcAt = SystemClock.uptimeMillis()
                onMain("dev cc") { ccJson(j) }
            }
            "stt" -> onSttText(text.orEmpty(), i.getBooleanExtra("final", !i.getBooleanExtra("partial", false)))
            "clear" -> clear()
            "demo" -> demo(src == "stt")
            "status" -> onMain("status") { status() }
            else -> log("CH dev: unknown op '$op' (cc, stt, demo, clear or status)")
        }
    }

    private val DEMO_CC = listOf(
        "ALL RIGHT", "ALL RIGHT, WE ARE BACK", "ALL RIGHT, WE ARE BACK\nSO WHERE WAS I",
        "SO WHERE WAS I\nOH RIGHT, THE BOSS FIGHT", "OH RIGHT, THE BOSS FIGHT\nI THINK WE NEED MORE POTIONS",
        "I THINK WE NEED MORE POTIONS\nLET ME CHECK THE SHOP", ">> CHAT SAYS GO LEFT",
        ">> CHAT SAYS GO LEFT\nOKAY, LEFT IT IS", "[LAUGHTER]", "THAT WAS CLOSE",
        "THAT WAS CLOSE\nTHANKS FOR THE FOLLOW, BY THE WAY", ""
    )

    private val DEMO_STT = listOf(
        "so where" to false, "so where was i" to true, "oh right the" to false, "oh right the boss fight" to true,
        "um" to true, "i think we need" to false, "i think we need more potions" to true, "let me check" to false,
        "let me check the shop" to true, "okay left it is" to true, "that was close" to true,
        "thanks for the follow by the way" to true
    )

    private fun demo(stt: Boolean) {
        log("CH dev demo (${if (stt) "speech to text" else "Twitch captions"})")
        for (k in 0 until 12) {
            main.postDelayed({
                safely("demo") {
                    if (stt) DEMO_STT[k].let { (t, f) -> stt(t, f) }
                    else { lastCcAt = SystemClock.uptimeMillis(); cc(DEMO_CC[k]) }
                }
            }, k * 1_500L)
        }
    }

    private fun status() {
        val now = SystemClock.uptimeMillis()
        log("CH status key=${Settings.get(KEY, true)} wanted=${wanted()} pbyp=${PbypWatch.active}/${PbypWatch.visible} " +
            "ccOff=$ccOff ccRecent=${ccRecent()} lines=${lines.size} cur='$cur' spoken=$curSpoken " +
            "partial='$partial' layout=${lineChars}x$maxLines listeners=${listeners.size} " +
            "shown='${shown.replace('\n', '|')}' final=$shownFinal ages=${lines.joinToString { ((now - it.at) / 1000).toString() }}")
    }
}

/**
 * Reads finished caption lines aloud during PbyP breaks, with an offline voice of the phone: the
 * phone's default text-to-speech engine, or one the user picked (eSpeak NG, RHVoice and so on).
 * No online voice is ever used.
 *
 * When it speaks: only while read-aloud is on, the PbyP break is active and on screen, Twitch is
 * in the foreground and not in picture-in-picture. It stops at once when any of that ends (a
 * 200 ms check backs up the signals), so it never talks after the ad. The ad itself keeps playing
 * in full: nothing here touches Twitch's players, its mute, audio focus or the phone's volume.
 *
 * How it sounds: TextToSpeech only renders each line to a file (synthesizeToFile), never speak(),
 * so the engine plays nothing itself and can not take audio focus. Our own MediaPlayer plays the
 * file as media speech at its own level ([KEY_VOLUME]). MediaPlayer requests no audio focus, so
 * the ad is never paused or ducked, and its sound is mixed in the media server, where AudioFilters
 * and AdVolume (which work on Twitch's in-app tracks) never see it. Two files take turns, so the
 * next line can be rendered while the last one plays.
 *
 * Keeping up with live: at most two lines wait; a waiting line older than 4 s is dropped, short
 * lines that arrive close together are joined, and a line said just now is not said again.
 *
 * Why the extra hooks: Twitch's manifest does not ask to see text-to-speech engines, so on
 * Android 11 and later the framework's own engine checks (TtsEngines) find nothing and
 * TextToSpeech gives up before binding, although the system service would bind the engine for
 * Twitch just fine. Those checks are answered "yes" for the one engine we ask for, and only on our
 * own "ptv-tts" thread, by thread identity, which also covers TextToSpeech reconnecting later.
 *
 * Threads: everything runs on "ptv-tts", made on first use and ended about 60 s after the last
 * break, together with the engine (the system keeps a bound engine at top priority).
 */
object ReadAloud {

    /** Int: 0 off, 1 on. Default 0. */
    const val KEY = "read_aloud"
    /** String: the engine's package, "" for the phone's default engine. */
    const val KEY_ENGINE = "read_aloud_engine"
    /** Int 10..100: our player's level in percent, against Twitch's own sound. Default 70. */
    const val KEY_VOLUME = "read_aloud_volume"
    /** Int 50..200: speaking speed in percent. 100 (the default) keeps the engine's own speed. */
    const val KEY_RATE = "read_aloud_rate"
    const val DEF_VOLUME = 70
    const val DEF_RATE = 100

    /** What the "Test voice" button says. */
    const val SAMPLE = "This is how the live captions sound when they are read aloud."

    /** Offline engines worth offering when installed, by package, with their names. */
    private val KNOWN = linkedMapOf(
        "com.reecedunn.espeak" to "eSpeak NG",
        "com.github.olga_yakovleva.rhvoice.android" to "RHVoice",
        "com.svox.pico" to "Pico TTS",
        "es.codefactory.eloquencetts" to "Eloquence",
        "com.google.android.tts" to "Speech Services by Google",
        "com.samsung.SMT" to "Samsung text-to-speech"
    )

    private const val MAX_CHARS = 200
    /** A waiting line older than this is dropped: live has moved on. */
    private const val STALE_MS = 4_000L
    /** A rendered line older than this is not played any more. */
    private const val PLAY_STALE_MS = 7_000L
    /** Lines this close together are joined when one of them is short. */
    private const val MERGE_MS = 1_500L
    private const val SHORT_WORDS = 4
    private const val QUEUE_MAX = 2
    /** The engine and our thread end this long after the last break. */
    private const val SHUTDOWN_MS = 60_000L
    private const val INIT_TIMEOUT_MS = 8_000L
    private const val SYNTH_TIMEOUT_MS = 10_000L
    /** While speaking, the gate is checked this often, whatever the signals say. */
    private const val GUARD_MS = 200L
    /** Engine checks for the list are repeated at most this often. */
    private const val PROBE_EVERY_MS = 10 * 60_000L
    private const val DAY_MS = 24 * 3600_000L
    /** This phone's engine checks. Not part of the synced settings, because engines differ per phone. */
    private const val PREFS = "ptv_tts"
    private const val DIR = "ptv_tts"

    private val PKG = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
    private val TAGS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")

    private val logs = LogLimit(30)

    // ---- the thread ----

    private val lock = Any()
    @Volatile private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    /** Our thread, for the engine-visibility hooks and for dropping work after a shutdown. */
    @Volatile private var ttsThread: Thread? = null

    // ---- gates, from any thread ----

    @Volatile private var installed = false
    @Volatile private var started = 0
    @Volatile private var pip = false
    @Volatile private var busy = false
    @Volatile private var lastActive = false
    @Volatile private var lastShown = false
    @Volatile private var state = "Off"
    /** Bumped by every stop, so a callback for older work can tell it is no longer wanted. */
    private val gen = AtomicInteger()

    @Volatile private var onRev = Int.MIN_VALUE
    @Volatile private var onCached = false

    /** Read-aloud switched on. Cheap: re-read only after a settings write. */
    fun on(): Boolean {
        if (!Settings.isReady()) return false
        val r = Settings.rev
        if (r != onRev) {
            onCached = Settings.getInt(KEY, 0) == 1
            onRev = r
        }
        return onCached
    }

    /** A short line for the menu: off, starting, ready with which voice, or why there is none. */
    fun status(): String = if (!on()) "Off" else state

    // ---- engine scope for the visibility hooks (set on ptv-tts right before each engine call) ----

    private class Attempt(val pkg: String?, val redirect: Boolean) {
        override fun toString() = (pkg ?: "Android's own choice") + if (redirect) " as default" else " by name"
    }

    /** The engine our hooks let through, and whether "the default engine" is answered with it. */
    @Volatile private var target: String? = null
    @Volatile private var redirect = false

    private fun scope(a: Attempt?) {
        target = a?.pkg
        redirect = a?.redirect == true
    }

    // ---- ptv-tts only ----

    private class Item(var text: String, val at: Long, var last: Long, val force: Boolean)
    private class Synth(val id: String, val file: File, val item: Item, val gen: Int)
    private class Ready(val file: File, val item: Item)
    private class Playing(val mp: MediaPlayer, val file: File, val item: Item)

    private val queue = ArrayDeque<Item>()
    private var synth: Synth? = null
    private var ready: Ready? = null
    private var playing: Playing? = null
    private var tts: TextToSpeech? = null
    private var mainAttempt: Attempt? = null
    private var engine = ""
    private var bootChosen = ""
    private var boot: Boot? = null
    private var gaveUp = false
    private var restarts = 0
    private var lastSpoken = ""
    private var seq = 0
    private var session = 0
    private var guarding = false
    private var probing: Boot? = null
    private val probeQueue = ArrayDeque<String>()
    @Volatile private var lastProbe = 0L
    private val toasted = HashSet<String>()

    // ---- install ----

    /**
     * The engine-visibility hooks (framework methods only Twitch's process sees, acting only on
     * our own thread), the break and activity signals. Done once at start-up, never during
     * playback. Nothing is created until a line is to be read or the voice is tested.
     */
    fun install() {
        if (installed) return
        installed = true
        runCatching { hookVisibility() }.onFailure { log("RA visibility hooks failed: $it") }
        runCatching { PbypWatch.addListener { onPbyp() } }.onFailure { log("RA PbyP listener failed: $it") }
        runCatching { watchActivities() }.onFailure { log("RA activity hooks failed: $it") }
        state = "Starts with the next ad break"
        log("RA read aloud installed, setting ${if (on()) "on" else "off"}")
    }

    /** The menu changed a read-aloud setting: switch off, pick up a new engine or speed. */
    fun refresh() {
        if (!on()) {
            stopAll("read aloud switched off")
            post(create = false) { shutdownNow("switched off") }
            return
        }
        post(create = false) {
            gaveUp = false
            val chosen = chosenEngine()
            val t = tts
            if ((t != null || boot != null) && chosen != bootChosen) {
                stopNow()
                closeTts()
                log("RA voice choice changed, the new one starts with the next line")
            } else if (t != null) {
                mainScope()
                runCatching { t.setSpeechRate(rate() / 100f) }
            }
        }
    }

    /** "Test voice" in the menu: says [text] now, whatever the break is doing. */
    fun test(text: String = SAMPLE) {
        val t = speakable(text) ?: return
        val at = SystemClock.uptimeMillis()
        post {
            gaveUp = false
            cancelShutdown()
            offer(t, at, force = true)
        }
    }

    /** Stop speaking at once and drop what waits. Any thread. */
    fun stopAll(why: String) {
        gen.incrementAndGet()
        post(create = false, front = true) {
            val was = busy
            stopNow()
            if (was) log("RA stopped: $why")
            idle()
        }
    }

    /** A finished caption line, from CaptionHub on the main thread. */
    internal fun onFinal(text: String) {
        if (!on() || !PbypWatch.active || !PbypWatch.visible || !fg() || pip) return
        val t = speakable(text) ?: return
        val at = SystemClock.uptimeMillis()
        post { offer(t, at, force = false) }
    }

    private fun fg() = started > 0

    private fun allowed() = on() && fg() && !pip && PbypWatch.active && PbypWatch.visible

    private fun why(): String = when {
        !on() -> "read aloud switched off"
        !fg() -> "Twitch went to the background"
        pip -> "picture in picture"
        !PbypWatch.active -> "the break ended"
        else -> "the live preview is hidden"
    }

    // ---- break and activity signals ----

    @Synchronized private fun onPbyp() {
        try {
            val a = PbypWatch.active
            val shown = a && PbypWatch.visible
            val began = a && !lastActive
            val ended = !a && lastActive
            val appeared = shown && !lastShown
            val gone = !shown && lastShown
            lastActive = a
            lastShown = shown
            if (ended || gone || (!shown && busy)) stopAll(if (!a) "the break ended" else "the live preview is hidden")
            // An engine check that is still going starts other apps' engines: not while the ad plays.
            if (began) post(create = false) { stopProbes("a break started") }
            if (a) {
                cancelShutdown()
                // Start the engine as the break shows, so the first line is not late.
                // A voice that failed last time gets one new try per break.
                if (appeared && on() && fg() && !pip) post {
                    gaveUp = false
                    // The break may be over by the time this runs: then the shutdown it set up
                    // stays, or is set up again, so the engine never stays bound after it.
                    if (!allowed()) { idle(); return@post }
                    cancelShutdown()
                    ensureTts()
                }
            } else if (ended) scheduleShutdown()
        } catch (t: Throwable) {
            logs.line("RA break signal failed: $t")
        }
    }

    /**
     * Foreground and picture-in-picture, from Activity itself: hooked at start-up, so the count is
     * right from the first screen. Every Twitch activity calls through to these.
     */
    private fun watchActivities() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    val a = p.thisObject as? Activity ?: return
                    when (p.method.name) {
                        "onStart" -> started++
                        "onResume" -> { if (started <= 0) started = 1; pip = false }
                        "onPause" -> if (a.isInPictureInPictureMode) { pip = true; stopAll("picture in picture") }
                        "onStop" -> {
                            started = (started - 1).coerceAtLeast(0)
                            if (started == 0) { pip = false; stopAll("Twitch went to the background") }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        var n = 0
        for (name in arrayOf("onStart", "onResume", "onPause", "onStop"))
            n += runCatching { XposedBridge.hookAllMethods(Activity::class.java, name, hook).size }.getOrDefault(0)
        log("RA activity hooks: $n")
    }

    // ---- engine visibility hooks ----

    private fun onOurThread() = Thread.currentThread() === ttsThread

    /** TtsEngines.isEngineInstalled(String): yes for the engine we asked for. */
    private val installedHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try {
                if (!onOurThread()) return
                val t = target ?: return
                if (p.result != true && p.args.getOrNull(0) == t) {
                    p.result = true
                    logs.first("installed", "RA visibility hook fired: isEngineInstalled($t)")
                }
            } catch (_: Throwable) {}
        }
    }

    /** TtsEngines.getEngineInfo(String): an entry for the engine we asked for, when none is seen. */
    private val infoHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try {
                if (!onOurThread()) return
                val t = target ?: return
                if (p.result == null && p.args.getOrNull(0) == t) {
                    p.result = TextToSpeech.EngineInfo().apply { name = t; label = KNOWN[t] ?: t }
                    logs.first("info", "RA visibility hook fired: getEngineInfo($t)")
                }
            } catch (_: Throwable) {}
        }
    }

    /** getDefaultEngine (TextToSpeech's and TtsEngines'): the engine we asked for, when redirecting. */
    private val defaultHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try {
                if (!onOurThread() || !redirect) return
                val t = target ?: return
                if (p.result != t) {
                    p.result = t
                    logs.first("default", "RA visibility hook fired: getDefaultEngine -> $t")
                }
            } catch (_: Throwable) {}
        }
    }

    private fun hookVisibility() {
        val te = runCatching { Class.forName("android.speech.tts.TtsEngines") }.getOrNull()
        val tc = TextToSpeech::class.java
        val counts = ArrayList<String>()
        if (te != null) {
            counts += "isEngineInstalled=" + hookNamed(te, "isEngineInstalled", 1, installedHook)
            counts += "getEngineInfo=" + hookNamed(te, "getEngineInfo", 1, infoHook)
            counts += "TtsEngines.getDefaultEngine=" + hookNamed(te, "getDefaultEngine", 0, defaultHook)
        } else counts += "TtsEngines missing"
        counts += "TextToSpeech.getDefaultEngine=" + hookNamed(tc, "getDefaultEngine", 0, defaultHook)
        // These callers are tiny and live in the boot image, where a compiled caller may have the
        // callee inlined past our hook. Running them interpreted keeps the calls real.
        val deopt = XposedBridge::class.java.methods.firstOrNull { it.name == "deoptimizeMethod" && it.parameterTypes.size == 1 }
        val d = if (deopt == null) "not available in this Xposed" else listOfNotNull(
            runCatching { tc.getDeclaredMethod("initTts") }.getOrNull(),
            runCatching { tc.getDeclaredMethod("getDefaultEngine") }.getOrNull(),
            te?.let { c -> runCatching { c.getDeclaredMethod("getDefaultEngine") }.getOrNull() }
        ).count { m -> runCatching { deopt.invoke(null, m) }.isSuccess }.toString()
        log("RA voice visibility hooks: ${counts.joinToString()}; deoptimized $d")
    }

    private fun hookNamed(c: Class<*>, name: String, params: Int, h: XC_MethodHook): Int =
        c.declaredMethods
            .filter {
                it.name == name && it.parameterTypes.size == params && !Modifier.isAbstract(it.modifiers) &&
                    (params == 0 || it.parameterTypes[0] == String::class.java)
            }
            .count { runCatching { XposedBridge.hookMethod(it, h) }.isSuccess }

    private val mCurrentEngine: Method? by lazy {
        runCatching { TextToSpeech::class.java.getMethod("getCurrentEngine") }.getOrNull()
    }

    /** The engine a TextToSpeech actually bound, when Android lets us read it. */
    private fun currentEngine(t: TextToSpeech): String? =
        runCatching { mCurrentEngine?.invoke(t) as? String }.getOrNull()

    // ---- engines for the menu ----

    /**
     * The voices to offer, package to name, the phone's default first ("" to "Phone's default
     * voice (its name)"). Engines Android lets Twitch see, the phone's default engine, the one
     * picked before, and known offline engines confirmed by an earlier check. Twitch can not list
     * engines itself on Android 11 and later, so known offline engines are checked in the
     * background by starting each once; call again later (or reopen the list) to see them.
     */
    fun engines(ctx: Context): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val def = defaultEngine(ctx)
        val visible = visibleEngines(ctx)
        fun name(p: String) = visible[p] ?: KNOWN[p] ?: p
        out[""] = "Phone's default voice" + (def?.let { " (${name(it)})" } ?: "")
        for ((p, l) in visible) out[p] = l
        if (def != null && def !in out) out[def] = name(def)
        val checked = probeResults(ctx)
        for (p in KNOWN.keys) if (checked[p] == true && p !in out) out[p] = name(p)
        val chosen = chosenEngine()
        if (chosen.isNotEmpty() && chosen !in out) out[chosen] = name(chosen) + if (checked[chosen] == false) " (not found)" else ""
        probeSoon(ctx.applicationContext ?: ctx, force = false)
        return out.toList()
    }

    private fun defaultEngine(ctx: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, "tts_default_synth")
    }.getOrNull()?.trim()?.takeIf { PKG.matches(it) }

    @Suppress("DEPRECATION")
    private fun visibleEngines(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, String>()
        val list = runCatching { pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0) }.getOrNull().orEmpty()
        for (ri in list) {
            val pkg = ri.serviceInfo?.packageName ?: continue
            out[pkg] = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: KNOWN[pkg] ?: pkg
        }
        return out
    }

    private fun chosenEngine(): String = Settings.getString(KEY_ENGINE, "").trim().takeIf { PKG.matches(it) } ?: ""

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun probeResults(ctx: Context): Map<String, Boolean> = runCatching {
        prefs(ctx).all.mapNotNull { (k, v) ->
            if (k.startsWith("probe:") && v is String) k.removePrefix("probe:") to v.startsWith("1") else null
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun remember(ctx: Context, pkg: String, ok: Boolean) {
        runCatching { prefs(ctx).edit().putString("probe:$pkg", (if (ok) "1:" else "0:") + System.currentTimeMillis()).apply() }
    }

    /** Not checked yet, or the answer is old: missing engines every 10 minutes, found ones daily. */
    private fun needsCheck(ctx: Context, pkg: String): Boolean {
        val v = runCatching { prefs(ctx).getString("probe:$pkg", null) }.getOrNull() ?: return true
        val ok = v.startsWith("1")
        val age = System.currentTimeMillis() - (v.substringAfter(':').toLongOrNull() ?: 0L)
        return age < 0 || age > (if (ok) DAY_MS else PROBE_EVERY_MS)
    }

    private fun probeSoon(ctx: Context, force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && lastProbe != 0L && now - lastProbe < PROBE_EVERY_MS) return
        lastProbe = now
        post { startProbe(ctx, force) }
    }

    private fun startProbe(ctx: Context, force: Boolean) {
        if (probing != null || probeQueue.isNotEmpty()) return
        // Never start other apps' engines during a break: the ad is playing.
        if (PbypWatch.active) { lastProbe = 0L; log("RA engine check put off: a break is on"); return }
        val def = defaultEngine(ctx)
        val visible = visibleEngines(ctx).keys
        for (p in KNOWN.keys) if (p != def && p !in visible && (force || needsCheck(ctx, p))) probeQueue.addLast(p)
        if (probeQueue.isNotEmpty()) log("RA checking engines: ${probeQueue.joinToString()}")
        probeNext(ctx)
    }

    /** Ptv-tts: end the engine checks for now. They run again the next time the list is opened. */
    private fun stopProbes(why: String) {
        if (probing == null && probeQueue.isEmpty()) return
        probing?.cancel()
        probing = null
        probeQueue.clear()
        lastProbe = 0L
        log("RA engine check stopped: $why")
    }

    /** One engine at a time: start it by name, see whether that very engine answered, close it. */
    private fun probeNext(ctx: Context) {
        // Never start other apps' engines during a break: the ad is playing.
        if (PbypWatch.active) {
            probing = null
            stopProbes("a break started")
            return
        }
        val pkg = probeQueue.removeFirstOrNull()
        if (pkg == null) { probing = null; idle(); return }
        val b = Boot(ctx, listOf(Attempt(pkg, false), Attempt(pkg, true)), "check $pkg") { t, got, _ ->
            val ok = t != null && got == pkg
            if (t != null) runCatching { t.shutdown() }
            remember(ctx, pkg, ok)
            log("RA engine check $pkg: ${if (ok) "installed" else "not found"}")
            probing = null
            probeNext(ctx)
        }
        probing = b
        b.start()
    }

    // ---- starting a TextToSpeech ----

    /**
     * Starts a TextToSpeech, trying each [Attempt] in turn, on ptv-tts. "By name" passes the
     * package to the constructor (needs the isEngineInstalled / getEngineInfo hooks); "as default"
     * passes none and answers getDefaultEngine with it (needs that hook). Either alone is enough,
     * so a phone where one of them is inlined or hidden still works. An engine that refused or is
     * missing is not tried again in the other way. Success counts only when that engine answered.
     */
    private class Boot(
        private val ctx: Context,
        list: List<Attempt>,
        private val why: String,
        private val done: (TextToSpeech?, String?, Attempt?) -> Unit
    ) {
        private val left = ArrayDeque(list)
        private var tts: TextToSpeech? = null
        private var cur: Attempt? = null
        private var token = 0
        private var over = false

        fun start() = next()

        fun cancel() {
            over = true
            token++
            close()
        }

        private fun next() {
            close()
            val a = left.removeFirstOrNull()
            if (a == null) { finish(null, null, null); return }
            cur = a
            val tk = ++token
            scope(a)
            var inCtor = true
            val l = TextToSpeech.OnInitListener { st ->
                // A failure reported from inside the constructor means our hooks did not get the
                // engine through; a later one means the engine itself refused or is not there.
                val sync = inCtor && Thread.currentThread() === ttsThread
                post(create = false) { result(tk, st, sync) }
            }
            val t = try {
                if (a.pkg == null || a.redirect) TextToSpeech(ctx, l) else TextToSpeech(ctx, l, a.pkg)
            } catch (e: Throwable) {
                log("RA $why: could not create a voice ($a): $e")
                null
            }
            inCtor = false
            tts = t
            if (t == null) { post(create = false) { result(tk, TextToSpeech.ERROR, true) }; return }
            handler?.postDelayed({
                if (Thread.currentThread() === ttsThread) {
                    try { result(tk, TextToSpeech.ERROR, false) } catch (e: Throwable) { log("RA $why: timeout step failed: $e") }
                }
            }, INIT_TIMEOUT_MS)
        }

        private fun result(tk: Int, st: Int, sync: Boolean) {
            if (over || tk != token) return
            token++
            val a = cur ?: return
            val t = tts
            if (st == TextToSpeech.SUCCESS && t != null) {
                val got = currentEngine(t) ?: a.pkg
                if (a.pkg != null && got != a.pkg && left.isNotEmpty()) {
                    log("RA $why: asked for $a, Android bound $got instead; trying the next way")
                    next()
                    return
                }
                tts = null
                finish(t, got, a)
                return
            }
            log("RA $why: $a did not start" + if (sync) " (not let through)" else " (the engine refused, is missing or did not answer)")
            if (!sync && a.pkg != null) left.removeAll { it.pkg == a.pkg }
            next()
        }

        private fun close() {
            val t = tts ?: return
            tts = null
            scope(cur)
            runCatching { t.shutdown() }
        }

        private fun finish(t: TextToSpeech?, got: String?, a: Attempt?) {
            over = true
            done(t, got, a)
        }
    }

    private fun mainScope() = scope(mainAttempt)

    /** True when a voice is ready; otherwise starts one (once) and the queue waits for it. */
    private fun ensureTts(): Boolean {
        if (tts != null) return true
        if (boot != null || gaveUp) return false
        val ctx = Settings.ctx() ?: return false
        val chosen = chosenEngine()
        val def = defaultEngine(ctx)
        val list = ArrayList<Attempt>()
        if (chosen.isNotEmpty()) { list += Attempt(chosen, false); list += Attempt(chosen, true) }
        if (def != null && def != chosen) list += Attempt(def, true)
        // Android's own pick: only works where the engines are visible to Twitch.
        list += Attempt(null, false)
        bootChosen = chosen
        state = "Starting the voice"
        val b = Boot(ctx, list, "voice") { t, got, a -> booted(t, got, a, chosen) }
        boot = b
        b.start()
        return false
    }

    private fun booted(t: TextToSpeech?, got: String?, a: Attempt?, chosen: String) {
        boot = null
        if (t == null || a == null) {
            gaveUp = true
            queue.clear()
            state = "No voice could be started on this phone"
            log("RA no voice could be started")
            toastOnce("none", "Read aloud: no voice could be started on this phone.")
            idle()
            return
        }
        tts = t
        mainAttempt = a
        engine = got ?: a.pkg.orEmpty()
        configure(t)
        Settings.ctx()?.let { c -> if (engine in KNOWN) remember(c, engine, true) }
        if (chosen.isNotEmpty() && engine != chosen)
            toastOnce("fallback", "Read aloud: the chosen voice is not available, so the phone's default voice is used.")
        state = "Ready: " + (KNOWN[engine] ?: engine.ifEmpty { "phone's default voice" })
        log("RA voice ready: engine=${engine.ifEmpty { "?" }} ($a)")
        pump()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { post(create = false) { synthDone(utteranceId, true) } }
        @Deprecated("Android's older error callback; the one with a code below is the same for us")
        override fun onError(utteranceId: String?) { post(create = false) { synthDone(utteranceId, false) } }
        override fun onError(utteranceId: String?, errorCode: Int) { post(create = false) { synthDone(utteranceId, false) } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { post(create = false) { synthDone(utteranceId, false) } }
    }

    private fun configure(t: TextToSpeech) {
        mainScope()
        runCatching { t.setOnUtteranceProgressListener(progress) }
        val r = rate()
        if (r != 100) runCatching { t.setSpeechRate(r / 100f) }
        runCatching { pickVoice(t) }.onFailure { log("RA voice choice failed: $it") }
    }

    /**
     * Offline only: keep the engine's voice when it runs on the phone and speaks English (the
     * captions are English: the speech model is, and so are nearly all Twitch captions); otherwise
     * take an installed English voice that needs no network, nearest the phone's own country.
     */
    private fun pickVoice(t: TextToSpeech) {
        fun local(v: Voice) = !v.isNetworkConnectionRequired &&
            v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        val cur = runCatching { t.voice }.getOrNull()
        if (cur != null && local(cur) && cur.locale.language == "en") {
            log("RA voice ${cur.name} (${cur.locale}), the engine's own")
            return
        }
        val all = runCatching { t.voices }.getOrNull().orEmpty().filter { local(it) }
        val country = Locale.getDefault().country
        val pick = all.filter { it.locale.language == "en" }
            .maxWithOrNull(compareBy<Voice>({ it.locale.country == country }, { it.locale.country == "US" }, { it.quality }, { -it.latency }))
            ?: if (cur != null && local(cur)) null
            else all.filter { it.locale.language == (cur?.locale?.language ?: Locale.getDefault().language) }.maxByOrNull { it.quality }
        if (pick == null) {
            log("RA voice ${cur?.name ?: "?"} kept: no other offline voice found (${all.size} offline voices)")
            return
        }
        val r = runCatching { t.setVoice(pick) }.getOrDefault(TextToSpeech.ERROR)
        log("RA voice ${pick.name} (${pick.locale}) chosen, result $r")
    }

    private fun rate() = Settings.getInt(KEY_RATE, DEF_RATE).coerceIn(50, 200)

    private fun volume() = Settings.getInt(KEY_VOLUME, DEF_VOLUME).coerceIn(10, 100) / 100f

    private fun closeTts() {
        boot?.cancel()
        boot = null
        val t = tts ?: return
        tts = null
        mainScope()
        runCatching { t.setOnUtteranceProgressListener(null) }
        runCatching { t.shutdown() }
        mainAttempt = null
    }

    // ---- the queue ----

    private fun offer(text: String, at: Long, force: Boolean) {
        // The gate first: a line that arrives after the break ended must not cancel the shutdown
        // the end of the break set up, or the engine would stay bound until the next break.
        if (!force && !allowed()) { idle(); return }
        cancelShutdown()
        if (text.equals(lastSpoken, ignoreCase = true) || queue.any { it.text.equals(text, ignoreCase = true) }) return
        val last = queue.lastOrNull()
        if (last != null && last.force == force && at - last.last < MERGE_MS &&
            (words(last.text) < SHORT_WORDS || words(text) < SHORT_WORDS) &&
            last.text.length + 1 + text.length <= MAX_CHARS
        ) {
            last.text = last.text + " " + text
            last.last = at
        } else {
            queue.addLast(Item(text, at, at, force))
            // When reading falls behind, the older line goes, so what is said stays near live.
            while (queue.size > QUEUE_MAX) queue.removeFirst()
        }
        pump()
    }

    private fun pump() {
        val now = SystemClock.uptimeMillis()
        val ok = allowed()
        val it = queue.iterator()
        while (it.hasNext()) {
            val i = it.next()
            if (!i.force && (!ok || now - i.at > STALE_MS)) it.remove()
        }
        if (queue.isNotEmpty() && tts == null && gaveUp) queue.clear()
        if (playing == null) ready?.let { r -> ready = null; play(r) }
        while (synth == null && ready == null && queue.isNotEmpty() && ensureTts()) {
            val n = queue.removeFirst()
            // A single word on its own (a "yeah", a name) is not worth the interruption.
            if (!n.force && words(n.text) < 2) continue
            startSynth(n)
            // Started: done for now. Refused: the voice was closed, and the next round of the
            // loop starts it again for the lines still waiting.
            if (synth != null) break
        }
        busy = playing != null || synth != null || ready != null || queue.isNotEmpty()
        if (busy) armGuard() else idle()
    }

    private fun startSynth(item: Item) {
        val t = tts ?: return
        val ctx = Settings.ctx() ?: return
        val dir = File(ctx.cacheDir, DIR)
        if (!dir.isDirectory) dir.mkdirs()
        // Two files take turns: the one playing is never overwritten by the next line.
        val busyFile = playing?.file
        val f = listOf(File(dir, "a.wav"), File(dir, "b.wav")).first { it != busyFile }
        val id = "ptv-ra-" + (++seq)
        mainScope()
        val r = runCatching { t.synthesizeToFile(item.text, params(), f, id) }.getOrDefault(TextToSpeech.ERROR)
        if (r != TextToSpeech.SUCCESS) {
            logs.line("RA the engine refused a line ($r), starting it again")
            closeTts()
            if (++restarts >= 3) { gaveUp = true; state = "The voice keeps failing; it is tried again next break" }
            return
        }
        lastSpoken = item.text
        val s = Synth(id, f, item, gen.get())
        synth = s
        handler?.postDelayed({
            if (Thread.currentThread() === ttsThread && synth === s) safely("synth timeout") {
                logs.line("RA a line took too long to render, skipped")
                synth = null
                f.delete()
                mainScope()
                runCatching { tts?.stop() }
                pump()
            }
        }, SYNTH_TIMEOUT_MS)
    }

    @Suppress("DEPRECATION")
    private fun params() = Bundle().apply {
        // Offline only: ask for the engine's on-phone synthesis, never a network voice.
        putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
        putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
    }

    private fun synthDone(id: String?, ok: Boolean) {
        val s = synth ?: return
        if (s.id != id) return
        synth = null
        if (!ok || s.gen != gen.get()) {
            s.file.delete()
            pump()
            return
        }
        ready = Ready(s.file, s.item)
        pump()
    }

    private val attrs: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    /**
     * Our own audio session, made once. AdVolume only ever turns down Twitch's ad players, but it
     * offers to keep a session of ours out explicitly, so this one is handed to it.
     */
    private fun session(ctx: Context): Int {
        if (session > 0) return session
        val s = runCatching { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId() }.getOrDefault(0)
        if (s > 0) {
            session = s
            runCatching { AdVolume.exclude(s) }
        }
        return session
    }

    private fun play(r: Ready) {
        val ctx = Settings.ctx()
        val now = SystemClock.uptimeMillis()
        if (ctx == null || (!r.item.force && (!allowed() || now - r.item.at > PLAY_STALE_MS)) || r.file.length() < 100) {
            r.file.delete()
            return
        }
        val g = gen.get()
        val mp = MediaPlayer()
        val p = Playing(mp, r.file, r.item)
        try {
            mp.setAudioAttributes(attrs)
            val s = session(ctx)
            if (s > 0) mp.audioSessionId = s
            mp.setDataSource(r.file.path)
            val v = volume()
            mp.setVolume(v, v)
            mp.setOnPreparedListener { m ->
                onPlayer("prepared") {
                    if (playing === p && g == gen.get() && (p.item.force || allowed())) {
                        m.start()
                        restarts = 0
                    } else {
                        endPlay(p)
                        pump()
                    }
                }
            }
            mp.setOnCompletionListener { onPlayer("done") { endPlay(p); pump() } }
            mp.setOnErrorListener { _, what, extra ->
                onPlayer("error") { logs.line("RA playback error $what/$extra"); endPlay(p); pump() }
                true
            }
            playing = p
            mp.prepareAsync()
        } catch (t: Throwable) {
            logs.line("RA playback failed: $t")
            if (playing === p) playing = null
            runCatching { mp.release() }
            r.file.delete()
        }
    }

    /** MediaPlayer calls back on our thread's looper; work from an ended thread is dropped. */
    private inline fun onPlayer(what: String, block: () -> Unit) {
        if (Thread.currentThread() === ttsThread) safely(what, block)
    }

    private fun endPlay(p: Playing) {
        if (playing === p) playing = null
        runCatching { p.mp.release() }
        p.file.delete()
    }

    /** Ptv-tts: everything stops now. Releasing the player cuts the sound at once. */
    private fun stopNow() {
        queue.clear()
        ready?.file?.delete()
        ready = null
        synth?.let { s ->
            synth = null
            s.file.delete()
            mainScope()
            runCatching { tts?.stop() }
        }
        playing?.let { endPlay(it) }
        busy = false
    }

    private fun armGuard() {
        if (guarding) return
        val hh = handler ?: return
        guarding = true
        hh.postDelayed(guardTask, GUARD_MS)
    }

    /** While anything waits or plays: the gate again, in case a stop signal never came. */
    private val guardTask = Runnable {
        if (Thread.currentThread() !== ttsThread) return@Runnable
        guarding = false
        safely("guard") {
            if (!busy) return@safely
            val force = playing?.item?.force ?: synth?.item?.force ?: ready?.item?.force ?: queue.firstOrNull()?.force ?: false
            if (!force && !allowed()) {
                gen.incrementAndGet()
                stopNow()
                log("RA stopped: ${why()}")
                idle()
                return@safely
            }
            armGuard()
        }
    }

    private fun words(t: String) = t.split(' ').count { w -> w.any { it.isLetterOrDigit() } }

    /** Caption text as it should be said: no sound tags like [MUSIC], no speaker marks, capped. */
    private fun speakable(raw: String): String? {
        var s = TAGS.replace(raw, " ").replace(">>", " ").replace('\u266A', ' ')
        s = WS.replace(s, " ").trim()
        if (s.count { it.isLetterOrDigit() } < 2) return null
        if (s.length > MAX_CHARS) {
            val cut = s.lastIndexOf(' ', MAX_CHARS)
            s = s.substring(0, if (cut > MAX_CHARS / 2) cut else MAX_CHARS)
        }
        return s
    }

    // ---- thread life ----

    private fun h(create: Boolean): Handler? {
        handler?.let { return it }
        if (!create) return null
        synchronized(lock) {
            handler?.let { return it }
            val t = HandlerThread("ptv-tts").apply { start() }
            val hh = Handler(t.looper)
            thread = t
            ttsThread = t
            handler = hh
            return hh
        }
    }

    /** Run [block] on ptv-tts (made when [create]); dropped if the thread ends first. */
    private fun post(create: Boolean = true, front: Boolean = false, block: () -> Unit) {
        val hh = h(create) ?: return
        val r = Runnable { if (Thread.currentThread() === ttsThread) safely("tts", block) }
        if (front) hh.postAtFrontOfQueue(r) else hh.post(r)
    }

    private val shutdownTask = Runnable {
        if (Thread.currentThread() === ttsThread) safely("shutdown") { shutdownNow("idle after the break") }
    }

    private fun scheduleShutdown() {
        val hh = handler ?: return
        hh.removeCallbacks(shutdownTask)
        hh.postDelayed(shutdownTask, SHUTDOWN_MS)
    }

    private fun cancelShutdown() {
        handler?.removeCallbacks(shutdownTask)
    }

    /** Nothing to say: end the engine and thread a while after the break, not during one. */
    private fun idle() {
        if (!busy && !PbypWatch.active) scheduleShutdown()
    }

    private fun shutdownNow(why: String) {
        stopNow()
        probing?.cancel()
        probing = null
        probeQueue.clear()
        closeTts()
        gaveUp = false
        restarts = 0
        guarding = false
        Settings.ctx()?.let { c -> runCatching { File(c.cacheDir, DIR).listFiles()?.forEach { it.delete() } } }
        val t = synchronized(lock) {
            val x = thread
            thread = null
            handler = null
            ttsThread = null
            x
        }
        t?.quitSafely()
        state = "Starts with the next ad break"
        log("RA voice shut down ($why)")
    }

    /** Once per session, and never over an ad: these come as a break starts, so they wait for its end. */
    private fun toastOnce(key: String, msg: String) {
        if (!toasted.add(key)) return
        AdTime.toast(msg)
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { logs.line("RA $what failed: $t") }
    }

    // ---- test commands (DevBridge) ----

    /**
     * op=say --es text T [--ez force true]   T as a finished caption line, through every gate; with
     *        force it is said now whatever the break does (the menu's "Test voice")
     * op=stop                                stops speaking at once
     * op=engines                             logs what the engine list offers (and starts checks)
     * op=probe                               checks the known offline engines again now
     * op=status, op=shutdown
     * Without op: text means say, otherwise status.
     */
    fun dev(i: Intent) {
        val text = i.getStringExtra("text")
        val op = i.getStringExtra("op") ?: if (text != null) "say" else "status"
        when (op) {
            "say" -> {
                val t = text ?: SAMPLE
                if (i.getBooleanExtra("force", false)) test(t)
                else {
                    if (!(on() && PbypWatch.active && PbypWatch.visible && fg() && !pip)) log("RA dev say: dropped by the gate (${why()})")
                    onFinal(t)
                }
            }
            "stop" -> stopAll("dev")
            "engines" -> Settings.ctx()?.let { c ->
                log("RA engines: " + engines(c).joinToString { "${it.first.ifEmpty { "(default)" }}=${it.second}" })
            }
            "probe" -> Settings.ctx()?.let { c -> probeSoon(c.applicationContext ?: c, force = true) }
            "shutdown" -> post(create = false) { shutdownNow("dev") }
            "status" -> {
                log("RA status on=${on()} state='${status()}' fg=${fg()} pip=$pip pbyp=${PbypWatch.active}/${PbypWatch.visible} " +
                    "busy=$busy thread=${thread != null} engine setting='${chosenEngine()}' volume=${volume()} rate=${rate()}")
                post(create = false) {
                    log("RA status engine=${engine.ifEmpty { "-" }} tts=${tts != null} booting=${boot != null} gaveUp=$gaveUp " +
                        "queue=${queue.size} synth=${synth != null} ready=${ready != null} playing=${playing != null} " +
                        "session=$session probing=${probing != null}")
                }
            }
            else -> log("RA dev: unknown op '$op' (say, stop, engines, probe, status or shutdown)")
        }
    }
}
