package tv.purple.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Current-channel resolution for channel emotes.
 *
 * 7TV/BTTV/FFZ channel sets are keyed by the Twitch numeric channel id. Found by BFS over
 * the live render graph: the readable, R8-kept class
 *
 *   tv.twitch.android.shared.chat.pub.messages.data.ChannelChatConnectionKey
 *       .channelId   : String  (e.g. "1504494384")
 *       .channelName : String  (e.g. "goshyboo")
 *
 * is constructed when chat connects to a channel. Hooking its constructor gives us the
 * channel id/login directly, and because it lives in the kept `...chat.pub.messages.data`
 * package, this anchor is obfuscation-immune (unlike the single-letter render classes).
 */
object Channels {

    private const val KEY_CLASS =
        "tv.twitch.android.shared.chat.pub.messages.data.ChannelChatConnectionKey"

    @Volatile private var loggedId: String? = null
    @Volatile private var loggedAt = 0L

    fun install(lp: LoadPackageParam) {
        val key = Names.cls(lp, KEY_CLASS) ?: run {
            log("CH ${KEY_CLASS} not found: channel-emote anchor stale")
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
                    liveId = id; liveName = name
                    EmoteRepo.loadChannelAsync(id, name)
                    ChannelPoints.onChannel(id, name)
                    RaidCard.onChannel(id)
                    runCatching { SevenTvBadges.onChannel(id) }
                }.onFailure { log("CH read error: $it") }
            }
        })
        log("CH channel anchor installed on ${KEY_CLASS}")
        installReplay(lp)
    }

    /** The channel whose chat last connected, and the channel of the last replayed line. */
    @Volatile private var liveId: String? = null
    @Volatile private var liveName: String? = null
    @Volatile private var replayId: String? = null
    /** Which chat is on screen, from the last ViewHider pass. */
    @Volatile private var liveShown = false
    @Volatile private var replayShown = false
    /** The channel of the replayed line being built on this thread, or null. */
    val replayChannel = ThreadLocal<String?>()

    /**
     * From each ViewHider pass. Whichever chat is on screen owns the emotes: the live chat gets its
     * channel back after a clip's chat or a VOD had them, and a VOD's chat gets its own back when
     * it shows again. VOD replay uses the live chat's list id too, inside a container of its own.
     */
    fun onPass(root: android.view.View) {
        val ctx = root.context
        val live = Res.id(ctx, "chat_view_delegate_root").takeIf { it != 0 }?.let { root.findViewById<android.view.View>(it) }
        val replay = Res.id(ctx, "chomments_coordinator_layout").takeIf { it != 0 }?.let { root.findViewById<android.view.View>(it) }
        liveShown = live?.isShown == true
        replayShown = replay?.isShown == true
        val now = EmoteRepo.currentChannel()
        if (liveShown && !replayShown) liveId?.let { if (it != now) EmoteRepo.loadChannelAsync(it, liveName) }
        else if (replayShown && !liveShown) replayId?.let { if (it != now) EmoteRepo.loadChannelAsync(it, null) }
    }

    /**
     * VOD chat replay never connects to a chat, so the key above is not built and the VOD's
     * channel emotes were never loaded. Its lines still go through the live assembler, so loading
     * the channel from each replayed comment is all that is missing. Clips with chat use the same
     * builder. While a live chat is on screen and the replay is not, the live chat keeps its emotes
     * and the replayed lines wait (marked with their channel) to be filled in later.
     */
    private fun installReplay(lp: LoadPackageParam) {
        val line = Names.cls(lp, Names.CHOMMENT_LINE) ?: run {
            log("CH replay line builder ${Names.CHOMMENT_LINE} not found: VOD channel emotes off")
            return
        }
        val methods = line.declaredMethods.filter {
            it.name == "a" && it.parameterTypes.size == 1 && android.text.Spanned::class.java.isAssignableFrom(it.returnType)
        }
        if (methods.isEmpty()) { log("CH replay line builder has no a(chomment): VOD channel emotes off"); return }
        for (m in methods) XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                replayChannel.set(null)
                runCatching {
                    val id = XposedHelpers.getObjectField(param.args[0], Names.CHOMMENT_CHANNEL_FIELD) as? String
                    if (id.isNullOrBlank() || !id.all { it.isDigit() }) return
                    replayChannel.set(id)
                    replayId = id
                    if (id == EmoteRepo.currentChannel()) return
                    if (liveShown && !replayShown) return
                    if (id != loggedId) { loggedId = id; log("CH replay channel: id=$id") }
                    EmoteRepo.loadChannelAsync(id, null)
                }.onFailure { log("CH replay read error: $it") }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                replayChannel.set(null)
            }
        })
        log("CH replay anchor installed on ${line.name}")
    }
}
