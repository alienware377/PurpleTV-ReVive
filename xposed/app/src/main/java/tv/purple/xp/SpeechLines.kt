package tv.purple.xp

/**
 * Where caption lines end.
 *
 * Streaming models ([Splitter]) hear the sound as it comes and give their result as a growing list
 * of tokens. A line used to end where Stt reset the recognizer, at the latest after 8 s of talk,
 * which cut words in half ("Thanks for in" / "dulge") and lost letters at the cut. Now the
 * recognizer is only reset after a pause (or after a long safety limit), and the lines are cut here,
 * from the tokens: always between two words, at the end of a sentence when there is one, else after
 * a comma or about [LONG] words (see [cut]). The tokens are SentencePiece pieces: a piece that
 * starts a word begins with a space (sherpa-onnx writes the "▁" mark as a space), any other
 * piece goes on the word before it. Chinese and Japanese characters each start a word of their own.
 * The tokens of a byte-level BPE model (the Chinese one) are bytes rather than text, and are read
 * back into such pieces first ([fromByteBpe]).
 *
 * A pause can also end a line just before the model writes its last piece: the full stop, the
 * question mark or the rest of a word then comes as the first piece after the reset. That piece is
 * handed back as [Step.join], to go at the end of the line before, instead of starting a new one.
 *
 * Whole sentence models get the sound of one sentence at a time; when a sentence runs too long,
 * [quietCut] picks where to cut it, in the quietest stretch near the limit.
 *
 * Plain JVM code, tested by SpeechLinesTest.
 */
object SpeechLines {

    /** A sentence ends a line once the line has this many words, so "Yeah. No." are not lines of their own. */
    const val MIN_SENTENCE = 6f
    /**
     * A line with no such sentence end is cut when it reaches this many words: at the last sentence
     * end in it, else at its last comma, else after [LONG] words. The few words past [LONG] give a
     * sentence that ends just after it the room to end the line.
     */
    const val MOST = 15f
    const val LONG = 12f
    /** At [MOST], the shortest line a sentence end may make, in words. */
    const val MIN_PART = 3f
    /** At [MOST], the shortest line a comma may make. */
    const val MIN_CLAUSE = 6f

    /** What a step of [Splitter] hands on. */
    class Step(
        /** Lines finished now, in order. */
        val lines: List<String>,
        /** A piece that belongs at the end of the line finished last (see [Splitter]), or "". */
        val join: String,
        /** The words heard after the finished lines, still growing. */
        val partial: String
    )

    /**
     * Turns the token lists of one streaming recognizer into finished lines and the words still
     * being said. Not thread safe: Stt uses it on its own thread only.
     *
     * The recognizer's result only ever grows until the recognizer is reset, so the tokens already
     * given out are counted ([done]) and only the ones after them are looked at. After a forced
     * reset (the safety limit, in the middle of talk) the words not given out yet are kept
     * ([carry]) and the next result goes on from them, so even that cut loses nothing.
     */
    class Splitter {
        private val carry = ArrayList<String>()
        private var done = 0
        /** The last line ended at a pause: the first piece after it may still belong to it. */
        private var joinOpen = false

        /** Words are kept from before a forced reset that no line has given out yet. */
        val holding: Boolean get() = carry.isNotEmpty()

        /** A new recognizer or a new stream: nothing carries over. */
        fun reset() {
            carry.clear()
            done = 0
            joinOpen = false
        }

        /** The result so far, while the talk goes on. */
        fun update(tokens: List<String>): Step = run(tokens, GOING)

        /**
         * The recognizer found an endpoint and is reset after this. [forced]: the safety limit cut
         * the talk, so the last word may not be finished and the words not given out are kept.
         * Otherwise it was a pause and everything heard is a line now.
         */
        fun endpoint(tokens: List<String>, forced: Boolean): Step = run(tokens, if (forced) FORCED else PAUSE)

        /** The sound stopped or jumped: everything heard is a line now, and nothing after it follows on. */
        fun flush(tokens: List<String>): Step = run(tokens, FLUSH)

        private fun run(tokens: List<String>, mode: Int): Step {
            if (done > tokens.size) done = tokens.size
            val last = mode != GOING
            var join = ""
            if (joinOpen) {
                // The pieces before the first word start belong to the line before.
                var k = done
                while (k < tokens.size && !startsWord(tokens, k)) k++
                if (k == tokens.size && !last) return Step(emptyList(), "", "")
                join = piece(tokens, done, k)
                done = k
                joinOpen = false
            } else if (carry.isEmpty() && done == 0) {
                // Nothing to put it on: a line never starts with the stop of a sentence before it.
                while (done < tokens.size && tokens[done].all { it == ' ' || it == '\u2581' || closing(it) } &&
                    tokens[done].any { closing(it) }) done++
            }
            val p = ArrayList<String>(carry.size + tokens.size - done)
            p.addAll(carry)
            p.addAll(tokens.subList(done, tokens.size))
            val lines = ArrayList<String>()
            var from = 0
            while (true) {
                val c = cut(p, from)
                if (c < 0) break
                text(p, from, c).takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                from = c
            }
            val rest = text(p, from, p.size)
            when (mode) {
                GOING -> {
                    if (from <= carry.size) carry.subList(0, from).clear()
                    else { done += from - carry.size; carry.clear() }
                    return Step(lines, join, rest)
                }
                FORCED -> {
                    val keep = ArrayList(p.subList(from, p.size))
                    carry.clear()
                    carry.addAll(keep)
                    done = 0
                    return Step(lines, join, rest)
                }
                else -> {
                    if (rest.isNotEmpty()) lines.add(rest)
                    carry.clear()
                    done = 0
                    // Only a pause right after a line leaves it open; a gap in the sound or a
                    // stretch of silence with nothing said closes it.
                    joinOpen = mode == PAUSE && rest.isNotEmpty()
                    return Step(lines, join, "")
                }
            }
        }
    }

    private const val GOING = 0
    private const val PAUSE = 1
    private const val FORCED = 2
    private const val FLUSH = 3

    /**
     * True when an endpoint after [heard] seconds of sound since the last reset, whose last word
     * came at [last] seconds, was the safety limit of [limit] seconds cutting the talk, not a pause:
     * the limit is reached, and less than a pause ([pause] seconds) has gone by since that word.
     * Only then may a word be cut in half (see [Splitter.endpoint]). A pause after nearly [limit]
     * of talk ends its line like any other pause.
     */
    fun forcedEnd(heard: Float, last: Float, limit: Float, pause: Float): Boolean = heard >= limit && heard - last < pause

    /**
     * Where the next line ends in [p] after [from]: the token index a new line starts at, or -1 when
     * the line goes on. Only word starts that have a token after them count, so a word that may
     * still grow is never cut.
     */
    internal fun cut(p: List<String>, from: Int): Int {
        var words = 0f
        var sentence = -1
        var clause = -1
        var long = -1
        var g = from
        while (g < p.size) {
            var e = g + 1
            while (e < p.size && !startsWord(p, e)) e++
            // The word [g, e) is finished only when another one starts after it.
            if (e >= p.size) break
            words += weight(p, g, e)
            val end = lastMark(p, g, e)
            if (end in SENTENCE) {
                if (words >= MIN_SENTENCE) return e
                if (words >= MIN_PART) sentence = e
            }
            if (end in CLAUSE && words >= MIN_CLAUSE) clause = e
            if (long < 0 && words >= LONG) long = e
            if (words >= MOST) return when {
                sentence > 0 -> sentence
                clause > 0 -> clause
                else -> long
            }
            g = e
        }
        return -1
    }

    /**
     * True when token [i] of [p] starts a word: it begins with the word mark (a space, or "▁"
     * in the raw form) and is more than a closing mark, or it is a Chinese or Japanese character.
     */
    internal fun startsWord(p: List<String>, i: Int): Boolean {
        val t = p[i]
        if (t.isEmpty()) return false
        val c = t[0]
        if (c == ' ' || c == '\u2581') {
            var k = 1
            while (k < t.length && (t[k] == ' ' || t[k] == '\u2581')) k++
            return k == t.length || !t.substring(k).all { closing(it) }
        }
        return cjk(t.codePointAt(0))
    }

    /** One for a word; half for each character in a script written without spaces. */
    private fun weight(p: List<String>, from: Int, to: Int): Float {
        var cjk = 0
        for (i in from until to) {
            val t = p[i]
            var k = 0
            while (k < t.length) {
                val cp = t.codePointAt(k)
                if (cjk(cp)) cjk++
                k += Character.charCount(cp)
            }
        }
        return if (cjk > 0) cjk * 0.5f else 1f
    }

    /** The last mark of a word, looking through closing quotes and brackets, or a space when it has none. */
    private fun lastMark(p: List<String>, from: Int, to: Int): Char {
        for (i in to - 1 downTo from) {
            val t = p[i]
            for (k in t.length - 1 downTo 0) {
                val c = t[k]
                if (c == ' ' || c == '\u2581' || c in QUOTES) continue
                return c
            }
        }
        return ' '
    }

    private const val SENTENCE = ".!?…。！？"
    private const val CLAUSE = ",;:、，；："
    private const val QUOTES = "\"'”’»)]}」』）"

    /** A mark that ends what came before it rather than starting something. */
    private fun closing(c: Char): Boolean = c in SENTENCE || c in CLAUSE || c in QUOTES || c == '-' || c == '\u2013' || c == '\u2014'

    private fun cjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    /** Tokens [from] until [to] as a line: joined, the word marks as spaces, trimmed. */
    internal fun text(p: List<String>, from: Int = 0, to: Int = p.size): String = raw(p, from, to).trim()

    /** The same for a piece that goes on the end of a line: only trailing space goes. */
    private fun piece(p: List<String>, from: Int, to: Int): String = raw(p, from, to).trimEnd()

    /**
     * The text of tokens [from] until [to]. A SentencePiece byte piece ("<0xE4>") is a byte of a
     * character the model has no piece for; runs of them are read back as UTF-8.
     */
    private fun raw(p: List<String>, from: Int, to: Int): String {
        val sb = StringBuilder()
        var bytes: java.io.ByteArrayOutputStream? = null
        fun flushBytes() {
            bytes?.let { sb.append(it.toString(Charsets.UTF_8.name())); bytes = null }
        }
        for (i in from until to) {
            val t = p[i]
            if (t.length == 6 && t.startsWith("<0x") && t[5] == '>') {
                val b = t.substring(3, 5).toIntOrNull(16)
                if (b != null) {
                    (bytes ?: java.io.ByteArrayOutputStream().also { bytes = it }).write(b)
                    continue
                }
            }
            flushBytes()
            sb.append(t.replace('\u2581', ' '))
        }
        flushBytes()
        return sb.toString()
    }

    // ---- byte-level BPE ----

    /**
     * Icefall's byte-level BPE alphabet: byte b is written as the character with the code BBPE[b].
     * Printable ASCII stands for itself, every other byte for a letter from U+0100 on, so that
     * SentencePiece can learn pieces of bytes as if they were letters.
     */
    private val BBPE: IntArray = ((256..287) + (32..126) + (288..305) + (308..318) + (321..328) + (330..382) + (384..422)).toIntArray()

    /** The byte each character of [BBPE] stands for, by its code; -1 for any other character. */
    private val BBPE_BYTE = IntArray(423) { -1 }.also { a -> BBPE.forEachIndexed { b, c -> a[c] = b } }

    /**
     * The tokens of a byte-level BPE model (see SttModel.byteBpe) as pieces like any other model's:
     * the bytes of each word, from one word mark to the next, read back as UTF-8, with every Chinese
     * or Japanese character a piece of its own and other text a piece that starts with a space where
     * it starts a word. Bytes at the end that do not make a whole character yet are left out until
     * they do, so the pieces only ever grow at the end, as [Splitter] needs.
     */
    fun fromByteBpe(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        val bytes = java.io.ByteArrayOutputStream()
        var word = false
        for (t in tokens) {
            // <unk> and the like stand for no bytes.
            if (t.length > 2 && t[0] == '<' && t[t.length - 1] == '>') continue
            for (c in t) {
                if (c == ' ' || c == '▁') {
                    if (bytes.size() > 0) { wordPieces(bytes.toByteArray(), word, out); bytes.reset() }
                    word = true
                    continue
                }
                val b = if (c.code < BBPE_BYTE.size) BBPE_BYTE[c.code] else -1
                if (b >= 0) bytes.write(b)
            }
        }
        wordPieces(bytes.toByteArray(), word, out)
        return out
    }

    /** One word's bytes as pieces, added to [out]. [word]: it starts with a word mark. */
    private fun wordPieces(b: ByteArray, word: Boolean, out: MutableList<String>) {
        val text = wholeChars(b)
        val run = StringBuilder()
        var runStarts = false
        fun endRun() {
            if (run.isEmpty()) return
            out.add(if (runStarts) " $run" else run.toString())
            run.setLength(0)
        }
        var k = 0
        while (k < text.length) {
            val cp = text.codePointAt(k)
            if (cjk(cp)) {
                endRun()
                out.add(String(Character.toChars(cp)))
            } else {
                if (run.isEmpty()) runStarts = word && k == 0
                run.appendCodePoint(cp)
            }
            k += Character.charCount(cp)
        }
        endRun()
    }

    /**
     * [b] read as UTF-8, whole characters only: bytes that can not start or continue one are
     * skipped, and a character whose last bytes have not come yet is left out.
     */
    private fun wholeChars(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            val n = when {
                c < 0x80 -> 1
                c in 0xC2..0xDF -> 2
                c in 0xE0..0xEF -> 3
                c in 0xF0..0xF4 -> 4
                else -> 0
            }
            if (n == 0) { i++; continue }
            var k = 1
            while (k < n && i + k < b.size && (b[i + k].toInt() and 0xC0) == 0x80) k++
            if (k < n) {
                // Its next bytes have not come yet: it waits. Another byte instead: not a character.
                if (i + k >= b.size) break
                i++
                continue
            }
            sb.append(String(b, i, n, Charsets.UTF_8))
            i += n
        }
        return sb.toString()
    }

    /**
     * Pieces of text that follow each other, as one: with a space between them, except between two
     * characters of a script written without spaces.
     */
    fun glue(parts: List<String>): String {
        val sb = StringBuilder()
        for (s in parts) {
            val t = s.trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty() && !(cjk(sb.codePointBefore(sb.length)) && cjk(t.codePointAt(0)))) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    /** The end of [text], at most [max] characters, cut at a space where it can be, with "…" in front when cut. */
    fun tail(text: String, max: Int): String {
        if (text.length <= max) return text
        var from = text.length - max
        val sp = text.indexOf(' ', from)
        if (sp in from until text.length - 1 && sp - from < max / 3) from = sp + 1
        return "…" + text.substring(from).trimStart()
    }

    // ---- whole sentence models ----

    /** 10 ms at 16 kHz: the step [quietCut] measures the sound in. */
    private const val FRAME = 160

    /**
     * Where to cut a sentence that ran too long, between [from] and [to] of the 16 kHz sound [x]:
     * in the middle of the longest quiet stretch there, the later one when two are as long, so the
     * cut falls in a pause between words rather than inside one. The sound is measured in 10 ms
     * steps, each with the ones beside it, and counts as quiet up to twice the quietest step (plus
     * a floor far below speech, for digital silence). [to] when the stretch is too short to measure.
     */
    fun quietCut(x: FloatArray, from: Int, to: Int): Int {
        val n = (to - from) / FRAME
        if (from < 0 || to > x.size || n < 3) return to
        val e = FloatArray(n)
        for (k in 0 until n) {
            var s = 0f
            val a = from + k * FRAME
            for (i in a until a + FRAME) s += x[i] * x[i]
            e[k] = s
        }
        val w = FloatArray(n) { k -> e[maxOf(0, k - 1)] + e[k] + e[minOf(n - 1, k + 1)] }
        var min = Float.MAX_VALUE
        for (v in w) if (v < min) min = v
        val quiet = min * 2f + 3 * FRAME * QUIET_FLOOR
        var best = -1
        var bestLen = 0
        var run = -1
        for (k in 0..n) {
            val q = k < n && w[k] <= quiet
            if (q && run < 0) run = k
            if (!q && run >= 0) {
                if (k - run >= bestLen) { bestLen = k - run; best = run }
                run = -1
            }
        }
        if (best < 0) return to
        return from + best * FRAME + bestLen * FRAME / 2
    }

    /** Sound per sample that is quiet whatever else is there: about -70 dB. */
    private const val QUIET_FLOOR = 1e-7f
}
