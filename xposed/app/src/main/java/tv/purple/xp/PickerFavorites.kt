package tv.purple.xp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Favorites section inside Twitch's NATIVE emote picker (per request: "a favorites section in
 * the emotes picker for only twitch and sub emotes that persists between chats").
 *
 * Obfuscation-immune anchors (resource ENTRY names survive R8; the resource table package is
 * still "tv.twitch.android.app" even after the app package rename, so getResourceEntryName is
 * used rather than getIdentifier):
 *   - emote_palette  = the picker's android.widget.GridView of emote cells
 *   - emote_picker   = the FrameLayout container we inject a favorites strip into
 *
 * We hook the framework GridView constructor (GridView is rare in this app, so this is cheap),
 * and when the inflated GridView turns out to be "emote_palette" we add a pinned horizontal
 * favorites strip at the TOP of the "emote_picker" container and pad the grid down so nothing is
 * hidden behind it. Tapping a favorite inserts it into the chat input (insert only, never sends —
 * same as the native picker). Long-pressing a favorite removes it. Favorites come from
 * [Favorites] (Twitch/sub only) and persist across channels via SharedPreferences.
 */
object PickerFavorites {

    /** The "Favorite emotes" toggle. It was in the menu and marked working but nothing read it,
     *  so the strip appeared regardless. */
    const val KEY_ENABLED = "picker_favorites"

    private fun enabled() = Settings.get(KEY_ENABLED, true)

    /** emote_picker containers we've already injected into. */
    private val INJECTED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<ViewGroup, Boolean>()
    )


    /** The currently-attached favorites chip row + its context, for live refresh on change. */
    @Volatile private var curRow: WeakReference<LinearLayout>? = null
    @Volatile private var curCtx: WeakReference<Context>? = null
    @Volatile private var listenerWired = false

    fun install(lp: LoadPackageParam) {
        // The emote_palette / emote_picker views are NOT real android.widget.GridView instances
        // at runtime — uiautomator only reports "android.widget.GridView" because the custom view
        // reports it as its accessibility class name. Hooking framework GridView (ctor/onMeasure/
        // onAttachedToWindow) therefore never fires. Instead we anchor class-agnostically: a single
        // ViewTreeObserver.OnGlobalLayoutListener on the chat-input window root (registered from
        // EmoteAutocomplete.attach via attachRoot) scans for the "emote_picker" container by
        // resource ENTRY name whenever the layout changes (which includes the picker expanding),
        // and injects the favorites strip the first time it appears.
        if (!listenerWired) {
            listenerWired = true
            Favorites.addListener {
                val row = curRow?.get() ?: return@addListener
                row.post { runCatching { rebuild(row) } }
            }
        }
        installDialogHook()
        log("PF favorites picker hook installed (global-layout + dialog anchor)")
    }

    /** Roots we've already attached a global-layout listener to (one per chat window). */
    private val ROOTED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    /** Cached emote_picker container, so the layout listener is near-free once injected. */
    @Volatile private var lastContainer: WeakReference<ViewGroup>? = null

    /**
     * Register a global-layout listener on the chat-input window root. Called by
     * [EmoteAutocomplete.attach] with the chat input. On each layout pass, if the favorites strip
     * isn't already injected into the live emote_picker, scan the tree for it and inject.
     */
    fun attachRoot(input: View) {
        val root = runCatching { input.rootView }.getOrNull() ?: return
        if (!ROOTED.add(root)) return
        runCatching {
            root.viewTreeObserver.addOnGlobalLayoutListener {
                runCatching {
                    val cached = lastContainer?.get()
                    if (cached != null && cached.isAttachedToWindow && INJECTED.contains(cached)) return@addOnGlobalLayoutListener
                    val container = findByEntry(root, "emote_picker") as? ViewGroup ?: return@addOnGlobalLayoutListener
                    // From 31.x a legacy emote_picker container is still inflated in the activity
                    // but never shown, while the real picker is a Compose sheet in its own window.
                    // Injecting into the hidden one looked like success and showed the user nothing.
                    if (!container.isShown || container.width == 0 || container.height == 0)
                        return@addOnGlobalLayoutListener
                    lastContainer = WeakReference(container)
                    onPaletteReady(container)
                }.onFailure { log("PF layout scan error: $it") }
            }
            log("PF global-layout listener attached to chat root")
        }.onFailure { log("PF attachRoot error: $it") }
    }

    /** Depth-first search for the first descendant whose resource entry name == [entry]. */
    private fun findByEntry(root: View, entry: String): View? {
        val e = Res.entry(root)
        if (e == entry) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByEntry(root.getChildAt(i) ?: continue, entry)?.let { return it }
            }
        }
        return null
    }

    private fun onPaletteReady(container: ViewGroup) {
        if (!enabled()) return
        if (!INJECTED.add(container)) { // already injected; just refresh contents
            curRow?.get()?.let { rebuild(it) }
            return
        }
        val ctx = container.context ?: return
        val stripH = dp(ctx, 50f)

        // Build: [ ★ Favorites ][ scrollable chip row ]
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val star = TextView(ctx).apply {
            text = "★"
            setTextColor(Color.parseColor("#FFD24A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(ctx, 10f), 0, dp(ctx, 8f), 0)
        }
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FF18141D"))
            addView(star, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        // Pin the strip to the TOP of the picker; push the grid down so it isn't hidden behind it.
        container.addView(strip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, stripH, Gravity.TOP
        ))
        // Pad the scrollable emote grid (emote_palette) down by the strip height so its first row
        // isn't hidden behind the strip. Found by entry name — it's not a real GridView at runtime.
        val palette = findByEntry(container, "emote_palette")
        runCatching {
            (palette as? ViewGroup)?.clipToPadding = true
            palette?.setPadding(
                palette.paddingLeft, palette.paddingTop + stripH,
                palette.paddingRight, palette.paddingBottom
            )
        }

        curRow = WeakReference(row)
        curCtx = WeakReference(ctx)
        rebuild(row)

        // Refresh on every reopen (the picker view may be reused while favorites changed).
        palette?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                curRow?.get()?.let { rebuild(it) }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        log("PF favorites strip injected into emote_picker")
    }

    // ---------------------------------------------------------------- newer picker (dialog)
    //
    // From Twitch 31.x the emote picker is a Jetpack Compose bottom sheet in its OWN dialog
    // window, so the activity-root layout listener above can never see it, and the emote_picker /
    // emote_palette view ids no longer exist at runtime at all. Confirmed on device: the sheet's
    // whole subtree is a single androidx.compose.ui.platform.ComposeView.
    //
    // What IS still a real view with a real id is the Material sheet container itself,
    // design_bottom_sheet. We reach it by hooking android.app.Dialog.show(), which is framework
    // and so cannot be obfuscated.

    @Volatile private var dialogHooked = false

    private fun installDialogHook() {
        if (dialogHooked) return
        dialogHooked = true
        runCatching {
            // Anchored on ViewGroup.addView rather than Dialog.show: the picker sheet never came
            // through Dialog.show on device, and addView is the moment the sheet container is
            // actually put into its window. Cost is one int compare per addView, which is rare
            // enough not to matter, and the id is resolved once.
            // Note when the chat input's emote button is tapped. The Compose picker draws its
            // tab labels without any real views, so the sheet cannot be recognised by its
            // contents; "a bottom sheet that opens right after the emote button was tapped" can.
            XposedBridge.hookAllMethods(View::class.java, "performClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    val id = v.id
                    if (id == View.NO_ID) return
                    if (id == emoteButtonId(v)) pickerTappedAt = android.os.SystemClock.uptimeMillis()
                }
            })
            // Long-press an emote in the picker to favorite it. Promised by the strip's hint and
            // the README from the start but never actually wired. Each picker cell is a real
            // ImageView with the emote_icon id whose description is the emote's name, so this
            // needs nothing obfuscated. Only Twitch and sub emotes can be favorites; anything
            // else is passed through untouched so Twitch's own long-press still works there.
            XposedBridge.hookAllMethods(View::class.java, "performLongClick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val v = param.thisObject as? View ?: return
                    if (v.id == View.NO_ID || v.id != emoteIconId(v)) return
                    // Animated or locked emotes read "KEKW, Animated emote"; the name is before the comma.
                    val name = v.contentDescription?.toString()?.substringBefore(", ")?.trim().orEmpty()
                    if (name.isEmpty() || !Favorites.isFavoritable(name)) return
                    val now = Favorites.toggle(name)
                    toast(v.context, if (now) "★ Favorited $name" else "Removed $name")
                    param.result = true
                }
            })
            XposedBridge.hookAllMethods(ViewGroup::class.java, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val child = param.args.getOrNull(0) as? View ?: return
                    val id = child.id
                    if (id == View.NO_ID) return
                    val want = sheetId(child)
                    if (want == 0 || id != want) return
                    log("PF sheet container added to window")
                    val sheet = child as? ViewGroup ?: return
                    runCatching { PickerSend.onSheet(sheet) }.onFailure { log("PS sheet: $it") }
                    // Compose fills the sheet a frame or two later, so the tab labels we match on
                    // do not exist yet at this point.
                    sheet.post { runCatching { tryInjectSheet(sheet) }
                        .onFailure { log("PF sheet inject error: $it") } }
                }
            })
        }.onFailure { log("PF dialog hook failed: $it") }
    }

    @Volatile private var sheetIdCache = -1
    @Volatile private var emoteBtnIdCache = -1
    @Volatile private var pickerTappedAt = 0L

    @Volatile private var emoteIconIdCache = -1

    private fun emoteIconId(v: View): Int {
        if (emoteIconIdCache >= 0) return emoteIconIdCache
        emoteIconIdCache = runCatching {
            v.resources.getIdentifier("emote_icon", "id", v.context.packageName)
        }.getOrDefault(0)
        return emoteIconIdCache
    }

    private fun emoteButtonId(v: View): Int {
        if (emoteBtnIdCache >= 0) return emoteBtnIdCache
        emoteBtnIdCache = runCatching {
            v.resources.getIdentifier("emoticon_picker", "id", v.context.packageName)
        }.getOrDefault(0)
        return emoteBtnIdCache
    }

    /** A sheet that opens within this long of the emote button being tapped is the picker. */
    private const val TAP_WINDOW_MS = 3000L

    private fun sheetId(v: View): Int {
        if (sheetIdCache >= 0) return sheetIdCache
        sheetIdCache = runCatching {
            v.resources.getIdentifier("design_bottom_sheet", "id", v.context.packageName)
        }.getOrDefault(0)
        return sheetIdCache
    }

    private fun tryInjectSheet(sheet: ViewGroup) {
        if (INJECTED.contains(sheet)) { curRow?.get()?.let { rebuild(it) }; return }
        val tapped = android.os.SystemClock.uptimeMillis() - pickerTappedAt < TAP_WINDOW_MS
        if (!tapped && !isEmotePickerSheet(sheet)) return
        injectIntoSheet(sheet)
    }

    /**
     * Tell the emote picker apart from every other bottom sheet in the app.
     *
     * design_bottom_sheet is a generic Material id, so matching it alone would put a favorites
     * strip on chat settings and everything else. The picker is identified by its own tab labels,
     * read from the app's string table so this still holds in any language.
     */
    private fun isEmotePickerSheet(sheet: ViewGroup): Boolean {
        val res = sheet.resources ?: return false
        val pkg = sheet.context?.packageName ?: return false
        val wanted = listOf("giphy_tab_emotes", "giphy_tab_gifs").mapNotNull { name ->
            runCatching {
                val id = res.getIdentifier(name, "string", pkg)
                if (id == 0) null else res.getString(id)
            }.getOrNull()
        }
        if (wanted.isEmpty()) return false
        var hit = false
        walk(sheet) { v ->
            if (!hit && v is TextView) {
                val t = runCatching { v.text?.toString() }.getOrNull()
                if (t != null && wanted.any { it.equals(t, ignoreCase = true) }) hit = true
            }
        }
        return hit
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

    private fun injectIntoSheet(sheet: ViewGroup) {
        if (!enabled()) return
        if (!INJECTED.add(sheet)) return
        val ctx = sheet.context ?: return
        val stripH = dp(ctx, 50f)
        val (strip, row) = buildStrip(ctx)

        sheet.addView(strip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, stripH, Gravity.TOP
        ))
        // Push the composed picker down rather than letting the strip sit over it. A margin is
        // used instead of padding because the sheet is a FrameLayout and Compose lays its own
        // content out inside whatever box it is given.
        for (i in 0 until sheet.childCount) {
            val child = sheet.getChildAt(i) ?: continue
            if (child === strip) continue
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            if (lp.topMargin < stripH) { lp.topMargin = stripH; child.layoutParams = lp }
        }

        curRow = WeakReference(row)
        curCtx = WeakReference(ctx)
        rebuild(row)
        log("PF favorites strip injected into the picker bottom sheet")
    }

    /** The strip itself, shared by both the old in-layout picker and the newer sheet. */
    private fun buildStrip(ctx: Context): Pair<LinearLayout, LinearLayout> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val star = TextView(ctx).apply {
            text = "★"
            setTextColor(Color.parseColor("#FFD24A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(ctx, 10f), 0, dp(ctx, 8f), 0)
        }
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FF18141D"))
            addView(star, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        return strip to row
    }

    /** Repopulate the chip row from the persisted favorites list. */
    private fun rebuild(row: LinearLayout) {
        val ctx = row.context ?: return
        row.removeAllViews()
        val favs = Favorites.list()
        if (favs.isEmpty()) {
            row.addView(TextView(ctx).apply {
                text = "Long-press an emote to add it here"
                setTextColor(Color.parseColor("#99FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER_VERTICAL
            })
            return
        }
        val sizePx = dp(ctx, 34f)
        // Favorites whose picture has not loaded yet: fetch those now and redraw the bar.
        val waiting = favs.filter { Favorites.isAvailable(it) && favDrawable(it) == null }
        if (waiting.isNotEmpty()) EmoteRepo.wantPictures(waiting) {
            row.post { runCatching { rebuild(row) } }
        }
        for (name in favs) {
            // Not usable in this chat (a 7TV/BTTV/FFZ word this channel does not have): shown
            // greyed out and not insertable, but still removable with a long-press.
            val usable = Favorites.isAvailable(name)
            val d = if (usable) favDrawable(name) else null
            val cell: View = if (d != null) {
                ImageView(ctx).apply {
                    setImageDrawable(d)
                    if (d is AnimatedImageDrawable) runCatching { d.start() }
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                }
            } else {
                // No cached image (e.g. emote not in the current channel's set) — show the name.
                TextView(ctx).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val holder = FrameLayout(ctx).apply {
                setPadding(dp(ctx, 6f), 0, dp(ctx, 6f), 0)
                isClickable = true
                addView(cell)
                if (!usable) alpha = 0.35f
                setOnClickListener {
                    if (!usable) { toast(ctx, "$name is not available in this chat"); return@setOnClickListener }
                    if (!EmoteAutocomplete.insertEmote(name))
                        toast(ctx, "Tap the chat box first")
                }
                setOnLongClickListener {
                    Favorites.remove(name)
                    toast(ctx, "Removed $name")
                    true
                }
            }
            row.addView(holder)
        }
    }

    /** A drawable for a favorite emote: fresh animated copy if animated, else the cached static
     *  suggestion drawable (a fresh copy so it isn't shared across views), else null. */
    private fun favDrawable(name: String): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            EmoteRepo.animBytes.containsKey(name)) {
            EmoteRepo.makeAnimated(name)?.let { return it }
        }
        EmoteRepo.suggestDrawables[name]?.let { return it.constantState?.newDrawable()?.mutate() ?: it }
        EmoteRepo.drawables[name]?.let { return it.constantState?.newDrawable()?.mutate() ?: it }
        return null
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
