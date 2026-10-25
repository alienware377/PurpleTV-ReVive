package tv.purple.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Keep live streams on Twitch's classic stream screen.
 *
 * From Twitch 31.5, phones open live streams in a new screen built with React Native (the
 * "ultralight" experiment), where chat, the emote picker and the message box are drawn by
 * JavaScript, out of the module's reach: no 7TV/BTTV/FFZ emotes, badges, autocomplete, filters
 * buttons or chat tweaks. Tablets, VODs and clips still use the classic screen. Twitch already
 * falls back to the classic screen whenever its routing check says no, so this makes that check
 * say no. The routing classes keep their readable names.
 */
object ClassicTheatre {

    const val KEY = "classic_theatre"
    private const val PKG = "tv.twitch.android.feature.discovery.feed.rn.theatre"

    fun install(lp: LoadPackageParam) {
        val cl = lp.classLoader
        val decision = runCatching { cl.loadClass("$PKG.RNTheatreRouteDecision") }.getOrNull()
        val refuse = decision?.let { runCatching { XposedHelpers.getStaticObjectField(it, "RefuseUltralightOff") }.getOrNull() }
        var n = 0
        if (refuse != null) runCatching {
            n += XposedBridge.hookAllMethods(cl.loadClass("${PKG}.RNTheatreRouteDecisionKt"), "rnTheatreRouteDecision", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (Settings.get(KEY, true)) p.result = refuse
                }
            }).size
        }
        runCatching {
            val launcher = cl.loadClass("$PKG.TwitchRNTheatreLauncher")
            for (m in listOf("launchIfEnabled", "startPreloadIfEnabled"))
                n += XposedBridge.hookAllMethods(launcher, m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (Settings.get(KEY, true)) p.result = false
                    }
                }).size
        }
        log(if (n > 0) "CT classic stream screen kept ($n hooks)" else "CT no React Native stream screen in this Twitch")
    }
}
