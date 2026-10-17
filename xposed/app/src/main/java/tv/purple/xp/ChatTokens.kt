package tv.purple.xp

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Shared read access to a live chat message's TOKEN list.
 *
 * Extracted from [ChatHighlight] once a second feature ([Blacklist]) needed the same reads. Both
 * ask the same two questions of a message -- "does it mention me" and "what plain text does it
 * contain" -- and both must answer them without naming an obfuscated class.
 *
 * The technique throughout is toString() matching. R8 renames classes and fields but leaves the
 * Kotlin-generated toString() output verbatim, so a data class still announces itself as
 * "LiveChatMessage(...)" or "TextToken(text=...)" no matter what its class ended up being called.
 *
 * IMPORTANT: the R8-kept `MessageToken$MentionToken` in the pub messages package is NOT what live
 * chat uses -- it is produced by a tokenizer that never fires on this path, so `instanceof` against
 * it matches nothing. The live tokens are an obfuscated hierarchy, identified here by label only.
 */
internal object ChatTokens {

    private const val MSG_PREFIX = "LiveChatMessage("
    private const val MENTION = "MentionToken("
    private const val TEXT = "TextToken(text="
    private val PREFIXES = listOf(MENTION, TEXT, "EmoteToken(", "UrlToken(",
        "BitsToken(", "CensoredTextToken(")

    @Volatile private var argIndex = -1
    @Volatile private var msgClass: Class<*>? = null
    @Volatile private var fTokens: Field? = null

    /** The chat message among a hooked call's arguments, or null if this call carries none. */
    fun message(args: Array<Any?>?): Any? {
        args ?: return null
        val cached = msgClass
        if (cached != null) {
            val a = args.getOrNull(argIndex)
            if (a != null && cached.isInstance(a)) { resolve(a); return a }
        }
        for (i in args.indices) {
            val a = args[i] ?: continue
            val cn = a.javaClass.name
            if (cn.startsWith("java.") || cn.startsWith("android.")) continue
            val s = runCatching { a.toString() }.getOrNull() ?: continue
            if (!s.startsWith(MSG_PREFIX)) continue
            argIndex = i; msgClass = a.javaClass
            resolve(a)
            return a
        }
        return null
    }

    /** Find the token list: the List field whose elements identify as known token types.
     *  Retried until a message with a non-empty list comes through. */
    private fun resolve(msg: Any) {
        if (fTokens != null) return
        for (f in msg.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            if (!List::class.java.isAssignableFrom(f.type)) continue
            val v = runCatching { f.isAccessible = true; f.get(msg) as? List<*> }.getOrNull()
            val first = v?.firstOrNull() ?: continue
            val s = runCatching { first.toString() }.getOrNull() ?: continue
            if (PREFIXES.none { s.startsWith(it) }) continue
            fTokens = f
            log("CT token field='${f.name}'")
            return
        }
    }

    private fun list(msg: Any): List<*> =
        runCatching { fTokens?.get(msg) as? List<*> }.getOrNull() ?: emptyList<Any>()

    /** True when a mention token is flagged as referring to the logged-in user. */
    fun mentionsLocalUser(msg: Any): Boolean {
        for (t in list(msg)) {
            val s = runCatching { t?.toString() }.getOrNull() ?: continue
            if (s.startsWith(MENTION) && s.contains("isLocalUser=true")) return true
        }
        return false
    }

    /** Plain-text runs only, so keywords can't match an emote name or a url. */
    fun textOf(msg: Any): List<String> {
        val out = ArrayList<String>(2)
        for (t in list(msg)) {
            val s = runCatching { t?.toString() }.getOrNull() ?: continue
            if (!s.startsWith(TEXT)) continue
            out.add(s.substring(TEXT.length).removeSuffix(")"))
        }
        return out
    }

    private val MSG_ID = Regex("messageId=([^,)]*)")

    /** The message's own id, read from its toString label. Used to tell a genuinely new message
     *  apart from the same one being re-assembled on a rebind. */
    fun messageId(msg: Any): String? {
        val s = runCatching { msg.toString() }.getOrNull() ?: return null
        return MSG_ID.find(s)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private const val EMOTE = "EmoteToken("
    private const val URL = "UrlToken("
    private val LABELLED_TEXT = Regex("text=([^,)]*)")
    private val LABELLED_URL = Regex("url=([^,)\\s]*)")

    /**
     * Everything in a message a filter could reasonably match: typed text, emote names and urls.
     *
     * Wider than [textOf] on purpose. The highlighter only tints, so matching an emote name there
     * would be a surprise; the blacklist HIDES, and a user who blacklists an emote or a domain
     * means it.
     */
    fun matchableOf(msg: Any): List<String> {
        val out = ArrayList<String>(4)
        for (t in list(msg)) {
            val s = runCatching { t?.toString() }.getOrNull() ?: continue
            when {
                s.startsWith(TEXT) -> out.add(s.substring(TEXT.length).removeSuffix(")"))
                // Emote and url tokens carry more than one field, so the value is read by label
                // rather than by position. Neither an emote name nor a url can contain a comma or
                // a closing paren, so the non-greedy stop is safe for both.
                s.startsWith(EMOTE) -> LABELLED_TEXT.find(s)?.groupValues?.get(1)?.let { out.add(it) }
                s.startsWith(URL) -> LABELLED_URL.find(s)?.groupValues?.get(1)?.let { out.add(it) }
            }
        }
        return out
    }
}
