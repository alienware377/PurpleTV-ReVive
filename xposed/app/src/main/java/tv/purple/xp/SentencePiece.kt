package tv.purple.xp

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A SentencePiece unigram tokenizer in plain Kotlin, enough for the Opus-MT (Marian) models.
 *
 * Why not the real library: it is C++, and every native library has to come as its own pinned
 * download that runs inside Twitch's process. The part Marian needs is small: read the .spm file
 * (a protobuf ModelProto), normalize the text the way the model says, then find the best way to
 * cut it into pieces (Viterbi over the piece scores). This follows sentencepiece's own
 * normalizer.cc and unigram_model.cc (EncodeOptimized), so the pieces match the library's.
 *
 * Only unigram models are handled, which is what every Opus-MT model uses. BPE or byte fallback
 * models are refused when loading, rather than tokenized wrongly.
 */
class SentencePiece private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val types: IntArray,
    private val norm: Normalizer
) {
    /** Piece text to its index, for the pieces the Viterbi search may use (normal, user defined, unused). */
    private val index = HashMap<String, Int>(pieces.size * 2)
    private val maxLen: Int
    private val unkId: Int
    private val minScore: Float
    private val maxScore: Float

    init {
        var ml = 1
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var unk = -1
        for (i in pieces.indices) {
            when (types[i]) {
                NORMAL, USER_DEFINED, UNUSED -> {
                    index[pieces[i]] = i
                    if (pieces[i].length > ml) ml = pieces[i].length
                }
                UNKNOWN -> if (unk < 0) unk = i
            }
            if (types[i] == NORMAL) { mn = minOf(mn, scores[i]); mx = maxOf(mx, scores[i]) }
        }
        maxLen = ml
        unkId = unk
        minScore = if (mn == Float.MAX_VALUE) 0f else mn
        maxScore = if (mx == -Float.MAX_VALUE) 0f else mx
        require(unkId >= 0) { "the SentencePiece model has no unknown piece" }
    }

    val size: Int get() = pieces.size
    fun piece(id: Int): String = pieces[id]
    val unkPiece: String get() = pieces[unkId]

    /** The text as the model sees it: normalized, spaces as U+2581, a leading U+2581 added. */
    fun normalize(text: String): String = norm.normalize(text)

    /** The best segmentation of [text] into piece strings. A run of unknown characters is one piece. */
    fun encode(text: String): List<String> {
        val s = normalize(text)
        if (s.isEmpty()) return emptyList()
        val n = s.length
        // Best path ending at each char position: its score, where its last piece starts, which piece.
        val best = FloatArray(n + 1)
        val from = IntArray(n + 1) { -1 }
        val id = IntArray(n + 1) { -1 }
        from[0] = 0
        val unkScore = minScore - UNK_PENALTY
        var i = 0
        while (i < n) {
            if (from[i] < 0) { i++; continue }
            val here = best[i]
            // One character, so a surrogate pair counts as one, as sentencepiece counts UTF-8 chars.
            val one = Character.charCount(s.codePointAt(i))
            var single = false
            val end = minOf(n, i + maxLen)
            var j = i + 1
            while (j <= end) {
                // Never end a piece in the middle of a surrogate pair.
                if (j < n && Character.isLowSurrogate(s[j]) && Character.isHighSurrogate(s[j - 1])) { j++; continue }
                val p = index[s.substring(i, j)]
                if (p != null && types[p] != UNUSED) {
                    val len = j - i
                    val sc = if (types[p] == USER_DEFINED) len * maxScore - 0.1f else scores[p]
                    val cand = sc + here
                    if (from[j] < 0 || cand > best[j]) { best[j] = cand; from[j] = i; id[j] = p }
                    if (len == one) single = true
                }
                j++
            }
            if (!single) {
                val k = i + one
                val cand = unkScore + here
                if (from[k] < 0 || cand > best[k]) { best[k] = cand; from[k] = i; id[k] = unkId }
            }
            i += one
        }
        val out = ArrayList<String>()
        val outIds = ArrayList<Int>()
        var k = n
        while (k > 0) {
            val st = from[k]
            out.add(s.substring(st, k)); outIds.add(id[k])
            k = st
        }
        out.reverse(); outIds.reverse()
        // sentencepiece hands a run of unknown pieces back as one piece.
        val merged = ArrayList<String>(out.size)
        var prevUnk = false
        for (x in out.indices) {
            val unk = outIds[x] == unkId
            if (unk && prevUnk) merged[merged.size - 1] = merged.last() + out[x] else merged.add(out[x])
            prevUnk = unk
        }
        return merged
    }

    /**
     * The normalizer from the model's NormalizerSpec: the precompiled character map (a darts-clone
     * double array trie over UTF-8 bytes plus a blob of replacement strings), then the whitespace
     * rules. Works on UTF-8 bytes like the library, so the trie's byte matches line up exactly.
     */
    internal class Normalizer(
        private val trie: IntArray?,
        private val blob: ByteArray?,
        private val addDummyPrefix: Boolean,
        private val removeExtraSpaces: Boolean,
        private val escapeSpaces: Boolean
    ) {
        fun normalize(text: String): String {
            val input = text.toByteArray(Charsets.UTF_8)
            var pos = 0
            // Skip leading spaces (after normalizing, so a no-break space counts too).
            if (removeExtraSpaces) {
                while (pos < input.size) {
                    val (rep, used) = prefix(input, pos)
                    if (!(rep.size == 1 && rep[0] == ' '.code.toByte())) break
                    pos += used
                }
            }
            if (pos >= input.size) return ""
            val out = ByteArrayOutputStream(input.size + 8)
            if (addDummyPrefix) out.write(SPACE)
            var prevSpace = removeExtraSpaces
            while (pos < input.size) {
                val (rep, used) = prefix(input, pos)
                var st = 0
                if (prevSpace) while (st < rep.size && rep[st] == ' '.code.toByte()) st++
                if (st < rep.size) {
                    for (b in st until rep.size) {
                        if (escapeSpaces && rep[b] == ' '.code.toByte()) out.write(SPACE) else out.write(rep[b].toInt())
                    }
                    prevSpace = rep[rep.size - 1] == ' '.code.toByte()
                }
                pos += used
                prevSpace = prevSpace && removeExtraSpaces
            }
            var bytes = out.toByteArray()
            if (removeExtraSpaces) {
                val sp = if (escapeSpaces) SPACE else byteArrayOf(' '.code.toByte())
                var len = bytes.size
                while (len >= sp.size && (0 until sp.size).all { bytes[len - sp.size + it] == sp[it] }) len -= sp.size
                if (len != bytes.size) bytes = bytes.copyOf(len)
            }
            return String(bytes, Charsets.UTF_8)
        }

        /** The replacement for the longest match at [pos] and how many input bytes it used. */
        private fun prefix(input: ByteArray, pos: Int): Pair<ByteArray, Int> {
            if (trie != null && blob != null) {
                var bestLen = 0
                var bestVal = -1
                // darts-clone commonPrefixSearch.
                var node = 0
                var unit = trie[node]
                node = node xor offset(unit)
                var i = pos
                while (i < input.size) {
                    val c = input[i].toInt() and 0xff
                    if (c == 0) break
                    node = node xor c
                    if (node < 0 || node >= trie.size) break
                    unit = trie[node]
                    if (label(unit) != c) break
                    node = node xor offset(unit)
                    if (node < 0 || node >= trie.size) break
                    if (hasLeaf(unit)) { bestLen = i - pos + 1; bestVal = value(trie[node]) }
                    i++
                }
                if (bestLen > 0 && bestVal >= 0 && bestVal < blob.size) {
                    var e = bestVal
                    while (e < blob.size && blob[e] != 0.toByte()) e++
                    return blob.copyOfRange(bestVal, e) to bestLen
                }
            }
            // No rule: one UTF-8 character as it is; a broken byte becomes U+FFFD.
            val n = utf8Len(input, pos)
            return if (n == 0) REPLACEMENT to 1 else input.copyOfRange(pos, pos + n) to n
        }

        private fun hasLeaf(u: Int) = ((u ushr 8) and 1) == 1
        private fun value(u: Int) = u and 0x7fffffff
        private fun label(u: Int) = u and (0x80000000.toInt() or 0xff)
        private fun offset(u: Int) = (u ushr 10) shl ((u and (1 shl 9)) ushr 6)

        /** Length of a valid UTF-8 character at [p], or 0 when the bytes there are not one. */
        private fun utf8Len(b: ByteArray, p: Int): Int {
            val c = b[p].toInt() and 0xff
            val n = when {
                c < 0x80 -> return 1
                c in 0xc2..0xdf -> 2
                c in 0xe0..0xef -> 3
                c in 0xf0..0xf4 -> 4
                else -> return 0
            }
            if (p + n > b.size) return 0
            for (k in 1 until n) if ((b[p + k].toInt() and 0xc0) != 0x80) return 0
            return n
        }
    }

    companion object {
        const val NORMAL = 1
        const val UNKNOWN = 2
        const val CONTROL = 3
        const val USER_DEFINED = 4
        const val UNUSED = 5
        const val BYTE = 6
        /** sentencepiece's kUnkPenalty: an unknown character costs this much below the worst piece. */
        private const val UNK_PENALTY = 10f
        /** U+2581, the piece's stand-in for a space, in UTF-8. */
        private val SPACE = byteArrayOf(0xe2.toByte(), 0x96.toByte(), 0x81.toByte())
        private val REPLACEMENT = byteArrayOf(0xef.toByte(), 0xbf.toByte(), 0xbd.toByte())

        fun load(f: File): SentencePiece = parse(f.readBytes())

        /** Reads a serialized ModelProto. Throws IllegalArgumentException for anything it can not handle. */
        fun parse(data: ByteArray): SentencePiece {
            val pieces = ArrayList<String>()
            val scores = ArrayList<Float>()
            val types = ArrayList<Int>()
            var modelType = 1
            var byteFallback = false
            var trie: IntArray? = null
            var blob: ByteArray? = null
            // NormalizerSpec defaults, as in sentencepiece_model.proto.
            var dummy = true
            var removeWs = true
            var escapeWs = true
            var suffix = false
            val r = Pb(data, 0, data.size)
            while (r.more()) {
                val tag = r.varint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                when {
                    field == 1 && wire == 2 -> {
                        val m = r.sub()
                        var piece = ""
                        var score = 0f
                        var type = NORMAL
                        while (m.more()) {
                            val t = m.varint().toInt()
                            when {
                                t == (1 shl 3 or 2) -> piece = m.string()
                                t == (2 shl 3 or 5) -> score = Float.fromBits(m.fixed32())
                                t == (3 shl 3) -> type = m.varint().toInt()
                                else -> m.skip(t and 7)
                            }
                        }
                        pieces.add(piece); scores.add(score); types.add(type)
                    }
                    field == 2 && wire == 2 -> {
                        val m = r.sub()
                        while (m.more()) {
                            val t = m.varint().toInt()
                            when (t) {
                                (3 shl 3) -> modelType = m.varint().toInt()
                                (24 shl 3) -> suffix = m.varint() != 0L
                                (35 shl 3) -> byteFallback = m.varint() != 0L
                                else -> m.skip(t and 7)
                            }
                        }
                    }
                    field == 3 && wire == 2 -> {
                        val m = r.sub()
                        while (m.more()) {
                            val t = m.varint().toInt()
                            when (t) {
                                (2 shl 3 or 2) -> {
                                    val b = m.bytes()
                                    if (b.size >= 4) {
                                        val tsz = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
                                            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
                                        require(tsz >= 0 && tsz % 4 == 0 && 4 + tsz <= b.size) { "bad precompiled charsmap" }
                                        trie = IntArray(tsz / 4) { k ->
                                            val o = 4 + k * 4
                                            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                                                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
                                        }
                                        blob = b.copyOfRange(4 + tsz, b.size)
                                    }
                                }
                                (3 shl 3) -> dummy = m.varint() != 0L
                                (4 shl 3) -> removeWs = m.varint() != 0L
                                (5 shl 3) -> escapeWs = m.varint() != 0L
                                else -> m.skip(t and 7)
                            }
                        }
                    }
                    else -> r.skip(wire)
                }
            }
            require(modelType == 1) { "only unigram SentencePiece models are supported (type $modelType)" }
            require(!byteFallback) { "byte fallback SentencePiece models are not supported" }
            require(!suffix) { "whitespace-as-suffix SentencePiece models are not supported" }
            require(pieces.isNotEmpty()) { "the SentencePiece model has no pieces" }
            val t = trie?.takeIf { it.isNotEmpty() }
            return SentencePiece(pieces.toTypedArray(), scores.toFloatArray(), types.toIntArray(),
                Normalizer(t, if (t != null) blob else null, dummy, removeWs, escapeWs))
        }
    }

    /** A minimal protobuf reader over [b] from [pos] to [end]. */
    private class Pb(private val b: ByteArray, private var pos: Int, private val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                require(pos < end && shift < 64) { "bad protobuf varint" }
                val c = b[pos++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl shift)
                if (c < 0x80) return r
                shift += 7
            }
        }
        fun fixed32(): Int {
            require(pos + 4 <= end) { "bad protobuf fixed32" }
            val v = (b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8) or
                ((b[pos + 2].toInt() and 0xff) shl 16) or ((b[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }
        private fun len(): Int {
            val n = varint()
            require(n >= 0 && n <= end - pos) { "bad protobuf length" }
            return n.toInt()
        }
        fun sub(): Pb { val n = len(); val p = Pb(b, pos, pos + n); pos += n; return p }
        fun bytes(): ByteArray { val n = len(); val r = b.copyOfRange(pos, pos + n); pos += n; return r }
        fun string(): String = String(bytes(), Charsets.UTF_8)
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { require(pos + 8 <= end); pos += 8 }
                2 -> { val n = len(); pos += n }
                5 -> { require(pos + 4 <= end); pos += 4 }
                else -> throw IllegalArgumentException("bad protobuf wire type $wire")
            }
        }
    }
}
