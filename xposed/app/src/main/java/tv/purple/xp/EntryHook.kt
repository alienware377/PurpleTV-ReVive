package tv.purple.xp

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

const val TAG = "PurpleXP"
// Host may be the stock package or the renamed coexistence package.
val TWITCH_PKGS = setOf("tv.twitch.android.app", "tv.purple.app")

fun log(msg: String) = XposedBridge.log("[$TAG] $msg")

/** Short toast on the main thread (safe to call from any thread). */
fun toast(ctx: Context, msg: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
}

class EntryHook : de.robv.android.xposed.IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TWITCH_PKGS) return
        log("loaded into ${lpparam.packageName}, classloader=${lpparam.classLoader}")

        // Phase 0 proof: confirm injection by hooking Application.attach/onCreate.
        XposedHelpers.findAndHookMethod(
            Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as Context
                    log("Application.attach fired — injection LIVE on Twitch. ctx=$ctx")
                    onTwitchContext(ctx, lpparam)
                }
            }
        )
    }

    @Volatile private var featuresWired = false

    private fun onTwitchContext(ctx: Context, lpparam: LoadPackageParam) {
        if (featuresWired) return
        featuresWired = true
        // Runtime kill-switch for isolation testing (toggle without rebuilding, no root):
        //   adb shell settings put global purplexp_disable 1   (then restart app) -> all hooks off
        //   adb shell settings put global purplexp_disable 0
        val disabled = runCatching {
            android.provider.Settings.Global.getInt(ctx.contentResolver, "purplexp_disable", 0) == 1
        }.getOrDefault(false)
        if (disabled) { log("DISABLED via purplexp_disable=1 — no features wired"); return }
        runCatching { TwitchVersion.init(ctx) }
        runCatching { Settings.init(ctx) }.onFailure { log("settings init failed: $it") }
        runCatching { DevBridge.install(ctx) }.onFailure { log("dev bridge failed: $it") }

        // Master switch. Off means the app must behave like stock Twitch, so nothing below is
        // wired -- but the settings entry still is, otherwise the only way back would be to
        // reinstall. That screen shows just the one switch while we are off.
        if (!Settings.get(Settings.KEY_ENABLED, true)) {
            log("PurpleTV is switched OFF — wiring the settings entry only")
            runCatching { SettingsEntry.install(lpparam) }
                .onFailure { log("settings entry failed: $it") }
            return
        }

        log("Twitch context ready; wiring features")
        runCatching { StallSampler.start() }
        runCatching { EmoteRepo.loadGlobalsAsync() }.onFailure { log("emote fetch wire failed: $it") }
        runCatching { EmoteRepo.harvestTwitchAsync(ctx) }.onFailure { log("twitch harvest wire failed: $it") }
        runCatching { EmoteRepo.loadTwitchGlobalAsync() }.onFailure { log("twitch global wire failed: $it") }
        runCatching { EmoteRepo.loadPersonalEmotesAsync(ctx) }.onFailure { log("personal emotes wire failed: $it") }
        runCatching { BadgeRepo.loadAsync() }.onFailure { log("badge fetch wire failed: $it") }
        runCatching { Pronouns.loadAsync() }.onFailure { log("pronoun wire failed: $it") }
        runCatching { PushFix.install() }.onFailure { log("push fix failed: $it") }
        runCatching { EmoteHooks.install(lpparam) }.onFailure { log("emote hooks failed: $it") }
        runCatching { Channels.install(lpparam) }.onFailure { log("channel probe failed: $it") }
        runCatching { EmoteAutocomplete.install(lpparam) }.onFailure { log("autocomplete failed: $it") }
        runCatching { GlVideoFilter.install() }.onFailure { log("GL filter install failed: $it") }
        runCatching { FrequentEmotes.install(lpparam) }.onFailure { log("frequent emotes failed: $it") }
        runCatching { RefreshButton.install(lpparam) }.onFailure { log("refresh button failed: $it") }
        runCatching { VodSeek.install(lpparam) }.onFailure { log("vod seek failed: $it") }
        runCatching { PickerSections.install(lpparam) }.onFailure { log("picker sections failed: $it") }
        runCatching { ClassicTheatre.install(lpparam) }.onFailure { log("classic theatre failed: $it") }
        runCatching { ModLogs.install(lpparam) }.onFailure { log("mod logs failed: $it") }
        runCatching { ChatGuards.install(lpparam) }.onFailure { log("chat guards failed: $it") }
        runCatching { PlayerStats.install(lpparam) }.onFailure { log("player stats failed: $it") }
        runCatching { AudioFilters.install() }.onFailure { log("audio filters failed: $it") }
        runCatching { RaidCard.install(lpparam) }.onFailure { log("raid card failed: $it") }
        runCatching { InAppUpdate.install(ctx) }.onFailure { log("in-app update failed: $it") }
        runCatching { UpdateChecker.schedule() }.onFailure { log("update check failed: $it") }
        runCatching { PickerFavorites.install(lpparam) }.onFailure { log("picker favorites failed: $it") }
        runCatching { ChatTransparency.install(lpparam) }.onFailure { log("chat transparency failed: $it") }
        runCatching { ChannelPoints.install(ctx) }.onFailure { log("channel points failed: $it") }
        runCatching { SettingsEntry.install(lpparam) }.onFailure { log("settings entry failed: $it") }
        runCatching { ViewHider.install(lpparam) }.onFailure { log("view hider failed: $it") }
        runCatching { HomeTabs.install(lpparam) }.onFailure { log("home tabs failed: $it") }
        runCatching { ChatAppearance.install(lpparam) }.onFailure { log("chat appearance failed: $it") }
        runCatching { LinkDisclaimer.install(lpparam) }.onFailure { log("link disclaimer failed: $it") }
        runCatching { PlayerGestures.install(lpparam) }.onFailure { log("player gestures failed: $it") }
    }
}
