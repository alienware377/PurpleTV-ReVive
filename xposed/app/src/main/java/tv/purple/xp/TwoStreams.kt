package tv.purple.xp

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * "Two streams": a second live channel beside the one on screen, each with its own chat.
 *
 * A button in the live player's top row lists the followed channels that are live, with a box to
 * type any other name. Picking one lays a screen over Twitch with both players and both chats. The
 * players are our own, built on the same IVS player Twitch uses, so Twitch's player is simply paused
 * (and muted) underneath and carries on where it was when the screen closes. Leaving the app
 * shrinks both players, stacked, into picture-in-picture.
 *
 * Each stream has its own volume, from the speaker button in its strip. Back closes the screen and Twitch's own player plays again. Each stream's strip can also carry on
 * with that stream alone in Twitch, or close it and carry on with the other.
 */
object TwoStreams {

    const val KEY = "show_dual_button"
    private const val TAG_BUTTON = "ptv_dual_button"
    private const val SPLASH = "tv.twitch.android.app.core.LandingActivity"
    /** How long after a launcher tap the main screen's new intent still counts as that tap. */
    private const val LAUNCHER_MS = 5_000L

    private val ROW = arrayOf("create_clip_text_button", "create_clip_button_compose_view", "switch_to_vertical_view",
        "cast_button", "media_route_button", "share_button", "settings_button", "audio_and_subtitles")
    /** Our other player buttons, so this one starts out left of all of them; BarFit has the final say. */
    private val OURS = arrayOf("ptv_sleep_timer", "ptv_filters_button", "ptv_refresh_button", "ptv_clip_save", "ptv_stats_button")

    private val main = Handler(Looper.getMainLooper())

    /** The screen while it is open. Read from player threads too, hence volatile. */
    @Volatile private var screen: TwoStreamsRoot? = null
    @Volatile private var host: WeakReference<Activity>? = null
    /** The channel Twitch's own player had when the screen opened. */
    private var twitchLogin: String? = null
    private var added = false
    /** Paused because the app went to the background, so coming back plays again. */
    private var pausedByStop = false
    /** Paused by the sleep timer: stays paused until a player is tapped. */
    private var sleeping = false
    private var backCb: Any? = null
    private var picker: WeakReference<Dialog>? = null
    private var refreshPosted = false
    /**
     * Twitch's views under the screen, hidden from screen readers and kept out of focus while it
     * is open, with their old accessibility and focus settings (-1 for a view that is not a group).
     */
    private val hidden = ArrayList<Triple<WeakReference<View>, Int, Int>>()
    private val stopped = WeakHashMap<Activity, Boolean>()

    fun isOpen(): Boolean = screen != null

    /** What the live captions follow while this screen is open. The first three work from any thread. */
    fun captionPlayer(): Any? = screen?.captionPlayer()
    fun captionLogin(): String? = screen?.captionLogin()
    fun captionSince(): Long? = screen?.captionSince()
    fun captionView(): DualPlayer? = screen?.captionView()

    // ---- install ----

    fun install(lp: LoadPackageParam) {
        runCatching { OwnPlayers.install(lp) }.onFailure { log("TS own players failed: $it") }
        runCatching { DualTracker.install(lp) }.onFailure { log("TS tracker failed: $it") }
        runCatching { DualCards.install(lp) }.onFailure { log("TS cards failed: $it") }
        runCatching { SettingsPip.install(lp) }.onFailure { log("TS settings failed: $it") }
        runCatching { TwoStreamPip.install() }.onFailure { log("TS picture-in-picture failed: $it") }
        TwoStreamPip.hint = { screen?.pipHint() }
        TwoStreamPip.onPip = { a, on -> if (host?.get() === a) pipChanged(a, on) }
        TwoStreamPip.onLeaving = { a -> closePicker(); hideKeyboard(a); screen?.closeMenus() }

        var n = 0
        for (m in arrayOf("onStart", "onStop"))
            n += runCatching { XposedBridge.hookAllMethods(Activity::class.java, m, lifeHook).size }.getOrDefault(0)
        n += runCatching { XposedBridge.hookAllMethods(Activity::class.java, "onNewIntent", newIntentHook).size }.getOrDefault(0)
        n += runCatching { XposedBridge.hookAllMethods(Activity::class.java, "onCreate", splashHook).size }.getOrDefault(0)
        // Before Android saves the app's state: a panel saved with it would come back with no place to go.
        n += runCatching {
            XposedBridge.hookAllMethods(android.app.Instrumentation::class.java, "callActivityOnSaveInstanceState", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (host?.get() === p.args[0]) runCatching { screen?.closeSheet() }
                }
            }).size
        }.getOrDefault(0)
        val core = runCatching { lp.classLoader.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull()
        stateHooked = core != null &&
            runCatching { XposedBridge.hookAllMethods(core, "handleStateChange", stateHook).isNotEmpty() }.getOrDefault(false)
        // Only the public release(): the native one it calls takes the player handle instead.
        val rel = core?.let { c -> runCatching { c.getMethod("release") }.getOrNull() }
        releaseHooked = rel != null && runCatching { XposedBridge.hookMethod(rel, releaseHook); true }.getOrDefault(false)
        log("TS installed: $n activity hooks, Twitch's player state ${if (stateHooked) "watched" else "polled"}, " +
            "release ${if (releaseHooked) "watched" else "not watched"}")
    }

    private val lifeHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val a = p.thisObject as? Activity ?: return
            val stop = p.method.name == "onStop"
            stopped[a] = stop
            if (screen == null || host?.get() !== a) return
            runCatching { if (stop) hostStopped(a) else hostStarted(a) }.onFailure { log("TS lifecycle: $it") }
        }
    }

    /**
     * Every launch goes through Twitch's splash screen (LandingActivity), which then hands the main
     * screen its own intent with extras, so the launcher icon can only be told apart there: a plain
     * MAIN/LAUNCHER intent with no address.
     */
    @Volatile private var launcherAt = 0L

    private val splashHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val a = p.thisObject as? Activity ?: return
            if (a.javaClass.name != SPLASH) return
            val i = a.intent ?: return
            if (i.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER) && i.data == null)
                launcherAt = SystemClock.uptimeMillis()
        }
    }

    /**
     * Twitch was asked to open something (a notification, a link). The screen gives way to it. The
     * launcher icon only brings the app back, so that one leaves the screen as it is.
     */
    private val newIntentHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val a = p.thisObject as? Activity ?: return
            if (screen == null || host?.get() !== a) return
            val i = p.args.getOrNull(0) as? Intent
            if (i?.hasExtra(SettingsPip.EXTRA) == true) return
            if (i != null && i.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER)) return
            if (i?.data == null && SystemClock.uptimeMillis() - launcherAt < LAUNCHER_MS) return
            // At once, not posted: Twitch starts loading what was asked for right after this, and its
            // player must not be held paused by us while it does.
            runCatching { shut(resume = false, navigate = null, why = "Twitch opened a link") }.onFailure { log("TS close on link: $it") }
        }
    }

    private fun hostStopped(a: Activity) {
        if (a.isInPictureInPictureMode) return
        screen?.pauseAll()
        pausedByStop = true
        syncMedia()
        closePicker()
        log("TS app left without picture-in-picture: both paused")
    }

    private fun hostStarted(a: Activity) {
        if (!a.isInPictureInPictureMode) screen?.setPip(false)
        if (pausedByStop) {
            pausedByStop = false
            if (!sleeping) screen?.resumeAll()
            syncMedia()
        }
        // Twitch may start its own player again when it comes back to the front.
        holdTwitch()
        main.postDelayed(holdNow, 500)
    }

    private fun pipChanged(a: Activity, on: Boolean) {
        // Leaving picture-in-picture while the app is in the background means it was swiped away.
        if (!on && stopped[a] == true) {
            shut(resume = false, navigate = null, why = "picture-in-picture closed")
            return
        }
        screen?.setPip(on)
        if (on) { closePicker(); hideKeyboard(a) }
        // Twitch switches its own player's mode right after this; make sure it stays paused.
        a.window?.decorView?.post(holdNow)
        log("TS picture-in-picture ${if (on) "on" else "off"}")
    }

    // ---- open and close ----

    /** Open the screen on [act] with [a] (normally Twitch's live channel, RefreshButton.liveLogin()) and [b]. */
    fun open(act: Activity, a: String, b: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { open(act, a, b) }; return }
        val la = DualUsher.cleanLogin(a)
        val lb = DualUsher.cleanLogin(b)
        if (la == null || lb == null) { toast(act, "That is not a channel name"); log("TS open refused: not a channel name"); return }
        if (la == lb) { toast(act, "Pick a different channel for the second stream"); return }
        if (act.isFinishing || act.isDestroyed) return
        val cur = screen
        if (cur != null && host?.get() === act) {
            if (cur.login(0) != la) cur.show(0, la)
            if (cur.login(1) != lb) cur.show(1, lb)
            log("TS streams now $la + $lb")
            return
        }
        if (cur != null) shut(resume = false, navigate = null, why = "opened again elsewhere")
        val decor = act.window?.decorView as? ViewGroup ?: return
        twitchLogin = RefreshButton.liveLogin()
        sleeping = false
        pausedByStop = false
        val r = TwoStreamsRoot(act, ui)
        screen = r
        host = WeakReference(act)
        try {
            decor.addView(r, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            r.show(0, la)
            r.show(1, lb)
        } catch (t: Throwable) {
            log("TS open failed: $t")
            toast(act, "Two streams could not start")
            shut(resume = true, navigate = null, why = "failed to open")
            return
        }
        hideKeyboard(act)
        r.requestFocus()
        hideSiblings(decor, r)
        holdTwitch()
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1000)
        registerBack(act)
        hookBack(act)
        TwoStreamPip.open(act)
        listenForAudio(act)
        log("TS open: $la + $lb (Twitch's player on ${twitchLogin ?: "unknown"})")
    }

    /**
     * Close the screen; stream [keep] (0 or 1) carries on in Twitch. When that is the channel
     * Twitch's own player already has, it simply plays again; any other channel is opened in Twitch.
     */
    fun close(keep: Int = 0) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { close(keep) }; return }
        val r = screen ?: return
        val k = keep.coerceIn(0, 1)
        val login = r.login(k)
        val tw = twitchLogin
        if (login == null || login == tw || (tw == null && k == 0)) shut(resume = true, navigate = null, why = "keeping stream ${k + 1}")
        else shut(resume = false, navigate = login, why = "keeping $login")
    }

    /**
     * Take the screen down. [resume] plays Twitch's own player again; [navigate] opens that channel
     * in Twitch instead. Neither happens while the app is in the background, so nothing starts
     * playing out of sight.
     */
    private fun shut(resume: Boolean, navigate: String?, why: String) {
        val r = screen ?: return
        val act = host?.get()
        screen = null
        host = null
        main.removeCallbacks(watchdog)
        main.removeCallbacks(holdNow)
        closePicker()
        FiltersPanel.closeFor(null)
        stopListening()
        val away = act == null || stopped[act] == true
        if (act != null) {
            unregisterBack(act)
            runCatching { TwoStreamPip.close(act) }.onFailure { log("TS pip close: $it") }
            hideKeyboard(act)
        } else TwoStreamPip.close(null)
        showSiblings()
        runCatching { r.releaseAll() }.onFailure { log("TS release: $it") }
        runCatching { SideEmotes.clearPictures() }
        runCatching { (r.parent as? ViewGroup)?.removeView(r) }
        releaseTwitch(resume && !away)
        if (navigate != null && act != null && !away) runCatching {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("twitch://stream/$navigate")).setPackage(act.packageName))
        }.onFailure { log("TS open in Twitch failed: $it") }
        sleeping = false
        pausedByStop = false
        log("TS closed: $why")
    }

    /** The sleep timer ran out: both streams pause, and stay paused until a player is tapped. */
    fun onSleep() = pauseBoth("the sleep timer")

    /** Both streams pause and stay paused until a player is tapped or a media key plays them again. */
    private fun pauseBoth(why: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { pauseBoth(why) }; return }
        val r = screen ?: return
        r.pauseAll()
        sleeping = true
        syncMedia()
        log("TS paused by $why")
    }

    private fun wake() {
        if (!sleeping) return
        sleeping = false
        // In the background without picture-in-picture, coming back to the app plays them.
        if (!pausedByStop) screen?.resumeAll()
        syncMedia()
    }

    private fun replaceStream(i: Int, login: String) {
        val r = screen ?: return
        r.show(i, login)
        log("TS stream ${i + 1} is now $login")
    }

    private val ui = object : TwoStreamsRoot.Ui {
        override fun back() { if (screen?.closeSheet() != true) shut(resume = true, navigate = null, why = "back") }
        override fun tapPlayer(i: Int) {
            wake()
            screen?.tapControls(i)
        }
        override fun played() {
            sleeping = false
            syncMedia()
        }
        override fun swap(i: Int) { host?.get()?.let { openPicker(it, i) } }
        override fun watch(i: Int) = close(i)
        override fun dismiss(i: Int) = close(1 - i)
        override fun laidOut() {
            if (refreshPosted) return
            refreshPosted = true
            main.post {
                refreshPosted = false
                host?.get()?.let { runCatching { TwoStreamPip.refresh(it) } }
            }
        }
        override fun detached(root: TwoStreamsRoot) {
            if (screen === root) shut(resume = false, navigate = null, why = "the window went away")
        }
    }

    // ---- Twitch's own player, held paused underneath ----

    private class Ivs(val pause: Method, val play: Method, val isMuted: Method, val setMuted: Method, val getState: Method)
    @Volatile private var ivsMethods: Ivs? = null
    /** Twitch players held while the screen is open, each with whether it was muted before. Guarded by [twitchLock]. */
    private val holds = WeakHashMap<Any, Boolean>()
    /** The player held last, for the status line and to know which one to play again. */
    @Volatile private var lastHeld: WeakReference<Any>? = null
    /** Whether Twitch's stream was playing when the screen opened: one paused by hand stays paused after. */
    private var heldWasPlaying = true
    private var heldOnce = false
    private var stateHooked = false
    private var releaseHooked = false
    private var repauses = 0

    /**
     * Twitch releases its player on a thread of its own, so a check on the main thread that it is
     * still Twitch's current one could pass just before the release and the call land just after,
     * on freed native memory. Every call on it holds this lock, and so does the release hook: a
     * release waits for a call already under way, and no call starts on a player being released.
     */
    private val twitchLock = Any()
    /** Players whose release has begun. Guarded by [twitchLock]. */
    private val releasing = WeakHashMap<Any, Boolean>()

    private val releaseHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val me = p.thisObject ?: return
            if (OwnPlayers.isMine(me)) return
            synchronized(twitchLock) { releasing[me] = true }
        }
    }

    /**
     * Not on its way out. With the release hook in place every release is seen, so a held player that
     * is not marked is still alive even when Twitch shows another one; without it only the player
     * Twitch shows now is trusted. Call with [twitchLock] held.
     */
    private fun usable(p: Any): Boolean = releasing[p] != true && (releaseHooked || twitchPlayer() === p)

    /**
     * The player Twitch shows: the one it last gave a picture surface, else the one that last loaded a
     * stream address. On 31.7.0 the player that loads the address is not always the one that plays
     * it, so RefreshButton's alone can be an idle one. Both forget a player the moment it is released.
     */
    private fun twitchPlayer(): Any? =
        PlayerStats.shownPlayer()?.takeIf { it !== PbypWatch.player() && !OwnPlayers.isMine(it) }
            ?: RefreshButton.currentPlayer()?.takeIf { !OwnPlayers.isMine(it) }

    /** Resolved once from the public Player interface: CorePlayerImpl itself is not public. */
    private fun ivs(p: Any): Ivs? {
        ivsMethods?.let { return it }
        return runCatching {
            val c = Class.forName("com.amazonaws.ivs.player.Player", false, p.javaClass.classLoader)
            Ivs(c.getMethod("pause"), c.getMethod("play"), c.getMethod("isMuted"),
                c.getMethod("setMuted", Boolean::class.javaPrimitiveType), c.getMethod("getState"))
        }.onFailure { log("TS player methods: $it") }.getOrNull()?.also { ivsMethods = it }
    }

    private val holdNow = Runnable { holdTwitch() }

    private val watchdog = object : Runnable {
        override fun run() {
            if (screen == null) return
            holdTwitch()
            main.postDelayed(this, 1000)
        }
    }

    /** Twitch's player reports a new state on its own thread; playing again while open is undone at once. */
    private val stateHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            if (screen == null) return
            val me = p.thisObject ?: return
            val code = p.args.getOrNull(0) as? Int ?: return
            if (code != 2 && code != 3) return
            if (OwnPlayers.isMine(me)) return
            val isHeld = synchronized(twitchLock) { holds.containsKey(me) }
            // A new player Twitch made while the screen is open is held as soon as it starts.
            if (isHeld || twitchPlayer() === me) main.post(holdNow)
        }
    }

    /**
     * Keep Twitch's players paused and muted while the screen is open: the one it shows now, and any
     * held earlier that started again. A released player is never called again (see [usable]).
     */
    private fun holdTwitch() {
        if (screen == null) return
        val cur = twitchPlayer()
        synchronized(twitchLock) {
            // Just paused in this pass: it can still read as playing for a moment.
            var fresh: Any? = null
            if (cur != null && !holds.containsKey(cur) && releasing[cur] != true) ivs(cur)?.let { hold(cur, it); fresh = cur }
            for (p in weakKeys(holds)) {
                if (p === fresh || !usable(p)) continue
                val m = ivs(p) ?: continue
                val st = stateName(m, p)
                if (st != "PLAYING" && st != "BUFFERING") continue
                runCatching { m.setMuted.invoke(p, true) }
                runCatching { m.pause.invoke(p) }
                repauses++
                if (repauses <= 5 || repauses % 20 == 0) log("TS Twitch's player started again, paused it ($repauses)")
            }
        }
    }

    private fun hold(p: Any, m: Ivs) {
        val muted = runCatching { m.isMuted.invoke(p) as? Boolean }.getOrNull() ?: false
        val st = stateName(m, p)
        if (!heldOnce) {
            heldOnce = true
            heldWasPlaying = st == null || st == "PLAYING" || st == "BUFFERING"
        }
        holds[p] = muted
        lastHeld = WeakReference(p)
        runCatching { m.setMuted.invoke(p, true) }
        runCatching { m.pause.invoke(p) }
        log("TS Twitch's player paused (it was $st)")
    }

    private fun stateName(m: Ivs, p: Any): String? =
        runCatching { m.getState.invoke(p) }.getOrNull()?.let { (it as? Enum<*>)?.name ?: it.toString() }

    /**
     * Give every held player its sound back, and play Twitch's stream again when [play] and it was
     * playing when the screen opened.
     */
    private fun releaseTwitch(play: Boolean) {
        val wasPlaying = heldWasPlaying
        val last = lastHeld?.get()
        heldOnce = false
        heldWasPlaying = true
        repauses = 0
        lastHeld = null
        synchronized(twitchLock) {
            val all = weakKeys(holds).map { it to (holds[it] ?: false) }
            holds.clear()
            for ((p, muted) in all) {
                if (!usable(p)) continue
                val m = ivs(p) ?: continue
                runCatching { m.setMuted.invoke(p, muted) }
            }
            if (!play || !wasPlaying) return
            val cur = twitchPlayer()
            val target = cur?.takeIf { c -> all.any { it.first === c } } ?: last?.takeIf { l -> all.any { it.first === l } }
            if (target != null && usable(target)) ivs(target)?.let { m ->
                runCatching { m.play.invoke(target) }.onFailure { log("TS Twitch's player did not play: $it") }
            }
        }
    }

    // ---- back ----

    /**
     * Android 16 apps get back through a callback instead of the back key, and ours goes ahead of
     * Twitch's own. On older versions it is never called and the screen's key handling does the job.
     * It also goes ahead of the keyboard's, so it closes the keyboard, then a text selection, before
     * the screen, as the back key would.
     */
    private fun registerBack(act: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            val cb = android.window.OnBackInvokedCallback {
                val a = host?.get()
                val decor = a?.window?.decorView
                val f = decor?.findFocus() as? EditText
                when {
                    screen == null -> {}
                    a != null && decor?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true -> hideKeyboard(a)
                    f != null && f.hasSelection() -> f.setSelection(f.selectionEnd)
                    screen?.closeSheet() == true -> {}
                    else -> shut(resume = true, navigate = null, why = "back")
                }
            }
            act.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
            backCb = cb
        }.onFailure { log("TS back callback: $it") }
    }

    private fun unregisterBack(act: Activity) {
        val cb = backCb ?: return
        backCb = null
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            act.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb as android.window.OnBackInvokedCallback)
        }.onFailure { log("TS back callback: $it") }
    }

    private val backHooked = HashSet<Class<*>>()

    /**
     * Last line of defence: when back reaches the activity anyway (focus was outside the screen),
     * the first onBackPressed up its classes closes the screen. Hooking Activity's own would miss it,
     * because the AndroidX activity overrides it without calling up.
     */
    private fun hookBack(act: Activity) {
        var c: Class<*>? = act.javaClass
        while (c != null && c != Any::class.java) {
            val k: Class<*> = c
            val m = runCatching { k.getDeclaredMethod("onBackPressed") }.getOrNull()
            if (m != null) {
                if (backHooked.add(k)) runCatching { XposedBridge.hookMethod(m, backHook) }.onFailure { log("TS back hook: $it") }
                return
            }
            c = k.superclass
        }
    }

    private val backHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            if (screen == null || host?.get() !== p.thisObject) return
            if (screen?.closeSheet() != true) shut(resume = true, navigate = null, why = "back")
            p.result = null
        }
    }

    // ---- headphones and media keys ----

    /** Unplugging headphones pauses both streams, as in any player. */
    private var noisy: BroadcastReceiver? = null
    private var noisyCtx: Context? = null
    /**
     * Our own media session, so headset, Bluetooth and lock-screen keys reach the two streams.
     * Without it they go to Twitch's session and its paused player underneath.
     */
    private var session: MediaSession? = null

    private fun listenForAudio(act: Activity) {
        stopListening()
        val app = act.applicationContext
        runCatching {
            val rcv = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseBoth("headphones unplugged")
                }
            }
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(rcv, f)
            noisy = rcv
            noisyCtx = app
        }.onFailure { log("TS headphones receiver: $it") }
        runCatching {
            val s = MediaSession(app, "ptv_dual")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPause() = pauseBoth("a media key")
                override fun onStop() = pauseBoth("a media key")
                override fun onPlay() = wake()
            }, main)
            session = s
            syncMedia()
            s.isActive = true
        }.onFailure { log("TS media session: $it") }
    }

    private fun stopListening() {
        val rcv = noisy
        val c = noisyCtx
        noisy = null
        noisyCtx = null
        if (rcv != null && c != null) runCatching { c.unregisterReceiver(rcv) }
        val s = session ?: return
        session = null
        runCatching { s.isActive = false; s.release() }.onFailure { log("TS media session: $it") }
    }

    /** Whether the streams play, so a play/pause key does the right thing. */
    private fun syncMedia() {
        val s = session ?: return
        val on = !sleeping && !pausedByStop
        runCatching {
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                .setState(if (on) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (on) 1f else 0f)
                .build())
        }
    }

    // ---- helpers ----

    private fun hideKeyboard(a: Activity) {
        val decor = a.window?.decorView ?: return
        runCatching {
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(decor.windowToken, 0)
        }
    }

    /**
     * Screen readers and focus search (keyboard, d-pad) would otherwise still reach Twitch's
     * screen under ours.
     */
    private fun hideSiblings(decor: ViewGroup, keep: View) {
        showSiblings()
        for (k in 0 until decor.childCount) {
            val v = decor.getChildAt(k)
            if (v !== keep) hideOne(v)
        }
    }

    private fun hideOne(v: View) {
        val group = v as? ViewGroup
        hidden.add(Triple(WeakReference(v), v.importantForAccessibility, group?.descendantFocusability ?: -1))
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        group?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun showSiblings() {
        for ((ref, was, wasFocus) in hidden) {
            val v = ref.get() ?: continue
            v.importantForAccessibility = was
            if (wasFocus >= 0) (v as? ViewGroup)?.descendantFocusability = wasFocus
        }
        hidden.clear()
    }

    private fun activityOf(ctx: Context?): Activity? {
        var c = ctx
        while (c != null) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    // ---- the player button ----

    /** From each ViewHider pass: add, place or remove the button. Live streams only. */
    fun attach(root: View) {
        keepOnTop(root)
        // The bar is out of sight under the screen; nothing to place until it closes.
        if (screen != null) return
        val on = Settings.get(KEY, true)
        if (!on && !added) return
        val ctx = root.context
        fun ids(n: String) = Res.id(ctx, n)
        val settingsId = ids("settings_button"); if (settingsId == 0) return
        val bars = ArrayList<ViewGroup>()
        collectBars(root, settingsId, bars)
        val live = RefreshButton.liveShown()
        for (bar in bars) {
            var btn = bar.findViewWithTag<ImageView>(TAG_BUTTON)
            if (!on) {
                if (btn != null) bar.removeView(btn)
                DualTip.remove(bar)
                continue
            }
            val row = ROW.mapNotNull { n -> ids(n).takeIf { it != 0 }?.let { bar.findViewById<View>(it) } }
                .filter { it.parent === bar && it.visibility == View.VISIBLE && it.width > 0 }.toMutableList()
            for (t in OURS) bar.findViewWithTag<View>(t)?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { row.add(it) }
            val ref = sizeRef(bar, row)
            if (ref == null || !live) {
                btn?.visibility = View.GONE
                DualTip.hide(bar)
                continue
            }
            if (btn == null) {
                btn = ImageView(bar.context).apply {
                    tag = TAG_BUTTON
                    setImageDrawable(DualIcons.Screens())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
                    contentDescription = I18n.t("Watch two streams")
                    setOnClickListener { v ->
                        DualTip.done()
                        activityOf(v.context)?.let { openPicker(it) }
                    }
                }
                bar.addView(btn, ViewGroup.LayoutParams(ref.height, ref.height))
                added = true
            }
            matchSize(btn, ref)
            val x = row.minOf { it.x } - ref.height
            if (btn.x != x) btn.x = x
            if (btn.y != ref.y) btn.y = ref.y
            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
            val shownBtn = btn
            runCatching { DualTip.place(bar, shownBtn) { activityOf(shownBtn.context)?.let { openPicker(it) } } }
        }
    }

    /** Twitch can add views to the window's top view later; the screen stays above all of them. */
    private fun keepOnTop(root: View) {
        val r = screen ?: return
        val decor = root as? ViewGroup ?: return
        if (r.parent !== decor) return
        if (decor.getChildAt(decor.childCount - 1) !== r) r.bringToFront()
        // A view Twitch added since the screen opened is kept away from screen readers and focus too.
        for (k in 0 until decor.childCount) {
            val v = decor.getChildAt(k)
            if (v !== r && hidden.none { it.first.get() === v }) hideOne(v)
        }
    }

    private fun collectBars(v: View, settingsId: Int, out: MutableList<ViewGroup>) {
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out && Res.isPlayerBar(it)) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectBars(v.getChildAt(i), settingsId, out)
    }

    // ---- the channel picker ----

    private fun closePicker() {
        picker?.get()?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
        picker = null
    }

    /**
     * The channel list: a box for any channel name, and the followed channels that are live now
     * (loaded in the background), leaving out those already on screen. [replace] is the stream to
     * change while the screen is open, or -1 to open it.
     */
    fun openPicker(act: Activity, replace: Int = -1) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { openPicker(act, replace) }; return }
        if (act.isFinishing || act.isDestroyed) return
        val r = screen
        val swapping = replace in 0..1 && r != null
        val first = RefreshButton.liveLogin()
        if (!swapping && first == null) { toast(act, "Open a live stream first"); return }
        val shown: Set<String> = if (r != null && swapping) setOfNotNull(r.login(0), r.login(1)) else setOfNotNull(first)
        closePicker()

        val b = TBuilder(act, android.R.style.Theme_Material_Dialog_Alert)
        val ctx = b.context
        val pad = dp(ctx, 20f)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(ctx, 8f), pad, 0)
        }
        val input = EditText(ctx).apply {
            hint = I18n.t("Channel name")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        val note = TextView(ctx).apply {
            setTextColor(TwoStreamsRoot.DIM)
            textSize = 13f
            setPadding(0, dp(ctx, 12f), 0, dp(ctx, 6f))
            text = I18n.t("Loading live channels you follow")
        }
        val list = ListView(ctx).apply { visibility = View.GONE; divider = null }
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        var all: List<DualUsher.Live>? = null
        var why = ""
        val showing = ArrayList<DualUsher.Live>()
        val rowH = dp(ctx, 60f)
        val maxH = (act.resources.displayMetrics.heightPixels * 0.38f).toInt().coerceAtLeast(rowH * 2)
        val adapter = object : BaseAdapter() {
            override fun getCount() = showing.size
            override fun getItem(position: Int): Any = showing[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = convertView as? TextView ?: TextView(ctx).apply {
                    setTextColor(TwoStreamsRoot.TEXT)
                    textSize = 16f
                    minimumHeight = rowH
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(ctx, 4f), dp(ctx, 8f), dp(ctx, 4f), dp(ctx, 8f))
                }
                tv.text = row(showing[position])
                return tv
            }
        }
        list.adapter = adapter

        val dlg = b.setTitle(if (swapping) "Change channel" else "Watch two streams")
            .setView(box)
            .setPositiveButton("Watch", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun filter() {
            val q = input.text?.toString()?.trim()?.lowercase().orEmpty()
            showing.clear()
            all?.filter { it.login !in shown && (q.isEmpty() || it.login.contains(q) || it.displayName.lowercase().contains(q)) }
                ?.let { showing.addAll(it) }
            adapter.notifyDataSetChanged()
            val lp = list.layoutParams
            val h = minOf(showing.size * rowH, maxH)
            if (lp.height != h) { lp.height = h; list.layoutParams = lp }
            list.visibility = if (showing.isEmpty()) View.GONE else View.VISIBLE
            note.text = when {
                all == null && why == "out" -> I18n.t("Log in to Twitch to see the channels you follow. You can still type any channel name.")
                all == null && why == "failed" -> I18n.t("Could not load the channels you follow. You can still type a channel name.")
                all == null -> I18n.t("Loading live channels you follow")
                all?.none { it.login !in shown } == true -> I18n.t("No other channel you follow is live right now. You can still type a channel name.")
                showing.isEmpty() -> I18n.t("No other live channel you follow matches that name. Tap Watch to open it anyway.")
                else -> I18n.t("Live channels you follow")
            }
        }

        fun choose(raw: String) {
            val login = DualUsher.cleanLogin(raw)
            if (login == null) { toast(act, if (raw.isBlank()) "Type a channel name" else "That is not a channel name"); return }
            if (login in shown) { toast(act, "That channel is already on screen"); return }
            runCatching { dlg.dismiss() }
            if (swapping) { if (screen != null) replaceStream(replace, login); return }
            val a = RefreshButton.liveLogin()
            if (a == null) { toast(act, "Open a live stream first"); return }
            open(act, a, login)
        }

        list.setOnItemClickListener { _, _, position, _ -> showing.getOrNull(position)?.let { choose(it.login) } }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Some keyboards type a line break for their enter key instead of sending the action.
                val i = s?.indexOf('\n') ?: -1
                if (i >= 0) { s?.delete(i, i + 1); choose(s.toString()); return }
                filter()
            }
        })
        input.setOnEditorActionListener { v, id, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id == EditorInfo.IME_ACTION_GO || enter) { choose(v.text?.toString().orEmpty()); true } else false
        }
        // Our own click handler, so a bad name keeps the list open instead of closing it.
        dlg.setOnShowListener { dlg.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener { choose(input.text?.toString().orEmpty()) } }
        runCatching { dlg.show() }.onFailure { log("TS picker: $it"); return }
        picker = WeakReference(dlg)

        val app = act.applicationContext
        Thread({
            val token = runCatching { EmoteRepo.authToken(app) }.getOrDefault("")
            var got: List<DualUsher.Live>? = null
            var state = ""
            if (token.isBlank()) state = "out"
            else {
                val json = EmoteRepo.gqlPost(token, DualUsher.followedBody())
                got = json?.let { runCatching { DualUsher.parseFollowed(it) }.getOrNull() }
                if (got == null) state = "failed"
            }
            log("TS followed live channels: ${got?.size ?: state}")
            main.post {
                if (!dlg.isShowing) return@post
                all = got
                why = state
                filter()
            }
        }, "ptv-dual-follow").start()
    }

    /** A round dot and a space. */
    private const val DOT = "\u25CF "

    /** One list row: the name in bold, then a red dot with the viewer count and the game. */
    private fun row(l: DualUsher.Live): CharSequence {
        val sb = SpannableStringBuilder(l.displayName.ifBlank { l.login })
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val game = l.game?.takeIf { it.isNotBlank() }
        val viewers = l.viewers
        if (game == null && viewers == null) return sb
        sb.append('\n')
        val start = sb.length
        if (viewers != null) {
            sb.append(DOT)
            sb.setSpan(ForegroundColorSpan(TwoStreamsRoot.LIVE), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val at = sb.length
            sb.append(TwoStreamsRoot.count(viewers))
            sb.setSpan(ForegroundColorSpan(TwoStreamsRoot.DIM), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (game != null) sb.append("   ")
        }
        if (game != null) {
            val at = sb.length
            sb.append(game)
            sb.setSpan(ForegroundColorSpan(TwoStreamsRoot.DIM), at, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.setSpan(RelativeSizeSpan(0.82f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    // ---- testing from a computer ----

    /**
     * DevBridge DEV_DUAL ("TS" lines in logcat), each with --es op:
     *   open --es login B                    Twitch's live channel beside B
     *   open --es login A --es login2 B      any two channels
     *   close [--ei keep 0|1]                0 plays Twitch's own player again, 1 carries on with the second stream in Twitch
     *   status                               sizes, states, volumes, picture-in-picture
     *   pip                                  into picture-in-picture now
     *   sound --ei i 0|1 --ei v 0..100       one stream's volume
     *   volume --ei i 0|1                    opens that stream's volume popup
     *   card --ei i 0|1 --es kind viewer|emote|chat|picker|points   opens that real Twitch screen from a pane
     *   clearfx                              forgets every channel's own filter settings
     *   tap --ei i 0|1                       a tap on that stream's video: shows its controls
     *   captions --ei i 0|1|-1               which stream the captions follow (-1 the louder one)
     *   closesheet                           closes an open picker or rewards panel
     *   settings                             opens Twitch's main settings the way the app does
     *   raid --ei i 0|1 --es login C         moves a pane as a raid to C would, without joining one
     *   picker [--ei replace 0|1]            the channel list, as the player button or a strip opens it
     *   swap --ei slot 0|1 --es login C      put channel C in place of a stream
     */
    fun dev(i: Intent) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { dev(i) }; return }
        when (val op = i.getStringExtra("op") ?: "status") {
            "open" -> {
                val act = ViewHider.currentActivity() ?: run { log("TS dev open: no activity on screen"); return }
                val l1 = i.getStringExtra("login")
                val l2 = i.getStringExtra("login2")
                val a = if (l2 != null) l1 else RefreshButton.liveLogin()
                val b = l2 ?: l1
                if (a == null || b == null) { log("TS dev open: needs --es login (and a live stream on screen, or --es login2)"); return }
                open(act, a, b)
            }
            "close" -> if (screen == null) log("TS dev close: not open") else close(i.getIntExtra("keep", 0))
            "status" -> status()
            "pip" -> {
                val a = host?.get()
                if (screen == null || a == null) log("TS dev pip: not open")
                else log("TS dev pip: ${if (TwoStreamPip.enterNow(a)) "entered" else "refused"}")
            }
            "sound" -> {
                val k = i.getIntExtra("i", 0)
                val v = i.getIntExtra("v", 100)
                screen?.setVolume(k, v, false)
                log("TS dev sound: stream ${k + 1} at $v")
            }
            "volume" -> screen?.openVolume(i.getIntExtra("i", 0))
            "card" -> screen?.devCard(i.getIntExtra("i", 0), i.getStringExtra("kind").orEmpty())
            "settings" -> ViewHider.currentActivity()?.let { log("TS dev settings: ${DualCards.settings(it)}") }
            "tap" -> ui.tapPlayer(i.getIntExtra("i", 0))
            "clearfx" -> {
                Settings.withFxScope(null) { Settings.replace(emptyMap()) { it.startsWith("dual_ui_fx_") } }
                screen?.let { r -> runCatching { VideoFilters.refresh(r) } }
                AudioFilters.refresh()
            }
            "captions" -> screen?.pickCaptions(i.getIntExtra("i", -1))
            "closesheet" -> log("TS dev sheet closed: ${screen?.closeSheet()}")
            "raid" -> i.getStringExtra("login")?.let { screen?.devRaid(i.getIntExtra("i", 1), it) }
            "picker" -> {
                val act = host?.get() ?: ViewHider.currentActivity() ?: run { log("TS dev picker: no activity on screen"); return }
                openPicker(act, i.getIntExtra("replace", -1))
            }
            "swap" -> {
                val login = i.getStringExtra("login")?.let { DualUsher.cleanLogin(it) }
                val slot = i.getIntExtra("slot", 1)
                if (screen == null || login == null || slot !in 0..1) log("TS dev swap: needs the screen open, --ei slot 0|1 and --es login")
                else replaceStream(slot, login)
            }
            else -> log("TS dev: unknown op '$op' (open, close, status, pip, sound, picker or swap)")
        }
    }

    private fun status() {
        val r = screen
        val a = host?.get()
        log("TS status: ${if (r == null) "closed" else "open on ${a?.javaClass?.simpleName}"}, " +
            "Twitch's channel ${twitchLogin ?: "-"}, Twitch's players held ${synchronized(twitchLock) { holds.size }} " +
            "(was playing: $heldWasPlaying, paused again $repauses times), sleeping $sleeping, " +
            "paused for background $pausedByStop, app stopped ${a?.let { stopped[it] } ?: "-"}, " +
            "back ${if (backCb != null) "callback and key" else "key"}, picker ${if (picker?.get()?.isShowing == true) "open" else "closed"}")
        r?.describe()?.forEach { log("TS status: $it") }
        log("TS status: ${TwoStreamPip.status(a)}")
    }
}
