package tv.purple.xp

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
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
    const val FONT_SIZE_DEFAULT = 13
    const val KEY_ALT_BG = "alternate_background"

    /** Subtle lift over Twitch's chat background, matching the original mod's shading. */
    private const val ALT_COLOR = 0x14FFFFFF

    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastActivity: java.lang.ref.WeakReference<Activity>? = null

    /** Font size Twitch originally gave each message view, so the slider can be undone. */
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
        val sizeSp = Settings.getInt(KEY_FONT_SIZE, FONT_SIZE_DEFAULT).coerceIn(8, 24).toFloat()
        val altBg = Settings.get(KEY_ALT_BG, false)
        val root = act.window?.decorView ?: return

        // Row index drives the alternating shade. Counting matched rows in tree order is stable
        // enough here: children of the chat recycler are laid out in message order.
        var rowIndex = 0
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
                        applyAltBackground(v, altBg, rowIndex)
                        rowIndex++
                    }
                }
            }
        }
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

    private fun applyFontSize(tv: TextView, sizeSp: Float) {
        val density = tv.resources.displayMetrics.scaledDensity
        if (!ORIG_SIZE.containsKey(tv)) ORIG_SIZE[tv] = tv.textSize / density
        val currentSp = tv.textSize / density
        if (kotlin.math.abs(currentSp - sizeSp) > 0.1f) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        }
    }

    /** Tracks rows we've tinted, so turning the toggle off can clear them. */
    private val TINTED = java.util.WeakHashMap<View, Boolean>()

    private fun applyAltBackground(row: View, enabled: Boolean, index: Int) {
        val want = enabled && (index % 2 == 1)
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
