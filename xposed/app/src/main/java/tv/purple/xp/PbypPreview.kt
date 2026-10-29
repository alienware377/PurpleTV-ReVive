package tv.purple.xp

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether a Picture-by-Picture (PbyP) ad break is on, for every feature that reacts to one.
 *
 * In a PbyP break Twitch plays the ad in the big player and keeps the live stream running, muted,
 * in a small second player next to it. Kept up to date by [PbypPreview]; listeners run on the main
 * thread after any of the three values below changes, and read them again themselves.
 */
object PbypWatch {

    /** A PbyP break is on: Twitch shows its small live box, or the dev simulation stands in for it.
     *  Stays true while Twitch is in the background, because the break itself goes on. */
    @Volatile var active: Boolean = false
        internal set

    /** The live picture is on screen right now: [active], Twitch in front, and the picture shown,
     *  in Twitch's box or in our bigger preview. */
    @Volatile var visible: Boolean = false
        internal set

    /** True while the DevBridge simulation runs, so other features can tell it from a real break. */
    @Volatile var simulated: Boolean = false
        internal set

    @Volatile internal var coreRef: WeakReference<Any>? = null

    /**
     * The PbyP player's IVS CorePlayerImpl, or null. Known from a few seconds before the break
     * (Twitch builds that player early) until Twitch releases it.
     *
     * Never call IVS's native getters (getVolume and the like) on it. Twitch releases the player
     * on a background thread, and a native call on a freed player kills Twitch in a way no
     * try/catch can stop. getAudioSessionId is safe: it only reads a number IVS keeps in Java.
     */
    fun player(): Any? = coreRef?.get()

    @Volatile private var mSession: Method? = null

    /** The PbyP player's audio session, or 0. Java only, see [player]. */
    fun audioSession(): Int {
        val p = player() ?: return 0
        return runCatching {
            val m = mSession ?: p.javaClass.getMethod("getAudioSessionId").also { mSession = it }
            m.invoke(p) as? Int ?: 0
        }.getOrDefault(0)
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }

    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    internal fun fire() {
        for (l in listeners) try { l() } catch (t: Throwable) { log("PP listener failed: $t") }
    }
}

/**
 * "Live preview during ads": during a Picture-by-Picture ad break the muted live stream is shown
 * bigger, in spare space, with a caption box for what is said on it.
 *
 * The rules this keeps: the ad is never moved, covered, resized or muted by us. Only Twitch's own
 * small live player view is moved, the one Twitch itself moves between boxes in its older layout.
 * Its player, its muted flag and its volume are never touched, and no stream is fetched.
 *
 * Where things go:
 *  - Portrait: Twitch's live box is a 75dp strip next to the ad's info row. The live player view
 *    moves into a box of ours at the top of the chat area, as wide as chat (at most 60% of its
 *    height), with the caption box under it. Chat is pushed down by a top margin, never covered.
 *  - Landscape: the live picture already fills the chat column, so it stays where it is. Only the
 *    caption box is added, at the top of the chat column. With chat hidden it sits at the bottom of
 *    Twitch's live box instead, over the live picture only.
 *
 * Moving a player view recreates its video surface (a short black frame and a rebuffer risk), so
 * the view moves only when a break starts or ends, on rotation, when the setting goes off and when
 * Twitch goes to the background. Everything else (the keyboard, caption lines, text size) only
 * resizes our box in place, which keeps the surface.
 *
 * Every pass and every frame while one of our views is placed, an overlap guard checks it against
 * the ad player and every ad surface. If any of them touches, everything goes back at once and that
 * frame is not drawn; nothing is shown again until the next break or rotation.
 *
 * Classic stream screen only: the React Native one has its own PbyP stack.
 */
object PbypPreview {

    /** "Live preview during ads": 0 off (Twitch's own small box), 1 on. */
    const val KEY = "pbyp_preview"
    const val DEF = 1
    /** Caption box height in lines, 1 to 5. */
    const val KEY_LINES = "pbyp_caption_lines"
    const val DEF_LINES = 3
    /** Caption text size in sp, 12 to 24. */
    const val KEY_TEXT_SP = "pbyp_caption_sp"
    const val DEF_TEXT_SP = 15

    private const val HOST_TAG = "ptv_pbyp_host"
    private const val FRAME_TAG = "ptv_pbyp_frame"
    private const val CAP_TAG = "ptv_pbyp_caption"
    private const val INBOX_TAG = "ptv_pbyp_inbox"
    private const val SIM_TAG = "ptv_pbyp_sim"
    private const val TPV = "tv.twitch.android.shared.player.core.TwitchPlayerView"
    private const val PBYP_NAME = "Picture-by-Picture"

    /** The preview never takes more than this share of the chat area's height. */
    private const val MAX_SHARE = 0.6f
    /** Chat must be at least this tall (dp) before the preview moves in. */
    private const val MIN_CHAT_DP = 200f
    /** The preview never shrinks below this (dp), so the keyboard squeezes it but never moves it. */
    private const val MIN_PREVIEW_DP = 60f
    /** In landscape the chat column must be at least this wide (dp) for the caption box. */
    private const val MIN_COLUMN_DP = 120f
    /** How long the chat area's size must hold still before a delayed move in. */
    private const val STABLE_MS = 500L

    /**
     * Everything that shows an ad or belongs to one. None of our views may touch any of them.
     * audio_ads_container and ad_paused_overlay_container always cover the player, so in landscape
     * with chat drawn over the video (Twitch's overlay chat) the chat column touches them and
     * nothing of ours is shown there.
     */
    private val AD_SURFACES = arrayOf(
        "player_pane", "ads_metadata_container", "stream_display_ads_container",
        "stream_display_ads_dsa_container", "ad_app_install_container", "audio_ads_container",
        "ad_paused_overlay_container"
    )

    private val main = Handler(Looper.getMainLooper())

    // ---- install-time lookups ----

    @Volatile private var installed = false
    @Volatile private var idsReady = false
    /** Twitch's live box in the stream screen, and the two boxes of its older player layout. */
    @Volatile private var idBox = 0
    @Volatile private var idOldA = 0
    @Volatile private var idOldB = 0
    private var idWrapper = 0
    private var idChat = 0
    private var idOverlay = 0
    private var idPane = 0
    private var adIds = IntArray(0)

    @Volatile private var tpvCls: Class<*>? = null
    @Volatile private var mpCls: Class<*>? = null
    /** TwitchPlayerView.setPlayer's parameter: the interface Twitch's player wrapper implements. */
    @Volatile private var wrapperIface: Class<*>? = null
    /** TwitchPlayerView.setMediaPlayer's parameter: Twitch's PbyP player object around the wrapper. */
    @Volatile private var mediaCls: Class<*>? = null
    @Volatile private var mGetPlayer: Method? = null
    private val coreFields = HashMap<Class<*>, Field?>()
    private val wrapperFields = HashMap<Class<*>, Field?>()
    /** Players IVS has released, so a dead one is never adopted again. */
    private val dead = WeakHashMap<Any, Boolean>()
    /** What each hook found at install, for the log and the dump. */
    private val notes = LinkedHashMap<String, String>()

    /** The PbyP TwitchPlayerView, wherever it sits now, and the player it was last handed. */
    @Volatile private var tpvRef: WeakReference<View>? = null
    @Volatile private var viewCore: WeakReference<Any>? = null

    // ---- activities (main thread) ----

    private var lastAct: WeakReference<Activity>? = null
    private var resumedAct: WeakReference<Activity>? = null
    private var placedAct: WeakReference<Activity>? = null
    private val glWired = java.util.Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val confSeen = WeakHashMap<Activity, Long>()

    // ---- what we placed (main thread) ----

    private class Borrow(val container: WeakReference<ViewGroup>, val view: WeakReference<View>,
                         val lp: ViewGroup.LayoutParams?, val index: Int)

    private enum class Mode { NONE, PREVIEW, CAPS, INBOX }

    private var borrow: Borrow? = null
    private var host: WeakReference<LinearLayout>? = null
    private var frame: WeakReference<FrameLayout>? = null
    private var capView: WeakReference<TextView>? = null
    private var inBox: WeakReference<TextView>? = null
    /** chat_view_container, the params we changed and by how much, so restore takes off only ours. */
    private var marginView: WeakReference<View>? = null
    private var marginLp: WeakReference<ViewGroup.LayoutParams>? = null
    private var marginDelta = 0
    private var mode = Mode.NONE

    // ---- break and epoch state (main thread) ----

    private var wasActive = false
    /** A move of the live view is allowed now: a break started, rotation, resume or a setting. */
    private var allowMove = false
    /** Moves into our box in this epoch. One at most, so the surface is not rebuilt over and over. */
    private var enlarges = 0
    /** The guard said no: nothing is placed until the next epoch. */
    private var refused = false
    private var failures = 0
    /** Two failed moves: the preview is off until Twitch restarts. Captions still work. */
    private var previewOff = false
    private var geomKey = Long.MIN_VALUE
    private var geomSince = 0L
    private var lastSig = 0L
    private var lastClash = ""
    private val syncPosted = AtomicBoolean(false)

    // ---- settings, re-read only after a write ----

    private var setRev = Int.MIN_VALUE
    private var settingsRead = false
    private var sPreview = true
    private var sCaps = true
    private var sLines = DEF_LINES
    private var sSp = DEF_TEXT_SP

    // ---- captions (main thread) ----

    private var capShown = ""
    private var capLive = false
    private var capHadText = false
    private val capPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)

    // ---- dev simulation (main thread) ----

    @Volatile private var simulating = false
    private var simView: WeakReference<FrameLayout>? = null
    private var simLabel: WeakReference<TextView>? = null
    private var simBox: WeakReference<ViewGroup>? = null
    private var simOldVis = View.GONE
    private var simHidden = false

    // ---- reused by the guard, which also runs every frame ----

    private val loc = IntArray(2)
    private val rMine = Rect()
    private val rAd = Rect()
    private val rPlan = Rect()
    /** By id, without boxing: the guard looks these up every frame. */
    private val cache = android.util.SparseArray<WeakReference<View>>()
    private var cacheRoot: WeakReference<View>? = null
    private var boxRef: WeakReference<ViewGroup>? = null
    private var preDrawRoot: WeakReference<View>? = null

    // =============================================================== install

    fun install(lp: LoadPackageParam) {
        if (installed) return
        installed = true
        val cl = lp.classLoader
        Settings.ctx()?.let { runCatching { resolveIds(it) } }
        mpCls = runCatching { cl.loadClass("com.amazonaws.ivs.player.MediaPlayer") }.getOrNull()
        runCatching { hookVisibility() }.onFailure { note("visibility", "failed: $it") }
        runCatching { hookPlayerView(cl) }.onFailure { note("player view", "failed: $it") }
        runCatching { hookDelegate(cl) }.onFailure { note("delegate", "failed: $it") }
        runCatching { hookFactory(cl) }.onFailure { note("factory", "failed: $it") }
        runCatching { hookRelease(cl) }.onFailure { note("release", "failed: $it") }
        runCatching { hookActivity() }.onFailure { note("activity", "failed: $it") }
        // Captions arrive from CaptionHub on whatever thread produced them; the box lives on ours.
        runCatching { CaptionHub.addListener { t, f -> main.post { safely("caption") { onCaption(t, f) } } } }
            .onFailure { note("captions", "failed: $it") }
        log("PP installed: " + notes.entries.joinToString { "${it.key} ${it.value}" })
    }

    /** A setting of ours changed (menu, DevBridge or sync): apply it now. */
    fun reapply() {
        main.post { setRev = Int.MIN_VALUE; lastSig = 0L; requestSync() }
    }

    private fun note(k: String, v: String) = synchronized(notes) { notes[k] = v }

    private fun resolveIds(ctx: Context) {
        if (idsReady) return
        idBox = Res.id(ctx, "pbyp_player_container")
        idOldA = Res.id(ctx, "ads_pbyp_portrait_container")
        idOldB = Res.id(ctx, "pbyp_landscape_container_inner")
        idWrapper = Res.id(ctx, "chat_wrapper")
        idChat = Res.id(ctx, "chat_view_container")
        idOverlay = Res.id(ctx, "chat_overlay_container")
        idPane = Res.id(ctx, "player_pane")
        adIds = IntArray(AD_SURFACES.size) { Res.id(ctx, AD_SURFACES[it]) }
        idsReady = idBox != 0
        if (!idsReady) log("PP no pbyp_player_container in this Twitch: preview off")
    }

    /**
     * Break start and end, by Twitch's own visibility write on its live box. Only an int compare of
     * the view's id runs for the app's other views. Needs no renamed class, works in every version,
     * and also sees ConstraintSet.applyTo writes.
     */
    private fun hookVisibility() {
        val n = XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                try {
                    val id = (p.thisObject as? View)?.id ?: return
                    if (id == View.NO_ID || id == 0) return
                    if (id != idBox && id != idOldA && id != idOldB) return
                    requestSync()
                } catch (_: Throwable) {}
            }
        }).size
        note("visibility", if (n > 0) "ok" else "missing")
    }

    /**
     * Which player is the PbyP one: the player handed to a TwitchPlayerView that sits in a PbyP box
     * (or that we know is the PbyP view, which may be in our box now). setPlayer is final and small,
     * so ART may build it into setMediaPlayer and skip that hook; setMediaPlayer is hooked too.
     */
    private fun hookPlayerView(cl: ClassLoader) {
        val c = runCatching { cl.loadClass(TPV) }.getOrNull() ?: run { note("player view", "missing"); return }
        tpvCls = c
        wrapperIface = c.declaredMethods.firstOrNull { it.name == "setPlayer" && it.parameterTypes.size == 1 }?.parameterTypes?.get(0)
        mediaCls = c.declaredMethods.firstOrNull { it.name == "setMediaPlayer" && it.parameterTypes.size == 1 }?.parameterTypes?.get(0)
        mGetPlayer = runCatching { c.getMethod("getPlayer") }.getOrNull()
        val n1 = XposedBridge.hookAllMethods(c, "setPlayer", after { p -> onPlayerSet(p.thisObject as? View, p.args.getOrNull(0), false) }).size
        val n2 = XposedBridge.hookAllMethods(c, "setMediaPlayer", after { p -> onPlayerSet(p.thisObject as? View, p.args.getOrNull(0), true) }).size
        note("player view", "setPlayer=$n1 setMediaPlayer=$n2")
    }

    /** Optional extra start/end signal: Twitch's PbyP view delegate render (q800.u, bnz.v). */
    private fun hookDelegate(cl: ClassLoader) {
        val why = runCatching<String> {
            val name = TwitchVersion.pick("bnz", "q800")
            val cls = cl.loadClass(name)
            val rx = cl.loadClass("tv.twitch.android.core.mvp.viewdelegate.RxViewDelegate")
            val st = cl.loadClass("tv.twitch.android.core.mvp.viewdelegate.ViewDelegateState")
            // Its direct parent is an abstract RxViewDelegate (o3, n3), so "is a", never "extends".
            if (!rx.isAssignableFrom(cls)) return@runCatching "off: $name is not a view delegate"
            if (cls.declaredFields.count { it.type == ViewGroup::class.java } != 1) return@runCatching "off: $name does not hold one ViewGroup"
            val ms = cls.declaredMethods.filter {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == st && !Modifier.isAbstract(it.modifiers)
            }
            if (ms.isEmpty()) return@runCatching "off: $name has no render method"
            for (m in ms) XposedBridge.hookMethod(m, after { requestSync() })
            "installed: $name/" + ms.joinToString(",") { it.name }
        }.getOrElse { "failed: $it" }
        note("delegate", why)
        log("PP delegate hook $why")
    }

    /**
     * Early signal: Twitch builds the PbyP player a few seconds before the break (d) and hands it
     * back after (f). Only calls for "Picture-by-Picture" (or the PBYP player type) count. The pin
     * is renamed in every release, so it is trusted only when d returns, and f takes, the very
     * type the readable TwitchPlayerView.setMediaPlayer takes.
     */
    private fun hookFactory(cl: ClassLoader) {
        val why = runCatching<String> {
            val mc = mediaCls ?: return@runCatching "off: setMediaPlayer not found"
            val name = TwitchVersion.pick("rnt", "d2u")
            val cls = cl.loadClass(name)
            val str = String::class.java
            val d = cls.declaredMethods.filter { it.name == "d" && it.parameterTypes.size == 2 && it.parameterTypes[1] == str && it.returnType == mc }
            val f = cls.declaredMethods.filter { it.name == "f" && it.parameterTypes.size == 2 && it.parameterTypes[0] == mc && it.parameterTypes[1] == str }
            if (d.size != 1 || f.size != 1) return@runCatching "off: $name.d/f do not match (d=${d.size} f=${f.size})"
            XposedBridge.hookMethod(d[0], after { p ->
                if (!isPbypCall(p)) return@after
                val core = coreOfMedia(p.result) ?: return@after
                if (isDead(core)) return@after
                PbypWatch.coreRef = WeakReference(core)
                log("PP pbyp player created (factory) ${tag(core)}")
                requestSync()
            })
            XposedBridge.hookMethod(f[0], before { p ->
                if (p.args.getOrNull(1) != PBYP_NAME) return@before
                val core = coreOfMedia(p.args.getOrNull(0)) ?: return@before
                synchronized(dead) { dead[core] = true }
                if (core === PbypWatch.player()) {
                    PbypWatch.coreRef = null
                    log("PP pbyp player handed back (factory)")
                    requestSync()
                }
            })
            "installed: $name.d/f"
        }.getOrElse { "failed: $it" }
        note("factory", why)
    }

    private fun isPbypCall(p: XC_MethodHook.MethodHookParam) =
        p.args.getOrNull(1) == PBYP_NAME || (p.args.getOrNull(0) as? Enum<*>)?.name == "PBYP"

    /** Forget the PbyP player as soon as IVS releases it. Runs on IVS's release thread: mark only. */
    private fun hookRelease(cl: ClassLoader) {
        val core = cl.loadClass("com.amazonaws.ivs.player.CorePlayerImpl")
        var n = 0
        for (m in core.declaredMethods) {
            if (m.name != "release" || !Modifier.isPublic(m.modifiers) || Modifier.isNative(m.modifiers)) continue
            if (runCatching { XposedBridge.hookMethod(m, before { p ->
                    val me = p.thisObject ?: return@before
                    synchronized(dead) { dead[me] = true }
                    if (me === PbypWatch.player()) { PbypWatch.coreRef = null; requestSync() }
                }) }.isSuccess) n++
        }
        note("release", "$n")
    }

    private fun hookActivity() {
        val a = Activity::class.java
        XposedBridge.hookAllMethods(a, "onResume", after { p -> (p.thisObject as? Activity)?.let { onResumed(it) } })
        XposedBridge.hookAllMethods(a, "onPause", before { p -> (p.thisObject as? Activity)?.let { onPaused(it) } })
        // Before Twitch's own handling, so the view is back in its box before Twitch rearranges.
        XposedBridge.hookAllMethods(a, "onConfigurationChanged", before { p ->
            val act = p.thisObject as? Activity ?: return@before
            onConfig(act, p.args.getOrNull(0) as? Configuration ?: return@before)
        })
        XposedBridge.hookAllMethods(a, "onDestroy", before { p -> (p.thisObject as? Activity)?.let { onGone(it) } })
        note("activity", "ok")
    }

    // =============================================================== player identity

    private fun onPlayerSet(v: View?, arg: Any?, media: Boolean) {
        if (v == null) return
        if (v !== tpvRef?.get() && !inPbypBox(v)) return
        tpvRef = WeakReference(v)
        if (arg == null) {
            // Twitch took the player off the PbyP view. Clear it only if it is still the one known,
            // so a late clear from the last break never wipes the next break's early player.
            val old = viewCore?.get()
            if (old != null && old === PbypWatch.player()) PbypWatch.coreRef = null
            viewCore = null
        } else {
            val core = if (media) coreOfMedia(arg) else coreOf(arg)
            if (core != null && !isDead(core)) {
                viewCore = WeakReference(core)
                if (core !== PbypWatch.player()) {
                    PbypWatch.coreRef = WeakReference(core)
                    log("PP core bound ${tag(core)} session=${PbypWatch.audioSession()}")
                }
            }
        }
        requestSync()
    }

    private fun inPbypBox(v: View): Boolean {
        val id = (v.parent as? View)?.id ?: return false
        return id != View.NO_ID && id != 0 && (id == idBox || id == idOldA || id == idOldB)
    }

    private fun isDead(o: Any) = synchronized(dead) { dead[o] == true }

    /** The IVS player inside Twitch's wrapper: the field declared as IVS's MediaPlayer, never by name. */
    private fun coreOf(wrapper: Any?): Any? {
        if (wrapper == null) return null
        val mp = mpCls ?: return null
        val f = cachedField(coreFields, wrapper.javaClass) { k -> fieldWhere(k) { it.type == mp } } ?: return null
        return f.get(wrapper)
    }

    /** From Twitch's PbyP player object: its wrapper field (typed by setPlayer's interface), then the IVS player. */
    private fun coreOfMedia(media: Any?): Any? {
        if (media == null) return null
        val wi = wrapperIface ?: return null
        val f = cachedField(wrapperFields, media.javaClass) { k -> fieldWhere(k) { wi.isAssignableFrom(it.type) } } ?: return null
        return coreOf(f.get(media))
    }

    private fun cachedField(map: HashMap<Class<*>, Field?>, k: Class<*>, find: (Class<*>) -> Field?): Field? =
        synchronized(map) { if (map.containsKey(k)) map[k] else find(k).also { map[k] = it } }

    private fun fieldWhere(k: Class<*>, test: (Field) -> Boolean): Field? {
        var c: Class<*>? = k
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) if (!Modifier.isStatic(f.modifiers) && test(f)) return f.apply { isAccessible = true }
            c = c.superclass
        }
        return null
    }

    /** Fallback when no hook saw the player: ask the PbyP view which wrapper it holds. */
    private fun adoptCore(v: View?) {
        if (v == null || PbypWatch.player() != null || !isPicture(v)) return
        val w = runCatching { mGetPlayer?.invoke(v) }.getOrNull() ?: return
        val core = runCatching { coreOf(w) }.getOrNull() ?: return
        if (isDead(core)) return
        PbypWatch.coreRef = WeakReference(core)
        log("PP core found on the view ${tag(core)}")
    }

    // =============================================================== activities

    private fun onResumed(act: Activity) {
        lastAct = WeakReference(act)
        resumedAct = WeakReference(act)
        runCatching { resolveIds(act) }
        confSeen[act] = confKey(act.resources.configuration)
        wire(act)
        newEpoch()
        lastSig = 0L
        requestSync()
    }

    private fun onPaused(act: Activity) {
        if (resumedAct?.get() === act) resumedAct = null
        if (placedAct?.get() === act) restore("pause")
        requestSync()
    }

    private fun onConfig(act: Activity, cfg: Configuration) {
        val key = confKey(cfg)
        val old = confSeen.put(act, key)
        // Only a turn or a new window size moves anything; dark mode, fonts and the like resize in place.
        if (old == null || old != key) {
            if (placedAct?.get() === act) restore("rotation")
            main.postDelayed({ newEpoch(); lastSig = 0L; requestSync() }, 300)
        } else requestSync()
    }

    private fun onGone(act: Activity) {
        if (placedAct?.get() === act) restore("closed")
        if (resumedAct?.get() === act) resumedAct = null
        glWired.remove(act)
    }

    private fun confKey(c: Configuration): Long =
        (c.orientation.toLong() shl 40) or ((c.screenWidthDp.toLong() and 0xFFFFF) shl 20) or (c.screenHeightDp.toLong() and 0xFFFFF)

    /** Our own layout pass per activity; ViewHider's is not ours to change. */
    private fun wire(act: Activity) {
        if (!glWired.add(act)) return
        runCatching {
            val root = act.window?.decorView ?: return
            root.viewTreeObserver.addOnGlobalLayoutListener { safely("check") { check(root) } }
        }
    }

    // =============================================================== layout pass

    /**
     * From every layout pass: cheap. Finds Twitch's live box (cached), runs the overlap guard while
     * anything of ours is placed, and asks for a [sync] only when something it depends on changed.
     * Never moves views itself: that is posted, never done inside the layout dispatch.
     */
    fun check(root: View) {
        if (!installed || !idsReady) return
        val c = container(root)
        val placed = anyPlaced()
        if (c == null && !placed && !PbypWatch.active && !simulating) return
        if (placed && !guardOk()) { refuse("layout"); return }
        val s = signature(root, c, placed)
        if (s != lastSig) { lastSig = s; requestSync() }
    }

    private var sh = 0L
    private fun mix(x: Int) { sh = (sh xor (x.toLong() and 0xFFFFFFFFL)) * 1099511628211L }
    private fun mix(b: Boolean) = mix(if (b) 1 else 0)

    private fun signature(root: View, c: ViewGroup?, placed: Boolean): Long {
        sh = -3750763034362895579L
        mix(c?.visibility ?: -9); mix(c?.isShown == true); mix(Settings.rev); mix(simulating)
        if (!PbypWatch.active && !placed && !simulating) return sh
        mix(c?.childCount ?: -1); mix(c?.width ?: -1); mix(c?.height ?: -1)
        mix(root.resources.configuration.orientation)
        mix(resumedAct?.get() != null)
        val theatre = c?.parent as? ViewGroup ?: return sh
        find(theatre, idWrapper)?.let { mix(it.visibility); mix(it.isShown); mix(it.width); mix(it.height) }
        find(theatre, idOverlay)?.let { mix(it.visibility) }
        find(theatre, idChat)?.let { v ->
            val lp = v.layoutParams
            mix(System.identityHashCode(lp)); mix((lp as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0)
        }
        find(theatre, idPane)?.let { v -> v.getLocationInWindow(loc); mix(loc[0]); mix(loc[1]); mix(v.width); mix(v.height) }
        return sh
    }

    private fun requestSync() {
        if (syncPosted.compareAndSet(false, true)) main.post(syncTask)
    }

    private val syncTask = Runnable {
        syncPosted.set(false)
        safely("sync") { sync() }
    }

    /** A delayed move in waits for the chat area to hold still; this looks again once it may have. */
    private val stableCheck = Runnable { requestSync() }

    private fun newEpoch() {
        allowMove = true
        enlarges = 0
        refused = false
        geomSince = SystemClock.uptimeMillis()
    }

    private fun readSettings() {
        val r = Settings.rev
        if (r == setRev && settingsRead) return
        setRev = r
        val p = Settings.getInt(KEY, DEF) != 0
        val c = Settings.get(CaptionHub.KEY, true)
        // A preview or caption switch is a user action: it may move the view (on or off) at once.
        if (settingsRead && (p != sPreview || c != sCaps)) newEpoch()
        sPreview = p
        sCaps = c
        sLines = Settings.getInt(KEY_LINES, DEF_LINES).coerceIn(1, 5)
        sSp = Settings.getInt(KEY_TEXT_SP, DEF_TEXT_SP).coerceIn(12, 24)
        settingsRead = true
    }

    /** Main thread only, and idempotent: works out where things should be and puts them there. */
    private fun sync() {
        if (!installed) return
        readSettings()
        val act = lastAct?.get() ?: ViewHider.currentActivity()
        val root = act?.window?.peekDecorView()
        val resumed = act != null && resumedAct?.get() === act
        if (act != null && !idsReady) runCatching { resolveIds(act) }
        val c = root?.let { container(it) }
        // The break is on while Twitch has its box set visible; whether it is on screen right now
        // (the stream screen may be hidden for a moment) only decides what we place.
        val boxOn = c != null && c.visibility == View.VISIBLE && c.isAttachedToWindow
        val boxShown = boxOn && c!!.isShown
        val old = if (!boxOn && !simulating && root != null) oldBox(root) else null
        val active = simulating || boxOn || old != null

        if (active && !wasActive) {
            log("PP show" + if (simulating) " (simulated)" else "")
            newEpoch()
        } else if (!active && wasActive) {
            log("PP hide")
        }
        wasActive = active
        PbypWatch.active = active

        // The live picture: our stand-in while simulating (Twitch's own view is never moved by the
        // simulation), else the PbyP view in our box or in Twitch's.
        val pic: View? = when {
            simulating -> simView?.get()
            else -> borrowedView() ?: c?.let { pictureIn(it) } ?: old?.let { pictureIn(it) }
        }
        if (active && !simulating) adoptCore(pic)

        // Only Twitch's stream-screen box gets anything of ours; the older layout's boxes are for
        // telling start and end only.
        if (!active || !resumed || root == null || c == null || !idsReady || (!boxShown && !simulating)) {
            if (anyPlaced()) restore(if (!active) "break over" else if (!resumed) "not in front" else "no live box")
            publish(active && resumed && pic.isOnScreen())
            return
        }
        if (simulating) simGuard(c)
        place(act, root, c, act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE, pic)
        // Again after placing: the stand-in may just have moved out of Twitch's box into ours.
        if (simulating) simGuard(c)
        publish(pic.isOnScreen())
    }

    private fun View?.isOnScreen(): Boolean =
        this != null && isShown && width > 0 && height > 0 && windowVisibility == View.VISIBLE

    private var pubActive = false
    private var pubVisible = false
    private var pubCore: WeakReference<Any>? = null

    private fun publish(visible: Boolean) {
        PbypWatch.visible = visible
        val a = PbypWatch.active
        val core = PbypWatch.player()
        if (a == pubActive && visible == pubVisible && core === pubCore?.get()) return
        pubActive = a; pubVisible = visible; pubCore = core?.let { WeakReference(it) }
        log("PP watch active=$a visible=$visible player=${core?.let { tag(it) } ?: "none"}" + if (PbypWatch.simulated) " (simulated)" else "")
        PbypWatch.fire()
    }

    // =============================================================== placing

    private fun place(act: Activity, root: View, c: ViewGroup, landscape: Boolean, pic: View?) {
        val theatre = c.parent as? ViewGroup ?: run { if (anyPlaced()) restore("no stream screen"); return }
        borrow?.let { if (it.container.get() !== c) restore("stream screen rebuilt") }
        // Twitch took its view out of our box itself: let go of everything and start clean.
        if (borrow != null && borrowedView() == null) restore("live view moved by Twitch")
        val d = act.resources.displayMetrics.density
        val m = px(8f, d)
        val wrapper = find(theatre, idWrapper) as? ViewGroup
        val chatView = find(theatre, idChat)?.takeIf { wrapper != null && it.parent === wrapper }
        val wrapperOk = wrapper != null && chatView != null && wrapper.visibility == View.VISIBLE &&
            wrapper.isShown && wrapper.width > 0 && wrapper.height > 0
        val capH = if (sCaps && capText().isNotEmpty()) captionHeight(act, sSp, sLines, d) else 0
        val gap = if (capH > 0) px(6f, d) else 0

        val gk = if (wrapper != null) (wrapper.width.toLong() shl 32) or (wrapper.height.toLong() and 0xFFFFFFFFL) else -1L
        val now = SystemClock.uptimeMillis()
        if (gk != geomKey) { geomKey = gk; geomSince = now }
        val stable = now - geomSince >= STABLE_MS
        val borrowed = borrowedView() != null

        var want = Mode.NONE
        var pw = 0
        var ph = 0
        if (!landscape && sPreview && !previewOff && !refused && wrapperOk && pic != null) {
            val w = wrapper!!.width
            val h = wrapper.height
            pw = w - 2 * m
            ph = pw * 9 / 16
            val room = (h * MAX_SHARE).toInt() - 2 * m - capH - gap
            if (ph > room) { ph = room; pw = ph * 16 / 9 }
            val minPh = px(MIN_PREVIEW_DP, d)
            if (borrowed) {
                // Already ours: only ever resized in place, the keyboard squeezes it to a minimum.
                if (ph < minPh) { ph = minPh; pw = minOf(w - 2 * m, ph * 16 / 9) }
                want = Mode.PREVIEW
            } else {
                val worth = h >= px(MIN_CHAT_DP, d) && ph >= minPh && ph >= (c.height * 1.2f).toInt()
                if (worth && enlarges == 0) {
                    if (allowMove || stable) want = Mode.PREVIEW
                    else { main.removeCallbacks(stableCheck); main.postDelayed(stableCheck, STABLE_MS + 50) }
                }
            }
        }
        if (want == Mode.NONE && capH > 0 && !refused) want = when {
            wrapperOk && (!landscape || wrapper!!.width >= px(MIN_COLUMN_DP, d)) -> Mode.CAPS
            c.width > 0 && c.height > 0 -> Mode.INBOX
            else -> Mode.NONE
        }
        allowMove = false

        if (borrowed && want != Mode.PREVIEW) restore(if (landscape) "landscape" else if (!sPreview) "setting off" else "no room")
        when (want) {
            Mode.PREVIEW -> if (borrowView() == null) enlarge(act, theatre, c, pic!!, wrapper!!, chatView!!, pw, ph, capH, gap, m)
                            else resize(chatView!!, pw, ph, capH, gap, m)
            Mode.CAPS -> capsHost(act, theatre, wrapper!!, chatView!!, capH, m)
            Mode.INBOX -> inBoxCaption(act, theatre, c, d)
            Mode.NONE -> {}
        }
        if (want != Mode.INBOX) removeInBox()
        if (want != Mode.CAPS && want != Mode.PREVIEW) removeHost()
        mode = if (anyPlaced()) want else Mode.NONE
        if (anyPlaced()) startPreDraw(root) else stopPreDraw()
    }

    private fun borrowView(): View? = borrow?.view?.get()

    /** The borrowed live view, if it really is still in our box. */
    private fun borrowedView(): View? {
        val v = borrow?.view?.get() ?: return null
        val f = frame?.get() ?: return null
        return if (v.parent === f) v else null
    }

    /** Move the live view into a box of ours at the top of chat. Only at an allowed moment. */
    private fun enlarge(act: Activity, theatre: ViewGroup, c: ViewGroup, pic: View, wrapper: ViewGroup, chatView: View,
                        pw: Int, ph: Int, capH: Int, gap: Int, m: Int) {
        if (pic.parent !== c) return
        val hostH = 2 * m + ph + capH + gap
        // Checked before anything is added, so a bad spot is never drawn even once.
        rectOf(wrapper, rPlan); rPlan.bottom = rPlan.top + hostH
        if (!planClear(theatre)) { refused = true; log("PP refused: the preview would touch $lastClash"); return }
        try {
            removeHost()
            val h = newHost(act, true)
            attachHost(wrapper, chatView, h, hostH)
            host = WeakReference(h)
            val f = frame?.get() ?: throw IllegalStateException("no preview frame")
            sizeFrame(f, pw, ph)
            styleCaption(capView?.get(), capH, gap, m)
            h.setPadding(0, m, 0, m)
            // Recorded before the move, so restore can always put it back where Twitch had it.
            borrow = Borrow(WeakReference(c), WeakReference(pic), pic.layoutParams, c.indexOfChild(pic))
            c.removeView(pic)
            f.addView(pic, FrameLayout.LayoutParams(MATCH, MATCH))
            setMargin(chatView, hostH)
            placedAct = WeakReference(act)
            enlarges++
            log("PP enlarged ${pw}x$ph in the chat area")
        } catch (t: Throwable) {
            log("PP enlarge failed: $t")
            restore("enlarge failed")
            if (++failures >= 2) { previewOff = true; log("PP disabled for this session") }
        }
    }

    /** The preview is ours already: size it in place, which keeps its video surface. */
    private fun resize(chatView: View, pw: Int, ph: Int, capH: Int, gap: Int, m: Int) {
        val h = host?.get() ?: return
        frame?.get()?.let { sizeFrame(it, pw, ph) }
        styleCaption(capView?.get(), capH, gap, m)
        val hostH = 2 * m + ph + capH + gap
        sizeView(h, hostH)
        setMargin(chatView, hostH)
    }

    /** Captions only, at the top of chat: the live picture stays in Twitch's box. */
    private fun capsHost(act: Activity, theatre: ViewGroup, wrapper: ViewGroup, chatView: View, capH: Int, m: Int) {
        val hostH = capH + m
        var h = host?.get()
        if (h != null && (h.parent !== wrapper || frame?.get() != null)) { removeHost(); h = null }
        if (h == null) {
            rectOf(wrapper, rPlan); rPlan.bottom = rPlan.top + hostH
            if (!planClear(theatre)) { refused = true; log("PP refused: the caption box would touch $lastClash"); return }
            h = newHost(act, false)
            attachHost(wrapper, chatView, h, hostH)
            host = WeakReference(h)
            placedAct = WeakReference(act)
        }
        h.setPadding(m, m / 2, m, m / 2)
        styleCaption(capView?.get(), capH, 0, 0)
        sizeView(h, hostH)
        setMargin(chatView, hostH)
    }

    /** Chat is hidden: two caption lines at the bottom of Twitch's live box, over the live picture only. */
    private fun inBoxCaption(act: Activity, theatre: ViewGroup, c: ViewGroup, d: Float) {
        var tv = inBox?.get()
        if (tv != null && tv.parent !== c) { removeInBox(); tv = null }
        if (tv == null) {
            val bandH = captionHeight(act, 12, 2, d)
            rectOf(c, rPlan); rPlan.top = rPlan.bottom - bandH
            if (!planClear(theatre)) { refused = true; log("PP refused: the caption overlay would touch $lastClash"); return }
            tv = newCaption(act, 12f).apply { tag = INBOX_TAG; maxLines = 2 }
            tv.background = GradientDrawable().apply { setColor(0xB3000000.toInt()) }
            c.addView(tv, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            inBox = WeakReference(tv)
            placedAct = WeakReference(act)
        }
        setCaption(tv, capText(), 2)
    }

    private fun newHost(ctx: Context, withFrame: Boolean): LinearLayout {
        val h = LinearLayout(ctx).apply {
            tag = HOST_TAG
            orientation = LinearLayout.VERTICAL
            if (withFrame) {
                setBackgroundColor(0xFF0E0E10.toInt())
                contentDescription = "Live stream preview"
            }
        }
        if (withFrame) {
            val f = FrameLayout(ctx).apply { tag = FRAME_TAG; setBackgroundColor(Color.BLACK) }
            h.addView(f, LinearLayout.LayoutParams(1, 1).apply { gravity = Gravity.CENTER_HORIZONTAL })
            frame = WeakReference(f)
        } else frame = null
        val tv = newCaption(ctx, sSp.toFloat()).apply { tag = CAP_TAG }
        h.addView(tv, LinearLayout.LayoutParams(MATCH, 0))
        capView = WeakReference(tv)
        return h
    }

    private fun newCaption(ctx: Context, sp: Float): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            includeFontPadding = false
            gravity = Gravity.START or Gravity.BOTTOM
            setPadding(px(10f, d), px(6f, d), px(10f, d), px(6f, d))
            background = GradientDrawable().apply { setColor(0xE6141018.toInt()); cornerRadius = 8f * d }
            // Never announced by itself, so TalkBack and the read-aloud voice do not talk over each other.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) (v as? TextView)?.let {
                    setCaption(it, capText(), if (it.tag == INBOX_TAG) 2 else sLines)
                }
            }
        }
    }

    /**
     * Into chat_wrapper right after chat_view_container, so Twitch's chat overlay (chat rules,
     * extensions), which comes later, still draws over it and its buttons can be tapped.
     */
    private fun attachHost(wrapper: ViewGroup, chatView: View, h: View, hostH: Int) {
        val idx = (wrapper.indexOfChild(chatView) + 1).coerceIn(0, wrapper.childCount)
        wrapper.addView(h, idx, FrameLayout.LayoutParams(MATCH, hostH, Gravity.TOP))
    }

    private fun sizeFrame(f: FrameLayout, pw: Int, ph: Int) {
        val lp = f.layoutParams ?: return
        if (lp.width != pw || lp.height != ph) { lp.width = pw; lp.height = ph; f.layoutParams = lp }
    }

    private fun sizeView(v: View, height: Int) {
        val lp = v.layoutParams ?: return
        if (lp.height != height) { lp.height = height; v.layoutParams = lp }
    }

    private fun styleCaption(tv: TextView?, capH: Int, gap: Int, m: Int) {
        tv ?: return
        val sp = sSp.toFloat()
        val want = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, tv.resources.displayMetrics)
        if (kotlin.math.abs(tv.textSize - want) > 0.5f) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (tv.maxLines != sLines) tv.maxLines = sLines
        val lp = tv.layoutParams as? LinearLayout.LayoutParams
        if (lp != null && (lp.height != capH || lp.topMargin != gap || lp.leftMargin != m)) {
            lp.height = capH; lp.topMargin = gap; lp.leftMargin = m; lp.rightMargin = m
            tv.layoutParams = lp
        }
        val vis = if (capH > 0) View.VISIBLE else View.GONE
        if (tv.visibility != vis) tv.visibility = vis
        setCaption(tv, capText(), sLines)
    }

    /**
     * Push chat down by our box's height. Only our own share is ever added or taken off; if Twitch
     * replaced the params in the meantime, our share went with them and is simply added again.
     */
    private fun setMargin(chatView: View, want: Int) {
        val lp = chatView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val same = marginView?.get() === chatView && marginLp?.get() === lp
        val have = if (same) marginDelta else 0
        if (same && have == want) return
        lp.topMargin = lp.topMargin - have + want
        chatView.layoutParams = lp
        marginView = WeakReference(chatView)
        marginLp = WeakReference(lp)
        marginDelta = want
    }

    private fun clearMargin() {
        val v = marginView?.get()
        val lp = marginLp?.get() as? ViewGroup.MarginLayoutParams
        if (v != null && lp != null && v.layoutParams === lp && marginDelta != 0 && lp.topMargin >= marginDelta) {
            lp.topMargin -= marginDelta
            v.layoutParams = lp
        }
        marginView = null; marginLp = null; marginDelta = 0
    }

    // =============================================================== restoring

    private fun anyPlaced(): Boolean =
        borrow != null || host?.get()?.parent != null || inBox?.get()?.parent != null

    /**
     * Put everything back as Twitch had it. Each step on its own, and a step whose view is gone is
     * skipped. The live view goes back into its own box at its old place with its old params, unless
     * Twitch already put a newer player view there (then the old one, with a dead player, stays out).
     */
    private fun restore(why: String) {
        var did = false
        val b = borrow
        borrow = null
        if (b != null) {
            did = true
            safely("put back") {
                val v = b.view.get()
                val c = b.container.get()
                val f = frame?.get()
                if (v != null) {
                    if (f != null && v.parent === f) f.removeView(v)
                    if (v.parent == null && c != null) {
                        val newer = isPicture(v) && (0 until c.childCount).any { i -> c.getChildAt(i).let { it !== v && isPicture(it) } }
                        if (!newer) c.addView(v, b.index.coerceIn(0, c.childCount), b.lp ?: FrameLayout.LayoutParams(MATCH, MATCH))
                        else log("PP restore: Twitch has a newer player view in its box, the old one stays out")
                    }
                }
            }
        }
        if (removeHost()) did = true
        if (removeInBox()) did = true
        clearMargin()
        stopPreDraw()
        placedAct = null
        mode = Mode.NONE
        lastSig = 0L
        if (did) log("PP restored ($why)")
    }

    private fun removeHost(): Boolean {
        val h = host?.get()
        host = null; frame = null; capView = null
        safely("remove host") { clearMargin() }
        val p = h?.parent as? ViewGroup ?: return false
        safely("remove host") { p.removeView(h) }
        return true
    }

    private fun removeInBox(): Boolean {
        val t = inBox?.get()
        inBox = null
        val p = t?.parent as? ViewGroup ?: return false
        safely("remove overlay") { p.removeView(t) }
        return true
    }

    private fun refuse(why: String) {
        restore("guard")
        refused = true
        log("PP refused: would touch the ad ($lastClash, $why); nothing shown until the next break or rotation")
    }

    // =============================================================== the overlap guard

    /** Every placed view of ours is clear of every ad surface. */
    private fun guardOk(): Boolean {
        host?.get()?.let { if (!clearOfAds(it)) return false }
        inBox?.get()?.let { if (!clearOfAds(it)) return false }
        return true
    }

    private fun clearOfAds(v: View): Boolean {
        if (v.parent == null || !v.isShown || v.width <= 0 || v.height <= 0) return true
        rectOf(v, rMine)
        val theatre = theatreOf(v) ?: return true
        return rectClear(rMine, theatre)
    }

    private fun planClear(theatre: ViewGroup): Boolean = rectClear(rPlan, theatre)

    private fun rectClear(r: Rect, theatre: ViewGroup): Boolean {
        for (i in adIds.indices) {
            val s = find(theatre, adIds[i]) ?: continue
            if (!s.isShown || s.width <= 0 || s.height <= 0) continue
            rectOf(s, rAd)
            if (Rect.intersects(r, rAd)) { lastClash = AD_SURFACES[i]; return false }
        }
        return true
    }

    private fun theatreOf(v: View): ViewGroup? {
        boxRef?.get()?.let { c -> (c.parent as? ViewGroup)?.let { return it } }
        return v.rootView as? ViewGroup
    }

    private fun rectOf(v: View, r: Rect) {
        v.getLocationInWindow(loc)
        r.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /**
     * Also before every frame while anything is placed: a change that never runs a layout pass
     * (an animation) can not slip a frame through. A bad frame is cancelled, not drawn. It returns
     * false only when everything really came off, so it can never keep cancelling frames.
     */
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        try {
            when {
                !anyPlaced() -> { stopPreDraw(); true }
                guardOk() -> true
                else -> { refuse("frame"); anyPlaced() }
            }
        } catch (t: Throwable) {
            logLimited("PP guard error: $t"); true
        }
    }

    private fun startPreDraw(root: View) {
        if (preDrawRoot?.get() === root) return
        stopPreDraw()
        runCatching { root.viewTreeObserver.addOnPreDrawListener(preDraw); preDrawRoot = WeakReference(root) }
    }

    private fun stopPreDraw() {
        val r = preDrawRoot?.get()
        preDrawRoot = null
        if (r != null) runCatching { r.viewTreeObserver.removeOnPreDrawListener(preDraw) }
    }

    // =============================================================== lookups

    /** Twitch's live box in the stream screen, cached and checked before reuse. */
    private fun container(root: View): ViewGroup? {
        boxRef?.get()?.let { if (it.isAttachedToWindow && it.rootView === root) return it }
        if (idBox == 0) return null
        val c = root.findViewById<View>(idBox) as? ViewGroup
        boxRef = c?.let { WeakReference(it) }
        return c
    }

    /** The older player layout's live boxes, for start and end only (nothing is placed there). */
    private fun oldBox(root: View): ViewGroup? {
        for (id in intArrayOf(idOldA, idOldB)) {
            if (id == 0) continue
            val v = root.findViewById<View>(id) as? ViewGroup ?: continue
            if (v.visibility == View.VISIBLE && v.isShown && pictureIn(v) != null) return v
        }
        return null
    }

    /** Views of the stream screen by id, searched from its root and cached. */
    private fun find(theatre: View, id: Int): View? {
        if (id == 0) return null
        if (cacheRoot?.get() !== theatre) { cache.clear(); cacheRoot = WeakReference(theatre) }
        cache.get(id)?.get()?.let { if (it.parent != null) return it }
        val v = theatre.findViewById<View>(id)
        if (v != null) cache.put(id, WeakReference(v)) else cache.remove(id)
        return v
    }

    private fun isPicture(v: View) = tpvCls?.isInstance(v) == true

    private fun pictureIn(c: ViewGroup): View? {
        for (i in 0 until c.childCount) { val v = c.getChildAt(i); if (isPicture(v)) return v }
        return null
    }

    private fun px(dp: Float, d: Float) = (dp * d + 0.5f).toInt()

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    // =============================================================== captions

    /**
     * The box content from CaptionHub, which does the rest (Twitch's captions from the PbyP player,
     * speech to text, tidying, wrapping, expiry): whole lines, newest last, "" for nothing.
     * [isFinal] false means the last line is still a live speech-to-text guess, drawn dimmer.
     * The box only takes space while there is text, so it never holds room for nothing.
     */
    private fun onCaption(text: String, isFinal: Boolean) {
        capShown = text
        capLive = !isFinal && text.isNotEmpty()
        renderCaptions()
    }

    private fun capText(): String = capShown

    private fun renderCaptions() {
        capView?.get()?.let { setCaption(it, capShown, sLines) }
        inBox?.get()?.let { setCaption(it, capShown, 2) }
        val has = capShown.isNotEmpty()
        // The box grows when text arrives and collapses when it goes: a resize in place.
        if (has != capHadText) { capHadText = has; requestSync() }
    }

    /**
     * Show the end of [full]: its last [lines] lines at the box's width (CaptionHub wraps by an
     * estimate, so a line may still wrap here). Also tells CaptionHub the box's real size, so its
     * wrapping fits.
     */
    private fun setCaption(tv: TextView, full: String, lines: Int) {
        val w = tv.width - tv.paddingLeft - tv.paddingRight
        tellHub(w, tv.textSize, lines)
        var shown = full
        if (w > 0 && full.isNotEmpty()) {
            val l = StaticLayout.Builder.obtain(full, 0, full.length, tv.paint, w).setIncludePad(false).build()
            if (l.lineCount > lines) shown = full.substring(l.getLineStart(l.lineCount - lines)).trimStart()
        }
        val live = capLive && shown.isNotEmpty()
        val cur = tv.text
        if (cur.toString() == shown && (cur is android.text.Spanned) == live) return
        if (live) {
            val sp = android.text.SpannableString(shown)
            sp.setSpan(android.text.style.ForegroundColorSpan(0xB3FFFFFF.toInt()), shown.lastIndexOf('\n') + 1,
                shown.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sp
        } else tv.text = shown
    }

    private var hubKey = 0L

    private fun tellHub(w: Int, textPx: Float, lines: Int) {
        if (w <= 0) return
        val k = (w.toLong() shl 32) or ((textPx.toInt().toLong() and 0xFFFF) shl 8) or lines.toLong()
        if (k == hubKey) return
        hubKey = k
        runCatching { CaptionHub.layoutForBox(w, textPx, lines) }
    }

    private fun captionHeight(ctx: Context, sp: Int, lines: Int, d: Float): Int {
        capPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), ctx.resources.displayMetrics)
        return lines * capPaint.getFontMetricsInt(null) + px(14f, d)
    }

    // =============================================================== dev and test entry points

    /**
     * Simulate a PbyP break on the current stream screen, for testing without waiting for an ad.
     * A stand-in with a ticking "TEST LIVE" clock goes into Twitch's live box, which is shown (dev
     * only). Twitch's own player view, if one is left there from an earlier break, is never moved.
     */
    fun simulateStart() { main.post { safely("simulate start") { simStart() } } }

    /** End the simulated break: everything goes back, the stand-in goes, the box's visibility too. */
    fun simulateEnd() { main.post { safely("simulate end") { simEnd() } } }

    private fun simStart() {
        if (simulating) { log("PP sim: already on"); return }
        val act = lastAct?.get() ?: ViewHider.currentActivity() ?: run { log("PP sim: no activity"); return }
        runCatching { resolveIds(act) }
        val root = act.window?.peekDecorView() ?: run { log("PP sim: no window"); return }
        val c = container(root) ?: run { log("PP sim: no live box here, open a live stream on the classic stream screen first"); return }
        val s = FrameLayout(act).apply { tag = SIM_TAG; setBackgroundColor(0xFF1F1A2E.toInt()) }
        val label = TextView(act).apply {
            setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        s.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        c.addView(s, FrameLayout.LayoutParams(MATCH, MATCH))
        simView = WeakReference(s); simLabel = WeakReference(label); simBox = WeakReference(c)
        simOldVis = c.visibility
        simHidden = false
        if (c.visibility != View.VISIBLE) c.visibility = View.VISIBLE
        lastAct = WeakReference(act)
        simulating = true
        PbypWatch.simulated = true
        tick.run()
        log("PP sim on")
        requestSync()
    }

    private fun simEnd() {
        if (!simulating) { log("PP sim: not on"); return }
        simulating = false
        PbypWatch.simulated = false
        main.removeCallbacks(tick)
        restore("simulation over")
        simView?.get()?.let { s -> (s.parent as? ViewGroup)?.removeView(s) }
        simBox?.get()?.let { c -> if (simOldVis != View.VISIBLE && c.visibility == View.VISIBLE) c.visibility = simOldVis }
        simView = null; simLabel = null; simBox = null
        log("PP sim off")
        requestSync()
    }

    /**
     * Without an ad there is no ad info row, so in portrait Twitch's live box sits half over the
     * video. Our stand-in is then hidden while it is still in Twitch's box, instead of drawing there.
     */
    private fun simGuard(c: ViewGroup) {
        val s = simView?.get() ?: return
        if (s.parent !== c) { if (simHidden) { s.visibility = View.VISIBLE; simHidden = false }; return }
        val theatre = c.parent as? ViewGroup ?: return
        rectOf(c, rPlan)
        val clear = rectClear(rPlan, theatre)
        if (!clear && !simHidden) { s.visibility = View.INVISIBLE; simHidden = true; log("PP sim: stand-in hidden, Twitch's box touches $lastClash") }
        else if (clear && simHidden) { s.visibility = View.VISIBLE; simHidden = false }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!simulating) return
            val l = simLabel?.get() ?: return
            l.text = "TEST LIVE " + android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
            main.postDelayed(this, 1000)
        }
    }

    /**
     * Log where everything is, for testing. Java state only: never an IVS native call. The
     * wrapper's flags and volumes are read from its fields (its muted flag among them).
     */
    fun dump() {
        main.post {
            safely("dump") {
                log("PP dump: active=${PbypWatch.active} visible=${PbypWatch.visible} sim=$simulating mode=$mode " +
                    "refused=$refused failures=$failures previewOff=$previewOff enlarges=$enlarges " +
                    "settings preview=$sPreview captions=$sCaps lines=$sLines sp=$sSp")
                log("PP dump hooks: " + synchronized(notes) { notes.entries.joinToString { "${it.key} ${it.value}" } })
                val act = lastAct?.get()
                val root = act?.window?.peekDecorView()
                val c = root?.let { container(it) }
                val theatre = c?.parent as? ViewGroup
                fun r(v: View?): String {
                    if (v == null) return "none"
                    rectOf(v, rAd)
                    return "vis=${v.visibility} shown=${v.isShown} [${rAd.left},${rAd.top} ${v.width}x${v.height}]"
                }
                log("PP dump twitch: player_pane ${r(theatre?.let { find(it, idPane) })} | pbyp box ${r(c)} children=${c?.childCount}")
                val chat = theatre?.let { find(it, idChat) }
                log("PP dump chat: wrapper ${r(theatre?.let { find(it, idWrapper) })} | chat_view_container ${r(chat)} " +
                    "topMargin=${(chat?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin} ours=$marginDelta | overlay ${r(theatre?.let { find(it, idOverlay) })}")
                log("PP dump ours: host ${r(host?.get())} frame ${r(frame?.get())} caption ${r(capView?.get())} overlay ${r(inBox?.get())} stand-in ${r(simView?.get())}")
                val tpv = tpvRef?.get()
                val where = when {
                    tpv == null -> "unknown"
                    tpv.parent === frame?.get() -> "our preview box"
                    tpv.parent == null -> "detached"
                    else -> (tpv.parent as? View)?.let { Res.entry(it) } ?: tpv.parent.javaClass.simpleName
                }
                log("PP dump live view: $where, guard ${if (guardOk()) "clear" else "CLASH $lastClash"}")
                val core = PbypWatch.player()
                val w = tpv?.let { runCatching { mGetPlayer?.invoke(it) }.getOrNull() }
                val flags = w?.let { o ->
                    var k: Class<*>? = o.javaClass
                    val out = ArrayList<String>()
                    while (k != null && k != Any::class.java) {
                        for (f in k.declaredFields) {
                            if (Modifier.isStatic(f.modifiers)) continue
                            if (f.type != java.lang.Boolean.TYPE && f.type != java.lang.Float.TYPE) continue
                            runCatching { f.isAccessible = true; out.add("${f.name}=${f.get(o)}") }
                        }
                        k = k.superclass
                    }
                    out.joinToString(" ")
                } ?: "none"
                log("PP dump player: core=${core?.let { tag(it) } ?: "none"} session=${PbypWatch.audioSession()} wrapper fields: $flags")
                log("PP dump captions: ${capShown.length} chars, live=$capLive, box lines=$sLines")
            }
        }
    }

    // =============================================================== helpers

    private fun tag(o: Any) = Integer.toHexString(System.identityHashCode(o))

    private fun before(body: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            try { body(p) } catch (t: Throwable) { logLimited("PP hook error in ${p.method.name}: $t") }
        }
    }

    private fun after(body: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try { body(p) } catch (t: Throwable) { logLimited("PP hook error in ${p.method.name}: $t") }
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { logLimited("PP $what failed: $t") }
    }

    private val logLock = Any()
    private var logWindow = 0L
    private var logCount = 0

    /** Errors can repeat every frame: at most 30 a minute. */
    private fun logLimited(msg: String) {
        val now = SystemClock.uptimeMillis()
        val ok = synchronized(logLock) {
            if (now - logWindow > 60_000) { logWindow = now; logCount = 0 }
            logCount++ < 30
        }
        if (ok) log(msg)
    }
}
