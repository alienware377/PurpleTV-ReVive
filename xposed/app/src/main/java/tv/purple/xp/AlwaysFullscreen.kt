package tv.purple.xp

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * "Always fullscreen": the status bar stays hidden in every Twitch screen, not just the landscape
 * player, so with CutoutFill the portrait player can use the strip beside the camera hole too.
 * A swipe down from the top edge shows it for a moment, as in any fullscreen app.
 *
 * Twitch shows the status bar again whenever it leaves its own fullscreen, so its requests to
 * show it are trimmed while this is on, and every activity hides it when it comes to the front.
 */
object AlwaysFullscreen {

    const val KEY = "always_fullscreen"

    fun on() = Settings.get(KEY, false)

    fun install(lp: LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                if (act.packageName != lp.packageName || !on()) return
                if (param.method.name == "onWindowFocusChanged" && param.args[0] != true) return
                runCatching { hide(act) }.onFailure { log("always fullscreen: $it") }
            }
        }
        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", hook)
        XposedHelpers.findAndHookMethod(Activity::class.java, "onWindowFocusChanged", Boolean::class.javaPrimitiveType, hook)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val ic = XposedHelpers.findClass("android.view.InsetsController", lp.classLoader)
                XposedBridge.hookAllMethods(ic, "show", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!on()) return
                        val types = param.args.getOrNull(0) as? Int ?: return
                        val keep = types and WindowInsets.Type.statusBars().inv()
                        if (keep == types) return
                        if (keep == 0) param.result = null else param.args[0] = keep
                    }
                })
            }.onFailure { log("always fullscreen show hook: $it") }
        }
        // Older Android, or code still on the flags: the fullscreen flag is kept on.
        XposedHelpers.findAndHookMethod(View::class.java, "setSystemUiVisibility", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Suppress("DEPRECATION")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!on()) return
                    val v = param.thisObject as View
                    if (v.rootView !== v) return
                    param.args[0] = (param.args[0] as Int) or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
            })
        log("always fullscreen installed")
    }

    @Suppress("DEPRECATION")
    private fun hide(act: Activity) {
        val w = act.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.statusBars())
            }
        } else {
            val d = w.decorView
            d.systemUiVisibility = d.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /** After the switch changes: hide or show the status bar on the screen behind the menu. */
    @Suppress("DEPRECATION")
    fun refresh() {
        val act = ViewHider.currentActivity() ?: return
        act.runOnUiThread {
            runCatching {
                if (on()) hide(act)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) act.window?.insetsController?.show(WindowInsets.Type.statusBars())
                else act.window?.decorView?.let { it.systemUiVisibility = it.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN.inv() }
                act.window?.decorView?.requestApplyInsets()
            }
        }
    }
}
