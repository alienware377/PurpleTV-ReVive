package tv.purple.xp

/**
 * Keeps what the many-language speech model writes in the writing systems the stream really uses.
 * That model is told no language, and over game sound or music it often writes a few letters of
 * some other script (Arabic letters over a Filipino stream, Devanagari over a Japanese one). Those
 * are left out here:
 *  - [Mode.LATIN] (Filipino, Lithuanian): words with letters of another script are dropped.
 *  - [Mode.AUTO]: the scripts the stream has used lately are learned from the lines kept. A line
 *    mostly in another script is shown when it is long, or when the line before was in that script
 *    too and the two make a fair length together, so a stream that changes language is followed
 *    after a line or so while short bursts of noise are not.
 *  - [Mode.ANY]: the text as it is, for models that only know one language.
 * A line that is mostly left out is dropped whole: what is left of it is usually noise too.
 *
 * One [Filter] per loaded model; it remembers the scripts of the stream heard so far. Not thread
 * safe: Stt uses it on its decoding thread only. Plain JVM code, tested by SpeechScriptTest.
 */
object SpeechScript {

    enum class Mode { ANY, LATIN, AUTO }

    /**
     * Script groups. Han and kana are one group, since Japanese mixes them; Chinese then lands in
     * it too, which is fine, the translator guesses the language from the text anyway.
     */
    internal fun group(cp: Int): String? {
        if (!Character.isLetter(cp)) return null
        return when (val s = Character.UnicodeScript.of(cp)) {
            // Shared marks like the Japanese long vowel ー belong to no one script.
            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> null
            Character.UnicodeScript.LATIN -> "latin"
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "cjk"
            else -> s.name.lowercase()
        }
    }

    /** One CJK or Hangul letter says about as much as two Latin ones. */
    private fun weight(g: String) = if (g == "cjk" || g == "hangul") 2f else 1f

    /** Scripts written without spaces, where one "word" can be a whole sentence. */
    private fun unspaced(g: String?) = g == "cjk" || g == "thai"

    /** A word, the group most of its letters are in (null: no letters) and its weighted letters per group. */
    private class Word(val text: String, val group: String?, val letters: Map<String, Float>) {
        val mixed: Boolean get() = letters.size > 1
    }

    private fun words(text: String): List<Word> = text.split(SPACE).filter { it.isNotEmpty() }.map { w ->
        val n = HashMap<String, Float>()
        var i = 0
        while (i < w.length) {
            val cp = w.codePointAt(i)
            i += Character.charCount(cp)
            group(cp)?.let { n[it] = (n[it] ?: 0f) + weight(it) }
        }
        Word(w, n.maxByOrNull { it.value }?.key, n)
    }

    /**
     * The words of [ws] in the [keep] groups. A mixed word is dropped, except in a script written
     * without spaces, where only its stray letters go.
     */
    private fun only(ws: List<Word>, keep: Set<String>): List<Word> = ws.mapNotNull { w ->
        when {
            w.group == null -> w
            w.group !in keep -> null
            !w.mixed -> w
            unspaced(w.group) -> {
                val sb = StringBuilder()
                var i = 0
                while (i < w.text.length) {
                    val cp = w.text.codePointAt(i)
                    i += Character.charCount(cp)
                    val g = group(cp)
                    if (g == null || g in keep) sb.appendCodePoint(cp)
                }
                Word(sb.toString(), w.group, mapOf(w.group to (w.letters[w.group] ?: 0f)))
            }
            else -> null
        }
    }

    private fun letters(ws: List<Word>): Float = ws.sumOf { w -> w.letters.values.sum().toDouble() }.toFloat()

    private val SPACE = Regex("\\s+")

    class Filter(val mode: Mode) {
        /** Letters kept lately per group, fading with every kept line. */
        private val seen = HashMap<String, Float>()
        /** The last line held back in AUTO mode: its group, when, and its letters. */
        private var heldGroup: String? = null
        private var heldAt = 0L
        private var heldLetters = 0f

        /**
         * What to show for [text], or "" when nothing of it should be. [final] lines teach the
         * filter; partial ones only follow what it already knows. [now] is a clock in ms.
         */
        fun take(text: String, final: Boolean, now: Long): String {
            val t = text.trim()
            if (t.isEmpty() || mode == Mode.ANY) return t
            val ws = words(t)
            return when (mode) {
                Mode.LATIN -> join(ws, only(ws, setOf("latin")))
                Mode.AUTO -> auto(ws, final, now)
                Mode.ANY -> t
            }
        }

        private fun auto(ws: List<Word>, final: Boolean, now: Long): String {
            val per = HashMap<String, Float>()
            for (w in ws) for ((g, n) in w.letters) per[g] = (per[g] ?: 0f) + n
            val main = per.maxByOrNull { it.value } ?: return ""
            val all = per.values.sum()
            val known = known()
            val ok = when {
                main.key in known -> true
                !final -> false
                // Long and nearly all in one script: a real change of language, not noise.
                main.value >= LONG && main.value >= all * 0.9f -> true
                // The line before was in this script too, close by, and together they are long enough.
                heldGroup == main.key && now - heldAt < HOLD_MS && heldLetters >= MIN_PART &&
                    main.value >= MIN_PART && heldLetters + main.value >= LONG -> true
                else -> false
            }
            if (!ok) {
                if (final) { heldGroup = main.key; heldAt = now; heldLetters = main.value }
                return ""
            }
            val out = join(ws, only(ws, known + main.key))
            if (final && out.isNotEmpty()) {
                heldGroup = null
                for (k in seen.keys.toList()) seen[k] = seen.getValue(k) * FADE
                for (w in words(out)) for ((g, n) in w.letters) seen[g] = (seen[g] ?: 0f) + n
            }
            return out
        }

        /** Groups with a fair share of what was kept lately. */
        private fun known(): Set<String> {
            val total = seen.values.sum()
            if (total <= 0f) return emptySet()
            return seen.filter { it.value >= total * SHARE }.keys
        }

        /** [kept] as a line, or "" when it is too little of [all] or hardly a letter. */
        private fun join(all: List<Word>, kept: List<Word>): String {
            val k = letters(kept)
            if (k < 2f || k < letters(all) * KEEP_SHARE) return ""
            return kept.joinToString(" ") { it.text }.trim()
        }
    }

    /** Weighted letters in one script that make a line count as a change of language on its own. */
    private const val LONG = 24f
    /** Each of two lines that change the language together needs at least this many. */
    private const val MIN_PART = 6f
    /** A held line and the next one in the same script must come this close together. */
    private const val HOLD_MS = 30_000L
    /** How much a kept line's letters still count after the next kept line. */
    private const val FADE = 0.85f
    /** Share of the kept letters a script needs to count as one the stream uses. */
    private const val SHARE = 0.2f
    /** A line needs at least this share of its letters left after filtering, or it is dropped whole. */
    private const val KEEP_SHARE = 0.5f
}
