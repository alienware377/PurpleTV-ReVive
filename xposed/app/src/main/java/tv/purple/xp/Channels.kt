package tv.purple.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Current-channel resolution (task #9 channel-emote half).
 *
 * 7TV/BTTV/FFZ channel sets are keyed by the Twitch numeric channel id. Found by BFS over
 * the live render graph: the READABLE, R8-kept class
 *
 *   tv.twitch.android.shared.chat.pub.messages.data.ChannelChatConnectionKey
 *       .channelId   : String  (e.g. "1504494384")
 *       .channelName : String  (e.g. "goshyboo")
 *
 * is constructed when chat connects to a channel. Hooking its constructor gives us the
 * channel id/login directly — and because it lives in the kept `...chat.pub.messages.data`
 * package, this anchor is obfuscation-immune (unlike the single-letter render classes).
 */
object Channels {

    private const val KEY_CLASS =
        "tv.twitch.android.shared.chat.pub.messages.data.ChannelChatConnectionKey"

    @Volatile private var loggedId: String? = null
    @Volatile private var loggedAt = 0L

    fun install(lp: LoadPackageParam) {
        val key = Names.cls(lp, KEY_CLASS) ?: run {
            log("CH ${KEY_CLASS} not found — channel-emote anchor stale")
            return
        }
        XposedBridge.hookAllConstructors(key, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val o = param.thisObject
                    val id = XposedHelpers.getObjectField(o, "channelId")?.toString() ?: return
                    val name = runCatching {
                        XposedHelpers.getObjectField(o, "channelName")?.toString()
                    }.getOrNull()
                    // Twitch builds this key 10 or more times in a few ms on every channel open, so
                    // only the first of a burst is logged. Everything below still runs each time.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (id != loggedId || now - loggedAt > 2000) {
                        loggedId = id; loggedAt = now
                        log("CH channel connect: id=$id name=$name")
                    }
                    EmoteRepo.loadChannelAsync(id, name)
                    ChannelPoints.onChannel(id, name)
                    RaidCard.onChannel(id)
                }.onFailure { log("CH read error: $it") }
            }
        })
        log("CH channel anchor installed on ${KEY_CLASS}")
    }
}
