package tv.purple.xp

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * "Show refresh button": a reload button in the live player controls, for a stream that froze or
 * fell behind.
 *
 * Live video is played by Amazon IVS, whose player class keeps its real name. We remember the
 * last stream address Twitch loaded and, on a tap, load it again and press play: the same thing
 * Twitch does when it first opens the stream. Stream addresses carry a short-lived pass, so after
 * a while the button instead reopens the channel through Twitch's own link, which fetches a fresh one.
 */
object RefreshButton {

    const val KEY = "show_refresh_button_v2"
    private const val TAG = "ptv_refresh_button"
    private const val FRESH_MS = 10 * 60_000L
    private const val ASKED = 32

    @Volatile private var player: WeakReference<Any>? = null
    @Volatile private var lastLoad: Any? = null
    @Volatile private var lastUri: Uri? = null
    @Volatile private var loadedAt = 0L
    /** Channels Twitch asked a player for lately, so a media title can be told from an ad's. */
    private val asked = Collections.synchronizedSet(LinkedHashSet<String>())
    /** What Twitch's media session says is playing: the channel's name, in lower case. */
    @Volatile private var sessionName: String? = null
    @Volatile private var playing: String? = null
    @Volatile private var playingAt = 0L
    /**
     * Players holding a clip: their sound says nothing about the channel's language. The clip feed
     * keeps several, the next one loaded ahead of the one on screen.
     */
    private val clipPlayers: MutableSet<Any> = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap()))
    @Volatile private var wasClip = false
    private var cls: Class<*>? = null

    fun install(lp: LoadPackageParam) {
        val c = runCatching { lp.classLoader.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull() ?: return
        cls = c
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                // The two-streams screen loads its own players. They must never become the stream
                // Twitch shows, or liveLogin() would name the second channel even after it closes.
                if (OwnPlayers.isMine(p.thisObject)) return
                val a0 = p.args.getOrNull(0) ?: return
                val uri = when (a0) {
                    is Uri -> a0
                    else -> runCatching { a0.javaClass.getMethod("getUri").invoke(a0) as? Uri }.getOrNull()
                } ?: return
                if (uri.host.isNullOrEmpty()) return
                loginOf(uri)?.let { note(it.lowercase(Locale.ROOT)) }
                // Side players (Picture-by-Picture ads, the vertical view, the feed) preload the "v2"
                // address. Skip them, so the button keeps reloading the player on screen.
                if (p.method.name == "preload" && uri.path?.contains("/api/v2/channel/hls/") == true) return
                player = WeakReference(p.thisObject)
                lastLoad = a0
                // The clock is the age of the address. Our own reload and Twitch's reloads of the same
                // address come through here too, so only a new address restarts it.
                if (uri != lastUri) loadedAt = SystemClock.uptimeMillis()
                lastUri = uri
                if (p.method.name == "load") endClip(p.thisObject)
            }
        }
        val n = XposedBridge.hookAllMethods(c, "load", hook).size + XposedBridge.hookAllMethods(c, "preload", hook).size
        // A clip goes straight to the native player, past load, so the address above still names
        // the channel it was opened from.
        val clips = XposedBridge.hookAllMethods(c, "loadClipManifest", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (OwnPlayers.isMine(p.thisObject)) return
                clipPlayers.add(p.thisObject)
                playingAt = SystemClock.uptimeMillis()
                log("RB clip loaded")
            }
        }).size
        XposedBridge.hookAllMethods(c, "loadSource", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) = endClip(p.thisObject)
        })
        // A preload names a channel that may never play, and a preloaded stream can start without
        // its address passing through here. The media session is what Twitch itself says is playing.
        val titles = XposedBridge.hookAllMethods(MediaSession::class.java, "setMetadata", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val title = (p.args.getOrNull(0) as? MediaMetadata)?.description?.title?.toString()
                sessionName = title?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
                playingLogin()
            }
        }).size
        // A released player must never be called again: its native side is gone, and a crash
        // there can not be caught. Its references go the moment Twitch releases it.
        val r = XposedBridge.hookAllMethods(c, "release", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val me = p.thisObject
                if (player?.get() === me) { player = null; lastLoad = null }
                endClip(me)
                PlayerStats.forget(me)
            }
        }).size
        log("RB hooks: $n, titles: $titles, clips: $clips, release: $r")
    }

    private fun loginOf(uri: Uri?): String? =
        uri?.path?.let { HLS.find(it)?.groupValues?.get(1) }?.takeIf { it.isNotBlank() }

    private fun note(login: String) = synchronized(asked) {
        asked.remove(login)
        asked.add(login)
        if (asked.size > ASKED) asked.remove(asked.first())
    }

    /**
     * The live channel being heard, in lower case, for what depends on that sound. [liveLogin] also
     * follows preloads, so it can name a channel that is not playing. The media session's title
     * wins when it is a channel Twitch asked for; an ad's title, or a display name in another
     * script, is not one, and the last address stands in.
     */
    fun playingLogin(): String? {
        val named = sessionName?.takeIf { it in asked }
        val login = named ?: liveLogin()?.lowercase(Locale.ROOT)
        if (login != playing) {
            playing = login
            playingAt = SystemClock.uptimeMillis()
            log("RB playing: ${login ?: "not live"}${if (named == null) " (last address)" else ""}")
        }
        return login
    }

    fun isClip(p: Any): Boolean = p in clipPlayers

    /** A live stream is what the player shows. After a clip opens, [liveLogin] still names the channel before it. */
    fun liveShown(): Boolean = liveLogin() != null && Stt.mainPlayer()?.let { isClip(it) } != true

    private fun endClip(p: Any) {
        if (!clipPlayers.remove(p)) return
        playingAt = SystemClock.uptimeMillis()
    }

    /**
     * True while the sound being heard is a clip. A change either way counts as a channel change,
     * so a sentence that began on one side of it is not credited to the other.
     */
    fun clipHeard(): Boolean {
        val now = Stt.mainPlayer()?.let { it in clipPlayers } == true
        if (now != wasClip) {
            wasClip = now
            playingAt = SystemClock.uptimeMillis()
            log("RB clip: $now")
        }
        return now
    }

    /** When the playing channel last changed (uptime ms), 0 before the first. */
    fun playingSince(): Long = playingAt

    /** The player Twitch is using now, for the stats box. */
    fun currentPlayer(): Any? = player?.get()

    private val HLS = Regex("/api/(?:v2/)?channel/hls/([^/]+)\\.m3u8")

    /** The live channel login, from the stream address (".../api/channel/hls/<login>.m3u8", or ".../api/v2/..."). */
    fun liveLogin(): String? {
        val path = lastUri?.path ?: return null
        return HLS.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * Twitch's last live stream address (".../api/channel/hls/<login>.m3u8?..."), so another
     * channel can be asked for with the same options Twitch uses. It carries Twitch's short-lived
     * pass, so it is never logged.
     */
    fun lastLiveUri(): String? {
        val u = lastUri ?: return null
        return if (u.path?.contains("/api/channel/hls/") == true) u.toString() else null
    }

    private fun refresh(v: View) {
        val pl = player?.get()
        val load = lastLoad
        val c = cls
        val fresh = SystemClock.uptimeMillis() - loadedAt < FRESH_MS
        if (pl != null && load != null && c != null && fresh) {
            val ok = runCatching {
                val m = c.methods.first { it.name == "load" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(load) }
                m.invoke(pl, load)
                c.getMethod("play").invoke(pl)
            }.isSuccess
            if (ok) { toast(v.context, "Reloading the stream"); log("RB reloaded stream"); return }
        }
        val login = liveLogin()
        if (login != null) {
            runCatching {
                v.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("twitch://stream/$login"))
                    .setPackage(v.context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                toast(v.context, "Reopening the stream")
                log("RB reopened $login")
            }.onFailure { log("RB reopen failed: $it") }
        } else toast(v.context, "Nothing to reload yet")
    }

    private val ROW = arrayOf("create_clip_text_button", "create_clip_button_compose_view", "switch_to_vertical_view",
        "cast_button", "media_route_button", "share_button", "settings_button", "audio_and_subtitles")
    private var added = false

    /** From each ViewHider pass: add, place or remove the button. Live streams only. */
    fun attach(root: View) {
        val on = Settings.get(KEY, true)
        if (!on && !added) return
        val ctx = root.context
        fun ids(n: String) = Res.id(ctx, n)
        val settingsId = ids("settings_button"); if (settingsId == 0) return
        val bars = ArrayList<ViewGroup>()
        collectBars(root, settingsId, bars)
        val live = liveLogin() != null
        for (bar in bars) {
            var btn = bar.findViewWithTag<ImageView>(TAG)
            if (!on) { if (btn != null) bar.removeView(btn); continue }
            val row = ROW.mapNotNull { n -> ids(n).takeIf { it != 0 }?.let { bar.findViewById<View>(it) } }
                .filter { it.parent === bar && it.visibility == View.VISIBLE && it.width > 0 }.toMutableList()
            for (t in arrayOf("ptv_sleep_timer", "ptv_filters_button"))
                bar.findViewWithTag<View>(t)?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { row.add(it) }
            val ref = sizeRef(bar, row)
            if (ref == null || !live) { btn?.visibility = View.GONE; continue }
            if (btn == null) {
                btn = ImageView(bar.context).apply {
                    tag = TAG
                    setImageDrawable(RefreshIcon())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
                    contentDescription = I18n.t("Reload stream")
                    setOnClickListener { refresh(it) }
                }
                bar.addView(btn, ViewGroup.LayoutParams(ref.height, ref.height))
                added = true
            }
            matchSize(btn, ref)
            val x = row.minOf { it.x } - ref.height
            if (btn.x != x) btn.x = x
            if (btn.y != ref.y) btn.y = ref.y
            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
        }
    }

    private fun collectBars(v: View, settingsId: Int, out: MutableList<ViewGroup>) {
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out && Res.isPlayerBar(it)) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectBars(v.getChildAt(i), settingsId, out)
    }

    private class RefreshIcon : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawArc(RectF(s * 0.22f, s * 0.22f, s * 0.78f, s * 0.78f), -60f, 290f, false, stroke)
            val p = Path().apply {
                moveTo(s * 0.62f, s * 0.12f); lineTo(s * 0.8f, s * 0.26f); lineTo(s * 0.58f, s * 0.36f); close()
            }
            c.drawPath(p, fill)
        }
    }
}
