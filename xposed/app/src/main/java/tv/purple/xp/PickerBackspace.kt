package tv.purple.xp

import android.view.View
import android.widget.EditText
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * The backspace button in the emote picker: delete the whole last word or emote (the default), one
 * letter, or leave Twitch's own behaviour. Twitch's own button ("delete_emote_button") is kept and
 * only its tap is taken over, so it looks and sits exactly as before.
 */
object PickerBackspace {

    const val KEY = "picker_backspace"
    const val MODE_TWITCH = 0
    const val MODE_WORD = 1
    const val MODE_LETTER = 2

    /**
     * Taken over at the click itself, so it works wherever Twitch puts the picker: in the stream
     * screen's own window (31.1.2) or in a bottom sheet window of its own (31.7.0), which the
     * layout-pass walks never see.
     */
    fun install(lp: LoadPackageParam) {
        // Twitch hides its button when the picker opens without the keyboard (on tablets and
        // unfolded phones, for one). Its picker mode enum keeps readable getters.
        runCatching {
            XposedHelpers.findAndHookMethod(TwitchVersion.pick("rpf", "oxf"), lp.classLoader, "getDeleteButtonEnabled", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (Settings.getInt(KEY, MODE_WORD) != MODE_TWITCH) p.result = true
                }
            })
        }.onFailure { log("PB show button failed: $it") }
        XposedBridge.hookAllMethods(View::class.java, "performClick", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as? View ?: return
                if (v.id == View.NO_ID || v.id != Res.id(v.context, "delete_emote_button")) return
                val mode = Settings.getInt(KEY, MODE_WORD)
                if (mode == MODE_TWITCH) return
                val input = inputFor(v) ?: return
                if (mode == MODE_WORD) deleteWord(input) else deleteLetter(input)
                p.result = true
            }
        })
        log("PB picker backspace installed")
    }

    /** The chat box, in the picker's own window or the stream screen's. */
    private fun inputFor(v: View): EditText? {
        val id = Res.id(v.context, "message_input")
        if (id == 0) return null
        return (v.rootView?.findViewById<View>(id)
            ?: ViewHider.currentActivity()?.window?.decorView?.findViewById(id)) as? EditText
    }

    /** Deletes the selection, or the last word or emote before the cursor with the space after it. */
    fun deleteWord(et: EditText) {
        val e = et.text ?: return
        val a = et.selectionStart; val b = et.selectionEnd
        if (a >= 0 && b >= 0 && a != b) { e.delete(minOf(a, b), maxOf(a, b)); return }
        val end = if (b >= 0) b else e.length
        var i = end
        while (i > 0 && e[i - 1].isWhitespace()) i--
        while (i > 0 && !e[i - 1].isWhitespace()) i--
        if (i < end) e.delete(i, end)
    }

    /** Deletes the selection, or one character (a whole emoji, not half of one) before the cursor. */
    fun deleteLetter(et: EditText) {
        val e = et.text ?: return
        val a = et.selectionStart; val b = et.selectionEnd
        if (a >= 0 && b >= 0 && a != b) { e.delete(minOf(a, b), maxOf(a, b)); return }
        val end = if (b >= 0) b else e.length
        if (end <= 0) return
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(e.toString())
        val start = it.preceding(end).takeIf { p -> p != android.icu.text.BreakIterator.DONE } ?: (end - 1)
        e.delete(start, end)
    }
}
