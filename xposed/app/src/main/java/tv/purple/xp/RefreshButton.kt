package tv.purple.xp

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

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

    @Volatile private var player: WeakReference<Any>? = null
    @Volatile private var lastLoad: Any? = null
    @Volatile private var lastUri: Uri? = null
    @Volatile private var loadedAt = 0L
    private var cls: Class<*>? = null

    fun install(lp: LoadPackageParam) {
        val c = runCatching { lp.classLoader.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull() ?: return
        cls = c
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val a0 = p.args.getOrNull(0) ?: return
                val uri = when (a0) {
                    is Uri -> a0
                    else -> runCatching { a0.javaClass.getMethod("getUri").invoke(a0) as? Uri }.getOrNull()
                } ?: return
                if (uri.host.isNullOrEmpty()) return
                // Side players (Picture-by-Picture ads, the vertical view, the feed) preload the "v2"
                // address. Skip them, so the button keeps reloading the player on screen.
                if (p.method.name == "preload" && uri.path?.contains("/api/v2/channel/hls/") == true) return
                player = WeakReference(p.thisObject)
                lastLoad = a0
                // The clock is the age of the address. Our own reload and Twitch's reloads of the same
                // address come through here too, so only a new address restarts it.
                if (uri != lastUri) loadedAt = SystemClock.uptimeMillis()
                lastUri = uri
            }
        }
        val n = XposedBridge.hookAllMethods(c, "load", hook).size + XposedBridge.hookAllMethods(c, "preload", hook).size
        log("RB hooks: $n")
    }

    /** The player Twitch is using now, for the stats box. */
    fun currentPlayer(): Any? = player?.get()

    private val HLS = Regex("/api/(?:v2/)?channel/hls/([^/]+)\\.m3u8")

    /** The live channel login, from the stream address (".../api/channel/hls/<login>.m3u8", or ".../api/v2/..."). */
    private fun liveLogin(): String? {
        val path = lastUri?.path ?: return null
        return HLS.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
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
            val ref = row.firstOrNull { it.height > 0 }
            if (ref == null || !live) { btn?.visibility = View.GONE; continue }
            if (btn == null) {
                btn = ImageView(bar.context).apply {
                    tag = TAG
                    setImageDrawable(RefreshIcon())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
                    contentDescription = "Reload stream"
                    setOnClickListener { refresh(it) }
                }
                bar.addView(btn, ViewGroup.LayoutParams(ref.height, ref.height))
                added = true
            }
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
