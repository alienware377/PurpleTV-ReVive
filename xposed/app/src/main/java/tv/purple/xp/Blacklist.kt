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
     *  View layer. Names every term that matched by its [Term.key], so removing one term frees
     *  only the lines that no remaining term hides. */
    class Marker(val terms: Set<String>)

    /** Match modes, mirroring the highlighter's so the two editors feel the same. [code] is the
     *  letter each is stored under. */
    enum class Type(val code: String) { USERNAME("U"), SENSITIVE("S"), INSENSITIVE("I") }

    class Term(val word: String, val type: Type) {
        /** The term as stored, "type|word". What a [Marker] names it by: a line on screen
         *  outlives any parsed copy of the list, and this stays the same across re-reads. */
        val key: String get() = "${type.code}|$word"
    }

    // ---------------------------------------------------------------- store
    //
    // Serialised one entry per line as "type|word", word last so it may contain the separator.

    /** Parsed form of the stored list. Rebuilt only when the settings change: [matches] runs once
     *  per message per bind, inside a layout pass, so re-reading SharedPreferences there would be a
     *  real cost. Keyed to [Settings.rev] rather than cleared by [save] alone, because a settings
     *  sync from another device writes the list without ever going through [save]. */
    private class Rules(
        val rev: Int,
        // Each maps the form a message is compared in to the keys of the terms that share it, so
        // "Foo" and "foo" as any-case words are one entry naming both.
        val users: Map<String, List<String>>,        // lower-cased
        val sensitive: Map<String, List<String>>,    // single words only
        val insensitive: Map<String, List<String>>,  // single words only, lower-cased
        val phrases: List<Regex>,         // terms holding a separator, see [phraseRegex]
        private val phraseTerms: List<Term>,
        val keys: Set<String>             // every term in the list, see [keys]
    ) {
        val empty = users.isEmpty() && sensitive.isEmpty() && insensitive.isEmpty() &&
            phrases.isEmpty()

        /** One pattern per phrase term, to tell which of them matched. Built only once the
         *  combined [phrases] has matched something, which most lines never do. */
        val phraseEach: List<Pair<Regex, String>> by lazy {
            phraseTerms.mapNotNull { t ->
                Blacklist.phraseRegex(listOf(t), anyCase = t.type == Type.INSENSITIVE)
                    ?.let { it to t.key }
            }
        }
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
        Settings.setString(KEY_WORDS, list.joinToString("\n") { it.key })
        cached = null
        // Rows already on screen are never re-bound, so a term the user just deleted would leave
        // its messages hidden until they happened to scroll off and back.
        runCatching { BlacklistRows.reapply() }.onFailure { log("BL reapply error: $it") }
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
            users = all.filter { it.type == Type.USERNAME }
                .groupBy({ it.word.lowercase() }, { it.key }),
            sensitive = words.filter { it.type == Type.SENSITIVE }
                .groupBy({ it.word }, { it.key }),
            insensitive = words.filter { it.type == Type.INSENSITIVE }
                .groupBy({ it.word.lowercase() }, { it.key }),
            phrases = listOfNotNull(
                phraseRegex(multi.filter { it.type == Type.SENSITIVE }, anyCase = false),
                phraseRegex(multi.filter { it.type == Type.INSENSITIVE }, anyCase = true)
            ),
            phraseTerms = multi,
            keys = all.mapTo(HashSet()) { it.key }
        ).also {
            // Don't latch the empty list that comes back before the module has a context, or
            // nothing would be hidden until the next settings write.
            if (Settings.isReady()) cached = it
        }
    }

    /** The [Term.key] of every term now in the list, for [BlacklistRows] to check a [Marker]
     *  against. Cached with the rest of the rules, so it is cheap enough for a bind. */
    fun keys(): Set<String> = rules().keys

    /** What a phrase must not run into on either side: letters, digits and underscore. */
    private const val WORD_CHAR = "[\\p{L}\\p{N}_]"

    /**
     * One pattern for every term that holds a separator ("spam.com", "e-girl", "free followers").
     * Such a term can never equal a single piece of the split in [hits], so it is looked for in
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

    /** The keys of the terms that hit the line [matches] last said yes to on this thread, handed
     *  on to [mark]. The caller runs the two back to back in one hook and only takes a yes or no
     *  from the first, so this is the one way across. */
    private val lastHits = ThreadLocal<Set<String>>()

    /** True when the message among [args] should be hidden. */
    fun matches(args: Array<Any?>?): Boolean {
        // Cleared first, so a line that matches nothing can never pass on an older line's terms.
        lastHits.remove()
        val hits = hits(args)
        if (hits.isEmpty()) return false
        lastHits.set(hits)
        return true
    }

    /**
     * The [Term.key] of every term that matches the message among [args], empty when none does.
     * Collects all of them rather than stopping at the first, because removing one term later must
     * keep a line hidden that another term also matches.
     */
    private fun hits(args: Array<Any?>?): Set<String> {
        val r = rules()
        if (r.empty) return emptySet()
        val msg = ChatTokens.message(args) ?: return emptySet()
        val out = HashSet<String>()

        if (r.users.isNotEmpty()) {
            val login = ChatIdentity.Sender.of(args)?.login?.lowercase()
            if (login != null) r.users[login]?.let { out.addAll(it) }
        }
        if (r.sensitive.isEmpty() && r.insensitive.isEmpty() && r.phrases.isEmpty()) return out

        // Guarded so a failure reading the text cannot throw away a username hit found above,
        // which used to return before the text was ever read.
        runCatching {
            for (text in ChatTokens.matchableOf(msg)) {
                if (r.phrases.any { it.containsMatchIn(text) }) {
                    for ((re, key) in r.phraseEach) if (re.containsMatchIn(text)) out.add(key)
                }
                for (w in text.split(SPLIT)) {
                    if (w.isEmpty()) continue
                    if (r.sensitive.isNotEmpty()) r.sensitive[w]?.let { out.addAll(it) }
                    if (r.insensitive.isNotEmpty())
                        r.insensitive[w.lowercase()]?.let { out.addAll(it) }
                }
            }
        }.onFailure { log("BL text match error: $it") }
        return out
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
     * comes back. Both slots share one Marker per line and Twitch's concatenation copies it only
     * once, so the assembled line carries it over the username only, which is enough: the View
     * side checks the whole text. Either slot can be empty (a gigantified-emote-only message has
     * no body), and a span over an empty range is not copied, hence tagging both.
     */
    fun mark(model: Any?) {
        // Taken before anything else so it can never be left behind for a later line.
        val hits = lastHits.get()
        lastHits.remove()
        model ?: return
        // Only ever called after a yes from [matches], so this is never expected to be missing.
        // If it is, naming every current term keeps the line hidden, as it was before the marker
        // named terms at all, until the list no longer holds any of them.
        val marker = Marker(hits ?: rules().keys)
        // Both, never short-circuited: see above.
        val okBody = tag(model, Names.BODY_FIELD, marker)
        val okName = tag(model, Names.NAME_FIELD, marker)
        if ((okBody || okName) && ++marked == 1) log("BL matched a message, marker attached")
    }

    private fun tag(model: Any, field: String, marker: Marker): Boolean {
        val cs = runCatching {
            XposedHelpers.getObjectField(model, field) as? CharSequence
        }.getOrNull() ?: return false
        if (cs.isEmpty()) return false
        val out = SpannableStringBuilder(cs)
        // A slot can arrive already marked by an earlier pass over the same text. Replaced rather
        // than kept: the terms it names may have left the list since, and this pass has just
        // matched against the list as it is now.
        for (old in out.getSpans(0, out.length, Marker::class.java)) out.removeSpan(old)
        out.setSpan(marker, 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        XposedHelpers.setObjectField(model, field, out)
        return true
    }
}
