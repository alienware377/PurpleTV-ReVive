package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * X-ASR taking over from Kroko for English. Token lists are written as in SpeechLinesTest, pieces
 * split at "|", and are the model's real output on recorded streams: it writes punctuation as a
 * word of its own (" ."), which must never show as "night .".
 */
class SttEnglishUpgradeTest {

    private fun t(s: String): List<String> = s.split('|').filter { it.isNotEmpty() }

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

    private val first = t(" S|ho|t| in| the| head| by| B|o|x|y| and| not|ic|ed| maybe| in| t|ro|u|b|le| as| well| .| He| comes| out| of| the| pa|n|da| form| .| |M|i|ke| is")
    private val second = t(" and| just| kind| of| fa|ci|li|t|ate| a| come|ba|ck| ,| I| s|up|po|se| .| Well| ,| there| you| go| .| Not|ic|e| st|u|ck| around| too| long| .| And| that|'|s")

    @Test fun punctuationGoesOnTheWordBefore() {
        val f = Feed()
        f.grow(first)
        assertEquals(listOf("Shot in the head by Boxy and noticed maybe in trouble as well.", "He comes out of the panda form."), f.lines)
        assertEquals("Mike is", f.partial)
    }

    @Test fun commasAndShortSentencesInOneLine() {
        val f = Feed()
        f.grow(second)
        assertEquals(listOf("and just kind of facilitate a comeback, I suppose.", "Well, there you go. Notice stuck around too long."), f.lines)
        assertEquals("And that's", f.partial)
    }

    @Test fun partialNeverShowsASpaceBeforeAMark() {
        val sp = SpeechLines.Splitter()
        for (k in 1..first.size) {
            val st = sp.update(first.subList(0, k))
            for (s in st.lines + st.partial) assertFalse(s, Regex(" [.,!?:;]").containsMatchIn(s))
        }
    }

    @Test fun aMarkAfterAPauseJoinsWithNoSpace() {
        val f = Feed()
        f.take(f.sp.endpoint(t(" Oh| ,| that| was| so"), false))
        assertEquals(listOf("Oh, that was so"), f.lines)
        f.take(f.sp.update(t(" .| They")))
        assertEquals(listOf("."), f.joins)
        assertEquals("They", f.partial)
    }

    @Test fun aForcedCutKeepsTheLastWordsAndItsMark() {
        val f = Feed()
        f.take(f.sp.endpoint(t(" it| does"), true))
        assertTrue(f.sp.holding)
        f.take(f.sp.flush(t(" ,| but| getting| b|li|nk| into| one| more")))
        assertEquals(listOf("it does, but getting blink into one more"), f.lines)
    }

    /** The real rewrite a beam search made on a stream: "agg's" became "aggs", one token fewer. */
    @Test fun aBeamRewriteNeitherLosesNorRepeatsAWord() {
        val f = Feed()
        f.grow(t(" and| then| there|'|s| not| the| a|gg|'|s| .| You| will| just"))
        assertEquals(listOf("and then there's not the agg's."), f.lines)
        assertEquals("You will just", f.partial)
        f.take(f.sp.update(t(" and| then| there|'|s| not| the| a|gg|s| .| You| will| just| get| less")))
        assertEquals(listOf("and then there's not the agg's."), f.lines)
        assertEquals("You will just get less", f.partial)
    }

    /** The other real shape: a rewrite that makes the text longer before the place reached. */
    @Test fun aBeamRewriteThatGrowsKeepsThePlace() {
        val f = Feed()
        f.grow(t(" and| then| there|'|s| not| the| so| .| Okay| ."))
        assertEquals(listOf("and then there's not the so."), f.lines)
        f.take(f.sp.update(t(" and| then| there|'|s| not| the| so| o|k| .| Okay| .| They")))
        assertEquals(listOf("and then there's not the so."), f.lines)
        assertEquals("Okay. They", f.partial)
    }

    @Test fun aBeamRewriteOfTheLastWordKeepsTheNextLine() {
        val f = Feed()
        f.grow(t(" and| then| there|'|s| not| the| b|a|g|s| .| You| will| just"))
        assertEquals(listOf("and then there's not the bags."), f.lines)
        f.take(f.sp.update(t(" and| then| there|'|s| not| the| bag| .| You| will| just| get| less")))
        assertEquals("You will just get less", f.partial)
    }

    @Test fun aBeamRewriteThatDropsTheMarkKeepsTheNextLine() {
        val f = Feed()
        f.grow(t(" and| then| there|'|s| not| the| b|a|g|s| .| You| will| just"))
        f.take(f.sp.update(t(" and| then| there|'|s| not| the| b|a|g|s| You| will| just| get")))
        assertEquals("You will just get", f.partial)
    }

    /** Real, from a meeting clip: the rewrite took "know." away and the next word must survive it. */
    @Test fun aWordTakenBackDoesNotCostTheNextOne() {
        val g = Feed()
        g.grow(t(" so| we| said| that| I| really| don|'|t| know| .| Just| some| way"))
        assertEquals(listOf("so we said that I really don't know."), g.lines)
        g.take(g.sp.update(t(" so| we| said| that| I| really| don|'|t| just| some| way| to| sort")))
        assertEquals("just some way to sort", g.partial)
    }

    /** Real: the old line was replaced, and the word after it must not be read as its full stop. */
    @Test fun aReplacedLineKeepsTheWordAfterIt() {
        val f = Feed()
        f.grow(t(" Get| that| light| out| of| mine| now| .| Fuck| it"))
        assertEquals(listOf("Get that light out of mine now."), f.lines)
        f.take(f.sp.update(t(" now| !| Fuck| .| Okay| then| we| can| go| on")))
        assertEquals("Fuck. Okay then we can go on", f.partial)
    }

    @Test fun aRepeatedPairDoesNotMoveThePlace() {
        val f = Feed()
        f.grow(t(" I| mean| you| know| it| was| fine| and| I| said| you| know| we| could| you| know| just| go"))
        assertEquals(listOf("I mean you know it was fine and I said you know"), f.lines)
        f.take(f.sp.update(t(" I| mean| you| know| it| was| fine| and| I| said| ya| know| we| could| you| know| just| go")))
        assertEquals("we could you know just go", f.partial)
    }

    @Test fun twoWordsMergedOrOneSplitKeepsTheNextLine() {
        val f = Feed()
        f.grow(t(" so| we| did| it| all| and| came| back| any| way| .| we| got| home| late"))
        assertEquals(listOf("so we did it all and came back any way."), f.lines)
        f.take(f.sp.update(t(" so| we| did| it| all| and| came| back| anyway| .| we| got| home| late")))
        assertEquals("we got home late", f.partial)
        val g = Feed()
        g.grow(t(" and| that| is| why| we| simply| cannot| .| stop| doing| it| now"))
        assertEquals(listOf("and that is why we simply cannot."), g.lines)
        g.take(g.sp.update(t(" and| that| is| why| we| simply| can| not| .| stop| doing| it| now")))
        assertEquals("stop doing it now", g.partial)
    }

    @Test fun aPieceAddedToTheLastWordDoesNotStartTheNextLine() {
        val f = Feed()
        f.grow(t(" so| we| went| back| to| the| old| house| and| got| the| book| from| my| mum| today"))
        assertEquals("from my mum today", f.partial)
        f.take(f.sp.update(t(" so| we| went| back| to| the| old| house| and| got| the| book|s| from| my| mum| today")))
        assertEquals("from my mum today", f.partial)
    }

    @Test fun aBestGuessThatShrinksDoesNotRepeatALine() {
        val f = Feed()
        val all = t(" and| then| there|'|s| not| the| a|gg|s| .| You| will| just")
        f.grow(all)
        f.take(f.sp.update(all.subList(0, 4)))
        assertEquals("You will just", f.partial)
        f.take(f.sp.update(all + t(" get")))
        assertEquals(listOf("and then there's not the aggs."), f.lines)
        assertEquals("You will just get", f.partial)
    }

    @Test fun chineseNeverShows() {
        // Real pieces of the model's vocabulary, the Chinese ones with its word mark as a space.
        val mixed = t(" I| said| 的| 槽| ，| okay| 。| Good| ，| man")
        val kept = SpeechLines.withoutCjk(mixed)
        assertEquals("I said okay. Good, man", SpeechLines.text(kept))
        assertTrue(SpeechLines.withoutCjk(t(" 的| 槽| 。")).isEmpty())
        assertEquals(first, SpeechLines.withoutCjk(first))
        // A bare word mark before a character that goes out leaves one space.
        assertEquals("well done", SpeechLines.text(SpeechLines.withoutCjk(t(" well| |污| done"))))
    }

    @Test fun chineseLeftOutStillGrowsAtTheEnd() {
        val mixed = t(" I| said| 的| 槽| ，| okay| 。| Good| ，| man")
        var before = emptyList<String>()
        for (k in 1..mixed.size) {
            val now = SpeechLines.withoutCjk(mixed.subList(0, k))
            assertEquals(before, now.subList(0, before.size))
            before = now
        }
    }

    @Test fun xasrIsNotOfferedAndIsSetUpForStreaming() {
        val x = SttModels.EN_XASR
        val en = SttModels.EN
        assertTrue(x !in SttModels.ALL)
        assertTrue(SttModels.ALL.none { it.asset == x.asset || it.dir == x.dir || it.tag == x.tag })
        assertEquals(listOf(en, SttModels.byLang("en-slang")), SttModels.dedicated("en"))
        assertEquals("en", x.sourceLang)
        assertEquals(SpeechScript.Mode.LATIN, x.script)
        assertTrue(x.cased)
        assertEquals(x.kind.roles.toSet(), x.files.keys)
        for (f in x.files.values) assertTrue(f, f in x.entries)
        for (pin in x.entries.values) assertTrue(pin.bytes > 0 && pin.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue("LICENSE-Apache-2.0.txt" in x.entries && "LICENSE-x-asr.txt" in x.entries)
        val dir = File("m")
        val c = SttModels.modelConfig(x, dir, 1)
        assertEquals("zipformer2", c.modelType)
        assertEquals(File(dir, "joiner.int8.onnx").absolutePath, c.transducer.joiner)
    }

    @Test fun decodingIsOneConstantPerModel() {
        assertSame(SttModels.EN_DECODING, SttModels.EN.decoding)
        assertSame(SttModels.EN_XASR_DECODING, SttModels.EN_XASR.decoding)
        assertEquals("greedy_search", SttModel.Decoding.GREEDY.method)
        assertEquals("modified_beam_search", SttModel.Decoding.BEAM4.method)
        assertEquals(4, SttModel.Decoding.BEAM4.maxPaths)
    }

    @Test fun englishStaysOnItsOwnModelWithXasrOnThePhone() {
        assertFalse(SttModels.EN_XASR_ON)
        val x = SttModels.EN_XASR
        val en = SttModels.EN
        val slang = SttModels.byLang("en-slang")!!
        fun up(m: SttModel, bad: Set<SttModel> = emptySet(), vararg have: SttModel) =
            SttModels.upgraded(m, { it in bad }) { it in have }
        assertSame(en, up(en, emptySet(), en))
        assertSame(en, up(en, emptySet(), en, x))
        assertSame(en, up(en, setOf(x), en, x))
        assertSame(slang, up(slang, emptySet(), slang, x))

        fun pick(bad: Set<SttModel>, vararg have: SttModel) =
            SttModels.autoPick("en", bad = { it in bad }) { it in have }
        assertSame(en, pick(emptySet(), en).model)
        assertSame(en, pick(emptySet(), en, x).model)
        // X-ASR alone does not count as having English: the released model is named as missing.
        assertSame(en, pick(emptySet(), x).missing)
        assertSame(en, pick(emptySet()).missing)
    }

    @Test fun russianStillMovesToGigaam() {
        val g = SttModels.RU_GIGAAM
        val ru = SttModels.byLang("ru")!!
        assertSame(g, SttModels.upgraded(ru, { false }) { it === ru || it === g })
    }
}
