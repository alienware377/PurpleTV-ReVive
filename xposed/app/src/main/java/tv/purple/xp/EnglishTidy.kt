package tv.purple.xp

/**
 * Small rewordings of English chat before it is translated out of English, for words the Opus-MT
 * models get wrong in a Twitch chat. Measured on the real models (see TranslateE2eTest):
 *  - "stream" alone becomes a river or electric current ("Strom", "arroyo", "ruisseau") in every
 *    target language, while "live stream" comes out right.
 *  - "twitch" in lower case becomes twitching ("Zucken", "tic"); "Twitch" stays a name.
 *  - "streamer" becomes a paper streamer or a creek in Spanish, Portuguese and Russian, while
 *    "content creator" comes out right there. German, French and Italian keep "streamer" as is.
 * Only whole words are touched, and only on the way into another language.
 */
internal object EnglishTidy {

    private val STREAMS = Regex("(?<![\\p{L}\\p{N}_])(?<!live )(stream)(s?)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val TWITCH = Regex("(?<![\\p{L}\\p{N}_])twitch(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val STREAMER = Regex("(?<![\\p{L}\\p{N}_])streamer(s?)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    /** Targets whose model turns "streamer" into something else. */
    private val CREATOR = setOf("es", "pt", "ru")

    fun prep(text: String, to: String): String {
        var t = TWITCH.replace(text, "Twitch")
        t = STREAMS.replace(t) { m -> (if (m.value[0].isUpperCase()) "Live stream" else "live stream") + m.groupValues[2] }
        if (to in CREATOR) t = STREAMER.replace(t) { m ->
            (if (m.value[0].isUpperCase()) "Content creator" else "content creator") + m.groupValues[1]
        }
        return t
    }
}
