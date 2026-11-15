package tv.purple.xp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class I18nTest {

    private val was = Locale.getDefault()

    @After fun restore() { Locale.setDefault(was) }

    @Test fun followsThePhoneLanguage() {
        Locale.setDefault(Locale.FRENCH)
        assertEquals("fr", I18n.lang())
        assertEquals("Mises à jour", I18n.t("Updates"))
        // Unknown text stays as it is.
        assertEquals("No such line here", I18n.t("No such line here"))
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Updates", I18n.t("Updates"))
    }

    @Test fun templatesFillTheirHoles() {
        Locale.setDefault(Locale.FRENCH)
        assertEquals(". 3 nouvelles versions depuis.", I18n.t(". 3 new versions since then."))
    }

    @Test fun patternMatchesWholeText() {
        val p = I18n.pattern("Download the {0}?")
        assertEquals("Japanese speech model", p.matchEntire("Download the Japanese speech model?")!!.groupValues[1])
        assertTrue(p.matchEntire("Download the x? now") == null)
    }

    /** Every translation file has every placeholder of its template. */
    @Test fun filesKeepTheirPlaceholders() {
        val dir = java.io.File("src/main/resources/i18n")
        val holes = Regex("\\{\\d+\\}")
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name != "en.json" }) {
            val o = org.json.JSONObject(f.readText())
            val t = o.getJSONObject("templates")
            for (k in t.keys()) {
                assertEquals("${f.name}: $k", holes.findAll(k).map { it.value }.toSet(), holes.findAll(t.getString(k)).map { it.value }.toSet())
            }
            val all = f.readText()
            assertTrue(f.name, '\u2014' !in all && '\u2013' !in all)
        }
    }
}
