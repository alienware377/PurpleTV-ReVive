package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualChatWireTest {

    @Test fun replyCarriesTheParentId() {
        assertEquals("@reply-parent-msg-id=b34ccfc7-4977-403a-8a94-33c6bac34fb8 PRIVMSG #chan :hello",
            IrcParse.privmsg("chan", "hello", "b34ccfc7-4977-403a-8a94-33c6bac34fb8"))
    }

    @Test fun oddReplyIdIsLeftOut() {
        assertEquals("PRIVMSG #chan :hello", IrcParse.privmsg("chan", "hello", "a b;c"))
    }

    @Test fun actionIsNeverAReply() {
        assertTrue(IrcParse.privmsg("chan", "/me waves", "abc")!!.startsWith("PRIVMSG"))
    }

    @Test fun gifRangeIsInclusiveAndInUtf16() {
        val text = "\uD83D\uDE00 [Looking GIF]"
        val g = IrcParse.gifRanges("2-14|abc|https://media.example/a.gif", text).single()
        assertEquals("[Looking GIF]", text.substring(g.start, g.end))
        assertEquals("abc", g.id)
    }

    @Test fun brokenGifEntriesAreSkipped() {
        assertTrue(IrcParse.gifRanges("0-3|id", "text").isEmpty())
        assertTrue(IrcParse.gifRanges("0-99|id|https://a/b", "text").isEmpty())
        assertTrue(IrcParse.gifRanges("0-3|id|http://a/b", "text").isEmpty())
    }
}
