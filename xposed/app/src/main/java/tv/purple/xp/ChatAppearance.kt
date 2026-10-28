package tv.purple.xp

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Chat appearance: message font size and alternating row backgrounds.
 *
 * Anchored on resource entry names, like the rest of the module:
 *   chat_message_item      — the message TextView of an ordinary chat line
 *   chat_message           — the message TextView of the One Chat overlay and the user-notice rows
 *   chat_message_container — the row ROOT, where the alternating background goes
 *
 * The first two were previously the wrong way round: `chat_message_item` was treated as the row
 * root and `chat_message` as the message view. Both names are real, which is what let the mistake
 * survive — but `chat_message` does not appear in the main chat list at all, so the font size
 * silently never reached it, and the alternating tint was painted on the message TextView instead
 * of the row, leaving the row's 6dp vertical padding untinted.
 *
 * Both settings are re-applied on every layout pass. Values are only written when they actually
 * differ from what the view already has: mutating a view during a layout pass schedules another
 * one, so an unconditional write would spin forever. Comparing first makes it converge after a
 * single extra pass.
 */
object ChatAppearance {

    const val KEY_FONT_SIZE = "chat_font_size_v2"
    /** Where the slider starts: Twitch 31's own chat size (ChatMessageView, font_small_new = 14sp).
     *  The old 13 was Twitch 28's size. Until the user moves the slider, chat is not resized at
     *  all and every view keeps the size Twitch gave it (see [apply]). */
    const val FONT_SIZE_DEFAULT = 14
    const val KEY_ALT_BG = "alternate_background"

    /** Subtle lift over Twitch's chat background, matching the original mod's shading. */
    private const val ALT_COLOR = 0x14FFFFFF

    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastActivity: java.lang.ref.WeakReference<Activity>? = null

    /** Text size in px Twitch originally gave each message view, recorded before we ever write to
     *  it, so an unset slider (or one a sync removed) puts Twitch's own size back. Kept in px:
     *  converting sp back through scaledDensity is wrong under Android 14's non-linear font scaling. */
    private val ORIG_SIZE = java.util.WeakHashMap<TextView, Float>()

    fun install(lp: LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                lastActivity = java.lang.ref.WeakReference(act)
                schedule(act)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", hook)
            log("ChatAppearance installed")
        }.onFailure { log("ChatAppearance hook failed: $it") }
    }

    /** Re-apply right after a slider or toggle changes in the settings menu. */
    fun reapply() {
        val act = lastActivity?.get() ?: return
        handler.post { runCatching { apply(act) } }
    }

    private fun schedule(act: Activity) {
        for (delay in longArrayOf(0L, 400L, 1000L)) {
            handler.postDelayed({ runCatching { apply(act) }.onFailure { log("CA apply: $it") } }, delay)
        }
        if (GL_WIRED.add(act)) {
            runCatching {
                val root = act.window?.decorView ?: return
                root.viewTreeObserver.addOnGlobalLayoutListener { runCatching { apply(act) } }
            }
        }
    }

    private fun apply(act: Activity) {
        // 0 = the user never picked a size (or a sync removed it): leave every view at the size
        // Twitch gave it, as the original mod did. Forcing a default instead shrank Twitch 31's
        // 14sp chat for everyone, and resized the One Chat overlay and AutoMod notices too.
        val stored = Settings.getInt(KEY_FONT_SIZE, 0)
        val sizeSp = if (stored > 0) stored.coerceIn(8, 24).toFloat() else 0f
        val altBg = Settings.get(KEY_ALT_BG, false)
        val root = act.window?.decorView ?: return
        // With the toggle off nothing reads the shading anchors, so drop them now instead of
        // leaving them to wait for the garbage collector.
        if (!altBg && !SHADED_FOR.isEmpty()) SHADED_FOR.clear()

        // Visible rows, grouped per chat list and shaded after the walk (see [shadeRows]). Only
        // built while the toggle is on, so the common case allocates nothing.
        var rows: HashMap<ViewParent?, ArrayList<View>>? = null
        var nItem = 0; var nMessage = 0; var nContainer = 0
        walk(root) { v ->
            when (Res.entry(v)) {
                // Ordinary chat lines carry chat_message_item; the One Chat overlay and the
                // user-notice rows carry chat_message. Both are message TextViews, so both are
                // sized -- matching only one of them is what left the slider inert.
                "chat_message_item" -> { nItem++; if (v is TextView) applyFontSize(v, sizeSp) }
                "chat_message" -> { nMessage++; if (v is TextView) applyFontSize(v, sizeSp) }
                "chat_message_container" -> {
                    nContainer++
                    // Rows the blacklist has collapsed are not on screen, so counting them would
                    // walk the alternation out of step with what is actually visible.
                    if (v.visibility == View.VISIBLE) {
                        if (!altBg) applyAltBackground(v, false)
                        else (rows ?: HashMap<ViewParent?, ArrayList<View>>().also { rows = it })
                            .getOrPut(v.parent) { ArrayList() }.add(v)
                    }
                }
            }
        }
        rows?.values?.forEach { shadeRows(it) }
        reportMatches(nItem, nMessage, nContainer)
    }

    /** Last reported match counts, so a change is logged once instead of every layout pass.
     *  Kept as ints rather than a string so the common case costs a comparison, not an allocation:
     *  this runs on every pass, and chat lays out constantly. */
    @Volatile private var lastItem = -1
    @Volatile private var lastMessage = -1
    @Volatile private var lastContainer = -1

    /** Which of the three anchors the live tree actually carries. Reported on CHANGE, because the
     *  counts differ per screen and a one-shot would describe whichever screen happened to be up
     *  first. This is the check that showed the old mapping never matched the main chat list at
     *  all: in a live chat it reads chat_message=0 while the other two are in double figures. */
    private fun reportMatches(item: Int, message: Int, container: Int) {
        if (item == 0 && message == 0 && container == 0) return
        if (item == lastItem && message == lastMessage && container == lastContainer) return
        lastItem = item; lastMessage = message; lastContainer = container
        log("CA anchors: chat_message_item=$item chat_message=$message " +
            "chat_message_container=$container")
    }

    /** [sizeSp] 0 puts back the size Twitch gave the view; anything else forces that size. */
    private fun applyFontSize(tv: TextView, sizeSp: Float) {
        // Recorded on first sight, which is always before anything below has written to the view.
        val orig = ORIG_SIZE.getOrPut(tv) { tv.textSize }
        // Compared in px against exactly what setTextSize will produce, which also follows
        // Android 14's non-linear font scaling.
        val wantPx = if (sizeSp > 0f) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, tv.resources.displayMetrics)
        } else orig
        if (kotlin.math.abs(tv.textSize - wantPx) > 0.5f) {
            if (sizeSp > 0f) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            else tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, orig)
        }
    }

    /** Tracks rows we've tinted, so turning the toggle off can clear them. While the toggle is on
     *  it is also each row's place in the alternation, read back by [shadeRows]. */
    private val TINTED = java.util.WeakHashMap<View, Boolean>()

    /** The message each row was last shaded for: its chat_message_item text object, compared by
     *  identity. A row still holding the same object is still showing the same message.
     *  The text is held weakly on purpose. Twitch's text is a Spannable, and the TextView hangs a
     *  watcher on it that points back at the TextView, whose parent is the row, the very key here.
     *  A strong value would therefore keep its own key alive, so no row (or the chat list and
     *  Activity behind it) would ever be freed. The TextView holds its current text strongly, so
     *  the weak copy lasts exactly as long as the comparison can still match. */
    private val SHADED_FOR = java.util.WeakHashMap<View, java.lang.ref.WeakReference<CharSequence>>()

    /**
     * Alternate the shade down one chat list, tied to the messages rather than to screen slots.
     * Counting rows alone made every line swap shade on each new message, because every row's
     * position moved by one. Instead, the topmost row still showing the message it was shaded for
     * keeps its shade, and the rest alternate out from it, so a message keeps its shade as it
     * scrolls. New and recycled rows simply take whatever the alternation gives them. With no
     * such row (first pass, or everything rebound) the top row starts unshaded.
     */
    private fun shadeRows(rows: ArrayList<View>) {
        // walk() visits children last to first, so put them in screen order.
        rows.sortBy { it.top }
        val texts = Array(rows.size) { messageText(rows[it]) }
        var offset = 0
        for (i in rows.indices) {
            val t = texts[i] ?: continue
            if (SHADED_FOR[rows[i]]?.get() === t) {
                offset = (if (TINTED[rows[i]] == true) 1 else 0) xor (i and 1)
                break
            }
        }
        for (i in rows.indices) {
            val t = texts[i]
            if (t == null) SHADED_FOR.remove(rows[i])
            // Only re-wrap when the message changed, so a steady layout pass allocates nothing.
            else if (SHADED_FOR[rows[i]]?.get() !== t) SHADED_FOR[rows[i]] = java.lang.ref.WeakReference(t)
            applyAltBackground(rows[i], ((i and 1) xor offset) == 1)
        }
    }

    /** The row's message text: its chat_message_item, a direct child in both row layouts. */
    private fun messageText(row: View): CharSequence? {
        if (row !is ViewGroup) return null
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c is TextView && Res.entry(c) == "chat_message_item") return c.text
        }
        return null
    }

    private fun applyAltBackground(row: View, want: Boolean) {
        val had = TINTED[row] == true
        if (want == had) return
        if (want) {
            row.setBackgroundColor(ALT_COLOR)
            TINTED[row] = true
        } else {
            // Rows are recycled, so clear to transparent rather than trying to restore a
            // per-row original — Twitch draws message rows on the list background.
            row.setBackgroundColor(Color.TRANSPARENT)
            TINTED[row] = false
        }
    }

    private inline fun walk(root: View, action: (View) -> Unit) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            action(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
        }
    }
}
