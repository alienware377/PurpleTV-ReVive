package tv.purple.xp

/**
 * One line of Twitch chat as it came off the IRC socket.
 *
 * [tags] holds the IRCv3 tags with their values already unescaped, so `tags["system-msg"]` reads
 * as plain text. A tag sent with no value is kept as "".
 */
data class IrcMsg(val tags: Map<String, String>, val prefix: String?, val command: String, val params: List<String>) {

    /** The channel login the line belongs to, without the '#', lowercase. Null for lines that
     *  belong to the connection rather than a chat (PING, the welcome, a sign-in NOTICE to "*"). */
    val channel: String?
        get() {
            for (p in params) if (p.length > 1 && p[0] == '#') return p.substring(1).lowercase()
            return null
        }

    /**
     * What the line says: the message of a PRIVMSG, a NOTICE or a CLEARMSG, the chatter's own
     * words on a USERNOTICE (null when they added none), and the login a CLEARCHAT is about (null
     * when the whole chat was cleared).
     */
    val text: String?
        get() = when (command) {
            "PRIVMSG", "USERNOTICE", "NOTICE", "CLEARCHAT", "CLEARMSG", "WHISPER" ->
                if (params.size >= 2) params[params.size - 1] else null
            else -> null
        }

    /** The sender's login, from "nick!user@host". Null for lines the server sends itself. */
    val nick: String?
        get() {
            val p = prefix ?: return null
            val bang = p.indexOf('!')
            if (bang > 0) return p.substring(0, bang)
            // A bare "tmi.twitch.tv" or "nick.tmi.twitch.tv" is a server name, not a chatter.
            return if (p.isEmpty() || p.indexOf('.') >= 0) null else p
        }

    /** The value of tag [k], or null when it is missing or empty (Twitch sends "color=" for a
     *  chatter who never picked a colour). */
    fun tag(k: String): String? = tags[k]?.takeIf { it.isNotEmpty() }
}

/** Where a Twitch emote sits in a message: UTF-16 positions into the text, [end] exclusive, so
 *  they can go straight into setSpan. */
data class EmoteRange(val id: String, val start: Int, val end: Int)

/** A GIF in a message: the part of the text it replaces, as UTF-16 positions, end exclusive. */
data class GifRange(val id: String, val url: String, val start: Int, val end: Int)

/**
 * Reads and writes Twitch chat lines (IRC with IRCv3 tags, as sent by irc-ws.chat.twitch.tv).
 *
 * Kept free of Android and of the socket so every rule here is tested on the JVM. Line shape:
 *   [@tag=value;tag=value ][:prefix ]COMMAND[ middle params][ :trailing]
 */
object IrcParse {

    private const val ACTION_OPEN = "\u0001ACTION"
    private const val CTCP = '\u0001'
    private val LINE_BREAKS = Regex("[\r\n]+")

    /** Split one websocket frame into lines. Twitch packs many lines into a busy chat's frames,
     *  each ended by CR LF. */
    fun lines(frame: String): List<String> {
        if (frame.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        var i = 0
        while (i < frame.length) {
            var nl = frame.indexOf('\n', i)
            if (nl < 0) nl = frame.length
            var end = nl
            if (end > i && frame[end - 1] == '\r') end--
            if (end > i) out.add(frame.substring(i, end))
            i = nl + 1
        }
        return out
    }

    /** One line into its parts, or null when it has no command at all. Never throws. */
    fun parse(line: String): IrcMsg? {
        var end = line.length
        while (end > 0 && (line[end - 1] == '\r' || line[end - 1] == '\n')) end--
        var i = 0
        var tags: Map<String, String> = emptyMap()
        if (i < end && line[i] == '@') {
            val sp = spaceOrEnd(line, i, end)
            if (sp >= end) return null
            tags = parseTags(line, i + 1, sp)
            i = sp
        }
        i = skipSpaces(line, i, end)
        var prefix: String? = null
        if (i < end && line[i] == ':') {
            val sp = spaceOrEnd(line, i, end)
            if (sp >= end) return null
            prefix = line.substring(i + 1, sp)
            i = skipSpaces(line, sp, end)
        }
        if (i >= end) return null
        val cmdEnd = spaceOrEnd(line, i, end)
        val command = line.substring(i, cmdEnd)
        i = cmdEnd
        val params = ArrayList<String>(2)
        while (true) {
            i = skipSpaces(line, i, end)
            if (i >= end) break
            if (line[i] == ':') { params.add(line.substring(i + 1, end)); break }
            val e = spaceOrEnd(line, i, end)
            params.add(line.substring(i, e))
            i = e
        }
        return IrcMsg(tags, prefix, command, params)
    }

    /**
     * Undo IRCv3 tag escaping: \s space, \: semicolon, \\ backslash, \r and \n. Any other escaped
     * character stands for itself and a lone backslash at the end is dropped, as the IRCv3 rules say.
     */
    fun unescapeTag(v: String): String {
        val first = v.indexOf('\\')
        if (first < 0) return v
        val sb = StringBuilder(v.length)
        sb.append(v, 0, first)
        var i = first
        while (i < v.length) {
            val c = v[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= v.length) break
            sb.append(when (val n = v[i + 1]) {
                's' -> ' '
                ':' -> ';'
                'r' -> '\r'
                'n' -> '\n'
                else -> n
            })
            i += 2
        }
        return sb.toString()
    }

    /**
     * The "emotes" tag ("25:0-4,12-16/1902:6-10") as ranges into [text], sorted by position.
     *
     * Twitch counts those positions in Unicode code points, while Android spans count UTF-16
     * units, so any emoji before an emote would shift every picture after it by one character.
     * Positions are converted here. Ranges outside the text, unreadable ones and any that overlap
     * an earlier one are dropped, so two pictures never claim the same characters.
     *
     * For a /me line, pass the text from [action]: Twitch counts from the start of the action's
     * own words, not from the hidden ACTION marker.
     */
    fun emoteRanges(tag: String?, text: String): List<EmoteRange> {
        if (tag.isNullOrEmpty() || text.isEmpty()) return emptyList()
        val points = text.codePointCount(0, text.length)
        // UTF-16 start of each code point, plus the text length at the end. Only needed when the
        // text has surrogate pairs: otherwise the two kinds of position are the same.
        val at: IntArray? = if (points == text.length) null else IntArray(points + 1).also { a ->
            var u = 0
            for (k in 0 until points) { a[k] = u; u += Character.charCount(text.codePointAt(u)) }
            a[points] = text.length
        }
        val found = ArrayList<EmoteRange>()
        for (group in tag.split('/')) {
            val colon = group.lastIndexOf(':')
            if (colon <= 0 || colon == group.length - 1) continue
            val id = group.substring(0, colon)
            for (r in group.substring(colon + 1).split(',')) {
                val dash = r.indexOf('-')
                if (dash <= 0) continue
                val a = r.substring(0, dash).toIntOrNull() ?: continue
                val b = r.substring(dash + 1).toIntOrNull() ?: continue
                if (a < 0 || b < a || b >= points) continue
                found.add(if (at == null) EmoteRange(id, a, b + 1) else EmoteRange(id, at[a], at[b + 1]))
            }
        }
        if (found.size < 2) return found
        found.sortBy { it.start }
        val out = ArrayList<EmoteRange>(found.size)
        var reached = 0
        for (e in found) if (e.start >= reached) { out.add(e); reached = e.end }
        return out
    }

    /**
     * The "gifs" tag: "start-end|gifId|url", comma separated. Positions count code points and the
     * end is inclusive, as in the emotes tag. Entries that do not fit the text are left out.
     */
    fun gifRanges(tag: String?, text: String): List<GifRange> {
        if (tag.isNullOrEmpty() || text.isEmpty()) return emptyList()
        val points = text.codePointCount(0, text.length)
        val out = ArrayList<GifRange>(1)
        for (entry in tag.split(',')) {
            val p = entry.split('|')
            if (p.size != 3 || p[1].isEmpty() || !p[2].startsWith("https://")) continue
            val dash = p[0].indexOf('-')
            if (dash <= 0) continue
            val a = p[0].substring(0, dash).toIntOrNull() ?: continue
            val b = p[0].substring(dash + 1).toIntOrNull() ?: continue
            if (a < 0 || b < a || b >= points) continue
            val s = text.offsetByCodePoints(0, a)
            out.add(GifRange(p[1], p[2], s, text.offsetByCodePoints(s, b - a + 1)))
        }
        return out.sortedBy { it.start }
    }

    /** The "badges" tag ("subscriber/12,premium/1") as (set, version) pairs, in the order Twitch
     *  sent them, which is the order they are shown in. */
    fun badges(tag: String?): List<Pair<String, String>> {
        if (tag.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Pair<String, String>>(3)
        for (b in tag.split(',')) {
            val slash = b.indexOf('/')
            if (slash <= 0 || slash == b.length - 1) continue
            out.add(b.substring(0, slash) to b.substring(slash + 1))
        }
        return out
    }

    /** The words of a /me line ("\u0001ACTION waves\u0001" gives "waves"), or null when [text]
     *  is an ordinary message. */
    fun action(text: String): String? {
        if (!text.startsWith(ACTION_OPEN)) return null
        var end = text.length
        if (end > ACTION_OPEN.length && text[end - 1] == CTCP) end--
        val from = ACTION_OPEN.length
        if (from == end) return ""
        if (text[from] != ' ') return null
        return text.substring(from + 1, end)
    }

    /** A channel or login as Twitch writes it in IRC: lowercase, no '#', only [a-z0-9_]. Null
     *  for anything else, so nothing typed can ever add a second command to a line. */
    fun cleanChannel(s: String?): String? {
        val c = s?.trim()?.removePrefix("#")?.lowercase() ?: return null
        if (c.isEmpty() || c.length > 25) return null
        for (ch in c) if (ch !in 'a'..'z' && ch !in '0'..'9' && ch != '_') return null
        return c
    }

    /**
     * The PRIVMSG line that sends [text] to [channel], or null when there is nothing to send.
     * "/me waves" becomes an ACTION, as in Twitch's own chat. Line breaks become spaces, because
     * a raw line break would end the IRC line and start a new command. [replyTo] is the id of the
     * message this answers.
     */
    fun privmsg(channel: String, text: String, replyTo: String? = null): String? {
        val ch = cleanChannel(channel) ?: return null
        val flat = text.replace(LINE_BREAKS, " ").trim()
        if (flat.isEmpty()) return null
        val body = if (flat == "/me" || flat.startsWith("/me ")) {
            val words = flat.substring(3).trim()
            if (words.isEmpty()) return null
            "$ACTION_OPEN $words$CTCP"
        } else flat
        val tag = replyTo?.takeIf { MESSAGE_ID.matches(it) && body === flat }?.let { "@reply-parent-msg-id=$it " }.orEmpty()
        return "${tag}PRIVMSG #$ch :$body"
    }

    private val MESSAGE_ID = Regex("[A-Za-z0-9-]{1,64}")

    private fun parseTags(s: String, from: Int, to: Int): Map<String, String> {
        val out = HashMap<String, String>(32)
        var i = from
        while (i < to) {
            var semi = s.indexOf(';', i)
            if (semi < 0 || semi > to) semi = to
            if (semi > i) {
                val eq = s.indexOf('=', i)
                if (eq in i until semi) {
                    if (eq > i) out[s.substring(i, eq)] = unescapeTag(s.substring(eq + 1, semi))
                } else out[s.substring(i, semi)] = ""
            }
            i = semi + 1
        }
        return out
    }

    private fun spaceOrEnd(s: String, from: Int, end: Int): Int {
        val sp = s.indexOf(' ', from)
        return if (sp < 0 || sp > end) end else sp
    }

    private fun skipSpaces(s: String, from: Int, end: Int): Int {
        var i = from
        while (i < end && s[i] == ' ') i++
        return i
    }
}
