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

    /** A phone language with no file: the text stays English, and so do the names put into it. */
    @Test fun shownLanguageHasAFile() {
        Locale.setDefault(Locale.forLanguageTag("pl"))
        assertEquals("pl", I18n.lang())
        assertEquals("en", I18n.shownLang())
        assertEquals("Portuguese", SttModels.langName("pt", I18n.shownLang()))
        Locale.setDefault(Locale.FRENCH)
        assertEquals("fr", I18n.shownLang())
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("en", I18n.shownLang())
    }

    @Test fun templatesFillTheirHoles() {
        Locale.setDefault(Locale.FRENCH)
        assertEquals(". 3 nouvelles versions depuis.", I18n.t(". 3 new versions since then."))
    }

    /** Auto's caption box notes and menu line, with a language name or a list in them. */
    @Test fun autoNotesAreTranslated() {
        Locale.setDefault(Locale.forLanguageTag("es"))
        assertEquals("Para ver subtítulos en Español: Configuración de Twitch > PurpleTV > Reproductor y toca «Descargar modelo de voz»",
            I18n.t("To get Spanish captions, go to Twitch Settings > PurpleTV > Player and tap Download speech model"))
        assertEquals("Hay un modelo de voz propio para Japonés, que da subtítulos mucho mejores. Para obtenerlo: Configuración de Twitch > PurpleTV > Reproductor y toca «Descargar modelo de voz»",
            I18n.t("Japanese has its own speech model, which gives much better captions. To get it, go to Twitch Settings > PurpleTV > Player and tap Download speech model"))
        val line = I18n.t("Auto: each stream uses the speech model for its language. Downloaded: Inglés, Japonés. " +
            "The all-languages model (Whisper base) finds a stream's language as it starts, and hears languages that have no model of their own. " +
            "Tap to download a language")
        assertTrue(line, line.startsWith("Auto: cada directo usa el modelo de voz de su idioma. Descargados: Inglés, Japonés. "))
        assertEquals("¿Descargar el modelo de voz para todos los idiomas (Whisper base)?", I18n.t("Download the all-languages speech model (Whisper base)?"))
        assertEquals("Español (descargando 45%)", I18n.t("Español (downloading 45%)"))
        assertEquals("Comprobando y descomprimiendo: 95%", I18n.t("Checking and unpacking: 95%"))
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
