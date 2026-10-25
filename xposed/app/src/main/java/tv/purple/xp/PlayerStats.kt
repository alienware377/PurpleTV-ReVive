package tv.purple.xp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Two player options built on Amazon IVS, the player Twitch uses, whose class keeps its real name:
 *
 *  - "Disable low latency": Twitch's live streams normally play as close to live as possible,
 *    which stutters on a weak connection. This turns IVS's low-latency mode off, trading a few
 *    seconds of delay for a steadier picture.
 *  - "Show stats button": a button in the player controls that lays a small box over the video
 *    with what the player is doing: quality, bitrate, frame rate, delay behind live, buffer,
 *    network estimate and dropped frames. Updated every second while it is open.
 */
object PlayerStats {

    const val KEY_STATS = "show_stats_button"
    const val KEY_NO_LOW_LATENCY = "disable_fast_bread"
    private const val TAG = "ptv_stats_button"
    private const val OVERLAY_TAG = "ptv_stats_overlay"

    private val ROW = arrayOf("create_clip_text_button", "create_clip_button_compose_view", "switch_to_vertical_view",
        "cast_button", "media_route_button", "share_button", "settings_button", "audio_and_subtitles")
    private val OURS = arrayOf("ptv_sleep_timer", "ptv_filters_button", "ptv_refresh_button")

    private val main = Handler(Looper.getMainLooper())
    private var overlay: WeakReference<TextView>? = null
    private var added = false
    @Volatile private var loggedLowLatency = false

    fun install(lp: LoadPackageParam) {
        val c = runCatching { lp.classLoader.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull() ?: return
        XposedBridge.hookAllMethods(c, "setLiveLowLatencyEnabled", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (!Settings.get(KEY_NO_LOW_LATENCY, false) || p.args.getOrNull(0) != true) return
                p.args[0] = false
                if (!loggedLowLatency) { loggedLowLatency = true; log("PS low latency kept off") }
            }
        })
        // Also after each load, in case Twitch never asks for it explicitly.
        XposedBridge.hookAllMethods(c, "load", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (!Settings.get(KEY_NO_LOW_LATENCY, false)) return
                runCatching { p.thisObject.javaClass.getMethod("setLiveLowLatencyEnabled", Boolean::class.javaPrimitiveType).invoke(p.thisObject, false) }
            }
        })
        // The player drawing to the screen is the one handed a surface; Twitch also keeps idle
        // players around (preloading, the mini player), whose numbers are all zero.
        XposedBridge.hookAllMethods(c, "setSurface", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (p.args.getOrNull(0) != null) shown = WeakReference(p.thisObject)
            }
        })
        log("PS player options installed")
    }

    @Volatile private var shown: WeakReference<Any>? = null

    // ---- the stats button and box ----

    /** From each layout pass: add, place or remove the button. */
    fun attach(root: View) {
        val on = Settings.get(KEY_STATS, false)
        if (!on && !added) return
        val ctx = root.context
        val settingsId = Res.id(ctx, "settings_button"); if (settingsId == 0) return
        val bars = ArrayList<ViewGroup>()
        collectBars(root, settingsId, bars)
        for (bar in bars) {
            var btn = bar.findViewWithTag<ImageView>(TAG)
            if (!on) { if (btn != null) bar.removeView(btn); hideOverlay(); continue }
            val row = ROW.mapNotNull { n -> Res.id(ctx, n).takeIf { it != 0 }?.let { bar.findViewById<View>(it) } }
                .filter { it.parent === bar && it.visibility == View.VISIBLE && it.width > 0 }.toMutableList()
            for (t in OURS) bar.findViewWithTag<View>(t)?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { row.add(it) }
            val ref = row.firstOrNull { it.height > 0 }
            if (ref == null) { btn?.visibility = View.GONE; continue }
            if (btn == null) {
                btn = ImageView(bar.context).apply {
                    tag = TAG
                    setImageDrawable(StatsIcon())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
                    contentDescription = "Player stats"
                    setOnClickListener { toggle(it) }
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
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectBars(v.getChildAt(i), settingsId, out)
    }

    private fun toggle(v: View) {
        if (overlay?.get()?.isAttachedToWindow == true) { hideOverlay(); return }
        val content = v.rootView.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        val ctx = v.context
        fun dp(x: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, x, ctx.resources.displayMetrics).toInt()
        val tv = TextView(ctx).apply {
            tag = OVERLAY_TAG
            setTextColor(Color.WHITE); textSize = 11.5f; typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(0xCC0E0A14.toInt()); cornerRadius = dp(10f).toFloat() }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            elevation = dp(4f).toFloat()
            setOnClickListener { hideOverlay() }
        }
        content.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))
        overlay = WeakReference(tv)
        tick.run()
    }

    private fun hideOverlay() {
        main.removeCallbacks(tick)
        overlay?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val tv = overlay?.get() ?: return
            if (!tv.isAttachedToWindow) { overlay = null; return }
            runCatching { place(tv) }
            tv.text = runCatching { describe() }.getOrElse { "Stats unavailable: $it" }
            main.postDelayed(this, 1000)
        }
    }

    /** Over the top left corner of the video. */
    private fun place(tv: TextView) {
        val root = tv.rootView
        val id = Res.id(tv.context, "playback_view_container")
        val video = if (id != 0) root.findViewById<View>(id) else null
        if (video == null || !video.isShown) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        val v = IntArray(2); video.getLocationInWindow(v)
        val p = IntArray(2); (tv.parent as View).getLocationInWindow(p)
        val m = (8 * tv.resources.displayMetrics.density)
        tv.x = v[0] - p[0] + m; tv.y = v[1] - p[1] + m
    }

    private fun call(o: Any, name: String): Any? = runCatching { o.javaClass.getMethod(name).invoke(o) }.getOrNull()

    private fun mbps(bps: Long) = if (bps <= 0) "-" else "%.1f Mbps".format(bps / 1_000_000.0)

    private fun describe(): String {
        val pl = shown?.get() ?: RefreshButton.currentPlayer() ?: return "No player yet"
        val q = call(pl, "getQuality")
        val st = call(pl, "getStatistics")
        val w = q?.let { call(it, "getWidth") } as? Int ?: 0
        val h = q?.let { call(it, "getHeight") } as? Int ?: 0
        val name = q?.let { call(it, "getName") } as? String ?: "-"
        val codecs = q?.let { call(it, "getCodecs") } as? String ?: ""
        val auto = call(pl, "isAutoQualityMode") as? Boolean ?: false
        val vbr = (st?.let { call(it, "getVideoBitRate") } as? Int ?: 0).toLong()
        val fps = st?.let { call(it, "getFrameRate") } as? Int ?: 0
        val dropped = st?.let { call(it, "getDroppedFrames") } as? Int ?: 0
        val decoded = st?.let { call(it, "getDecodedFrames") } as? Int ?: 0
        val latency = call(pl, "getLiveLatency") as? Long ?: -1L
        val pos = call(pl, "getPosition") as? Long ?: 0L
        val buf = call(pl, "getBufferedPosition") as? Long ?: 0L
        val bw = call(pl, "getBandwidthEstimate") as? Long ?: 0L
        val avg = call(pl, "getAverageBitrate") as? Long ?: 0L
        val low = call(pl, "isLiveLowLatency") as? Boolean
        val proto = call(pl, "getProtocol") as? String ?: ""
        val rate = call(pl, "getPlaybackRate") as? Float ?: 1f
        return buildString {
            append("Quality   $name" + (if (w > 0) " (${w}x$h)" else "") + (if (auto) ", auto" else "") + "\n")
            append("Video     ${mbps(vbr)}, $fps fps" + (if (avg > 0) " (avg ${mbps(avg)})" else "") + "\n")
            if (latency >= 0) append("Delay     %.1f s behind live".format(latency / 1000.0) +
                (if (low != null) (if (low) ", low latency on" else ", low latency off") else "") + "\n")
            append("Buffer    %.1f s".format((buf - pos).coerceAtLeast(0) / 1000.0) +
                (if (rate != 1f) ", speed %.2fx".format(rate) else "") + "\n")
            append("Network   ${mbps(bw)} estimate\n")
            append("Frames    $dropped dropped of $decoded")
            if (codecs.isNotEmpty() || proto.isNotEmpty()) append("\nFormat    " + listOf(codecs, proto).filter { it.isNotEmpty() }.joinToString(", "))
        }
    }

    /** Three bars: the stats button. */
    private class StatsIcon : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            stroke.strokeWidth = s * 0.11f
            c.drawLine(s * 0.25f, s * 0.8f, s * 0.25f, s * 0.52f, stroke)
            c.drawLine(s * 0.5f, s * 0.8f, s * 0.5f, s * 0.22f, stroke)
            c.drawLine(s * 0.75f, s * 0.8f, s * 0.75f, s * 0.4f, stroke)
        }
    }
}
