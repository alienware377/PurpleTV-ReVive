package tv.purple.xp

import org.json.JSONArray
import org.json.JSONObject

/**
 * The 7TV EventAPI (wss://events.7tv.io/v3) messages that 7TV badges need, read into plain values.
 *
 * Kept free of Android and of the socket so it can be tested on the JVM. Every frame is a JSON
 * object {op, d, t, s}. Opcodes, checked live against events.7tv.io on 2026-11-03:
 *   server: 0 dispatch, 1 hello {session_id, heartbeat_interval}, 2 heartbeat, 4 reconnect,
 *           5 ack, 6 error, 7 end of stream {code, message}
 *   client: 35 subscribe, 36 unsubscribe, both {type, condition}
 *
 * The two dispatches that matter:
 *   cosmetic.create     body.object = {kind:"BADGE", data:{id, name, tooltip, host:{url, files}}}
 *   entitlement.create  body.object = {kind:"BADGE", ref_id:<badge id>,
 *   entitlement.delete                 user:{connections:[{platform:"TWITCH", id:<twitch id>}]}}
 * Paints, emote sets and everything else 7TV sends along are ignored.
 */
object SevenTvEvents {

    const val OP_DISPATCH = 0
    const val OP_HELLO = 1
    const val OP_HEARTBEAT = 2
    const val OP_RECONNECT = 4
    const val OP_ACK = 5
    const val OP_ERROR = 6
    const val OP_END = 7
    const val OP_SUBSCRIBE = 35
    const val OP_UNSUBSCRIBE = 36

    /** What a channel subscription asks for, the same three Chatterino asks for. Not the
     *  "entitlement.*" wildcard: that also brings every update to the chatters' emote sets. */
    val TYPES = listOf("cosmetic.create", "entitlement.create", "entitlement.delete")

    sealed class Msg
    class Hello(val sessionId: String, val heartbeatMs: Long) : Msg()
    object Heartbeat : Msg()
    object Reconnect : Msg()
    class Error(val text: String) : Msg()
    class End(val code: Int, val text: String) : Msg()
    /** A badge's look: [url] is the picture to draw, already a full https address. */
    class BadgeDef(val id: String, val tooltip: String, val url: String) : Msg()
    /** [badgeId] was given to ([create]) or taken from these Twitch accounts. */
    class Entitlement(val create: Boolean, val badgeId: String, val twitchIds: List<String>) : Msg()
    /** Anything else: acks, paints, emote sets, malformed frames. */
    object Other : Msg()

    /** Read one text frame. Never throws; a frame it cannot read is [Other]. [wantPx] is the badge
     *  height the picture is drawn at, used to pick the closest file. */
    fun parse(text: String, wantPx: Int): Msg = runCatching { parseOrThrow(text, wantPx) }.getOrDefault(Other)

    private fun parseOrThrow(text: String, wantPx: Int): Msg {
        // 7TV also sends the chatters' personal emote sets along with their entitlements, some
        // tens of KB each. A dispatch that does not mention a badge is skipped unread.
        if (text.contains("\"op\":0") && !text.contains("\"BADGE\"")) return Other
        val root = JSONObject(text)
        val d = root.optJSONObject("d") ?: JSONObject()
        return when (root.optInt("op", -1)) {
            OP_HELLO -> Hello(d.optString("session_id"), d.optLong("heartbeat_interval", 45_000L))
            OP_HEARTBEAT -> Heartbeat
            OP_RECONNECT -> Reconnect
            OP_ERROR -> Error(d.optString("message", d.toString()))
            OP_END -> End(d.optInt("code"), d.optString("message"))
            OP_DISPATCH -> dispatch(d, wantPx)
            else -> Other
        }
    }

    private fun dispatch(d: JSONObject, wantPx: Int): Msg {
        val type = d.optString("type")
        val obj = d.optJSONObject("body")?.optJSONObject("object") ?: return Other
        if (obj.optString("kind") != "BADGE") return Other
        return when (type) {
            "cosmetic.create" -> {
                val data = obj.optJSONObject("data") ?: return Other
                val id = data.optString("id").ifEmpty { obj.optString("id") }
                val host = data.optJSONObject("host") ?: return Other
                val url = pickUrl(host.optString("url"), host.optJSONArray("files"), wantPx) ?: return Other
                if (id.isEmpty()) return Other
                BadgeDef(id, data.optString("tooltip").ifEmpty { data.optString("name") }, url)
            }
            "entitlement.create", "entitlement.delete" -> {
                val ref = obj.optString("ref_id")
                if (ref.isEmpty()) return Other
                val conns = obj.optJSONObject("user")?.optJSONArray("connections") ?: return Other
                val ids = ArrayList<String>(1)
                for (i in 0 until conns.length()) {
                    val c = conns.optJSONObject(i) ?: continue
                    val id = c.optString("id")
                    // A 7TV account can also link YouTube, Kick and Discord; only the Twitch id
                    // matches a chat line here, and it is always numeric.
                    if (c.optString("platform") == "TWITCH" && id.isNotEmpty() && id.all { it.isDigit() }) ids.add(id)
                }
                if (ids.isEmpty()) Other else Entitlement(type == "entitlement.create", ref, ids)
            }
            else -> Other
        }
    }

    /**
     * The badge file to download. 7TV lists 1x to 4x (18, 36, 54, 72 px), in WEBP and sometimes
     * AVIF too. AVIF only decodes on Android 12 and up, so WEBP is the one used. The smallest file
     * at least [wantPx] tall is picked, so it is scaled down rather than blown up; with no file
     * list at all, 3x is the usual size for a phone.
     */
    fun pickUrl(hostUrl: String, files: JSONArray?, wantPx: Int): String? {
        if (hostUrl.isEmpty()) return null
        val base = (if (hostUrl.startsWith("//")) "https:$hostUrl" else hostUrl).trimEnd('/')
        var best: String? = null; var bestH = 0
        var biggest: String? = null; var biggestH = -1
        if (files != null) for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val webp = f.optString("format").equals("WEBP", true) || name.endsWith(".webp")
            if (!webp || name.isEmpty()) continue
            val h = f.optInt("height")
            if (h > biggestH) { biggest = name; biggestH = h }
            if (h >= wantPx && (best == null || h < bestH)) { best = name; bestH = h }
        }
        val name = best ?: biggest ?: "3x.webp"
        return "$base/$name"
    }

    /** A subscribe (or unsubscribe) frame for one event [type] in a Twitch channel's chat. */
    fun subscription(type: String, channelId: String, subscribe: Boolean = true): String =
        JSONObject()
            .put("op", if (subscribe) OP_SUBSCRIBE else OP_UNSUBSCRIBE)
            .put("d", JSONObject()
                .put("type", type)
                .put("condition", JSONObject()
                    .put("ctx", "channel")
                    .put("platform", "TWITCH")
                    .put("id", channelId)))
            .toString()

    /** Body of a passive channel presence: asks 7TV to send [sessionId] the user's own cosmetics
     *  for that channel without announcing them to anyone else. */
    fun presenceBody(sessionId: String, channelId: String): String =
        JSONObject()
            .put("kind", 1)
            .put("passive", true)
            .put("session_id", sessionId)
            .put("data", JSONObject()
                .put("platform", "TWITCH")
                .put("ctx", "channel")
                .put("id", channelId))
            .toString()
}
