package tv.purple.xp

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Twitch's own chat badges by the "set/version" names IRC sends ("subscriber/12", "moderator/1").
 *
 * IRC only names a badge; the picture and its title come from Twitch's API, the same two GQL
 * questions the badge sheet asks (BadgeInfo): the global badges once, and each channel's own
 * (its subscriber and bits badges) once per channel. A channel's own badge wins over the global
 * one of the same name, the way Twitch shows them.
 *
 * Threading: requests run one after another on a private thread; [of] only reads memory.
 */
object TwitchBadges {

    private const val Q_GLOBAL =
        "query { badges { setID version title description imageURL(size: QUADRUPLE) } }"
    private const val Q_CHANNEL =
        "query(\$id: ID!) { user(id: \$id) { broadcastBadges { setID version title description imageURL(size: QUADRUPLE) } } }"

    /** A failed request is not repeated before this, however many lines ask. */
    private const val RETRY_MS = 60_000L
    private const val MAX_CHANNELS = 12

    private class Badge(val url: String, val title: String)

    /**
     * One badge list: exact "set/version" names, plus each set's numbered versions in order. The
     * second is for a version the list does not have, like a sub badge for a month count the
     * channel never drew a picture for, where Twitch shows the nearest lower one.
     */
    private class Table(val exact: Map<String, Badge>, val numbered: Map<String, List<Pair<Long, Badge>>>, at: Long) {
        /** When a chat last asked for this list, so the least used one goes first. */
        @Volatile var used = at
        fun find(set: String, version: String): Badge? {
            exact["$set/$version"]?.let { return it }
            val want = version.toLongOrNull() ?: return null
            val list = numbered[set] ?: return null
            var best: Badge? = null
            for ((v, b) in list) { if (v <= want) best = b else break }
            return best
        }
    }

    private val work = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ptv-dual-badges").apply { isDaemon = true }
    }

    @Volatile private var global: Table? = null
    private val channels = ConcurrentHashMap<String, Table>()

    // --- work thread only ---
    private var globalTried = 0L
    private val channelTried = HashMap<String, Long>()

    /**
     * Fetch the global badges (once) and [channelId]'s own (once), then call [done] on a worker
     * thread, also when a request failed. A blank [channelId] fetches only the globals.
     */
    fun load(ctx: Context, channelId: String, done: () -> Unit) {
        val app = ctx.applicationContext ?: ctx
        work.execute {
            runCatching {
                val now = SystemClock.uptimeMillis()
                if (channelId.isNotBlank()) channels[channelId]?.used = now
                if (global == null && (globalTried == 0L || now - globalTried > RETRY_MS)) {
                    globalTried = now
                    query(app, Q_GLOBAL, null)?.let { data ->
                        val t = table(data.optJSONArray("badges"))
                        if (t.exact.isNotEmpty()) { global = t; log("TB global badges: ${t.exact.size}") }
                    }
                }
                if (channelId.isNotBlank() && channels[channelId] == null) {
                    val tried = channelTried[channelId]
                    if (tried == null || now - tried > RETRY_MS) {
                        channelTried[channelId] = now
                        query(app, Q_CHANNEL, JSONObject().put("id", channelId))?.let { data ->
                            // A channel without badges of its own answers with an empty list:
                            // that is still an answer, so it is kept and not asked again.
                            val user = data.optJSONObject("user")
                            if (user != null || data.has("user")) {
                                val t = table(user?.optJSONArray("broadcastBadges"))
                                channels[channelId] = t
                                channelTried.remove(channelId)
                                trim(channelId)
                                log("TB channel $channelId badges: ${t.exact.size}")
                            }
                        }
                    }
                }
            }.onFailure { log("TB load failed: ${it.javaClass.simpleName}") }
            runCatching { done() }.onFailure { log("TB done callback failed: $it") }
        }
    }

    /**
     * True while the global badges or [channelId]'s own are not in yet, so a caller can ask
     * [load] again later. A failed request is only repeated after [RETRY_MS].
     */
    fun missing(channelId: String) = global == null || (channelId.isNotBlank() && channels[channelId] == null)

    /**
     * The picture address and title of badge [set]/[version] in [channelId]'s chat, or null when
     * it is not known (yet). The channel's own badge first, then the global one.
     */
    fun of(channelId: String, set: String, version: String): Pair<String, String>? {
        if (set.isEmpty()) return null
        val ch = if (channelId.isNotEmpty()) channels[channelId] else null
        val g = global
        val b = ch?.exact?.get("$set/$version") ?: g?.exact?.get("$set/$version")
            ?: ch?.find(set, version) ?: g?.find(set, version) ?: return null
        return b.url to b.title
    }

    private fun query(ctx: Context, q: String, vars: JSONObject?): JSONObject? {
        val body = JSONObject().put("query", q).apply { if (vars != null) put("variables", vars) }.toString()
        // The token goes to gql.twitch.tv only, inside gqlPost, and is never logged.
        val resp = EmoteRepo.gqlPost(EmoteRepo.authToken(ctx), body) ?: return null
        return runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull()
    }

    private fun table(list: JSONArray?): Table {
        val exact = HashMap<String, Badge>()
        val numbered = HashMap<String, MutableList<Pair<Long, Badge>>>()
        if (list != null) for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val set = o.optString("setID"); val version = o.optString("version")
            val url = o.optString("imageURL")
            if (set.isEmpty() || version.isEmpty() || url.isEmpty()) continue
            val b = Badge(url, o.optString("title").ifBlank { set })
            exact["$set/$version"] = b
            version.toLongOrNull()?.let { numbered.getOrPut(set) { ArrayList() }.add(it to b) }
        }
        for (l in numbered.values) l.sortBy { it.first }
        return Table(exact, numbered, SystemClock.uptimeMillis())
    }

    /** Keep the badge lists of the last few channels only, and always those of an open chat. */
    private fun trim(keep: String) {
        while (channels.size > MAX_CHANNELS) {
            val oldest = channels.entries.filter { it.key != keep && !SideEmotes.isPinned(it.key) }
                .minByOrNull { it.value.used }?.key ?: return
            channels.remove(oldest)
        }
    }
}
