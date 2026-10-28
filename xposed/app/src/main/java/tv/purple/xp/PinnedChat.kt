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
    /** Pins we pressed "Hide for Yourself" on: key -> (tries in a row, when). Twitch forgets its
     *  hidden pins when the chat is reopened, so a pin seen again later is simply hidden again. */
    private val done = HashMap<String, LongArray>()
    /** Start of the Drops notice's key in [done]; pin keys start with the channel id. */
    private const val DROPS = "drops|"
    /** Pins waiting for their 30 seconds. */
    private val timed = HashSet<String>()
    /** Containers hidden by the pin fallback, so they can be shown again. */
    private val hidden = WeakHashMap<View, Boolean>()
    /** Containers hidden by the Drops fallback -> when. Kept apart from [hidden] so the pin pass
     *  does not show them again in the same pass (an endless hide/show layout loop). */
    private val dropsHidden = WeakHashMap<View, Long>()
    /** Containers made see-through while a pin or notice is dealt with, so they can be shown again. */
    private val masked = WeakHashMap<View, Boolean>()
    private var busy = false
    private val compactTap = HashMap<String, Long>()

    fun apply(root: View) {
        // Areas with a Drops notice on show: they stay masked below.
        val drops = runCatching { applyDrops(root) }.onFailure { log("drops notice: $it") }.getOrNull() ?: emptySet()
        val mode = Settings.getInt(KEY, MODE_DEFAULT)
        if (mode == MODE_DEFAULT) { restoreAll(); unmaskAreas(drops); return }
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
        val seen = HashSet<String>()
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
            seen.add(key)
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
                    else done[key] = longArrayOf(0, android.os.SystemClock.uptimeMillis())
                }, DELAY_MS)
            }
        }
        // A hidden container whose pin is gone (or no longer the page on show) comes back.
        for (v in weakKeys(hidden)) if (v !in pinsShown) { v.visibility = View.VISIBLE; hidden.remove(v) }
        // Tries only count while the pin stays on show. One that went away (its button worked, or
        // the chat was closed) starts from nothing when it is back, so the fallback above is only
        // for a button that keeps not working, never for the same pin hidden again and again.
        val now = android.os.SystemClock.uptimeMillis()
        for ((k, v) in done) if (k !in seen && !k.startsWith(DROPS) && now - v[1] >= 2500) v[0] = 0
        unmaskAreas(pinsShown + drops)
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
            if (mask) maskArea(container)
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
        if (mask) maskArea(container)
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
        handler.postDelayed({ container.alpha = 1f; masked.remove(container); busy = false }, 150)
    }

    private fun maskArea(v: View) { v.alpha = 0f; masked[v] = true }

    /** Masked areas not in [keep] are shown again: a short banner can go away (unpinned, another
     *  page swiped in, the option turned off) before the full one ever comes to be dismissed.
     *  Not while a dismissal runs; its finish() shows its own area again. */
    private fun unmaskAreas(keep: Set<View>) {
        if (busy) return
        for (v in weakKeys(masked)) if (v !in keep) { v.alpha = 1f; masked.remove(v) }
    }

    private fun hide(v: View) {
        if (v.visibility != View.GONE) { v.visibility = View.GONE; hidden[v] = true }
    }

    /** Shows the areas the pin fallback hid. The Drops fallback keeps its own. */
    private fun restoreAll() {
        for (v in weakKeys(hidden)) v.visibility = View.VISIBLE
        hidden.clear()
    }

    private fun hideDrops(v: View) {
        if (v.visibility != View.GONE) { v.visibility = View.GONE; dropsHidden[v] = android.os.SystemClock.uptimeMillis() }
    }

    private fun restoreDrops() {
        for (v in weakKeys(dropsHidden)) v.visibility = View.VISIBLE
        dropsHidden.clear()
    }

    // ---- Drops notice ----

    /** Returns the areas with a Drops notice on show, which are left masked. */
    private fun applyDrops(root: View): Set<View> {
        val shown = HashSet<View>()
        if (!Settings.get(KEY_DROPS, false)) { restoreDrops(); return shown }
        val ctx = root.context
        fun id(n: String) = Res.id(ctx, n)
        val title = str(root, "twitch_drops") ?: return shown
        val headerId = id("highlight_header")
        val now = android.os.SystemClock.uptimeMillis()
        // An area the fallback hid comes back once its notice is gone, whatever the pin mode. A
        // hidden area is not laid out, so what it holds can be out of date: after 30 s it is shown
        // again, still masked, and looked at afresh on the next pass, once it has been laid out.
        for (v in weakKeys(dropsHidden)) {
            val since = now - (dropsHidden[v] ?: continue)
            if (since < 30_000 && holdsDrops(v, headerId, title)) continue
            dropsHidden.remove(v)
            if (since >= 30_000) { maskArea(v); shown.add(v) }
            v.visibility = View.VISIBLE
        }
        if (shown.isNotEmpty()) return shown
        val headers = ArrayList<TextView>()
        collect(root, headerId, headers)
        val key = DROPS + (EmoteRepo.currentChannel() ?: "")
        var seen = false
        for (h in headers) {
            if (!onScreen(h) || h.text?.toString()?.trim()?.equals(title, true) != true) continue
            val container = ancestor(h, id("community_highlight_container")) ?: continue
            seen = true
            shown.add(container)
            val prev = done[key]
            if (prev != null && now - prev[1] < 3000) continue
            if (prev != null && prev[0] >= 3) { hideDrops(container); continue }
            dismissDrops(root, container, h, key)
        }
        // As with pins, tries only count while the notice stays up (or stays hidden by us), so
        // notices dismissed fine before never add up to the fallback.
        val prev = done[key]
        if (!seen && dropsHidden.isEmpty() && prev != null && now - prev[1] >= 3000) prev[0] = 0
        return shown
    }

    /** Whether [v] still holds the Drops notice, asking the views themselves: nothing inside a
     *  hidden area counts as shown. */
    private fun holdsDrops(v: View, headerId: Int, title: String): Boolean {
        val hs = ArrayList<TextView>()
        collect(v, headerId, hs)
        return hs.any { it.visibility == View.VISIBLE && it.text?.toString()?.trim()?.equals(title, true) == true }
    }

    private fun dismissDrops(root: View, container: View, header: View, key: String) {
        if (busy) return
        busy = true
        val attempt = (done[key]?.get(0) ?: 0L) + 1
        done[key] = longArrayOf(attempt, android.os.SystemClock.uptimeMillis())
        maskArea(container)
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
                    // No button to press: while the notice stays, it is hidden straight away.
                    done[key]?.set(0, 3)
                    hideDrops(container)
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
