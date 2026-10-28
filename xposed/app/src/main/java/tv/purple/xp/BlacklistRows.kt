package tv.purple.xp

import android.content.Context
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The View half of the blacklist: collapse the chat row whose text carries [Blacklist.Marker].
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

    /** Rows we collapsed, so a restore touches exactly those and nothing else. The value is false
     *  while a restore queued by [reapply] has yet to run, so a rebind can still cancel it. */
    private val COLLAPSED = java.util.WeakHashMap<View, Boolean>()

    /** Every term in force when a row was collapsed or the list was last edited, so [reapply] can
     *  tell a removal, which may free rows, from an add, which can only hide more. The marker does
     *  not say which term matched, so this is as precise as the View layer can be. */
    @Volatile private var known: Set<String> = emptySet()

    /** The stored list [known] last took in, so collapsing a row only re-reads it after a change. */
    @Volatile private var notedRaw: String? = null

    private fun termKeys(): Set<String> =
        Blacklist.terms().mapTo(HashSet()) { "${it.type}|${it.word}" }

    private fun noteTerms() {
        val raw = Settings.getString(Blacklist.KEY_WORDS)
        if (raw == notedRaw) return
        notedRaw = raw
        known = known + termKeys()
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

        val want = cs is Spanned &&
            cs.getSpans(0, cs.length, Blacklist.Marker::class.java).isNotEmpty()
        val state = COLLAPSED[row]
        val had = state != null
        if (want == had) {
            // A cached row re-bound to another hidden message before its queued restore ran:
            // cancel that restore, or it would show this message once the row is attached.
            if (state == false) { COLLAPSED[row] = true; runCatching { noteTerms() } }
            return want
        }

        val lp = row.layoutParams ?: return want
        if (want) {
            runCatching { noteTerms() }
            lp.height = 0
            row.visibility = View.INVISIBLE
            COLLAPSED[row] = true
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

    /** Restore every row we hid. Called when the term list changes: rows already on screen are
     *  never re-bound, so nothing else would bring them back. */
    fun reapply() {
        val rows = runCatching { weakKeys(COLLAPSED) }.getOrNull() ?: return
        val now = runCatching { termKeys() }.getOrDefault(emptySet())
        val before = known
        known = now
        notedRaw = null
        if (rows.isEmpty()) return
        // Only a removal can free a row. An add just hides more, and restoring here would show
        // every message the other terms had hidden until each row happened to be re-bound.
        if (now.containsAll(before)) return
        for (row in rows) {
            COLLAPSED[row] = false
            row.post {
                runCatching {
                    // Checked when it runs, not when posted: a rebind in between may have
                    // cancelled it, or already restored the row itself.
                    if (COLLAPSED[row] != false) return@runCatching
                    row.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    row.visibility = View.VISIBLE
                    COLLAPSED.remove(row)
                    row.requestLayout()
                }
            }
        }
        log("BL restored ${rows.size} row(s) after a term change")
    }
}
