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
    private const val MODE_DEFAULT = 0
    private const val MODE_DISABLED = 1
    private const val MODE_TIMED = 2
    private const val DELAY_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    /** Pins already dealt with, by message text, so a rebound page is not handled twice. */
    private val done = HashSet<String>()
    /** Pins waiting for their 30 seconds. */
    private val timed = HashSet<String>()
    /** Containers hidden by the fallback, so they can be shown again. */
    private val hidden = WeakHashMap<View, Boolean>()
    private var busy = false

    fun apply(root: View) {
        val mode = Settings.getInt(KEY, MODE_DEFAULT)
        if (mode == MODE_DEFAULT) { restoreAll(); return }
        val ctx = root.context
        fun id(n: String) = ctx.resources.getIdentifier(n, "id", ctx.packageName)
        val headerId = id("highlight_header")
        val containerId = id("community_highlight_container")
        if (headerId == 0 || containerId == 0) return
        val notice = str(root, "pinned_chat_message_notice")
        val prefix = pinnedByPrefix(root)

        val headers = ArrayList<TextView>()
        collect(root, headerId, headers)
        val pinsShown = HashSet<View>()
        for (h in headers) {
            if (!h.isShown) continue
            val t = h.text?.toString()?.trim() ?: continue
            val isPin = (notice != null && t.equals(notice, true)) || (prefix != null && t.startsWith(prefix, true))
            if (!isPin) continue
            val container = ancestor(h, containerId) ?: continue
            pinsShown.add(container)
            val key = keyOf(h, id("highlight_subheader"))
            if (key in done) {
                // Our dismiss did not take (or the button was missing): keep it out of sight.
                hide(container)
                continue
            }
            when (mode) {
                MODE_DISABLED -> dismiss(container, h, key, mask = true)
                MODE_TIMED -> if (timed.add(key)) handler.postDelayed({
                    if (h.isShown) dismiss(container, h, key, mask = false) else done.add(key)
                }, DELAY_MS)
            }
        }
        // A hidden container whose pin is gone (or no longer the page on show) comes back.
        for (v in hidden.keys.toList()) if (v !in pinsShown) { v.visibility = View.VISIBLE; hidden.remove(v) }
    }

    private fun dismiss(container: View, header: View, key: String, mask: Boolean) {
        if (busy) return
        busy = true
        done.add(key)
        if (mask) container.alpha = 0f
        val ctx = container.context
        fun id(n: String) = ctx.resources.getIdentifier(n, "id", ctx.packageName)
        // Expand the banner: its action arrow, or the banner itself.
        val page = ancestor(header, id("highlight_container")) ?: header
        val expand = (page as? ViewGroup)?.findViewById<View>(id("banner_action_icon"))
        (clickTarget(expand ?: page) ?: clickTarget(page))?.performClick()
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                val btn = (container as? ViewGroup)?.findViewById<View>(id("dismiss_pinned_message_button"))
                val target = btn?.takeIf { it.isShown }?.let { clickTarget(it) }
                if (target != null) {
                    target.performClick()
                    log("pinned message hidden")
                    finish(container)
                } else if (++tries < 15) {
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

    /** Forget handled pins when the channel changes, so a new channel's pin is handled afresh. */
    fun channelChanged() { done.clear(); timed.clear() }

    private fun keyOf(header: TextView, subId: Int): String {
        val page = (header.parent as? ViewGroup)
        val sub = page?.findViewById<TextView>(subId)?.text?.toString()
        return (EmoteRepo.currentChannel() ?: "") + "|" + (sub ?: header.text.toString())
    }

    private fun str(v: View, name: String): String? {
        val id = v.resources.getIdentifier(name, "string", v.context.packageName)
        return if (id == 0) null else runCatching { v.resources.getString(id).trim() }.getOrNull()
    }

    /** The words before the badge and name in "Pinned by <badge> name", in the app's language. */
    private fun pinnedByPrefix(v: View): String? {
        val id = v.resources.getIdentifier("pinned_chat_message_pinned_by_text", "string", v.context.packageName)
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

    private fun clickTarget(v: View?): View? {
        if (v == null) return null
        if (v.hasOnClickListeners()) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) clickTarget(v.getChildAt(i))?.let { return it }
        return null
    }
}
