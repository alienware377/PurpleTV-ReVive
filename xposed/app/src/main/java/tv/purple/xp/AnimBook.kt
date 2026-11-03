package tv.purple.xp

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Which animated pictures should be running, and on which views they are shown.
 *
 * A host is a view that shows pictures (a chat line, a picker cell) and a picture is one animated
 * drawable. A picture runs while at least one of its hosts is showing (attached, in a visible
 * window, every parent visible) and stops, a main loop turn later, when none is. A picture can
 * be on two hosts at once:
 * the same chat line drawn in two places, a recycled row that has not been given new text yet, or
 * the late emote pass copying a picture onto a row on screen. It keeps running while either shows.
 *
 * Plain Kotlin with no Android types, so the bookkeeping can be tested on the JVM. [H] and [D]
 * are compared by identity (views and drawables do not override equals). Hosts and pictures are
 * held weakly, so a view or a line that is gone is not kept alive by this book. Not thread safe:
 * everything runs on the main thread, where views are bound and drawn.
 */
class AnimBook<H : Any, D : Any>(
    private val isRunning: (D) -> Boolean,
    private val start: (D) -> Unit,
    private val stop: (D) -> Unit,
    /** Asks for [flush] to be called soon (the next main loop turn on a device). */
    private val requestFlush: () -> Unit,
) {
    private class Host<D>(var showing: Boolean) { val pics = ArrayList<D>(2) }

    private val hosts = WeakHashMap<H, Host<D>>()
    /** Each picture's hosts. Weak references, so a picture alive in a chat model does not keep
     *  an old row alive. Never holds the picture itself, or the weak key could not be cleared. */
    private val pics = WeakHashMap<D, ArrayList<WeakReference<H>>>()

    /** How many times a picture was started or stopped here, for the diagnostic line. */
    var starts = 0L; private set
    var stops = 0L; private set

    fun isHost(h: H): Boolean = hosts.containsKey(h)

    /** [h] now shows exactly [list] (empty when its new content has no animated picture). */
    fun show(h: H, list: List<D>, showing: Boolean) {
        val st = hosts.getOrPut(h) { Host(showing) }
        st.showing = showing
        // Pictures it no longer shows: drop the link and stop them unless another host shows them.
        for (i in st.pics.indices) {
            val d = st.pics[i]
            if (!containsRef(list, d)) { unlink(d, h); refresh(d) }
        }
        st.pics.clear()
        for (i in list.indices) {
            val d = list[i]
            if (containsRef(st.pics, d)) continue
            st.pics.add(d)
            link(d, h)
            refresh(d)
        }
    }

    /** [h] was attached, detached, hidden or shown. */
    fun setShowing(h: H, showing: Boolean) {
        val st = hosts[h] ?: return
        if (st.showing == showing) return
        st.showing = showing
        for (i in st.pics.indices) refresh(st.pics[i])
    }

    /** The live hosts of [d], or null. The list is the book's own: read it, do not keep it. */
    fun hostsOf(d: D): List<WeakReference<H>>? = pics[d]

    /** A copy of every known host, to look at each one again (a window was hidden or shown). */
    fun allHosts(): List<H> = ArrayList(hosts.keys)

    /** Counts for the diagnostic line. Walks every picture, so only call it now and then. */
    fun stats(): Stats {
        var running = 0; var stopped = 0
        for (d in ArrayList(pics.keys)) if (isRunning(d)) running++ else stopped++
        var showing = 0
        for (st in hosts.values) if (st.showing && st.pics.isNotEmpty()) showing++
        return Stats(running, stopped, hosts.size, showing, starts, stops)
    }

    data class Stats(
        val running: Int, val stopped: Int, val hosts: Int, val showingHosts: Int,
        val starts: Long, val stops: Long,
    )

    private fun link(d: D, h: H) {
        val list = pics.getOrPut(d) { ArrayList(1) }
        var i = list.size - 1
        while (i >= 0) {
            val o = list[i].get()
            if (o === h) return
            if (o == null) list.removeAt(i)
            i--
        }
        list.add(WeakReference(h))
    }

    private fun unlink(d: D, h: H) {
        val list = pics[d] ?: return
        var i = list.size - 1
        while (i >= 0) {
            val o = list[i].get()
            if (o == null || o === h) list.removeAt(i)
            i--
        }
    }

    /**
     * Stop the pictures that were left without a showing host, unless one showed them again
     * since. Called by the owner soon after [requestFlush]: a picture moving to another row, or
     * a suggestion strip rebuilt for the next keystroke, is unlinked from the old view a moment
     * before the new one is attached, and stopping it in between would restart its animation.
     */
    fun flush() {
        if (pending.isEmpty()) return
        val list = ArrayList(pending)
        pending.clear()
        for (d in list) if (!wanted(d) && isRunning(d)) { stop(d); stops++ }
    }

    private val pending = ArrayList<D>()

    /** Start [d] if one of its hosts is showing; if none is, stop it on the next [flush]. */
    private fun refresh(d: D) {
        if (wanted(d)) {
            if (!isRunning(d)) { start(d); starts++ }
        } else if (isRunning(d) && !containsRef(pending, d)) {
            pending.add(d)
            if (pending.size == 1) requestFlush()
        }
    }

    private fun wanted(d: D): Boolean {
        val list = pics[d] ?: return false
        var want = false
        var i = list.size - 1
        while (i >= 0) {
            val h = list[i].get()
            if (h == null) list.removeAt(i)
            else if (hosts[h]?.showing == true) want = true
            i--
        }
        // On no host any more: nothing will draw it, so it is forgotten once stopped.
        if (list.isEmpty()) pics.remove(d)
        return want
    }

    private fun containsRef(list: List<D>, d: D): Boolean {
        for (i in list.indices) if (list[i] === d) return true
        return false
    }
}
