package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTidyTest {

    @Test fun stuckRepeatsAreCut() {
        assertEquals("kaya kaya kaya", SpeechTidy.whisper("kaya kaya kaya kaya kaya kaya kaya kaya kaya kaya", 10f))
        assertEquals("we're good, we're good, we're good!", SpeechTidy.whisper("we're good, we're good, we're good!", 3f))
        assertEquals("go team go team go team now", SpeechTidy.whisper("go team go team go team go team go team now", 5f))
        assertEquals("wauw, wauw, wauw, w", SpeechTidy.whisper("wauw, wauw, wauw, wauw, wauw, w", 2f))
        assertEquals("草草草", SpeechTidy.whisper("草草草草草草草草", 2f))
    }

    @Test fun stockLinesOverShortSoundAreDropped() {
        assertEquals("", SpeechTidy.whisper("Thanks for watching!", 1.2f))
        assertEquals("", SpeechTidy.whisper("ご視聴ありがとうございました", 2f))
        assertEquals("", SpeechTidy.whisper("♪ ♪", 3f))
        // Said in a long stretch of talk, it may be real.
        assertEquals("Thank you.", SpeechTidy.whisper("Thank you.", 6f))
    }

    @Test fun normalLinesStay() {
        val s = "Keep up your end and I'll owe you a serious debt, kid."
        assertEquals(s, SpeechTidy.whisper(s, 3f))
        assertEquals("手前でおもろい", SpeechTidy.whisper("手前でおもろい", 1.5f))
    }
}
