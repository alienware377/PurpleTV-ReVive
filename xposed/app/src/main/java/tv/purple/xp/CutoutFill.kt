package tv.purple.xp

import android.app.Activity
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Fullscreen video next to a camera hole (issue #7).
 *
 * In the landscape player (status bar hidden) the strip beside the camera hole stayed black and the
 * video sat off centre. With Always fullscreen the upright player gets the same. Only the edges the
 * player itself reaches are given up, so chat beside or under it keeps clear of the hole. Two
 * things kept the player out:
 *  - On Android 9 and 10 the window itself keeps out of the hole unless it asks otherwise, so it
 *    is switched to the short-edges cutout mode while that holds, and back after. From Android 11
 *    Twitch already asks for the hole itself.
 *  - On every version Twitch pads its layout by the hole's inset (on Android 15 and newer the
 *    window already reaches the hole, and that padding alone leaves the bar). That edge's share
 *    of the inset is taken out of what the activity's views are given while the status bar is hidden.
 * Dialogs and sheets keep the real insets, and nothing changes while the status bar shows.
 */
object CutoutFill {

    const val KEY = "cutout_fill"

    /** Mode each window had before we touched it, to put back. */
    private val original = java.util.WeakHashMap<Activity, Int>()

    fun install(lp: LoadPackageParam) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.P) return
        if (sdk < 35) XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                if (act.packageName != lp.packageName) return
                runCatching { attach(act) }.onFailure { log("cutout attach failed: $it") }
            }
        })
        XposedHelpers.findAndHookMethod(ViewGroup::class.java, "dispatchApplyWindowInsets", WindowInsets::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (v.javaClass.name != DECOR) return
                    val insets = param.args[0] as? WindowInsets ?: return
                    runCatching { strip(v, insets) }.onFailure { log("cutout strip failed: $it") }.getOrNull()
                        ?.let { param.args[0] = it }
                }
            })
        log("cutout fill installed (sdk $sdk)")
    }

    private const val DECOR = "com.android.internal.policy.DecorView"

    /** Which edges' camera hole space the player gets: only edges the player itself reaches. */
    private data class Sides(val left: Boolean, val top: Boolean, val right: Boolean)

    /**
     * The stream screen's player pane, not the buttons over it (those come and go with a tap) and
     * not the autoplay previews in lists, which use the same id.
     */
    private fun player(decor: View): View? {
        val pane = Res.id(decor.context, "player_pane").takeIf { it != 0 } ?: return null
        // Live streams sit in the theatre root; VODs and clips in the video presenter's container.
        for (host in arrayOf("video_presenter_container", "theatre_root_container")) {
            val id = Res.id(decor.context, host).takeIf { it != 0 } ?: continue
            val root = decor.findViewById<View>(id)?.takeIf { it.isShown } ?: continue
            root.findViewById<View>(pane)?.takeIf { it.isShown && it.height > 0 }?.let { return it }
        }
        return null
    }

    /**
     * The edges whose hole the player may cover, or null for none. A hole beside something else
     * (chat next to a landscape player, or a tablet's side notch beside the chat under an upright
     * player) keeps its space, so text is never drawn under it.
     */
    private fun sides(decor: View, cut: android.view.DisplayCutout?, statusHidden: Boolean): Sides? {
        if (!Settings.get(KEY, true) || cut == null || !statusHidden) return null
        if (!landscape(decor) && !AlwaysFullscreen.on()) return null
        val type = (decor.layoutParams as? WindowManager.LayoutParams)?.type ?: return null
        if (type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return null
        val p = player(decor) ?: return null
        val at = IntArray(2); p.getLocationInWindow(at)
        val pl = at[0]; val pt = at[1]; val pr = pl + p.width; val pb = pt + p.height
        val w = decor.width
        val m = 4
        var left = false; var top = false; var right = false
        for (r in cut.boundingRects) {
            if (r.isEmpty) continue
            if (r.left <= 0 && r.top < pb && r.bottom > pt && pl <= cut.safeInsetLeft + m) left = true
            if (r.right >= w && r.top < pb && r.bottom > pt && pr >= w - cut.safeInsetRight - m) right = true
            if (r.top <= 0 && r.left < pr && r.right > pl && pt <= cut.safeInsetTop + m) top = true
        }
        return if (left || top || right) Sides(left, top, right) else null
    }

    /** [insets] with the hole's space given to the player on [sides]'s edges, or null to leave them. */
    private fun strip(decor: View, insets: WindowInsets): WindowInsets? {
        val cut = insets.displayCutout ?: return null
        val s = sides(decor, cut, statusBarHidden(decor, insets)) ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            return insets.consumeDisplayCutout()
        }
        val l = if (s.left) 0 else cut.safeInsetLeft
        val t = if (s.top) 0 else cut.safeInsetTop
        val r = if (s.right) 0 else cut.safeInsetRight
        val b = cut.safeInsetBottom
        val kept = if (l == 0 && t == 0 && r == 0 && b == 0) null else android.view.DisplayCutout(
            Insets.of(l, t, r, b),
            cut.boundingRectLeft.takeIf { !s.left }, cut.boundingRectTop.takeIf { !s.top },
            cut.boundingRectRight.takeIf { !s.right }, cut.boundingRectBottom)
        return WindowInsets.Builder(insets)
            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(l, t, r, b))
            .setDisplayCutout(kept)
            .build()
    }

    /** What each window was last given, to ask for new insets when that changes. */
    private val given = java.util.WeakHashMap<View, String>()

    /**
     * From each ViewHider pass: the player opening or closing, or chat showing beside it, changes
     * which edges it gets, and the window has to be given its insets again for that.
     */
    fun onPass(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val real = root.rootWindowInsets ?: return
        val now = sides(root, real.displayCutout, statusBarHidden(root, real))?.toString() ?: "none"
        if (given[root] == now) return
        given[root] = now
        log("cutout: player gets $now")
        root.requestApplyInsets()
    }

    /** Upright, the hole's space is only given with Always fullscreen (otherwise the status bar is there). */
    private fun landscape(v: View) =
        v.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    @Suppress("DEPRECATION")
    private fun statusBarHidden(decor: View, insets: WindowInsets): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return !insets.isVisible(WindowInsets.Type.statusBars())
        val flags = (decor.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0
        return flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0 ||
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
    }

    // ---- Android 14 and older: let the window itself reach the hole ----

    private fun attach(act: Activity) {
        val decor = act.window?.decorView ?: return
        original[act] = act.window.attributes.layoutInDisplayCutoutMode
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update(act, decor) }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun update(act: Activity, decor: View) {
        if (act.isFinishing) return
        val window = act.window ?: return
        val base = original[act] ?: return
        // Twitch asks for the hole itself on Android 11 and newer (always mode), which is already
        // more than short edges: then only its padding is in the way, and strip() handles that.
        if (base == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && base == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)) return
        // The window itself does not reach the hole yet, so the player's edges cannot be measured
        // against it: any shown player in fullscreen asks for it, and strip() then decides per edge.
        val root = decor.rootWindowInsets
        val want = Settings.get(KEY, true) && (landscape(decor) || AlwaysFullscreen.on()) &&
            player(decor) != null && root != null && statusBarHidden(decor, root)
        val mode = if (want) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else base
        val attrs = window.attributes
        if (attrs.layoutInDisplayCutoutMode == mode) return
        attrs.layoutInDisplayCutoutMode = mode
        window.attributes = attrs
        log("cutout mode -> ${if (want) "short edges" else "original ($base)"}")
    }
}
