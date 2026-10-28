package tv.purple.xp

import android.content.Context
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The View half of the blacklist: collapse the chat row whose text carries a [Blacklist.Marker]
 * naming a term that is still in the list.
 *
 * Driven from the existing framework hook on TextView.setText (see [EmoteHooks]), which is both
 * obfuscation-immune and exactly the right moment: RecyclerView binds a row BEFORE measuring it,
 * so a height written here is honoured in the same layout pass and the hidden message never gets
 * a frame on screen.
 *
 * Three things about this are deliberate and were each arrived at the hard way:
 *
 *  - ONE level up, never a walk. `chat_message_item` is the message TextView inside
 *    `chat_message_container` in the two layouts we cover, but it is also the ROOT of
 *    system_message_item, deleted_mod_notice and auto_mod_caught_notice. On those a walk looking
 *    for a container would find none, climb past the RecyclerView and reach a fragment or activity
 *    root — the same mistake that once hid the whole Home tab and produced a 404 ghost page.
 *
 *  - INVISIBLE, never GONE. A RecyclerView LayoutManager has no visibility check, so a GONE row is
 *    still measured at full height and still consumes it — `layoutParams.height = 0` is the entire
 *    mechanism, and the visibility is only there to stop the row drawing. GONE would also break the
 *    restore, because ViewRootImpl discards layout requests from a view that is GONE.
 *
 *  - The One Chat overlay is NOT handled. Its `one_chat_message_container` id is carried by a
 *    fragment root in vertical theatre, so collapsing it would take the whole surface with it.
 *
 * Every write is gated on the state actually changing. Mutating a view during a layout pass
 * schedules another one, so an ungated write spins forever.
 */
internal object BlacklistRows {

    /** The message TextView, and the row root we collapse. Resolved once, compared as ints: this
     *  runs for every TextView.setText in the whole app, and getResourceEntryName throws for the
     *  unnamed ids that most of them have. */
    @Volatile private var itemId = 0
    @Volatile private var containerId = 0
    @Volatile private var resolved = false

    /** A row we collapsed. [terms] are the keys its message's marker names, so [reapply] frees it
     *  only once none of them is left in the list. [restoring] is true while a restore queued by
     *  [reapply] has yet to run, so a rebind can still cancel it. */
    private class Held(var terms: Set<String>) {
        var restoring = false
    }

    /** Rows we collapsed, so a restore touches exactly those and nothing else. */
    private val COLLAPSED = java.util.WeakHashMap<View, Held>()

    /** The keys named by every [Blacklist.Marker] on [cs], empty when it carries none. */
    private fun markedTerms(cs: CharSequence): Set<String> {
        if (cs !is Spanned) return emptySet()
        val marks = cs.getSpans(0, cs.length, Blacklist.Marker::class.java)
        return when (marks.size) {
            0 -> emptySet()
            1 -> marks[0].terms
            else -> marks.flatMapTo(HashSet()) { it.terms }
        }
    }

    /** True while any of [terms] is still in the list. When the list cannot be read the row stays
     *  hidden, as it always did before the marker named its terms. */
    private fun stillListed(terms: Set<String>): Boolean {
        val now = runCatching { Blacklist.keys() }.getOrNull() ?: return true
        return terms.any { it in now }
    }

    @Volatile private var missLogged = false

    /** Rows hidden since launch, for the progress log below. */
    @Volatile private var hidden = 0

    @Volatile private var markSeen = false

    private fun resolve(ctx: Context) {
        if (resolved) return
        resolved = true
        runCatching {
            val res = ctx.resources; val pkg = ctx.packageName
            itemId = res.getIdentifier("chat_message_item", "id", pkg)
            containerId = res.getIdentifier("chat_message_container", "id", pkg)
            log("BL ids item=$itemId container=$containerId")
        }.onFailure { log("BL id resolve failed: $it") }
    }

    /**
     * Apply the blacklist to the row [tv] belongs to.
     *
     * @return true when the row is hidden, so the caller can skip work that would only decorate an
     *         invisible line.
     */
    fun onSetText(tv: TextView, cs: CharSequence): Boolean {
        if (!resolved) resolve(tv.context)

        // One-shot: report where a marked line ACTUALLY lands. Self-limiting -- it only runs once
        // a match has been made and stops for good at the first sighting, so the cost never
        // reaches the general case of every TextView.setText in the app.
        if (!markSeen && Blacklist.marked > 0 && cs is Spanned &&
            cs.getSpans(0, cs.length, Blacklist.Marker::class.java).isNotEmpty()) {
            markSeen = true
            fun name(v: View?) = v?.let {
                runCatching { it.resources.getResourceEntryName(it.id) }.getOrDefault("(no id)")
            } ?: "(none)"
            log("BL marked text landed on '${name(tv)}' inside '${name(tv.parent as? View)}'")
        }

        if (itemId == 0 || tv.id != itemId) return false

        val row = tv.parent as? ViewGroup ?: return false
        if (row.id != containerId) {
            // Expected on the notice layouts where this TextView is itself the root. Logged once so
            // that a genuine anchor break is still visible rather than silently disabling the
            // feature.
            if (!missLogged) {
                missLogged = true
                val what = runCatching { row.resources.getResourceEntryName(row.id) }
                    .getOrDefault("(no id)")
                log("BL parent is '$what', not chat_message_container — row skipped")
            }
            return false
        }

        // Checked against the list as it is now, not just for a marker: Twitch keeps the marked
        // line for as long as it stays in chat, so a row freed by removing its term would be
        // hidden again the next time it is bound.
        val terms = markedTerms(cs)
        val want = terms.isNotEmpty() && stillListed(terms)
        val held = COLLAPSED[row]
        if (want == (held != null)) {
            if (held != null) {
                // A cached row re-bound to another hidden message: it is judged by this message's
                // terms from now on, and a restore still queued for the old one is cancelled, or
                // it would show this message once the row is attached.
                held.terms = terms
                held.restoring = false
            }
            return want
        }

        val lp = row.layoutParams ?: return want
        if (want) {
            lp.height = 0
            row.visibility = View.INVISIBLE
            COLLAPSED[row] = Held(terms)
            // Quiet but provable: the first hide confirms the whole chain end to end, and the
            // running total afterwards is the only way to tell "nothing matched" apart from
            // "matching silently stopped working".
            if (++hidden == 1 || hidden % 25 == 0) log("BL hidden $hidden message(s)")
        } else {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            row.visibility = View.VISIBLE
            COLLAPSED.remove(row)
        }
        // Required on the restore path: RecyclerView caches measurements, and WRAP_CONTENT produces
        // an UNSPECIFIED spec that its cache always considers up to date — without this the row
        // would keep its cached height of 0 forever. requestLayout sets the child's own
        // force-layout flag before propagating, which is the part that matters here.
        row.requestLayout()
        return want
    }

    /** Restore every row we hid whose terms have all left the list. Called when the term list
     *  changes: rows already on screen are never re-bound, so nothing else would bring them back.
     *  A row that a remaining term also matched stays hidden, and an add frees nothing. */
    fun reapply() {
        val rows = runCatching { weakKeys(COLLAPSED) }.getOrNull() ?: return
        if (rows.isEmpty()) return
        // Unreadable means keep everything hidden: a row shown by mistake stays shown until it
        // happens to be re-bound.
        val now = runCatching { Blacklist.keys() }.getOrNull() ?: return
        var freed = 0
        for (row in rows) {
            runCatching {
                val held = COLLAPSED[row] ?: return@runCatching
                if (held.terms.any { it in now }) return@runCatching
                held.restoring = true
                freed++
                row.post {
                    runCatching {
                        // Checked when it runs, not when posted: a rebind in between may have
                        // cancelled it, or already restored the row itself.
                        if (COLLAPSED[row]?.restoring != true) return@runCatching
                        row.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        row.visibility = View.VISIBLE
                        COLLAPSED.remove(row)
                        row.requestLayout()
                    }
                }
            }
        }
        if (freed > 0) log("BL restored $freed row(s) after a term change")
    }
}
