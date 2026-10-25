package tv.purple.xp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import kotlin.math.abs
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * No-colon emote autocomplete + standalone settings entry point.
 *
 * Obfuscation-immune anchors only:
 *   - chat input  = android.widget.MultiAutoCompleteTextView with resource id "message_input"
 *   - settings    = long-press the "emoticon_picker" ImageView (resolved by resource entry name)
 *
 * We hook the framework MultiAutoCompleteTextView.onAttachedToWindow so we catch the input
 * regardless of which obfuscated Twitch class owns it. On each text change we compute the
 * word around the cursor and, if length>=2 and enabled, pop a horizontal strip of emote
 * chips above the input. Tapping a chip replaces that word with "<name> " — for 7TV/BTTV/FFZ
 * that plain text IS sending; for Twitch emotes it completes the native token.
 */
object EmoteAutocomplete {

    private const val INPUT_CLASS = "android.widget.MultiAutoCompleteTextView"
    private const val MIN_LEN = 2

    /** How long the suggestion strip stays up after the last keystroke, in seconds. Four was too
     *  short in practice: emote images are fetched asynchronously, so the strip could vanish
     *  before the pictures it was meant to show had even arrived. */
    const val KEY_TIMEOUT = "suggest_timeout_s"
    const val TIMEOUT_DEFAULT = 12

    private fun stripTimeoutMs(): Long =
        Settings.getInt(KEY_TIMEOUT, TIMEOUT_DEFAULT).coerceIn(2, 30) * 1000L

    /** Per-name cache of fresh animated drawables for the strip, so we don't re-decode ~30
     *  AnimatedImageDrawables on every keystroke. Each chip ImageView is its own view, but a
     *  given name shows in at most one chip at a time, so one cached instance per name is safe.
     *  Cleared if it grows large to bound memory. */
    private val stripAnim = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>()

    fun install(lp: LoadPackageParam) {
        val cl = lp.classLoader
        val cls = runCatching { cl.loadClass(INPUT_CLASS) }.getOrNull() ?: run {
            log("AC $INPUT_CLASS not loadable"); return
        }
        // The chat input is a plain android.widget.MultiAutoCompleteTextView (confirmed via
        // uiautomator). Hook its CONSTRUCTOR (declared on this class, so hookAllConstructors
        // catches it) and defer the id check to a post() — the view's id isn't assigned until
        // after inflation. We identify by RESOURCE ENTRY NAME ("message_input"), which is
        // package-independent: the resource table package is still "tv.twitch.android.app"
        // even though the app package was renamed to "tv.purple.app", so getIdentifier with
        // the runtime package returns 0. getResourceEntryName(id) sidesteps that entirely.
        XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = param.thisObject as? EditText ?: return
                v.post {
                    runCatching {
                        val entry = Res.entry(v) ?: return@post
                        if (entry != "message_input") return@post
                        attach(v, v.context ?: return@post)
                    }.onFailure { log("AC attach error: $it") }
                }
            }
        })
        log("AC autocomplete hook installed (ctor)")
    }

    /** When a finger last went down on the strip, so a picture redraw does not cut a hold short. */
    @Volatile private var lastStripTouch = 0L

    /** Tag so we only wire each input instance once. */
    private val WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<EditText, Boolean>()
    )

    /** Full display height in px. */
    private fun realScreenHeight(ctx: Context): Int = runCatching {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getSize(p)
        p.y
    }.getOrDefault(ctx.resources.displayMetrics.heightPixels)

    /** Activities we've already navbar-padded, so we apply the inset fix once each. */
    private val NAVBAR_FIXED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<android.app.Activity, Boolean>()
    )

    /** Unwrap a (possibly wrapped) Context to its hosting Activity, or null. */
    private fun activityOf(c: Context?): android.app.Activity? {
        var cur = c
        while (cur is android.content.ContextWrapper) {
            if (cur is android.app.Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    /**
     * Fix bug #3 (portrait nav-bar cutoff). The chat Activity draws EDGE-TO-EDGE (window
     * mFrame spans the full 2560 incl. the nav bar; FIT_INSETS_CONTROLLED) but the bottom
     * content row (chat_message_input_view_container, bottom==2480) does NOT reserve the 80px
     * system nav-bar inset, so the input row underlaps the nav bar (∨□○◁ overlap the input box).
     * Canonical edge-to-edge fix: pad the Activity content root's bottom by the nav-bar inset so
     * all content sits above the nav bar. Only adds bottom padding — video at the top is
     * untouched. Applied once per Activity.
     */
    private fun applyNavbarInset(input: EditText) {
        val act = activityOf(input.context) ?: return
        if (!NAVBAR_FIXED.add(act)) return
        runCatching {
            val content = act.findViewById<View>(android.R.id.content) ?: return
            content.setOnApplyWindowInsetsListener { v, insets ->
                // Portrait only: pad bottom by the nav-bar inset so the chat input clears the
                // system nav bar (#3). In landscape we must NOT touch content padding/layout —
                // doing so breaks the player's theater/fullscreen surface (turns it black, #4).
                val landscape = v.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                @Suppress("DEPRECATION") val nav = if (landscape) 0 else insets.systemWindowInsetBottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav)
                insets
            }
            content.requestApplyInsets()
            log("AC navbar inset fix applied to ${act.javaClass.simpleName}")
        }.onFailure { log("AC navbar fix error: $it") }
    }

    /** Weak ref to the most-recently-attached chat input, so the favorites bar in the native
     *  emote picker can insert an emote into it (mirrors the native picker's own behaviour). */
    @Volatile private var lastInput: java.lang.ref.WeakReference<EditText>? = null

    /** Insert "<name> " at the cursor of the current chat input (insert only — never sends). */
    fun insertEmote(name: String): Boolean {
        val input = lastInput?.get() ?: return false
        return runCatching {
            val ed = input.text ?: return false
            val pos = input.selectionStart.coerceIn(0, ed.length)
            val needPre = pos > 0 && ed[pos - 1] != ' '
            val insert = if (needPre) " $name " else "$name "
            ed.insert(pos, insert)
            input.setSelection((pos + insert.length).coerceAtMost(input.text.length))
            FrequentEmotes.noteInserted(name)
            true
        }.getOrDefault(false)
    }

    private fun attach(input: EditText, ctx: Context) {
        if (!WIRED.add(input)) return
        lastInput = java.lang.ref.WeakReference(input)
        log("AC wired chat input id=${input.id}")
        // Anchor the favorites-picker injector on this input's window root (class-agnostic).
        runCatching { PickerFavorites.attachRoot(input) }.onFailure { log("AC PF attachRoot error: $it") }
        wireSettingsLongPress(input, ctx)
        applyNavbarInset(input)

        var popup: PopupWindow? = null

        // Auto-dismiss the strip a few seconds after the last keystroke. The keyboard is
        // unmeasurable on this host and pressing Back to hide the IME does NOT clear the
        // EditText's focus, so onFocusChange alone can't catch "keyboard closed" — without this
        // the strip would linger mid-screen as a stray bar. Each keystroke reschedules the
        // dismiss, so the strip stays put while actively typing and clears shortly after.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val autoDismiss = Runnable { runCatching { popup?.dismiss() } }
        lateinit var refresh: Runnable
        lateinit var pictureRedraw: Runnable
        pictureRedraw = Runnable {
            if (popup?.isShowing != true) return@Runnable
            if (android.os.SystemClock.uptimeMillis() - lastStripTouch < Pins.BOTH_FAVORITE_MS + 500) {
                handler.postDelayed(pictureRedraw, 500); return@Runnable
            }
            handler.post(refresh)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                // Read the word on the next frame, not now. Twitch's emote picker inserts the
                // text first and moves the cursor after, so reading it here saw the cursor still
                // inside the PREVIOUS emote, and the strip kept showing that one.
                handler.removeCallbacks(refresh)
                handler.post(refresh)
            }
        })

        refresh = Runnable {
                runCatching {
                    handler.removeCallbacks(autoDismiss)
                    if (!Settings.get(Settings.KEY_AUTOCOMPLETE)) { popup?.dismiss(); return@Runnable }
                    val text = input.text?.toString() ?: ""
                    val cur = input.selectionStart.coerceIn(0, text.length)
                    val (word, start, end) = wordAround(text, cur)
                    if (word.length < MIN_LEN) { popup?.dismiss(); return@Runnable }
                    val sugs = EmoteRepo.suggest(word, 30)
                    if (sugs.isEmpty()) { popup?.dismiss(); return@Runnable }
                    popup?.dismiss()
                    // Pictures still missing for what is shown: fetch those first, and redraw the
                    // list as they land (unless a finger is on it, which a redraw would cancel).
                    val missing = sugs.filter { it.drawable == null }.map { it.name }
                    if (missing.isNotEmpty()) EmoteRepo.wantPictures(missing) {
                        handler.removeCallbacks(pictureRedraw)
                        handler.postDelayed(pictureRedraw, 250)
                    }
                    popup = showStrip(input, ctx, sugs, { name ->
                        replaceWord(input, start, end, name)
                    }, { handler.post(refresh) }, {
                        // A hold can outlast the strip's timeout; keep it open meanwhile.
                        lastStripTouch = android.os.SystemClock.uptimeMillis()
                        handler.removeCallbacks(autoDismiss)
                        handler.postDelayed(autoDismiss, stripTimeoutMs() + Pins.BOTH_FAVORITE_MS)
                    })
                    handler.postDelayed(autoDismiss, stripTimeoutMs())
                }.onFailure { log("AC text error: $it") }
        }

        // Also dismiss immediately when the input loses focus (switching fields / leaving chat).
        // Creates NO window, so it cannot reintroduce the old kb-tracker's black-player regression.
        runCatching {
            input.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) { handler.removeCallbacks(autoDismiss); runCatching { popup?.dismiss() } }
            }
        }
    }

    /** word [start,end) around cursor, split on whitespace. */
    private fun wordAround(text: String, cur: Int): Triple<String, Int, Int> {
        var s = cur
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        var en = cur
        while (en < text.length && !text[en].isWhitespace()) en++
        return Triple(text.substring(s, en), s, en)
    }

    private fun replaceWord(input: EditText, start: Int, end: Int, name: String) {
        runCatching {
            val ed = input.text ?: return
            val safeEnd = end.coerceAtMost(ed.length)
            val safeStart = start.coerceAtMost(safeEnd)
            ed.replace(safeStart, safeEnd, "$name ")
            input.setSelection((safeStart + name.length + 1).coerceAtMost(input.text.length))
            FrequentEmotes.noteInserted(name)
        }.onFailure { log("AC insert error: $it") }
    }

    /**
     * Tap picks the emote. Holding it pins it to the top of the list, adds it to the emote
     * picker's favorites bar, or (the default) both: 2 seconds pins, 5 seconds favorites instead.
     * The list is rebuilt only once the finger lifts, since rebuilding mid-hold would throw the
     * held chip away along with its timers.
     */
    private fun wireHold(chip: View, name: String, onPick: (String) -> Unit, onChanged: () -> Unit,
                         onTouchStart: () -> Unit) {
        val ctx = chip.context
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        val lp = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        var fired = false
        var pinnedNow = false
        var downX = 0f; var downY = 0f
        val favoritable = Favorites.isFavoritable(name)
        fun buzz() { runCatching { chip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } }
        val pin = Runnable {
            fired = true; buzz()
            pinnedNow = true
            val now = Pins.toggle(name)
            val more = if (Pins.holdMode() == Pins.HOLD_BOTH && favoritable) ". Keep holding to add it to favorites instead" else ""
            toast(ctx, (if (now) "Pinned $name" else "Unpinned $name") + more)
        }
        val fav = Runnable {
            fired = true
            if (!favoritable) {
                if (Pins.holdMode() == Pins.HOLD_FAVORITE)
                    toast(ctx, "This emote cannot go in the favorites bar")
                return@Runnable
            }
            buzz()
            // In "both", the 2 second pin already happened; a longer hold means favorite instead.
            if (pinnedNow) { Pins.toggle(name); pinnedNow = false }
            val now = Favorites.toggle(name)
            toast(ctx, if (now) "\u2605 Added $name to favorites" else "Removed $name from favorites")
        }
        fun stop() { h.removeCallbacks(pin); h.removeCallbacks(fav); chip.setBackgroundColor(Color.TRANSPARENT) }
        chip.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fired = false; pinnedNow = false; downX = ev.x; downY = ev.y
                    onTouchStart()
                    v.setBackgroundColor(0x33FFFFFF)
                    when (Pins.holdMode()) {
                        Pins.HOLD_PIN -> h.postDelayed(pin, lp)
                        Pins.HOLD_FAVORITE -> h.postDelayed(fav, lp)
                        else -> { h.postDelayed(pin, Pins.BOTH_PIN_MS); h.postDelayed(fav, Pins.BOTH_FAVORITE_MS) }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) stop()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.x - downX) > slop || abs(ev.y - downY) > slop
                    stop()
                    if (fired) onChanged() else if (!moved) onPick(name)
                    true
                }
                MotionEvent.ACTION_CANCEL -> { stop(); if (fired) onChanged(); false }
                else -> true
            }
        }
    }

    private fun tipView(ctx: Context, onChanged: () -> Unit): View {
        val msg = when (Pins.holdMode()) {
            Pins.HOLD_PIN -> "Tip: hold an emote to pin it to the top of this list whenever it matches."
            Pins.HOLD_FAVORITE -> "Tip: hold a Twitch emote to add it to the favorites bar in the emote picker."
            else -> "Tip: hold an emote for 2 seconds to pin it to the top of this list, or 5 seconds to add it to the favorites bar in the emote picker."
        } + " You can change this in PurpleTV settings."
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10f), dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f))
            setBackgroundColor(Color.parseColor("#FF3A2A55"))
            addView(TextView(ctx).apply {
                text = msg
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "\u2715"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                contentDescription = "Dismiss tip"
                setPadding(dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f))
                setOnClickListener {
                    Settings.set(Pins.KEY_TIP_DISMISSED, true)
                    onChanged()
                }
            })
        }
    }

    /** The whole input row (input plus its buttons), found by resource name, or the input
     *  itself if the row is not recognised. */
    private fun inputRow(input: EditText): View {
        var v: View? = input
        while (v != null) {
            val e = Res.entry(v)
            if (e == "chat_message_input_view_container" || e == "message_input_view_container") return v
            v = v.parent as? View
        }
        return input
    }

    /** Lay the suggestions out as a scrollable vertical list above the keyboard, the way the
     *  original PurpleTV did, instead of a single horizontal strip. */
    const val KEY_VERTICAL = "suggest_vertical"

    /** Tallest the vertical list may grow before it starts scrolling. */
    private const val VERTICAL_MAX_DP = 220f

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    /** Horizontal chip strip anchored just above the input. */
    private fun showStrip(
        input: EditText, ctx: Context, sugs: List<EmoteRepo.Sug>, onPick: (String) -> Unit,
        onChanged: () -> Unit, onTouchStart: () -> Unit
    ): PopupWindow {
        val vertical = Settings.get(KEY_VERTICAL, true)
        val row = LinearLayout(ctx).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
        }
        val imgPx = dp(ctx, 26f)
        for (sug in sugs) {
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 6f), dp(ctx, 3f), dp(ctx, 6f), dp(ctx, 3f))
                isClickable = true
            }
            wireHold(chip, sug.name, onPick, onChanged, onTouchStart)
            // Prefer a looping animated drawable for animated emotes; else the static one. An
            // ImageView hosts the drawable directly (provides its own invalidation callback), so
            // unlike chat spans we just start() it. Animated copies are cached per-name so we don't
            // re-decode dozens on every keystroke (cache bounded to keep memory in check).
            val chosen: android.graphics.drawable.Drawable? =
                if (EmoteRepo.animBytes.containsKey(sug.name)) {
                    if (stripAnim.size > 200) stripAnim.clear()
                    // Keyed by url too: the same word can be a different emote in another chat.
                    val k = sug.name + "|" + EmoteRepo.urlOf(sug.name)
                    stripAnim[k] ?: EmoteRepo.makeAnimated(sug.name)?.also { stripAnim[k] = it }
                } else {
                    sug.drawable?.let { it.constantState?.newDrawable()?.mutate() ?: it }
                }
            (chosen ?: sug.drawable)?.let { d ->
                chip.addView(ImageView(ctx).apply {
                    setImageDrawable(d)
                    if (d is android.graphics.drawable.AnimatedImageDrawable) runCatching { d.start() }
                    layoutParams = LinearLayout.LayoutParams(imgPx, imgPx).apply {
                        rightMargin = dp(ctx, 4f)
                    }
                })
            }
            if (sug.pinned) chip.addView(TextView(ctx).apply {
                text = "\uD83D\uDCCC"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, 0, dp(ctx, 3f), 0)
            })
            chip.addView(TextView(ctx).apply {
                text = sug.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            if (vertical) row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            else row.addView(chip)
        }
        val scroll: ViewGroup = if (vertical) {
            android.widget.ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                setBackgroundColor(Color.parseColor("#FF1F1925"))
                addView(row)
            }
        } else {
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.parseColor("#FF1F1925"))
                addView(row)
            }
        }
        // Horizontal is a fixed bar. Vertical grows with the number of suggestions and then
        // stops and scrolls, so a long match list cannot swallow the screen.
        val listH = if (vertical) {
            val rowH = imgPx + dp(ctx, 6f)
            (sugs.size * rowH + dp(ctx, 8f)).coerceAtMost(dp(ctx, VERTICAL_MAX_DP))
        } else dp(ctx, 44f)
        // The one-time tip sits on top of the list until its X is pressed.
        val rowWidth = inputRow(input).width.takeIf { it > 0 } ?: (input.rootView ?: input).width
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FF1F1925"))
        }
        var tipH = 0
        if (!Settings.get(Pins.KEY_TIP_DISMISSED, false)) {
            val tip = tipView(ctx, onChanged)
            tip.measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            tipH = tip.measuredHeight
            content.addView(tip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tipH))
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH))
        val stripH = listH + tipH
        val pw = PopupWindow(
            content, ViewGroup.LayoutParams.MATCH_PARENT, stripH, false
        ).apply {
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            // Draw BEHIND the keyboard. The strip now sits on the input row, which a docked
            // keyboard never covers, so the only keyboard it can overlap is a floating one, and
            // that must stay on top: the strip was drawn over the floating keyboard's keys.
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        }
        runCatching {
            val root = input.rootView ?: input
            // Sit directly on top of the chat input row and only as wide as it: in landscape
            // that is the chat column, not the whole screen across the player. The row is
            // measured live, and re-measured on every layout, because it moves when the emote
            // picker or keyboard opens and closes; a strip placed once was left hanging
            // mid-screen after the picker closed.
            val row = inputRow(input)
            fun place(): IntArray {
                val loc = IntArray(2)
                row.getLocationInWindow(loc)
                val rootH = root.height.takeIf { it > 0 } ?: realScreenHeight(ctx)
                val y = (loc[1] - stripH).coerceIn(dp(ctx, 4f), (rootH - stripH).coerceAtLeast(dp(ctx, 4f)))
                return intArrayOf(loc[0], y, row.width.takeIf { it > 0 } ?: root.width)
            }
            val p = place()
            pw.width = p[2]
            pw.showAtLocation(root, Gravity.TOP or Gravity.START, p[0], p[1])
            var last = p
            val follow = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                if (!pw.isShowing) return@OnGlobalLayoutListener
                if (!row.isShown) { runCatching { pw.dismiss() }; return@OnGlobalLayoutListener }
                val n = place()
                if (!n.contentEquals(last)) { last = n; pw.update(n[0], n[1], n[2], stripH) }
            }
            val vto = root.viewTreeObserver
            vto.addOnGlobalLayoutListener(follow)
            pw.setOnDismissListener {
                runCatching { root.viewTreeObserver.removeOnGlobalLayoutListener(follow) }
            }
        }.onFailure { log("AC popup error: $it") }
        return pw
    }

    /**
     * Open the PurpleTV settings dialog by long-pressing the chat input while it's EMPTY.
     * (The emoticon_picker / Twitch gear icons are owned by Twitch and consume their own
     * long-press, so we anchor on our own input instead.) When the field has text we return
     * false so Twitch's normal text-selection long-press still works.
     */
    private fun wireSettingsLongPress(input: EditText, ctx: Context) {
        runCatching {
            input.setOnLongClickListener {
                if ((input.text?.length ?: 0) == 0) {
                    log("AC settings long-press fired")
                    openSettingsDialog(ctx); true
                } else false
            }
            log("AC settings long-press wired on input (long-press empty input to open)")
        }.onFailure { log("AC settings wire error: $it") }
    }

    private fun openSettingsDialog(ctx: Context) {
        runCatching {
            val pad = dp(ctx, 20f)
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, dp(ctx, 12f), pad, dp(ctx, 4f))
            }

            // Boolean toggles (one CheckBox per Settings.ITEMS entry).
            for ((key, label) in Settings.ITEMS) {
                container.addView(android.widget.CheckBox(ctx).apply {
                    text = label
                    isChecked = Settings.get(key)
                    setOnCheckedChangeListener { _, v -> Settings.set(key, v) }
                })
            }

            // Divider.
            container.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)
                ).apply { topMargin = dp(ctx, 12f); bottomMargin = dp(ctx, 8f) }
            })

            // Landscape chat-panel opacity slider (0..100% opaque).
            val opacity = Settings.getInt(Settings.KEY_CHAT_OPACITY, Settings.CHAT_OPACITY_DEFAULT)
            val opacityLabel = TextView(ctx).apply {
                text = "Landscape chat opacity: $opacity%"
                setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
            }
            container.addView(opacityLabel)
            container.addView(android.widget.SeekBar(ctx).apply {
                max = 100
                progress = opacity
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        opacityLabel.text = "Landscape chat opacity: $p%"
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                        Settings.setInt(Settings.KEY_CHAT_OPACITY, sb?.progress ?: 100)
                        ChatTransparency.reapply()
                    }
                })
            })

            val scroll = android.widget.ScrollView(ctx).apply { addView(container) }
            AlertDialog.Builder(ctx)
                .setTitle("PurpleTV")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .show()
        }.onFailure { log("AC settings dialog error: $it") }
    }
}
