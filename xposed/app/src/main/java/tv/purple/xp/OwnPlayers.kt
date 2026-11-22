package tv.purple.xp

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * IVS players this module made itself (the two-streams screen). Every IVS hook in the module sees
 * them as thisObject, the same CorePlayerImpl Twitch uses, so the hooks that follow Twitch's own
 * player ask [isMine] first and leave ours alone.
 *
 * It also tells our players what their stream is doing. IVS reports to listeners of an abstract
 * class the module can not subclass, so instead the public methods native code calls on every
 * player (state, error, video size) are hooked here. They run on IVS's own threads, which is why
 * the hook only checks identity and posts the rest to the main thread.
 */
object OwnPlayers {

    /** Always called on the main thread, and never after [detach]. */
    interface Events {
        /** Native codes: 1 READY, 2 BUFFERING, 3 PLAYING, 4 ENDED, anything else IDLE. */
        fun state(code: Int)
        fun error(source: String, type: Int, code: Int, message: String)
        /** The decoded video size. */
        fun size(w: Int, h: Int)
    }

    private class Entry(player: Any, @Volatile var events: Events?) {
        val ref = WeakReference(player)
    }

    // Replaced as a whole on every change, so readers on IVS threads need no lock. Weak, because a
    // released player can still send a late callback and should still count as ours until it is gone.
    @Volatile private var entries: Array<Entry> = emptyArray()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var installed = false

    fun install(lp: LoadPackageParam) {
        if (installed) return
        val c = runCatching { lp.classLoader.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull()
            ?: run { log("OP no IVS player class"); return }
        installed = true
        val int = Int::class.javaPrimitiveType!!
        val str = String::class.java
        val n = listOf(
            hook(c, "handleStateChange", int) { e, a -> e.state(a[0] as? Int ?: 0) },
            hook(c, "handleError", str, int, int, str) { e, a ->
                e.error(a[0] as? String ?: "", a[1] as? Int ?: 0, a[2] as? Int ?: 0, a[3] as? String ?: "")
            },
            hook(c, "onSizeChange", int, int) { e, a -> e.size(a[0] as? Int ?: 0, a[1] as? Int ?: 0) },
        ).count { it }
        log("OP hooks: $n of 3")
    }

    private fun hook(c: Class<*>, name: String, vararg types: Class<*>, deliver: (Events, Array<Any?>) -> Unit): Boolean {
        val m = runCatching { c.getMethod(name, *types) }.getOrNull() ?: run { log("OP no $name"); return false }
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val e = entryOf(p.thisObject) ?: return
                if (e.events == null) return
                val a = p.args
                main.post {
                    // Read again here: a detach on the main thread between the native call and
                    // this post means the player is on its way to release.
                    val ev = e.events ?: return@post
                    runCatching { deliver(ev, a) }.onFailure { log("OP $name: $it") }
                }
            }
        })
        return true
    }

    /** Register right after build(), before load, so even the first callbacks are seen as ours. */
    fun add(player: Any, events: Events?) {
        synchronized(this) {
            val keep = entries.filter { val o = it.ref.get(); o != null && o !== player }
            entries = (keep + Entry(player, events)).toTypedArray()
        }
    }

    /** Stop delivering events, before the player is released. It stays ours for every hook. */
    fun detach(player: Any) {
        entryOf(player)?.events = null
    }

    /** No lock and no allocation: hooks call this on IVS's native threads for every player. */
    fun isMine(o: Any?): Boolean = entryOf(o) != null

    private fun entryOf(o: Any?): Entry? {
        if (o == null) return null
        val list = entries
        for (i in list.indices) {
            val e = list[i]
            if (e.ref.get() === o) return e
        }
        return null
    }
}
