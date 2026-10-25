package tv.purple.xp

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Generic "hide a piece of Twitch's UI" engine.
 *
 * Every hide-style toggle in the settings menu reduces to the same operation: find views by
 * resource ENTRY name and set them GONE. Rather than write that hook once per feature, all the
 * rules live in [RULES] and a single layout pass applies them.
 *
 * Entry names are obfuscation-immune (R8 renames classes, not resource entries), so this keeps
 * working across Twitch updates. Views are restored to VISIBLE when their toggle is turned off,
 * so nothing needs an app restart.
 *
 * Note we only ever touch visibility — never remove views from their parent — because Twitch's
 * own presenters keep references to them and would crash on a detached view.
 */
object ViewHider {

    /**
     * @param key       SharedPreferences key backing the toggle
     * @param entries   resource entry names to hide when the toggle is on
     * @param landscape when true the rule only applies in landscape orientation
     */
    private class Rule(
        val key: String,
        val entries: List<String>,
        val landscape: Boolean = false,
        /** The setting SHOWS the views (on by default) and hiding happens when it is off. */
        val inverted: Boolean = false
    ) {
        fun active() = if (inverted) !Settings.get(key, true) else Settings.get(key, false)
    }

    private val RULES = listOf(
        Rule("hide_chat_header", listOf("chat_header_container", "chat_header")),
        Rule("hide_leaderboards", listOf("leaderboards_container", "leaderboards_condensed_view")),
        Rule("disable_hype_train", listOf("hype_train", "shared_hype_train_visibility_group")),
        // bit_picker is the Bits button beside the chat input on Twitch 31.x; bits_button is the
        // older name, kept for older builds.
        Rule("hide_bits_button", listOf("bits_button", "bit_picker")),
        Rule("hide_message_input", listOf("chat_message_input_view_container",
            "message_input_view_container")),
        // Same targets as above, but only while the device is in landscape.
        Rule("auto_hide_message_input", listOf("chat_message_input_view_container",
            "message_input_view_container"), landscape = true),

        // --- Player ---
        Rule("hide_unfollow_button", listOf("unfollow_button")),
        // "Follow/Subscribe" is one setting covering both buttons in the player metadata bar.
        Rule("hide_fsb", listOf("follow_button", "subscribe_button",
            "extended_follow_button_container", "extended_subscribe_button_container",
            // Twitch 31.x: the visible Follow and Subscribe live in one Compose strip in the chat
            // header, and the ids above are never shown there. That strip also carries Get Bits,
            // which cannot be separated from it, so it goes too.
            "chat_header_buttons_container")),
        Rule("hide_player_create_clip_button", listOf("create_clip_button_compose_view",
            "create_clip_text_button", "create_clip_panel")),
        Rule("hide_player_live_share_button", listOf("share_button")),
        Rule("disable_cast", listOf("cast_button")),
        // The message box of "One Chat", the chat laid over the video in fullscreen landscape.
        Rule("one_chat_lurker", listOf("one_chat_message_input_view_container")),
        // "Twitch Stories" is a show switch: off removes the row of story circles on Home.
        Rule(KEY_STORIES, listOf("stories_shelf_container", "stories_shelf"), inverted = true),
        // The "Intended for certain audiences" chip on the player. The full-screen warning in
        // front of a mature stream is a button press, not a view to hide; see applyStopSign.
        Rule(KEY_MATURE, listOf("mature_disclosure")),

        // --- View ---
        // Entry names verified against the live 28.x view tree. The names taken from the older
        // decoded resources (create_button, discover_tab) no longer match what Twitch inflates,
        // so the current ones come first and the legacy names stay as fallbacks for other builds.
        // following_nav_rail_create_button is the plus at the top of the side rail that
        // replaces the bottom bar in landscape on tablets.
        Rule("hide_create_button", listOf("viewer_bottom_nav_create_placeholder",
            "custom_create_bottom_nav_button", "create_button", "following_nav_rail_create_button")),
        // NOTE: "hide_discover_tab" is deliberately NOT a view rule -- see hideBrowseItem().
        // "hide_discover_feed" USED to live here and was actively dangerous. Its three targets:
        //   discovery_feed_home_root       root view of the ENTIRE Home fragment -- hiding it
        //                                  kills Home outright, Following tab included
        //   discovery_feed_pager_container root view of the pager fragment that IS the "Live" and
        //                                  "Clips" tabs, and parent of the error page, so hiding
        //                                  it measures the feed at 0x0 and tips it into the
        //                                  "Boo! Ghost sighting" state rather than just blanking
        //   discovery_feed_navigation      never carried by any view; a navigation-graph id only
        // Both real targets are FRAGMENT ROOTS, which the fragment manager sets back to VISIBLE on
        // every rebind, so the rule also fought the framework on every layout pass. Removing the
        // feed is now handled by hiding the individual TAB views instead -- see HomeTabs.
    )

    /** Every entry name any rule cares about, so the tree walk collects in one pass. */
    private val ALL_ENTRIES: Set<String> = RULES.flatMap { it.entries }.toSet()

    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastActivity: java.lang.ref.WeakReference<Activity>? = null

    /** The activity most recently resumed, for the dev probe. */
    fun currentActivity(): Activity? = lastActivity?.get()

    fun install(lp: LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                lastActivity = java.lang.ref.WeakReference(act)
                schedule(act)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", hook)
            XposedBridge.hookAllMethods(Activity::class.java, "onConfigurationChanged", hook)
            log("ViewHider installed (${RULES.size} rules)")
        }.onFailure { log("ViewHider hook failed: $it") }
    }

    /** Re-apply immediately after a toggle changes in the settings menu. */
    fun reapply() {
        val act = lastActivity?.get() ?: return
        handler.post { runCatching { apply(act) } }
    }

    private fun schedule(act: Activity) {
        // Later passes included deliberately: some of Twitch's chrome (notably the bottom bar)
        // only settles well after the first second, and a rule applied inside 900ms can measure a
        // layout that has not finished changing.
        for (delay in longArrayOf(0L, 300L, 900L, 2000L, 4000L, 8000L)) {
            handler.postDelayed({ runCatching { apply(act) }.onFailure { log("VH apply: $it") } }, delay)
        }
        if (GL_WIRED.add(act)) {
            runCatching {
                val root = act.window?.decorView ?: return
                root.viewTreeObserver.addOnGlobalLayoutListener { runCatching { apply(act) } }
            }
        }
    }

    const val KEY_BROWSE = "hide_discover_tab"

    /**
     * Hide the bottom bar's "Browse" entry.
     *
     * Done through the MENU, not by hiding its view. A bottom navigation bar rebuilds its item
     * views whenever the selection changes, so a view set to GONE comes back as a freshly inflated
     * VISIBLE one and only disappears again on the next layout pass -- which is exactly the
     * half-second flash of the button (or of the gap it left) when another tab is tapped.
     * The menu is the model behind those views, so an item hidden there simply never gets
     * inflated and survives every rebuild.
     *
     * Obfuscation-immune: the item id is resolved from its resource ENTRY name, and the menu is
     * reached through a public getMenu() that any menu-hosting view exposes. Views that have no
     * such method, or whose menu lacks the item, are skipped.
     */
    private fun hideBrowseItem(act: Activity, hide: Boolean) {
        val itemId = runCatching {
            act.resources.getIdentifier("viewer_bottom_nav_explore", "id", act.packageName)
        }.getOrDefault(0)
        if (itemId == 0) return
        val root = act.window?.decorView ?: return
        walk(root) { v ->
            val menu = runCatching {
                v.javaClass.getMethod("getMenu").invoke(v) as? android.view.Menu
            }.getOrNull() ?: return@walk
            val item = runCatching { menu.findItem(itemId) }.getOrNull() ?: return@walk
            // Guarded: the walk visits every menu-hosting view in the window, and some menus
            // refuse mutation. An escaping throw here used to abort the whole pass silently.
            runCatching { if (item.isVisible == hide) item.isVisible = !hide }
            runCatching { dumpNav(v) }
        }
    }

    private const val CREATE_BUTTON = "custom_create_bottom_nav_button"
    private const val CREATE_CELL = "viewer_bottom_nav_create_placeholder"

    /** Horizontal correction currently applied to a create button, so the next pass can tell the
     *  difference between "already centred" and "never moved". */
    private val NUDGED = java.util.WeakHashMap<View, Int>()

    /**
     * Sit the floating "+" on its own nav cell.
     *
     * Hiding Browse leaves four evenly spaced cells, but the create button is not one of them — it
     * is a separate view pinned to the centre of the BAR. With five cells the centre happened to be
     * its cell too; with four it is not, so it drifts ~196px right of the cell and opens a gap next
     * to Home.
     *
     * Corrected with a right MARGIN rather than translationX. Three earlier attempts used
     * translationX and none of them ever took effect on device — the geometry was right and both
     * views were reachable, but something on Twitch's side resets it. A margin is consumed by the
     * parent's own layout instead of being a draw-time transform, so it survives.
     *
     * Self-correcting rather than a fixed offset: it measures the miss on every pass and folds it
     * into the correction already applied, so it converges to zero, needs no knowledge of how many
     * cells there are, and undoes itself when Browse is switched back on.
     */
    private fun recentreCreateButton(root: View) {
        var button: View? = null
        var cell: View? = null
        walk(root) { v ->
            if (v.width == 0) return@walk
            when (runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()) {
                CREATE_BUTTON -> button = v
                CREATE_CELL -> cell = v
            }
        }
        val b = button ?: return
        val c = cell ?: return
        val lp = b.layoutParams as? ViewGroup.MarginLayoutParams ?: return

        val pos = IntArray(2)
        b.getLocationInWindow(pos); val bCentre = pos[0] + b.width / 2
        c.getLocationInWindow(pos); val cCentre = pos[0] + c.width / 2

        // Only ever correct for the case this exists for: Browse hidden, on a horizontal bar.
        // Otherwise the target is no correction at all. On a tablet the nav can be a vertical
        // rail, where comparing x centres means nothing, and with Browse showing the button
        // already sits on its cell.
        val horizontal = kotlin.math.abs(b.height - c.height) < c.height &&
            c.width > c.height
        val miss = bCentre - cCentre
        val target = if (!Settings.get(KEY_BROWSE, false) || !horizontal) 0
            else if (kotlin.math.abs(miss) <= 2) (NUDGED[b] ?: lp.rightMargin)
            // A larger right margin pulls a centre-aligned child left, so the correction is
            // additive. It cannot go negative, so a button already left of its cell is left be.
            else ((NUDGED[b] ?: 0) + miss).coerceAtLeast(0)

        // Write only when the margin actually changes. Setting layoutParams requests a layout,
        // and doing that from inside every layout pass never settles. That is exactly what
        // happened on the tablet: the clamp kept producing the same 0, which was written back
        // anyway, forever.
        if (target == lp.rightMargin) { NUDGED[b] = target; return }
        lp.rightMargin = target
        b.layoutParams = lp
        NUDGED[b] = target
        log("VH create button right margin now $target (miss was $miss)")
    }

    /** Last reported bottom-bar geometry, so a change is logged once instead of every pass. */
    @Volatile private var lastNavSig: String? = null

    /**
     * Report the bottom bar's real geometry.
     *
     * Logged on CHANGE rather than once, because the bar re-shares its cells well after the first
     * layout pass: a one-shot latch reports the pre-change state and then lies about it forever,
     * which is the exact mistake that made three earlier attempts at this look like they worked.
     *
     * Coordinates are in the window, since the floating create button is not a child of the item
     * row and its left/right are relative to a different parent.
     */
    private fun dumpNav(bar: View) {
        if (bar.width == 0) return
        val root = (bar.parent as? ViewGroup) ?: return
        val pos = IntArray(2)
        val sb = StringBuilder("NAV bar w=${bar.width}")
        walk(root) { v ->
            if (v.width == 0) return@walk
            val entry = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                ?: return@walk
            if (!entry.startsWith("viewer_bottom_nav") && entry != CREATE_BUTTON) return@walk
            v.getLocationInWindow(pos)
            sb.append(" | $entry vis=${v.visibility} x=${pos[0]} w=${v.width} c=${pos[0] + v.width / 2}")
        }
        val sig = sb.toString()
        if (sig != lastNavSig) { lastNavSig = sig; log(sig) }
    }

    const val KEY_MATURE = "disable_mature_content"
    const val KEY_STORIES = "stories"

    /** Stop-sign views already answered, so one warning is never pressed twice. */
    private val STOP_DONE = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /**
     * Answer the mature-content warning in front of a stream with its own "Start watching" button,
     * the same as a tap. Left alone whenever Twitch asks for age verification instead, so this
     * never gets anyone past a gate that is about more than a content preference.
     */
    private fun applyStopSign(root: View) {
        val ctx = root.context
        fun byName(n: String): View? {
            val id = ctx.resources.getIdentifier(n, "id", ctx.packageName)
            return if (id == 0) null else root.findViewById(id)
        }
        for (suffix in arrayOf("", "_horizontal")) {
            if (byName("stop_sign_button_age_verify$suffix")?.isShown == true) return
            val ok = byName("stop_sign_button_affirmative$suffix") ?: continue
            if (!ok.isShown || !STOP_DONE.add(ok)) continue
            val target = clickTarget(ok) ?: continue
            log("VH mature warning answered (click=${target.performClick()})")
            return
        }
    }

    /** Twitch's Button hands its listener to an inner view; press whichever one has it. */
    private fun clickTarget(v: View): View? {
        if (v.hasOnClickListeners()) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) clickTarget(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun apply(act: Activity) {
        // Cheap exit: if nothing is switched on there's no reason to walk the tree at all.
        val active = RULES.filter { it.active() }
        val root = act.window?.decorView ?: return
        // Menu-backed rather than view-backed, so it runs every pass regardless of the view rules.
        runCatching { hideBrowseItem(act, Settings.get(KEY_BROWSE, false)) }
        runCatching { recentreCreateButton(root) }

        // Report whenever the enabled set CHANGES rather than once ever. A one-shot log fired on
        // the first layout pass and then went stale, which made it report "(none)" while a rule
        // was in fact switched on -- a diagnostic that lies is worse than none at all.
        runCatching { applyHypeTrain(root, Settings.get(KEY_HYPE, false)) }
        runCatching { applyUnfollow(root, Settings.get(KEY_UNFOLLOW, false)) }
        runCatching { if (Settings.get(KEY_MATURE, false)) applyStopSign(root) }
        runCatching { SleepTimer.attach(root) }.onFailure { log("sleep timer: $it") }
        runCatching { VideoFilters.attach(root) }.onFailure { log("video filters: $it") }
        runCatching { PinnedChat.apply(root) }.onFailure { log("pinned chat: $it") }

        val sig = active.joinToString(",") { it.key } +
            (if (Settings.get(KEY_BROWSE, false)) ",$KEY_BROWSE" else "")
        if (sig != lastActiveSig) {
            lastActiveSig = sig
            log("VH enabled rules: " + sig.trim(',').ifEmpty { "(none)" })
        }
        if (active.isEmpty() && !anyHidden) return

        val landscape = act.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE

        // One walk, collecting every view whose entry name is referenced by any rule.
        val found = HashMap<String, MutableList<View>>()
        walk(root) { v ->
            val entry = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (entry != null && entry in ALL_ENTRIES) {
                found.getOrPut(entry) { mutableListOf() }.add(v)
            }
        }
        if (found.isEmpty()) return

        // An entry is hidden if ANY enabled rule naming it applies in the current orientation.
        val shouldHide = HashSet<String>()
        for (rule in active) {
            if (rule.landscape && !landscape) continue
            shouldHide.addAll(rule.entries)
        }

        var hidAny = false
        for ((entry, views) in found) {
            val hide = entry in shouldHide
            for (v in views) {
                if (hide) {
                    if (v.visibility != View.GONE) {
                        v.visibility = View.GONE
                        // Rules match by entry name ANYWHERE in the tree, with no notion of which
                        // screen they are on, so a rule written for the home feed can silently
                        // hit a dialog or a channel page. Log each distinct hide so a screen that
                        // breaks can be traced back to the exact rule that touched it.
                        val where = act.javaClass.simpleName
                        if (REPORTED.add("$entry@$where"))
                            log("VH hid '$entry' on $where (rule fired)")
                    }
                    HIDDEN[v] = true
                    hidAny = true
                } else if (HIDDEN.remove(v) != null) {
                    // Restore ONLY views we hid ourselves. Twitch keeps plenty of these GONE on
                    // purpose — follow vs unfollow are mutually exclusive, discovery-feed
                    // containers are hidden off their tab, clip panels stay collapsed until
                    // opened. Blanket-setting every match to VISIBLE force-showed all of that and
                    // corrupted screens the rules were never meant to touch.
                    if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                }
            }
        }
        anyHidden = hidAny
    }

    private const val KEY_HYPE = "disable_hype_train"
    private const val KEY_UNFOLLOW = "hide_unfollow_button"

    /** Follow buttons currently hidden because they were in the "following" state. */
    private val UNFOLLOW_HIDDEN = java.util.WeakHashMap<View, Boolean>()
    @Volatile private var unfollowLabel: String? = null

    /**
     * Hide the unfollow button on Twitch 31.x.
     *
     * There is no separate unfollow button any more: it is the same follow_button, which reads
     * "Following. Tap to unfollow" when you follow the channel. So it is hidden only in that state,
     * recognised by that exact description read from the app's own strings, so it holds in any
     * language. When you do not follow, the button is the ordinary Follow and is left alone.
     */
    private fun applyUnfollow(root: View, on: Boolean) {
        if (!on && UNFOLLOW_HIDDEN.isEmpty()) return
        val label = unfollowLabel ?: runCatching {
            val res = root.resources
            val id = res.getIdentifier("unfollow_talkback", "string", root.context.packageName)
            if (id == 0) null else res.getString(id)
        }.getOrNull()?.also { unfollowLabel = it }
        walk(root) { v ->
            if (entryOf(v) != "follow_button") return@walk
            val following = on && label != null &&
                v.contentDescription?.toString() == label
            if (following) {
                if (v.visibility != View.GONE) { v.visibility = View.GONE; log("VH hid unfollow button") }
                UNFOLLOW_HIDDEN[v] = true
            } else if (UNFOLLOW_HIDDEN.remove(v) != null && v.visibility != View.VISIBLE) {
                v.visibility = View.VISIBLE
            }
        }
    }

    /** Highlight containers currently hidden because a hype train was in them. */
    private val HYPE_HIDDEN = java.util.WeakHashMap<View, Boolean>()

    /**
     * Hide the hype train banner on Twitch 31.x.
     *
     * The hype_train ids the rule above names are never on screen any more. A hype train is now
     * shown as a community highlight, in the same banner slot that polls, predictions and raids
     * use, so hiding that slot outright would take all of those with it. Instead it is hidden only
     * while a hype train is actually in it: the "level" id belongs exclusively to the hype train
     * layouts, so its presence is the test.
     *
     * Checked by the level view's own visibility rather than isShown, because once the container
     * is hidden nothing inside it counts as shown, and the next pass would put it straight back.
     */
    private fun applyHypeTrain(root: View, on: Boolean) {
        if (!on && HYPE_HIDDEN.isEmpty()) return
        walk(root) { v ->
            if (entryOf(v) != "community_highlight_container") return@walk
            val train = on && v is ViewGroup && containsVisibleEntry(v, "level")
            if (train) {
                if (v.visibility != View.GONE) {
                    v.visibility = View.GONE
                    log("VH hid hype train banner")
                }
                HYPE_HIDDEN[v] = true
            } else if (HYPE_HIDDEN.remove(v) != null && v.visibility != View.VISIBLE) {
                v.visibility = View.VISIBLE
            }
        }
    }

    private fun entryOf(v: View): String? =
        if (v.id == View.NO_ID) null
        else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

    private fun containsVisibleEntry(root: ViewGroup, entry: String): Boolean {
        var hit = false
        walk(root) { v -> if (!hit && v.visibility == View.VISIBLE && entryOf(v) == entry) hit = true }
        return hit
    }

    /** Views this module set GONE, so a toggle-off restores exactly those and nothing else. */
    private val HIDDEN = java.util.WeakHashMap<View, Boolean>()

    /** "entry@Activity" pairs already logged, so the trace stays one line per rule per screen
     *  instead of one per layout pass. */
    private val REPORTED: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Last reported set of enabled rule keys, so a change is logged exactly once. */
    @Volatile private var lastActiveSig: String? = null

    /** Tracks whether we currently have anything hidden, so [apply] can restore after a toggle-off. */
    @Volatile private var anyHidden = false

    private inline fun walk(root: View, action: (View) -> Unit) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            action(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
        }
    }
}
