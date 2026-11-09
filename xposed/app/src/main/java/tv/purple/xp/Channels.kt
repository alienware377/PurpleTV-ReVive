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
                    liveId = id; liveName = name; liveAt = now
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

    /** The channel whose chat last connected, and when a line of it was last built. */
    @Volatile private var liveId: String? = null
    @Volatile private var liveName: String? = null
    @Volatile private var liveAt = 0L
    /** When a replayed line was last built, and whether one is being built on this thread. */
    @Volatile private var replayAt = 0L
    private val inReplay = ThreadLocal<Boolean>()
    /** Set while a replayed line is built for a channel whose emotes are not in yet. */
    val replayWaiting = ThreadLocal<Boolean>()

    /** How long one kind of chat must be quiet before the other may take the emote channel over. */
    private const val QUIET_MS = 5_000L

    /**
     * From the line assembler, for every chat line. A line of the connected live chat brings that
     * channel's emotes back once replays have gone quiet (a clip's chat or a VOD in the background
     * had taken them over).
     */
    fun onLine() {
        if (inReplay.get() == true) return
        val now = android.os.SystemClock.uptimeMillis()
        liveAt = now
        val id = liveId ?: return
        if (id != EmoteRepo.currentChannel() && now - replayAt > QUIET_MS) EmoteRepo.loadChannelAsync(id, liveName)
    }

    /**
     * VOD chat replay never connects to a chat, so the key above is not built and the VOD's
     * channel emotes were never loaded. Its lines still go through the live assembler, so loading
     * the channel from each replayed comment is all that is missing. Clips with chat use the same
     * builder. While a live chat is still busy the replay waits its turn, and its lines are marked
     * to be filled in once its channel is in.
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
                inReplay.set(true)
                replayWaiting.set(false)
                runCatching {
                    val id = XposedHelpers.getObjectField(param.args[0], Names.CHOMMENT_CHANNEL_FIELD) as? String
                    if (id.isNullOrBlank() || !id.all { it.isDigit() }) return
                    val now = android.os.SystemClock.uptimeMillis()
                    replayAt = now
                    if (id == EmoteRepo.currentChannel()) return
                    if (now - liveAt <= QUIET_MS) { replayWaiting.set(true); return }
                    if (id != loggedId) { loggedId = id; log("CH replay channel: id=$id") }
                    EmoteRepo.loadChannelAsync(id, null)
                }.onFailure { log("CH replay read error: $it") }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                inReplay.set(false)
                replayWaiting.set(false)
            }
        })
        log("CH replay anchor installed on ${line.name}")
    }
}
