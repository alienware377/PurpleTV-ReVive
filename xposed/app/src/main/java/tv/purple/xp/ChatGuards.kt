package tv.purple.xp

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Two small guards, pinned for Twitch 31.1.2:
 *
 *  - "Do not clear chat when commanded to": a moderator's /clear normally empties everyone's
 *    chat. The whole-chat clear reaches the app as one event (ChannelMessagesCleared), handled in
 *    one place, so that one event is dropped and the messages stay. Timeouts and bans of single
 *    users are a different event and keep working. A short note says chat was cleared.
 *  - "Turn off autoplay": when a VOD or clip ends, Twitch shows an "Up next" card and plays it
 *    after a 10 second countdown. The countdown is stopped, so nothing plays by itself; the card
 *    stays and its play button still works.
 */
object ChatGuards {

    const val KEY_NO_CLEAR = "prevent_mod_clear"
    const val KEY_NO_AUTOPLAY = "disable_theatre_autoplay"

    private const val DISPATCH = "b1"          // lambda class; case 0xa = onChannelEventReceived
    private const val DISPATCH_CASE = 0xa
    private val CHANNEL_CLEARED get() = TwitchVersion.pick("rm5", "aq5")  // ChannelMessagesCleared(channelId)
    private val UNIT get() = TwitchVersion.pick("sd50", "o760")            // the app's kotlin.Unit
    private val AUTOPLAY_VIEW get() = TwitchVersion.pick("e92", "ya2")    // autoplay "Up next" view delegate
    private val AUTOPLAY_PLAYABLE get() = TwitchVersion.pick("wcw", "hww")
    private val COUNTDOWN_DONE get() = TwitchVersion.pick("ud1", "of1")   // Runnable; case 1 with b = e92 ends the countdown

    @Volatile private var lastNote = 0L

    fun install(lp: LoadPackageParam) {
        val cl = lp.classLoader
        runCatching {
            val unit = XposedHelpers.getStaticObjectField(cl.loadClass(UNIT), "a")
            XposedBridge.hookAllMethods(cl.loadClass(DISPATCH), "invoke", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val ev = p.args.getOrNull(0) ?: return
                    if (ev.javaClass.name != CHANNEL_CLEARED) return
                    if (XposedHelpers.getIntField(p.thisObject, "a") != DISPATCH_CASE) return
                    if (!Settings.get(KEY_NO_CLEAR, false)) return
                    p.result = unit
                    log("CG kept chat after a moderator cleared it")
                    note()
                }
            })
            log("CG clear-chat guard installed")
        }.onFailure { log("CG clear-chat guard failed: $it") }

        runCatching {
            val view = cl.loadClass(AUTOPLAY_VIEW)
            XposedBridge.hookAllMethods(view, "w", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (!Settings.get(KEY_NO_AUTOPLAY, false)) return
                    if (p.args.getOrNull(0)?.javaClass?.name != AUTOPLAY_PLAYABLE) return
                    runCatching { XposedHelpers.getObjectField(p.thisObject, "l")?.let { XposedHelpers.callMethod(it, "dispose") } }
                    (XposedHelpers.getObjectField(p.thisObject, "h") as? TextView)?.text = "Up next"
                    (XposedHelpers.getObjectField(p.thisObject, "g") as? View)?.visibility = View.INVISIBLE
                    log("CG autoplay countdown stopped")
                }
            })
            // Safety net: if the countdown still finishes, do not jump to the next video.
            XposedBridge.hookAllMethods(cl.loadClass(COUNTDOWN_DONE), "run", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!Settings.get(KEY_NO_AUTOPLAY, false)) return
                    if (XposedHelpers.getIntField(p.thisObject, "a") != 1) return
                    if (XposedHelpers.getObjectField(p.thisObject, "b")?.javaClass?.name != AUTOPLAY_VIEW) return
                    p.result = null
                }
            })
            log("CG autoplay guard installed")
        }.onFailure { log("CG autoplay guard failed: $it") }
    }

    private fun note() {
        val now = SystemClock.uptimeMillis()
        if (now - lastNote < 10_000) return
        lastNote = now
        val act = ViewHider.currentActivity() ?: return
        act.runOnUiThread { Toast.makeText(act, "A moderator cleared chat. PurpleTV kept the messages.", Toast.LENGTH_SHORT).show() }
    }
}
