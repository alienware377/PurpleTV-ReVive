package tv.purple.xp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Mod logs: in channels where you are a moderator (or your own), a short line in chat for each
 * moderator action, with who did it: timeouts (length and reason), bans (reason), unbans, removed
 * timeouts and deleted messages.
 *
 * Twitch already receives these (its moderator-actions feed, whose model keeps its readable name)
 * but keeps only the target, and its app never says who acted. Each action is read as Twitch
 * opens it and shown through Twitch's own chat notice line, the same channel the raid card uses.
 * Non-moderators never receive the feed, so they never see anything.
 */
object ModLogs {

    const val KEY = "mod_logs_notices"

    private const val ACTION = "tv.twitch.android.shared.moderation.actions.pubsub.ModerationActionPubSubEvent\$ModeratorAction"
    private const val STRINGS = "tv.twitch.android.core.strings.e"   // StringResource factory; c(String) = plain text
    private val NOTICE get() = TwitchVersion.pick("f95", "vb5")         // RegularChannelNotice(noticeId, message)
    private val NOTICE_EVENT get() = TwitchVersion.pick("de6", "ih6")   // ChannelNoticeEvent(channelId, notice)
    private val CHAT_NOTICE get() = TwitchVersion.pick("aq5", "kt5")    // ChatNotice(chatNoticeEvent)

    private val main = Handler(Looper.getMainLooper())
    private var cl: ClassLoader? = null

    /** Each action reaches several listeners in the app; only the first is shown. */
    private val seen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?) = size > 64
    }

    fun install(lp: LoadPackageParam) {
        cl = lp.classLoader
        runCatching {
            XposedHelpers.findAndHookMethod(ACTION, lp.classLoader, "getData", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    runCatching { onAction(p.result ?: return) }.onFailure { log("ML read: $it") }
                }
            })
            log("ML mod logs installed")
        }.onFailure { log("ML mod logs failed: $it") }
    }

    private fun onAction(d: Any) {
        if (!Settings.get(KEY, true)) return
        fun s(m: String) = runCatching { XposedHelpers.callMethod(d, m) as? String }.getOrNull()
        val action = s("getAction") ?: return
        val args = (runCatching { XposedHelpers.callMethod(d, "getArgs") }.getOrNull() as? List<*>)
            ?.map { it?.toString().orEmpty() }.orEmpty()
        val mod = s("getModeratorName").orEmpty().ifEmpty { "A moderator" }
        val who = s("getTargetUserLogin")?.takeIf { it.isNotEmpty() } ?: args.getOrNull(0).orEmpty()
        val key = listOf(action, s("getCreatedAt"), s("getModeratorId"), s("getTargetUserId"), args.joinToString("\u0001"))
            .joinToString("|")
        synchronized(seen) { if (seen.put(key, SystemClock.uptimeMillis()) != null) return }
        fun why(r: String?) = r?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        val text = when (action) {
            "timeout" -> "$mod timed out $who for ${length(args.getOrNull(1))}${why(args.getOrNull(2))}"
            "ban" -> "$mod banned $who${why(args.getOrNull(1))}"
            "unban" -> "$mod unbanned $who"
            "untimeout" -> "$mod removed the timeout on $who"
            "delete" -> "$mod deleted a message from $who" +
                (args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ": \"$it\"" } ?: "")
            else -> null
        } ?: return
        log("ML $action by $mod on $who")
        main.post { push("⚖ $text") }
    }

    /** "600" -> "10 minutes", and so on. */
    private fun length(secs: String?): String {
        val n = secs?.toLongOrNull() ?: return "a while"
        fun unit(v: Long, u: String) = "$v $u" + if (v == 1L) "" else "s"
        return when {
            n % 86400 == 0L && n >= 86400 -> unit(n / 86400, "day")
            n % 3600 == 0L && n >= 3600 -> unit(n / 3600, "hour")
            n % 60 == 0L && n >= 60 -> unit(n / 60, "minute")
            else -> unit(n, "second")
        }
    }

    private fun push(text: String) = runCatching {
        val l = cl ?: return@runCatching
        val subj = RaidCard.noticeSubject() ?: run { log("ML no chat notice channel yet"); return@runCatching }
        val res = XposedHelpers.callStaticMethod(XposedHelpers.findClass(STRINGS, l), "c", text)
        val notice = XposedHelpers.newInstance(XposedHelpers.findClass(NOTICE, l), "ptv_modlog", res)
        val channel = TwitchVersion.channelIdArg(l, RaidCard.currentChannel().orEmpty()) ?: run { log("ML no channel yet"); return@runCatching }
        val event = XposedHelpers.newInstance(XposedHelpers.findClass(NOTICE_EVENT, l), channel, notice)
        XposedHelpers.callMethod(subj, TwitchVersion.onNext, XposedHelpers.newInstance(XposedHelpers.findClass(CHAT_NOTICE, l), event))
    }.onFailure { log("ML show: $it") }

    /** Hidden test: show a sample line in the open chat. */
    fun test(text: String) { main.post { push("⚖ $text") } }
}
