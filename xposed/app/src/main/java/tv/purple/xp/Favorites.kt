package tv.purple.xp

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Persistent favorite-emote store: an ORDERED list of emote names shown in the favorites bar of
 * the emote picker.
 *
 * Any emote can be a favorite: the user's own Twitch / sub emotes, and 7TV / BTTV / FFZ / Homies
 * ones. A third-party favorite only works in a chat whose emote set has that word, so the bar
 * greys it out elsewhere (see [isAvailable]), and its picture is always the current chat's,
 * since two channels often use the same word for different images.
 *
 * With [KEY_PER_CHANNEL] on, each channel keeps its own list; the shared list is left untouched
 * and comes back when the option is turned off.
 *
 * Persisted via [Settings] as a U+0001-joined string (U+0001 cannot occur in an emote token).
 * Listeners are notified on every change, and on a channel switch, so an open bar refreshes.
 *
 * Favorites can also sit in named groups, each with a name and/or an emote as its icon, that open
 * and close like folders. The loose list keeps its old key, so favorites from before groups stay
 * exactly where they were; the groups live next to it as JSON. [list] is every favorite, loose and
 * grouped, so "is this a favorite" means the same thing everywhere.
 */
object Favorites {
    private const val SEP = "\u0001"

    const val KEY_PER_CHANNEL = "fav_per_channel"

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** The settings key holding the list that applies right now. */
    private fun storeKey(): String {
        if (!Settings.get(KEY_PER_CHANNEL, false)) return Settings.KEY_FAVORITES
        val ch = EmoteRepo.currentChannel() ?: return Settings.KEY_FAVORITES
        return "${Settings.KEY_FAVORITES}@$ch"
    }

    private fun read(): MutableList<String> =
        Settings.getString(storeKey(), "").split(SEP).filter { it.isNotBlank() }.toMutableList()

    /** Every favorite: loose ones, then each group's. */
    fun list(): List<String> = read() + groups().flatMap { it.items }

    /** Favorites not in any group, in their order. */
    fun loose(): List<String> = read()

    fun contains(name: String): Boolean = name in read() || groups().any { name in it.items }

    // ---- groups ----

    class Group(val id: String, var name: String, var icon: String, var open: Boolean, val items: MutableList<String>)

    private fun groupsKey() = storeKey() + "#groups"

    fun groups(): List<Group> = runCatching {
        val a = org.json.JSONArray(Settings.getString(groupsKey(), "[]").ifBlank { "[]" })
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val items = o.optJSONArray("items")
            Group(o.getString("id"), o.optString("name"), o.optString("icon"), o.optBoolean("open", true),
                (0 until (items?.length() ?: 0)).map { items!!.getString(it) }.toMutableList())
        }
    }.getOrDefault(emptyList())

    private fun writeGroups(gs: List<Group>) {
        val a = org.json.JSONArray()
        for (g in gs) a.put(org.json.JSONObject().put("id", g.id).put("name", g.name).put("icon", g.icon)
            .put("open", g.open).put("items", org.json.JSONArray(g.items)))
        Settings.setString(groupsKey(), a.toString())
    }

    fun newGroup(name: String, icon: String): Group {
        val gs = groups().toMutableList()
        val g = Group("g" + System.currentTimeMillis().toString(36), name, icon, true, mutableListOf())
        gs.add(g); writeGroups(gs); notifyChanged()
        return g
    }

    fun editGroup(id: String, name: String, icon: String) {
        val gs = groups()
        gs.firstOrNull { it.id == id }?.let { it.name = name; it.icon = icon } ?: return
        writeGroups(gs); notifyChanged()
    }

    /** Deletes the group; its emotes go back to the loose favorites. */
    fun deleteGroup(id: String) {
        val gs = groups().toMutableList()
        val g = gs.firstOrNull { it.id == id } ?: return
        gs.remove(g)
        val loose = read(); for (n in g.items) if (n !in loose) loose.add(n)
        writeGroups(gs); write(loose)
    }

    fun setOpen(id: String, open: Boolean) {
        val gs = groups()
        gs.firstOrNull { it.id == id }?.let { it.open = open } ?: return
        writeGroups(gs); notifyChanged()
    }

    /** Move [name] into group [groupId] (null = the loose favorites), before [before] or at the end. */
    fun move(name: String, groupId: String?, before: String?) {
        if (name == before) return
        val loose = read(); val gs = groups()
        val target = if (groupId == null) loose else gs.firstOrNull { it.id == groupId }?.items ?: return
        loose.remove(name); for (g in gs) g.items.remove(name)
        val at = before?.let { target.indexOf(it) } ?: -1
        if (at >= 0) target.add(at, name) else target.add(name)
        writeGroups(gs); write(loose)
    }

    /** Twitch and sub emotes the user owns, and any 7TV / BTTV / FFZ / Homies emote. */
    fun isFavoritable(name: String): Boolean =
        name.isNotBlank() && (EmoteRepo.twitch.containsKey(name) || EmoteRepo.map.containsKey(name))

    /** Whether [name] can be used in the current chat. A Twitch emote always can; a third-party
     *  one only when this channel's (or the global) set has it and its source is switched on. */
    fun isAvailable(name: String): Boolean =
        EmoteRepo.twitch.containsKey(name) || (EmoteRepo.map.containsKey(name) && EmoteRepo.enabled(name))

    /** Add if favoritable + not already present. Returns true if the list changed. */
    fun add(name: String): Boolean {
        val cur = read()
        if (!isFavoritable(name) || contains(name)) return false
        cur.add(name); write(cur); return true
    }

    fun remove(name: String): Boolean {
        val cur = read()
        if (cur.remove(name)) { write(cur); return true }
        val gs = groups()
        if (gs.none { it.items.remove(name) }) return false
        writeGroups(gs); notifyChanged(); return true
    }

    /** Toggle membership. Returns the NEW state (true = now a favorite). */
    fun toggle(name: String): Boolean = if (contains(name)) { remove(name); false } else add(name)

    fun addListener(l: () -> Unit) { listeners.add(l) }

    /** Called when the channel changes: availability, pictures and maybe the list itself differ. */
    fun channelChanged() = notifyChanged()

    private fun write(list: List<String>) {
        Settings.setString(storeKey(), list.joinToString(SEP))
        notifyChanged()
    }

    private fun notifyChanged() { for (l in listeners) runCatching { l() } }
}
