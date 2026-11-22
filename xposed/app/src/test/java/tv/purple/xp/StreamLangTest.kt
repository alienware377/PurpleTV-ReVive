package tv.purple.xp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLangTest {

    /** Every tag seen on live channels (gql.twitch.tv, broadcastSettings.language, November 2026). */
    private val seenTags = listOf("EN", "ES", "PT", "DE", "FR", "IT", "RU", "ZH", "ZH_HK", "JA", "KO", "AR", "TL", "ID", "NO",
        "TH", "VI", "HI", "DA", "FI", "SV", "PL", "NL", "TR", "UK", "HU", "EL", "BG", "CS", "SK", "RO", "CA", "MS")

    @Test fun twitchTagsBecomeLanguageCodes() {
        assertEquals("en", StreamLang.fromTwitch("EN"))
        assertEquals("ja", StreamLang.fromTwitch("JA"))
        assertEquals("pt", StreamLang.fromTwitch(" pt "))
        // Hong Kong Chinese: Twitch writes the enum name; a dash is read the same way.
        assertEquals("zh", StreamLang.fromTwitch("ZH_HK"))
        assertEquals("zh", StreamLang.fromTwitch("zh-hk"))
        // Tags that name no spoken language, and nothing at all.
        assertNull(StreamLang.fromTwitch("OTHER"))
        assertNull(StreamLang.fromTwitch("ASL"))
        assertNull(StreamLang.fromTwitch(null))
        assertNull(StreamLang.fromTwitch(""))
        assertNull(StreamLang.fromTwitch("E1"))
        // Old forms of codes, as Whisper writes them.
        assertEquals("he", StreamLang.fromTwitch("IW"))
        assertEquals("id", StreamLang.fromTwitch("IN"))
        assertEquals("no", StreamLang.fromTwitch("NB"))
        assertEquals("tl", StreamLang.fromTwitch("FIL"))
    }

    /** Whisper can be told every language a Twitch channel can be set to, so Auto never asks it for one it does not know. */
    @Test fun everyTwitchLanguageIsOneWhisperKnows() {
        for (t in seenTags) {
            val code = StreamLang.fromTwitch(t)
            assertNotNull(t, code)
            assertTrue(t, code in SttModels.WHISPER_LANGS)
            assertNotNull(t, SttModels.forced(code!!))
        }
    }

    @Test fun theRequestAsksForTheTagOnly() {
        val b = JSONObject(StreamLang.body("somechannel"))
        assertEquals(StreamLang.QUERY, b.getString("query"))
        assertEquals("somechannel", b.getJSONObject("variables").getString("l"))
        assertTrue("broadcastSettings{language}" in StreamLang.QUERY)
    }

    /** Replies as gql.twitch.tv gives them. */
    @Test fun repliesAreRead() {
        val pt = StreamLang.parse("""{"data":{"user":{"broadcastSettings":{"language":"PT"}}},"extensions":{"durationMilliseconds":23,"requestID":"01M822GD5JMD9MSGWNAA6D61ZK"}}""")!!
        assertEquals("pt", pt.lang)
        assertEquals("PT", pt.tag)
        val other = StreamLang.parse("""{"data":{"user":{"broadcastSettings":{"language":"OTHER"}}}}""")!!
        assertNull(other.lang)
        assertEquals("OTHER", other.tag)
        // No such channel: an answer, with no language.
        val gone = StreamLang.parse("""{"data":{"user":null},"extensions":{"durationMilliseconds":6}}""")!!
        assertNull(gone.lang)
        assertNull(StreamLang.parse("""{"data":{"user":{"broadcastSettings":{"language":null}}}}""")!!.lang)
        assertNull(StreamLang.parse("""{"data":{"user":{"broadcastSettings":null}}}""")!!.lang)
        // Not an answer: asked again later.
        assertNull(StreamLang.parse("""{"errors":[{"message":"service timeout"}]}"""))
        // Part of the data, with a service behind it failed: says nothing about the channel.
        assertNull(StreamLang.parse("""{"errors":[{"message":"service timeout","path":["user"]}],"data":{"user":null}}"""))
        assertNull(StreamLang.parse("""{"errors":[{"message":"service error","path":["user","broadcastSettings"]}],"data":{"user":{"broadcastSettings":null}}}"""))
        assertNull(StreamLang.parse("""{"errors":[{"message":"service error"}],"data":{"user":{"broadcastSettings":{"language":null}}}}"""))
        // A tag that did come is used, whatever else failed.
        assertEquals("de", StreamLang.parse("""{"errors":[{"message":"x","path":["other"]}],"data":{"user":{"broadcastSettings":{"language":"DE"}}}}""")!!.lang)
        assertNull(StreamLang.parse("""{"data":null}"""))
        assertNull(StreamLang.parse("<html>"))
    }

    @Test fun whisperNamesItsLanguage() {
        assertEquals("en", StreamLang.fromWhisper("en"))
        assertEquals("ja", StreamLang.fromWhisper("<|ja|>"))
        assertEquals("haw", StreamLang.fromWhisper(" haw "))
        // Cantonese is only in bigger Whisper models; told it, base would end the process.
        assertNull(StreamLang.fromWhisper("yue"))
        assertNull(StreamLang.fromWhisper(""))
        assertNull(StreamLang.fromWhisper("xx"))
    }

    /** Letters per second as Whisper base wrote them for real stream sound. */
    @Test fun onlyTalkCounts() {
        assertTrue(StreamLang.counts("ahead by Foxy and notice maybe in trouble as well.", 4.0f))
        assertTrue(StreamLang.counts("Do you know who we play first? Show now", 8.0f))
        // Too short to say much, however clear.
        assertFalse(StreamLang.counts("Unlocked a new pronoun.", 1.9f))
        // A word or two in seconds of sound: music or mumbling.
        assertFalse(StreamLang.counts("Lithia", 8.0f))
        assertFalse(StreamLang.counts("это деждано", 7.5f))
        assertFalse(StreamLang.counts("(upbeat music)", 6.0f))
        assertFalse(StreamLang.counts("", 3.0f))
        // Chinese and Japanese characters count for more than a letter.
        assertTrue(StreamLang.counts("それをって、皆様に見上げている", 2.9f))
        assertTrue(StreamLang.counts("我们今天玩这个游戏", 3.0f))
    }

    @Test fun twoSentencesConfirmTheTag() {
        assertNull(StreamLang.decide(emptyList(), "en"))
        assertNull(StreamLang.decide(listOf("en"), "en"))
        assertEquals("en", StreamLang.decide(listOf("en", "en"), "en")!!.lang)
        assertEquals("es", StreamLang.decide(listOf("en", "es", "es"), "es")!!.lang)
        // Not two the same yet: one more.
        assertNull(StreamLang.decide(listOf("en", "es"), "es"))
    }

    @Test fun theSoundOverrulesAWrongTag() {
        // Two of the first three in another language, none in the tag's.
        assertNull(StreamLang.decide(listOf("es", "es"), "en"))
        assertEquals("es", StreamLang.decide(listOf("es", "es", "es"), "en")!!.lang)
        assertEquals("es", StreamLang.decide(listOf("es", "fr", "es"), "en")!!.lang)
        // One of them in the tag's language: the tag stays.
        assertEquals("en", StreamLang.decide(listOf("es", "en", "es"), "en")!!.lang)
        assertEquals("es", StreamLang.decide(listOf("en", "en", "es"), "es")!!.lang)
        // No tag: the first two that agree.
        assertEquals("ko", StreamLang.decide(listOf("ko", "ko"), null)!!.lang)
        assertNull(StreamLang.decide(listOf("ko", "ja"), null))
    }

    @Test fun withoutAgreementTheTagStays() {
        assertEquals("ru", StreamLang.decide(listOf("ru", "pl", "ja"), "ru")!!.lang)
        assertEquals("en", StreamLang.decide(listOf("ru", "pl", "ja"), "en")!!.lang)
        // No tag either: no language known, Whisper goes on finding it per sentence.
        val none = StreamLang.decide(listOf("ru", "pl", "ja"), null)!!
        assertNull(none.lang)
        // Many sentences heard, too few of them talk: the tag.
        assertEquals("de", StreamLang.decide(listOf("en"), "de", StreamLang.MAX_HEARD)!!.lang)
        assertNull(StreamLang.decide(listOf("en"), "de", StreamLang.MAX_HEARD - 1))
    }

    /**
     * The check run over Whisper base's own sentences from the start of recorded streams: which
     * language it heard, what it wrote and how long each sentence was.
     */
    @Test fun recordedStreams() {
        fun check(tag: String?, vararg s: Triple<String, String, Float>): String? {
            val votes = ArrayList<String>()
            for ((k, x) in s.withIndex()) {
                val code = StreamLang.fromWhisper(x.first)
                if (code != null && StreamLang.counts(x.second, x.third)) votes.add(code)
                StreamLang.decide(votes, tag, k + 1)?.let { return it.lang }
            }
            error("no answer")
        }
        // A Spanish channel that starts with an English song, then talks.
        assertEquals("es", check("es",
            Triple("en", "our pockets fully loaded.", 2.1f),
            Triple("en", "Lithia", 8.0f),
            Triple("es", "me prometo el sila Luna", 8.0f),
            Triple("es", "Lucía la gente en Twitter te odia pero yo te amo", 8.0f),
            Triple("es", "no pasa nada está bien entiendo que", 4.2f)))
        // A Russian channel with game sound: no two agree, the tag stays.
        assertEquals("ru", check("ru",
            Triple("ru", "и ключ прикладывается", 2.9f),
            Triple("ru", "это деждано", 7.5f),
            Triple("pl", "Byśmy nosią sprazić.", 2.9f),
            Triple("ja", "それをって、皆様に見上げている", 2.9f)))
        // An English channel whose tag was left at another language.
        assertEquals("en", check("ru",
            Triple("en", "I push lane for you as well.", 6.0f),
            Triple("en", "It looks very empty guys", 4.2f),
            Triple("en", "I think it has to be I know. I'm running beat", 5.8f)))
    }
}
