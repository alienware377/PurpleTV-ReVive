package tv.purple.xp

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Send straight from the emote picker.
 *
 * The picker opens as a bottom sheet in a window of its own, and that window covers the whole
 * screen with Material's "touch_outside" layer: a tap anywhere outside the sheet only closes it.
 * So pressing Send with the picker open closed the picker and sent nothing, and it took a second
 * press. This watches that outside layer: when a tap there lands on the chat's Send button, the
 * picker still closes as before and the button is pressed too.
 */
object PickerSend {

    /** Test switch: log instead of pressing Send, so device tests never post to chat. */
    private const val KEY_DRY_RUN = "dev_send_dry_run"
    private val SEND_IDS = listOf("chat_message_send", "message_input_send")

    /** From the picker sheet hook, when a bottom sheet is put into its window. */
    @SuppressLint("ClickableViewAccessibility")
    fun onSheet(sheet: ViewGroup) {
        val ctx = sheet.context
        val id = Res.id(ctx, "touch_outside")
        if (id == 0) return
        val outside = sheet.rootView?.findViewById<View>(id) ?: return
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f; var downAt = 0L
        // A touch listener that only watches (returns false), so the sheet still closes as before.
        outside.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; downAt = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val tap = abs(e.rawX - downX) < slop && abs(e.rawY - downY) < slop &&
                        SystemClock.uptimeMillis() - downAt < 600
                    if (tap) sendAt(e.rawX.toInt(), e.rawY.toInt())
                }
            }
            false
        }
    }

    private fun sendAt(x: Int, y: Int) {
        val act = ViewHider.currentActivity() ?: return
        val root = act.window?.decorView ?: return
        val r = Rect()
        val send = SEND_IDS.mapNotNull { n -> Res.id(act, n).takeIf { it != 0 }?.let { root.findViewById<View>(it) } }
            .firstOrNull { v ->
                if (!v.isShown || !v.isEnabled) return@firstOrNull false
                val loc = IntArray(2).also { v.getLocationOnScreen(it) }
                r.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                r.contains(x, y)
            } ?: return
        // After the sheet has handled its own close.
        send.post {
            if (!send.isShown || !send.isEnabled) return@post
            if (Settings.get(KEY_DRY_RUN, false)) { log("PS would press Send (${Res.entry(send)})"); return@post }
            send.performClick()
            log("PS pressed Send from the emote picker")
        }
    }
}
