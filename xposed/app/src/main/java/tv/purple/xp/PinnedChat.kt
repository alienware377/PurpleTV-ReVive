package tv.purple.xp

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/**
 * "Pinned messages: Default / Disabled / 30 sec."
 *
 * A pinned chat message is one page of Twitch's community highlight pager, which it shares with
 * hype trains, polls and predictions, so the container cannot simply be hidden. Instead the pinned
 * page is recognised by its header text (Twitch's own "Pinned Chat" / "Pinned by ..." strings,
 * looked up by resource name, so it follows the app's language) and removed the way a viewer
 * would: expand it and press Twitch's own "Hide for Yourself" button. Other highlights stay.
 *
 * Disabled does that as soon as a pin appears, with the highlight area masked so it does not
 * flash. 30 sec waits 30 seconds after a pin first appears. If the button cannot be found, the
 * highlight area is hidden while the pin is the page on show, like the hype train option.
 */
object PinnedChat {

    const val KEY = "pinned_message"
    /** Also dismiss the "Twitch Drops" notice the same way, with its own "Dismiss This Message". */
    const val KEY_DROPS = "hide_drops_notice"
    private const val MODE_DEFAULT = 0
    private const val MODE_DISABLED = 1
    private const val MODE_TIMED = 2
    private const val DELAY_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    /** Pins we pressed "Hide for Yourself" on: key -> (tries, when). Twitch forgets its hidden
     *  pins when the chat is reopened, so a pin seen again later is simply hidden again. */
    private val done = HashMap<String, LongArray>()
    /** Pins waiting for their 30 seconds. */
    private val timed = HashSet<String>()
    /** Containers hidden by the fallback, so they can be shown again. */
    private val hidden = WeakHashMap<View, Boolean>()
    private var busy = false
    private val compactTap = HashMap<String, Long>()

    fun apply(root: View) {
        runCatching { applyDrops(root) }.onFailure { log("drops notice: $it") }
        val mode = Settings.getInt(KEY, MODE_DEFAULT)
        if (mode == MODE_DEFAULT) { if (!Settings.get(KEY_DROPS, false)) restoreAll(); return }
        val ctx = root.context
        fun id(n: String) = Res.id(ctx, n)
        val headerId = id("highlight_header")
        val containerId = id("community_highlight_container")
        if (headerId == 0 || containerId == 0) return
        val notice = str(root, "pinned_chat_message_notice")
        val prefix = pinnedByPrefix(root)

        val headers = ArrayList<TextView>()
        collect(root, headerId, headers)
        val pinsShown = HashSet<View>()
        for (h in headers) {
            val t = h.text?.toString()?.trim() ?: continue
            val isPin = (notice != null && t.equals(notice, true)) || (prefix != null && t.startsWith(prefix, true))
            if (!isPin) continue
            val container = ancestor(h, containerId) ?: continue
            // A pin inside an area we hid still counts as present, so the area is not flipped
            // back and forth; any other pin only counts while it is on screen.
            if (container in hidden) { pinsShown.add(container); continue }
            if (!onScreen(h)) continue
            pinsShown.add(container)
            val key = keyOf(h, id("highlight_subheader"))
            val prev = done[key]
            if (prev != null) {
                val since = android.os.SystemClock.uptimeMillis() - prev[1]
                if (since < 2500) continue                       // still being hidden
                if (prev[0] >= 3) { hide(container); continue }  // the button never worked: hide the area
                dismiss(container, h, key, mask = mode == MODE_DISABLED)
                continue
            }
            when (mode) {
                MODE_DISABLED -> dismiss(container, h, key, mask = true)
                MODE_TIMED -> if (timed.add(key)) handler.postDelayed({
                    if (h.isShown) dismiss(container, h, key, mask = false)
                    else done[key] = longArrayOf(1, android.os.SystemClock.uptimeMillis())
                }, DELAY_MS)
            }
        }
        // A hidden container whose pin is gone (or no longer the page on show) comes back.
        for (v in hidden.keys.toList()) if (v !in pinsShown) { v.visibility = View.VISIBLE; hidden.remove(v) }
    }

    private fun dismiss(container: View, header: View, key: String, mask: Boolean) {
        if (busy) return
        val ctx0 = container.context
        fun id0(n: String) = Res.id(ctx0, n)
        val page0 = ancestor(header, id0("highlight_container")) ?: header
        val already = (container as? ViewGroup)?.findViewById<View>(id0("dismiss_pinned_message_button"))?.takeIf { it.isShown }
        val arrow = (page0 as? ViewGroup)?.findViewById<View>(id0("banner_action_icon"))?.takeIf { it.isShown }
        // Twitch first shows a short "Pinned Chat" banner without the expand arrow, then swaps in
        // the full one. Until the arrow (or the expanded card) is there, keep it masked and wait.
        if (already == null && arrow == null) {
            if (mask) container.alpha = 0f
            // The short banner turns into the full one when tapped; tap it once per second at most.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - (compactTap[key] ?: 0L) > 1000) {
                compactTap[key] = now
                (clickTarget(page0) ?: clickUp(header))?.performClick()
                handler.postDelayed({ ViewHider.reapply() }, 400)
            }
            return
        }
        busy = true
        val attempt = (done[key]?.get(0) ?: 0L) + 1
        done[key] = longArrayOf(attempt, android.os.SystemClock.uptimeMillis())
        if (mask) container.alpha = 0f
        val ctx = container.context
        fun id(n: String) = Res.id(ctx, n)
        // Expand the banner: its action arrow, or the banner itself.
        val page = ancestor(header, id("highlight_container")) ?: header
        if (already == null) {
            val expand = (page as? ViewGroup)?.findViewById<View>(id("banner_action_icon"))?.takeIf { it.isShown }
            (expand?.let { clickTarget(it) ?: clickUp(it) })?.performClick()
        }
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                val btn = (container as? ViewGroup)?.findViewById<View>(id("dismiss_pinned_message_button"))
                val target = btn?.takeIf { it.isShown }?.let { clickTarget(it) }
                if (target != null) {
                    target.performClick()
                    log("pinned message hidden")
                    finish(container)
                } else if (++tries < 30) {
                    handler.postDelayed(this, 100)
                } else {
                    log("pinned message: no hide button, hiding the highlight area instead")
                    hide(container)
                    finish(container)
                }
            }
        }
        handler.postDelayed(poll, 120)
    }

    private fun finish(container: View) {
        handler.postDelayed({ container.alpha = 1f; busy = false }, 150)
    }

    private fun hide(v: View) {
        if (v.visibility != View.GONE) { v.visibility = View.GONE; hidden[v] = true }
    }

    private fun restoreAll() {
        for (v in hidden.keys.toList()) v.visibility = View.VISIBLE
        hidden.clear()
    }

    // ---- Drops notice ----

    private fun applyDrops(root: View) {
        if (!Settings.get(KEY_DROPS, false)) return
        val ctx = root.context
        fun id(n: String) = Res.id(ctx, n)
        val title = str(root, "twitch_drops") ?: return
        val headers = ArrayList<TextView>()
        collect(root, id("highlight_header"), headers)
        for (h in headers) {
            if (!onScreen(h) || h.text?.toString()?.trim()?.equals(title, true) != true) continue
            val container = ancestor(h, id("community_highlight_container")) ?: continue
            val key = "drops|" + (EmoteRepo.currentChannel() ?: "")
            val prev = done[key]
            if (prev != null && android.os.SystemClock.uptimeMillis() - prev[1] < 3000) continue
            if (prev != null && prev[0] >= 3) { hide(container); continue }
            dismissDrops(root, container, h, key)
        }
    }

    private fun dismissDrops(root: View, container: View, header: View, key: String) {
        if (busy) return
        busy = true
        val attempt = (done[key]?.get(0) ?: 0L) + 1
        done[key] = longArrayOf(attempt, android.os.SystemClock.uptimeMillis())
        container.alpha = 0f
        val ctx = root.context
        val label = str(root, "dismiss_drops") ?: "Dismiss This Message"
        val page = ancestor(header, Res.id(ctx, "highlight_container")) ?: header
        (clickTarget(page) ?: clickUp(header))?.performClick()
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                if (ComposeClick.clickText(root.rootView, label)) {
                    log("drops notice hidden")
                    finish(container)
                } else if (++tries < 20) {
                    handler.postDelayed(this, 150)
                } else {
                    log("drops notice: no dismiss button, hiding the highlight area instead")
                    hide(container)
                    finish(container)
                }
            }
        }
        handler.postDelayed(poll, 250)
    }

    /** Forget handled pins when the channel changes, so a new channel's pin is handled afresh. */
    fun channelChanged() { done.clear(); timed.clear() }

    /** Really on screen: the highlight pager keeps its other pages laid out beside the visible one. */
    private fun onScreen(v: View): Boolean {
        if (!v.isShown) return false
        val r = android.graphics.Rect()
        return v.getGlobalVisibleRect(r) && r.width() > v.width / 2
    }

    private fun keyOf(header: TextView, subId: Int): String {
        val page = (header.parent as? ViewGroup)
        val sub = page?.findViewById<TextView>(subId)?.text?.toString()
        return (EmoteRepo.currentChannel() ?: "") + "|" + (sub ?: header.text.toString())
    }

    private fun str(v: View, name: String): String? {
        val id = Res.id(v.context, name, "string")
        return if (id == 0) null else runCatching { v.resources.getString(id).trim() }.getOrNull()
    }

    /** The words before the badge and name in "Pinned by <badge> name", in the app's language. */
    private fun pinnedByPrefix(v: View): String? {
        val id = Res.id(v.context, "pinned_chat_message_pinned_by_text", "string")
        if (id == 0) return null
        val raw = runCatching { v.resources.getText(id).toString() }.getOrNull() ?: return null
        val cut = raw.indexOfFirst { it == '￼' || it == '%' }
        return (if (cut > 0) raw.substring(0, cut) else raw).trim().takeIf { it.length >= 3 }
    }

    private fun collect(v: View, id: Int, out: MutableList<TextView>) {
        if (v.id == id && v is TextView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), id, out)
    }

    private fun ancestor(v: View, id: Int): View? {
        var p: View? = v
        while (p != null) { if (p.id == id) return p; p = p.parent as? View }
        return null
    }

    /** The nearest clickable view at or above [v]. */
    private fun clickUp(v: View): View? {
        var p: View? = v
        while (p != null) { if (p.hasOnClickListeners()) return p; p = p.parent as? View }
        return null
    }

    private fun clickTarget(v: View?): View? {
        if (v == null) return null
        if (v.hasOnClickListeners()) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) clickTarget(v.getChildAt(i))?.let { return it }
        return null
    }
}
