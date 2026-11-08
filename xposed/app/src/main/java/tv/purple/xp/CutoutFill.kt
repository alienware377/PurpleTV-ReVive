package tv.purple.xp

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Fullscreen video next to a camera hole (issue #7).
 *
 * Up to Android 14 a window keeps out of a camera hole unless it asks otherwise, so in the
 * landscape player the hole's edge of the screen stays black and the video sits off centre.
 * Android 15 and newer already let Twitch (it targets 36) use that strip, so this only switches
 * the window to the short-edges cutout mode while it is landscape with the status bar hidden,
 * and puts the old mode back the moment either stops being true. Long-edge cutouts, like the
 * notch some tablets have in landscape, are left alone.
 */
object CutoutFill {

    const val KEY = "cutout_fill"

    /** Mode each window had before we touched it, to put back. */
    private val original = java.util.WeakHashMap<Activity, Int>()

    fun install(lp: LoadPackageParam) {
        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.P || sdk >= 35) return
        XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                if (act.packageName != lp.packageName) return
                runCatching { attach(act) }.onFailure { log("cutout attach failed: $it") }
            }
        })
        log("cutout fill installed (sdk $sdk)")
    }

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
        val want = Settings.get(KEY, true) &&
            act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            statusBarHidden(decor, window.attributes.flags)
        val mode = if (want) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES else base
        val attrs = window.attributes
        if (attrs.layoutInDisplayCutoutMode == mode) return
        attrs.layoutInDisplayCutoutMode = mode
        window.attributes = attrs
        log("cutout mode -> ${if (want) "short edges" else "original ($base)"}")
    }

    @Suppress("DEPRECATION")
    private fun statusBarHidden(decor: View, flags: Int): Boolean {
        if (flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = decor.rootWindowInsets ?: return false
            return !insets.isVisible(WindowInsets.Type.statusBars())
        }
        return decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
    }
}
