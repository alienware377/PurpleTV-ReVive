package tv.purple.xp

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import java.util.WeakHashMap

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

    private val wrapped = WeakHashMap<View, View.OnClickListener?>()

    /** From each layout pass. */
    fun attach(root: View) {
        val ctx = root.context
        val id = Res.id(ctx, "delete_emote_button")
        if (id == 0) return
        val btn = (root as? ViewGroup)?.findViewById<View>(id) ?: return
        if (wrapped.containsKey(btn)) return
        val original = listenerOf(btn)
        wrapped[btn] = original
        btn.setOnClickListener { v ->
            val mode = Settings.getInt(KEY, MODE_WORD)
            val input = inputFor(v)
            if (mode == MODE_TWITCH || input == null) { original?.onClick(v); return@setOnClickListener }
            if (mode == MODE_WORD) deleteWord(input) else deleteLetter(input)
        }
    }

    private fun inputFor(v: View): EditText? {
        val root = v.rootView ?: return null
        val id = Res.id(v.context, "message_input")
        return (if (id != 0) root.findViewById<View>(id) else null) as? EditText
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

    private fun listenerOf(v: View): View.OnClickListener? = runCatching {
        val li = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(v)
        li?.javaClass?.getDeclaredField("mOnClickListener")?.apply { isAccessible = true }?.get(li) as? View.OnClickListener
    }.getOrNull()
}
