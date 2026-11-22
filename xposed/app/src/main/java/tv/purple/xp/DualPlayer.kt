package tv.purple.xp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.lang.reflect.Method
import java.security.SecureRandom

/**
 * One live channel played by an IVS player of our own into a TextureView, letterboxed to the pane.
 *
 * Twitch's own player is left alone (the two-streams screen only pauses it), so this builds a
 * second one the way Twitch builds its own: MediaPlayer.Builder with a session config, loading the
 * channel's usher address. The IVS classes come from Twitch's class loader and are reached through
 * the public Player interface only, as its implementation class is not public.
 *
 * A released IVS player must never be called again: its native side is freed, and a crash there can
 * not be caught. So every call goes through [call], which checks the player's own flag first. The flag
 * is set on the main thread before the last two calls (detach the surface, then release, which runs
 * on a worker thread like Twitch's own release does).
 *
 * Everything here runs on the main thread except the network request for the stream pass.
 */
class DualPlayer(ctx: Context, val login: String) : FrameLayout(ctx) {

    enum class State { LOADING, PLAYING, BUFFERING, OFFLINE, ERROR }

    data class Info(
        val login: String, val id: String?, val displayName: String?, val title: String?, val viewers: Int?,
        val game: String?, val state: State, val message: String?,
    )

    /** Called on the main thread whenever [info] changes. */
    var onInfo: ((Info) -> Unit)? = null

    val info: Info get() = last

    private val main = Handler(Looper.getMainLooper())
    private val video = TextureView(ctx)
    private val status = TextView(ctx)
    private val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall)

    /** One IVS player. [dead] is set before its release and checked before every call. */
    private class Core(val obj: Any) { var dead = false }

    private enum class Mode { START, META, RECOVER }

    private var core: Core? = null
    private var ivs: Ivs? = null
    private var surface: Surface? = null
    @Volatile private var released = false
    private var started = false
    private var paused = false
    private var pausedAt = 0L
    private var audible = false
    private var capW = 0
    private var capH = 0
    private var vidW = 0
    private var vidH = 0
    private var gen = 0
    private var fetching = false
    private var lastFetch = 0L
    private var played = false
    private var lastPlayCall = 0L
    private var lastAutoRetry = 0L

    private var id: String? = null
    private var displayName: String? = null
    private var title: String? = null
    private var viewers: Int? = null
    private var game: String? = null
    private var state = State.LOADING
    private var message: String? = null
    private var last = Info(login, null, null, null, null, null, State.LOADING, null)

    private val tick = Runnable { onTick() }

    private val events = object : OwnPlayers.Events {
        override fun state(code: Int) = onState(code)
        override fun error(source: String, type: Int, code: Int, message: String) = onError(source, type, code, message)
        override fun size(w: Int, h: Int) = onSize(w, h)
    }

    init {
        setBackgroundColor(Color.BLACK)
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (released) return
                surface?.release()
                val s = Surface(st)
                surface = s
                core?.let { call(it, ivs?.setSurface, s) }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // The player lets go first, then the surface it was drawing into.
                core?.let { call(it, ivs?.setSurface, null) }
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(0xDDFFFFFF.toInt())
        addView(spinner, LayoutParams(dp(28f), dp(28f), Gravity.CENTER))
        status.apply {
            tag = "ptv_dual_status"
            setTextColor(0xFFEFEFF1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(0xB3000000.toInt()) }
            setOnClickListener { if (state == State.ERROR || state == State.OFFLINE) retry() }
        }
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(12f); rightMargin = dp(12f)
        })
        render()
    }

    /** Fetch a stream pass and start playing. Once; [retry] starts over. */
    fun start() {
        if (released || started) return
        started = true
        if (DualUsher.cleanLogin(login) == null) { fail(I18n.t("Channel not found")); return }
        setState(State.LOADING, null)
        fetch(Mode.START)
    }

    /** Only one stream is heard at a time; the other is muted, not paused. */
    fun setAudible(on: Boolean) {
        audible = on
        core?.let { call(it, ivs?.setMuted, !on) }
    }

    /**
     * Cap the automatic quality to what the pane can show, rounded up to 480p, 720p or 1080p
     * (larger panes are not capped). [w] and [h] are the pane's size in pixels. Two streams at
     * source quality in panes half the screen wide only cost data and battery.
     */
    fun setCap(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // The height a 16:9 picture really gets inside the pane.
        val shown = minOf(h, (w * 9 + 15) / 16)
        val (cw, ch) = when {
            shown <= 480 -> 854 to 480
            shown <= 720 -> 1280 to 720
            shown <= 1080 -> 1920 to 1080
            else -> 3840 to 2160
        }
        if (cw == capW && ch == capH) return
        capW = cw
        capH = ch
        core?.let { call(it, ivs?.maxSize, cw, ch) }
    }

    fun pause() {
        if (released || paused) return
        paused = true
        pausedAt = SystemClock.uptimeMillis()
        core?.let { call(it, ivs?.pause) }
        main.removeCallbacks(tick)
    }

    fun resume() {
        if (released || !paused) return
        paused = false
        val now = SystemClock.uptimeMillis()
        val c = core
        when {
            // A live stream paused for long is far behind; a fresh load starts at live again.
            c != null && now - pausedAt > LONG_PAUSE_MS -> { retry(); return }
            c != null -> { call(c, ivs?.play); lastPlayCall = now }
            state == State.OFFLINE && !fetching && now - lastFetch >= OFFLINE_CHECK_MS -> { fetch(Mode.START); return }
        }
        schedule()
    }

    /** Start over with a fresh stream pass: passes are short-lived, so an old one may be refused. */
    fun retry() {
        if (released) return
        started = true
        dropCore()
        played = false
        setState(State.LOADING, null)
        fetch(Mode.START)
    }

    /** Stop for good. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        gen++
        main.removeCallbacks(tick)
        dropCore()
        surface?.release()
        surface = null
        status.setOnClickListener(null)
        log("DP $login closed")
    }

    // --- stream pass ---

    private fun fetch(mode: Mode, retryOk: Boolean = false) {
        if (released) return
        val my = ++gen
        fetching = true
        lastFetch = SystemClock.uptimeMillis()
        val app = context.applicationContext ?: context
        val who = login
        Thread({
            val raw = runCatching { EmoteRepo.gqlPost(EmoteRepo.authToken(app), DualUsher.tokenBody(who)) }.getOrNull()
            val a = raw?.let { DualUsher.parseAccess(it, who) }
            val missing = a == null && raw != null && DualUsher.userMissing(raw)
            main.post { onAccess(my, mode, retryOk, raw != null, a, missing) }
        }, "ptv-dual-token").apply { isDaemon = true }.start()
    }

    private fun onAccess(my: Int, mode: Mode, retryOk: Boolean, reached: Boolean, a: DualUsher.Access?, missing: Boolean = false) {
        if (released || my != gen) return
        fetching = false
        if (a != null) {
            id = a.id ?: id
            displayName = a.displayName ?: displayName
            // A refresh that briefly says "offline" while the video still plays keeps the old details.
            if (a.live || mode != Mode.META) { title = a.title; viewers = a.viewers; game = a.game }
        }
        if (mode == Mode.META) { render(); emit(); schedule(); return }
        when {
            // A failed background check of an offline channel says nothing new; look again later.
            a == null && state == State.OFFLINE -> schedule()
            a == null && missing -> fail(I18n.t("Channel not found"))
            a == null -> fail(if (reached) I18n.t("Could not open this channel") else I18n.t("Could not reach Twitch"))
            a.forbidden != null -> {
                log("DP $login refused: ${a.forbidden}")
                fail(I18n.t("This stream is not available to you"))
            }
            !a.live -> { log("DP $login offline"); setState(State.OFFLINE, null) }
            mode == Mode.RECOVER && !retryOk -> fail(I18n.t("The stream stopped with an error"))
            else -> open(a)
        }
    }

    // --- player ---

    private fun open(a: DualUsher.Access) {
        dropCore()
        val api = Ivs.of(context.classLoader) ?: run { fail(I18n.t("The video player could not start")); return }
        ivs = api
        val obj = api.create(context) ?: run { fail(I18n.t("The video player could not start")); return }
        val c = Core(obj)
        OwnPlayers.add(obj, events)
        core = c
        // A quiet reconnect of a stream that was playing keeps showing the spinner, not "Loading".
        val recovering = state == State.BUFFERING && played
        if (!recovering) played = false
        if (paused) pausedAt = SystemClock.uptimeMillis()
        setState(if (recovering) State.BUFFERING else State.LOADING, null)
        call(c, api.autoQuality, true)
        call(c, api.lowLatency, true)
        call(c, api.rebufferToLive, true)
        if (api.resumeMode != null) call(c, api.recovery, api.resumeMode)
        call(c, api.setMuted, !audible)
        if (capW > 0) call(c, api.maxSize, capW, capH)
        surface?.let { call(c, api.setSurface, it) }
        val template = runCatching { RefreshButton.lastLiveUri() }.getOrNull()
        val url = DualUsher.usherUrl(template, a.login, a, System.currentTimeMillis(), sessionHex(), appVersion(context))
        if (!call(c, api.load, Uri.parse(url), "application/x-mpegURL")) {
            dropCore()
            fail(I18n.t("The video player could not start"))
            return
        }
        if (!paused) { call(c, api.play); lastPlayCall = SystemClock.uptimeMillis() }
        val from = if (template != null) "Twitch's parameters" else "basic parameters"
        log("DP $login loading ($from)")
    }

    private fun onState(code: Int) {
        val c = core ?: return
        if (released || c.dead) return
        when (code) {
            // A paused IVS player also reports READY, so only a player we want running is pushed on.
            1 -> {
                val now = SystemClock.uptimeMillis()
                if (!paused && now - lastPlayCall > 1000) { call(c, ivs?.play); lastPlayCall = now }
            }
            2 -> if (played) setState(State.BUFFERING, null)
            3 -> { played = true; setState(State.PLAYING, null) }
            4 -> { log("DP $login ended"); dropCore(); setState(State.OFFLINE, null) }
        }
    }

    private fun onError(source: String, type: Int, code: Int, msg: String) {
        val c = core ?: return
        if (released || c.dead) return
        // The message can quote the stream address, whose pass must stay out of the log.
        log("DP $login error $type/$code from $source: ${msg.replace(QUERY, "?...")}")
        dropCore()
        val now = SystemClock.uptimeMillis()
        // One quiet retry for a stream that was already playing (a network change, an expired
        // segment). A second error within a minute is shown, so a broken stream does not loop.
        val retryOk = played && now - lastAutoRetry > 60_000L
        if (retryOk) lastAutoRetry = now
        setState(if (played) State.BUFFERING else State.LOADING, null)
        // Ask Twitch first: an error right as a stream ends should read "offline", not "error".
        fetch(Mode.RECOVER, retryOk)
    }

    private fun onSize(w: Int, h: Int) {
        if (released || w <= 0 || h <= 0 || (w == vidW && h == vidH)) return
        vidW = w
        vidH = h
        requestLayout()
    }

    /** Detach and release the current player, if any. Nothing calls it afterwards. */
    private fun dropCore() {
        val c = core ?: return
        core = null
        OwnPlayers.detach(c.obj)
        if (c.dead) return
        c.dead = true
        val api = ivs ?: return
        runCatching { api.setSurface.invoke(c.obj, *arrayOf<Any?>(null)) }.onFailure { log("DP detach failed: ${it.cause ?: it}") }
        Thread({
            runCatching { api.release.invoke(c.obj) }.onFailure { log("DP release failed: ${it.cause ?: it}") }
        }, "ptv-dual-release").start()
    }

    /** Every IVS call goes through here, so nothing reaches a player after its release. */
    private fun call(c: Core?, m: Method?, vararg args: Any?): Boolean {
        if (released || c == null || c.dead || m == null) return false
        return try {
            m.invoke(c.obj, *args)
            true
        } catch (t: Throwable) {
            log("DP ${m.name} failed: ${t.cause ?: t}")
            false
        }
    }

    private fun fail(msg: String) {
        dropCore()
        setState(State.ERROR, msg)
    }

    // --- status ---

    private fun setState(s: State, msg: String?) {
        state = s
        message = msg
        render()
        emit()
        schedule()
    }

    private fun render() {
        val name = displayName ?: login
        val text: String? = when (state) {
            State.LOADING -> I18n.t("Loading ${name}")
            State.OFFLINE -> I18n.t("${name} is offline")
            State.ERROR -> {
                val why = message ?: I18n.t("Something went wrong")
                val tap = I18n.t("Tap to try again")
                "$why\n$tap"
            }
            else -> null
        }
        if (text == null) status.visibility = View.GONE
        else {
            if (status.text?.toString() != text) status.text = text
            status.visibility = View.VISIBLE
        }
        status.isClickable = state == State.ERROR || state == State.OFFLINE
        spinner.visibility = if (state == State.BUFFERING) View.VISIBLE else View.GONE
    }

    private fun emit() {
        if (released) return
        val now = Info(login, id, displayName, title, viewers, game, state, message)
        if (now == last) return
        last = now
        onInfo?.invoke(now)
    }

    // --- checks while on screen ---

    /** Offline: look again every minute. Playing: refresh the viewer count and title now and then. */
    private fun schedule() {
        main.removeCallbacks(tick)
        if (released || paused) return
        val delay = when (state) {
            State.OFFLINE -> OFFLINE_CHECK_MS
            State.PLAYING, State.BUFFERING -> META_MS
            else -> return
        }
        main.postDelayed(tick, delay)
    }

    private fun onTick() {
        if (released || paused) return
        if (fetching || !isAttachedToWindow || windowVisibility != View.VISIBLE) { schedule(); return }
        when (state) {
            State.OFFLINE -> fetch(Mode.START)
            State.PLAYING, State.BUFFERING -> fetch(Mode.META)
            else -> {}
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && state == State.OFFLINE && !released && !paused && !fetching &&
            SystemClock.uptimeMillis() - lastFetch >= OFFLINE_CHECK_MS) main.post(tick)
    }

    // --- letterbox ---

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        if (w <= 0 || h <= 0) return
        // Until the stream reports its size, assume 16:9, which nearly every stream is.
        val vw = if (vidW > 0) vidW.toLong() else 16L
        val vh = if (vidH > 0) vidH.toLong() else 9L
        val fw: Int
        val fh: Int
        if (w * vh > h * vw) { fh = h; fw = (h * vw / vh).toInt().coerceAtLeast(1) }
        else { fw = w; fh = (w * vh / vw).toInt().coerceAtLeast(1) }
        video.measure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY))
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /**
     * The IVS methods this file uses, looked up once from Twitch's class loader. The builder path is
     * the one Twitch uses; Player.Factory (a player with default settings) is the fallback.
     */
    private class Ivs(private val cl: ClassLoader) {
        private val player: Class<*> = cl.loadClass("com.amazonaws.ivs.player.Player")
        val load: Method = player.getMethod("load", Uri::class.java, String::class.java)
        val play: Method = player.getMethod("play")
        val pause: Method = player.getMethod("pause")
        val release: Method = player.getMethod("release")
        val setSurface: Method = player.getMethod("setSurface", Surface::class.java)
        val setMuted: Method? = opt("setMuted", BOOL)
        val maxSize: Method? = opt("setAutoMaxVideoSize", INT, INT)
        val autoQuality: Method? = opt("setAutoQualityMode", BOOL)
        val lowLatency: Method? = opt("setLiveLowLatencyEnabled", BOOL)
        val rebufferToLive: Method? = opt("setRebufferToLive", BOOL)
        private val modeCls: Class<*>? = runCatching { cl.loadClass("com.amazonaws.ivs.player.Player\$NetworkRecoveryMode") }.getOrNull()
        val recovery: Method? = modeCls?.let { opt("setNetworkRecoveryMode", it) }
        val resumeMode: Any? = modeCls?.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "RESUME" }

        private fun opt(name: String, vararg types: Class<*>): Method? =
            runCatching { player.getMethod(name, *types) }.onFailure { log("DP no $name") }.getOrNull()

        fun create(ctx: Context): Any? {
            val built = runCatching {
                val b = cl.loadClass("com.amazonaws.ivs.player.MediaPlayer\$Builder")
                val inst = b.getConstructor(Context::class.java).newInstance(ctx)
                val scCls = runCatching { cl.loadClass("com.amazonaws.ivs.player.SessionConfig") }.getOrNull()
                val sc = scCls?.let { runCatching { it.getConstructor().newInstance() }.getOrNull() }
                if (scCls != null && sc != null) {
                    // Each setter on its own: Twitch 31.1.2's IVS has almost none of them. Twitch turns
                    // this one on for its live player so a stream that fell behind speeds up to catch up.
                    runCatching { scCls.getMethod("setLiveCatchUpSpeedUpEnabled", BOOL).invoke(sc, true) }
                    runCatching { b.getMethod("sessionConfig", scCls).invoke(inst, sc) }.onFailure { log("DP session config: $it") }
                }
                b.getMethod("build").invoke(inst)
            }.onFailure { log("DP builder failed: ${it.cause ?: it}") }.getOrNull()
            if (built != null) return built
            return runCatching {
                cl.loadClass("com.amazonaws.ivs.player.Player\$Factory").getMethod("create", Context::class.java).invoke(null, ctx)
            }.onFailure { log("DP factory failed: ${it.cause ?: it}") }.getOrNull()
        }

        companion object {
            @Volatile private var cached: Ivs? = null

            fun of(cl: ClassLoader): Ivs? {
                cached?.takeIf { it.cl === cl }?.let { return it }
                return runCatching { Ivs(cl) }.onFailure { log("DP IVS not found: $it") }.getOrNull()?.also { cached = it }
            }
        }
    }

    companion object {
        private const val OFFLINE_CHECK_MS = 60_000L
        private const val META_MS = 120_000L
        private const val LONG_PAUSE_MS = 60_000L
        private val BOOL = Boolean::class.javaPrimitiveType!!
        private val INT = Int::class.javaPrimitiveType!!
        private val QUERY = Regex("\\?\\S*")
        private val rnd = SecureRandom()
        @Volatile private var version: String? = null

        /** 32 hex digits, the shape of Twitch's own play_session_id. */
        private fun sessionHex(): String {
            val b = ByteArray(16)
            rnd.nextBytes(b)
            val sb = StringBuilder(32)
            for (x in b) sb.append(Character.forDigit((x.toInt() shr 4) and 15, 16)).append(Character.forDigit(x.toInt() and 15, 16))
            return sb.toString()
        }

        private fun appVersion(ctx: Context): String = version ?: (runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "").also { version = it }
    }
}
