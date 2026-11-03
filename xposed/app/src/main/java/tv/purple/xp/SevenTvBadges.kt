package tv.purple.xp

import android.os.SystemClock
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 7TV badges, the way Chatterino 7 gets them: one websocket to 7TV's EventAPI, subscribed to the
 * chat the user is in, instead of one request per chatter.
 *
 * 7TV has no list of who owns which badge. What it does have is presence: every 7TV client
 * (the browser extension, Chatterino, 7TV's own app) tells 7TV which chat its user is in, and
 * 7TV then pushes that user's badge to every socket subscribed to that chat, as a cosmetic.create
 * (what the badge looks like) and an entitlement.create (which Twitch account wears it). So a
 * chatter's badge shows once their own 7TV client has announced them, which for anyone actively
 * chatting is almost at once. Measured in a busy chat: 16 entitlements in the first minute.
 *
 * The user's own badge would never arrive that way unless they also run 7TV somewhere else, so
 * this sends one passive presence for the logged in account: 7TV answers it with that account's
 * cosmetics on our own session only. It needs no login token, just the account's 7TV id, looked up
 * once per app run.
 *
 * Threading: socket bookkeeping runs on one private thread, OkHttp reads frames on its own, and
 * the chat render path only reads two small maps. Nothing here ever waits on the network from the
 * render path or the main thread.
 */
object SevenTvBadges {

    private const val URL = "wss://events.7tv.io/v3"

    /** Close the socket after this long with no chat line drawn: the app is in the background,
     *  or the user left chat. The next line drawn opens it again. */
    private const val IDLE_MS = 5 * 60_000L

    /** Waits before reconnecting, in seconds, after each failure in a row. */
    private val BACKOFF_S = longArrayOf(2, 5, 15, 30, 60, 120, 300)

    /** Memory caps. Badge kinds number in the hundreds; users are trimmed oldest-seen first. */
    private const val MAX_BADGES = 1000
    private const val MAX_USERS = 20_000

    private class Look(val tooltip: String, val url: String)

    private fun <K, V> lru(cap: Int): MutableMap<K, V> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(64, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<K, V>?) = size > cap
        })

    /** 7TV badge id -> its picture and tooltip. */
    private val looks: MutableMap<String, Look> = lru(MAX_BADGES)
    /** Twitch user id -> 7TV badge id. Kept across channels and reconnects: a badge belongs to
     *  the account, not to the chat it was seen in. */
    private val wearer: MutableMap<String, String> = lru(MAX_USERS)

    private val loop = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ptv-7tv-ws").apply { isDaemon = true }
    }
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ptv-7tv-io").apply { isDaemon = true }
    }

    // --- owned by the loop thread ---
    private var ws: WebSocket? = null
    /** Bumped per socket, so a late callback from a socket already dropped is ignored. */
    private var gen = 0
    private var ready = false
    private var sessionId = ""
    private var subscribedTo: String? = null
    private var heartbeatMs = 45_000L
    private var openedAt = 0L
    private var failures = 0
    private var retry: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null

    @Volatile private var lastFrame = 0L
    /** The chat the user is in, from [Channels]. */
    @Volatile private var channel: String? = null
    /** Last time a chat line asked for a badge. */
    @Volatile private var lastUse = 0L
    /** Set while the socket is shut for being idle, so the next chat line reopens it once. */
    private val idleClosed = AtomicBoolean(false)

    /** Own 7TV account id: null not looked up yet, "" none. */
    @Volatile private var selfId: String? = null
    /** Which sign-in [selfId] belongs to (a hash of it, never the token itself). */
    @Volatile private var selfFor = 0

    fun enabled(): Boolean = Settings.get(BadgeRepo.KEY_7TV, true)

    /** Chat connected to [channelId]. Repeats (Twitch builds the key ten times per open) are cheap. */
    fun onChannel(channelId: String) {
        if (channelId.isBlank()) return
        lastUse = SystemClock.uptimeMillis()
        val woke = idleClosed.compareAndSet(true, false)
        if (channel == channelId && !woke) return
        channel = channelId
        loop.execute {
            // A new chat is worth one attempt now rather than the rest of a long backoff, but only
            // once: chat flipping between two ids (shared chat, a raid) must not skip the backoff.
            if (ws == null && failures <= 1) { retry?.cancel(false); retry = null }
            reconcile()
        }
    }

    /** The toggle or synced settings changed: connect or disconnect to match. */
    fun sync() { loop.execute { reconcile() } }

    /**
     * The 7TV badge [userId] wears, or null. Called for every chat line, so it only reads memory,
     * and notes the chat is in use.
     */
    fun badgeFor(userId: String): BadgeRepo.Badge? {
        lastUse = SystemClock.uptimeMillis()
        if (idleClosed.compareAndSet(true, false)) loop.execute { reconcile() }
        val id = wearer[userId] ?: return null
        val l = looks[id] ?: return null
        return BadgeRepo.Badge("7tv", l.tooltip, l.url)
    }

    // ---- socket lifecycle (loop thread) ----

    private fun reconcile() {
        val want = channel?.takeIf { enabled() }
        if (want == null) { close("7TV badges off"); return }
        if (ws == null) { if (retry == null) connect(); return }
        if (!ready) return  // subscribed in onHello
        if (subscribedTo == want) return
        subscribedTo?.let { old -> for (t in SevenTvEvents.TYPES) ws?.send(SevenTvEvents.subscription(t, old, false)) }
        for (t in SevenTvEvents.TYPES) ws?.send(SevenTvEvents.subscription(t, want))
        subscribedTo = want
        log("7TV subscribed to channel $want")
        presence(want)
    }

    private fun connect() {
        retry = null
        val my = ++gen
        ready = false; subscribedTo = null; sessionId = ""
        openedAt = SystemClock.uptimeMillis(); lastFrame = openedAt
        log("7TV connecting")
        ws = runCatching { EmoteRepo.webSocket(URL, Listener(my)) }
            .onFailure { log("7TV connect failed: $it") }.getOrNull()
        if (ws == null) { dropped(my, "no socket"); return }
        if (watchdog == null)
            watchdog = loop.scheduleWithFixedDelay({ runCatching { check() } }, 15, 15, TimeUnit.SECONDS)
    }

    private fun close(why: String) {
        retry?.cancel(false); retry = null
        watchdog?.cancel(false); watchdog = null
        val s = ws ?: return
        gen++
        ws = null; ready = false; subscribedTo = null
        runCatching { s.close(1000, null) }
        log("7TV closed: $why")
    }

    /** Socket [my] is gone: try again later, sooner if it had been up a while. */
    private fun dropped(my: Int, why: String) {
        if (my != gen) return
        // Retire this socket, so its own close or failure callback arriving later is ignored.
        gen++
        ws = null; ready = false; subscribedTo = null
        if (SystemClock.uptimeMillis() - openedAt > 60_000L) failures = 0
        if (channel == null || !enabled() || idleClosed.get()) return
        val wait = BACKOFF_S[failures.coerceAtMost(BACKOFF_S.size - 1)]
        failures++
        // A little jitter, so phones that lost the same server do not all come back at once.
        val ms = wait * 1000L + (Math.random() * 1000).toLong()
        log("7TV disconnected ($why); retrying in ${ms / 1000}s")
        retry?.cancel(false)
        retry = loop.schedule({ retry = null; reconcile() }, ms, TimeUnit.MILLISECONDS)
    }

    /** Every 15 s: a silent server, or a chat nobody has looked at for a while. */
    private fun check() {
        val now = SystemClock.uptimeMillis()
        // Looked at before the socket: while a reconnect waits there is no socket, and a chat
        // closed meanwhile must stop the retries too, or they go on for the whole run.
        if (now - lastUse > IDLE_MS) {
            if (!idleClosed.getAndSet(true)) {
                retry?.cancel(false); retry = null
                if (ws != null) close("no chat on screen")
            }
            return
        }
        val s = ws ?: return
        // 7TV sends a heartbeat every heartbeat_interval (about 45 s). Missing two and a half of
        // them means the connection died without saying so, which mobile networks do.
        if (now - lastFrame > heartbeatMs * 5 / 2) {
            log("7TV heartbeat missed; reconnecting")
            val my = gen
            runCatching { s.cancel() }
            dropped(my, "heartbeat timeout")
        }
    }

    private fun onHello(my: Int, h: SevenTvEvents.Hello) {
        if (my != gen) return
        ready = true
        sessionId = h.sessionId
        if (h.heartbeatMs > 0) heartbeatMs = h.heartbeatMs
        reconcile()
    }

    private class Listener(private val my: Int) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            lastFrame = SystemClock.uptimeMillis()
            when (val m = SevenTvEvents.parse(text, BadgeRepo.badgeHeightPx())) {
                is SevenTvEvents.Hello -> loop.execute { onHello(my, m) }
                is SevenTvEvents.BadgeDef -> {
                    looks[m.id] = Look(m.tooltip, m.url)
                    // Fetched now, so the badge is ready for that chatter's next line.
                    BadgeRepo.prefetchImage(m.url)
                }
                is SevenTvEvents.Entitlement -> for (id in m.twitchIds) {
                    if (m.create) wearer[id] = m.badgeId
                    else synchronized(wearer) { if (wearer[id] == m.badgeId) wearer.remove(id) }
                }
                is SevenTvEvents.Reconnect -> loop.execute {
                    if (my == gen) { failures = 0; runCatching { webSocket.close(1000, null) }; dropped(my, "server asked to reconnect") }
                }
                is SevenTvEvents.Error -> log("7TV error: ${m.text}")
                is SevenTvEvents.End -> log("7TV end of stream ${m.code}: ${m.text}")
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(1000, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            loop.execute { dropped(my, "closed $code") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            loop.execute { dropped(my, t.toString()) }
        }
    }

    // ---- the user's own badge ----

    /** Ask 7TV for the logged in account's own badge in [channelId]. One small POST per channel. */
    private fun presence(channelId: String) {
        val session = sessionId
        if (session.isEmpty()) return
        io.execute {
            runCatching {
                // Another account signed in since: its own 7TV account is looked up again.
                val tok = Settings.ctx()?.let { EmoteRepo.authToken(it) }.orEmpty().hashCode()
                if (tok != selfFor) { selfId = null; selfFor = tok }
                val me = selfId ?: lookupSelf() ?: return@runCatching
                if (me.isEmpty()) return@runCatching
                val code = EmoteRepo.httpPostJson("https://7tv.io/v3/users/$me/presences",
                    SevenTvEvents.presenceBody(session, channelId))
                if (code !in 200..299) log("7TV presence HTTP $code")
            }.onFailure { log("7TV presence failed: $it") }
        }
    }

    /** Own Twitch id from Twitch, then its 7TV account. Null when it should be tried again later. */
    private fun lookupSelf(): String? {
        val ctx = Settings.ctx() ?: return null
        val token = EmoteRepo.authToken(ctx)
        if (token.isBlank()) return null  // logged out: maybe logged in by the next channel
        val body = JSONObject().put("query", "query { currentUser { id } }").toString()
        val twitchId = EmoteRepo.gqlPost(token, body)?.let {
            JSONObject(it).optJSONObject("data")?.optJSONObject("currentUser")?.optString("id")
        }
        if (twitchId.isNullOrEmpty()) return null
        val found = runCatching {
            JSONObject(EmoteRepo.httpGet("https://7tv.io/v3/users/twitch/$twitchId"))
                .optJSONObject("user")?.optString("id").orEmpty()
        }.getOrElse { e ->
            // 404 means no 7TV account, which will not change this run; anything else might.
            if (e.message?.contains("HTTP 404") == true) "" else return null
        }
        selfId = found
        log("7TV own account: ${if (found.isEmpty()) "none" else "found"}")
        return found
    }
}
