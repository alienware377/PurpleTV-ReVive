package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTidyTest {
    @Test fun streamBecomesLiveStream() {
        assertEquals("Thanks for the live stream!", EnglishTidy.prep("Thanks for the stream!", "de"))
        assertEquals("Live streams are fun", EnglishTidy.prep("Streams are fun", "de"))
        assertEquals("great live stream today", EnglishTidy.prep("great live stream today", "de"))
        // Other words with "stream" in them stay.
        assertEquals("streaming is fun, upstream too", EnglishTidy.prep("streaming is fun, upstream too", "de"))
    }

    @Test fun twitchIsAName() {
        assertEquals("banned from Twitch", EnglishTidy.prep("banned from twitch", "fr"))
        assertEquals("twitchy", EnglishTidy.prep("twitchy", "fr"))
    }

    @Test fun streamerOnlyWhereTheModelBreaksIt() {
        assertEquals("this content creator is the best", EnglishTidy.prep("this streamer is the best", "es"))
        assertEquals("Content creators", EnglishTidy.prep("Streamers", "pt"))
        assertEquals("this streamer is the best", EnglishTidy.prep("this streamer is the best", "de"))
    }
}
