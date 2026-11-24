package tv.purple.xp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import java.lang.ref.WeakReference
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The two streams screen itself: a dark layer over the whole of Twitch's window with two players,
 * a strip with each stream's name and buttons, and each stream's chat.
 *
 * It hangs off the window's top view, not inside Twitch's layout, so nothing Twitch hides, moves or
 * rebuilds can reach it. It keeps every touch, mouse wheel and back press to itself, and keyboard
 * focus inside itself, so nothing under it reacts.
 *
 * Sizes are worked out by hand on every layout because the arrangement changes shape with the
 * window: two columns when it is wide, players stacked over the chats when it is tall, only the two
 * players in picture-in-picture. Edge to edge windows no longer shrink for the keyboard, so its
 * height is kept clear the same way as the system bars.
 */
@SuppressLint("ViewConstructor")
internal class TwoStreamsRoot(private val act: Activity, private val ui: Ui) : FrameLayout(act) {

    /** What the screen asks of whoever opened it. Always called on the main thread. */
    interface Ui {
        fun back()
        fun tapPlayer(i: Int)
        /** A stream was started again with its own Play button. */
        fun played()
        fun swap(i: Int)
        fun watch(i: Int)
        fun dismiss(i: Int)
        fun laidOut()
        fun detached(root: TwoStreamsRoot)
    }

    private class Slot(val login: String, val player: DualPlayer, val chat: DualChatPane, val header: DualHeader) {
        var channelId: String? = null
        var state: DualPlayer.State? = null
        var capW = 0
        var capH = 0
        var compact: Boolean? = null
        var paused = false
        var volume = 100
        var raids: DualRaids.Sub? = null
        /** Paused with its own button: it stays paused when the app comes back to the front. */
        var heldByUser = false
    }

    private val slots = arrayOfNulls<Slot>(2)
    private var pip = false
    private var lastInsets: WindowInsets? = null
    private val safe = Rect()
    /** Height of the safe area without the keyboard, so opening it never flips the arrangement. */
    private var tallNoIme = 0
    private var ime = false
    private var shape = ""
    /** Player, header and chat of the first stream, then the same three for the second. */
    private val frames = Array(6) { Rect() }
    private val loc = IntArray(2)
    private val hit = Rect()
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downOn = -1
    private var backDown = false
    /** The stream whose emote picker or rewards panel is open, or -1. */
    private var sheetOwner = -1
    /** The stream the captions follow when one was picked, or -1 for the louder one. */
    private var captionPick = -1
    @Volatile private var captionName: String? = null
    @Volatile private var captionFrom = 0L
    private val sheetHost = FrameLayout(act).apply {
        id = View.generateViewId()
        setBackgroundColor(BG)
        isClickable = true
        visibility = View.GONE
    }
    private val sheetClose = ImageView(act).apply {
        setImageDrawable(DualIcons.ChevronDown())
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = I18n.t("Close")
        background = GradientDrawable().apply {
            setColor(HEADER_BG)
            val r = 10 * resources.displayMetrics.density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        visibility = View.GONE
        setOnClickListener { closeSheet() }
    }

    init {
        tag = ROOT_TAG
        setBackgroundColor(BG)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        // Without a touch screen in use, Android would wash the whole focused screen in its accent colour.
        defaultFocusHighlightEnabled = false
        // The first stream stays on the left whatever the language; the strips mirror themselves.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(sheetHost)
        addView(sheetClose)
    }

    // ---- streams ----

    /** Put [login] in place [i], letting go of whatever played there before. */
    fun show(i: Int, login: String, raidId: String? = null, carryVolume: Int? = null,
             startPaused: Boolean = false, heldByUser: Boolean = false) {
        slots[i]?.let { drop(it) }
        val ctx = context
        val player = DualPlayer(ctx, login, raidId)
        val chat = DualChatPane(scaled(ctx), login)
        val header = DualHeader(ctx, i, ui)
        val s = Slot(login, player, chat, header)
        s.volume = Settings.getInt(VOLUME_KEY + login, carryVolume ?: 100).coerceIn(0, 100)
        slots[i] = s
        addView(player)
        addView(header)
        addView(chat)
        header.bind(player.info)
        header.setLevel(s.volume)
        header.onStacked = { on -> Settings.set(KEY_STACKED, on); requestLayout() }
        chat.onSheet = { kind -> if (slots[i] === s) openSheet(i, kind) }
        player.onControl = { key, v -> if (slots[i] === s) control(i, s, key, v) }
        header.onVolume = { p, done -> if (slots[i] === s) setVolume(i, p, done) }
        player.onInfo = { inf -> if (slots[i] === s) onInfo(i, s, inf) }
        player.setVolumePercent(s.volume)
        if (startPaused) {
            s.paused = true
            s.heldByUser = heldByUser
            player.pause()
        }
        player.start()
        chat.start()
        updateKeepOn()
        updateCaptions()
        requestLayout()
    }

    // ---- captions: one recognizer, on one of the two streams ----

    private fun captionIndex(): Int {
        if (captionPick in 0..1 && slots[captionPick] != null) return captionPick
        val a = slots[0] ?: return 1
        val b = slots[1] ?: return 0
        return if (b.volume > a.volume) 1 else 0
    }

    private fun updateCaptions() {
        val l = slots[captionIndex()]?.login
        if (l != captionName) {
            captionName = l
            captionFrom = android.os.SystemClock.uptimeMillis()
        }
    }

    fun pickCaptions(i: Int) {
        captionPick = i
        updateCaptions()
    }

    fun captionPlayer(): Any? = slots[captionIndex()]?.player?.ivsPlayer
    fun captionLogin(): String? = captionName
    fun captionSince(): Long = captionFrom
    fun captionView(): DualPlayer? = if (inPip()) null else slots[captionIndex()]?.player

    private fun onInfo(i: Int, s: Slot, inf: DualPlayer.Info) {
        s.header.bind(inf)
        inf.id?.takeIf { it != s.channelId }?.let { id ->
            s.channelId = id
            s.chat.setChannelId(id)
            s.raids?.let { DualRaids.remove(it) }
            s.raids = DualRaids.Sub(id) { login, name, raidId -> if (slots[i] === s) raided(i, s, login, name, raidId) }
                .also { DualRaids.add(context, it) }
        }
        if (inf.state != s.state) {
            s.state = inf.state
            updateKeepOn()
        }
        updatePoints(s)
    }

    /** The channel in this pane raided [login]: the pane goes along, unless the other one is there already. */
    private fun raided(i: Int, s: Slot, login: String, name: String, raidId: String) {
        val text = I18n.t("Raid: moving to $name")
        if (slots[1 - i]?.login == login || s.login == login) { s.chat.notice(text); return }
        show(i, login, raidId, s.volume, s.paused, s.heldByUser)
        slots[i]?.chat?.notice(text)
    }

    fun devCard(i: Int, kind: String) { slots.getOrNull(i)?.chat?.devCard(kind) }

    /** Test hook: what a real raid does to pane [i] when it leaves, without joining anything. */
    fun devRaid(i: Int, login: String) {
        val s = slots.getOrNull(i) ?: return
        raided(i, s, login, login, "dev")
    }

    /** Bonus chests are claimed for a channel while its pane is really playing. */
    private fun updatePoints(s: Slot) {
        val id = s.channelId
        val on = !s.paused && id != null && (s.state == DualPlayer.State.PLAYING || s.state == DualPlayer.State.BUFFERING)
        if (on && id != null) ChannelPoints.watch(s.login, id) else ChannelPoints.unwatch(s.login)
    }

    /**
     * The screen stays on while a stream plays or is still loading, and may sleep once both are
     * paused, offline or failed. Held by the view rather than the window flag: Twitch clears that
     * flag itself when its own player pauses, which it does the moment this screen opens.
     */
    private fun updateKeepOn() {
        val want = slots.any { it != null && !it.paused && it.state != DualPlayer.State.OFFLINE && it.state != DualPlayer.State.ERROR }
        if (keepScreenOn != want) keepScreenOn = want
    }

    /** A tap on the video shows or hides that stream's controls. */
    fun tapControls(i: Int) {
        for (k in 0..1) if (k != i) slots[k]?.player?.takeIf { !it.isPausedNow }?.hideControls()
        slots.getOrNull(i)?.player?.toggleControls()
    }

    private fun control(i: Int, s: Slot, key: String, v: View) {
        when (key) {
            "play" -> {
                if (s.player.isPausedNow) { s.heldByUser = false; s.paused = false; s.player.resume(); ui.played() }
                else { s.heldByUser = true; s.paused = true; s.player.pause() }
                updatePoints(s)
                updateKeepOn()
            }
            "refresh" -> s.player.retry()
            "quality" -> showQualities(s, v)
            "captions" -> {
                pickCaptions(i)
                toast(context, I18n.t("Captions follow ${s.player.info.displayName ?: s.login}"))
            }
            "filters" -> FiltersPanel.show(v, s.login)
            "stats" -> s.player.toggleStats()
            "clip" -> ClipSave.start(context, s.channelId, s.login)
            "share" -> runCatching {
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://www.twitch.tv/${s.login}"), null))
            }.onFailure { log("TS share: $it") }
            "sleep" -> SleepTimer.choose(act)
        }
    }

    private fun showQualities(s: Slot, anchor: View) {
        val list = s.player.qualities()
        if (list.isEmpty()) { toast(context, "Not playing yet"); return }
        val now = s.player.currentQuality()
        val pm = PopupMenu(ContextThemeWrapper(context, android.R.style.Theme_Material), anchor)
        pm.menu.add(1, 0, 0, I18n.t("Auto")).isChecked = now == null
        list.forEachIndexed { k, (name, _) -> pm.menu.add(1, k + 1, k + 1, name).isChecked = name == now }
        pm.menu.setGroupCheckable(1, true, true)
        pm.setOnMenuItemClickListener {
            s.player.chooseQuality(if (it.itemId == 0) null else list[it.itemId - 1].second)
            true
        }
        pm.show()
    }

    /** The chats draw at their own, smaller density, so more fits in a pane. */
    private fun scaled(base: Context): Context {
        val pct = Settings.getInt(KEY_CHAT_SIZE, CHAT_SIZE_DEF).coerceIn(60, 100)
        if (pct == 100) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.densityDpi = cfg.densityDpi * pct / 100
        return ContextThemeWrapper(base, base.theme).apply { applyOverrideConfiguration(cfg) }
    }

    /** Twitch's own emote picker or rewards panel for stream [i], over the chats. */
    private fun openSheet(i: Int, kind: String) {
        val s = slots[i] ?: return
        val id = s.channelId
        if (id == null) { toast(context, "Not connected to chat yet"); return }
        closeSheet()
        val name = s.player.info.displayName ?: s.login
        s.chat.hideKeys()
        EmoteRepo.loadChannelAsync(id, s.login)
        val ok = if (kind == "points") DualCards.openPoints(act, sheetHost.id, id, s.login, name)
        else {
            EmoteAutocomplete.useBox(s.chat.box)
            PickerFavorites.attachRoot(s.chat.box)
            DualCards.openEmotes(act, sheetHost.id, id, s.login, name) { action, code ->
                when (action) {
                    "emote_selected" -> code?.let { EmoteAutocomplete.insertEmote(it) }
                    "delete" -> PickerBackspace.backspace(s.chat.box)
                    "show_keyboard" -> { closeSheet(); s.chat.focusBox() }
                }
            }
        }
        if (!ok) {
            DualCards.closeSheet(act)
            EmoteAutocomplete.useBox(null)
            toast(context, "Could not open this")
            return
        }
        sheetOwner = i
        sheetHost.visibility = View.VISIBLE
        sheetClose.visibility = View.VISIBLE
        sheetHost.bringToFront()
        sheetClose.bringToFront()
        requestLayout()
    }

    /** True when a panel was open and is now closed. */
    fun closeSheet(): Boolean {
        if (sheetOwner < 0) return false
        sheetOwner = -1
        DualCards.closeSheet(act)
        EmoteAutocomplete.useBox(null)
        sheetHost.visibility = View.GONE
        sheetClose.visibility = View.GONE
        requestLayout()
        return true
    }

    /** Over both chats, tall enough to use even when the chats are short. */
    private fun placeSheet() {
        val own = frames[sheetOwner * 3 + 2]
        val other = frames[(1 - sheetOwner) * 3 + 2]
        val chatTop = if (shape.startsWith("stacked") || other.isEmpty) own.top else min(own.top, other.top)
        val top = max(safe.top + dp(36), min(chatTop, safe.bottom - dp(SHEET_MIN_DP)))
        place(sheetHost, Rect(safe.left, top, safe.right, safe.bottom))
        // A handle centred on the panel's top edge, clear of each stream's own close button.
        val hw = dp(64)
        val hh = dp(24)
        if (sheetClose.paddingLeft != dp(20)) sheetClose.setPadding(dp(20), dp(3), dp(20), dp(3))
        place(sheetClose, Rect(safe.centerX() - hw / 2, top - hh, safe.centerX() + hw / 2, top))
    }

    private fun drop(s: Slot) {
        if (sheetOwner >= 0 && slots[sheetOwner] === s) closeSheet()
        FiltersPanel.closeFor(s.login)
        s.player.onInfo = null
        s.raids?.let { DualRaids.remove(it) }
        s.raids = null
        ChannelPoints.unwatch(s.login)
        runCatching { s.player.release() }.onFailure { log("TS player release: $it") }
        runCatching { s.chat.stop() }.onFailure { log("TS chat stop: $it") }
        s.header.closeMenu()
        // Focus that leaves with a removed view goes to the first focusable view in the window,
        // which is in Twitch's screen underneath.
        if (s.chat.hasFocus() || s.header.hasFocus() || s.player.hasFocus()) requestFocus()
        removeView(s.player)
        removeView(s.header)
        removeView(s.chat)
    }

    /** Both streams stopped and let go. The screen is done after this. */
    fun releaseAll() {
        for (k in 0..1) slots[k]?.let { drop(it); slots[k] = null }
        updateKeepOn()
    }

    fun login(i: Int): String? = slots.getOrNull(i)?.login

    /** Stream [i]'s volume, kept per channel once [save] says the slider has been let go. */
    fun setVolume(i: Int, percent: Int, save: Boolean) {
        val s = slots.getOrNull(i) ?: return
        val p = percent.coerceIn(0, 100)
        s.volume = p
        s.player.setVolumePercent(p)
        s.header.setLevel(p)
        updateCaptions()
        if (save) Settings.setInt(VOLUME_KEY + s.login, p)
    }

    fun openVolume(i: Int) { slots.getOrNull(i)?.header?.openVolume() }

    fun pauseAll() {
        for (s in slots) s?.let { it.paused = true; runCatching { it.player.pause() }; updatePoints(it) }
        updateKeepOn()
    }

    fun resumeAll() {
        for (s in slots) s?.let {
            if (it.heldByUser) return@let
            it.paused = false
            runCatching { it.player.resume() }
            updatePoints(it)
        }
        updateKeepOn()
    }

    fun setPip(on: Boolean) {
        if (pip == on) return
        pip = on
        if (on) { closeMenus(); closeSheet() }
        requestLayout()
    }

    fun closeMenus() { for (s in slots) s?.header?.closeMenu() }

    private fun inPip() = pip || act.isInPictureInPictureMode

    /** Both players together, in window coordinates, or null before they are laid out. */
    fun playersRect(): Rect? {
        val out = Rect()
        var any = false
        for (s in slots) {
            val p = s?.player ?: continue
            if (p.width == 0 || p.height == 0 || !p.getGlobalVisibleRect(hit)) continue
            if (any) out.union(hit) else out.set(hit)
            any = true
        }
        return if (any) out else null
    }

    /**
     * Where Android should grow the small window from: the stacked players when they already have
     * its 8:9 shape, else an 8:9 box in the middle of the screen. Android keeps an old hint for as
     * long as no new one comes, so there is always one, never a stale rectangle from an earlier layout.
     */
    fun pipHint(): Rect? {
        if (!isAttachedToWindow || width == 0 || height == 0) return null
        val both = playersRect()
        if (both != null && both.height() > 0 && abs(both.width().toFloat() / both.height() - 8f / 9f) < 0.05f) return both
        if (!getGlobalVisibleRect(hit)) return null
        var w = hit.width()
        var h = w * 9 / 8
        if (h > hit.height()) { h = hit.height(); w = h * 8 / 9 }
        val l = hit.centerX() - w / 2
        val t = hit.centerY() - h / 2
        return Rect(l, t, l + w, t + h)
    }

    /** Lines for the dev status report. */
    fun describe(): List<String> {
        val out = ArrayList<String>()
        out += "screen ${width}x$height, safe ${safe.toShortString()}, $shape, keyboard ${if (ime) "open" else "closed"}, " +
            if (inPip()) "picture-in-picture" else "full window"
        for (k in 0..1) {
            val s = slots[k] ?: continue
            val inf = s.player.info
            out += "${if (k == 0) "A" else "B"} ${s.login}: ${inf.state}${inf.message?.let { " ($it)" } ?: ""}, " +
                "volume ${s.volume}, player ${s.player.width}x${s.player.height}, header ${s.header.width}x${s.header.height}, " +
                "chat ${s.chat.width}x${s.chat.height}${if (s.compact == true) " compact" else ""}, cap ${s.capW}x${s.capH}, " +
                "id ${s.channelId ?: "-"}, viewers ${inf.viewers ?: "-"}"
        }
        return out
    }

    // ---- layout ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            View.getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    /** The children are measured here, once our place in the window is known. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return
        val small = inPip()
        for (s in slots) {
            if (s == null) continue
            val vis = if (small) View.GONE else View.VISIBLE
            if (s.header.visibility != vis) s.header.visibility = vis
            if (s.chat.visibility != vis) s.chat.visibility = vis
        }
        for (f in frames) f.setEmpty()
        if (small) {
            safe.set(0, 0, w, h)
            ime = false
            pipFrames(w, h)
        } else {
            measureSafe(w, h)
            val tall = safe.width() <= tallNoIme
            for (s in slots) s?.header?.setTall(tall, stacked())
            if (!tall) columns() else if (stacked()) stackedRows() else rows()
        }
        for (k in 0..1) {
            val s = slots[k] ?: continue
            val pf = frames[k * 3]
            place(s.player, pf)
            if (!small) {
                place(s.header, frames[k * 3 + 1])
                place(s.chat, frames[k * 3 + 2])
            }
            if (pf.width() > 0 && pf.height() > 0 && (pf.width() != s.capW || pf.height() != s.capH)) {
                s.capW = pf.width(); s.capH = pf.height()
                runCatching { s.player.setCap(s.capW, s.capH) }
            }
            // A chat too short for its message box drops it, unless someone is typing in it.
            val compact = small || (!ime && frames[k * 3 + 2].height() < dp(120))
            if (s.compact != compact) { s.compact = compact; s.chat.compact = compact }
        }
        if (sheetOwner >= 0 && !small) placeSheet()
        ui.laidOut()
    }

    private fun place(v: View, f: Rect) {
        val w = max(0, f.width())
        val h = max(0, f.height())
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(f.left, f.top, f.left + w, f.top + h)
    }

    /**
     * The part of us clear of system bars, camera holes and the keyboard. Worked out from the real
     * window insets and where we sit in the window, so it is right whether Twitch's window already
     * keeps clear of them (older Android pads the window's top view) or draws edge to edge.
     */
    private fun measureSafe(w: Int, h: Int) {
        val ins = rootWindowInsets ?: lastInsets
        var il = 0; var itop = 0; var ir = 0; var ib = 0; var kb = 0
        if (ins != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                il = bars.left; itop = bars.top; ir = bars.right; ib = bars.bottom
                kb = ins.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                val e = oldInsets(ins)
                il = e[0]; itop = e[1]; ir = e[2]; ib = e[3]; kb = e[4]
            }
        }
        getLocationInWindow(loc)
        val win = rootView
        val ww = if (win.width > 0) win.width else w
        val wh = if (win.height > 0) win.height else h
        val left = max(0, il - loc[0])
        val top = max(0, itop - loc[1])
        val right = max(0, loc[0] + w - (ww - ir))
        val barsBottom = max(0, loc[1] + h - (wh - ib))
        val bottom = max(barsBottom, loc[1] + h - (wh - kb))
        ime = kb > ib + dp(48) && bottom > barsBottom
        safe.set(left, top, max(left, w - right), max(top, h - bottom))
        tallNoIme = max(0, h - barsBottom - top)
    }

    /** Left, top, right, bottom and keyboard height before Android 11, where the keyboard is part of the bottom inset. */
    @Suppress("DEPRECATION")
    private fun oldInsets(ins: WindowInsets): IntArray {
        var l = ins.systemWindowInsetLeft
        var t = ins.systemWindowInsetTop
        var r = ins.systemWindowInsetRight
        var b = ins.systemWindowInsetBottom
        var kb = 0
        val stable = ins.stableInsetBottom
        if (b > stable + dp(80)) { kb = b; b = stable }
        if (Build.VERSION.SDK_INT >= 28) ins.displayCutout?.let { c ->
            l = max(l, c.safeInsetLeft); t = max(t, c.safeInsetTop)
            r = max(r, c.safeInsetRight); b = max(b, c.safeInsetBottom)
        }
        return intArrayOf(l, t, r, b, kb)
    }

    /** Wide: two columns, each a player, its strip and its chat. */
    private fun columns() {
        val gap = dp(2)
        val hh = dp(HEADER_DP)
        val colW = (safe.width() - gap) / 2
        val minChat = dp(if (ime) 96 else 160)
        var ph = colW * 9 / 16
        val room = safe.height() - hh - minChat
        if (ph > room) ph = max(room, dp(MIN_PLAYER_DP))
        ph = max(0, min(ph, safe.height() - hh))
        for (k in 0..1) {
            val x0 = safe.left + k * (colW + gap)
            val x1 = if (k == 0) x0 + colW else safe.right
            frames[k * 3].set(x0, safe.top, x1, safe.top + ph)
            val hb = min(safe.bottom, safe.top + ph + hh)
            frames[k * 3 + 1].set(x0, safe.top + ph, x1, hb)
            frames[k * 3 + 2].set(x0, hb, x1, max(hb, safe.bottom))
        }
        shape = "columns"
    }

    /**
     * Tall: players over everything, then the two strips side by side, then the two chats. Short of
     * room (a keyboard, a small phone) the players shrink; once stacked players would be smaller
     * than two side by side, they go side by side.
     */
    private fun rows() {
        val gap = dp(2)
        val hh = dp(HEADER_DP)
        val w = safe.width()
        val colW = (w - gap) / 2
        val minChat = dp(if (ime) 120 else 200)
        val room = max(0, safe.height() - hh - minChat)
        val full = w * 9 / 16
        var side = false
        var ph: Int
        if (2 * full <= room) ph = full
        else {
            val stacked = max(room / 2, dp(MIN_PLAYER_DP))
            val beside = min(colW * 9 / 16, max(room, dp(MIN_PLAYER_DP)))
            if (beside > stacked) { side = true; ph = beside } else ph = stacked
        }
        ph = max(0, min(ph, if (side) safe.height() else safe.height() / 2))
        val top = safe.top
        if (side) {
            frames[0].set(safe.left, top, safe.left + colW, top + ph)
            frames[3].set(safe.left + colW + gap, top, safe.right, top + ph)
        } else {
            frames[0].set(safe.left, top, safe.right, top + ph)
            frames[3].set(safe.left, top + ph, safe.right, top + 2 * ph)
        }
        val hy = min(safe.bottom, if (side) top + ph else top + 2 * ph)
        val hb = min(safe.bottom, hy + hh)
        for (k in 0..1) {
            val x0 = safe.left + k * (colW + gap)
            val x1 = if (k == 0) x0 + colW else safe.right
            frames[k * 3 + 1].set(x0, hy, x1, hb)
            frames[k * 3 + 2].set(x0, hb, x1, max(hb, safe.bottom))
        }
        shape = if (side) "rows, players side by side" else "rows"
    }

    private fun stacked() = Settings.get(KEY_STACKED, false)

    /**
     * Tall, with stacked chats: each stream directly above its own full-width chat. While typing,
     * the players sit side by side on top and the chat being typed in takes the rest.
     */
    private fun stackedRows() {
        val gap = dp(2)
        val hh = dp(HEADER_DP)
        val w = safe.width()
        val typing = if (ime) slots.indexOfFirst { it?.chat?.hasFocus() == true } else -1
        if (typing >= 0) {
            val colW = (w - gap) / 2
            val ph = max(0, min(colW * 9 / 16, max(safe.height() - hh - dp(96), dp(MIN_PLAYER_DP))))
            frames[0].set(safe.left, safe.top, safe.left + colW, safe.top + ph)
            frames[3].set(safe.left + colW + gap, safe.top, safe.right, safe.top + ph)
            val hb = min(safe.bottom, safe.top + ph + hh)
            frames[typing * 3 + 1].set(safe.left, safe.top + ph, safe.right, hb)
            frames[typing * 3 + 2].set(safe.left, hb, safe.right, max(hb, safe.bottom))
            shape = "stacked, typing in ${typing + 1}"
            return
        }
        val block = (safe.height() - gap) / 2
        val ph = max(0, min(w * 9 / 16, max(block - hh - dp(MIN_STACKED_CHAT_DP), dp(MIN_PLAYER_DP))))
        for (k in 0..1) {
            val y0 = safe.top + k * (block + gap)
            val y1 = if (k == 0) y0 + block else safe.bottom
            val hb = min(y1, y0 + ph + hh)
            frames[k * 3].set(safe.left, y0, safe.right, y0 + ph)
            frames[k * 3 + 1].set(safe.left, y0 + ph, safe.right, hb)
            frames[k * 3 + 2].set(safe.left, hb, safe.right, max(hb, y1))
        }
        shape = "stacked"
    }

    /** The setting can change on Twitch's settings page, with this screen waiting underneath. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) requestLayout()
    }

    /** Picture-in-picture: the two players fill the small window, stacked unless it is wide. */
    private fun pipFrames(w: Int, h: Int) {
        if (w > h * 1.2f) {
            frames[0].set(0, 0, w / 2, h)
            frames[3].set(w / 2, 0, w, h)
        } else {
            frames[0].set(0, 0, w, h / 2)
            frames[3].set(0, h / 2, w, h)
        }
        shape = "picture-in-picture"
    }

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Kept from the children: the panes must not pad themselves on top of our own spacing.
        lastInsets = insets
        requestLayout()
        return insets
    }

    // ---- input: everything stops here ----

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downAt = ev.eventTime
                // A press on the player's "Tap to try again" label only retries.
                val k = playerAt(ev.x, ev.y)
                downOn = if (k >= 0 && onPlayerControl(k, ev.x, ev.y)) -1 else k
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> downOn = -1
            MotionEvent.ACTION_MOVE ->
                if (downOn >= 0 && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) downOn = -1
            MotionEvent.ACTION_UP -> {
                val i = downOn
                downOn = -1
                if (i >= 0 && ev.eventTime - downAt < ViewConfiguration.getLongPressTimeout() && playerAt(ev.x, ev.y) == i)
                    post { ui.tapPlayer(i) }
            }
        }
        super.dispatchTouchEvent(ev)
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    private fun playerAt(x: Float, y: Float): Int {
        for (k in 0..1) {
            val p = slots[k]?.player ?: continue
            if (p.visibility == View.VISIBLE && x >= p.left && x < p.right && y >= p.top && y < p.bottom) return k
        }
        return -1
    }

    /** Whether ([x], [y]) is on something inside player [k] that takes taps itself. */
    private fun onPlayerControl(k: Int, x: Float, y: Float): Boolean {
        val p = slots[k]?.player ?: return false
        val lx = x - p.left
        val ly = y - p.top
        if (p.isControlAt(lx, ly)) return true
        for (j in 0 until p.childCount) {
            val c = p.getChildAt(j)
            if (c.visibility == View.VISIBLE && c.isClickable && lx >= c.left && lx < c.right && ly >= c.top && ly < c.bottom) return true
        }
        return false
    }

    /** A mouse wheel or a hovering pointer over us must not scroll or light up Twitch's views below. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        super.dispatchGenericMotionEvent(event)
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        super.dispatchHoverEvent(event)
        return true
    }

    /**
     * Back closes the screen when it is let go, and only if the press started here: a press whose
     * start closed the keyboard or a menu is not a second back.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backDown = !super.dispatchKeyEvent(event)
            KeyEvent.ACTION_UP -> {
                val mine = backDown
                backDown = false
                if (!mine) super.dispatchKeyEvent(event)
                else if (!event.isCanceled) ui.back()
            }
        }
        return true
    }

    /** Arrow keys and tab keep to our views instead of wandering into Twitch's screen underneath. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        return if (next != null && inside(next)) next else focused
    }

    /** With the focus on this view itself, the search would go straight to the window and skip the check above. */
    override fun focusSearch(direction: Int): View? = focusSearch(this, direction)

    private fun inside(v: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ui.detached(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val ROOT_TAG = "ptv_dual_root"
        /** Kept on this device only: dual_ui_ settings are left out of settings sync. */
        const val VOLUME_KEY = "dual_ui_vol_"
        const val KEY_STACKED = "dual_stacked_chats"
        const val KEY_CHAT_SIZE = "dual_chat_size"
        const val CHAT_SIZE_DEF = 85
        const val SHEET_MIN_DP = 320
        /** Enough for a few lines above the message box. */
        const val MIN_STACKED_CHAT_DP = 120
        const val BG = 0xFF0E0E10.toInt()
        const val HEADER_BG = 0xFF1F1F23.toInt()
        const val TEXT = 0xFFEFEFF1.toInt()
        const val DIM = 0xFFADADB8.toInt()
        const val LIVE = 0xFFEB0400.toInt()
        const val AMBER = 0xFFFFB31A.toInt()
        const val OFF = 0xFF53535F.toInt()
        const val HEADER_DP = 40
        const val MIN_PLAYER_DP = 56

        /** Viewer counts the way Twitch writes them: 950, 12.3K, 1.2M. */
        fun count(n: Int): String {
            val f = DecimalFormat("0.#")
            return when {
                n >= 1_000_000 -> f.format((n / 100_000) / 10.0) + "M"
                n >= 1_000 -> f.format((n / 100) / 10.0) + "K"
                else -> n.toString()
            }
        }
    }
}

/**
 * One stream's strip: a live dot, its name and viewer count, then its buttons. Narrow strips (two
 * side by side on a phone held upright, or a folding phone's outer screen) fold "change channel"
 * and "watch in Twitch" into one menu button, so the name still has room.
 */
@SuppressLint("ViewConstructor")
internal class DualHeader(ctx: Context, private val index: Int, private val ui: TwoStreamsRoot.Ui) : ViewGroup(ctx) {

    private val dotBg = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(TwoStreamsRoot.OFF) }
    private val dot = View(ctx).apply { background = dotBg }
    private val name = TextView(ctx).apply {
        setTextColor(TwoStreamsRoot.TEXT)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val viewers = TextView(ctx).apply {
        setTextColor(TwoStreamsRoot.DIM)
        textSize = 12f
        isSingleLine = true
        includeFontPadding = false
    }
    private val speaker = DualIcons.Speaker()
    private val sound = button(speaker) { showVolume() }
    private val swap = button(DualIcons.Swap()) { ui.swap(index) }
    private val watch = button(DualIcons.Expand()) { ui.watch(index) }
    private val close = button(DualIcons.Close()) { ui.dismiss(index) }
    private val more = button(DualIcons.More()) { showMore() }
    /** Called with the new volume, and whether the slider has been let go. */
    var onVolume: ((Int, Boolean) -> Unit)? = null
    var onStacked: ((Boolean) -> Unit)? = null
    private var tall = false
    private var stackedOn = false
    private var level = 100
    private var beforeMute = 100
    private var volumePopup: WeakReference<PopupWindow>? = null
    private var four = true
    private var label = ""
    private var menu: WeakReference<PopupMenu>? = null

    init {
        setBackgroundColor(TwoStreamsRoot.HEADER_BG)
        layoutDirection = if (I18n.rtl()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        more.visibility = View.GONE
        for (v in arrayOf(dot, name, viewers, sound, swap, watch, close, more)) addView(v)
        describe()
    }

    private fun button(icon: Drawable, onTap: () -> Unit) = ImageView(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null)
        setOnClickListener { onTap() }
    }

    fun bind(inf: DualPlayer.Info) {
        label = inf.displayName?.takeIf { it.isNotBlank() } ?: inf.login
        if (name.text?.toString() != label) name.text = label
        dotBg.setColor(when (inf.state) {
            DualPlayer.State.PLAYING, DualPlayer.State.BUFFERING -> TwoStreamsRoot.LIVE
            DualPlayer.State.ERROR -> TwoStreamsRoot.AMBER
            DualPlayer.State.OFFLINE -> TwoStreamsRoot.OFF
            else -> TwoStreamsRoot.DIM
        })
        val live = inf.state == DualPlayer.State.PLAYING || inf.state == DualPlayer.State.BUFFERING
        val count = when {
            live -> inf.viewers?.let { TwoStreamsRoot.count(it) }.orEmpty()
            inf.state == DualPlayer.State.OFFLINE -> I18n.t("Offline")
            else -> ""
        }
        if (viewers.text?.toString() != count) { viewers.text = count; requestLayout() }
        // Long press on the name shows what the stream is about.
        val about = listOfNotNull(inf.title?.takeIf { it.isNotBlank() }, inf.game?.takeIf { it.isNotBlank() }).joinToString("\n")
        name.tooltipText = about.ifEmpty { null }
        describe()
    }

    /** In a tall window the menu button stays, as it holds the stacked chats switch. */
    fun setTall(isTall: Boolean, stacked: Boolean) {
        stackedOn = stacked
        if (tall == isTall) return
        tall = isTall
        requestLayout()
    }

    fun setLevel(p: Int) {
        if (level == p) return
        level = p
        if (p > 0) beforeMute = p
        speaker.loudness = p
        describe()
    }

    fun openVolume() = showVolume()

    /** A small slider under the speaker button, with the volume as a number. */
    private fun showVolume() {
        closeMenu()
        val ctx = context
        val icon = DualIcons.Speaker().apply { loudness = this@DualHeader.level }
        val number = TextView(ctx).apply {
            setTextColor(TwoStreamsRoot.TEXT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "${this@DualHeader.level}%"
        }
        val bar = SeekBar(ctx).apply {
            max = 100
            progress = this@DualHeader.level
            progressTintList = ColorStateList.valueOf(FilterIcons.PURPLE)
            thumbTintList = ColorStateList.valueOf(FilterIcons.PURPLE)
            contentDescription = I18n.t("Volume") + if (label.isEmpty()) "" else " ($label)"
        }
        fun show(p: Int) { icon.loudness = p; number.text = "$p%" }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                show(p)
                if (fromUser) onVolume?.invoke(p, false)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onVolume?.invoke(s.progress, true) }
        })
        val mute = ImageView(ctx).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val ip = dp(7)
            setPadding(ip, ip, ip, ip)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, null)
            contentDescription = I18n.t("Volume")
            setOnClickListener {
                val p = if (this@DualHeader.level > 0) 0 else beforeMute.coerceAtLeast(1)
                bar.progress = p
                onVolume?.invoke(p, true)
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            background = GradientDrawable().apply {
                setColor(0xF21F1F23.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), FilterIcons.PURPLE)
            }
            addView(mute, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(number, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val room = (parent as? View)?.width?.takeIf { it > 0 } ?: width
        val pw = PopupWindow(box, min(dp(260), max(dp(160), min(room, width) - dp(8))), dp(48), true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        pw.showAsDropDown(sound, 0, dp(2), Gravity.END)
        volumePopup = WeakReference(pw)
    }

    /** Spoken names for every button; the channel name tells the two strips apart. */
    private fun describe() {
        val who = if (label.isEmpty()) "" else " ($label)"
        sound.contentDescription = I18n.t("Volume") + who + " $level%"
        swap.contentDescription = I18n.t("Change channel") + who
        watch.contentDescription = I18n.t("Watch in Twitch") + who
        close.contentDescription = I18n.t("Close this stream") + who
        more.contentDescription = I18n.t("More options") + who
    }

    private fun showMore() {
        closeMenu()
        val pm = PopupMenu(ContextThemeWrapper(context, android.R.style.Theme_Material), more)
        pm.menu.add(0, 1, 0, I18n.t("Change channel"))
        pm.menu.add(0, 2, 1, I18n.t("Watch in Twitch"))
        if (tall) pm.menu.add(0, 3, 2, I18n.t("Stacked chats")).apply { isCheckable = true; isChecked = stackedOn }
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> ui.swap(index); 2 -> ui.watch(index); 3 -> onStacked?.invoke(!stackedOn) }
            true
        }
        pm.show()
        menu = WeakReference(pm)
    }

    fun closeMenu() {
        menu?.get()?.dismiss()
        menu = null
        volumePopup?.get()?.dismiss()
        volumePopup = null
    }

    override fun onDetachedFromWindow() {
        closeMenu()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.getDefaultSize(0, widthMeasureSpec), View.getDefaultSize(0, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val pad = dp(10)
        val dotS = dp(8)
        val gap = dp(6)
        val textStart = pad + dotS + gap
        val minName = dp(64)
        val wantFour = !tall && w - textStart - minName >= 4 * dp(36)
        if (wantFour != four) {
            four = wantFour
            val v = if (four) View.VISIBLE else View.GONE
            swap.visibility = v
            watch.visibility = v
            more.visibility = if (four) View.GONE else View.VISIBLE
        }
        val btns = if (four) arrayOf(sound, swap, watch, close) else arrayOf(sound, more, close)
        val lo = min(dp(28), h)
        val bw = ((w - textStart - minName) / btns.size).coerceIn(lo, max(lo, min(h, dp(44))))
        // The icon keeps its share of the button, so small buttons still show a readable icon.
        val ip = (bw * 0.24f).toInt()
        var x = w - dp(2)
        for (k in btns.indices.reversed()) {
            val v = btns[k]
            if (v.paddingLeft != ip) v.setPadding(ip, ip, ip, ip)
            put(v, w, x - bw, (h - bw) / 2, x, (h + bw) / 2)
            x -= bw
        }
        val textEnd = x - dp(4)
        put(dot, w, pad, (h - dotS) / 2, pad + dotS, (h + dotS) / 2)
        val room = max(0, textEnd - textStart)
        viewers.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        val vw = if (viewers.text.isNullOrEmpty()) 0 else viewers.measuredWidth
        val withViewers = vw > 0 && room - vw - gap >= dp(48)
        val nameMax = if (withViewers) room - vw - gap else room
        name.measure(View.MeasureSpec.makeMeasureSpec(nameMax, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        val nw = name.measuredWidth
        val nh = name.measuredHeight
        put(name, w, textStart, (h - nh) / 2, textStart + nw, (h - nh) / 2 + nh)
        if (withViewers) {
            val vx = textStart + nw + gap
            val vh = viewers.measuredHeight
            put(viewers, w, vx, (h - vh) / 2, vx + vw, (h - vh) / 2 + vh)
        } else put(viewers, w, 0, 0, 0, 0)
    }

    /** Lay [v] out at these left-to-right bounds, mirrored for right-to-left languages. */
    private fun put(v: View, w: Int, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val ll = if (rtl) w - r else l
        val rr = if (rtl) w - l else r
        v.measure(View.MeasureSpec.makeMeasureSpec(max(0, rr - ll), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(max(0, b - t), View.MeasureSpec.EXACTLY))
        v.layout(ll, t, max(ll, rr), max(t, b))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}

/** The screen's icons, drawn in code like the other player buttons so they stay sharp. */
internal object DualIcons {

    /** Two screens, one over the other: the player button that opens two streams. */
    class Screens : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            val r = s * 0.07f
            c.drawRoundRect(RectF(s * 0.2f, s * 0.17f, s * 0.8f, s * 0.46f), r, r, stroke)
            c.drawRoundRect(RectF(s * 0.2f, s * 0.54f, s * 0.8f, s * 0.83f), r, r, stroke)
        }
    }

    /** A speaker: two sound waves above 50%, one at 50% or less, an outline with a cross at 0. */
    class Speaker : FilterIcons.Icon() {
        var loudness = 100
            set(v) {
                if (field == v) return
                field = v
                color = if (v > 0) Color.WHITE else TwoStreamsRoot.DIM
                invalidateSelf()
            }

        override fun paint(c: Canvas, s: Float) {
            val body = Path().apply {
                moveTo(s * 0.14f, s * 0.38f); lineTo(s * 0.30f, s * 0.38f); lineTo(s * 0.50f, s * 0.20f)
                lineTo(s * 0.50f, s * 0.80f); lineTo(s * 0.30f, s * 0.62f); lineTo(s * 0.14f, s * 0.62f); close()
            }
            if (loudness > 0) {
                c.drawPath(body, fill)
                c.drawArc(RectF(s * 0.44f, s * 0.36f, s * 0.72f, s * 0.64f), -50f, 100f, false, stroke)
                if (loudness > 50) c.drawArc(RectF(s * 0.40f, s * 0.27f, s * 0.86f, s * 0.73f), -50f, 100f, false, stroke)
            } else {
                c.drawPath(body, stroke)
                c.drawLine(s * 0.64f, s * 0.40f, s * 0.84f, s * 0.60f, stroke)
                c.drawLine(s * 0.84f, s * 0.40f, s * 0.64f, s * 0.60f, stroke)
            }
        }
    }

    /** Two arrows the opposite way: put another channel here. */
    class Swap : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            val p = Path().apply {
                moveTo(s * 0.18f, s * 0.34f); lineTo(s * 0.80f, s * 0.34f)
                moveTo(s * 0.67f, s * 0.21f); lineTo(s * 0.80f, s * 0.34f); lineTo(s * 0.67f, s * 0.47f)
                moveTo(s * 0.82f, s * 0.66f); lineTo(s * 0.20f, s * 0.66f)
                moveTo(s * 0.33f, s * 0.53f); lineTo(s * 0.20f, s * 0.66f); lineTo(s * 0.33f, s * 0.79f)
            }
            c.drawPath(p, stroke)
        }
    }

    /** Four corners pointing out: carry on with this stream alone, in Twitch. */
    class Expand : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            val a = 0.2f
            val b = 0.8f
            val k = 0.22f
            val p = Path().apply {
                moveTo(s * a, s * (a + k)); lineTo(s * a, s * a); lineTo(s * (a + k), s * a)
                moveTo(s * (b - k), s * a); lineTo(s * b, s * a); lineTo(s * b, s * (a + k))
                moveTo(s * b, s * (b - k)); lineTo(s * b, s * b); lineTo(s * (b - k), s * b)
                moveTo(s * (a + k), s * b); lineTo(s * a, s * b); lineTo(s * a, s * (b - k))
            }
            c.drawPath(p, stroke)
        }
    }

    /** A cross: close this stream, the other one carries on in Twitch. */
    class Close : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawLine(s * 0.27f, s * 0.27f, s * 0.73f, s * 0.73f, stroke)
            c.drawLine(s * 0.73f, s * 0.27f, s * 0.27f, s * 0.73f, stroke)
        }
    }

    /** Three dots: the menu that holds the buttons a narrow strip has no room for. */
    /** Points down: closes a panel that came up from below. */
    class ChevronDown : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawLine(s * 0.2f, s * 0.36f, s * 0.5f, s * 0.66f, stroke)
            c.drawLine(s * 0.5f, s * 0.66f, s * 0.8f, s * 0.36f, stroke)
        }
    }

    /** A cog: Twitch's main settings. */
    class Gear : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.13f, stroke)
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.29f, stroke)
            for (k in 0 until 8) {
                c.save()
                c.rotate(k * 45f, s * 0.5f, s * 0.5f)
                c.drawLine(s * 0.5f, s * 0.21f, s * 0.5f, s * 0.11f, stroke)
                c.restore()
            }
        }
    }

    /** A channel points coin. */
    class Points : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.34f, stroke)
            c.drawArc(RectF(s * 0.34f, s * 0.34f, s * 0.66f, s * 0.66f), 180f, 90f, false, stroke)
        }
    }

    /** A smiling face: the emote picker. */
    class Smile : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawCircle(s * 0.5f, s * 0.5f, s * 0.34f, stroke)
            c.drawCircle(s * 0.39f, s * 0.42f, s * 0.04f, fill)
            c.drawCircle(s * 0.61f, s * 0.42f, s * 0.04f, fill)
            c.drawArc(RectF(s * 0.34f, s * 0.36f, s * 0.66f, s * 0.68f), 25f, 130f, false, stroke)
        }
    }

    /** A rounded box with its arrow pointing down at the button under it. */
    class DownBubble(private val bg: Int, private val edge: Int, private val r: Float, private val w: Float,
                     private val arrow: Float) : Drawable() {
        var arrowX = 0f
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edge; style = Paint.Style.STROKE; strokeWidth = w }

        override fun draw(c: Canvas) {
            val b = bounds
            val box = RectF(b.left + w, b.top + w, b.right - w, b.bottom - arrow - w)
            val p = Path().apply {
                addRoundRect(box, r, r, Path.Direction.CW)
                val t = Path().apply {
                    moveTo(arrowX - arrow, box.bottom - 1f)
                    lineTo(arrowX, b.bottom.toFloat() - w)
                    lineTo(arrowX + arrow, box.bottom - 1f)
                    close()
                }
                op(t, Path.Op.UNION)
            }
            c.drawPath(p, fillPaint)
            c.drawPath(p, line)
        }

        override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; line.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { fillPaint.colorFilter = cf; line.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    class More : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            for (k in 0..2) c.drawCircle(s * 0.5f, s * (0.26f + k * 0.24f), s * 0.07f, fill)
        }
    }
}
