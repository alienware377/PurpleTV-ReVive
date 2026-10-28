package tv.purple.xp

import android.text.SpannableStringBuilder
import android.text.Spanned
import de.robv.android.xposed.XposedHelpers

/**
 * Chat blacklist: hide messages containing a word, or sent by a given user.
 *
 * Split in two because the halves happen at very different moments. Matching runs here, on the
 * chat-line model as it is assembled; the actual row hiding runs in [BlacklistRows], against the
 * View that eventually displays it. The two are joined by a [Marker] span attached to the line's
 * body and username slots, which survives Twitch's own concatenation of the line (it appends
 * through a SpannableStringBuilder, which copies spans) and so arrives intact at TextView.setText.
 *
 * That marker is the whole design: it means the decision is made once, where the message model is
 * readable, and the View layer never needs to know what a chat message is.
 */
object Blacklist {

    const val KEY_WORDS = "blacklist_terms"

    /** Attached to a hidden line's body and username so [BlacklistRows] can recognise it at the
     *  View layer. */
    object Marker

    /** Match modes, mirroring the highlighter's so the two editors feel the same. */
    enum class Type { USERNAME, SENSITIVE, INSENSITIVE }

    class Term(val word: String, val type: Type)

    // ---------------------------------------------------------------- store
    //
    // Serialised one entry per line as "type|word", word last so it may contain the separator.

    /** Parsed form of the stored list. Rebuilt only when the settings change: [matches] runs once
     *  per message per bind, inside a layout pass, so re-reading SharedPreferences there would be a
     *  real cost. Keyed to [Settings.rev] rather than cleared by [save] alone, because a settings
     *  sync from another device writes the list without ever going through [save]. */
    private class Rules(
        val rev: Int,
        val users: List<String>,          // lower-cased
        val sensitive: List<String>,      // single words only
        val insensitive: List<String>,    // single words only, lower-cased
        val phrases: List<Regex>          // terms holding a separator, see [phraseRegex]
    ) {
        val empty = users.isEmpty() && sensitive.isEmpty() && insensitive.isEmpty() &&
            phrases.isEmpty()
    }

    @Volatile private var cached: Rules? = null

    fun terms(): List<Term> {
        val raw = Settings.getString(KEY_WORDS)
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Term>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val a = line.indexOf('|')
            if (a < 0) continue
            val type = when (line.substring(0, a)) {
                "U" -> Type.USERNAME
                "S" -> Type.SENSITIVE
                else -> Type.INSENSITIVE
            }
            val word = line.substring(a + 1)
            if (word.isNotBlank()) out.add(Term(word, type))
        }
        return out
    }

    fun save(list: List<Term>) {
        Settings.setString(KEY_WORDS, list.joinToString("\n") {
            val t = when (it.type) {
                Type.USERNAME -> "U"; Type.SENSITIVE -> "S"; Type.INSENSITIVE -> "I"
            }
            "$t|${it.word}"
        })
        cached = null
        // Rows already on screen are never re-bound, so a term the user just deleted would leave
        // its messages hidden until they happened to scroll off and back.
        BlacklistRows.reapply()
    }

    fun add(t: Term) = save(terms() + t)
    fun remove(index: Int) = save(terms().filterIndexed { i, _ -> i != index })

    private fun rules(): Rules {
        // Read BEFORE the terms, so a write that lands mid-parse leaves an older rev behind and
        // the next call parses again instead of keeping the old list.
        val r = Settings.rev
        cached?.let { if (it.rev == r) return it }
        val all = terms()
        val (words, multi) = all.filter { it.type != Type.USERNAME }
            .partition { !SPLIT.containsMatchIn(it.word) }
        return Rules(
            rev = r,
            users = all.filter { it.type == Type.USERNAME }.map { it.word.lowercase() },
            sensitive = words.filter { it.type == Type.SENSITIVE }.map { it.word },
            insensitive = words.filter { it.type == Type.INSENSITIVE }.map { it.word.lowercase() },
            phrases = listOfNotNull(
                phraseRegex(multi.filter { it.type == Type.SENSITIVE }, anyCase = false),
                phraseRegex(multi.filter { it.type == Type.INSENSITIVE }, anyCase = true)
            )
        ).also {
            // Don't latch the empty list that comes back before the module has a context, or
            // nothing would be hidden until the next settings write.
            if (Settings.isReady()) cached = it
        }
    }

    /** What a phrase must not run into on either side: letters, digits and underscore. */
    private const val WORD_CHAR = "[\\p{L}\\p{N}_]"

    /**
     * One pattern for every term that holds a separator ("spam.com", "e-girl", "free followers").
     * Such a term can never equal a single piece of the split in [matches], so it is looked for in
     * the whole run instead, bounded so "spam.com" still hides "https://spam.com/x" but not
     * "notspam.com". Lookarounds rather than \b, which misbehaves when a term starts or ends with
     * punctuation. Built once per rule change, and null when there is nothing to match or the
     * pattern will not compile, so a bad term can never throw inside the chat bind.
     */
    private fun phraseRegex(list: List<Term>, anyCase: Boolean): Regex? {
        // A term made only of separators has nothing to match on, as before.
        val words = list.map { it.word }.filter { w -> w.split(SPLIT).any { it.isNotEmpty() } }
        if (words.isEmpty()) return null
        return runCatching {
            val alts = words.joinToString("|") { Regex.escape(it) }
            Regex(
                "(?<!$WORD_CHAR)(?:$alts)(?!$WORD_CHAR)",
                if (anyCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            )
        }.onFailure { log("BL phrase pattern failed: $it") }.getOrNull()
    }

    // ---------------------------------------------------------------- match

    /** Word separators. Wider than whitespace so "word." and "word!" still match, matching the
     *  behaviour of the original this feature is modelled on. */
    private val SPLIT = Regex("[\\s,.!?-]+")

    /** True when the message among [args] should be hidden. */
    fun matches(args: Array<Any?>?): Boolean {
        val r = rules()
        if (r.empty) return false
        val msg = ChatTokens.message(args) ?: return false

        if (r.users.isNotEmpty()) {
            val login = ChatIdentity.Sender.of(args)?.login?.lowercase()
            if (login != null && login in r.users) return true
        }
        if (r.sensitive.isEmpty() && r.insensitive.isEmpty() && r.phrases.isEmpty()) return false

        for (text in ChatTokens.matchableOf(msg)) {
            if (r.phrases.any { it.containsMatchIn(text) }) return true
            for (w in text.split(SPLIT)) {
                if (w.isEmpty()) continue
                if (r.sensitive.isNotEmpty() && w in r.sensitive) return true
                if (r.insensitive.isNotEmpty() && w.lowercase() in r.insensitive) return true
            }
        }
        return false
    }

    /** Lines matched so far. Read by [BlacklistRows] to bound its own diagnostic. */
    @Volatile var marked = 0
        private set

    /**
     * Tag an assembled chat line as hidden.
     *
     * Must run AFTER [ChatLineStyle.apply], which replaces the body wholesale when recovering a
     * deleted message and would drop the marker with it, and BEFORE [ChatLineStyle.plantOriginal],
     * which copies the username slot and captures the body, marker included.
     *
     * The marker goes on BOTH the body and the username slot. When a moderator deletes a line that
     * is already on screen, Twitch redraws it from a slice that ends at the username and appends
     * its "<message deleted>" placeholder, so a marker on the body alone is cut away and the row
     * comes back. Marker is one shared object and Twitch's concatenation copies it only once per
     * line, so the assembled line carries it over the username only, which is enough: the View
     * side checks the whole text. Either slot can be empty (a gigantified-emote-only message has
     * no body), and a span over an empty range is not copied, hence tagging both.
     */
    fun mark(model: Any?) {
        model ?: return
        // Both, never short-circuited: see above.
        val okBody = tag(model, Names.BODY_FIELD)
        val okName = tag(model, Names.NAME_FIELD)
        if ((okBody || okName) && ++marked == 1) log("BL matched a message, marker attached")
    }

    private fun tag(model: Any, field: String): Boolean {
        val cs = runCatching {
            XposedHelpers.getObjectField(model, field) as? CharSequence
        }.getOrNull() ?: return false
        if (cs.isEmpty()) return false
        if (cs is Spanned && cs.getSpans(0, cs.length, Marker::class.java).isNotEmpty()) return true
        val out = SpannableStringBuilder(cs)
        out.setSpan(Marker, 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        XposedHelpers.setObjectField(model, field, out)
        return true
    }
}
