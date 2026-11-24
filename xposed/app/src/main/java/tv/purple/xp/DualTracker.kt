package tv.purple.xp

import android.net.Uri
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Builds a two-streams player through Twitch's own player factory, so its playback tracker runs
 * for that channel exactly as it does in the normal stream screen. No report is written here:
 * Twitch's tracker listens to the player and sends its own.
 *
 * Reached by reflection, as the names change with every Twitch release. When a name is missing the
 * pane still plays, on a plain player without the tracker.
 */
internal object DualTracker {

    private const val TAG = "TwoStreams"
    private const val DEV_LOG = "dev_view_log"

    class Session(val tracked: Any, val player: Any)

    private class Names(
        val factory: String, val tracked: String, val wrapper: String, val kind: String, val format: String,
        val source: String, val playable: String, val uriSource: String, val live: String, val manifest: String,
        val props: String, val parser: String, val parse: String, val reason: String, val analytics: String,
        val create: String, val destroy: String, val load: String, val start: String, val pause: String,
        val resume: String, val stop: String, val wrapperField: String, val playerField: String,
        val lifecycleField: String, val lifecycleRemove: String, val listener: String, val send: String,
        val attach: String,
    )

    private val V317 = Names(
        factory = "d2u", tracked = "bm50", wrapper = "aoa", kind = "vp80", format = "pv60",
        source = "rqs", playable = "u5u", uriSource = "s2u", live = "p5u", manifest = "rso",
        props = "vgp", parser = "hat", parse = "m", reason = "os40", analytics = "jn0",
        create = "d", destroy = "f", load = "e", start = "j", pause = "f",
        resume = "g", stop = "k", wrapperField = "e", playerField = "t",
        lifecycleField = "b", lifecycleRemove = "b", listener = "j310", send = "e",
        attach = "attachSurface",
    )

    private val V31 = Names(
        factory = "rnt", tracked = "tt40", wrapper = "tja", kind = "xv70", format = "t160",
        source = "scs", playable = "lrt", uriSource = "hot", live = "grt", manifest = "mfo",
        props = "v3p", parser = "eyr", parse = "m", reason = "m240", analytics = "jm0",
        create = "d", destroy = "f", load = "e", start = "k", pause = "f",
        resume = "g", stop = "l", wrapperField = "e", playerField = "t",
        lifecycleField = "b", lifecycleRemove = "b", listener = "rh00", send = "e",
        attach = "v0",
    )

    private fun names(): Names = if (TwitchVersion.atLeast317()) V317 else V31

    private class Api(val cl: ClassLoader, n: Names) {
        private val factoryCls = cl.loadClass(n.factory)
        private val trackedCls = cl.loadClass(n.tracked)
        private val kindCls = cl.loadClass(n.kind)
        private val formatCls = cl.loadClass(n.format)
        private val sourceCls = cl.loadClass(n.source)
        private val playableCls = cl.loadClass(n.playable)
        private val propsCls = cl.loadClass(n.props)
        private val reasonCls = cl.loadClass(n.reason)
        private val channelCls = cl.loadClass("tv.twitch.android.models.channel.ChannelModel")
        private val streamCls = cl.loadClass("tv.twitch.android.models.streams.StreamModel")
        private val tokenCls = cl.loadClass("tv.twitch.android.models.AccessTokenResponse")
        private val manifestCls = cl.loadClass(n.manifest)

        val create: Method = factoryCls.getMethod(n.create, kindCls, String::class.java)
        val destroy: Method = factoryCls.getMethod(n.destroy, trackedCls, String::class.java)
        val load: Method = trackedCls.getMethod(n.load, sourceCls, playableCls)
        val start: Method = trackedCls.getMethod(n.start)
        val pause: Method = trackedCls.getMethod(n.pause)
        val resume: Method = trackedCls.getMethod(n.resume)
        val stop: Method = trackedCls.getMethod(n.stop)
        val wrapper: Field = trackedCls.getField(n.wrapperField)
        val player: Field = cl.loadClass(n.wrapper).getField(n.playerField)
        val attach: Method = cl.loadClass(n.wrapper).getMethod(n.attach, Surface::class.java)
        val lifecycle: Field = trackedCls.getField(n.lifecycleField)
        val lifecycleRemove: Method = lifecycle.type.getMethod(n.lifecycleRemove, cl.loadClass(n.listener))
        private val parse: Method = cl.loadClass(n.parser).getDeclaredMethod(n.parse, String::class.java).apply { isAccessible = true }
        private val uriSource = cl.loadClass(n.uriSource).getConstructor(Uri::class.java, formatCls)
        private val live = cl.loadClass(n.live).getConstructor(channelCls, streamCls, manifestCls, String::class.java)
        private val manifest = manifestCls.getConstructor(String::class.java, propsCls, tokenCls, String::class.java)
        private val token = tokenCls.getConstructor(String::class.java, String::class.java, reasonCls, Long::class.javaPrimitiveType)
        private val stream = streamCls.getConstructor(Long::class.javaPrimitiveType, Double::class.javaPrimitiveType, String::class.java, channelCls)

        val normal: Any = kindCls.enumConstants!!.first { (it as Enum<*>).name == "NORMAL" }
        private val hls: Any = formatCls.enumConstants!!.first { (it as Enum<*>).name == "HLS" }

        fun source(url: String): Any = uriSource.newInstance(Uri.parse(url), hls)

        fun playable(url: String, a: DualUsher.Access, playlist: String): Any {
            val id = a.id ?: error("no channel id")
            val ch = channelCls.getConstructor().newInstance()
            // The channel id is Twitch's own Tuid type from 31.7.0 on, and a plain number before it.
            if (TwitchVersion.atLeast317()) set(channelCls, ch, "tuid", TwitchVersion.channelIdArg(cl, id) ?: error("bad channel id"))
            else set(channelCls, ch, "id", id.toInt())
            set(channelCls, ch, "name", a.login)
            set(channelCls, ch, "displayName", a.displayName ?: a.login)
            set(channelCls, ch, "game", a.game)
            val st = stream.newInstance(a.streamId?.toLongOrNull() ?: 0L, 0.0, null, ch)
            set(streamCls, st, "game", a.game)
            set(streamCls, st, "gameDisplayName", a.game)
            set(streamCls, st, "title", a.title)
            set(streamCls, st, "viewerCount", a.viewers ?: 0)
            set(streamCls, st, "streamTypeAsString", "live")
            val pass = token.newInstance(a.value, a.signature, null, -1L)
            val m = manifest.newInstance(url, parse.invoke(null, playlist), pass, playlist)
            return live.newInstance(ch, st, m, normal.toString())
        }

        private fun set(c: Class<*>, o: Any, name: String, v: Any?) {
            c.getDeclaredField(name).apply { isAccessible = true }.set(o, v)
        }
    }

    @Volatile private var factory: Any? = null
    private var api: Api? = null

    fun install(lp: LoadPackageParam) {
        val n = names()
        val c = lp.classLoader.loadClass(n.factory)
        XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { factory = p.thisObject }
        })
        runCatching { watchReports(lp.classLoader, n) }.onFailure { log("DT report log failed: $it") }
        api = runCatching { Api(lp.classLoader, n) }.onFailure { log("DT names failed: $it") }.getOrNull()
        log("DT installed, tracker ${if (api != null) "ready" else "missing"}")
    }

    /** For testing only, behind a dev setting: which channels Twitch's tracker reports. */
    private fun watchReports(cl: ClassLoader, n: Names) {
        val m = cl.loadClass(n.analytics).getMethod(n.send, String::class.java, Map::class.java)
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (p.args[0] != "minute-watched" || !Settings.get(DEV_LOG, false)) return
                val e = p.args[1] as? Map<*, *> ?: return
                log("DT minute-watched channel=${e["channel"]} id=${e["channel_id"]} muted=${e["muted"]} " +
                    "live=${e["live"]} player=${e["player"]} minutes=${e["minutes_logged"]}")
            }
        })
    }

    /** A new tracked player, or null to fall back on a plain one. Main thread. */
    fun create(): Session? {
        val a = api ?: return null
        val f = factory ?: run { log("DT Twitch's player factory is not up yet"); return null }
        return runCatching {
            val t = a.create.invoke(f, a.normal, TAG)!!
            // Twitch pauses players on this list when it leaves the stream screen; panes have their own rules.
            a.lifecycleRemove.invoke(a.lifecycle.get(t), t)
            val player = a.player.get(a.wrapper.get(t))!!
            Session(t, player)
        }.onFailure { log("DT create failed: ${it.cause ?: it}") }.getOrNull()
    }

    /** Load the stream with its channel details, so reports carry that channel. */
    fun load(s: Session, url: String, access: DualUsher.Access, playlist: String): Boolean {
        val a = api ?: return false
        return runCatching { a.load.invoke(s.tracked, a.source(url), a.playable(url, access, playlist)); true }
            .onFailure { log("DT load failed: ${(it.cause ?: it).javaClass.simpleName}") }
            .getOrDefault(false)
    }

    /** Twitch's wrapper only starts once it has been handed a surface itself. */
    fun attach(s: Session, surface: Surface) {
        val a = api ?: return
        runCatching { a.attach.invoke(a.wrapper.get(s.tracked), surface) }.onFailure { log("DT attach failed: ${it.cause ?: it}") }
    }

    fun start(s: Session) = run(s, "start") { it.start }
    fun pause(s: Session) = run(s, "pause") { it.pause }
    fun resume(s: Session) = run(s, "resume") { it.resume }

    private inline fun run(s: Session, what: String, m: (Api) -> Method) {
        val a = api ?: return
        runCatching { m(a).invoke(s.tracked) }.onFailure { log("DT $what failed: ${it.cause ?: it}") }
    }

    /** Ends the tracked session, then frees the player the way Twitch does. Main thread. */
    fun release(s: Session) {
        val a = api ?: return
        runCatching { a.stop.invoke(s.tracked) }.onFailure { log("DT stop failed: ${it.cause ?: it}") }
        val f = factory ?: return
        runCatching { a.destroy.invoke(f, s.tracked, TAG) }.onFailure { log("DT release failed: ${it.cause ?: it}") }
    }
}
