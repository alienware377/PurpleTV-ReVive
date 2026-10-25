package tv.purple.xp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The raid card ("X is raiding with a party of N raiders") for viewers who arrive WITH the raid.
 *
 * Twitch only shows that card from the live chat event sent the moment a raid lands. Raiders are
 * moved over a few seconds later on purpose, so their chat joins after the event has gone by and
 * chat history never carries it: the card is lost. Here the raid is remembered from Twitch's own
 * raid model (RaidEventInfo, readable name) while still in the raiding channel, and when chat
 * then connects to the raid's target, the same card is handed to Twitch's chat through the
 * channel it already uses for raid cards, so it looks exactly like the real one. If the real
 * card did arrive, nothing is added.
 */
object RaidCard {

    const val KEY = "raid_card_for_raiders"

    // Pinned for Twitch 31.1.2 (R8 names). All are only reached through reflection, so a missing
    // class just switches this off.
    private const val RAID_INFO = "tv.twitch.android.shared.raids.pubsub.RaidEventInfo"
    private const val NOTICES = "znc"      // chat notice source; field b = PublishSubject of ChatNotice
    private const val CHAT_NOTICE = "aq5"  // ChatNotice(chatNoticeEvent)
    private const val RAID_EVENT = "me6"   // RaidNoticeEvent(channelId, notice)
    private const val RAID_NOTICE = "sg6"  // ChatRaidNotice(systemMessage, profileImageUrl, raidingUserInfo, viewerCount)
    private const val USER_INFO = "j960"   // UserInfo(userId, userName, displayName, unused)

    private class Raid(val source: String, val target: String, val count: Int, val at: Long)

    private val main = Handler(Looper.getMainLooper())
    private var cl: ClassLoader? = null
    @Volatile private var lastRaid: Raid? = null
    @Volatile private var current: String? = null
    @Volatile private var previous: String? = null
    private var subject: WeakReference<Any>? = null
    /** When the last real raid card was built by Twitch, so ours is skipped if it came through. */
    @Volatile private var realAt = 0L
    @Volatile private var building = false
    @Volatile private var doneFor: String? = null

    fun install(lp: LoadPackageParam) {
        cl = lp.classLoader
        val info = runCatching { XposedHelpers.findClass(RAID_INFO, lp.classLoader) }.getOrNull()
        if (info == null) { log("RC $RAID_INFO not found, raid card fix off"); return }
        // The raid model is filled in from JSON without its constructor running, so it is read
        // whenever Twitch asks it for the target channel: when the raid banner is drawn, and when
        // the raid goes and viewers are moved.
        XposedHelpers.findAndHookMethod(info, "getTargetChannelId", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                runCatching {
                    val o = p.thisObject
                    val dst = p.result?.let(::tuid) ?: return
                    val src = XposedHelpers.callMethod(o, "getSourceChannelId")?.let(::tuid) ?: return
                    val n = XposedHelpers.callMethod(o, "getNumUsersInRaid") as? Int ?: 0
                    val prev = lastRaid
                    if (prev?.target == dst && prev.source == src && prev.count == n) return
                    lastRaid = Raid(src, dst, n, if (prev?.target == dst && prev.source == src) prev.at else SystemClock.uptimeMillis())
                    log("RC raid seen: $src -> $dst with $n viewers")
                }.onFailure { log("RC raid read: $it") }
            }
        })
        runCatching {
            XposedBridge.hookAllConstructors(XposedHelpers.findClass(NOTICES, lp.classLoader), object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    subject = WeakReference(XposedHelpers.getObjectField(p.thisObject, "b"))
                }
            })
            XposedBridge.hookAllConstructors(XposedHelpers.findClass(RAID_NOTICE, lp.classLoader), object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    // Twitch's raid message gives the raider's picture as ".../profile_image-%s.png"
                    // and the app never fills in the size, so the picture fails and the generic
                    // avatar shows. Fill it in.
                    val url = p.args.getOrNull(1) as? String ?: return
                    if (url.contains("%s")) p.args[1] = url.replace("%s", "70x70")
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (!building) {
                        realAt = SystemClock.uptimeMillis()
                        log("RC Twitch built a raid card itself (picture ${p.args.getOrNull(1)})")
                    }
                }
            })
        }.onFailure { log("RC chat notice hooks failed: $it") }
        log("RC raid card fix installed")
    }

    /** Twitch's Tuid (readable name) wraps the numeric id. */
    private fun tuid(t: Any): String? =
        runCatching { XposedHelpers.callMethod(t, "toInt")?.toString() }.getOrNull()?.takeIf { it != "0" }
            ?: t.toString().filter(Char::isDigit).takeIf { it.isNotEmpty() }

    /** From the channel anchor: chat is connecting to channel [id]. */
    fun onChannel(id: String) {
        if (id == current) return
        previous = current; current = id
        if (!Settings.get(KEY, true)) return
        val r = lastRaid ?: return
        if (r.target != id || doneFor == r.target + "|" + r.at) return
        // Only when coming straight from the raiding channel, shortly after the raid.
        if (r.source != previous || SystemClock.uptimeMillis() - r.at > 5 * 60_000L) return
        doneFor = r.target + "|" + r.at
        log("RC arrived with the raid from ${r.source}; adding the raid card if Twitch's never shows")
        main.postDelayed({ inject(r, attempt = 1) }, 3500)
    }

    /** Hidden test: pretend a raid from [source] into [target] just happened. */
    fun simulate(source: String, target: String, count: Int) {
        lastRaid = Raid(source, target, count, SystemClock.uptimeMillis())
        doneFor = null
        log("RC simulated raid: $source -> $target with $count viewers")
    }

    private fun inject(r: Raid, attempt: Int) {
        if (realAt > r.at) { log("RC Twitch showed the real raid card, nothing added"); return }
        if (current != r.target) return
        Thread({
            // Who raided: login, display name and picture, from Twitch's own API.
            val user = runCatching {
                val token = Settings.ctx()?.let { EmoteRepo.authToken(it) }.orEmpty()
                val body = JSONObject().put("query",
                    "query{user(id:\"${r.source}\"){login displayName profileImageURL(width:70)}}").toString()
                JSONObject(EmoteRepo.gqlPost(token, body) ?: "{}").optJSONObject("data")?.optJSONObject("user")
            }.getOrNull()
            val login = user?.optString("login").orEmpty()
            val display = user?.optString("displayName").orEmpty().ifEmpty { login }
            val logo = user?.optString("profileImageURL").orEmpty()
            if (display.isEmpty()) { log("RC could not look up the raider ${r.source}"); return@Thread }
            main.post {
                if (realAt > r.at) { log("RC Twitch showed the real raid card, nothing added"); return@post }
                if (push(r, login, display, logo)) {
                    main.postDelayed({
                        if (shown()) log("RC raid card from $display shown")
                        else if (attempt < 3) { log("RC raid card not in chat yet, trying again"); inject(r, attempt + 1) }
                        else log("RC raid card did not appear")
                    }, 2000)
                }
            }
        }, "ptv-raid-card").apply { isDaemon = true }.start()
    }

    private fun push(r: Raid, login: String, display: String, logo: String): Boolean = runCatching {
        val l = cl ?: return false
        val subj = subject?.get() ?: run { log("RC no chat notice channel captured"); return false }
        building = true
        val user = XposedHelpers.newInstance(XposedHelpers.findClass(USER_INFO, l), r.source, login, display, 0)
        val system = if (r.count == 1) "1 raider from $display has joined!" else "${r.count} raiders from $display have joined!"
        val notice = XposedHelpers.newInstance(XposedHelpers.findClass(RAID_NOTICE, l), system, logo, user, r.count)
        val event = XposedHelpers.newInstance(XposedHelpers.findClass(RAID_EVENT, l), r.target, notice)
        val wrapped = XposedHelpers.newInstance(XposedHelpers.findClass(CHAT_NOTICE, l), event)
        building = false
        XposedHelpers.callMethod(subj, "c", wrapped)   // PublishSubject.onNext
        true
    }.getOrElse { building = false; log("RC raid card failed: $it"); false }

    /** Whether a raid card is on screen in chat. */
    private fun shown(): Boolean {
        val root = ViewHider.currentActivity()?.window?.decorView as? ViewGroup ?: return false
        val id = Res.id(root.context, "raid_message_container")
        if (id == 0) return false
        var hit = false
        fun walk(v: View) {
            if (hit) return
            if (v.id == id && v.isShown) { hit = true; return }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return hit
    }
}
