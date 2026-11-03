package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakRecapTest {
    private var n = 0
    private fun line(who: String, text: String, emotes: List<String> = emptyList()) =
        ChatFeed.Line("id${n++}", who, text, emotes, "1", 0L)

    @Test fun picksTopicLinesInOrder() {
        val said = listOf(
            "okay so we are going to the boss now",
            "the boss has two phases and the second boss phase is hard",
            "let me grab some water real quick guys",
            "we beat the boss phase one easily",
            "thanks for the follow everybody appreciate it",
        )
        val p = BreakRecap.pickLines(said, 2)
        assertEquals(2, p.size)
        assertTrue(p.all { "boss" in it })
        // Kept in the order they were said.
        assertTrue(said.indexOf(p[0]) < said.indexOf(p[1]))
    }

    @Test fun shortLinesAndRepeatsDropped() {
        val p = BreakRecap.pickLines(listOf("yeah", "uh huh", "this is a real line", "this is a real line"), 3)
        assertEquals(listOf("this is a real line"), p)
    }

    @Test fun longLinesTrimmed() {
        val long = "word ".repeat(40).trim()
        assertTrue(BreakRecap.pickLines(listOf(long), 1)[0].length <= 90)
    }

    @Test fun topEmotesCountMessagesNotRepeats() {
        val lines = listOf(
            line("a", "", listOf("KEKW", "KEKW", "KEKW")),
            line("b", "", listOf("KEKW")),
            line("c", "", listOf("LUL")),
            line("d", "", listOf("LUL")),
            line("e", "", listOf("LUL")),
            line("f", "", listOf("Once")),
        )
        assertEquals(listOf("LUL" to 3, "KEKW" to 2), BreakRecap.topEmotes(lines))
    }

    @Test fun topPhraseNeedsThreePeople() {
        val few = listOf(line("a", "W"), line("a", "W"), line("b", "W"))
        assertNull(BreakRecap.topPhrase(few))
        val many = listOf(line("a", "W"), line("b", "w!"), line("c", "W KEKW", listOf("KEKW")), line("d", "L"))
        assertEquals("W" to 3, BreakRecap.topPhrase(many))
    }
}
