package tv.purple.xp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * "While the ad played": once an ad break is over, a short recap in chat of what you missed.
 *  - On stream: the streamer's most telling lines, from the captions of the live picture
 *    (Twitch's own captions or speech to text, so only when captions are on and Twitch showed
 *    the small live picture during the ad).
 *  - In chat: how busy it was, the top emotes and what most people said.
 *
 * All of it is worked out on the phone from what the module already sees. Nothing is sent
 * anywhere, and no AI model is involved: the lines are picked by the words that came up most.
 */
object BreakRecap {

    const val KEY = "ad_break_recap"
    /** Shorter breaks are not worth a recap. */
    private const val MIN_BREAK_MS = 15_000L
    private const val TICK_MS = 1000L
    private const val MAX_SAID = 3
    private const val MAX_LINES = 400

    private val main = Handler(Looper.getMainLooper())
    // Main thread only from here on.
    private var on = false
    private var startedAt = 0L
    private var channel: String? = null
    private val said = ArrayList<String>()
    private val chat = ArrayList<ChatFeed.Line>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        ChatFeed.addListener { l -> main.post { if (on && chat.size < 5000) chat.add(l) } }
        CaptionHub.addLineListener { t -> if (on && said.size < MAX_LINES) said.add(t) }
        PbypWatch.addListener { tick() }
        main.postDelayed(loop, TICK_MS)
        log("BR break recap installed")
    }

    /** Display ads and AdVolume's windows have no listener, so the state is looked at each second. */
    private val loop = object : Runnable {
        override fun run() {
            try { tick() } catch (t: Throwable) { log("BR tick: $t") }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        val ad = AdTime.now()
        if (ad && !on) {
            on = true; startedAt = SystemClock.uptimeMillis(); channel = EmoteRepo.currentChannel()
            said.clear(); chat.clear()
        } else if (!ad && on) {
            on = false
            val ms = SystemClock.uptimeMillis() - startedAt
            val same = channel == EmoteRepo.currentChannel()
            if (Settings.get(KEY, true) && same && ms >= MIN_BREAK_MS) {
                val lines = build(ms)
                if (lines.isNotEmpty()) {
                    log("BR recap after ${ms / 1000}s: ${said.size} caption lines, ${chat.size} chat lines")
                    for (l in lines) ModLogs.notice(l)
                }
            }
            said.clear(); chat.clear()
        }
    }

    private fun build(ms: Long): List<String> {
        val out = ArrayList<String>(4)
        val secs = ms / 1000
        val len = if (secs >= 60) "${secs / 60} min ${secs % 60} s" else "$secs s"
        val chatLines = chat.filter { it.channel == null || it.channel == channel }
        val picks = pickLines(said, MAX_SAID)
        if (picks.isEmpty() && chatLines.isEmpty()) return out
        out += "📺 While the ad played ($len):"
        if (picks.isNotEmpty()) out += "On stream: " + picks.joinToString("  ·  ") { "“$it”" }
        if (chatLines.isNotEmpty()) {
            val people = chatLines.map { it.login }.toHashSet().size
            val parts = ArrayList<String>(3)
            parts += "${chatLines.size} message" + (if (chatLines.size == 1) "" else "s") +
                " from $people chatter" + (if (people == 1) "" else "s")
            topEmotes(chatLines).takeIf { it.isNotEmpty() }?.let { parts += "top emotes " + it.joinToString(", ") { (e, n) -> "$e ×$n" } }
            topPhrase(chatLines)?.let { (p, n) -> parts += "most said “$p” ×$n" }
            out += "In chat: " + parts.joinToString(", ")
        }
        return out
    }

    // ------------------------------------------------------------ picking (pure, unit tested)

    private val WORD = Regex("[\\p{L}\\p{N}']+")

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "so", "to", "of", "in", "on", "at", "for", "with", "is", "it",
        "its", "it's", "i", "i'm", "you", "we", "they", "he", "she", "this", "that", "was", "be", "are",
        "just", "like", "yeah", "oh", "uh", "um", "okay", "ok", "do", "don't", "not", "have", "my", "me",
        "what", "there", "if", "all", "get", "got", "go", "know", "right", "really", "gonna", "can", "your",
        "then", "now", "here", "one", "up", "out", "no", "yes", "be", "am", "were", "been", "as", "by",
    )

    /** The [n] lines that carry the words said most often, in the order they were said. */
    internal fun pickLines(lines: List<String>, n: Int): List<String> {
        val clean = lines.map { it.trim() }.filter { it.split(' ').size >= 4 }.distinct()
        if (clean.isEmpty()) return emptyList()
        if (clean.size <= n) return clean.map { trimLine(it) }
        val freq = HashMap<String, Int>()
        val words = clean.map { l -> WORD.findAll(l.lowercase()).map { it.value }.filter { it !in STOP && it.length > 2 }.toList() }
        for (ws in words) for (w in ws.toHashSet()) freq[w] = (freq[w] ?: 0) + 1
        val scored = clean.indices.map { i ->
            val ws = words[i]
            val s = if (ws.isEmpty()) 0.0 else ws.sumOf { (freq[it] ?: 0).toDouble() } / Math.sqrt(ws.size.toDouble())
            i to s
        }
        return scored.sortedByDescending { it.second }.take(n).map { it.first }.sorted().map { trimLine(clean[it]) }
    }

    private fun trimLine(s: String) = if (s.length <= 90) s else s.take(87).trimEnd() + "…"

    internal fun topEmotes(lines: List<ChatFeed.Line>, n: Int = 3): List<Pair<String, Int>> {
        val c = HashMap<String, Int>()
        for (l in lines) for (e in l.emotes.toHashSet()) c[e] = (c[e] ?: 0) + 1
        return c.entries.filter { it.value >= 2 }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n).map { it.key to it.value }
    }

    /** The message most people sent (by at least three chatters), emote-only lines left out. */
    internal fun topPhrase(lines: List<ChatFeed.Line>): Pair<String, Int>? {
        val who = HashMap<String, HashSet<String>>()
        val shown = HashMap<String, String>()
        for (l in lines) {
            val t = l.text.trim()
            if (t.isEmpty() || t.length > 40) continue
            val words = t.split(Regex("\\s+")).filter { it !in l.emotes }
            if (words.isEmpty()) continue
            val key = words.joinToString(" ").lowercase().trimEnd('!', '?', '.')
            if (key.isEmpty()) continue
            who.getOrPut(key) { HashSet() }.add(l.login)
            shown.putIfAbsent(key, words.joinToString(" "))
        }
        val best = who.entries.maxByOrNull { it.value.size } ?: return null
        return if (best.value.size >= 3) shown[best.key]!! to best.value.size else null
    }
}
