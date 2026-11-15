package tv.purple.xp

/**
 * Cleans up what Whisper writes. Over game sound, music or a long held note it sometimes gets
 * stuck on one word ("kaya kaya kaya ..." for ten seconds), and over near silence it writes the
 * stock lines of the videos it learned from ("Thanks for watching!"). Both are noise, not speech.
 * Plain JVM code, tested by SpeechTidyTest.
 */
object SpeechTidy {

    /** Times a word or short phrase may repeat in a row before the rest of the run goes. */
    private const val MAX_REPEAT = 3

    /** Lines Whisper writes over silence or music, matched without case or end punctuation. */
    private val STOCK = setOf(
        "thank you", "thanks for watching", "thank you for watching", "thank you so much for watching",
        "please subscribe", "subscribe", "like and subscribe", "bye", "you",
        "ご視聴ありがとうございました", "ありがとうございました", "字幕", "продолжение следует",
        "subtítulos realizados por la comunidad de amara.org", "untertitel der amara.org-community",
        "sous-titres réalisés par la communauté d'amara.org", "請不吝點贊 訂閱 轉發 打賞支持明鏡與點點欄目"
    )

    /** [text] with stuck repeats cut short, or "" when it is a stock line or no letters at all. */
    fun whisper(text: String, seconds: Float): String {
        val t = text.trim()
        if (t.none { it.isLetter() }) return ""
        val key = t.lowercase().trimEnd('.', '!', '?', '。', '！', '？', ' ')
        // A stock line from a short piece of sound is noise; said inside real talk it may be real.
        if (key in STOCK && seconds < 4f) return ""
        return collapse(t)
    }

    /** Cuts runs of the same 1 to 3 word unit to [MAX_REPEAT], and the same for repeated characters in unspaced text. */
    internal fun collapse(t: String): String {
        val words = t.split(' ').filter { it.isNotEmpty() }
        if (words.size >= 2) {
            val out = ArrayList<String>()
            var i = 0
            outer@ while (i < words.size) {
                for (n in 1..3) {
                    if (i + n * (MAX_REPEAT + 1) > words.size) continue
                    val unit = words.subList(i, i + n).map { norm(it) }
                    var k = 1
                    while (i + (k + 1) * n <= words.size && words.subList(i + k * n, i + (k + 1) * n).map { norm(it) } == unit) k++
                    if (k > MAX_REPEAT) {
                        repeat(MAX_REPEAT) { out.addAll(words.subList(i, i + n)) }
                        i += k * n
                        continue@outer
                    }
                }
                out.add(words[i])
                i++
            }
            return out.joinToString(" ")
        }
        // One long token: Japanese or Chinese text, or "wwwwwwww". Cut a character run of the same unit.
        val m = Regex("(.{1,4}?)\\1{${MAX_REPEAT},}").replace(t) { r -> r.groupValues[1].repeat(MAX_REPEAT) }
        return m
    }

    private fun norm(w: String) = w.lowercase().trim(',', '.', '!', '?', '、', '。')
}
