package tv.purple.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field

/**
 * The three-dots chat menu button beside the chat box ("view_chat_settings") in landscape too.
 * Twitch draws it in every orientation but only shows it in portrait: its chat box presenter picks
 * the box's buttons from the screen orientation in its state (portrait: the menu, landscape: none).
 * So while it picks them, the state says portrait, and everything else it decides (send button,
 * Bits cancel, a hidden menu while the keyboard is up) stays exactly Twitch's own logic.
 *
 * The state is put back as soon as the pick is done, on the same thread, so nothing else ever sees
 * the borrowed orientation.
 */
object ChatMenuLandscape {

    const val KEY = "chat_menu_landscape"
    const val DEF = true

    private const val EXTRA = "ptv_orientation"

    fun install(lp: LoadPackageParam) {
        val cl = lp.classLoader
        // The presenter's lambdas (one class, a switch inside), its state and the orientation enum.
        val lambda = XposedHelpers.findClass(TwitchVersion.pick("d66", "j96"), cl)
        val state = XposedHelpers.findClass(TwitchVersion.pick("z56", "f96"), cl)
        val orient = XposedHelpers.findClass(TwitchVersion.pick("bu6", "vx6"), cl)
        val portrait = orient.enumConstants?.firstOrNull { (it as Enum<*>).name == "PORTRAIT" }
            ?: run { log("CM no PORTRAIT in ${orient.name}"); return }
        val hField: Field = state.getDeclaredField("h").apply { isAccessible = true }
        if (hField.type != orient) { log("CM ${state.name}.h is ${hField.type.name}, not the orientation"); return }
        val vas = XposedHelpers.findClass("tv.twitch.android.core.mvp.presenter.ViewAndState", cl)
        val ps = XposedHelpers.findClass("tv.twitch.android.core.mvp.presenter.PresenterState", cl)
        val stateField = vas.declaredFields.firstOrNull { it.type == ps }?.apply { isAccessible = true }
            ?: run { log("CM no state field in ViewAndState"); return }

        XposedHelpers.findAndHookMethod(lambda, "invoke", Any::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val a = p.args[0] ?: return
                if (a.javaClass !== vas || !Settings.get(KEY, DEF)) return
                val st = stateField.get(a) ?: return
                if (st.javaClass !== state) return
                val o = hField.get(st) ?: return
                if (o === portrait) return
                hField.set(st, portrait)
                p.setObjectExtra(EXTRA, o)
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                val o = p.getObjectExtra(EXTRA) ?: return
                val a = p.args[0] ?: return
                runCatching { stateField.get(a)?.let { hField.set(it, o) } }
                    .onFailure { log("CM could not put the orientation back: $it") }
            }
        })
        log("CM landscape chat menu installed")
    }
}
