package tv.purple.xp

/**
 * Emotes pinned to the top of the suggestion strip.
 *
 * A pin only changes ORDER: a pinned emote still shows only when what is typed matches it, and
 * then it comes first. Stored as one comma separated string; emote names never contain a comma
 * or a space.
 */
object Pins {

    private const val KEY = "suggest_pins"

    /** Keep a separate set of pins for each channel instead of one shared set. */
    const val KEY_PER_CHANNEL = "pins_per_channel"

    private fun storeKey(): String {
        if (!Settings.get(KEY_PER_CHANNEL, false)) return KEY
        val ch = EmoteRepo.currentChannel() ?: return KEY
        return "$KEY@$ch"
    }

    /** What holding an emote in the strip does. */
    const val KEY_HOLD = "suggest_hold_action"
    const val HOLD_PIN = 0
    const val HOLD_FAVORITE = 1
    const val HOLD_BOTH = 2
    const val HOLD_DEFAULT = HOLD_BOTH

    /** In [HOLD_BOTH], how long a hold pins, and how long it favorites instead. */
    const val BOTH_PIN_MS = 2000L
    const val BOTH_FAVORITE_MS = 5000L

    /** The one-time tip about all of this, closed for good with its X. */
    const val KEY_TIP_DISMISSED = "suggest_tip_dismissed"

    @Volatile private var cacheRaw: String? = null
    @Volatile private var cache: Set<String> = emptySet()

    fun all(): Set<String> {
        val raw = Settings.getString(storeKey(), "")
        if (raw != cacheRaw) {
            cache = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            cacheRaw = raw
        }
        return cache
    }

    fun isPinned(name: String) = name in all()

    /** Pin or unpin [name]; returns true when it is now pinned. */
    fun toggle(name: String): Boolean {
        val cur = all().toMutableSet()
        val now = cur.add(name)
        if (!now) cur.remove(name)
        Settings.setString(storeKey(), cur.sorted().joinToString(","))
        return now
    }

    fun holdMode(): Int = Settings.getInt(KEY_HOLD, HOLD_DEFAULT).coerceIn(HOLD_PIN, HOLD_BOTH)
}
