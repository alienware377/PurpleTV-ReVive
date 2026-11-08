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
 * video sat off centre. Two things keep it out:
 *  - Up to Android 14 the window itself keeps out of the hole unless it asks otherwise, so it is
 *    switched to the short-edges cutout mode while that holds, and back after.
 *  - On every version Twitch pads its layout by the hole's inset (on Android 15 and newer the
 *    window already reaches the hole, and that padding alone leaves the bar). The hole's inset is
 *    taken out of what the activity's views are given while the status bar is hidden.
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

    /** [insets] without the camera hole's share, or null to leave them as they are. */
    private fun strip(decor: View, insets: WindowInsets): WindowInsets? {
        if (!Settings.get(KEY, true)) return null
        val type = (decor.layoutParams as? WindowManager.LayoutParams)?.type ?: return null
        if (type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return null
        if (insets.displayCutout == null) return null
        if (!landscape(decor) || !statusBarHidden(decor, insets)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets.Builder(insets)
                .setInsets(WindowInsets.Type.displayCutout(), Insets.NONE)
                .setDisplayCutout(null)
                .build()
        } else {
            @Suppress("DEPRECATION")
            insets.consumeDisplayCutout()
        }
    }

    /** Landscape only: upright, the hole sits on the player's top row of buttons. */
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
        val root = decor.rootWindowInsets
        val want = Settings.get(KEY, true) && landscape(decor) && root != null && statusBarHidden(decor, root)
        val mode = if (want) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else base
        val attrs = window.attributes
        if (attrs.layoutInDisplayCutoutMode == mode) return
        attrs.layoutInDisplayCutoutMode = mode
        window.attributes = attrs
        log("cutout mode -> ${if (want) "short edges" else "original ($base)"}")
    }
}
