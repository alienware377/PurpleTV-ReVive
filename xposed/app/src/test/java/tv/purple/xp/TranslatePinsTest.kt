package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class TranslatePinsTest {

    @Test fun everyTargetHasItsModelPinned() {
        for (t in OpusEngine.TARGETS) {
            val m = TranslatePins.MODELS[t.model]
            assertNotNull("no pin for ${t.model}", m)
            for (f in listOf(MarianModel.ENCODER, MarianModel.DECODER, "source.spm", "vocab.json", "config.json"))
                assertTrue("${t.model} lacks $f", f in m!!.entries)
        }
        assertTrue(OpusEngine.INTO_EN in TranslatePins.MODELS)
        assertEquals("en", OpusEngine.TARGETS.first().code)
    }

    @Test fun everyChatLanguageHasItsIntoEnglishModel() {
        for (c in OpusEngine.CHAT_LANGS) {
            assertNotNull("${c.code} is not a target", OpusEngine.target(c.code))
            for (name in listOf(c.model) + c.fallbacks) {
                val m = TranslatePins.MODELS[name]
                assertNotNull("no pin for $name", m)
                for (f in listOf(MarianModel.ENCODER, MarianModel.DECODER, "source.spm", "vocab.json", "config.json"))
                    assertTrue("$name lacks $f", f in m!!.entries)
                // The new models live in their own release; translate-pack-1 never changes.
                assertEquals(name, TranslatePins.TAG_2, m!!.tag)
            }
        }
        // Every target except English has a better into English model.
        assertEquals(OpusEngine.TARGETS.map { it.code }.filter { it != "en" }, OpusEngine.CHAT_LANGS.map { it.code })
        for (t in OpusEngine.TARGETS) assertEquals(t.model, TranslatePins.TAG, TranslatePins.MODELS.getValue(t.model).tag)
    }

    @Test fun pinsLookSane() {
        val all = TranslatePins.MODELS.values.map { Triple(it.asset, it.sha256, it.entries) } +
            Triple(TranslatePins.ENGINE_ASSET, TranslatePins.ENGINE_SHA256, TranslatePins.ENGINE_ENTRIES)
        for ((asset, sha, entries) in all) {
            assertTrue(asset, asset.endsWith(".zip") && '/' !in asset)
            assertTrue(sha, sha.matches(Regex("[0-9a-f]{64}")))
            for ((name, pin) in entries) {
                assertTrue(name, ".." !in name && !name.startsWith("/"))
                assertTrue(pin.sha256.matches(Regex("[0-9a-f]{64}")) && pin.bytes > 0)
            }
        }
        assertTrue(TranslatePins.ENGINE_ORT_LIB in TranslatePins.ENGINE_ENTRIES)
        assertTrue(TranslatePins.ENGINE_JNI_LIB in TranslatePins.ENGINE_ENTRIES)
        // The asset names are what tells the packs apart on disk, so they must all differ.
        assertEquals(all.size, all.map { it.first }.toSet().size)
    }

    /** With -PptvTranslateDir set, the built zips must match the pins exactly. */
    @Test fun builtZipsMatchThePins() {
        val dir = System.getProperty("ptv.translate.dir")?.let { File(it) }
        assumeTrue(dir?.isDirectory == true)
        val zips = TranslatePins.MODELS.values.map { Triple(it.asset, it.sha256, it.bytes) } +
            Triple(TranslatePins.ENGINE_ASSET, TranslatePins.ENGINE_SHA256, TranslatePins.ENGINE_BYTES)
        var n = 0
        for ((asset, sha, bytes) in zips) {
            val f = File(dir, asset)
            if (!f.isFile) continue
            assertEquals(asset, bytes, f.length())
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val k = i.read(b); if (k < 0) break; md.update(b, 0, k) } }
            assertEquals(asset, sha, md.digest().joinToString("") { "%02x".format(it) })
            n++
        }
        assumeTrue(n > 0)
    }
}
