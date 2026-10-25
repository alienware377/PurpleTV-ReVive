package tv.purple.xp

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Hide whole sections of the Following page: Continue Watching, Categories and Offline channels.
 *
 * The page is one RecyclerView ("following_list_recycler_view") holding a header row plus item
 * rows per section. Rows are recognised without any obfuscated code: headers by their text
 * (Twitch's own section title strings, so any language works), and item rows by views only that
 * section's layout has (a VOD progress bar, a "new videos" dot, the horizontal categories list).
 * A RecyclerView ignores GONE on its rows, so a hidden row is squeezed to zero height instead, and
 * put back exactly when the option is switched off or the row is reused for something else.
 */
object FollowingSections {

    const val KEY_RESUME = "hide_resume_watching_section"
    const val KEY_GAMES = "hide_game_section"
    const val KEY_OFFLINE = "hide_offline_channel_section"

    private class Saved(val height: Int, val top: Int, val bottom: Int)
    private val saved = WeakHashMap<View, Saved>()
    private val wired = WeakHashMap<View, Boolean>()

    fun attach(root: View) {
        val ctx = root.context
        val id = Res.id(ctx, "following_list_recycler_view")
        if (id == 0) return
        val rv = (root as? ViewGroup)?.findViewById<ViewGroup>(id) ?: return
        if (wired.containsKey(rv)) return
        wired[rv] = true
        rv.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!rv.isAttachedToWindow) return true
                return runCatching { !pass(rv) }.getOrDefault(true)
            }
        })
    }

    /** Returns true when anything changed (the frame is then skipped and laid out again). */
    private fun pass(rv: ViewGroup): Boolean {
        val any = Settings.get(KEY_RESUME, false) || Settings.get(KEY_GAMES, false) || Settings.get(KEY_OFFLINE, false)
        if (!any && saved.isEmpty()) return false
        val ctx = rv.context
        fun id(n: String) = Res.id(ctx, n)
        fun str(n: String) = Res.id(ctx, n, "string").takeIf { it != 0 }
            ?.let { runCatching { ctx.getString(it).trim() }.getOrNull() }
        val hdrId = id("following_tab_section_header")
        val hidden = HashMap<String, String?>()
        if (Settings.get(KEY_RESUME, false)) hidden["resume"] = str("continue_watching")
        if (Settings.get(KEY_GAMES, false)) hidden["games"] = str("followed_categories_section_title")
        if (Settings.get(KEY_OFFLINE, false)) hidden["offline"] = str("following_offline_channels_2")
        val vodId = id("vod_progress_watched"); val newVidId = id("new_videos_indicator"); val hlistId = id("horizontal_list")

        var changed = false
        for (i in 0 until rv.childCount) {
            val row = rv.getChildAt(i)
            val header = (row as? ViewGroup)?.findViewById<TextView>(hdrId) ?: (row as? TextView)?.takeIf { it.id == hdrId }
            val hide = when {
                header != null -> {
                    val t = header.text?.toString()?.trim()
                    hidden.values.any { it != null && it.equals(t, true) }
                }
                "resume" in hidden && vodId != 0 && row.findViewById<View>(vodId) != null -> true
                "offline" in hidden && newVidId != 0 && row.findViewById<View>(newVidId) != null -> true
                "games" in hidden && hlistId != 0 && (row.id == hlistId || row.findViewById<View>(hlistId) != null) -> true
                else -> false
            }
            changed = changed or setCollapsed(row, hide)
        }
        if (changed) rv.requestLayout()
        return changed
    }

    private fun setCollapsed(row: View, hide: Boolean): Boolean {
        val lp = row.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        if (hide) {
            if (saved.containsKey(row) && lp.height == 0) return false
            if (!saved.containsKey(row)) saved[row] = Saved(lp.height, lp.topMargin, lp.bottomMargin)
            lp.height = 0; lp.topMargin = 0; lp.bottomMargin = 0
            row.layoutParams = lp
            row.visibility = View.INVISIBLE
            return true
        }
        val s = saved.remove(row) ?: return false
        lp.height = s.height; lp.topMargin = s.top; lp.bottomMargin = s.bottom
        row.layoutParams = lp
        row.visibility = View.VISIBLE
        return true
    }
}
