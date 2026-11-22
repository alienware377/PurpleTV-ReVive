package tv.purple.xp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * The Twitch requests behind the two-streams screen, read into plain values.
 *
 * Kept free of Android so it can be tested on the JVM. A live stream plays from Twitch's "usher"
 * address, which needs a short-lived pass: a token and its signature from Twitch's GraphQL
 * gateway. The same request also asks for the channel and its stream, so one round trip gives
 * both the header (name, title, viewers) and what the player needs to start.
 *
 * The token query is the one Twitch itself sends (StreamAccessTokenQuery, the same text in 31.7.0
 * and 31.1.2) plus a lookup of the channel, with the params Twitch sends for its normal live player.
 */
object DualUsher {

    const val TOKEN_QUERY =
        "query StreamAccessTokenQuery(\$channelName: String!, \$login: String!, \$params: PlaybackAccessTokenParams!) { " +
        "streamPlaybackAccessToken(channelName: \$channelName, params: \$params) { __typename ...PlaybackAccessTokenFragment } " +
        "user(login: \$login) { id login displayName profileImageURL(width: 70) " +
        "stream { id title viewersCount game { displayName } } } }  " +
        "fragment PlaybackAccessTokenFragment on PlaybackAccessToken { signature value authorization { forbiddenReasonCode } }"

    /** The request body for EmoteRepo.gqlPost. */
    fun tokenBody(login: String): String {
        val l = login.trim().lowercase()
        val params = JSONObject().put("platform", "android").put("playerType", "mobile_player")
        val vars = JSONObject().put("channelName", l).put("login", l).put("params", params)
        return JSONObject()
            .put("operationName", "StreamAccessTokenQuery")
            .put("query", TOKEN_QUERY)
            .put("variables", vars)
            .toString()
    }

    /**
     * A stream pass plus what Twitch told us about the channel. [forbidden] is Twitch's reason code
     * when it refuses to play this stream for this account or region, and null when it may play.
     * [live] is false when the channel exists but is not streaming.
     */
    data class Access(
        val value: String, val signature: String, val forbidden: String?, val id: String?, val login: String,
        val displayName: String?, val live: Boolean, val title: String?, val viewers: Int?, val game: String?,
    )

    /** Twitch answered, and there is no channel by that name (data.user is null). */
    fun userMissing(json: String): Boolean {
        val data = runCatching { JSONObject(json) }.getOrNull()?.obj("data") ?: return false
        return data.has("user") && data.isNull("user")
    }

    /**
     * Read the token reply. Null when the reply is not JSON, carries no token or signature, or
     * names no such channel (a banned or mistyped name answers user: null). A reply that has a
     * token but also reports an error elsewhere (a missing picture, say) is still used.
     */
    fun parseAccess(json: String, login: String): Access? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val data = root.obj("data") ?: return null
        val tok = data.obj("streamPlaybackAccessToken") ?: return null
        val value = tok.str("value") ?: return null
        val sig = tok.str("signature") ?: return null
        val user = data.obj("user") ?: return null
        // Twitch answers "NONE" when nothing stands in the way.
        val forbidden = tok.obj("authorization")?.str("forbiddenReasonCode")?.takeUnless { it.equals("NONE", ignoreCase = true) }
        val stream = user.obj("stream")
        return Access(
            value = value,
            signature = sig,
            forbidden = forbidden,
            id = user.idStr("id"),
            login = user.str("login")?.lowercase() ?: login.trim().lowercase(),
            displayName = user.str("displayName"),
            live = stream != null,
            title = stream?.str("title"),
            viewers = stream?.int("viewersCount"),
            game = stream?.obj("game")?.str("displayName"),
        )
    }

    private const val USHER = "https://usher.ttvnw.net"

    /** Per-stream values a copied address must not carry over: the old pass, its clock and
     *  session, and a raid that brought the viewer to the other channel. */
    private val NOT_COPIED = setOf("token", "sig", "cache_buster", "play_session_id", "raid_id")

    /**
     * The usher address for [login]'s live stream.
     *
     * [template] is the last live address Twitch loaded itself (RefreshButton.lastLiveUri()), or
     * null. Copying its parameters means our player asks for the stream exactly the way Twitch
     * does on this device (device type, ad flags, language, app version), with a fresh pass, clock
     * and session in the same places. Without one, the parameters every Twitch live request sends
     * are used, in Twitch's order.
     */
    fun usherUrl(template: String?, login: String, a: Access, nowMs: Long, sessionHex: String, appVersion: String): String {
        val path = "/api/channel/hls/" + enc(login.trim().lowercase()) + ".m3u8"
        val token = "token=" + enc(a.value)
        val sig = "sig=" + enc(a.signature)
        val buster = "cache_buster=$nowMs"
        val session = "play_session_id=" + enc(sessionHex)
        val copied = template?.let { templateParams(it) }
        if (copied != null) {
            val out = arrayListOf(token, sig)
            var hasBuster = false
            var hasSession = false
            for (p in copied.second) {
                when (val k = p.substringBefore('=')) {
                    "cache_buster" -> if (!hasBuster) { out.add(buster); hasBuster = true }
                    "play_session_id" -> if (!hasSession) { out.add(session); hasSession = true }
                    // A feed autoplay may have been the last thing Twitch loaded; ours is a normal start.
                    "autoplayed" -> out.add("autoplayed=false")
                    else -> if (k.isNotEmpty() && k !in NOT_COPIED) out.add(p)
                }
            }
            if (!hasBuster) out.add(buster)
            if (!hasSession) out.add(session)
            return copied.first + path + "?" + out.joinToString("&")
        }
        val out = arrayListOf(
            token, sig, "player_backend=Core", buster, "fast_bread=true", "allow_source=true",
            "allow_audio_only=true", "autoplayed=false", "force_preroll=false", session, "os=Android",
            "transcode_mode=cbr_v1",
        )
        if (appVersion.isNotBlank()) out.add("app_version=" + enc(appVersion.trim()))
        out.add("include_unavailable=true")
        return USHER + path + "?" + out.joinToString("&")
    }

    /** The origin and the raw "k=v" parts of a Twitch usher address, or null when [t] is not one
     *  (another host, not https, or nothing worth copying). Values stay encoded as Twitch sent them. */
    private fun templateParams(t: String): Pair<String, List<String>>? {
        val s = t.trim()
        if (!s.startsWith("https://", ignoreCase = true)) return null
        val rest = s.substring(8)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd).lowercase()
        if (!HOST.matches(host) || !(host == "ttvnw.net" || host.endsWith(".ttvnw.net"))) return null
        val q = rest.substring(hostEnd).substringBefore('#')
        if ('?' !in q) return null
        val parts = q.substringAfter('?').split('&').filter { it.isNotEmpty() }
        if (parts.none { it.substringBefore('=') !in NOT_COPIED }) return null
        return "https://$host" to parts
    }

    private val HOST = Regex("[a-z0-9.-]+")

    /** Like Android's Uri.Builder: UTF-8, and a space as %20 rather than a plus. */
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** One followed channel that is live now, for the channel picker. */
    data class Live(val id: String, val login: String, val displayName: String, val title: String?, val viewers: Int?, val game: String?)

    const val FOLLOWED_QUERY =
        "query FollowedLiveUsers { currentUser { id followedLiveUsers(first: 50) { edges { node { " +
        "id login displayName stream { id title viewersCount game { displayName } } } } } } }"

    fun followedBody(): String = JSONObject()
        .put("operationName", "FollowedLiveUsers")
        .put("query", FOLLOWED_QUERY)
        .put("variables", JSONObject())
        .toString()

    /**
     * The live channels from a followed-channels reply, most watched first. Both connection
     * shapes are read ("edges { node }" and "nodes"); channels that are not streaming, repeats
     * and entries without a login are left out. A list that is there but empty gives an empty
     * list. Logged out (currentUser: null), an error reply, a missing list or something that is
     * not JSON gives null, so the picker can say the list could not be loaded.
     */
    fun parseFollowed(json: String): List<Live>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val conn = root.obj("data")?.obj("currentUser")?.obj("followedLiveUsers") ?: return null
        val users = ArrayList<JSONObject>()
        conn.arr("edges")?.let { a -> for (i in 0 until a.length()) (a.opt(i) as? JSONObject)?.obj("node")?.let { users.add(it) } }
        conn.arr("nodes")?.let { a -> for (i in 0 until a.length()) (a.opt(i) as? JSONObject)?.let { users.add(it) } }
        val seen = HashSet<String>()
        val out = ArrayList<Live>()
        for (u in users) {
            val login = u.str("login")?.lowercase() ?: continue
            val stream = u.obj("stream") ?: continue
            val id = u.idStr("id") ?: continue
            if (!seen.add(login)) continue
            out.add(Live(id, login, u.str("displayName") ?: login, stream.str("title"), stream.int("viewersCount"),
                stream.obj("game")?.str("displayName")))
        }
        // Stable, so channels with the same count keep Twitch's order.
        return out.sortedByDescending { it.viewers ?: -1 }
    }

    private val LOGIN = Regex("[a-z0-9_]{2,25}")

    /**
     * A channel login from what someone typed or pasted: "Name", "@name", "#name",
     * "twitch.tv/name", "https://www.twitch.tv/name?x", "twitch://stream/name". Lowercase, or
     * null when it can not be a Twitch login (letters, digits and underscores, 2 to 25 long).
     */
    fun cleanLogin(s: String): String? {
        var t = s.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        val at = lower.indexOf("twitch.tv/")
        t = when {
            lower.startsWith("twitch://stream/") -> t.substring("twitch://stream/".length)
            at >= 0 && (at == 0 || lower[at - 1] == '.' || lower[at - 1] == '/') -> t.substring(at + "twitch.tv/".length)
            '/' in t -> return null
            else -> t
        }
        t = t.trimStart('@', '#')
        val end = t.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) t = t.substring(0, end)
        val login = t.trim().lowercase()
        return login.takeIf { LOGIN.matches(it) }
    }

    // org.json on Android turns a JSON null into the text "null" through optString, so values
    // are read by type instead.
    private fun JSONObject.obj(k: String): JSONObject? = opt(k) as? JSONObject
    private fun JSONObject.arr(k: String): JSONArray? = opt(k) as? JSONArray
    private fun JSONObject.str(k: String): String? = (opt(k) as? String)?.takeIf { it.isNotEmpty() }
    private fun JSONObject.int(k: String): Int? = (opt(k) as? Number)?.toInt()
    private fun JSONObject.idStr(k: String): String? = when (val v = opt(k)) {
        is String -> v.takeIf { it.isNotEmpty() }
        is Number -> v.toLong().toString()
        else -> null
    }
}
