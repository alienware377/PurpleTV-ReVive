package tv.purple.xp

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SttModelsTest {

    private val dir = File("/data/ptv_stt/x")

    /** English must load exactly as it did before there was a choice: same pins, same config. */
    @Test fun englishIsTheDefaultAndUnchanged() {
        assertSame(SttModels.EN, SttModels.ALL[SttModels.DEF])
        assertSame(SttModels.EN, SttModels.chosen())
        val en = SttModels.EN
        assertEquals("captions-pack-1", en.tag)
        assertEquals(PackPins.TAG, en.tag)
        assertEquals("model", en.dir)
        assertEquals(PackPins.MODEL_ASSET, en.asset)
        assertEquals(PackPins.MODEL_SHA256, en.sha256)
        assertEquals(PackPins.MODEL_BYTES, en.bytes)
        assertEquals(PackPins.MODEL_UNPACKED_BYTES, en.unpackedBytes)
        assertSame(PackPins.MODEL_ENTRIES, en.entries)
        // The config Stt.load built by hand for the English model before there was a choice.
        fun path(role: String) = File(dir, PackPins.MODEL_FILES.getValue(role)).absolutePath
        val old = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(encoder = path("encoder"), decoder = path("decoder"), joiner = path("joiner")),
            tokens = path("tokens"), numThreads = 2, provider = "cpu", modelType = PackPins.MODEL_TYPE)
        assertEquals(old, SttModels.modelConfig(en, dir, 2))
    }

    @Test fun captionsPack1PinsAreUntouched() {
        assertEquals("captions-pack-1", PackPins.TAG)
        assertEquals("purpletv-captions-model-kroko-en-2025-08-06.zip", PackPins.MODEL_ASSET)
        assertEquals("be9d7cb6e6e34703b8b15997c435a968b1d753e70770a9ca1eb5c69b8f52ad21", PackPins.MODEL_SHA256)
        assertEquals(55728993L, PackPins.MODEL_BYTES)
    }

    @Test fun storedValuesMapToModels() {
        for ((i, m) in SttModels.ALL.withIndex()) assertSame(m, SttModels.forValue(i))
        // A value from a newer version (synced over) or junk falls back to English.
        assertSame(SttModels.EN, SttModels.forValue(SttModels.ALL.size))
        assertSame(SttModels.EN, SttModels.forValue(-1))
        // The stored number is a place in ALL, so the order must never change. New ones go last.
        assertEquals(listOf("en", "es", "de", "fr", "ru", "ko", "zh"), SttModels.ALL.map { it.lang })
        assertEquals(SttModels.ALL.map { it.label }, SttModels.labels())
        for (m in SttModels.ALL) assertSame(m, SttModels.byLang(m.lang))
    }

    @Test fun pinsLookSane() {
        for (m in SttModels.ALL) {
            assertTrue(m.asset, m.asset.endsWith(".zip") && '/' !in m.asset)
            assertTrue(m.sha256, m.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(m.bytes > 0)
            assertTrue(m.lang, m.lang.matches(Regex("[a-z]{2}")))
            assertTrue(m.credit, m.credit.isNotBlank() && m.licence.isNotBlank())
            for ((name, pin) in m.entries) {
                assertTrue(name, ".." !in name && !name.startsWith("/") && '\\' !in name)
                assertTrue(pin.sha256.matches(Regex("[0-9a-f]{64}")) && pin.bytes > 0)
            }
            // Every role the model's kind needs is named, and every named file is in the zip.
            assertEquals(m.lang, m.kind.roles.toSet(), m.files.keys)
            for (f in m.files.values) assertTrue("${m.lang}: $f", f in m.entries)
            if (m !== SttModels.EN) {
                assertEquals(SttModels.TAG2, m.tag)
                assertEquals("model-${m.lang}", m.dir)
                assertTrue("LICENSE.txt" in m.entries && "README.txt" in m.entries)
            }
        }
        // Asset names and folders tell the packs apart on disk, so they must all differ.
        assertEquals(SttModels.ALL.size, SttModels.ALL.map { it.asset }.toSet().size)
        assertEquals(SttModels.ALL.size, SttModels.ALL.map { it.dir }.toSet().size)
        assertEquals(SttModels.ALL.size, SttModels.ALL.map { it.lang }.toSet().size)
    }

    @Test fun configFollowsTheModelKind() {
        val zh = SttModels.byLang("zh")!!
        assertEquals(SttModel.Kind.ZIPFORMER2_CTC, zh.kind)
        val c = SttModels.modelConfig(zh, dir, 1)
        assertEquals(File(dir, zh.files.getValue("model")).absolutePath, c.zipformer2Ctc.model)
        assertEquals("", c.transducer.encoder)
        assertEquals(File(dir, zh.files.getValue("tokens")).absolutePath, c.tokens)

        val ko = SttModels.byLang("ko")!!
        val k = SttModels.modelConfig(ko, dir, 1)
        // Zipformer version 1: sherpa-onnx must be told, or it reads the model as version 2.
        assertEquals("zipformer", k.modelType)
        assertEquals(File(dir, ko.files.getValue("encoder")).absolutePath, k.transducer.encoder)
        assertEquals("", k.zipformer2Ctc.model)

        for (lang in listOf("es", "de", "fr", "ru")) {
            val m = SttModels.byLang(lang)!!
            val t = SttModels.modelConfig(m, dir, 3)
            assertEquals("zipformer2", t.modelType)
            assertEquals(3, t.numThreads)
            assertTrue(t.transducer.joiner.endsWith(m.files.getValue("joiner")))
        }
    }

    /** Translator must understand every stream language as a source (live captions pass it on). */
    @Test fun streamLanguagesAreTranslatorCodes() {
        // Its targets, plus Korean: opus-mt-mul-en reads Korean, it is only not offered as a target.
        val known = Translator.languages().map { it.code }.toSet() + setOf("ko")
        for (m in SttModels.ALL) assertTrue(m.lang, m.lang in known)
    }

    /** With -PptvCaptionsDir set, the built zips must match the pins exactly. */
    @Test fun builtZipsMatchThePins() {
        val out = System.getProperty("ptv.captions.dir")?.let { File(it) }
        assumeTrue(out?.isDirectory == true)
        var n = 0
        for (m in SttModels.PACK2) {
            val f = File(out, m.asset)
            if (!f.isFile) continue
            assertEquals(m.asset, m.bytes, f.length())
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val k = i.read(b); if (k < 0) break; md.update(b, 0, k) } }
            assertEquals(m.asset, m.sha256, md.digest().joinToString("") { "%02x".format(it) })
            java.util.zip.ZipFile(f).use { z ->
                val names = z.entries().asSequence().map { it.name }.toList()
                assertEquals(m.asset, m.entries.keys.toList(), names)
                for (e in z.entries()) assertEquals(e.name, m.entries.getValue(e.name).bytes, e.size)
            }
            n++
        }
        assumeTrue(n > 0)
    }
}
