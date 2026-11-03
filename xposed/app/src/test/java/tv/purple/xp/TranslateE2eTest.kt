package tv.purple.xp

import ai.onnxruntime.NativeLibs
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Real translations on the desktop JVM with the same ONNX Runtime version as the phone pack.
 *
 * Skips itself unless the models and natives are there. To run it:
 *   python3 -I tools/make_translate_pack.py /some/dir
 *   gradle testDebugUnitTest -PptvTranslateDir=/some/dir   (or PTV_TRANSLATE_DIR=/some/dir)
 * It needs /some/dir/desktop-ort/{libonnxruntime.so,libonnxruntime4j_jni.so} (linux-x64) and
 * /some/dir/models/<name>/ for the models it uses (en-es, mul-en, en-ROMANCE, en-zh; and from
 * translate-pack-2, built with --release translate-pack-2: es-en, de-en, ROMANCE-en, zh-en, ru-en).
 */
class TranslateE2eTest {
    companion object {
        private val root: File? = System.getProperty("ptv.translate.dir")?.let { File(it) }

        @BeforeClass @JvmStatic fun natives() {
            val d = root?.let { File(it, "desktop-ort") } ?: return
            val ort = File(d, "libonnxruntime.so")
            val jni = File(d, "libonnxruntime4j_jni.so")
            // The patched loader takes absolute paths, the same way the phone does.
            if (ort.isFile && jni.isFile) NativeLibs.set(ort, jni)
        }

        private val cache = HashMap<String, MarianModel>()

        fun model(name: String): MarianModel {
            val r = root
            assumeTrue("set -PptvTranslateDir to run the translation test", r != null)
            assumeTrue("desktop ONNX Runtime natives missing", File(r, "desktop-ort/libonnxruntime4j_jni.so").isFile)
            val d = File(r, "models/$name")
            assumeTrue("model $name missing", File(d, MarianModel.DECODER).isFile)
            return cache.getOrPut(name) { MarianModel(d) }
        }
    }

    @Test fun englishToSpanish() {
        val m = model("en-es")
        val out = m.translate("Hello, how are you?")
        println("en-es: $out")
        assertTrue(out, out.contains("Hola"))
    }

    @Test fun intoEnglish() {
        val m = model("mul-en")
        val de = m.translate("Ich habe heute keine Zeit.")
        val es = m.translate("Hola, ¿cómo estás?")
        println("mul-en: $de | $es")
        assertTrue(de, de.lowercase().contains("time"))
        assertTrue(es, es.lowercase().contains("how are you"))
    }

    @Test fun portugueseThroughRomance() {
        val m = model("en-ROMANCE")
        val out = m.translate("Good morning, my friend.", ">>pt_br<<")
        println("en-pt: $out")
        assertTrue(out, out.contains("Bom dia"))
    }

    @Test fun chineseNeedsItsToken() {
        val m = model("en-zh")
        val out = m.translate("I love you.", ">>cmn_Hans<<")
        println("en-zh: $out")
        assertTrue(out, out.contains("爱"))
    }

    @Test fun arabicWithItsToken() {
        val m = model("en-ar")
        val out = m.translate("Thank you very much, my friend.", ">>ara<<")
        println("en-ar: $out")
        assertTrue(out, out.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC })
    }

    @Test fun pivotThroughEnglish() {
        val en = model("mul-en").translate("Gracias por el stream, nos vemos mañana.")
        val de = model("en-de").translate(en)
        println("es>en>de: $en | $de")
        assertTrue(de, de.contains("Danke") || de.contains("danke"))
    }

    // ---- translate-pack-2: the into English model per chat language ----

    @Test fun spanishOwnModelBeatsTheGeneralOne() {
        val line = "Gracias por el stream, eres el mejor"
        val own = model("es-en").translate(line)
        // For comparison only: the general model, when it is there too.
        val general = runCatching { model("mul-en").translate(line) }.getOrNull()
        println("es-en: $own | mul-en: $general")
        assertTrue(own, own.lowercase().contains("stream"))
        assertTrue(own, own.lowercase().contains("best"))
    }

    @Test fun germanOwnModel() {
        val out = model("de-en").translate("Ich habe heute keine Zeit, aber morgen schaue ich wieder zu.")
        println("de-en: $out")
        assertTrue(out, out.lowercase().contains("time") && out.lowercase().contains("tomorrow"))
    }

    @Test fun portugueseThroughRomanceIntoEnglish() {
        val out = model("ROMANCE-en").translate("Obrigado pela live, você é demais!")
        println("pt>en (ROMANCE-en): $out")
        assertTrue(out, out.lowercase().contains("thank"))
    }

    @Test fun chineseOwnModel() {
        val out = model("zh-en").translate("谢谢你的直播，明天见！")
        println("zh-en: $out")
        assertTrue(out, out.lowercase().contains("thank"))
    }

    @Test fun russianOwnModel() {
        val out = model("ru-en").translate("Спасибо за стрим, до завтра!")
        println("ru-en: $out")
        assertTrue(out, out.lowercase().contains("thank"))
    }

    @Test fun pivotWithTheOwnModel() {
        val en = model("es-en").translate("Gracias por el stream, nos vemos mañana.")
        val de = model("en-de").translate(en)
        println("es>en>de (es-en): $en | $de")
        assertTrue(de, de.contains("Danke") || de.contains("danke"))
    }

    @Test fun memoryAndSpeed() {
        fun rss(): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: -1L
        val r = root
        assumeTrue(r != null && File(r, "models/en-fr/" + MarianModel.DECODER).isFile)
        // The first model also loads the natives, so the second one shows what a model costs.
        val warm = MarianModel(File(r, "models/en-fr"))
        warm.translate("Hello there")
        val before = rss()
        val m = MarianModel(File(r, "models/en-de"))
        val loaded = rss()
        val line = "That was an amazing play, I can not believe he survived that fight!"
        m.translate(line)
        val t0 = System.nanoTime()
        val out = m.translate(line)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val after = rss()
        m.close(); warm.close()
        println("en-de: $out | model rss kB: +${loaded - before} loaded, +${after - before} after two lines | $ms ms per line")
        assertTrue(out.isNotBlank())
    }

    @Test fun cancelStopsIt() {
        val m = model("en-es")
        var threw = false
        try { m.translate("This sentence will never be finished.", null) { true } } catch (e: java.util.concurrent.CancellationException) { threw = true }
        assertTrue(threw)
        // And the model still works afterwards.
        assertTrue(m.translate("Thank you").isNotBlank())
    }
}
