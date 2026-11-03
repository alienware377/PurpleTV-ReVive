package tv.purple.xp

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.CancellationException

/**
 * The tokenizer side of one Opus-MT (Marian) model, as transformers' MarianTokenizer does it:
 * text into pieces with the model's source.spm, pieces into ids with vocab.json (one vocabulary
 * shared by both sides), then the end of sentence token. Ids back to text through the same
 * vocabulary, with the special tokens dropped.
 *
 * Plain JVM code, no Android or ONNX Runtime, so the unit tests check it against reference ids.
 */
class MarianTokenizer(private val spm: SentencePiece, vocabJson: String) {
    private val toId = HashMap<String, Int>()
    private val toPiece: Array<String?>
    val eosId: Int
    val unkId: Int
    val padId: Int

    init {
        val o = JSONObject(vocabJson)
        var max = -1
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.getInt(k)
            toId[k] = v
            if (v > max) max = v
        }
        toPiece = arrayOfNulls(max + 1)
        for ((k, v) in toId) if (v >= 0) toPiece[v] = k
        eosId = toId["</s>"] ?: error("vocab.json has no </s>")
        unkId = toId["<unk>"] ?: error("vocab.json has no <unk>")
        padId = toId["<pad>"] ?: -1
    }

    val vocabSize: Int get() = toPiece.size

    fun id(piece: String): Int? = toId[piece]

    /**
     * Ids for [text], ending with </s>. [prefix] is a target language token such as ">>pt_br<<"
     * for the models that serve several languages; it goes first, as its own token. At most
     * [maxLen] ids in all: a longer text is cut, keeping the closing </s>.
     */
    fun encode(text: String, prefix: String? = null, maxLen: Int = 512): LongArray {
        val ids = ArrayList<Int>()
        if (prefix != null) ids.add(toId[prefix] ?: error("the model has no token $prefix"))
        for (p in spm.encode(text)) ids.add(toId[p] ?: unkId)
        if (ids.size > maxLen - 1) while (ids.size > maxLen - 1) ids.removeAt(ids.size - 1)
        ids.add(eosId)
        return LongArray(ids.size) { ids[it].toLong() }
    }

    /** Pieces for [text] as the source model cuts it, for tests and logs. */
    fun pieces(text: String): List<String> = spm.encode(text)

    /** Text for generated [ids]: special tokens and language tokens dropped, U+2581 back to spaces. */
    fun decode(ids: LongArray): String {
        val sb = StringBuilder()
        for (l in ids) {
            val i = l.toInt()
            if (i == eosId || i == padId || i == unkId) continue
            val p = toPiece.getOrNull(i) ?: continue
            if (p.startsWith(">>") && p.endsWith("<<")) continue
            sb.append(p)
        }
        val s = sb.toString().replace('▁', ' ').trim()
        return cleanUp(s)
    }

    companion object {
        fun load(dir: File): MarianTokenizer =
            MarianTokenizer(SentencePiece.load(File(dir, "source.spm")), File(dir, "vocab.json").readText())

        /**
         * transformers' clean_up_tokenization (the models' tokenizer_config sets it): no space
         * before common punctuation and English contractions.
         */
        fun cleanUp(s: String): String = s
            .replace(" .", ".").replace(" ?", "?").replace(" !", "!").replace(" ,", ",")
            .replace(" ' ", "'").replace(" n't", "n't").replace(" 'm", "'m").replace(" 's", "'s")
            .replace(" 've", "'ve").replace(" 're", "'re")
    }
}

/** The numbers the generation loop needs from config.json. */
class MarianConfig(val decoderStart: Long, val eos: Long, val pad: Long, val bad: Set<Long>, val maxLength: Int) {
    companion object {
        fun load(dir: File): MarianConfig {
            val c = JSONObject(File(dir, "config.json").readText())
            val g = File(dir, "generation_config.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
            fun num(k: String): Long? = (g?.optLong(k, Long.MIN_VALUE)?.takeIf { it != Long.MIN_VALUE })
                ?: c.optLong(k, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            val pad = num("pad_token_id") ?: error("config.json has no pad_token_id")
            val bad = HashSet<Long>()
            // Marian never generates <pad>: it is the decoder start token, and transformers bans
            // it through bad_words_ids. Only single token entries matter for greedy search.
            val bw = g?.optJSONArray("bad_words_ids") ?: c.optJSONArray("bad_words_ids")
            if (bw != null) for (i in 0 until bw.length()) {
                val a = bw.optJSONArray(i) ?: continue
                if (a.length() == 1) bad.add(a.getLong(0))
            }
            bad.add(pad)
            return MarianConfig(
                decoderStart = num("decoder_start_token_id") ?: pad,
                eos = num("eos_token_id") ?: error("config.json has no eos_token_id"),
                pad = pad,
                bad = bad,
                maxLength = (num("max_length") ?: 512L).toInt()
            )
        }
    }
}

/**
 * One Opus-MT model on ONNX Runtime: the quantized encoder and the merged quantized decoder (one
 * graph with and without the key/value cache, picked by its use_cache_branch input), greedy search.
 *
 * Input and output names come from the sessions, not from a fixed list, so a model with another
 * layer count works the same: every "present.X" output feeds the next step's "past_key_values.X".
 * The encoder half of the cache is the same at every step, so the first step's copy is kept.
 *
 * One call at a time ([translate] is synchronized), from a worker thread. ONNX Runtime's own
 * threads are capped ([threads], 2 by default) so chat translation never takes the whole phone.
 */
class MarianModel(val dir: File, threads: Int = 2, private val shrink: Boolean = true) : Closeable {
    val tokenizer = MarianTokenizer.load(dir)
    val config = MarianConfig.load(dir)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val pastNames: List<String>
    private val pastShapes: Map<String, LongArray>
    private val hasCacheBranch: Boolean
    @Volatile private var closed = false

    init {
        val opts = OrtSession.SessionOptions()
        try {
            opts.setIntraOpNumThreads(threads)
            // Idle pool threads would otherwise spin on a core after each run, right beside the video.
            runCatching { opts.addConfigEntry("session.intra_op.allow_spinning", "0") }
            opts.setInterOpNumThreads(1)
            opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            encoder = env.createSession(File(dir, ENCODER).absolutePath, opts)
            decoder = try {
                env.createSession(File(dir, DECODER).absolutePath, opts)
            } catch (t: Throwable) { encoder.close(); throw t }
        } finally {
            opts.close()
        }
        // A model that does not look right must not keep its two sessions (about 110 MB native).
        val layout = try { readLayout(decoder) } catch (t: Throwable) {
            runCatching { decoder.close() }; runCatching { encoder.close() }; throw t
        }
        pastNames = layout.first
        pastShapes = layout.second
        hasCacheBranch = layout.third
    }

    private fun readLayout(decoder: OrtSession): Triple<List<String>, Map<String, LongArray>, Boolean> {
        val info: Map<String, NodeInfo> = decoder.inputInfo
        val names = info.keys.filter { it.startsWith(PAST) }
        val shapes = names.associateWith { n ->
            val shape = (info[n]!!.info as TensorInfo).shape.clone()
            // Unknown sizes: the first is the batch (1), the rest is the cache length (0 at the start).
            var first = true
            for (k in shape.indices) if (shape[k] < 0) { shape[k] = if (first) 1 else 0; first = false }
            shape
        }
        for (n in names) require(PRESENT + n.removePrefix(PAST) in decoder.outputNames) { "the decoder has no output for $n" }
        return Triple(names, shapes, USE_CACHE in info)
    }

    /**
     * Translates [text], with [prefix] as the target language token when the model needs one.
     * [cancel] is asked between steps; when it says true this throws CancellationException.
     */
    @Synchronized
    fun translate(text: String, prefix: String? = null, cancel: () -> Boolean = { false }): String {
        check(!closed) { "the model is closed" }
        val ids = tokenizer.encode(text, prefix, MAX_INPUT)
        val out = generate(ids, cancel)
        return tokenizer.decode(out)
    }

    /** Greedy search over the merged decoder. Returns the generated ids without the start token. */
    @Synchronized
    fun generate(input: LongArray, cancel: () -> Boolean = { false }): LongArray {
        check(!closed) { "the model is closed" }
        val n = input.size
        val maxNew = minOf(MAX_NEW, 2 * n + 10, config.maxLength - 1)
        val made = ArrayList<Long>()
        val owned = ArrayList<OnnxValue>()
        var first: OrtSession.Result? = null
        var prev: OrtSession.Result? = null
        try {
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, n.toLong())).also { owned.add(it) }
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), longArrayOf(1, n.toLong())).also { owned.add(it) }
            val hidden = encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask))
            try {
                val state = hidden.get(0) as OnnxTensor
                if (cancel()) throw CancellationException()
                var next = config.decoderStart
                // Empty caches for the first step: the merged graph still wants every input.
                val empty = HashMap<String, OnnxTensor>()
                for (name in pastNames) {
                    val shape = pastShapes.getValue(name)
                    empty[name] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape).also { owned.add(it) }
                }
                val noCache = if (hasCacheBranch) OnnxTensor.createTensor(env, booleanArrayOf(false)).also { owned.add(it) } else null
                val withCache = if (hasCacheBranch) OnnxTensor.createTensor(env, booleanArrayOf(true)).also { owned.add(it) } else null
                fun feed(step: Int, tok: OnnxTensor): Map<String, OnnxTensor> {
                    val f = HashMap<String, OnnxTensor>()
                    f["input_ids"] = tok
                    f["encoder_attention_mask"] = mask
                    f["encoder_hidden_states"] = state
                    if (hasCacheBranch) f[USE_CACHE] = if (step == 0) noCache!! else withCache!!
                    for (name in pastNames) {
                        val outName = PRESENT + name.removePrefix(PAST)
                        f[name] = when {
                            step == 0 -> empty.getValue(name)
                            ".encoder." in name -> first!!.get(outName).get() as OnnxTensor
                            else -> prev!!.get(outName).get() as OnnxTensor
                        }
                    }
                    return f
                }
                try {
                    for (step in 0 until maxNew) {
                        if (cancel() || closed) throw CancellationException()
                        val tok = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(next)), longArrayOf(1, 1))
                        val res = try { decoder.run(feed(step, tok)) } finally { tok.close() }
                        if (step == 0) first = res else if (prev !== first) prev?.close()
                        prev = res
                        val logits = res.get("logits").get() as OnnxTensor
                        next = argmax(logits)
                        if (next == config.eos) break
                        made.add(next)
                    }
                } finally {
                    if (shrink && !closed) runCatching { shrinkArena(::feed) }
                }
            } finally {
                hidden.close()
            }
        } finally {
            if (prev !== first) runCatching { prev?.close() }
            runCatching { first?.close() }
            for (v in owned) runCatching { v.close() }
        }
        return made.toLongArray()
    }

    /**
     * ONNX Runtime's memory arena keeps the largest buffers a run needed (well over 100 MB for
     * these decoders: the output layer over the whole vocabulary), which is quick for the next
     * step but too much to hold inside Twitch between lines. Shrinking after every step makes
     * each one about twice as slow, so the line runs with the arena as it is, and then one extra
     * short step (the first step again, a few tens of milliseconds) runs with shrinking on, which
     * hands the arena's spare memory back at its end.
     */
    private fun shrinkArena(feed: (Int, OnnxTensor) -> Map<String, OnnxTensor>) {
        val ro = OrtSession.RunOptions()
        try {
            ro.addRunConfigEntry("memory.enable_memory_arena_shrinkage", "cpu:0")
            val tok = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(config.decoderStart)), longArrayOf(1, 1))
            try { decoder.run(feed(0, tok), ro).close() } finally { tok.close() }
        } finally {
            ro.close()
        }
    }

    /** config.bad as a flag per id: no boxing for each of the ~60k ids at every step. */
    private val banned: BooleanArray by lazy {
        val n = (config.bad.maxOrNull() ?: -1L).toInt() + 1
        BooleanArray(n.coerceAtLeast(0)).also { b -> for (x in config.bad) if (x >= 0) b[x.toInt()] = true }
    }

    /** The best token at the last position, never one of the banned ones. */
    private fun argmax(logits: OnnxTensor): Long {
        val shape = logits.info.shape
        val vocab = shape[shape.size - 1].toInt()
        val fb = logits.floatBuffer
        val base = fb.limit() - vocab
        var best = -1
        var bestV = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            val v = fb.get(base + i)
            if (v > bestV && !(i < banned.size && banned[i])) { bestV = v; best = i }
        }
        return if (best < 0) config.eos else best.toLong()
    }

    /**
     * Frees the sessions. A translation running now stops at its next step first (the flag is
     * set before the lock is taken), so this waits for one decoder step at most.
     */
    override fun close() {
        closed = true
        synchronized(this) {
            runCatching { decoder.close() }
            runCatching { encoder.close() }
        }
    }

    companion object {
        const val ENCODER = "encoder_model_quantized.onnx"
        const val DECODER = "decoder_model_merged_quantized.onnx"
        private const val PAST = "past_key_values."
        private const val PRESENT = "present."
        private const val USE_CACHE = "use_cache_branch"
        /** Input ids at most (the models know 512 positions; a chat line is far shorter). */
        const val MAX_INPUT = 256
        /** New tokens at most, also capped at twice the input plus ten. */
        const val MAX_NEW = 256
    }
}
