package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatColorsTest {

    private val chatBg = 0xFF18181B.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun hex(c: Int) = "#%08X".format(c)
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test fun paletteIsTwitchs() {
        assertEquals(15, ChatColors.PALETTE.size)
        assertEquals(0xFFFF0000.toInt(), ChatColors.PALETTE[0])
        assertEquals(0xFF1E90FF.toInt(), ChatColors.PALETTE[11])
        assertEquals(0xFF00FF7F.toInt(), ChatColors.PALETTE[14])
    }

    @Test fun forNameIndexesLikeTheApp() {
        // "a".hashCode() is 97, and 97 % 15 is 7.
        assertEquals(ChatColors.PALETTE[7], ChatColors.forName("a"))
        // This login hashes to Int.MIN_VALUE: the remainder comes first, so abs() cannot overflow.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        assertEquals(ChatColors.PALETTE[8], ChatColors.forName("polygenelubricants"))
        assertEquals(ChatColors.forName("viewer_one"), ChatColors.forName("viewer_one"))
    }

    @Test fun parse() {
        assertEquals(0xFF1E90FF.toInt(), ChatColors.parse("#1E90FF"))
        assertEquals(0xFF1E90FF.toInt(), ChatColors.parse("#1e90ff"))
        assertEquals(0xFF1E90FF.toInt(), ChatColors.parse("1E90FF"))
        assertEquals(0x801E90FF.toInt(), ChatColors.parse("#801E90FF"))
        assertEquals(black, ChatColors.parse("#000000"))
        assertNull(ChatColors.parse(null))
        assertNull(ChatColors.parse(""))
        assertNull(ChatColors.parse("#"))
        assertNull(ChatColors.parse("#12345"))
        assertNull(ChatColors.parse("#GGGGGG"))
    }

    @Test fun contrastMatchesWcag() {
        assertEquals(21.0, ChatColors.contrast(white, black), 1e-9)
        assertEquals(1.0, ChatColors.contrast(chatBg, chatBg), 1e-9)
        assertEquals(0.0, ChatColors.luminance(black), 1e-12)
        assertEquals(1.0, ChatColors.luminance(white), 1e-9)
    }

    @Test fun readableColoursAreKept() {
        assertEquals(white, ChatColors.readable(white, chatBg))
        assertEquals(0xFF00FF7F.toInt(), ChatColors.readable(0xFF00FF7F.toInt(), chatBg))
        assertEquals(0xFF1E90FF.toInt(), ChatColors.readable(0xFF1E90FF.toInt(), chatBg))
        assertEquals(black, ChatColors.readable(black, white))
    }

    @Test fun tooDarkOnDarkBecomesGrey() {
        assertEquals(0xFF7A7A7A.toInt(), ChatColors.readable(black, chatBg))
        // Pure blue is dark enough (luminance 0.07) to land under the floor too.
        assertEquals(0xFF7A7A7A.toInt(), ChatColors.readable(0xFF0000FF.toInt(), chatBg))
        assertEquals("no background reads as black, with the light grey",
            0xFFEFEFF1.toInt(), ChatColors.readable(0xFF0000FF.toInt(), 0))
    }

    @Test fun tooLightOnLightBecomesGrey() {
        assertEquals(0xFF717171.toInt(), ChatColors.readable(0xFFF8F8F8.toInt(), white))
        assertEquals(0xFF717171.toInt(), ChatColors.readable(white, white))
    }

    @Test fun midColoursAreLightenedOnDark() {
        val sea = 0xFF2E8B57.toInt()
        assertTrue(ChatColors.contrast(sea, chatBg) < 4.5)
        val out = ChatColors.readable(sea, chatBg)
        assertNotEquals(sea, out)
        assertTrue("reaches the target: " + hex(out), ChatColors.contrast(out, chatBg) >= 4.5)
        assertTrue("lighter", ChatColors.luminance(out) > ChatColors.luminance(sea))
        assertTrue("still green: " + hex(out), green(out) > red(out) && green(out) > blue(out))
        assertEquals(0xFF3D9964.toInt(), out)
        assertEquals("pure red is a touch too dark here", 0xFFFF2913.toInt(), ChatColors.readable(0xFFFF0000.toInt(), chatBg))
    }

    @Test fun midColoursAreDarkenedOnLight() {
        val gold = 0xFFDAA520.toInt()
        val out = ChatColors.readable(gold, white)
        assertTrue("reaches the target: " + hex(out), ChatColors.contrast(out, white) >= 4.5)
        assertTrue("darker", ChatColors.luminance(out) < ChatColors.luminance(gold))
        assertTrue("still warm: " + hex(out), red(out) > blue(out))
        assertEquals(0xFF916700.toInt(), out)
        assertEquals(0xFF0074DE.toInt(), ChatColors.readable(0xFF1E90FF.toInt(), white))
    }

    @Test fun everyPaletteColourEndsReadableOrGrey() {
        for (c in ChatColors.PALETTE) for (bg in intArrayOf(chatBg, white, 0xFF0E0E10.toInt())) {
            val out = ChatColors.readable(c, bg)
            val grey = out == 0xFF7A7A7A.toInt() || out == 0xFF717171.toInt()
            assertTrue(hex(c) + " on " + hex(bg) + " gave " + hex(out), grey || ChatColors.contrast(out, bg) >= 4.5)
            assertEquals("same answer the second time", out, ChatColors.readable(c, bg))
        }
    }

    @Test fun nameColorFollowsTheApp() {
        assertEquals("no colour: the palette, as is", ChatColors.forName("viewer_one"),
            ChatColors.nameColor(null, "viewer_one", chatBg))
        assertEquals(ChatColors.forName("viewer_one"), ChatColors.nameColor("", "viewer_one", chatBg))
        assertEquals(0xFF7A7A7A.toInt(), ChatColors.nameColor("#0000FF", "viewer_one", chatBg))
        assertEquals(0xFF1E90FF.toInt(), ChatColors.nameColor("#1E90FF", "viewer_one", chatBg))
    }
}
