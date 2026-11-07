package tv.purple.xp

import android.content.pm.ApplicationInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Keeps a patched Twitch alive when Android refreshes its ApplicationInfo.
 *
 * A theme, wallpaper colour or overlay change makes the system call
 * LoadedApk.updateApplicationInfo(), which builds a fresh AppComponentFactory from the current
 * class loader. In a patched build that factory is LSPatch's loader stub, and its static init
 * loads liblspatch.so again. The library is already bound to the first class loader, so the load
 * fails with "already opened by ClassLoader" and the app crashes on the main thread.
 *
 * The factory that is already running is the right one, so it is kept instead. This happens in
 * LSPatch's own code before PurpleTV does anything, which is why it is installed in every process
 * and ignores the kill switch.
 */
object LoaderFix {

    fun install() {
        val loadedApk = XposedHelpers.findClass("android.app.LoadedApk", null)
        XposedBridge.hookAllMethods(loadedApk, "createAppFactory", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val info = param.args.getOrNull(0) as? ApplicationInfo ?: return
                if (info.appComponentFactory?.startsWith("org.lsposed.lspatch.") != true) return
                val existing = XposedHelpers.getObjectField(param.thisObject, "mAppComponentFactory") ?: return
                param.result = existing
                log("loader fix: kept the running AppComponentFactory")
            }
        })
    }
}
