package tv.purple.xp

import org.json.JSONArray
import org.json.JSONObject

/**
 * What Twitch shows above a chat, read per channel: pinned messages, a running hype train and drops.
 * Twitch's own banners are tied to its single chat, so the two-streams panes ask for the same data
 * with Twitch's own GraphQL fields and show it in one thin line. Kept free of Android for the tests.
 */
internal object DualBanners {

    enum class Kind { HYPE, PINNED, DROPS }

    /** [progress] is 0 to 1 for a hype train's level, and null for the others. */
    data class Item(val kind: Kind, val id: String, val text: String, val level: Int = 0, val progress: Float? = null)

    private const val PIN_FIELDS = "edges { node { id endsAt pinnedMessage { content { text } sender { displayName login } } } }"

    const val QUERY = "query DualBanners(\$id: ID!) { " +
        "channel(id: \$id) { " +
        "mod: pinnedChatMessages(messageType: MOD) { $PIN_FIELDS } " +
        "paid: pinnedChatMessages(messageType: PAID) { $PIN_FIELDS } " +
        "viewerDropCampaigns { id name eventBasedDrops { id name missionName missionDescription } } } " +
        "user(id: \$id) { channel { hypeTrain { execution { id isActive progress { level { value } goal progression } } } } } }"

    fun body(channelId: String): String = JSONObject()
        .put("operationName", "DualBanners")
        .put("query", QUERY)
        .put("variables", JSONObject().put("id", channelId))
        .toString()

    /**
     * The items in a reply, most urgent first. One part failing leaves the others: Twitch answers
     * with null there and an error beside it. Pins past their end time are left out.
     */
    fun parse(json: String, nowMs: Long): List<Item>? {
        val data = runCatching { JSONObject(json) }.getOrNull()?.opt("data") as? JSONObject ?: return null
        val out = ArrayList<Item>()
        hype(data)?.let { out.add(it) }
        val channel = data.opt("channel") as? JSONObject
        if (channel != null) {
            for (key in arrayOf("paid", "mod")) pins(channel.opt(key) as? JSONObject, nowMs, out)
            drops(channel.opt("viewerDropCampaigns") as? JSONArray, out)
        }
        return out
    }

    private fun hype(data: JSONObject): Item? {
        val e = (data.opt("user") as? JSONObject)?.opt("channel").let { it as? JSONObject }
            ?.opt("hypeTrain").let { it as? JSONObject }?.opt("execution") as? JSONObject ?: return null
        if (e.opt("isActive") != true) return null
        val p = e.opt("progress") as? JSONObject ?: return null
        val level = ((p.opt("level") as? JSONObject)?.opt("value") as? Number)?.toInt() ?: 1
        val goal = (p.opt("goal") as? Number)?.toFloat() ?: 0f
        val done = (p.opt("progression") as? Number)?.toFloat() ?: 0f
        val ratio = if (goal > 0f) (done / goal).coerceIn(0f, 1f) else 0f
        return Item(Kind.HYPE, str(e, "id") ?: "hype", "${(ratio * 100).toInt()}%", level, ratio)
    }

    private fun pins(conn: JSONObject?, nowMs: Long, out: MutableList<Item>) {
        val edges = conn?.opt("edges") as? JSONArray ?: return
        for (i in 0 until edges.length()) {
            val n = (edges.opt(i) as? JSONObject)?.opt("node") as? JSONObject ?: continue
            val id = str(n, "id") ?: continue
            val ends = str(n, "endsAt")?.let { s -> runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull() }
            if (ends != null && ends <= nowMs) continue
            val m = n.opt("pinnedMessage") as? JSONObject ?: continue
            val text = (m.opt("content") as? JSONObject)?.let { str(it, "text") } ?: continue
            val who = (m.opt("sender") as? JSONObject)?.let { str(it, "displayName") ?: str(it, "login") }
            out.add(Item(Kind.PINNED, id, if (who != null) "$who: $text" else text))
        }
    }

    private fun drops(list: JSONArray?, out: MutableList<Item>) {
        if (list == null) return
        for (i in 0 until list.length()) {
            val c = list.opt(i) as? JSONObject ?: continue
            val id = str(c, "id") ?: continue
            val first = (c.opt("eventBasedDrops") as? JSONArray)?.opt(0) as? JSONObject
            val text = first?.let { str(it, "missionDescription") ?: str(it, "missionName") ?: str(it, "name") }
                ?: str(c, "name") ?: continue
            out.add(Item(Kind.DROPS, id, text))
        }
    }

    // org.json on Android turns a JSON null into the text "null" through optString.
    private fun str(o: JSONObject, k: String): String? = (o.opt(k) as? String)?.takeIf { it.isNotEmpty() }
}
