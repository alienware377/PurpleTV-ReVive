package tv.purple.xp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlin.math.abs

/**
 * Taps that land outside the emote picker, on the chat bar behind it.
 *
 * The picker opens as a bottom sheet in a window of its own, and that window covers the whole
 * screen with Material's "touch_outside" layer: a tap anywhere outside the sheet only closes it.
 * So pressing Send with the picker open closed the picker and sent nothing, and tapping the chat
 * box closed the picker without bringing up the keyboard. This watches that outside layer: the
 * picker still closes as before, and then Send is pressed, or the chat box gets the keyboard.
 */
object PickerSend {

    /** Test switch: log instead of pressing Send, so device tests never post to chat. */
    private const val KEY_DRY_RUN = "dev_send_dry_run"
    private val SEND_IDS = listOf("chat_message_send", "message_input_send")
    private const val INPUT_ID = "message_input"
    private var logged = 0

    /** Test safety: with the dry-run switch on, any press of Send is logged and blocked. */
    fun install() {
        de.robv.android.xposed.XposedBridge.hookAllMethods(View::class.java, "performClick", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as? View ?: return
                if (v.id == View.NO_ID || SEND_IDS.none { Res.id(v.context, it) == v.id }) return
                if (!Settings.get(KEY_DRY_RUN, false)) return
                log("PS Send was pressed (dry run: blocked, nothing sent)")
                p.result = true
            }
        })
    }

    /** From the picker sheet hook, when a bottom sheet is put into its window. */
    @SuppressLint("ClickableViewAccessibility")
    fun onSheet(sheet: ViewGroup) {
        val ctx = sheet.context
        val id = Res.id(ctx, "touch_outside")
        if (id == 0) return
        val outside = sheet.rootView?.findViewById<View>(id) ?: return
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2
        var downX = 0f; var downY = 0f; var downAt = 0L
        // A touch listener that only watches (returns false), so the sheet still closes as before.
        outside.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; downAt = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val tap = abs(e.rawX - downX) < slop && abs(e.rawY - downY) < slop &&
                        SystemClock.uptimeMillis() - downAt < 700
                    // Where the finger went down: the chat bar can start moving as the sheet closes.
                    if (tap) runCatching { tapAt(downX.toInt(), downY.toInt()) }.onFailure { log("PS tap: $it") }
                }
            }
            false
        }
    }

    private fun dp(ctx: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    /** The view's box on screen, grown by [grow] on every side for fingers that land just off it. */
    private fun hit(v: View, x: Int, y: Int, grow: Int): Boolean {
        if (!v.isShown || !v.isEnabled) return false
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        return Rect(loc[0] - grow, loc[1] - grow, loc[0] + v.width + grow, loc[1] + v.height + grow).contains(x, y)
    }

    private fun tapAt(x: Int, y: Int) {
        val act = ViewHider.currentActivity() ?: return
        val root = act.window?.decorView ?: return
        val send = SEND_IDS.mapNotNull { n -> Res.id(act, n).takeIf { it != 0 }?.let { root.findViewById<View>(it) } }
            .firstOrNull { hit(it, x, y, dp(act, 10f)) }
        if (send != null) {
            // After the sheet has handled its own close.
            send.post {
                if (!send.isShown || !send.isEnabled) { log("PS Send was gone by the time the picker closed"); return@post }
                if (Settings.get(KEY_DRY_RUN, false)) { log("PS would press Send (${Res.entry(send)})"); return@post }
                send.performClick()
                log("PS pressed Send from the emote picker")
            }
            return
        }
        val input = Res.id(act, INPUT_ID).takeIf { it != 0 }?.let { root.findViewById<View>(it) } as? EditText
        if (input != null && hit(input, x, y, 0)) {
            // The picker's window gives focus back to Twitch's as it closes; ask for the keyboard after.
            fun open() {
                input.requestFocus()
                input.setSelection(input.text?.length ?: 0)
                (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            input.postDelayed({ open() }, 150)
            input.postDelayed({ if (!input.isFocused) open() }, 450)
            if (logged++ < 5) log("PS chat box tapped from the emote picker: keyboard")
            return
        }
        if (logged++ < 5) log("PS tap outside the picker at $x,$y hit neither Send nor the chat box")
    }
}
