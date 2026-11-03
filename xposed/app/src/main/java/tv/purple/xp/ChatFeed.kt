package tv.purple.xp

/**
 * Every new chat line once, as plain data, for the features that only read chat: emote stats, the
 * ad break recap and translation. Fed from the one chat assembler hook in EmoteHooks.
 *
 * Twitch assembles the same line again on rebinds and scrollback, so lines are told apart by the
 * message id and each one is handed on only the first time it is seen.
 */
object ChatFeed {

    class Line(
        val id: String,
        val login: String,
        /** The typed text runs, emote names and urls left out. */
        val text: String,
        /** Every emote in the line by name, Twitch's own and 7TV/BTTV/FFZ, in order. */
        val emotes: List<String>,
        val channel: String?,
        val at: Long,
    )

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Line) -> Unit>()
    fun addListener(l: (Line) -> Unit) { listeners.add(l) }

    private val seen = object : LinkedHashMap<String, Boolean>(512, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 3000
    }

    /** Called from the assembler hook (main thread) for every line that is shown. */
    fun onLine(args: Array<Any?>?) {
        if (listeners.isEmpty()) return
        val msg = ChatTokens.message(args) ?: return
        val id = ChatTokens.messageId(msg) ?: return
        synchronized(seen) { if (seen.put(id, true) != null) return }
        val who = ChatIdentity.Sender.of(args) ?: return
        val text = ChatTokens.textOf(msg).joinToString("").trim()
        val emotes = ArrayList<String>(4)
        ChatTokens.emotesOf(msg).forEach { emotes.add(it) }
        // 7TV, BTTV and FFZ emotes are plain words to Twitch: pick out the ones we know.
        if (text.isNotEmpty()) for (w in text.split(' ')) {
            if (w.isNotEmpty() && EmoteRepo.map.containsKey(w) && EmoteRepo.enabled(w)) emotes.add(w)
        }
        val line = Line(id, who.login, text, emotes, EmoteRepo.currentChannel(), System.currentTimeMillis())
        for (l in listeners) runCatching { l(line) }.onFailure { log("CF listener: $it") }
    }
}
