package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Token lists here are written as "| It|'|s| this": pieces split at "|", a piece that starts a word
 * begins with a space, as sherpa-onnx gives them. Most come from the English model on real streams.
 */
class SpeechLinesTest {

    private fun t(s: String): List<String> = s.split('|').filter { it.isNotEmpty() }

    /** What a streaming result looks like as the model decodes: one more token each time. */
    private class Feed(val sp: SpeechLines.Splitter = SpeechLines.Splitter()) {
        val lines = ArrayList<String>()
        val joins = ArrayList<String>()
        var partial = ""
        fun take(st: SpeechLines.Step) {
            if (st.join.isNotEmpty()) joins.add(st.join)
            lines.addAll(st.lines)
            partial = st.partial
        }
        fun grow(tokens: List<String>) { for (k in 1..tokens.size) take(sp.update(tokens.subList(0, k))) }
    }

    @Test fun wordStarts() {
        val p = listOf(" the", "s", ".", " .", " ", " (", "中", "。", "\u2581the", " -", "ing")
        val starts = p.indices.filter { SpeechLines.startsWord(p, it) }.map { p[it] }
        assertEquals(listOf(" the", " ", " (", "中", "\u2581the"), starts)
    }

    @Test fun textJoinsPiecesAndBytes() {
        assertEquals("It's this", SpeechLines.text(t(" It|'|s| this")))
        assertEquals("We'll go", SpeechLines.text(t(" |W|e|'|ll| go")))
        assertEquals("你好", SpeechLines.text(listOf("<0xE4>", "<0xBD>", "<0xA0>", "好")))
    }

    @Test fun aSentenceEndsALineButNeverAWordInHalf() {
        val f = Feed()
        // "Thanks for in" / "dulge" is what an 8 s reset made of this.
        f.grow(t(" Thanks| for| in|dul|ging| me|.| Should| we| apolog|ize| for| that|?| No|."))
        assertEquals(listOf("Thanks for indulging me. Should we apologize for that?"), f.lines)
        assertEquals("No.", f.partial)
        val g = Feed()
        g.grow(t(" It|'|s| cool|.| I| mean|,| we|'|re| just| going| to| keep| blast|ing| north|.| We| keep"))
        assertEquals(listOf("It's cool. I mean, we're just going to keep blasting north."), g.lines)
        assertEquals("We keep", g.partial)
    }

    @Test fun shortSentencesStayTogether() {
        val f = Feed()
        f.grow(t(" Yes|.| No|.| That|'|s| what| I|'|m| saying|.| So"))
        assertEquals(listOf("Yes. No. That's what I'm saying."), f.lines)
        assertEquals("So", f.partial)
    }

    @Test fun longTalkIsCutAtASentenceEndACommaOrAfterTwelveWords() {
        val f = Feed()
        f.grow(t(" every| single| blue| sign| we| see|,| which| is| our| saving| grace| our| absolute| lord| and| savior| who| we| follow"))
        // Fifteen words in with no sentence end: cut back at the comma after six.
        assertEquals(listOf("every single blue sign we see,"), f.lines)
        assertEquals("which is our saving grace our absolute lord and savior who we follow", f.partial)
        // A short sentence first: its end is a better place than a comma further on.
        val s = Feed()
        s.grow(t(" Yes|,| that|'|s| what| I|'|m| saying|.| So| it|'|s| like|,| should| we| apologize| that| things| are| going| well"))
        assertEquals(listOf("Yes, that's what I'm saying."), s.lines)
        // A sentence that ends a few words past twelve ends the line there.
        val e = Feed()
        e.grow(t(" And| so| we|'|re| here| and| is| it| how| do| you| know| the| town| name|?| How| are"))
        assertEquals(listOf("And so we're here and is it how do you know the town name?"), e.lines)
        // No marks at all: twelve words a line.
        val g = Feed()
        g.grow(t((1..30).joinToString("") { "| w$it" }))
        assertEquals(listOf((1..12).joinToString(" ") { "w$it" }, (13..24).joinToString(" ") { "w$it" }), g.lines)
        assertEquals((25..30).joinToString(" ") { "w$it" }, g.partial)
    }

    @Test fun aWholeBatchAtOnceIsCutIntoSeveralLines() {
        val sp = SpeechLines.Splitter()
        val st = sp.update(t((1..30).joinToString("") { "| w$it" }))
        assertEquals(2, st.lines.size)
        assertEquals((25..30).joinToString(" ") { "w$it" }, st.partial)
    }

    @Test fun aPauseFinishesTheLineAndItsStopFollowsIt() {
        val f = Feed()
        f.grow(t(" Get| blue"))
        f.take(f.sp.endpoint(t(" Get| blue"), forced = false))
        assertEquals(listOf("Get blue"), f.lines)
        assertEquals("", f.partial)
        // The question mark comes after the reset: nothing to show until a word starts.
        f.take(f.sp.update(t("?")))
        assertTrue(f.joins.isEmpty())
        assertEquals("", f.partial)
        f.grow(t("?| I| can"))
        assertEquals(listOf("?"), f.joins)
        assertEquals("I can", f.partial)
        assertEquals(listOf("Get blue"), f.lines)
    }

    @Test fun theRestOfAWordAfterAPauseGoesOnTheLineBefore() {
        val f = Feed()
        f.take(f.sp.endpoint(t(" Thanks| for| in"), forced = false))
        f.grow(t("dul|ge| me"))
        assertEquals(listOf("dulge"), f.joins)
        assertEquals("me", f.partial)
        // A lone full stop that ends the next stream is still a piece for the line before.
        val g = Feed()
        g.take(g.sp.endpoint(t(" |j|e|al|ous"), forced = false))
        g.take(g.sp.endpoint(t("."), forced = false))
        assertEquals(listOf("jealous"), g.lines)
        assertEquals(listOf("."), g.joins)
    }

    @Test fun aForcedResetCarriesTheWordsOn() {
        val f = Feed()
        // The safety limit lands inside "the": the e comes after the reset.
        f.grow(t(" We| give| you| th"))
        f.take(f.sp.endpoint(t(" We| give| you| th"), forced = true))
        assertTrue(f.sp.holding)
        assertTrue(f.lines.isEmpty())
        assertEquals("We give you th", f.partial)
        f.grow(t("e| level|."))
        assertEquals("We give you the level.", f.partial)
        f.take(f.sp.endpoint(t("e| level|."), forced = false))
        assertEquals(listOf("We give you the level."), f.lines)
        assertFalse(f.sp.holding)
    }

    @Test fun carriedWordsCountTowardsTheNextCut() {
        val f = Feed()
        f.take(f.sp.endpoint(t((1..10).joinToString("") { "| w$it" }), forced = true))
        f.grow(t((11..18).joinToString("") { "| w$it" }))
        assertEquals(listOf((1..12).joinToString(" ") { "w$it" }), f.lines)
        assertEquals((13..18).joinToString(" ") { "w$it" }, f.partial)
        assertFalse(f.sp.holding)
    }

    @Test fun aFlushEndsEverythingAndOpensNothing() {
        val f = Feed()
        f.take(f.sp.endpoint(t(" one| two| thr"), forced = true))
        f.take(f.sp.flush(emptyList()))
        assertEquals(listOf("one two thr"), f.lines)
        // After a gap in the sound a stop has no line to go on, and a line never starts with it.
        f.grow(t(".| And| both"))
        assertTrue(f.joins.isEmpty())
        assertEquals("And both", f.partial)
    }

    @Test fun silenceWithNothingSaidClosesTheLine() {
        val f = Feed()
        f.take(f.sp.endpoint(t(" It|'|s| this| sign| today|.| Okay"), forced = false))
        f.take(f.sp.endpoint(emptyList(), forced = false))
        f.grow(t(",| I| mean"))
        assertTrue(f.joins.isEmpty())
        assertEquals("I mean", f.partial)
    }

    @Test fun nothingIsLostOverManyCuts() {
        val f = Feed()
        val words = t(" so| it|'|s| like|,| should| we| apolog|ize| that| things| are| going| well| for| us|?| I| sup|pose| not|." +
            "| Should| we| f|u|ck|ing| apologize| for| that|?| No|.| I| want| to| apologize| for| a| damn| thing|.")
        f.grow(words)
        f.take(f.sp.endpoint(words, forced = false))
        assertEquals(SpeechLines.text(words), f.lines.joinToString(" "))
        assertEquals(listOf("so it's like, should we apologize that things are going well for us?",
            "I suppose not. Should we fucking apologize for that?", "No. I want to apologize for a damn thing."), f.lines)
    }

    /**
     * The Chinese model's tokens are byte-level BPE. These are its own for a recorded sentence
     * (sherpa-onnx 1.13.8 with the shipped model), and the text it wrote for them.
     */
    private val zhText = "不要问你的国家能为你做什么而要问你能为你的国家做什么"
    private val zhTokens = listOf("▁ƋŞĮ", "▁ƏŌĢ", "▁ƐĺŔ", "▁ƋţŅ", "▁ƎĽĥ", "▁ƌľţ", "▁ƌŔŜ", "▁ƏĤţ", "▁ƋŞŠ", "▁ƋţŅ",
        "▁ƌĢĽ", "▁Ƌšġ", "▁Ƌşĩ", "▁Əġĭ", "▁ƏŌĢ", "▁ƐĺŔ", "▁ƋţŅ", "▁ƏĤţ", "▁ƋŞŠ", "▁ƋţŅ", "▁ƎĽĥ", "▁ƌľţ", "▁ƌŔŜ",
        "▁ƌĢĽ", "▁Ƌšġ", "▁Ƌşĩ")

    @Test fun byteLevelTokensAreReadBackAsText() {
        val p = SpeechLines.fromByteBpe(zhTokens)
        assertEquals(zhText.map { it.toString() }, p)
        assertEquals(zhText, SpeechLines.text(p))
        // The bytes of a character that is not whole yet wait for the rest of it: 国 is ƌ ľ ţ.
        assertEquals(listOf("不"), SpeechLines.fromByteBpe(listOf("▁ƋŞĮ", "▁ƌ", "ľ")))
        assertEquals(listOf("不", "国"), SpeechLines.fromByteBpe(listOf("▁ƋŞĮ", "▁ƌ", "ľ", "ţ")))
        // Printable ASCII stands for itself: a word in Latin letters is a word like any other.
        assertEquals(listOf("不", " ok"), SpeechLines.fromByteBpe(listOf("▁ƋŞĮ", "▁o", "k")))
        assertEquals("不 ok", SpeechLines.text(listOf("不", " ok")))
        assertTrue(SpeechLines.fromByteBpe(listOf("<unk>")).isEmpty())
    }

    @Test fun chineseIsCutByCharacters() {
        val f = Feed()
        // The sentence twice, as the model gives it: one more token each time.
        val tokens = zhTokens + zhTokens
        for (k in 1..tokens.size) f.take(f.sp.update(SpeechLines.fromByteBpe(tokens.subList(0, k))))
        val s = zhText + zhText
        // Two characters count as a word: the line is cut at 24 once 30 are finished.
        assertEquals(listOf(s.take(24)), f.lines)
        assertEquals(s.drop(24), f.partial)
        f.take(f.sp.endpoint(SpeechLines.fromByteBpe(tokens), forced = false))
        assertEquals(listOf(s.take(24), s.drop(24)), f.lines)
    }

    /** An endpoint past the safety limit is only taken for its cut when the talk was still going on. */
    @Test fun aPauseAfterNearlyThirtySecondsIsAPause() {
        // Talk until 29.5 s, then quiet: the pause ends it once 30.3 s are decoded, past the limit,
        // and the model has been given a little more sound than it decoded by then.
        assertFalse(SpeechLines.forcedEnd(30.5f, 29.5f, 30f, 0.8f))
        // The limit in the middle of talk: the last word came just before it.
        assertTrue(SpeechLines.forcedEnd(30.1f, 29.9f, 30f, 0.8f))
        // Short of the limit, an endpoint is always a pause.
        assertFalse(SpeechLines.forcedEnd(12f, 11.9f, 30f, 0.8f))
        // So the words of such a long stretch all come out as final lines, and the line stays open
        // for the rest of its last word.
        val f = Feed()
        val words = t((1..20).joinToString("") { "| w$it" } + "| in")
        f.grow(words)
        f.take(f.sp.endpoint(words, forced = SpeechLines.forcedEnd(30.5f, 29.5f, 30f, 0.8f)))
        assertFalse(f.sp.holding)
        assertEquals("", f.partial)
        assertEquals(listOf((1..12).joinToString(" ") { "w$it" }, (13..20).joinToString(" ") { "w$it" } + " in"), f.lines)
        f.grow(t("dulge| me"))
        assertEquals(listOf("dulge"), f.joins)
        assertEquals("me", f.partial)
    }

    @Test fun glueAndTail() {
        assertEquals("Hello there world", SpeechLines.glue(listOf("Hello there", " ", "world ")))
        assertEquals("今天天气", SpeechLines.glue(listOf("今天", "天气")))
        assertEquals("short", SpeechLines.tail("short", 10))
        assertEquals("…cccc dddd", SpeechLines.tail("aaaa bbbb cccc dddd", 10))
    }

    /** 16 kHz sound: "words" of a 200 Hz tone, with the given gaps (start s, length s) left quiet. */
    private fun talk(seconds: Float, gaps: List<Pair<Float, Float>>, dipAt: Float = -1f): FloatArray {
        val n = (seconds * 16_000).toInt()
        return FloatArray(n) { i ->
            val s = i / 16_000f
            val quiet = gaps.any { (a, l) -> s >= a && s < a + l }
            val amp = when {
                quiet -> 0.002f
                dipAt >= 0 && s >= dipAt && s < dipAt + 0.03f -> 0.05f
                else -> 0.3f
            }
            amp * sin(2 * PI * 200 * i / 16_000).toFloat()
        }
    }

    @Test fun aLongSentenceIsCutInItsLongestPause() {
        // A short gap between words late on, and a real pause earlier: the pause wins.
        val x = talk(3f, listOf(1.0f to 0.20f, 2.5f to 0.04f))
        val c = SpeechLines.quietCut(x, 8_000, x.size)
        assertTrue("cut at $c", c in 16_000 until 19_200)
        // Two pauses as long: the later one, so the line is as long as it can be.
        val y = talk(3f, listOf(1.0f to 0.1f, 2.2f to 0.1f))
        val d = SpeechLines.quietCut(y, 8_000, y.size)
        assertTrue("cut at $d", d in 35_200 until 36_800)
    }

    @Test fun talkWithoutAPauseIsCutAtItsQuietestMoment() {
        val x = talk(3f, emptyList(), dipAt = 1.7f)
        val c = SpeechLines.quietCut(x, 8_000, x.size)
        assertTrue("cut at $c", c in 27_000 until 28_000)
        // Too little sound to look at: the end.
        assertEquals(100, SpeechLines.quietCut(x, 0, 100))
    }
}
