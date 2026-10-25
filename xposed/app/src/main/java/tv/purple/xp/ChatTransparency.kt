package tv.purple.xp

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Landscape theater chat-panel transparency (task #10).
 *
 * Obfuscation-immune: we never name a Twitch class. We hook the framework
 * android.app.Activity lifecycle (onResume / onConfigurationChanged) and, when the device
 * is in LANDSCAPE, walk the decor view to find the chat side-panel by RESOURCE ENTRY NAME
 * (package-independent — see EmoteAutocomplete for the same trick) and fade its BACKGROUND to the
 * user's chosen opacity. Only the background drawable is faded, never View.alpha, so chat text and
 * emotes stay fully opaque and readable over the see-through panel.
 * In PORTRAIT we restore full opacity so the normal layout is unchanged.
 *
 * Applied on a short post-delay because the theater chat panel is inflated asynchronously
 * after the player goes fullscreen. [reapply] lets the settings slider update live.
 */
object ChatTransparency {

    /** Chat-panel container resource entry names, OUTERMOST first. In landscape theater the chat
     *  is nested chat_wrapper > chat_view_container > chat_view_delegate_root (all same bounds).
     *  The dark panel BACKGROUND drawable lives on an outer container, NOT on delegate_root — so
     *  dimming delegate_root alone faded only the text. We dim the OUTERMOST present container so
     *  the whole panel (background + text) composites once at the chosen alpha, and reset the
     *  inner ones to 1f to avoid compounding the alpha on nested views. */
    private val PANEL_IDS = listOf(
        "chat_wrapper",
        "chat_view_container",
        "chat_view_delegate_root"
    )

    /** One-shot diagnostic: log which chat views carry a background drawable. */
    @Volatile private var bgLogged = false

    @Volatile private var lastActivity: WeakReference<Activity>? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun install(lp: LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                lastActivity = WeakReference(act)
                scheduleApply(act)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", hook)
            XposedBridge.hookAllMethods(Activity::class.java, "onConfigurationChanged", hook)
            hookGuideline(lp)
            log("ChatTransparency installed (Activity onResume/onConfigurationChanged)")
        }.onFailure { log("ChatTransparency hook failed: $it") }
    }

    /** Re-apply to the last-known foreground activity (called when the slider changes). */
    fun reapply() {
        val act = lastActivity?.get() ?: return
        scheduleApply(act)
    }

    /** Activities we've attached a global-layout listener to (reapply on every layout). */
    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )

    private fun scheduleApply(act: Activity) {
        // The chat fragment inflates well AFTER onResume (network-gated), and Twitch re-renders
        // can reset alpha. So besides a few timed retries, attach a global-layout listener that
        // re-applies on every layout pass (cheap: a guarded tree walk) for the activity's life.
        for (delay in longArrayOf(0L, 250L, 750L, 1500L, 3000L)) {
            handler.postDelayed({ runCatching { apply(act) }.onFailure { log("CT apply: $it") } }, delay)
        }
        if (GL_WIRED.add(act)) {
            runCatching {
                val root = act.window?.decorView ?: return
                root.viewTreeObserver.addOnGlobalLayoutListener {
                    runCatching { apply(act) }
                }
            }
        }
    }

    private fun apply(act: Activity) {
        val landscape = act.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val opacitySetting = Settings.getInt(Settings.KEY_CHAT_OPACITY, Settings.CHAT_OPACITY_DEFAULT)
        // Landscape theater: apply the user's opacity. Portrait: always full opacity (restore).
        val alpha = if (landscape) (opacitySetting.coerceIn(0, 100)) / 100f else 1f
        val root = act.window?.decorView ?: return

        // Collect every matching container by name (PANEL_IDS is already ordered outermost-first).
        val matches = HashMap<String, View>()
        walk(root) { v ->
            val entry = Res.entry(v)
            if (entry != null && entry in PANEL_IDS) matches[entry] = v
        }
        if (matches.isEmpty()) return

        // One-shot: report which chat container holds the panel background drawable.
        if (landscape && !bgLogged) {
            bgLogged = true
            for (name in PANEL_IDS) matches[name]?.let { v ->
                log("CT bg-probe '$name': background=${v.background?.javaClass?.simpleName ?: "null"} alpha=${v.alpha}")
            }
        }

        // Fade ONLY the dark panel background, never the chat content.
        //
        // Setting View.alpha would composite the whole subtree — background, text and emotes — at
        // the chosen opacity, which washes out the messages. Instead every matched view is held at
        // alpha 1f and the opacity is pushed into the background DRAWABLE alpha, so the panel goes
        // see-through while text and emotes stay fully opaque on top of it.
        val bgAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
        for (name in PANEL_IDS) {
            val v = matches[name] ?: continue
            if (v.alpha != 1f) v.alpha = 1f   // undo whole-view dimming from earlier versions
            // mutate() so we never alter a ColorDrawable shared with other Twitch views.
            val bg = v.background?.mutate() ?: continue
            if (bg.alpha != bgAlpha) bg.alpha = bgAlpha
        }

        // Portrait passes null, which restores Twitch's own split.
        val widthPct = if (landscape) Settings.getInt(KEY_CHAT_WIDTH, CHAT_WIDTH_DEFAULT) else null
        matches["chat_wrapper"]?.let { applyChatWidth(it, widthPct) }
    }

    // -----------------------------------------------------------------------------------
    // Landscape chat panel width.
    //
    // WHY NOT layoutParams.width: in the landscape theatre layout both the chat panel and the
    // player pane are declared layout_width="0dp" (MATCH_CONSTRAINT) inside a ConstraintLayout:
    //
    //   player_pane    : constraintEnd_toStartOf = chat_guideline
    //   chat_wrapper   : constraintStart_toEndOf = chat_guideline, constraintEnd_toEndOf = parent
    //   chat_guideline : vertical Guideline with layout_constraintGuide_percent
    //
    // With 0dp the solver IGNORES the stored width and derives it from those anchors, so the two
    // panes are complementary by construction and meet at the guideline. Writing a width switched
    // the panel to a fixed-width solve anchored at its START edge (the guideline), which is why it
    // grew rightwards off the screen instead of moving left — chat text got clipped mid-word.
    //
    // The one real lever is the guideline percent. Moving it resizes the player pane, the chat
    // panel and the several other siblings pinned to the same guideline in a single layout pass.
    // guidePercent is the fraction LEFT of the guideline, so a chat width of pct maps to
    // 1 - pct/100. That also holds under RTL: the guideline mirrors, and the panel (anchored
    // start_toEnd) then occupies the left band — still (1 - p) wide either way.
    //
    // androidx is touched by REFLECTION ONLY, never named at compile time. The layout XML inflates
    // ConstraintLayout and Guideline by fully-qualified string, so R8 cannot rename them without
    // rewriting that XML. Every access is still runCatching-guarded, so a future obfuscated or
    // Compose-based build degrades to a clean no-op rather than breaking chat.
    // -----------------------------------------------------------------------------------

    const val KEY_CHAT_WIDTH = "landscape_chat_size_v3"
    const val CHAT_WIDTH_DEFAULT = 30
    private const val CHAT_WIDTH_MIN = 10
    private const val CHAT_WIDTH_MAX = 50

    /** Twitch has two landscape chat widths: its normal one and an expanded one (swipe the chat
     *  left; swipe right to go back). Each gets its own setting. */
    const val KEY_CHAT_WIDTH_WIDE = "landscape_chat_size_wide"
    const val CHAT_WIDTH_WIDE_DEFAULT = 50
    const val CHAT_WIDTH_WIDE_MIN = 25
    const val CHAT_WIDTH_WIDE_MAX = 75
    /** Twitch's own widths are about 20% and 50%; anything from 35% up is its expanded one. */
    private const val WIDE_FROM = 0.35f

    /** Whether each guideline is in Twitch's expanded state, judged from Twitch's own writes. */
    private val wideMode = java.util.WeakHashMap<View, Boolean>()
    @Volatile private var writing = false

    /** Twitch's own split, read from geometry before our first write, so turning the feature
     *  off restores the exact shipped layout. Weak-keyed: never pins a dead Activity. */
    private val originalGuidePercent = java.util.WeakHashMap<View, Float>()

    /** Last percent we wrote per guideline, so a settled layout isn't rewritten every pass. */
    private val lastWritten = java.util.WeakHashMap<View, Float>()

    /** Cached Guideline.setGuidelinePercent(float). */
    @Volatile private var mSetGuidePercent: java.lang.reflect.Method? = null
    @Volatile private var structureLogged = false

    /**
     * Resize the landscape theatre chat panel to [pct] percent of screen width.
     *
     * @param panel the view matched by resource entry name "chat_wrapper"
     * @param pct   10..50, or null to RESTORE Twitch's split (portrait / feature off)
     *
     * Returns cleanly with the layout untouched if the expected ConstraintLayout +
     * chat_guideline structure isn't present.
     *
     * Deliberately uses NO field reflection. ConstraintLayout.LayoutParams is obfuscated at
     * runtime (it shows up as e.g. "m69") and all of its fields are renamed, so guidePercent /
     * startToEnd cannot be reached by name. Two things do survive R8, because the layout XML
     * inflates them by fully-qualified string: the Guideline class itself and its public
     * setGuidelinePercent(float). The CURRENT split is read from geometry instead of a field --
     * a vertical guideline's laid-out x over the parent width IS the percent.
     */
    private fun applyChatWidth(panel: View, pct: Int?) {
        runCatching {
            val parent = panel.parent as? ViewGroup ?: return
            var guide: View? = null
            for (i in 0 until parent.childCount) {
                val c = parent.getChildAt(i)
                // A Guideline sets itself GONE in its constructor -- don't skip GONE views.
                val e = Res.entry(c)
                if (e == "chat_guideline") { guide = c; break }
            }
            val guideline = guide ?: return
            val width = parent.width
            if (width <= 0) return                       // not laid out yet

            val current = guideline.left.toFloat() / width

            // Snapshot Twitch's own split once, before we ever write. Guard the range so a
            // half-laid-out frame (guideline at 0, or flush right) is never mistaken for it.
            if (!originalGuidePercent.containsKey(guideline)) {
                if (current <= 0.05f || current >= 0.95f) return
                originalGuidePercent[guideline] = current
                if (!structureLogged) {
                    structureLogged = true
                    log("CW structure ok: original split=" + current)
                }
            }
            val original = originalGuidePercent[guideline] ?: return

            // Twitch moves the guideline itself (when the stream settles, when the controls show,
            // and between its normal and expanded widths). Whenever it is somewhere we did not put
            // it, that was Twitch: read which of its two widths it chose, then apply ours for it.
            val last = lastWritten[guideline]
            if (last == null || kotlin.math.abs(current - last) > 0.005f)
                wideMode[guideline] = (1f - current) >= WIDE_FROM
            val target = if (pct == null) original else targetFor(wideMode[guideline] == true, pct)
            if (kotlin.math.abs(current - target) < 0.005f) { lastWritten[guideline] = target; return }

            val m = mSetGuidePercent ?: runCatching {
                guideline.javaClass.getMethod("setGuidelinePercent", java.lang.Float.TYPE)
            }.getOrNull()?.also { mSetGuidePercent = it } ?: return

            writing = true
            try { m.invoke(guideline, target) } finally { writing = false }
            lastWritten[guideline] = target
        }.onFailure { log("CW applyChatWidth: " + it) }
    }


    /** The guideline position (fraction left of it) for the normal or expanded chat width. */
    private fun targetFor(wide: Boolean, narrowPct: Int): Float {
        val pct = if (wide) Settings.getInt(KEY_CHAT_WIDTH_WIDE, CHAT_WIDTH_WIDE_DEFAULT).coerceIn(CHAT_WIDTH_WIDE_MIN, CHAT_WIDTH_WIDE_MAX)
                  else narrowPct.coerceIn(CHAT_WIDTH_MIN, CHAT_WIDTH_MAX)
        return 1f - pct / 100f
    }

    /** When Twitch itself sets the chat guideline, swap in our width for the state it picked. */
    private fun hookGuideline(lp: LoadPackageParam) = runCatching {
        val g = lp.classLoader.loadClass("androidx.constraintlayout.widget.Guideline")
        XposedBridge.hookAllMethods(g, "setGuidelinePercent", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (writing) return
                val v = p.thisObject as? View ?: return
                if (Res.entry(v) != "chat_guideline") return
                if (v.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
                val asked = p.args.getOrNull(0) as? Float ?: return
                if (asked <= 0.05f || asked >= 0.95f) return     // chat hidden or full: leave it
                val wide = (1f - asked) >= WIDE_FROM
                wideMode[v] = wide
                val target = targetFor(wide, Settings.getInt(KEY_CHAT_WIDTH, CHAT_WIDTH_DEFAULT))
                p.args[0] = target
                lastWritten[v] = target
            }
        })
    }.onFailure { log("CW guideline hook: $it") }

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
