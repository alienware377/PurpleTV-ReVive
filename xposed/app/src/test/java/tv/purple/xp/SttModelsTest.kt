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
        assertEquals(listOf("en", "es", "de", "fr", "ru", "ko", "zh", "ja", "tl", "lt", "auto", "en-slang"), SttModels.ALL.map { it.lang })
        assertEquals(SttModels.ALL.map { it.label }, SttModels.labels())
        for (m in SttModels.ALL) assertSame(m, SttModels.byLang(m.lang))
    }

    @Test fun pinsLookSane() {
        for (m in SttModels.ALL) {
            assertTrue(m.asset, m.asset.endsWith(".zip") && '/' !in m.asset)
            assertTrue(m.sha256, m.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(m.bytes > 0)
            assertTrue(m.lang, m.lang.matches(Regex("[a-z]{2}(-[a-z]+)?")) || m.lang == SttModels.AUTO)
            assertTrue(m.credit, m.credit.isNotBlank() && m.licence.isNotBlank())
            for ((name, pin) in m.entries) {
                assertTrue(name, ".." !in name && !name.startsWith("/") && '\\' !in name)
                assertTrue(pin.sha256.matches(Regex("[0-9a-f]{64}")) && pin.bytes > 0)
            }
            // Every role the model's kind needs is named, and every named file is in the zip.
            assertEquals(m.lang, m.kind.roles.toSet(), m.files.keys)
            for (f in m.files.values) assertTrue("${m.lang}: $f", f in m.entries)
            if (m in SttModels.PACK2) {
                assertEquals(SttModels.TAG2, m.tag)
                assertEquals("model-${m.lang}", m.dir)
            }
            if (m in SttModels.PACK3) {
                assertEquals(if (m.lang == SttModels.AUTO) SttModels.TAG4 else SttModels.TAG3, m.tag)
                assertTrue(m.lang, m.kind.offline)
                assertTrue(m.lang, "LICENSE-MIT-silero-vad.txt" in m.entries)
            }
            if (m !== SttModels.EN) assertTrue("LICENSE.txt" in m.entries && "README.txt" in m.entries)
        }
        // Asset names and folders tell the packs apart on disk. Languages that share a model share
        // both, and everything else about the download too.
        for ((_, same) in SttModels.ALL.groupBy { it.asset }) {
            val a = same.first()
            for (m in same) {
                assertEquals(a.dir, m.dir)
                assertEquals(a.sha256, m.sha256)
                assertEquals(a.bytes, m.bytes)
                assertEquals(a.entries, m.entries)
                assertEquals(a.kind, m.kind)
                assertEquals(a.what, m.what)
            }
        }
        assertEquals(SttModels.ALL.map { it.asset }.toSet().size, SttModels.ALL.map { it.dir }.toSet().size)
        assertEquals(SttModels.ALL.size, SttModels.ALL.map { it.lang }.toSet().size)
        // Filipino and Lithuanian are one download; Auto is Whisper, its own.
        assertEquals(1, SttModels.ALL.filter { it.lang in setOf("tl", "lt") }.map { it.asset }.toSet().size)
        assertEquals(SttModel.Kind.WHISPER, SttModels.byLang(SttModels.AUTO)!!.kind)
    }

    @Test fun wholeSentenceModelsGetTheirOwnConfig() {
        val ja = SttModels.byLang("ja")!!
        val c = SttModels.offlineConfig(ja, dir, 2)
        assertEquals(File(dir, ja.files.getValue("encoder")).absolutePath, c.transducer.encoder)
        assertEquals(File(dir, ja.files.getValue("tokens")).absolutePath, c.tokens)
        assertEquals("", c.omnilingual.model)
        assertEquals(2, c.numThreads)
        val tl = SttModels.byLang("tl")!!
        val o = SttModels.offlineConfig(tl, dir, 2)
        assertEquals(File(dir, "model.int8.onnx").absolutePath, o.omnilingual.model)
        assertEquals("", o.transducer.encoder)
        val auto = SttModels.offlineConfig(SttModels.byLang(SttModels.AUTO)!!, dir, 2)
        assertEquals(File(dir, "base-encoder.int8.onnx").absolutePath, auto.whisper.encoder)
        // No language named, so Whisper finds it.
        assertEquals("", auto.whisper.language)
        for (m in SttModels.PACK3) {
            val v = SttModels.vadConfig(m, dir)
            assertEquals(File(dir, "silero_vad.onnx").absolutePath, v.sileroVadModelConfig.model)
            assertEquals(16000, v.sampleRate)
        }
        // A streaming config for a whole sentence model (or the other way round) is a mistake.
        assertTrue(runCatching { SttModels.modelConfig(ja, dir, 1) }.isFailure)
        assertTrue(runCatching { SttModels.offlineConfig(SttModels.EN, dir, 1) }.isFailure)
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

    @Test fun whisperIsToldOnlyLanguagesItKnows() {
        val w = SttModels.WHISPER
        assertSame(SttModels.byLang(SttModels.AUTO), w)
        // The list in Whisper base's own metadata: told anything else, sherpa-onnx ends the process.
        assertEquals(99, SttModels.WHISPER_LANGS.size)
        assertTrue("yue" !in SttModels.WHISPER_LANGS && "haw" in SttModels.WHISPER_LANGS)
        for (m in SttModels.ALL) assertTrue(m.lang, m.lang == SttModels.AUTO || m.lang.substringBefore('-') in SttModels.WHISPER_LANGS)
        assertEquals(null, SttModels.forced("yue"))
        assertEquals(null, SttModels.forced("xx"))
        val pt = SttModels.forced("pt")!!
        assertSame(pt, SttModels.forced("pt"))
        // The same download, folder and pins as Whisper itself; only the language differs.
        assertEquals(w.asset, pt.asset)
        assertEquals(w.dir, pt.dir)
        assertSame(w.entries, pt.entries)
        // SttPacks finds a model's download by its lang.
        assertEquals(w.lang, pt.lang)
        assertEquals("pt", pt.sourceLang)
        assertEquals("auto:pt", pt.key)
        assertEquals(SttModels.AUTO, w.key)
        assertEquals("pt", SttModels.offlineConfig(pt, dir, 2).whisper.language)
        assertEquals("", SttModels.offlineConfig(w, dir, 2).whisper.language)
        // A language Whisper does not know never reaches its config.
        val bad = SttModel(w.lang, w.label, w.tag, w.asset, w.sha256, w.bytes, w.kind, w.modelType, w.licence, w.credit,
            w.files, w.entries, w.dir, w.what, w.script, whisperLang = "yue")
        assertEquals("", SttModels.offlineConfig(bad, dir, 2).whisper.language)
        // English (Twitch slang) is Whisper base.en: a model for English, told nothing.
        assertEquals("", SttModels.offlineConfig(SttModels.byLang("en-slang")!!, dir, 2).whisper.language)
    }

    /** What Auto runs for a stream in each language, with what is downloaded. */
    @Test fun autoUsesTheLanguagesOwnModel() {
        val w = SttModels.WHISPER
        val en = SttModels.EN
        val slang = SttModels.byLang("en-slang")!!
        fun pick(lang: String?, vararg have: SttModel) = SttModels.autoPick(lang) { it in have }

        // Its own model, downloaded: that one. English before English (Twitch slang).
        assertSame(en, pick("en", en, slang, w).model)
        assertSame(slang, pick("en", slang, w).model)
        assertSame(SttModels.byLang("ja"), pick("ja", SttModels.byLang("ja")!!).model)
        assertSame(SttModels.byLang("tl"), pick("tl", SttModels.byLang("tl")!!, w).model)
        assertEquals(null, pick("es", SttModels.byLang("es")!!).missing)

        // Its own model not downloaded: Whisper told the language, and the model it is missing.
        val es = pick("es", w, en)
        assertEquals("auto:es", es.model.key)
        assertSame(SttModels.byLang("es"), es.missing)
        // Neither: its own model, which the caption box then says needs a download.
        val es2 = pick("es", en)
        assertSame(SttModels.byLang("es"), es2.model)
        assertSame(SttModels.byLang("es"), es2.missing)
        assertSame(en, pick("en").model)

        // No model of its own: Whisper told the language, downloaded or not.
        for (lang in listOf("pt", "it", "ar", "pl", "tr")) {
            assertEquals("auto:$lang", pick(lang, w, en).model.key)
            assertEquals("auto:$lang", pick(lang).model.key)
            assertEquals(null, pick(lang, w).missing)
            assertEquals(lang, pick(lang, w).model.sourceLang)
        }

        // Not known, or one Whisper can not be told: Whisper finding each sentence's language.
        assertSame(w, pick(null, w, en).model)
        assertSame(w, pick("xx", w).model)
        assertEquals(null, pick(null, w).model.sourceLang)
    }

    /** A model that would not load in this run is passed over for Whisper told the language. */
    @Test fun autoPassesOverAModelThatFailedToLoad() {
        val w = SttModels.WHISPER
        val en = SttModels.EN
        val slang = SttModels.byLang("en-slang")!!
        val tl = SttModels.byLang("tl")!!
        val es = SttModels.byLang("es")!!
        fun pick(lang: String, bad: Set<String>, vararg have: SttModel) =
            SttModels.autoPick(lang, bad = { it.lang in bad }) { it in have }
        // Downloaded but failed, Whisper there: Whisper told the language, and no note to download
        // a model that is already on the phone.
        val p = pick("tl", setOf("tl"), tl, w)
        assertEquals("auto:tl", p.model.key)
        assertEquals(null, p.missing)
        // Another model of the language's own comes first.
        assertSame(slang, pick("en", setOf("en"), en, slang, w).model)
        // Whisper failed as well, or is not there: the failed model, which Stt does not load.
        assertSame(tl, pick("tl", setOf("tl", SttModels.AUTO), tl, w).model)
        assertSame(tl, pick("tl", setOf("tl"), tl).model)
        assertEquals(null, pick("tl", setOf("tl"), tl).missing)
        // A model that is not downloaded is still one to name.
        val e = pick("es", setOf("tl"), w)
        assertEquals("auto:es", e.model.key)
        assertSame(es, e.missing)
    }

    /** GigaAM adds no row and no download, and is set up as sherpa-onnx needs it. */
    @Test fun gigaamIsNotOfferedAndHasItsOwnConfig() {
        val g = SttModels.RU_GIGAAM
        val ru = SttModels.byLang("ru")!!
        assertTrue(g !in SttModels.ALL)
        assertTrue(SttModels.ALL.none { it.asset == g.asset || it.dir == g.dir || it.tag == g.tag })
        assertEquals(listOf(ru), SttModels.dedicated("ru"))
        assertSame(ru, SttModels.byLang("ru"))
        // Heard as Russian by the translator, but failing to load must not turn the old model off.
        assertEquals("ru", g.sourceLang)
        assertTrue(g.key != ru.key)
        assertEquals(setOf(SttModels.TAG6, SttModels.TAG7), SttModels.UPGRADES.values.map { it.tag }.toSet())

        assertTrue(g.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(g.kind.roles.toSet(), g.files.keys)
        for (f in g.files.values) assertTrue(f, f in g.entries)
        for (pin in g.entries.values) assertTrue(pin.bytes > 0 && pin.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue("LICENSE.txt" in g.entries && "README.txt" in g.entries && "LICENSE-MIT-silero-vad.txt" in g.entries)
        assertEquals("MIT", g.licence)

        assertEquals(64, g.featDim)
        assertTrue(g.cased)
        assertEquals(80, ru.featDim)
        val c = SttModels.offlineConfig(g, dir, 2)
        assertEquals("nemo_transducer", c.modelType)
        assertEquals(File(dir, "encoder.int8.onnx").absolutePath, c.transducer.encoder)
        assertEquals(File(dir, "joiner.onnx").absolutePath, c.transducer.joiner)
        assertEquals(File(dir, "silero_vad.onnx").absolutePath, SttModels.vadConfig(g, dir).sileroVadModelConfig.model)
    }

    /** Russian stays on its old model unless GigaAM is on the phone and did not fail to load. */
    @Test fun russianMovesToGigaamOnlyWhenItIsThere() {
        val g = SttModels.RU_GIGAAM
        val ru = SttModels.byLang("ru")!!
        fun up(m: SttModel, bad: Set<SttModel> = emptySet(), vararg have: SttModel) =
            SttModels.upgraded(m, { it in bad }) { it in have }
        assertSame(ru, up(ru))
        assertSame(ru, up(ru, emptySet(), ru))
        assertSame(g, up(ru, emptySet(), ru, g))
        assertSame(g, up(ru, emptySet(), g))
        assertSame(ru, up(ru, setOf(g), ru, g))
        for (m in SttModels.ALL) if (m !== ru) assertSame(m, up(m, emptySet(), *SttModels.ALL.toTypedArray(), g))
    }

    /** Auto follows the same rule, and only ever names the released model as missing. */
    @Test fun autoMovesToGigaamOnlyWhenItIsThere() {
        val g = SttModels.RU_GIGAAM
        val ru = SttModels.byLang("ru")!!
        val w = SttModels.WHISPER
        fun pick(bad: Set<SttModel>, vararg have: SttModel) =
            SttModels.autoPick("ru", bad = { it in bad }) { it in have }
        assertSame(ru, pick(emptySet(), ru).model)
        assertEquals(null, pick(emptySet(), ru).missing)
        assertSame(g, pick(emptySet(), ru, g).model)
        assertSame(g, pick(emptySet(), g).model)
        assertEquals(null, pick(emptySet(), g).missing)
        assertSame(g, pick(setOf(ru), ru, g).model)
        // GigaAM failed to load: back to the old model.
        assertSame(ru, pick(setOf(g), ru, g).model)
        // Nothing for Russian on the phone: Whisper told Russian, and the old model is the one named.
        val none = pick(emptySet(), w)
        assertEquals("auto:ru", none.model.key)
        assertSame(ru, none.missing)
        assertSame(ru, pick(emptySet()).model)
        assertSame(ru, pick(emptySet()).missing)
        assertSame(ru, pick(setOf(g), w).missing)
    }

    @Test fun languageNames() {
        assertEquals("Spanish", SttModels.langName("es"))
        assertEquals("English", SttModels.langName("en"))
        assertEquals("Filipino", SttModels.langName("tl"))
        // Without a model of its own: the name in the language the menus are shown in.
        assertEquals("Portuguese", SttModels.langName("pt"))
        assertEquals("Polish", SttModels.langName("pl"))
        assertEquals("Portugués", SttModels.langName("pt", "es"))
        assertEquals("zz", SttModels.langName("zz"))
    }

    /** Translator must understand every stream language as a source (live captions pass it on). */
    @Test fun streamLanguagesAreTranslatorCodes() {
        // Its targets, plus languages opus-mt-mul-en reads that are not offered as a target.
        val known = Translator.languages().map { it.code }.toSet() + setOf("ko", "ja", "tl", "lt")
        for (m in SttModels.ALL) {
            if (m.lang == SttModels.AUTO) assertEquals(null, m.sourceLang)
            else assertTrue(m.lang, m.sourceLang == m.lang.substringBefore('-') && m.sourceLang in known)
        }
    }

    /** With -PptvCaptionsDir set, the built zips must match the pins exactly. */
    @Test fun builtZipsMatchThePins() {
        val out = System.getProperty("ptv.captions.dir")?.let { File(it) }
        assumeTrue(out?.isDirectory == true)
        var n = 0
        for (m in (SttModels.PACK2 + SttModels.PACK3 + SttModels.PACK5).distinctBy { it.asset }) {
            if (!File(out, m.asset).isFile) continue
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
