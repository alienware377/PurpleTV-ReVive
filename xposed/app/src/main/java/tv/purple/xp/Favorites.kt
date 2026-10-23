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

    /** Ordered favorite names (most-recently-added last). */
    fun list(): List<String> = read()

    fun contains(name: String): Boolean = name in read()

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
        if (!isFavoritable(name) || name in cur) return false
        cur.add(name); write(cur); return true
    }

    fun remove(name: String): Boolean {
        val cur = read()
        if (!cur.remove(name)) return false
        write(cur); return true
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
