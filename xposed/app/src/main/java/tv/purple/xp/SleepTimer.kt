package tv.purple.xp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast

/**
 * A sleep timer: a clock button in the player's top bar that pauses the stream after a chosen
 * time.
 *
 * The bar is a ConstraintLayout built by obfuscated code, so the button is not wired into its
 * constraints. It is added as a plain child and placed by coordinates just left of the leftmost
 * visible control on that row, and it follows that row's visibility, so it appears and fades with
 * the rest of the player controls.
 *
 * Pausing goes through the media session, the same as a headset's pause button, which Twitch's
 * player already answers. Nothing in Twitch is called directly.
 */
object SleepTimer {

    const val KEY = "show_timer_button"

    private const val TAG = "ptv_sleep_timer"
    private val ROW = arrayOf("create_clip_text_button", "switch_to_vertical_view", "cast_button",
        "share_button", "settings_button", "audio_and_subtitles")
    private val MINUTES = intArrayOf(15, 30, 45, 60, 90, 120)

    private val handler = Handler(Looper.getMainLooper())
    private var added = false
    /** Uptime at which the stream will be paused, or 0 when no timer is running. */
    @Volatile private var dueAt = 0L
    private val fire = Runnable { pauseNow() }
    private var appCtx: Context? = null

    /** Called on every ViewHider pass. Adds, places, or removes the button. */
    fun attach(root: View) {
        val on = Settings.get(KEY, false)
        // Nothing to add and nothing to take away: skip the tree walk.
        if (!on && dueAt > 0) { handler.removeCallbacks(fire); dueAt = 0 }
        if (!on && !added) return
        val ctx = root.context
        fun ids(n: String) = ctx.resources.getIdentifier(n, "id", ctx.packageName)
        val settingsId = ids("settings_button")
        if (settingsId == 0) return
        // Every player overlay on screen that has a settings gear gets the button.
        val bars = ArrayList<ViewGroup>()
        collect(root, settingsId, bars)
        for (bar in bars) {
            var btn = bar.findViewWithTag<ImageView>(TAG)
            if (!on) { if (btn != null) bar.removeView(btn); continue }
            // The row's controls: the reference for size and height is any shown one.
            val row = ROW.mapNotNull { n -> ids(n).takeIf { it != 0 }?.let { bar.findViewById<View>(it) } }
                .filter { it.parent === bar && it.visibility == View.VISIBLE && it.width > 0 }
            val ref = row.firstOrNull { it.height > 0 }
            if (ref == null) { btn?.visibility = View.GONE; continue }
            if (btn == null) {
                btn = make(bar.context, ref)
                bar.addView(btn, ViewGroup.LayoutParams(ref.height, ref.height))
                added = true
            }
            val left = row.minOf { it.x }
            val x = left - ref.height
            if (btn.x != x) btn.x = x
            if (btn.y != ref.y) btn.y = ref.y
            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
            tint(btn)
        }
    }

    private fun collect(v: View, settingsId: Int, out: MutableList<ViewGroup>) {
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), settingsId, out)
    }

    private fun make(ctx: Context, ref: View) = ImageView(ctx).apply {
        tag = TAG
        setImageResource(android.R.drawable.ic_lock_idle_alarm)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
        contentDescription = "Sleep timer"
        setOnClickListener { choose(it.context) }
    }

    /** White when idle, Twitch purple while a timer is running. */
    private fun tint(v: ImageView) {
        val c = if (dueAt > 0) 0xFFA970FF.toInt() else Color.WHITE
        if (tinted[v] != c) { v.setColorFilter(c, PorterDuff.Mode.SRC_IN); tinted[v] = c }
    }

    private val tinted = java.util.WeakHashMap<ImageView, Int>()

    private fun choose(ctx: Context) {
        val left = if (dueAt > 0) ((dueAt - SystemClock.uptimeMillis()) / 60000L + 1) else 0L
        val items = ArrayList<String>()
        items.add(if (dueAt > 0) "Turn off ($left min left)" else "Off")
        for (m in MINUTES) items.add(if (m % 60 == 0) "${m / 60} hour${if (m > 60) "s" else ""}"
            else if (m > 60) "${m / 60}.5 hours" else "$m minutes")
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pause the stream after")
            .setItems(items.toTypedArray()) { d, which ->
                if (which == 0) cancel(ctx) else start(ctx, MINUTES[which - 1] * 60_000L)
                d.dismiss()
            }
            .show()
    }

    /** Start (or restart) the timer. Public so the dev bridge can test with a short delay. */
    fun start(ctx: Context, ms: Long) {
        appCtx = ctx.applicationContext
        handler.removeCallbacks(fire)
        dueAt = SystemClock.uptimeMillis() + ms
        handler.postAtTime(fire, dueAt)
        val min = ms / 60000L
        toast(ctx, if (min > 0) "Sleep timer: pausing in $min min" else "Sleep timer: pausing in ${ms / 1000} s")
        log("sleep timer set for ${ms / 1000}s")
        ViewHider.reapply()
    }

    private fun cancel(ctx: Context) {
        handler.removeCallbacks(fire)
        if (dueAt > 0) toast(ctx, "Sleep timer off")
        dueAt = 0
        ViewHider.reapply()
    }

    private fun pauseNow() {
        dueAt = 0
        val ctx = appCtx ?: Settings.ctx() ?: return
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            log("sleep timer: paused")
        }.onFailure { log("sleep timer pause failed: $it") }
        ViewHider.reapply()
    }

    private fun toast(ctx: Context, s: String) =
        runCatching { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }
}
