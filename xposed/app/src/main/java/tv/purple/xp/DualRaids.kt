package tv.purple.xp

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Twitch's raids for the two-streams panes. Each pane's channel is followed on the "raid.<id>"
 * PubSub topic, the one the app itself listens to. A raid that is getting ready is joined with
 * Twitch's own JoinRaid request, so the raid counts this viewer, and when it leaves the pane moves
 * to the raided channel.
 *
 * Connection work runs on one private thread; [Sub.onGo] is called on the main thread.
 */
internal object DualRaids {

    private const val URL = "wss://pubsub-edge.twitch.tv/v1"
    private const val PING_EVERY_S = 240L
    private const val PONG_WAIT_S = 20L
    private val BACKOFF_S = longArrayOf(1, 2, 4, 8, 16, 30, 60)
    private const val JOIN_QUERY = "mutation JoinRaidMutation(\$input: ID!) { joinRaid(input: { raidID: \$input } ) { raidID } }"

    class Sub(val channelId: String, val onGo: (login: String, name: String, raidId: String) -> Unit)

    data class Event(val type: String, val raidId: String, val creatorId: String?,
                     val targetLogin: String?, val targetName: String?)

    /** The inner message of a raid topic, or null when it is not one. */
    fun parse(message: String): Event? {
        val o = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val type = o.optString("type").takeIf { it.startsWith("raid_") } ?: return null
        val raid = o.optJSONObject("raid") ?: return null
        val id = raid.optString("id").takeIf { it.isNotEmpty() } ?: return null
        fun text(k: String) = raid.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        return Event(type, id, text("creator_id"), text("target_login")?.lowercase(), text("target_display_name"))
    }

    fun joinBody(raidId: String): String = JSONObject()
        .put("operationName", "JoinRaidMutation")
        .put("query", JOIN_QUERY)
        .put("variables", JSONObject().put("input", raidId))
        .toString()

    private val main = Handler(Looper.getMainLooper())
    private val loop = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ptv-dual-raids").apply { isDaemon = true }
    }

    // --- loop thread only ---
    private val subs = ArrayList<Sub>()
    private val joined = LinkedHashSet<String>()
    private var appCtx: Context? = null
    private var ws: WebSocket? = null
    private var gen = 0
    private var failures = 0
    private var retry: ScheduledFuture<*>? = null
    private var pinger: ScheduledFuture<*>? = null
    private var pongWait: ScheduledFuture<*>? = null

    fun add(ctx: Context, s: Sub) {
        val app = ctx.applicationContext ?: ctx
        loop.execute {
            safe {
                appCtx = app
                val fresh = subs.none { it.channelId == s.channelId }
                subs.add(s)
                if (ws == null) { if (retry == null) connect() }
                else if (fresh) listen("LISTEN", listOf(s.channelId))
            }
        }
    }

    fun remove(s: Sub) {
        loop.execute {
            safe {
                if (!subs.remove(s)) return@safe
                if (subs.isEmpty()) close()
                else if (subs.none { it.channelId == s.channelId }) listen("UNLISTEN", listOf(s.channelId))
            }
        }
    }

    private fun connect() {
        retry = null
        val my = ++gen
        ws = runCatching { EmoteRepo.webSocket(URL, Conn(my)) }.onFailure { log("RD connect failed: $it") }.getOrNull()
        if (ws == null) { dropped(my); return }
        if (pinger == null)
            pinger = loop.scheduleWithFixedDelay({ safe { ping() } }, PING_EVERY_S, PING_EVERY_S, TimeUnit.SECONDS)
    }

    private fun close() {
        gen++
        retry?.cancel(false); retry = null
        pinger?.cancel(false); pinger = null
        pongWait?.cancel(false); pongWait = null
        runCatching { ws?.close(1000, null) }
        ws = null
        failures = 0
    }

    private fun listen(type: String, channels: List<String>) {
        val s = ws ?: return
        if (channels.isEmpty()) return
        val data = JSONObject().put("topics", JSONArray(channels.map { "raid.$it" }))
        val token = appCtx?.let { c -> runCatching { EmoteRepo.authToken(c) }.getOrDefault("") }.orEmpty()
        if (token.isNotBlank()) data.put("auth_token", token)
        s.send(JSONObject().put("type", type).put("nonce", "ptv${System.nanoTime()}").put("data", data).toString())
    }

    private fun ping() {
        val s = ws ?: return
        val my = gen
        s.send("{\"type\":\"PING\"}")
        pongWait?.cancel(false)
        pongWait = loop.schedule({ safe { if (my == gen) restart(my) } }, PONG_WAIT_S, TimeUnit.SECONDS)
    }

    private fun restart(my: Int) {
        if (my != gen) return
        runCatching { ws?.cancel() }
        dropped(my)
    }

    private fun dropped(my: Int) {
        if (my != gen) return
        gen++
        ws = null
        pongWait?.cancel(false); pongWait = null
        if (subs.isEmpty() || retry != null) return
        val wait = BACKOFF_S[failures.coerceAtMost(BACKOFF_S.size - 1)]
        failures++
        retry = loop.schedule({ safe { retry = null; if (ws == null && subs.isNotEmpty()) connect() } }, wait, TimeUnit.SECONDS)
    }

    private fun frame(my: Int, text: String) {
        if (my != gen) return
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (o.optString("type")) {
            "PONG" -> { pongWait?.cancel(false); pongWait = null }
            "RECONNECT" -> restart(my)
            "RESPONSE" -> {
                failures = 0
                val error = o.optString("error")
                log(if (error.isEmpty()) "RD listening for raids" else "RD listen refused: $error")
            }
            "MESSAGE" -> {
                val data = o.optJSONObject("data") ?: return
                val channel = data.optString("topic").removePrefix("raid.")
                parse(data.optString("message"))?.let { event(channel, it) }
            }
        }
    }

    private fun event(channel: String, e: Event) {
        when (e.type) {
            "raid_update_v2" -> join(e)
            "raid_go_v2" -> {
                join(e)
                val login = e.targetLogin ?: return
                val name = e.targetName ?: login
                val to = subs.filter { it.channelId == channel }
                log("RD raid from $channel to $login")
                main.post { for (s in to) runCatching { s.onGo(login, name, e.raidId) }.onFailure { log("RD move failed: $it") } }
            }
            "raid_cancel_v2" -> joined.remove(e.raidId)
        }
    }

    /** Once per raid, and never for a raid this account started itself. */
    private fun join(e: Event) {
        if (e.raidId in joined) return
        val ctx = appCtx ?: return
        if (e.creatorId != null && e.creatorId == runCatching { EmoteRepo.ownUserId(ctx) }.getOrNull()) return
        val token = runCatching { EmoteRepo.authToken(ctx) }.getOrDefault("")
        if (token.isBlank()) return
        joined.add(e.raidId)
        if (joined.size > 20) joined.remove(joined.first())
        val ok = runCatching { EmoteRepo.gqlPost(token, joinBody(e.raidId)) }.getOrNull()
            ?.let { r -> runCatching { JSONObject(r).optJSONObject("data")?.optJSONObject("joinRaid") != null }.getOrDefault(false) } == true
        log("RD ${if (ok) "joined" else "could not join"} the raid to ${e.targetLogin ?: "?"}")
        if (!ok) joined.remove(e.raidId)
    }

    private class Conn(private val my: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            loop.execute { safe { if (my == gen) listen("LISTEN", subs.map { it.channelId }.distinct()) } }
        }
        override fun onMessage(webSocket: WebSocket, text: String) { loop.execute { safe { frame(my, text) } } }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { loop.execute { safe { dropped(my) } } }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { loop.execute { safe { dropped(my) } } }
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { log("RD error: ${t.javaClass.simpleName}") }
    }
}
