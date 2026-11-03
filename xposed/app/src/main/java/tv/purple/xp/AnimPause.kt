package tv.purple.xp

import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Runs animated emotes and badges only while they can be seen.
 *
 * An AnimatedImageDrawable that was started stays started until someone stops it, whatever
 * happens to the view showing it. With hardware drawing the frames are only decoded for pictures
 * the window actually draws, so a row that scrolled away mostly costs memory, but a started
 * picture that is still drawn without being seen (a picker page next to the one on screen, a
 * line drawn into a software layer) keeps decoding and asking for new frames. So every view that
 * shows one of these pictures is tracked ([AnimBook]): a picture is stopped once no view showing
 * it is attached, visible and in a visible window, and started again when one is.
 *
 * What tells us a view changed:
 *  - attach and detach, from a listener put on each view once (a chat row scrolled off screen is
 *    detached by the RecyclerView, a closed picker or suggestion strip is removed);
 *  - its own or a parent's visibility, and the window's, from View.onVisibilityAggregated, the
 *    call the framework itself uses to pause an ImageView's animation;
 *  - the window being hidden or shown (Twitch sent to the background and back), from one window
 *    visibility listener per window, which looks at every tracked view again.
 *
 * Chat lines draw their pictures through spans, which have no view of their own. Each picture's
 * callback is one shared object ([Mux]) that redraws every view showing the picture right now,
 * so a line that moved to another row, or a picture copied onto a row by the late emote pass,
 * keeps moving. The old way pointed the callback at the first row only, and the callback could
 * also be collected, since a drawable only keeps a weak reference to it.
 */
object AnimPause {

    private const val LOG_EVERY_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())

    private val book = AnimBook<View, Drawable>(
        isRunning = { (it as? Animatable)?.isRunning == true },
        start = { runCatching { (it as? Animatable)?.start() }; changed() },
        stop = { runCatching { (it as? Animatable)?.stop() }; changed() },
        requestFlush = { main.post(Flush) },
    )

    private object Flush : Runnable {
        override fun run() { runCatching { book.flush() } }
    }

    /** Views that already carry our attach listener. */
    private val listened = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    /** Windows that already carry our window visibility listener. */
    private val windows = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ViewTreeObserver, Boolean>())

    /** True once View.onVisibilityAggregated is hooked, so a hidden parent can be trusted to be
     *  reported when it is shown again. Without it only attach and the window are looked at, and
     *  a picture under a hidden parent keeps running rather than risking one that never restarts. */
    @Volatile private var aggregated = false
    private var lastLog = 0L

    fun install() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            XposedHelpers.findAndHookMethod(View::class.java, "onVisibilityAggregated", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as View
                        // Called for every view on attach and on visibility changes, so the
                        // common case (not one of ours) is a single map lookup.
                        if (Looper.myLooper() !== Looper.getMainLooper() || !book.isHost(v)) return
                        val vis = param.args[0] as Boolean
                        runCatching { book.setShowing(v, vis && v.isAttachedToWindow) }                    }
                })
            aggregated = true
            log("anim pause: visibility hook installed")
        }.onFailure { log("anim pause: visibility hook failed, pausing on detach and window only: $it") }
    }

    /** From TextView.setText: [tv] now shows [cs]. Its animated span pictures are tracked. */
    fun onSetText(tv: TextView, cs: CharSequence?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // A view still being built can be given text on another thread. The book is main thread
        // only, so that text is looked at there.
        // By then newer text may be on it: what it shows then is what counts.
        if (Looper.myLooper() !== Looper.getMainLooper()) { main.post { onSetText(tv, tv.text) }; return }
        var list: ArrayList<Drawable>? = null
        if (cs is Spanned) {
            val spans = cs.getSpans(0, cs.length, ImageSpan::class.java)
            for (sp in spans) {
                val dr = sp.drawable
                if (dr is AnimatedImageDrawable) {
                    (list ?: ArrayList<Drawable>(spans.size).also { list = it }).add(dr)
                    // Setting the same callback again would allocate a new weak reference each time.
                    if (dr.callback !== Mux) dr.callback = Mux
                }
            }
        }
        // Most setText calls in the app are not ours and never were: nothing to do.
        if (list == null && !book.isHost(tv)) return
        listen(tv)
        book.show(tv, list ?: emptyList(), showingNow(tv))
    }

    /** After [iv].setImageDrawable([d]): the ImageView is its own callback, we only start and stop. */
    fun onImage(iv: ImageView, d: Drawable?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            (d as? Animatable)?.start(); return
        }
        val anim = d is AnimatedImageDrawable
        if (!anim && !book.isHost(iv)) return
        listen(iv)
        book.show(iv, if (anim) listOf(d!!) else emptyList(), showingNow(iv))
    }

    /** One line for the log: how many tracked pictures run and how many are paused. */
    fun report(): String {
        val s = book.stats()
        return "anim pause: running=${s.running} paused=${s.stopped} views=${s.hosts} " +
            "showing=${s.showingHosts} starts=${s.starts} stops=${s.stops}"
    }

    /** Seen by the user right now, as far as the view can tell. */
    private fun showing(v: View): Boolean =
        v.isAttachedToWindow && v.windowVisibility == View.VISIBLE && (!aggregated || v.isShown)

    /**
     * [showing], for the moment a view is given new content. A RecyclerView takes its rows off
     * their parent at the start of each layout and puts them back at the end without any attach
     * or visibility event, and a row rebound in between (a changed or restyled message) looks
     * hidden then. That row is taken as showing, and looked at again once the layout is done;
     * otherwise its emotes would stop and stay frozen on screen.
     */
    private fun showingNow(v: View): Boolean {
        if (!v.isAttachedToWindow || v.windowVisibility != View.VISIBLE) return false
        if (!aggregated || v.isShown) return true
        if (v.visibility != View.VISIBLE) return false
        var p: android.view.ViewParent? = v.parent
        while (p is View) {
            if (p.visibility != View.VISIBLE) return false
            p = p.parent
        }
        // The chain ends without reaching the window: taken off its parent for a layout.
        if (p != null) return false
        main.post { if (book.isHost(v)) runCatching { book.setShowing(v, showing(v)) } }
        return true
    }

    private fun listen(v: View) {
        if (listened.add(v)) v.addOnAttachStateChangeListener(Attach)
        if (v.isAttachedToWindow) watchWindow(v)
    }

    private fun watchWindow(v: View) {
        val vto = v.viewTreeObserver
        if (vto.isAlive && windows.add(vto)) vto.addOnWindowVisibilityChangeListener(WindowVis)
    }

    private object Attach : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            runCatching { watchWindow(v); book.setShowing(v, showing(v)) }
        }
        override fun onViewDetachedFromWindow(v: View) {
            runCatching { book.setShowing(v, false) }
        }
    }

    /** A window was hidden or shown: Twitch went to the background or came back. */
    private object WindowVis : ViewTreeObserver.OnWindowVisibilityChangeListener {
        override fun onWindowVisibilityChanged(visibility: Int) {
            runCatching { for (v in book.allHosts()) book.setShowing(v, showing(v)) }
        }
    }

    /**
     * The callback of every chat span picture. It redraws each view the picture is on now, and
     * schedules frames on the main thread the way a View would (only software drawing asks for
     * that; hardware drawing runs the frames itself). A static object, so it is never collected.
     */
    private object Mux : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            val hosts = book.hostsOf(who) ?: return
            // By index: called for every frame, so no iterator is made.
            for (i in hosts.indices) hosts[i].get()?.invalidate()
        }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            main.postAtTime(what, who, `when`)
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            main.removeCallbacks(what, who)
        }
    }

    /** At most once a minute, and only when a picture was started or stopped. */
    private fun changed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLog < LOG_EVERY_MS) return
        lastLog = now
        main.post { log(report()) }
    }
}
