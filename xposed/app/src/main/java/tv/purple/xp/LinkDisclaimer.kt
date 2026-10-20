package tv.purple.xp

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Skip the "Careful, this link will take you to ..." confirmation when a chat link is tapped.
 *
 * The warning is Twitch's generic alert dialog (layout alert_dialog_content_view), built in
 * obfuscated code, so the hook sits on the framework Dialog.show instead. Right after show the
 * content is attached; if the message view starts with the localized browser_link_disclaimer
 * text, the dialog is made invisible and its positive button pressed, which is exactly what the
 * user would have done. Every other alert is left alone.
 */
object LinkDisclaimer {

    const val KEY_ENABLED = "disable_link_disclaimer"

    fun install(@Suppress("UNUSED_PARAMETER") lp: LoadPackageParam) {
        XposedHelpers.findAndHookMethod(Dialog::class.java, "show", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!Settings.get(KEY_ENABLED, false)) return
                val dialog = param.thisObject as? Dialog ?: return
                runCatching { skipIfDisclaimer(dialog) }
                    .onFailure { log("link disclaimer error: $it") }
            }
        })
    }

    private fun skipIfDisclaimer(dialog: Dialog) {
        val window = dialog.window ?: return
        val root = window.decorView
        val ctx = dialog.context
        val msg = find(root, ctx, "alert_dialog_message_view") as? TextView ?: return
        val prefix = prefix(ctx) ?: return
        if (!msg.text.toString().startsWith(prefix)) return
        val ok = find(root, ctx, "alert_dialog_positive_button") ?: return

        // Hide first, so nothing flashes on screen for the frame before the click lands.
        window.attributes = window.attributes.apply {
            alpha = 0f
            dimAmount = 0f
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val clicked = ok.performClick()
        if (dialog.isShowing) runCatching { dialog.dismiss() }
        log("link disclaimer skipped (click=$clicked)")
    }

    private fun find(root: View, ctx: Context, name: String): View? {
        val id = ctx.resources.getIdentifier(name, "id", ctx.packageName)
        return if (id == 0) null else root.findViewById(id)
    }

    /** The text before the URL placeholder, e.g. "This link will take you to ". */
    private var cached: String? = null

    private fun prefix(ctx: Context): String? {
        cached?.let { return it }
        val id = ctx.resources.getIdentifier("browser_link_disclaimer", "string", ctx.packageName)
        if (id == 0) return null
        val raw = ctx.resources.getText(id).toString()
        val cut = raw.indexOf('%').takeIf { it > 0 } ?: return null
        return raw.substring(0, cut).trimEnd().takeIf { it.length >= 4 }?.also { cached = it }
    }
}
