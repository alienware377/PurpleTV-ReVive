package tv.purple.xp

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Locale
import java.util.TreeSet
import java.util.WeakHashMap

/**
 * "Ad volume": ads play at 50%, 25% or 15% of their normal loudness. The ad itself is untouched:
 * it plays in full, on screen, never muted, and counts for the streamer as usual.
 *
 * Where the sound is turned down, and why there:
 *  - One level below Twitch, on the gain of Android's AudioTrack, where every in-app sound ends up
 *    (Amazon IVS for video, ExoPlayer for audio ads). Nothing of Twitch's is called, so everything
 *    Twitch knows and reports stays as Twitch set it: IVS getVolume, Twitch's muted flag, its
 *    audio-ad loudness value and the player_volume it sends. Twitch's mute, the phone's volume and
 *    IVS setVolume/setMuted are never used.
 *  - The hook sits on AudioTrack.playerSetVolume, the one step every gain write passes through
 *    (Twitch's, IVS's own ad loudness, Android's). Several classes override it, so ART can not
 *    inline it past the hook, and Android keeps Twitch's unscaled value in PlayerBase while only
 *    what goes on to the speaker is scaled. If that hidden method can not be reached, the public
 *    setVolume and setStereoVolume are hooked instead.
 *  - Our gain is max(raw * f, min(raw, 3%)): never 0, never above what Twitch asked for, and a zero
 *    Twitch set itself (its mute, the muted stream beside a side-by-side ad) stays Twitch's zero.
 *  - Restoring re-writes Twitch's own last value, so the stream comes back exactly as it was.
 *
 * When an ad plays, in layers. Any one of them opens a window, and every end path restores:
 *  1. On the IVS player that plays a stitched (SureStream) ad: IVS's handleAdBreakStarted/Ended and
 *     the ad's DATERANGE metadata (CLASS twitch-stitched-ad..., ended by ...-break-end or by
 *     twitch-stream-source = live). Only that player's audio session is turned down.
 *  2. Twitch's own ad state (VideoAdActive, AudioAdActive, Inactive) as its ad presenter pushes it.
 *     Client ads play in their own IVS player whose source is not a Twitch stream, and audio ads on
 *     ExoPlayer, so during those windows exactly those are turned down.
 *  3. Fail-safes: a new load or release of the player, the option going back to Normal, and a
 *     watchdog (the ad's own length plus 20 s, IVS's ad clock going quiet, and a hard cap).
 *
 * Threads: hooks on IVS, audio and main threads only update small maps and hand the real work to
 * our own "ptv-advol" thread. The lock never wraps an IVS call or a log line, and no IVS native
 * method (getVolume, setVolume, isMuted) is ever called.
 *
 * Checked and never reads the lowered gain: Twitch's z53 (registered by vf2 in 31.7.0; a43 and wd2
 * in 31.1.2) listens to Android's playback configurations, but only to re-check the output device.
 * Apps other than the system's get those configurations without volumes anyway.
 */
object AdVolume {

    const val KEY = "ad_volume"
    /** What each option stores: the percentage of the ad's normal loudness. 100 is Twitch's own. */
    val VALUES = listOf(100, 50, 25, 15)
    val OPTIONS = listOf("Normal", "50%", "25%", "15%")
    const val DEF = 100

    /** About -30 dB: the floor for our gain, unless Twitch's own value is already lower. */
    private const val MIN_GAIN = 0.03f
    /** Added to every guessed ad length before the watchdog restores. */
    private const val GRACE_MS = 20_000L
    /** The watchdog when nothing says how long the ad is. */
    private const val NO_INFO_MS = 200_000L
    /** No window lasts longer than this, whatever keeps it open. */
    private const val HARD_CAP_MS = 10 * 60_000L
    /** How long IVS's ad clock may go quiet before the ad counts as over. */
    private const val TICK_QUIET_MS = 10_000L
    private const val POLL_MS = 500L
    /** A second pass after each change, for a sink IVS rebuilt while we switched. */
    private const val SETTLE_MS = 400L
    /** Largest metadata read. DATERANGE tags carry long tokens, so this is generous. */
    private const val CUE_CAP = 64 * 1024

    private enum class Kind { NEW, LIVE, VOD, CLIP, SOURCE, OTHER }
    private enum class Tw { NONE, VIDEO_SS, VIDEO_CLIENT, AUDIO }

    /** One IVS player (CorePlayerImpl). */
    private class PInfo {
        var kind = Kind.NEW
        /** Host and path of the last load, for status lines. No query: it carries tokens. */
        var where = ""
        var ad = false
        val from = LinkedHashSet<String>()
        var since = 0L
        var deadline = 0L
        var lastId: String? = null
        var endedId: String? = null
        var endedAt = 0L
        var endedBy = ""
        var lastTick = 0L
        var tickGap = 0L
        var ticks = 0
        /** When each ad ID was first seen, so a repeated cue can never stretch its window. */
        val idSeen = HashMap<String, Long>()
    }

    /** One AudioTrack. raw is Twitch's own last gain, applied what went on to the speaker. */
    private class TInfo(var session: Int, var exo: Boolean) {
        var seen = false
        var rawL = 1f
        var rawR = 1f
        var applied = 1f
        var scaled = false
        var fApplied = 1f
        /** Gain writes that were not ours, so a setStereoVolume restore can tell one raced it. */
        var writes = 0
        fun raw() = maxOf(rawL, rawR)
    }

    // ---- state: everything below is guarded by L ----

    private val L = Any()
    private val players = WeakHashMap<Any, PInfo>()
    private val tracks = WeakHashMap<AudioTrack, TInfo>()
    /** Source listener to the address IVS preloaded for it, so loadSource can tell a live stream. */
    private val preloads = WeakHashMap<Any, Uri>()
    private val renderers = WeakHashMap<Any, Boolean>()
    private val excluded = HashSet<Int>()
    private var tw = Tw.NONE
    private var twDeadline = 0L
    /** Sessions whose tracks are scaled, sorted. Replaced, never changed in place. */
    private var adSessions = IntArray(0)
    private var fNow = 1f
    /** Bumped whenever adSessions or fNow change, so a gain write that raced a change can tell.
     *  Written under L, read without it by the gain hook's after step. */
    @Volatile private var gen = 0
    private var tracksLogged = 0

    // ---- ptv-advol thread only ----

    private var afSessions: Set<Int> = emptySet()
    private var polling = false

    @Volatile private var installed = false
    @Volatile private var mode = "none"
    /** PlayerBase's record of Twitch's own gain. Only used with the playerSetVolume hook. */
    @Volatile private var fieldL: Field? = null
    @Volatile private var fieldR: Field? = null
    /** PlayerBase.updatePlayerVolume: pushes Twitch's own stored gain again without changing it. */
    @Volatile private var mUpdate: Method? = null
    @Volatile private var mSession: Method? = null
    @Volatile private var rTrack: Field? = null
    @Volatile private var rVolume: Field? = null
    @Volatile private var presCls: Class<*>? = null
    @Volatile private var vidCls: Class<*>? = null
    @Volatile private var audCls: Class<*>? = null
    @Volatile private var typeField: Field? = null

    private val bg: Handler by lazy { Handler(HandlerThread("ptv-advol").apply { start() }.looper) }

    // ---- the setting ----

    @Volatile private var fRev = Int.MIN_VALUE
    @Volatile private var fCached = 1f

    /** The chosen factor, 0.15 to 1. Re-read only after a settings write. */
    fun factor(): Float {
        if (!Settings.isReady()) return 1f
        val r = Settings.rev
        if (r != fRev) {
            // Anything that is not one of the options means Normal, exactly as the menu shows it,
            // so a stray value (an old index, a synced value from another build) never turns ads
            // down while the row says Normal.
            val v = Settings.getInt(KEY, DEF)
            fCached = (if (v in VALUES) v else DEF) / 100f
            fRev = r
        }
        return fCached
    }

    /** The one place a gain is computed. */
    private fun scaled(raw: Float, f: Float): Float =
        if (raw.isNaN() || raw <= 0f || f >= 1f) raw else maxOf(raw * f, minOf(raw, MIN_GAIN))

    /** The setting changed: re-apply at once (from the menu, DevBridge or sync). */
    fun refresh() {
        if (!installed) return
        bg.post { safely("refresh") { recompute("setting"); if (anyOpen()) ensurePoll() } }
    }

    /** Keep an in-app sound of our own (read-aloud) at its own level during audio-ad windows. */
    fun exclude(session: Int) {
        if (session <= 0) return
        synchronized(L) { excluded.add(session) }
        if (installed) bg.post { safely("exclude") { recompute("exclude") } }
    }

    fun unexclude(session: Int) {
        synchronized(L) { excluded.remove(session) }
        if (installed) bg.post { safely("exclude") { recompute("exclude") } }
    }

    // ---- install ----

    fun install(lp: LoadPackageParam) {
        if (installed) return
        installed = true
        bg
        hookTracks()
        val cl = lp.classLoader
        val core = runCatching { cl.loadClass("com.amazonaws.ivs.player.CorePlayerImpl") }.getOrNull()
        if (core != null) hookIvs(core, cl) else log("AV IVS player not found: stitched ads are not detected")
        hookTwitchState(cl)
        log("AV ad volume installed, mode=$mode, setting ${Settings.getInt(KEY, DEF)}%")
    }

    // updatePlayerVolume is a blocked hidden method: when Android refuses it, the lookup comes back
    // empty and restores simply go through setStereoVolume.
    @SuppressLint("BlockedPrivateApi")
    private fun hookTracks() {
        val at = AudioTrack::class.java
        val nc = runCatching { XposedBridge.hookAllConstructors(at, ctorHook).size }.getOrDefault(0)
        log("AV hook AudioTrack constructors: $nc")
        val nr = runCatching { XposedBridge.hookAllMethods(at, "release", releaseHook).size }.getOrDefault(0)
        log("AV hook AudioTrack.release: $nr")
        val f = Float::class.javaPrimitiveType
        val psv = runCatching {
            at.getDeclaredMethod("playerSetVolume", Boolean::class.javaPrimitiveType, f, f)
        }.getOrNull()
        if (psv != null && runCatching { XposedBridge.hookMethod(psv, gainHook(1, 2)) }.isSuccess) {
            mode = "playerSetVolume"
            runCatching {
                val pb = Class.forName("android.media.PlayerBase")
                val l = pb.getDeclaredField("mLeftVolume").apply { isAccessible = true }
                val r = pb.getDeclaredField("mRightVolume").apply { isAccessible = true }
                fieldL = l; fieldR = r
                // Re-pushes what PlayerBase holds right now. Unlike setStereoVolume it never stores
                // a value of ours there, so a Twitch write racing our restore can not be overwritten.
                mUpdate = runCatching { pb.getDeclaredMethod("updatePlayerVolume").apply { isAccessible = true } }.getOrNull()
            }
            log("AV hook AudioTrack.playerSetVolume: ok, Twitch's gain read from " +
                (if (fieldL != null) "PlayerBase" else "the call") +
                ", restore via " + (if (mUpdate != null) "updatePlayerVolume" else "setStereoVolume"))
            return
        }
        // Fallback: the public pair. setVolume(F) calls setStereoVolume itself, and the depth
        // counter in gainHook makes sure only the outer one scales.
        val n1 = runCatching { XposedBridge.hookMethod(at.getMethod("setVolume", f), gainHook(0, 0)); 1 }.getOrDefault(0)
        val n2 = runCatching { XposedBridge.hookMethod(at.getMethod("setStereoVolume", f, f), gainHook(0, 1)); 1 }.getOrDefault(0)
        mode = if (n1 + n2 > 0) "setVolume" else "none"
        log("AV hook AudioTrack.playerSetVolume unavailable, fallback setVolume=$n1 setStereoVolume=$n2")
    }

    private fun hookIvs(core: Class<*>, cl: ClassLoader) {
        // Public and pure Java (Platform's stored id), so it is safe from any thread.
        mSession = runCatching { core.getMethod("getAudioSessionId") }.getOrNull()
        log("AV player audio session getter: ${if (mSession != null) "ok" else "missing"}")
        hookPublic(core, "handleAdBreakStarted", before { p -> onAdBreak(p, true) })
        hookPublic(core, "handleAdBreakEnded", before { p -> onAdBreak(p, false) })
        hookPublic(core, "handleMetadata", before { p ->
            onMetadata(p.thisObject ?: return@before, p.args.getOrNull(0) as? String, p.args.getOrNull(1) as? ByteBuffer)
        })
        hookPublic(core, "handleAdCreativeStarted", before { p -> onCreative(p) })
        hookPublic(core, "handleAdTimeUpdate", before { p -> onTick(p.thisObject ?: return@before) })
        val load = before { p -> onLoad(p) }
        for (n in arrayOf("load", "loadSource", "loadClipManifest")) hookPublic(core, n, load)
        hookPublic(core, "release", before { p -> reset(p.thisObject ?: return@before, "release", null, null) })
        hookPublic(core, "preload", before { p ->
            val u = p.args.getOrNull(0) as? Uri ?: return@before
            val l = p.args.getOrNull(1) ?: return@before
            synchronized(L) { preloads[l] = u }
        })

        // A new audio sink mid-ad gets a new session. configureAudio is large (never inlined) and
        // runs after IVS stored the new session, unlike the tiny Platform.setAudioSessionId.
        runCatching {
            val trk = cl.loadClass("com.amazonaws.ivs.player.MediaSink\$Track")
            hookPublic(trk, "configureAudio", after { _ ->
                if (anyOpen()) {
                    first("new-sink", "an audio sink was built during an ad")
                    bg.post { safely("sink") { recompute("new audio sink") } }
                }
            })
        }.onFailure { log("AV hook configureAudio failed: $it") }

        // IVS's renderer keeps the exact gain it wants on its track. Only read in fallback mode,
        // where PlayerBase's copy is our scaled one.
        runCatching {
            val atr = cl.loadClass("com.amazonaws.ivs.player.AudioTrackRenderer")
            rTrack = atr.getDeclaredField("track").apply { isAccessible = true }
            rVolume = atr.getDeclaredField("volume").apply { isAccessible = true }
            hookPublic(atr, "configure", after { p -> synchronized(L) { renderers[p.thisObject] = true } })
        }.onFailure { log("AV hook AudioTrackRenderer failed: $it") }
    }

    private fun hookTwitchState(cl: ClassLoader) {
        val off: String? = runCatching<String?> {
            val rx = cl.loadClass("tv.twitch.android.core.mvp.presenter.RxPresenter")
            val ps = cl.loadClass("tv.twitch.android.core.mvp.presenter.PresenterState")
            val pres = cl.loadClass(TwitchVersion.pick("gh30", "t640"))
            val vid = cl.loadClass(TwitchVersion.pick("dh30", "q640"))
            val aud = cl.loadClass(TwitchVersion.pick("zg30", "m640"))
            // The pins are renamed in every Twitch release: check their shape before trusting them.
            if (!rx.isAssignableFrom(pres)) return@runCatching "the presenter pin is not a presenter"
            val bool = Boolean::class.javaPrimitiveType
            val vc = vid.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 2 && it.parameterTypes[0] == bool && it.parameterTypes[1].isEnum
            } ?: return@runCatching "VideoAdActive pin has no (boolean, enum) constructor"
            val en = vc.parameterTypes[1]
            val names = en.enumConstants?.map { (it as Enum<*>).name }.orEmpty()
            if ("Client" !in names || "SureStream" !in names) return@runCatching "ad type enum is $names"
            if (aud.declaredConstructors.none { it.parameterTypes.size == 1 && it.parameterTypes[0] == bool })
                return@runCatching "AudioAdActive pin has no (boolean) constructor"
            if (vid.superclass != aud.superclass) return@runCatching "the ad state pins do not share a parent"
            val tf = vid.declaredFields.firstOrNull { it.type == en } ?: return@runCatching "no ad type field"
            tf.isAccessible = true
            val push = rx.declaredMethods
                .filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == ps && it.returnType == Void.TYPE }
                .let { if (it.size == 1) it[0] else rx.getDeclaredMethod("x2", ps) }
            presCls = pres; vidCls = vid; audCls = aud; typeField = tf
            XposedBridge.hookMethod(push, twitchHook)
            log("AV hook RxPresenter.${push.name}(PresenterState): ok")
            null
        }.getOrElse { "error $it" }
        if (off != null) { log("AV twitch state path off: $off"); return }

        // The reducer calls that push on a final class, so ART may inline it into the compiled
        // reducer and skip the hook. Running the reducer interpreted keeps the call real.
        val red = runCatching {
            cl.loadClass(TwitchVersion.pick("mwz", "ii00")).getDeclaredMethod("invoke", Any::class.java)
        }.getOrNull()
        val deopt = XposedBridge::class.java.methods.firstOrNull { it.name == "deoptimizeMethod" && it.parameterTypes.size == 1 }
        val r = when {
            red == null -> "reducer not found"
            deopt == null -> "not available in this Xposed"
            else -> runCatching { deopt.invoke(null, red); "ok" }.getOrElse { "failed: $it" }
        }
        log("AV deoptimize ad state reducer: $r")
    }

    private fun hookPublic(c: Class<*>, name: String, h: XC_MethodHook): Int {
        val n = c.declaredMethods
            .filter { it.name == name && Modifier.isPublic(it.modifiers) && !Modifier.isNative(it.modifiers) }
            .count { m -> runCatching { XposedBridge.hookMethod(m, h) }.isSuccess }
        log("AV hook ${c.simpleName}.$name: $n")
        return n
    }

    private fun before(body: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            try { body(p) } catch (t: Throwable) { logLimited("AV hook error in ${p.method.name}: $t") }
        }
    }

    private fun after(body: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try { body(p) } catch (t: Throwable) { logLimited("AV hook error in ${p.method.name}: $t") }
        }
    }

    // ---- the gain hook (every in-app volume write) ----

    /**
     * [0] nesting depth of our hooked methods, [1] set while we re-write a raced value, [2] the
     * decision generation this call was scaled with (-1 when it was not looked at), [3] set while
     * this thread runs one of our own setStereoVolume restores.
     */
    private val depth = object : ThreadLocal<IntArray>() { override fun initialValue() = IntArray(4) }

    private fun gainHook(li: Int, ri: Int) = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val d = depth.get() ?: return
            if (d[0]++ == 0) {
                d[2] = -1
                try { d[2] = onGain(p, li, ri, d[3] != 0) } catch (_: Throwable) {}
            }
        }
        override fun afterHookedMethod(p: MethodHookParam) {
            val d = depth.get() ?: return
            if (d[0] > 0 && --d[0] == 0) try { afterGain(p, d) } catch (_: Throwable) {}
        }
    }

    /**
     * Record Twitch's value, and scale what goes on to the speaker when this session is an ad's.
     * Returns the decision generation used, or -1 when the call was not ours to look at.
     * [mine] is set when this is one of our own setStereoVolume restores.
     */
    private fun onGain(p: XC_MethodHook.MethodHookParam, li: Int, ri: Int, mine: Boolean): Int {
        val t = p.thisObject as? AudioTrack ?: return -1
        var l = p.args[li] as? Float ?: return -1
        var r = p.args[ri] as? Float ?: return -1
        var rl = l
        var rr = r
        val fl = fieldL
        val fr = fieldR
        if (fl != null && fr != null) { rl = fl.getFloat(t); rr = fr.getFloat(t) }
        synchronized(L) {
            val i = tracks[t] ?: TInfo(t.audioSessionId, false).also { tracks[t] = it }
            if (mine && i.seen) {
                // Our own restore is never recorded as Twitch's value: its snapshot may be older
                // than a Twitch write that just landed. With the public hooks it carries Twitch's
                // newest value instead. playerSetVolume's values already include Android's own
                // multiplier (a phone call holds it at 0), so those stay, and rewrite's second
                // pass heals a stale one.
                if (fl == null || fr == null) {
                    l = i.rawL; r = if (ri != li) i.rawR else i.rawL
                    p.args[li] = l
                    if (ri != li) p.args[ri] = r
                    rl = l; rr = r
                }
            } else {
                i.rawL = rl; i.rawR = rr; i.seen = true
                if (!mine) i.writes++
            }
            val raw = maxOf(rl, rr)
            val f = fNow
            if (f < 1f && raw > 0f && Arrays.binarySearch(adSessions, i.session) >= 0) {
                val k = scaled(raw, f) / raw
                p.args[li] = l * k
                if (ri != li) p.args[ri] = r * k
                i.applied = maxOf(l, r) * k; i.scaled = true; i.fApplied = f
            } else {
                i.applied = maxOf(l, r); i.scaled = false
            }
            return gen
        }
    }

    /**
     * The write above reaches the speaker after the lock is let go. If an ad started or ended in
     * between, it carried a stale decision, and Twitch may not write again for a long time: push
     * Twitch's own value once more so the current decision applies. The generation is this call's
     * own, so a restore of ours that ran in between can not hide the stale write.
     */
    private fun afterGain(p: XC_MethodHook.MethodHookParam, d: IntArray) {
        if (d[1] != 0 || d[2] < 0 || d[2] == gen) return
        val t = p.thisObject as? AudioTrack ?: return
        d[1] = 1
        try { rewrite(t) } finally { d[1] = 0 }
    }

    private val ctorHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try { onTrack(p.thisObject as? AudioTrack ?: return) } catch (_: Throwable) {}
        }
    }

    private val releaseHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val t = p.thisObject as? AudioTrack ?: return
            synchronized(L) { tracks.remove(t) }
        }
    }

    private fun onTrack(t: AudioTrack) {
        val thread = Thread.currentThread().name
        // ExoPlayer, which plays the audio ads, builds its tracks on its own playback thread.
        val exo = thread.startsWith("ExoPlayer:")
        val s = t.audioSessionId
        var joined = false
        var say = false
        synchronized(L) {
            // Android may already have pushed a gain from inside the constructor: keep what we saw,
            // but take the session from now, when the constructor has surely stored it.
            val known = tracks[t]
            if (known == null) tracks[t] = TInfo(s, exo) else { known.exo = exo; if (s > 0) known.session = s }
            // Joined at once, so ExoPlayer's very first gain write already arrives scaled.
            if (exo && s > 0 && fNow < 1f && (tw == Tw.VIDEO_CLIENT || tw == Tw.AUDIO) && s !in excluded &&
                Arrays.binarySearch(adSessions, s) < 0
            ) {
                adSessions = (adSessions + s).also { it.sort() }
                gen++
                joined = true
            }
            if (tracksLogged < 8) { tracksLogged++; say = true }
        }
        if (say) log("AV track session=$s thread=$thread")
        if (joined) {
            first("exo-track", "session $s joined the ad")
            bg.post { safely("exo track") { recompute("exo track") } }
        }
    }

    /**
     * Push Twitch's own value again, so our hook decides afresh. With updatePlayerVolume Android
     * re-reads Twitch's latest value itself at that moment and nothing of Twitch's is overwritten.
     */
    private fun rewrite(t: AudioTrack): Boolean {
        val up = mUpdate
        if (up != null) {
            if (rawNow(t) == null) return false
            up.invoke(t)
            return true
        }
        // setStereoVolume stores our snapshot in PlayerBase, so a Twitch write that lands between
        // the snapshot and our write would be undone. The count is read before the snapshot: any
        // Twitch write after it shows up, and then Twitch's newest value (its own 0 too) is sent
        // once more so it lands last.
        val w0 = synchronized(L) { tracks[t]?.writes } ?: return false
        val v = rawNow(t) ?: return false
        val d = depth.get()
        val was = d?.get(3) ?: 0
        d?.set(3, 1)
        try {
            t.setStereoVolume(v[0], v[1])
            val again = synchronized(L) {
                tracks[t]?.takeIf { it.writes != w0 }?.let { floatArrayOf(it.rawL, it.rawR) }
            }
            if (again != null) t.setStereoVolume(again[0], again[1])
        } finally {
            d?.set(3, was)
        }
        return true
    }

    /**
     * Twitch's current gain for this track, or null to leave it alone: never seen a write (no
     * guessing a default), or Twitch's own zero. Never above the last value we saw Twitch write.
     */
    private fun rawNow(t: AudioTrack): FloatArray? {
        var oL = 0f
        var oR = 0f
        var rv = Float.NaN
        val fl = fieldL
        val fr = fieldR
        synchronized(L) {
            val i = tracks[t] ?: return null
            if (!i.seen) return null
            oL = i.rawL; oR = i.rawR
            if (fl == null) {
                val rt = rTrack; val rvf = rVolume
                if (rt != null && rvf != null) for (r in renderers.keys) if (rt.get(r) === t) { rv = rvf.getFloat(r); break }
            }
        }
        var sL = oL
        var sR = oR
        if (fl != null && fr != null) { sL = fl.getFloat(t); sR = fr.getFloat(t) }
        else if (!rv.isNaN()) { sL = rv; sR = rv }
        val l = minOf(sL, oL)
        val r = minOf(sR, oR)
        return if (maxOf(l, r) > 0f) floatArrayOf(l, r) else null
    }

    // ---- which sessions belong to an ad (ptv-advol thread) ----

    private fun sessionOf(p: Any): Int = runCatching { mSession?.invoke(p) as? Int ?: 0 }.getOrDefault(0)

    private fun recompute(why: String) {
        val f = factor()
        val ivs = ArrayList<Any>()
        val others = ArrayList<Any>()
        val exo = HashSet<Int>()
        val excl = HashSet<Int>()
        val twNow = synchronized(L) {
            for ((p, i) in players) if (i.ad) ivs.add(p) else if (i.kind == Kind.OTHER) others.add(p)
            if (tw == Tw.VIDEO_CLIENT || tw == Tw.AUDIO) for (i in tracks.values) if (i.exo && i.session > 0) exo.add(i.session)
            excl.addAll(excluded)
            tw
        }
        val cands = TreeSet<Int>()
        if (f < 1f) {
            for (p in ivs) sessionOf(p).let { if (it > 0) cands.add(it) }
            if (twNow == Tw.VIDEO_CLIENT || twNow == Tw.AUDIO) {
                for (p in others) sessionOf(p).let { if (it > 0) cands.add(it) }
                cands.addAll(exo)
            }
            cands.removeAll(excl)
        }
        // Auto volume and the Compressor would pull a quieter track straight back up, so on those
        // sessions the cut goes into AudioFilters' effect after its compressor, and the track keeps
        // Twitch's gain.
        val af = cands.filterTo(HashSet()) { AudioFilters.evensOut(it) }
        val mine = cands.filter { it !in af }.toIntArray()
        val changed = HashSet<Int>()
        synchronized(L) {
            val old = adSessions
            if (f != fNow) {
                for (s in old) changed.add(s)
                for (s in mine) changed.add(s)
            } else {
                for (s in old) if (Arrays.binarySearch(mine, s) < 0) changed.add(s)
                for (s in mine) if (Arrays.binarySearch(old, s) < 0) changed.add(s)
            }
            if (changed.isNotEmpty() || f != fNow) { adSessions = mine; fNow = f; gen++ }
        }
        val db = if (f < 1f) (20.0 * Math.log10(f.toDouble())).toFloat() else 0f
        val oldAf = afSessions
        for (s in af) AudioFilters.setAdCut(s, db)
        for (s in oldAf) if (s !in af) AudioFilters.setAdCut(s, 0f)
        afSessions = af
        if (af != oldAf) logLimited("AV filters cut ${fmt(db)} dB on sessions $af (was $oldAf) ($why)")
        if (changed.isEmpty()) return
        applyTo(changed, false, why)
        bg.postDelayed({ safely("settle") { applyTo(changed, true, null) } }, SETTLE_MS)
    }

    /** Re-write the tracks of these sessions whose applied gain does not match the decision now. */
    private fun applyTo(sessions: Set<Int>, force: Boolean, why: String?) {
        val todo = ArrayList<AudioTrack>()
        // The forced pass is plain insurance. Only updatePlayerVolume is free to repeat: a
        // setStereoVolume restore stores a value of ours, so it is sent only when a track is wrong.
        val redo = force && mUpdate != null
        synchronized(L) {
            for ((t, i) in tracks) {
                if (!i.seen || i.session !in sessions) continue
                val want = fNow < 1f && Arrays.binarySearch(adSessions, i.session) >= 0
                if (redo || want != i.scaled || (want && i.fApplied != fNow)) todo.add(t)
            }
        }
        // Outside the lock: a framework call can wait on the audio server.
        for (t in todo) runCatching { rewrite(t) }
        if (why == null) return
        val lines = ArrayList<String>()
        synchronized(L) {
            for (s in sessions) {
                val on = fNow < 1f && Arrays.binarySearch(adSessions, s) >= 0
                val ts = tracks.values.filter { it.session == s && it.seen }
                    .joinToString { "${fmt(it.raw())}->${fmt(it.applied)}" }
                lines.add("AV ${if (on) "apply" else "restore"} session=$s f=$fNow ($why) tracks=[$ts]")
            }
        }
        for (l in lines) logLimited(l)
    }

    // ---- windows ----

    private fun anyOpen() = synchronized(L) { tw != Tw.NONE || players.values.any { it.ad } }

    private fun ensurePoll() {
        if (polling) return
        polling = true
        bg.postDelayed(pollTask, POLL_MS)
    }

    /**
     * While any window is open: the watchdog, a new audio session on a marked player, a settings
     * change that came through sync, and AudioFilters being switched on or off mid-ad.
     */
    private val pollTask = Runnable {
        polling = false
        safely("poll") {
            val now = SystemClock.uptimeMillis()
            val ends = ArrayList<String>()
            synchronized(L) {
                for ((p, i) in players) {
                    if (!i.ad) continue
                    val why = when {
                        now - i.since > HARD_CAP_MS -> "watchdog"
                        // IVS's ad clock ticked steadily and then stopped: the ad is over.
                        i.ticks >= 3 && now > i.lastTick + maxOf(TICK_QUIET_MS, 3 * i.tickGap) -> "ticks"
                        i.ticks < 3 && now > i.deadline -> "watchdog"
                        else -> null
                    } ?: continue
                    ends.add("AV end src=$why player=${tag(p)} " + endLocked(i, why, now))
                }
                if (tw != Tw.NONE && now > twDeadline) { ends.add("AV end src=watchdog twitch=$tw"); tw = Tw.NONE }
            }
            for (e in ends) log(e)
            recompute("poll")
            if (anyOpen()) ensurePoll()
        }
    }

    /** Close a player's window. Called under L; returns what to log once the lock is let go. */
    private fun endLocked(i: PInfo, src: String, now: Long): String {
        val d = "from=${i.from} after ${(now - i.since) / 1000}s"
        i.ad = false; i.from.clear()
        i.endedAt = now; i.endedId = i.lastId; i.endedBy = src
        i.lastTick = 0; i.ticks = 0
        return d
    }

    private fun ivsStart(player: Any, src: String, durMs: Long, left: Int, id: String?, grace: Long = GRACE_MS) {
        val now = SystemClock.uptimeMillis()
        var opened = false
        var kind = Kind.NEW
        var dl = 0L
        synchronized(L) {
            val i = players.getOrPut(player) { PInfo() }
            // The same ad's start seen again just after its end is an echo, not a new ad.
            if (id != null && id == i.endedId && now - i.endedAt < 2_000) return
            val firstSeen = if (id == null) now else {
                if (i.idSeen.size > 16) i.idSeen.clear()
                i.idSeen.getOrPut(id) { now }
            }
            dl = firstSeen + (if (durMs > 0) durMs * maxOf(1, left) else NO_INFO_MS) + grace
            kind = i.kind
            if (!i.ad) {
                i.ad = true; i.since = now; i.deadline = dl; i.from.clear(); i.lastTick = 0; i.ticks = 0
                opened = true
            } else i.deadline = maxOf(i.deadline, dl)
            i.from.add(src)
            if (id != null) i.lastId = id
        }
        if (!opened) return
        log("AV start src=$src player=${tag(player)} kind=$kind watchdog=${(dl - now) / 1000}s")
        bg.post { safely("start") { recompute("start $src"); ensurePoll() } }
    }

    private fun ivsEnd(player: Any, src: String) {
        val d = synchronized(L) {
            val i = players[player] ?: return
            if (!i.ad) return
            endLocked(i, src, SystemClock.uptimeMillis())
        }
        log("AV end src=$src player=${tag(player)} $d")
        bg.post { safely("end") { recompute("end $src") } }
    }

    /** A load (kind set) or a release (kind null) on a player: any ad marking on it ends. */
    private fun reset(player: Any, src: String, kind: Kind?, uri: Uri?) {
        var ended: String? = null
        val open = synchronized(L) {
            val i = if (kind == null) players.remove(player) else players.getOrPut(player) { PInfo() }
            if (i != null && i.ad) ended = endLocked(i, src, SystemClock.uptimeMillis())
            if (i != null && kind != null) { i.kind = kind; i.where = where(uri) }
            tw != Tw.NONE || players.values.any { it.ad }
        }
        ended?.let { log("AV end src=$src player=${tag(player)} $it") }
        // On the main thread here, so the restore itself runs on ours.
        if (ended != null || open) bg.post { safely(src) { recompute(src) } }
    }

    // ---- IVS signals ----

    private fun onAdBreak(p: XC_MethodHook.MethodHookParam, start: Boolean) {
        val player = p.thisObject ?: return
        if (!start) { first("ivs-end", ""); ivsEnd(player, "ivs"); return }
        val ab = p.args.getOrNull(0)
        val dur = msOf(call(ab, "getDuration") as? Long ?: 0L)
        val len = call(ab, "getPodLength") as? Int ?: 0
        first("ivs-start", "dur=${dur}ms pod=$len")
        ivsStart(player, "ivs", dur, 1, call(ab, "getAdBreakId") as? String)
    }

    private fun onCreative(p: XC_MethodHook.MethodHookParam) {
        val player = p.thisObject ?: return
        val dur = msOf(call(p.args.getOrNull(0), "getDuration") as? Long ?: 0L)
        first("creative", "dur=${dur}ms")
        if (dur <= 0) return
        val now = SystemClock.uptimeMillis()
        synchronized(L) { players[player]?.let { if (it.ad) it.deadline = maxOf(it.deadline, now + dur + GRACE_MS) } }
    }

    /** IVS's ad clock: keeps the window open while it ticks, and reopens one it closed by going quiet. */
    private fun onTick(player: Any) {
        val now = SystemClock.uptimeMillis()
        var reopened = false
        synchronized(L) {
            val i = players[player] ?: return
            if (!i.ad && i.endedBy == "ticks" && now - i.endedAt < 60_000) {
                i.ad = true; i.since = now; i.deadline = now + NO_INFO_MS; i.from.clear(); i.from.add("ticks")
                i.lastTick = 0; i.ticks = 0
                reopened = true
            }
            if (i.lastTick > 0) i.tickGap = now - i.lastTick
            i.lastTick = now
            i.ticks++
        }
        first("ticks", "")
        if (reopened) {
            log("AV start src=ticks player=${tag(player)} (the ad clock is ticking again)")
            bg.post { safely("ticks") { recompute("ticks"); ensurePoll() } }
        }
    }

    /** IVS durations come without a unit: big numbers are taken as milliseconds, small as seconds. */
    private fun msOf(x: Long) = if (x > 1000) x else x * 1000

    private val NEEDLE = "twitch-st".toByteArray(Charsets.US_ASCII)

    /**
     * Every text/json metadata IVS delivers, on its own thread: quick byte scan first, then only the
     * few keys we need. Read from a duplicate, because Twitch copies the original right after us
     * and its position must not move.
     */
    private fun onMetadata(player: Any, type: String?, buf: ByteBuffer?) {
        if (buf == null || type != "text/json") return
        val d = buf.duplicate()
        val from = d.position()
        val to = minOf(d.limit(), from + CUE_CAP)
        if (to - from < NEEDLE.size || !hasNeedle(d, from, to)) return
        val bytes = ByteArray(to - from)
        d.get(bytes)
        onCue(player, String(bytes, Charsets.ISO_8859_1))
    }

    private fun hasNeedle(b: ByteBuffer, from: Int, to: Int): Boolean {
        val n = NEEDLE
        var i = from
        val last = to - n.size
        while (i <= last) {
            if (b.get(i) == n[0]) {
                var k = 1
                while (k < n.size && b.get(i + k) == n[k]) k++
                if (k == n.size) return true
            }
            i++
        }
        return false
    }

    private fun onCue(player: Any, s: String) {
        val cls = jsonStr(s, "CLASS") ?: return
        when {
            cls == "twitch-stream-source" -> if (jsonStr(s, "X-TV-TWITCH-STREAM-SOURCE") == "live") {
                first("cue-end", "stream source live")
                ivsEnd(player, "cue")
            }
            cls.startsWith("twitch-stitched-ad") && cls.endsWith("-break-end") -> {
                first("cue-end", cls)
                ivsEnd(player, "cue")
            }
            cls.startsWith("twitch-stitched-ad") -> {
                val dur = jsonStr(s, "DURATION")?.toFloatOrNull() ?: 0f
                val len = jsonStr(s, "X-TV-TWITCH-AD-POD-LENGTH")?.toIntOrNull() ?: 1
                val pos = jsonStr(s, "X-TV-TWITCH-AD-POD-POSITION")?.toIntOrNull() ?: 0
                first("cue-start", "$cls dur=$dur pod=$pos/$len")
                ivsStart(player, "cue", (dur * 1000).toLong(), len - pos, jsonStr(s, "ID"))
            }
        }
    }

    /** One top-level value, string or bare. Enough for the flat DATERANGE object IVS sends. */
    private fun jsonStr(s: String, key: String): String? {
        val k = s.indexOf("\"$key\"")
        if (k < 0) return null
        var i = k + key.length + 2
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != ':') return null
        i++
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return null
        if (s[i] == '"') {
            val e = s.indexOf('"', i + 1)
            return if (e < 0) null else s.substring(i + 1, e)
        }
        var e = i
        while (e < s.length && s[e] != ',' && s[e] != '}' && !s[e].isWhitespace()) e++
        return s.substring(i, e)
    }

    private fun onLoad(p: XC_MethodHook.MethodHookParam) {
        val player = p.thisObject ?: return
        val a0 = p.args.getOrNull(0)
        var uri: Uri? = null
        val kind = when (p.method.name) {
            "loadClipManifest" -> Kind.CLIP
            // Twitch's preload path: the source carries the listener its preload was given.
            "loadSource" -> {
                val l = a0?.let { runCatching { XposedHelpers.getObjectField(it, "listener") }.getOrNull() }
                uri = l?.let { synchronized(L) { preloads[it] } }
                if (uri == null) Kind.SOURCE else classify(uri, null).let { if (it == Kind.OTHER) Kind.SOURCE else it }
            }
            else -> {
                uri = a0 as? Uri ?: call(a0, "getUri") as? Uri
                classify(uri, p.args.getOrNull(1) as? String ?: call(a0, "getMediaType") as? String)
            }
        }
        reset(player, "load", kind, uri)
    }

    /** Twitch streams, VODs and clips are never ads. Anything else is where client ads land. */
    private fun classify(u: Uri?, type: String?): Kind {
        if (type == "application/x-twitch-clip") return Kind.CLIP
        if (type == "video/mp4") return Kind.OTHER
        val host = u?.host.orEmpty()
        val path = u?.path.orEmpty()
        return when {
            path.contains("/channel/hls/") -> Kind.LIVE
            path.contains("/vod/") && (host.contains("usher") || host.contains("ttvnw") || host.contains("twitch")) -> Kind.VOD
            host.contains("usher") -> Kind.LIVE
            else -> Kind.OTHER
        }
    }

    // ---- Twitch's own ad state ----

    private val twitchHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            // Every presenter in the app pushes its state through here, on the main thread: return
            // at once for all but Twitch's ad presenter, and do the rest on our own thread.
            if (p.thisObject?.javaClass !== presCls) return
            try {
                val st = p.args.getOrNull(0) ?: return
                val k = when (st.javaClass) {
                    vidCls -> if ((typeField?.get(st) as? Enum<*>)?.name == "SureStream") Tw.VIDEO_SS else Tw.VIDEO_CLIENT
                    audCls -> Tw.AUDIO
                    else -> Tw.NONE
                }
                bg.post { safely("twitch state") { twitchState(k) } }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Twitch's state opens and closes only its own windows (client and audio ads). Stitched ads are
     * matched on the IVS player itself, so Twitch's Inactive between two stitched ads does not
     * bring the volume up for a moment.
     */
    private fun twitchState(k: Tw) {
        val now = SystemClock.uptimeMillis()
        val old = synchronized(L) {
            val o = tw
            tw = k
            // Re-armed on every push (pause, resume, next ad), so only a lost Inactive hits it.
            if (k != Tw.NONE) twDeadline = now + NO_INFO_MS
            o
        }
        if (old != k) {
            first("twitch", k.name)
            log(when {
                old == Tw.NONE -> "AV start src=twitch state=$k"
                k == Tw.NONE -> "AV end src=twitch was=$old"
                else -> "AV twitch state $old -> $k"
            })
        }
        recompute("twitch $k")
        if (k != Tw.NONE) ensurePoll()
    }

    // ---- test commands (DevBridge DEV_ADVOL) ----

    /**
     * op=start: an ad window on the main player (the one on screen) for s seconds (default 30).
     *   With via=cue it goes through the DATERANGE parser instead (watchdog s + 20 s).
     * op=end: closes every window, or with via=cue sends the stream-source live cue to the main player.
     * op=state kind=client|audio|surestream|none: feeds Twitch's ad state as its presenter would.
     * op=status: logs the state. Reads Java fields only, never an IVS native method.
     */
    fun dev(i: Intent) {
        if (!installed) { log("AV dev: ad volume is not installed"); return }
        val op = i.getStringExtra("op").orEmpty()
        val secs = i.getIntExtra("s", 30).coerceIn(1, 600)
        val cue = i.getStringExtra("via") == "cue"
        val kind = i.getStringExtra("kind").orEmpty()
        bg.post {
            safely("dev $op") {
                when (op) {
                    "start" -> devStart(secs, cue)
                    "end" -> devEnd(cue)
                    "state" -> twitchState(when (kind) {
                        "client" -> Tw.VIDEO_CLIENT
                        "audio" -> Tw.AUDIO
                        "surestream" -> Tw.VIDEO_SS
                        else -> Tw.NONE
                    })
                    "status" -> status()
                    else -> log("AV dev: unknown op '$op' (start, end, state or status)")
                }
            }
        }
    }

    private fun mainPlayer(): Any? = PlayerStats.shownPlayer() ?: RefreshButton.currentPlayer()

    private fun devStart(secs: Int, cue: Boolean) {
        val p = mainPlayer() ?: run { log("AV dev: no player yet, open a live stream first"); return }
        if (cue) {
            val json = "{\"CLASS\":\"twitch-stitched-ad\",\"ID\":\"ptv-dev-${SystemClock.uptimeMillis()}\"," +
                "\"DURATION\":\"$secs\",\"X-TV-TWITCH-AD-POD-LENGTH\":\"1\",\"X-TV-TWITCH-AD-POD-POSITION\":\"0\"}"
            onMetadata(p, "text/json", ByteBuffer.wrap(json.toByteArray(Charsets.US_ASCII)))
        } else ivsStart(p, "dev", secs * 1000L, 1, null, grace = 0L)
    }

    private fun devEnd(cue: Boolean) {
        if (cue) {
            val p = mainPlayer() ?: run { log("AV dev: no player yet"); return }
            val json = "{\"CLASS\":\"twitch-stream-source\",\"X-TV-TWITCH-STREAM-SOURCE\":\"live\"}"
            onMetadata(p, "text/json", ByteBuffer.wrap(json.toByteArray(Charsets.US_ASCII)))
            return
        }
        val now = SystemClock.uptimeMillis()
        val ends = ArrayList<String>()
        synchronized(L) {
            for ((p, i) in players) if (i.ad) ends.add("AV end src=dev player=${tag(p)} " + endLocked(i, "dev", now))
            if (tw != Tw.NONE) { ends.add("AV end src=dev twitch=$tw"); tw = Tw.NONE }
        }
        if (ends.isEmpty()) log("AV dev: no ad window was open")
        for (e in ends) log(e)
        recompute("dev end")
    }

    private fun status() {
        val now = SystemClock.uptimeMillis()
        val setting = Settings.getInt(KEY, DEF)
        val pl = ArrayList<Pair<Any, String>>()
        val tl = ArrayList<String>()
        val head = synchronized(L) {
            for ((p, i) in players) pl.add(p to "kind=${i.kind} ad=${i.ad} from=${i.from}" +
                (if (i.ad) " left=${(i.deadline - now) / 1000}s ticks=${i.ticks}" else "") + " where=${i.where}")
            for (i in tracks.values.take(24)) tl.add("AV   track session=${i.session} exo=${i.exo} seen=${i.seen} " +
                "raw=${fmt(i.rawL)}/${fmt(i.rawR)} applied=${fmt(i.applied)} scaled=${i.scaled}")
            "AV status setting=$setting% f=$fNow mode=$mode restore=${if (mUpdate != null) "update" else "setStereo"} twitch=$tw scaled=${adSessions.contentToString()} " +
                "filters=$afSessions excluded=$excluded players=${players.size} tracks=${tracks.size}"
        }
        log(head)
        for ((p, d) in pl) log("AV   player ${tag(p)} session=${sessionOf(p)} $d")
        for (l in tl) log(l)
    }

    // ---- small helpers ----

    private fun call(o: Any?, name: String): Any? =
        if (o == null) null else runCatching { o.javaClass.getMethod(name).invoke(o) }.getOrNull()

    private fun tag(o: Any) = Integer.toHexString(System.identityHashCode(o))

    private fun where(u: Uri?) = u?.let { (it.host.orEmpty() + it.path.orEmpty()).take(80) }.orEmpty()

    private fun fmt(x: Float) = String.format(Locale.US, "%.3f", x)

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { logLimited("AV $what failed: $t") }
    }

    private val fired = HashSet<String>()

    /** The first time each detection layer fires, so a test run shows which ones Twitch uses. */
    private fun first(layer: String, detail: String) {
        val isNew = synchronized(fired) { fired.add(layer) }
        if (isNew) log("AV first $layer" + if (detail.isNotEmpty()) ": $detail" else "")
    }

    private val logLock = Any()
    private var logWindow = 0L
    private var logCount = 0

    /** Apply and restore lines can come in bursts: at most 40 a minute. */
    private fun logLimited(msg: String) {
        val now = SystemClock.uptimeMillis()
        val ok = synchronized(logLock) {
            if (now - logWindow > 60_000) { logWindow = now; logCount = 0 }
            logCount++ < 40
        }
        if (ok) log(msg)
    }
}
