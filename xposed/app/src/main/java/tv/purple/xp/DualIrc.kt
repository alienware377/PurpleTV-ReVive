package tv.purple.xp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * One Twitch IRC connection (wss://irc-ws.chat.twitch.tv) shared by both chat panes.
 *
 * Twitch's own chat stack follows the one channel on screen, and several features hook it to
 * learn which channel that is (emotes, channel points, raid cards). Reading a second chat through
 * it would drag all of them along, so the panes get a plain IRC socket of their own instead.
 *
 * Signing in copies the app exactly: the same capabilities, then PASS oauth:<token> and NICK
 * <login> from the app's own stored sign-in. Logged out, or when Twitch refuses the token, chat
 * is read anonymously (as justinfanNNNNN) and [send] says no.
 *
 * Threading: connection bookkeeping runs on one private thread. Lines are parsed on OkHttp's
 * reader thread and reach the main thread in one post per frame, grouped by channel. Listeners
 * are only ever called on the main thread, and only with their own channel's lines.
 */
object DualIrc {

    /** Called on the main thread. [onConnected] true once the channel is joined, false when the
     *  connection drops; [canSend] may have changed at either point. */
    interface Listener { fun onMessage(m: IrcMsg); fun onConnected(on: Boolean) }

    private const val URL = "wss://irc-ws.chat.twitch.tv"
    private const val CAPS = "CAP REQ :twitch.tv/tags twitch.tv/commands"
    /** The password Twitch's own web chat uses for anonymous readers; any value is accepted. */
    private const val ANON_PASS = "SCHMOOPIIE"

    /** Waits before reconnecting, in seconds, after each failure in a row. */
    private val BACKOFF_S = longArrayOf(1, 2, 4, 8, 16, 30)
    /** Our own PING interval, and how long an answer (or any line at all) may take after it.
     *  Mobile networks drop sockets without a word; this is how a dead one is noticed. */
    private const val PING_EVERY_S = 60L
    private const val PONG_WAIT_S = 20L
    /** A socket that opened but never got the server's welcome is treated as dead too. */
    private const val WELCOME_WAIT_S = 20L
    /** A connection that stayed up this long starts the backoff from the beginning again. */
    private const val STABLE_MS = 60_000L

    /** Twitch allows 20 messages per 30 s to a normal account and locks IRC senders out for a
     *  while when they go over, so stay one under it. Nobody types that fast by hand. */
    private const val SEND_WINDOW_MS = 30_000L
    private const val SEND_MAX = 19

    /** Same test switch as the chat Send button: device tests never post to a real chat. */
    private const val KEY_DRY_RUN = "dev_send_dry_run"

    private val main = Handler(Looper.getMainLooper())
    private val loop = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ptv-irc").apply { isDaemon = true }
    }

    // --- main thread only ---
    private val listeners = HashMap<String, MutableList<Listener>>()

    // --- read from any thread ---
    /** Channels whose join the server confirmed on the current socket. Written on main. */
    private val live: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    /** Last USERSTATE per channel: own colour, badges and display name there. */
    private val states = ConcurrentHashMap<String, IrcMsg>()
    @Volatile private var ws: WebSocket? = null
    /** Bumped per socket (written on the loop thread only), so a late callback from a socket
     *  already given up on changes nothing. */
    @Volatile private var gen = 0
    @Volatile private var signedIn = false
    @Volatile private var self: String? = null
    @Volatile private var lastData = 0L
    private val sentAt = java.util.ArrayDeque<Long>()

    // --- loop thread only ---
    private var appCtx: Context? = null
    /** Channels some pane wants, in the order they were asked for. */
    private val wanted = LinkedHashSet<String>()
    /** Channels a JOIN was sent for on the current socket. */
    private val joined = HashSet<String>()
    private var welcomed = false
    private var anonymous = true
    /** Twitch refused the token: read anonymously until the chats are closed and opened again. */
    private var authRefused = false
    private var openedAt = 0L
    private var failures = 0
    private var retry: ScheduledFuture<*>? = null
    private var pinger: ScheduledFuture<*>? = null

    /** Start receiving [channel]'s lines in [l]. Opens the connection if this is the first. */
    fun join(ctx: Context, channel: String, l: Listener) {
        val ch = IrcParse.cleanChannel(channel) ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { join(ctx, ch, l) }; return }
        val list = listeners.getOrPut(ch) { ArrayList(2) }
        if (l !in list) list.add(l)
        // Already joined for another pane: say so, but not from inside the caller's own call.
        if (ch in live) main.post { if (ch in live && listeners[ch]?.contains(l) == true) safe { l.onConnected(true) } }
        val app = ctx.applicationContext ?: ctx
        loop.execute {
            appCtx = app
            if (wanted.add(ch)) log("IRC joining #$ch")
            reconcile()
        }
    }

    /** Stop sending [channel]'s lines to [l]. The socket closes when no channel is left. */
    fun leave(channel: String, l: Listener) {
        val ch = IrcParse.cleanChannel(channel) ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { leave(ch, l) }; return }
        val list = listeners[ch] ?: return
        list.remove(l)
        if (list.isNotEmpty()) return
        listeners.remove(ch)
        live.remove(ch)
        states.remove(ch)
        loop.execute { part(ch) }
    }

    /**
     * Send [text] to [channel] as the signed-in account. "/me ..." is sent as an action. False
     * when reading anonymously, not joined yet, or over Twitch's rate limit. Twitch does not echo
     * our own lines back, so the caller shows a sent line itself.
     */
    fun send(channel: String, text: String): Boolean {
        val ch = IrcParse.cleanChannel(channel) ?: return false
        val line = IrcParse.privmsg(ch, text) ?: return false
        if (!canSend() || ch !in live) return false
        if (Settings.get(KEY_DRY_RUN, false)) { log("IRC send to #$ch blocked (dry run, nothing sent)"); return true }
        if (!allowSend()) { log("IRC send to #$ch held back: rate limit"); return false }
        val s = ws ?: return false
        return s.send(line)
    }

    /** True while signed in with the account's own token and connected. */
    fun canSend(): Boolean = signedIn && ws != null

    /** The login this connection is signed in as, or null when reading anonymously. */
    fun selfLogin(): String? = self

    /** The last USERSTATE Twitch sent for [channel]: own colour, badges and display name there. */
    fun userState(channel: String): IrcMsg? = IrcParse.cleanChannel(channel)?.let { states[it] }

    // ---- connection lifecycle (loop thread) ----

    private fun reconcile() {
        if (wanted.isEmpty()) { close("no channel left"); return }
        val s = ws
        if (s == null) { if (retry == null) connect(); return }
        if (!welcomed) return  // joined in welcome()
        for (ch in wanted) if (joined.add(ch)) s.send("JOIN #$ch")
    }

    private fun part(ch: String) {
        if (!wanted.remove(ch)) return
        if (joined.remove(ch)) ws?.send("PART #$ch")
        log("IRC left #$ch")
        if (wanted.isEmpty()) close("no channel left")
    }

    private fun connect() {
        retry = null
        val my = ++gen
        welcomed = false; signedIn = false; joined.clear()
        openedAt = SystemClock.uptimeMillis(); lastData = openedAt
        log("IRC connecting")
        ws = runCatching { EmoteRepo.webSocket(URL, Conn(my)) }
            .onFailure { log("IRC connect failed: $it") }.getOrNull()
        if (ws == null) { dropped(my, "no socket"); return }
        loop.schedule({ runCatching { if (my == gen && !welcomed) stall(my, "no welcome from the server") } },
            WELCOME_WAIT_S, TimeUnit.SECONDS)
        if (pinger == null)
            pinger = loop.scheduleWithFixedDelay({ runCatching { ping() } }, PING_EVERY_S, PING_EVERY_S, TimeUnit.SECONDS)
    }

    /** The socket is open: sign in. The token is read here, fresh, and never kept. */
    private fun login(my: Int, s: WebSocket) {
        if (my != gen) return
        val ctx = appCtx
        val token = if (authRefused || ctx == null) "" else runCatching { EmoteRepo.authToken(ctx) }.getOrDefault("")
        val login = if (token.isBlank() || ctx == null) null
            else IrcParse.cleanChannel(runCatching { EmoteRepo.ownLogin(ctx) }.getOrDefault(""))
        anonymous = token.isBlank() || login == null
        s.send(CAPS)
        if (anonymous) {
            s.send("PASS $ANON_PASS")
            s.send("NICK justinfan${Random.nextInt(10000, 100000)}")
        } else {
            s.send("PASS oauth:$token")
            s.send("NICK $login")
        }
    }

    private fun welcome(my: Int, nick: String?) {
        if (my != gen || welcomed) return
        welcomed = true
        self = if (anonymous) null else IrcParse.cleanChannel(nick)
        signedIn = !anonymous
        log(if (anonymous) "IRC connected, reading anonymously" else "IRC connected, signed in")
        reconcile()
    }

    /** Twitch said no to the token (expired, revoked): carry on reading without it. */
    private fun refused(my: Int) {
        if (my != gen || anonymous) return
        authRefused = true
        log("IRC sign-in refused; reading chat anonymously")
        failures = 0
        runCatching { ws?.cancel() }
        dropped(my, "sign-in refused")
    }

    /** RECONNECT: the server is about to restart and asks clients to come back right away. */
    private fun serverReconnect(my: Int) {
        if (my != gen) return
        failures = 0
        runCatching { ws?.close(1000, null) }
        dropped(my, "server asked to reconnect")
    }

    /** Socket [my] looks dead without having said so: drop it and start again. */
    private fun stall(my: Int, why: String) {
        if (my != gen) return
        runCatching { ws?.cancel() }
        dropped(my, why)
    }

    private fun ping() {
        val s = ws ?: return
        if (!welcomed) return
        val my = gen
        val at = SystemClock.uptimeMillis()
        if (!s.send("PING :tmi.twitch.tv")) { stall(my, "socket refused a PING"); return }
        loop.schedule({ runCatching { if (my == gen && lastData < at) stall(my, "no answer to PING") } },
            PONG_WAIT_S, TimeUnit.SECONDS)
    }

    /** Socket [my] is gone: tell the panes, then try again after the backoff. */
    private fun dropped(my: Int, why: String) {
        if (my != gen) return
        // Retire this socket, so its own close or failure callback arriving later is ignored.
        gen++
        ws = null; welcomed = false; signedIn = false; joined.clear()
        main.post { down() }
        if (SystemClock.uptimeMillis() - openedAt > STABLE_MS) failures = 0
        if (wanted.isEmpty()) { close("no channel left"); return }
        val wait = BACKOFF_S[failures.coerceAtMost(BACKOFF_S.size - 1)]
        failures++
        // A little jitter, so phones that lost the same server do not all come back at once.
        val ms = wait * 1000L + (Math.random() * 500).toLong()
        log("IRC disconnected ($why); retrying in ${(ms + 500) / 1000}s")
        retry?.cancel(false)
        retry = loop.schedule({ retry = null; runCatching { reconcile() } }, ms, TimeUnit.MILLISECONDS)
    }

    private fun close(why: String) {
        retry?.cancel(false); retry = null
        pinger?.cancel(false); pinger = null
        // The next time chats open, the token gets another chance.
        authRefused = false
        failures = 0
        val s = ws ?: return
        gen++
        ws = null; welcomed = false; signedIn = false; joined.clear()
        runCatching { s.close(1000, null) }
        main.post { down() }
        log("IRC closed: $why")
    }

    private class Conn(private val my: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            loop.execute { login(my, webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            read(my, webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            loop.execute { dropped(my, "closed $code") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            loop.execute { dropped(my, t.javaClass.simpleName + ": " + t.message) }
        }
    }

    // ---- reading (OkHttp's reader thread) ----

    private fun read(my: Int, s: WebSocket, frame: String) {
        if (my == gen) lastData = SystemClock.uptimeMillis()
        var batch: HashMap<String, ArrayList<IrcMsg>>? = null
        for (line in IrcParse.lines(frame)) {
            val m = IrcParse.parse(line) ?: continue
            if (!control(my, s, m)) continue
            val ch = m.channel ?: continue
            val b = batch ?: HashMap<String, ArrayList<IrcMsg>>(4).also { batch = it }
            b.getOrPut(ch) { ArrayList() }.add(m)
        }
        val b = batch ?: return
        main.post { deliver(my, b) }
    }

    /** Handles what the connection itself needs. True when the line is for the chat panes. */
    private fun control(my: Int, s: WebSocket, m: IrcMsg): Boolean {
        when (m.command) {
            "PING" -> { s.send("PONG :" + (m.params.lastOrNull() ?: "tmi.twitch.tv")); return false }
            "PONG", "CAP", "GLOBALUSERSTATE" -> return false
            "001" -> { val nick = m.params.firstOrNull(); loop.execute { welcome(my, nick) }; return false }
            "RECONNECT" -> { loop.execute { serverReconnect(my) }; return false }
            "USERSTATE" -> { val ch = m.channel; if (ch != null && my == gen) states[ch] = m }
            "NOTICE" -> if (m.channel == null) {
                if (isRefusal(m.text)) loop.execute { refused(my) }
                return false
            }
        }
        // Other numeric replies (the name list, the message of the day) only matter to the connection.
        return !(m.command.length == 3 && m.command.all { it in '0'..'9' })
    }

    private fun isRefusal(text: String?): Boolean {
        val t = text ?: return false
        return t.contains("authentication failed", ignoreCase = true) ||
            t.contains("Login unsuccessful", ignoreCase = true) ||
            t.contains("Improperly formatted auth", ignoreCase = true)
    }

    // ---- delivery (main thread) ----

    private fun deliver(my: Int, batch: Map<String, List<IrcMsg>>) {
        for ((ch, lines) in batch) {
            val ls = listeners[ch]?.toTypedArray() ?: continue
            // The server confirms a join with our own JOIN echo, then the channel's ROOMSTATE.
            if (my == gen && ch !in live && lines.any { it.command == "ROOMSTATE" || it.command == "JOIN" }) {
                live.add(ch)
                for (l in ls) if (stillOn(ch, l)) safe { l.onConnected(true) }
            }
            for (m in lines) for (l in ls) if (stillOn(ch, l)) safe { l.onMessage(m) }
        }
    }

    private fun down() {
        if (live.isEmpty()) return
        val was = live.toList()
        live.clear()
        for (ch in was) listeners[ch]?.toTypedArray()?.forEach { l -> safe { l.onConnected(false) } }
    }

    /** A listener may leave from inside a callback; the rest of that batch must skip it. */
    private fun stillOn(ch: String, l: Listener): Boolean = listeners[ch]?.contains(l) == true

    private fun allowSend(): Boolean = synchronized(sentAt) {
        val now = SystemClock.uptimeMillis()
        while (sentAt.isNotEmpty() && now - sentAt.first() >= SEND_WINDOW_MS) sentAt.pollFirst()
        if (sentAt.size >= SEND_MAX) false else { sentAt.addLast(now); true }
    }

    /** A pane that throws must not take the app down with it, nor stop the other pane's lines. */
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { log("IRC listener failed: $t") }
    }
}
