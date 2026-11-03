package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LangGuessTest {

    private fun lang(s: String) = LangGuess.guess(s).lang

    @Test fun commonLatinLanguages() {
        assertEquals("en", lang("I think this is the best game we have played all week"))
        assertEquals("es", lang("no sé por qué el juego está tan difícil hoy"))
        assertEquals("pt", lang("eu não sei porque o jogo está tão difícil hoje"))
        assertEquals("de", lang("ich weiß nicht warum das Spiel heute so schwer ist"))
        assertEquals("fr", lang("je ne sais pas pourquoi le jeu est si dur aujourd'hui"))
        assertEquals("it", lang("non so perché il gioco è così difficile oggi"))
        assertEquals("nl", lang("ik weet niet waarom het spel vandaag zo moeilijk is"))
        assertEquals("pl", lang("nie wiem dlaczego ta gra jest dzisiaj tak trudna"))
        assertEquals("tr", lang("bu oyun neden bu kadar zor bilmiyorum ama çok güzel"))
    }

    @Test fun otherScripts() {
        assertEquals("ru", lang("привет всем, как дела у вас сегодня?"))
        assertEquals("uk", lang("привіт усім, як у вас справи сьогодні? дуже добре"))
        assertEquals("zh", lang("这个游戏太难了"))
        assertEquals("ja", lang("このゲームは難しいです"))
        assertEquals("ko", lang("이 게임 너무 어려워요"))
        assertEquals("ar", lang("مرحبا كيف حالك اليوم"))
        assertEquals("el", lang("καλησπέρα σε όλους"))
    }

    @Test fun shortAndEmoteLinesAreUnknown() {
        for (s in listOf("", "   ", "LUL", "KEKW KEKW KEKW", "PogChamp catJAM monkaS", "gg", "lol", "hahaha",
                "kkkkkkk", "xD", "!!!", "123 456", "@someone", "https://twitch.tv/somebody", "W", "o7 o7",
                "jajajaja", "ok", "hi chat", "Sadge", "pog pog pog", "yes"))
            assertEquals("\"$s\"", LangGuess.UNKNOWN, lang(s))
    }

    @Test fun emotesAndNamesDoNotSwayIt() {
        assertEquals("es", lang("@StreamerName KEKW no sé por qué está tan mal LUL"))
        assertEquals("en", lang("PogChamp that was the best play I have ever seen"))
    }

    @Test fun confidenceIsHigherForClearLines() {
        val clear = LangGuess.guess("je ne sais pas pourquoi le jeu est si dur aujourd'hui")
        val thin = LangGuess.guess("le jeu est")
        assertTrue(clear.confidence in 0f..1f)
        assertTrue("${clear.confidence} vs ${thin.confidence}", !thin.known || clear.confidence > thin.confidence)
        assertTrue(clear.confidence >= 0.5f)
    }

    @Test fun englishChatWithGermanLookingWordsIsNotGerman() {
        for (t in listOf("nice hat man great stuff", "I am so in on this bro", "that was art man")) {
            val g = LangGuess.guess(t)
            org.junit.Assert.assertFalse("$t -> ${g.lang} ${g.confidence}", g.known && g.lang == "de" && g.confidence >= Translator.MIN_CONFIDENCE)
        }
    }
}
