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
 * content is attached; if the message view starts (or, in Korean, ends) with the localized
 * browser_link_disclaimer text, the dialog is made invisible and its positive button pressed, which is exactly what the
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
        val (fixed, before) = fixedText(ctx) ?: return
        val text = msg.text.toString()
        if (!(if (before) text.startsWith(fixed) else text.trimEnd().endsWith(fixed))) return
        val ok = find(root, ctx, "alert_dialog_positive_button") ?: return

        // Hide first, so nothing flashes on screen for the frame before the click lands.
        window.attributes = window.attributes.apply {
            alpha = 0f
            dimAmount = 0f
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val clicked = clickable(ok)?.performClick() ?: false
        if (dialog.isShowing) runCatching { dialog.dismiss() }
        log("link disclaimer skipped (click=$clicked)")
    }

    /** Twitch's Button is a wrapper that hands its listener to an inner MaterialButton, so press
     *  whichever view in the subtree actually has a listener. */
    private fun clickable(v: View): View? {
        if (v.hasOnClickListeners()) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) clickable(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun find(root: View, ctx: Context, name: String): View? {
        val id = ctx.resources.getIdentifier(name, "id", ctx.packageName)
        return if (id == 0) null else root.findViewById(id)
    }

    /** The fixed text beside the URL placeholder, and whether it comes before it. Normally the
     *  text before, e.g. "This link will take you to". Korean puts the URL first, so there the
     *  text after it is used instead. */
    private var cached: Pair<String, Boolean>? = null

    private fun fixedText(ctx: Context): Pair<String, Boolean>? {
        cached?.let { return it }
        val id = ctx.resources.getIdentifier("browser_link_disclaimer", "string", ctx.packageName)
        if (id == 0) return null
        val raw = ctx.resources.getText(id).toString()
        val ph = Regex("%(\\d+\\$)?s").find(raw) ?: return null
        val before = raw.substring(0, ph.range.first).trimEnd()
        val after = raw.substring(ph.range.last + 1).trim()
        val part = when {
            before.length >= 4 -> before to true
            after.length >= 4 -> after to false
            else -> return null
        }
        return part.also { cached = it }
    }
}
