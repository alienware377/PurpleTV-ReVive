package tv.purple.xp

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class SentencePieceTest {

    // ---- a tiny hand made model, so these run in every build ----

    /** Writes a ModelProto by hand: pieces (text, score, type) and a NormalizerSpec without a charsmap. */
    private fun model(pieces: List<Triple<String, Float, Int>>, dummy: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(o: ByteArrayOutputStream, v: Long) {
            var x = v
            while (true) {
                if (x and 0x7fL.inv() == 0L) { o.write(x.toInt()); return }
                o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7
            }
        }
        fun field(o: ByteArrayOutputStream, n: Int, body: ByteArray) {
            varint(o, (n shl 3 or 2).toLong()); varint(o, body.size.toLong()); o.write(body)
        }
        for ((p, s, t) in pieces) {
            val m = ByteArrayOutputStream()
            field(m, 1, p.toByteArray())
            varint(m, (2 shl 3 or 5).toLong())
            val bits = s.toRawBits()
            for (k in 0 until 4) m.write((bits ushr (8 * k)) and 0xff)
            varint(m, (3 shl 3).toLong()); varint(m, t.toLong())
            field(out, 1, m.toByteArray())
        }
        val trainer = ByteArrayOutputStream().also { varint(it, (3 shl 3).toLong()); varint(it, 1) }
        field(out, 2, trainer.toByteArray())
        val norm = ByteArrayOutputStream().also { field(it, 1, "identity".toByteArray()); varint(it, (3 shl 3).toLong()); varint(it, if (dummy) 1 else 0) }
        field(out, 3, norm.toByteArray())
        return out.toByteArray()
    }

    private val N = SentencePiece.NORMAL
    private val tiny by lazy {
        SentencePiece.parse(model(listOf(
            Triple("<unk>", 0f, SentencePiece.UNKNOWN),
            Triple("<s>", 0f, SentencePiece.CONTROL),
            Triple("</s>", 0f, SentencePiece.CONTROL),
            Triple("▁", -2f, N),
            Triple("▁hello", -1f, N),
            Triple("▁hell", -1.5f, N),
            Triple("o", -2f, N),
            Triple("▁world", -1f, N),
            Triple("▁w", -3f, N),
            Triple("or", -3f, N),
            Triple("ld", -3f, N),
            Triple("h", -4f, N), Triple("e", -4f, N), Triple("l", -4f, N), Triple("w", -4f, N),
            Triple("r", -4f, N), Triple("d", -4f, N),
            Triple("unused", 10f, SentencePiece.UNUSED)
        )))
    }

    @Test fun picksTheBestSegmentation() {
        assertEquals(listOf("▁hello", "▁world"), tiny.encode("hello world"))
    }

    @Test fun squeezesAndTrimsSpaces() {
        assertEquals("▁hello▁world", tiny.normalize("   hello    world  "))
        assertEquals(listOf("▁hello", "▁world"), tiny.encode("   hello    world  "))
        assertEquals("", tiny.normalize("    "))
        assertEquals(emptyList<String>(), tiny.encode(""))
    }

    @Test fun unknownRunsAreOnePiece() {
        // The two emoji are one unknown piece, as sentencepiece returns them.
        assertEquals(listOf("▁hello", "▁", "😀🔥", "▁world"), tiny.encode("hello 😀🔥 world"))
    }

    @Test fun unusedPiecesAreNeverPicked() {
        val p = tiny.encode("unused")
        assertEquals(false, p.contains("unused"))
        assertEquals("▁unused", p.joinToString(""))
    }

    @Test fun refusesBpe() {
        val bytes = model(listOf(Triple("<unk>", 0f, SentencePiece.UNKNOWN), Triple("a", 0f, N)))
        // Patch trainer_spec model_type from 1 (unigram) to 2 (BPE). Piece types use the same
        // 0x18 tag, and the trainer spec comes after the pieces, so it is the last such pair.
        val i = (0 until bytes.size - 1).last { bytes[it] == 0x18.toByte() && bytes[it + 1] == 1.toByte() && bytes[it - 2] == 0x12.toByte() }
        bytes[i + 1] = 2
        val r = runCatching { SentencePiece.parse(bytes) }
        assertEquals(true, r.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun decodeDropsSpecialTokens() {
        val vocab = "{\"</s>\":0,\"<unk>\":1,\"▁Hola\":2,\",\":3,\"▁amigo\":4,\"!\":5,\"<pad>\":6,\">>es<<\":7}"
        val t = MarianTokenizer(tiny, vocab)
        assertEquals("Hola, amigo!", t.decode(longArrayOf(6, 7, 2, 3, 4, 1, 5, 0)))
        assertEquals("I'm fine", MarianTokenizer.cleanUp("I 'm fine"))
    }

    // ---- the real models, against ids from transformers' MarianTokenizer ----

    /**
     * src/test/resources/translate/tokref.json holds pieces and ids that transformers 4.x
     * MarianTokenizer gave for tricky lines (spaces, NFKC, emoji, CJK, Cyrillic, accents). It needs
     * the models (source.spm and vocab.json) from tools/make_translate_pack.py, see TranslateE2eTest.
     */
    @Test fun matchesTransformersOnRealModels() {
        val root = System.getProperty("ptv.translate.dir")?.let { File(it, "models") }
        assumeTrue("set -PptvTranslateDir to compare with the real models", root?.isDirectory == true)
        val ref = JSONObject(javaClass.getResource("/translate/tokref.json")!!.readText())
        var checked = 0
        for (m in ref.keys()) {
            val dir = File(root, m)
            if (!File(dir, "source.spm").isFile) continue
            val tok = MarianTokenizer.load(dir)
            val rows = ref.getJSONArray(m)
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val text = r.getString("text")
                val prefix = if (r.isNull("prefix")) null else r.getString("prefix")
                val pj = r.getJSONArray("pieces")
                val pieces = (0 until pj.length()).map { pj.getString(it) }
                val ij = r.getJSONArray("ids")
                val ids = LongArray(ij.length()) { ij.getLong(it) }
                assertEquals("$m pieces of \"$text\"", pieces, tok.pieces(text))
                assertArrayEquals("$m ids of \"$text\"", ids, tok.encode(text, prefix))
                checked++
            }
        }
        assumeTrue("no model folders found", checked > 0)
        println("tokenizer: $checked lines match transformers")
    }
}
