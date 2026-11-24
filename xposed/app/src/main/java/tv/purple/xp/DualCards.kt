package tv.purple.xp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.ConcurrentHashMap

/**
 * Twitch's own cards and sheets, opened for a two-streams pane's channel: viewer card, emote card,
 * GIF card, chat settings and the main settings screen. They are the same screens the normal chat
 * opens, so anything that changes them carries over. Each call answers false when it could not
 * open, and the pane falls back on what it did before.
 */
internal object DualCards {

    private class Names(
        val activity: String, val fm: String, val route: String, val navigator: String,
        val viewer: String, val emote: String, val chat: String, val gifHost: String, val gifMake: String,
    )

    private val V317 = Names("androidx.fragment.app.r", "androidx.fragment.app.y", "bhr", "B", "mgr", "ker", "ldr", "qpr", "G")
    private val V31 = Names("androidx.fragment.app.n", "androidx.fragment.app.u", "o3r", "O", "a3r", "y0r", "zzq", "qgv", "s0")

    private fun names() = if (TwitchVersion.atLeast317()) V317 else V31

    private const val FLAG_CHAT_ACTIONS = 0x400
    private const val SETTINGS = "tv.twitch.android.settings.SettingsActivity"
    private const val CODE_SETTINGS = 30

    private val main = Handler(Looper.getMainLooper())
    private val mentions = ConcurrentHashMap<String, (String) -> Unit>()

    private const val FRAGMENT = "androidx.fragment.app.m"
    private const val RECORD = "androidx.fragment.app.a"
    private const val PICKER = "tv.twitch.android.shared.emotes.emotepicker.EmotePickerFragment"
    private const val REWARDS = "tv.twitch.android.feature.theatre.vertical.rewards.CommunityPointsRewardsFragment"
    private const val INFO = "tv.twitch.android.shared.emotes.emotepicker.compose.SimpleChannelInfo"
    private const val STATUS = "tv.twitch.android.models.chat.ChatUserStatus"
    private const val PICK_KEY = "EmotePickerFragment_result"
    private const val SHEET_TAG = "ptv_dual_sheet"

    /** The picker or rewards panel open inside the two-streams screen, and who gets its picks. */
    @Volatile private var sheet: Any? = null
    @Volatile private var onPick: ((String, String?) -> Unit)? = null

    /** The viewer card answers through a fragment result under the key it was opened with. */
    fun install(lp: LoadPackageParam) {
        val fm = lp.classLoader.loadClass(names().fm)
        val set = fm.getDeclaredMethod("o0", Bundle::class.java, String::class.java)
        XposedBridge.hookMethod(set, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val key = p.args[1] as? String ?: return
                if (key == PICK_KEY) {
                    val pick = onPick ?: return
                    p.result = null
                    val b = p.args[0] as? Bundle ?: return
                    val action = b.getString("action") ?: return
                    val code = b.getString("emote_code")
                    main.post { pick(action, code) }
                    return
                }
                val to = mentions[key] ?: return
                p.result = null
                val b = p.args[0] as? Bundle ?: return
                runCatching {
                    b.classLoader = lp.classLoader
                    @Suppress("DEPRECATION") val r = b.getParcelable<Parcelable>(key) ?: return
                    if (r.javaClass.simpleName != "MentionInChat\$AppendUsernameAndFocusKeyboard" &&
                        !r.javaClass.name.endsWith("MentionInChat\$AppendUsernameAndFocusKeyboard")) return
                    val who = r.javaClass.getMethod("getUsername").invoke(r) as? String ?: return
                    main.post { to(who) }
                }.onFailure { log("DV card answer failed: $it") }
            }
        })
        // Twitch's rewards panel expects to sit inside its stream screen; here it is its own parent.
        XposedBridge.hookMethod(lp.classLoader.loadClass(FRAGMENT).getMethod("requireParentFragment"), object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) { if (p.thisObject === sheet) p.result = p.thisObject }
        })
        log("DV cards ready")
    }

    fun forget(key: String) { mentions.remove(key) }

    private fun open(act: Activity, route: Any): Boolean {
        val n = names()
        val cl = act.classLoader
        val nav = act.javaClass.getMethod(n.navigator).invoke(act) ?: return false
        nav.javaClass.getMethod("d", cl.loadClass(n.activity), cl.loadClass(n.route)).invoke(nav, act, route)
        return true
    }

    private inline fun attempt(what: String, block: () -> Boolean): Boolean =
        runCatching(block).onFailure { log("DV $what failed: ${it.cause ?: it}") }.getOrDefault(false)

    fun viewer(act: Activity, key: String, channelId: String, userId: String, login: String, ownId: String,
               isMod: Boolean, isOwner: Boolean, messageId: String, message: String, onMention: (String) -> Unit): Boolean =
        attempt("viewer card") {
            val s = String::class.java
            val z = Boolean::class.javaPrimitiveType
            val c = act.classLoader.loadClass(names().viewer).getConstructor(
                s, s, s, s, z, z, s, s, s, s, Int::class.javaPrimitiveType)
            mentions[key] = onMention
            open(act, c.newInstance(channelId, userId, login, ownId, isMod, isOwner, messageId, "", message, key, FLAG_CHAT_ACTIONS))
        }

    fun emote(act: Activity, emoteId: String, channelId: String, channelName: String, messageId: String?, userId: String?): Boolean =
        attempt("emote card") {
            val cl = act.classLoader
            val s = String::class.java
            val meta = cl.loadClass("tv.twitch.android.models.emotecard.EmoteCardTrackingMetadata")
                .getConstructor(s, s, s, s, s).newInstance(channelId, channelName, "chat", messageId.orEmpty(), userId.orEmpty())
            val modes = cl.loadClass("tv.twitch.android.shared.one.chat.pub.ChatModeMetadata")
            val mode = modes.enumConstants!!.first { (it as Enum<*>).name == "OneChatCompact" }
            val c = cl.loadClass(names().emote).getConstructor(s, meta.javaClass, s, modes)
            open(act, c.newInstance(emoteId, meta, "chat", mode))
        }

    fun gif(act: Activity, id: String, url: String, title: String, messageId: String?, userId: String?,
            login: String?, display: String?, channelId: String?): Boolean =
        attempt("GIF card") {
            val n = names()
            val s = String::class.java
            val f = act.classLoader.loadClass(n.gifHost).getMethod(n.gifMake, s, s, s, s, s, s, s, s)
                .invoke(null, id, url, title, messageId.orEmpty(), userId.orEmpty(), login.orEmpty(), display.orEmpty(), channelId.orEmpty())!!
            val fmCls = act.classLoader.loadClass(n.fm)
            f.javaClass.getMethod("show", fmCls, s).invoke(f, fragmentManager(act), "gif_card")
            true
        }

    fun chatSettings(act: Activity, channelId: String, isMod: Boolean): Boolean =
        attempt("chat settings") {
            val z = Boolean::class.javaPrimitiveType
            val c = act.classLoader.loadClass(names().chat)
                .getConstructor(Int::class.javaPrimitiveType, String::class.java, z, z, z)
            open(act, c.newInstance(8, channelId, isMod, true, false))
        }

    private fun fragmentManager(act: Activity): Any {
        val c = act.classLoader.loadClass(names().fm)
        // 31.1.2's getter is declared with a subclass of the manager as its type.
        return act.javaClass.methods.first { it.parameterCount == 0 && c.isAssignableFrom(it.returnType) }.invoke(act)!!
    }

    private fun record(act: Activity): Any {
        val fm = fragmentManager(act)
        return act.classLoader.loadClass(RECORD).getConstructor(act.classLoader.loadClass(names().fm)).newInstance(fm)
    }

    private fun channelInfo(cl: ClassLoader, id: String, login: String, display: String): android.os.Parcelable {
        val first: Any = if (TwitchVersion.atLeast317()) TwitchVersion.channelIdArg(cl, id) ?: error("bad channel id") else id.toInt()
        val c = cl.loadClass(INFO).declaredConstructors.first { it.parameterTypes.size == 7 }.apply { isAccessible = true }
        return c.newInstance(first, login, display, null, false, false, false) as android.os.Parcelable
    }

    private fun show(act: Activity, containerId: Int, f: Any, args: Bundle) {
        val cl = act.classLoader
        args.classLoader = cl
        f.javaClass.getMethod("setArguments", Bundle::class.java).invoke(f, args)
        sheet = f
        val r = record(act)
        val base = cl.loadClass(RECORD)
        base.getMethod("h", Int::class.javaPrimitiveType, cl.loadClass(FRAGMENT), String::class.java, Int::class.javaPrimitiveType)
            .invoke(r, containerId, f, SHEET_TAG, 1)
        base.getMethod("e").invoke(r)
    }

    /** Twitch's emote picker inside [containerId]. Picks go to [pick] as (action, emote code). */
    fun openEmotes(act: Activity, containerId: Int, channelId: String, login: String, display: String,
                   pick: (String, String?) -> Unit): Boolean = attempt("emote picker") {
        closeSheet(act)
        val cl = act.classLoader
        val f = cl.loadClass(PICKER).getConstructor().newInstance()
        onPick = pick
        show(act, containerId, f, Bundle().apply { putParcelable("EmotePickerFragment_args", channelInfo(cl, channelId, login, display)) })
        true
    }

    /** Twitch's channel points rewards inside [containerId]. */
    fun openPoints(act: Activity, containerId: Int, channelId: String, login: String, display: String): Boolean =
        attempt("channel points") {
            closeSheet(act)
            val cl = act.classLoader
            val f = cl.loadClass(REWARDS).getConstructor().newInstance()
            val status = cl.loadClass(STATUS).getConstructor(String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .newInstance(display, false, false)
            show(act, containerId, f, Bundle().apply {
                putParcelable("CommunityPointsRewardsChannel", channelInfo(cl, channelId, login, display))
                putString("ChatViewScreenName", "theater_mode")
                putString("ChatViewSubScreen", "chat")
                when (status) {
                    is android.os.Parcelable -> putParcelable("CommunityPointsRewardsUserStatus", status)
                    is java.io.Serializable -> putSerializable("CommunityPointsRewardsUserStatus", status)
                }
            })
            true
        }

    fun closeSheet(act: Activity?) {
        onPick = null
        val f = sheet ?: return
        sheet = null
        if (act == null) return
        runCatching {
            val cl = act.classLoader
            val r = record(act)
            val base = cl.loadClass(RECORD)
            base.getMethod("i", cl.loadClass(FRAGMENT)).invoke(r, f)
            base.getMethod("e").invoke(r)
        }.onFailure { log("DV sheet close failed: ${it.cause ?: it}") }
    }

    /** Twitch's main settings screen, the one behind Profile and the cog. */
    fun settings(act: Activity): Boolean = attempt("settings") {
        act.startActivityForResult(Intent(act, act.classLoader.loadClass(SETTINGS)), CODE_SETTINGS)
        true
    }
}
