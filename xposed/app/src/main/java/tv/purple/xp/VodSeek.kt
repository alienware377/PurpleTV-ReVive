package tv.purple.xp

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.WeakHashMap

/**
 * "Player forward seek" and "Player backward seek" for VODs.
 *
 * Twitch's VOD buttons always jump +30 and -10 seconds: each tap adds to one running total, and a
 * second after the last tap the player seeks once. We count the taps on the two buttons and, when
 * that single seek reaches the player, swap Twitch's fixed amounts for the chosen ones.
 *
 * The seek is caught on Amazon IVS's CorePlayerImpl.seekTo, which keeps its real name because it is
 * called from native code. The buttons are found by resource name, and their own touch listener is
 * wrapped rather than replaced.
 */
object VodSeek {

    const val KEY_FWD = "forward_seek"
    const val KEY_BACK = "backward_seek"
    private const val TW_FWD_MS = 30_000L
    private const val TW_BACK_MS = 10_000L

    @Volatile private var fwd = 0
    @Volatile private var back = 0
    @Volatile private var lastTap = 0L
    private val wrapped = WeakHashMap<View, Boolean>()

    private fun fwdMs() = Settings.getInt(KEY_FWD, 30).coerceIn(5, 120) * 1000L
    private fun backMs() = Settings.getInt(KEY_BACK, 10).coerceIn(5, 120) * 1000L
    private fun custom() = fwdMs() != TW_FWD_MS || backMs() != TW_BACK_MS

    fun install(lp: LoadPackageParam) {
        val cls = runCatching { lp.classLoader.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull()
            ?: run { log("VS IVS player not found"); return }
        val m = cls.getDeclaredMethod("seekTo", java.lang.Long.TYPE)
        val getPos = runCatching { cls.getMethod("getPosition") }.getOrNull()
        val getDur = runCatching { cls.getMethod("getDuration") }.getOrNull()
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val f = fwd; val b = back
                if (f == 0 && b == 0) return
                fwd = 0; back = 0
                if (SystemClock.uptimeMillis() - lastTap > 2500 || !custom()) return
                val ms = p.args[0] as Long
                // Twitch clamps its own target to the start or end of the VOD, so taking its +30/-10
                // back off the seek can land on the wrong side there. The player has not moved yet,
                // so start from where it really is, and only undo Twitch's amounts if that fails.
                val pos = runCatching { getPos!!.invoke(p.thisObject) as Long }.getOrDefault(-1L)
                val base = if (pos >= 0) pos else ms - (TW_FWD_MS * f - TW_BACK_MS * b)
                var target = base + fwdMs() * f - backMs() * b
                val dur = runCatching { getDur!!.invoke(p.thisObject) as Long }.getOrDefault(-1L)
                target = target.coerceAtLeast(0L)
                // One short of the end, like Twitch's own seek, so it does not land on the very end.
                if (dur > 0) target = target.coerceAtMost(dur - 1)
                p.args[0] = target
                log("VS seek adjusted: ${ms / 1000}s -> ${target / 1000}s")
            }
        })
        log("VS seek hook installed")
    }

    /** From each ViewHider pass: wrap the seek buttons' own touch listeners once. */
    fun attach(root: View) {
        val ctx = root.context
        fun id(n: String) = Res.id(ctx, n)
        for ((name, forward) in listOf("seek_forward_button" to true, "seek_rewind_button" to false)) {
            val vid = id(name); if (vid == 0) continue
            val v = (root as? ViewGroup)?.findViewById<View>(vid) ?: continue
            if (wrapped.containsKey(v)) continue
            val prev = listenerOf(v) ?: continue
            wrapped[v] = true
            v.setOnTouchListener { view, ev ->
                val r = prev.onTouch(view, ev)
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && r && custom()) {
                    if (forward) fwd++ else back++
                    lastTap = SystemClock.uptimeMillis()
                    // Show the real amount instead of Twitch's fixed one.
                    val label = (root as ViewGroup).findViewById<TextView>(
                        id(if (forward) "seek_forward_amount_text_view" else "seek_rewind_amount_text_view"))
                    label?.post {
                        val sec = if (forward) fwdMs() / 1000 * fwd else backMs() / 1000 * back
                        if (sec > 0) label.text = if (forward) "+$sec" else "-$sec"
                    }
                }
                r
            }
        }
    }

    private fun listenerOf(v: View): View.OnTouchListener? = runCatching {
        val li = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(v)
        li?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }?.get(li) as? View.OnTouchListener
    }.getOrNull()
}
