package tv.purple.xp

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * A one-time tip the first time a deleted message shows up in chat while "Deleted messages" is on
 * Twitch's default, pointing at the setting that shows what it said. It sits at the top of the
 * chat, out of the way of new messages, until its X is pressed or it is tapped to open the setting.
 */
object DeletedTip {

    const val KEY = "deleted_tip_dismissed"

    /** Offered at most once per run, so leaving it open and moving on does not nag. */
    @Volatile private var offered = false

    /** The bidi isolate close (PDI) Twitch puts after the placeholder under an RTL layout. */
    private const val PDI = '⁩'

    /** From the TextView.setText hook: costs a char compare or two until a deleted placeholder shows. */
    fun onText(tv: TextView, cs: CharSequence) {
        if (offered) return
        // RTL wraps the placeholder in bidi isolates, so the line ends in one PDI (deleted while on
        // screen) or two (already deleted, where the whole line is wrapped as well). Look past them.
        var end = cs.length
        if (end > 0 && cs[end - 1] == PDI) { end--; if (end > 0 && cs[end - 1] == PDI) end-- }
        if (end < 3 || cs[end - 1] != '>') return
        val text = if (end == cs.length) cs else cs.subSequence(0, end)
        if (!ChatLineStyle.isPlainPlaceholder(tv.context, text)) return
        offered = true
        if (Settings.get(KEY, false)) return
        tv.post { runCatching { show(tv) }.onFailure { log("DT tip: $it") } }
    }

    private fun show(tv: TextView) {
        if (!tv.isAttachedToWindow) return
        // The chat list: the deleted line's nearest scrolling parent.
        var list: View = tv
        var p = tv.parent
        while (p is View) {
            if (p.javaClass.name.contains("RecyclerView")) { list = p; break }
            p = p.parent
        }
        val ctx = tv.context
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
        val m = dp(8f)
        lateinit var pw: PopupWindow
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF2241B31.toInt()); cornerRadius = dp(12f).toFloat(); setStroke(dp(1.5f), FilterIcons.PURPLE)
            }
            setPadding(dp(12f), dp(9f), dp(4f), dp(9f))
            addView(TextView(ctx).apply {
                text = "A message was just deleted. To see what deleted messages said, go to PurpleTV settings › Chat › Deleted messages. Tap here to open it."
                setTextColor(0xFFEFEFF1.toInt()); textSize = 13.5f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "✕"; setTextColor(0xFFEFEFF1.toInt()); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                contentDescription = "Close tip"
                setOnClickListener { Settings.set(KEY, true); pw.dismiss() }
            }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
            setOnClickListener {
                Settings.set(KEY, true); pw.dismiss()
                (ViewHider.currentActivity() ?: ctx).let { PurpleMenu.showChat(it) }
            }
        }
        val w = (list.width - 2 * m).coerceAtLeast(dp(220f))
        pw = PopupWindow(card, w, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = true; isOutsideTouchable = false
        }
        val loc = IntArray(2).also { list.getLocationInWindow(it) }
        pw.showAtLocation(list, Gravity.TOP or Gravity.START, loc[0] + m, loc[1] + m)
        // Gone with the chat it belongs to.
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { runCatching { pw.dismiss() }; v.removeOnAttachStateChangeListener(this) }
        })
        log("DT deleted message tip shown")
    }
}
