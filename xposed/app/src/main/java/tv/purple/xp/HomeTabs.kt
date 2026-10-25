package tv.purple.xp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Removes the Home page's "Live" and "Clips" tabs and keeps "Following" selected.
 *
 * Those two tabs ARE Twitch's discovery feed -- the short-form vertical video pager. The obvious
 * approach, hiding the feed's container views by resource entry name, is a trap: those containers
 * are FRAGMENT ROOTS. Hiding the pager's root measures it at 0x0 and tips the feed into its error
 * state ("Boo! Ghost sighting"), hiding the home root kills the whole Home page including
 * Following, and the fragment manager sets both back to VISIBLE on every rebind so the rule
 * silently fights the framework forever.
 *
 * Removing the TABS instead sidesteps all of that: the pages still exist and stay perfectly
 * healthy, they just become unreachable.
 *
 * Obfuscation-immune throughout. The tab strip is found by resource entry name; its internals are
 * plain framework containers (the strip's only child is a LinearLayout of per-tab LinearLayouts);
 * tabs are identified by comparing their label against Twitch's OWN string resources, looked up by
 * entry name rather than by id; and selection uses View.performClick(), which the tab view
 * overrides to select itself.
 */
object HomeTabs {

    const val KEY = "hide_live_clips_tabs"
    /** Select Following once whenever the Home tab strip first appears, leaving the other tabs. */
    const val KEY_DEFAULT_FOLLOWING = "home_default_following"

    /** Tab strips already steered to Following, so the user's own tab choice is never undone. */
    private val STEERED = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** Both strips exist in the layout and Twitch shows exactly one, so operate on whichever is
     *  currently visible rather than assuming. */
    private val STRIP_IDS = listOf("tab_layout", "tab_layout_no_tab_width")

    /** String resource ENTRY names for the tabs to remove, and the one to fall back to. */
    private val REMOVE = listOf("live", "clips")
    private const val KEEP = "following"

    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastActivity: java.lang.ref.WeakReference<Activity>? = null
    @Volatile private var logged = false

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
            log("HomeTabs installed")
        }.onFailure { log("HomeTabs hook failed: $it") }
    }

    fun reapply() {
        val act = lastActivity?.get() ?: return
        handler.post { runCatching { apply(act) } }
    }

    private fun schedule(act: Activity) {
        for (d in longArrayOf(0L, 400L, 1200L)) {
            handler.postDelayed({ runCatching { apply(act) }.onFailure { log("HT apply: $it") } }, d)
        }
        // The tab mediator tears down and rebuilds every tab view whenever the pager's adapter
        // changes, so a one-shot pass would be undone. A layout listener is the only way to keep
        // up, and the work below is cheap and idempotent.
        if (GL_WIRED.add(act)) {
            runCatching {
                act.window?.decorView?.viewTreeObserver
                    ?.addOnGlobalLayoutListener { runCatching { apply(act) } }
            }
        }
    }

    private fun apply(act: Activity) {
        runCatching { openOnFollowing(act) }
        if (!Settings.get(KEY, false)) {
            if (restoreNeeded) restore(act)
            return
        }
        val root = act.window?.decorView ?: return
        val strip = findStrip(root) ?: return
        // The strip's single child is the row that holds one view per tab.
        val row = (strip as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return

        val labels = labelsOf(act)
        var following: View? = null
        var hidSelected = false

        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i)
            val text = firstText(tab)?.trim()?.lowercase() ?: continue
            when {
                labels.remove.any { it.equals(text, true) } -> {
                    if (tab.visibility != View.GONE) {
                        if (tab.isSelected) hidSelected = true
                        tab.visibility = View.GONE
                        HIDDEN[tab] = true
                        restoreNeeded = true
                        if (!logged) { logged = true; log("HT removed Live/Clips tabs") }
                    }
                }
                labels.keep.equals(text, true) -> following = tab
            }
        }

        // Only steer the selection when the tab the user was on has just been taken away --
        // otherwise this would yank them back to Following every single layout pass.
        if (hidSelected && following != null && !following.isSelected) {
            runCatching { following.performClick() }
        }
    }

    /** The first time a Home tab strip is seen, select Following, once. */
    private fun openOnFollowing(act: Activity) {
        if (!Settings.get(KEY_DEFAULT_FOLLOWING, true)) return
        val root = act.window?.decorView ?: return
        val strip = findStrip(root) ?: return
        if (!strip.isShown || strip in STEERED) return
        val row = (strip as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        if (row.childCount == 0) return
        val keep = labelsOf(act).keep
        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i)
            if (firstText(tab)?.trim()?.equals(keep, true) != true) continue
            STEERED.add(strip)
            if (!tab.isSelected) { runCatching { tab.performClick() }; log("HT opened Home on Following") }
            return
        }
    }

    /** Tabs we hid, so switching the option off puts back exactly those. */
    private val HIDDEN = java.util.WeakHashMap<View, Boolean>()
    @Volatile private var restoreNeeded = false

    private fun restore(act: Activity) {
        val root = act.window?.decorView ?: return
        val strip = findStrip(root) ?: return
        val row = (strip as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i)
            if (HIDDEN.remove(tab) != null && tab.visibility != View.VISIBLE)
                tab.visibility = View.VISIBLE
        }
        restoreNeeded = false
    }

    private class Labels(val remove: List<String>, val keep: String)

    /** Twitch's own tab captions, resolved by resource ENTRY name so this follows the app's
     *  language instead of assuming English. */
    private fun labelsOf(act: Activity): Labels {
        val r = act.resources
        val p = act.packageName
        fun s(entry: String): String? = runCatching {
            val id = r.getIdentifier(entry, "string", p)
            if (id != 0) r.getString(id).trim().lowercase() else null
        }.getOrNull()
        return Labels(REMOVE.mapNotNull { s(it) }, s(KEEP) ?: "following")
    }

    private fun findStrip(root: View): View? {
        var fallback: View? = null
        walk(root) { v ->
            val e = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (e != null && e in STRIP_IDS) {
                if (v.visibility == View.VISIBLE && fallback == null) fallback = v
                else if (fallback == null) fallback = v
            }
        }
        return fallback
    }

    /** First non-empty TextView caption inside a tab. */
    private fun firstText(v: View): String? {
        if (v is TextView) return v.text?.toString()?.takeIf { it.isNotBlank() }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) firstText(v.getChildAt(i))?.let { return it }
        }
        return null
    }

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
