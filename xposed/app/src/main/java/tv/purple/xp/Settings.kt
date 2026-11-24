package tv.purple.xp

import android.content.Context

/**
 * Standalone PurpleTV settings, backed by the module's own SharedPreferences
 * ("ptv_settings", MODE_PRIVATE) inside the host app's data dir. No dependency on
 * Twitch's obfuscated settings screen.
 *
 * Keys (all default-on):
 *   emote_autocomplete   master toggle for the no-colon suggestion strip
 *   src_seventv          include 7TV emotes in suggestions/injection
 *   src_bttv             include BTTV emotes
 *   src_ffz              include FFZ emotes
 *   src_twitch           include the user's harvested Twitch emotes in suggestions
 */
object Settings {
    private const val PREFS = "ptv_settings"

    /**
     * Master switch. When off, the module wires nothing but its own settings entry, so the app
     * behaves exactly like stock Twitch and the switch itself is still reachable to turn back on.
     * Read once at startup, hence the restart requirement.
     */
    const val KEY_ENABLED = "mod_enabled"

    const val KEY_AUTOCOMPLETE = "emote_autocomplete"
    const val KEY_SRC_SEVENTV = "src_seventv"
    const val KEY_SRC_BTTV = "src_bttv"
    const val KEY_SRC_FFZ = "src_ffz"
    const val KEY_SRC_TWITCH = "src_twitch"

    /** Auto-claim channel-points bonus chests for the current channel (GQL miner). */
    const val KEY_AUTO_POINTS = "auto_channel_points"

    /** Landscape theater chat-panel opacity, 0..100 (% opaque). Default 100 = unchanged. */
    const val KEY_CHAT_OPACITY = "chat_opacity_landscape"
    const val CHAT_OPACITY_DEFAULT = 100

    /** Favorited Twitch/sub emote names, persisted as a separator-joined ordered list. */
    const val KEY_FAVORITES = "fav_twitch_emotes"

    /** Display label per key, for the settings dialog (declaration order preserved). */
    val ITEMS = linkedMapOf(
        KEY_AUTOCOMPLETE to "Emote autocomplete (no colon needed)",
        KEY_SRC_SEVENTV to "7TV emotes",
        KEY_SRC_BTTV to "BTTV emotes",
        KEY_SRC_FFZ to "FFZ emotes",
        KEY_SRC_TWITCH to "Twitch emotes in suggestions",
        KEY_AUTO_POINTS to "Auto-claim channel points"
    )

    @Volatile private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext ?: ctx
    }

    /** True once a context is available, so a hot-path reader can tell a real value from the
     *  default it gets before the module has finished starting up. */
    fun isReady() = appCtx != null

    /** The host app context, for the few features that need a system service. */
    fun ctx(): Context? = appCtx

    /**
     * Bumped whenever anything is written. Readers on hot paths cache their value and re-read only
     * when this changes: [get] and friends hit SharedPreferences every call, which is fine for a
     * settings screen and much too expensive for a hook that runs on every TextView write in the
     * app.
     */
    /** Bumped after every write, from any thread; readers re-read when it moves. Atomic, so two
     *  writes at once can never count as one and leave a reader on a stale value. */
    private val revCounter = java.util.concurrent.atomic.AtomicInteger(0)
    val rev: Int get() = revCounter.get()

    // ---- filters per stream ----

    /**
     * While a channel's scope is set on this thread, video and audio filter settings read and write
     * that channel's own copy. A value it has no copy of yet reads the shared one, so a stream starts
     * out with the filters already set up. The copies stay on this device: "dual_ui_" keys are not synced.
     */
    private val fxScope = ThreadLocal<String?>()
    @Volatile private var fxUsed = false
    private const val FX_PREFIX = "dual_ui_fx_"

    fun <T> withFxScope(scope: String?, block: () -> T): T {
        val before = fxScope.get()
        setFxScope(scope)
        try { return block() } finally { fxScope.set(before) }
    }

    /** For a panel that stays open: everything on this thread uses [scope] until it is cleared. */
    fun setFxScope(scope: String?) {
        if (scope != null) fxUsed = true
        fxScope.set(scope)
    }

    fun inFxScope(): Boolean = fxUsed && fxScope.get() != null

    private fun isFx(k: String) = (k.startsWith("vf_") && !k.startsWith("vf_ui_") && !k.startsWith("vf_tip") && k != "vf_force_gl") ||
        (k.startsWith("af_") && !k.startsWith("af_ui_"))

    /** The key of the channel's own copy, or null outside a scope or for any other setting. */
    private fun scoped(key: String): String? {
        if (!fxUsed) return null
        val s = fxScope.get() ?: return null
        return if (isFx(key)) "$FX_PREFIX${s}_$key" else null
    }

    /** The key to read: the channel's copy when it has one. */
    private fun readKey(p: android.content.SharedPreferences, key: String): String =
        scoped(key)?.takeIf { p.contains(it) } ?: key

    fun get(key: String, def: Boolean = true): Boolean {
        val c = appCtx ?: return def
        return runCatching {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getBoolean(readKey(p, key), def)
        }.getOrDefault(def)
    }

    fun set(key: String, value: Boolean) {
        val c = appCtx ?: return
        val own = scoped(key)
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(own ?: key, value).apply()
        }
        revCounter.incrementAndGet()
        if (own == null) SettingsSync.changed(key)
    }

    fun getInt(key: String, def: Int): Int {
        val c = appCtx ?: return def
        return runCatching {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getInt(readKey(p, key), def)
        }.getOrDefault(def)
    }

    fun setInt(key: String, value: Int) {
        val c = appCtx ?: return
        val own = scoped(key)
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(own ?: key, value).apply()
        }
        revCounter.incrementAndGet()
        if (own == null) SettingsSync.changed(key)
    }

    fun getString(key: String, def: String = ""): String {
        val c = appCtx ?: return def
        return runCatching {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString(readKey(p, key), def) ?: def
        }.getOrDefault(def)
    }

    fun setString(key: String, value: String) {
        val c = appCtx ?: return
        val own = scoped(key)
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(own ?: key, value).apply()
        }
        revCounter.incrementAndGet()
        if (own == null) SettingsSync.changed(key)
    }

    /** Every stored setting, for [SettingsSync]. */
    fun all(): Map<String, *> {
        val base = appCtx?.let { c ->
            runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all }.getOrNull()
        } ?: return emptyMap<String, Any>()
        val s = (if (fxUsed) fxScope.get() else null) ?: return base
        // In a channel's scope its own copies stand in for the shared values.
        val prefix = "$FX_PREFIX${s}_"
        val out = HashMap<String, Any?>(base)
        for ((k, v) in base) if (k.startsWith(prefix)) out[k.removePrefix(prefix)] = v
        return out
    }

    /**
     * Make the settings whose keys pass [mine] exactly [values]: written, replaced, or removed when
     * absent. One write and one [rev] bump. Returns the keys it actually changed (written or
     * removed). It does not tell [SettingsSync] about them, because sync itself uses this to apply
     * other devices' values, which must not count as changes made here. A caller making its own
     * change passes the returned keys to [SettingsSync.changed].
     */
    fun replace(values: Map<String, Any?>, mine: (String) -> Boolean): Set<String> {
        val c = appCtx ?: return emptySet()
        return runCatching {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cur = all()
            val e = p.edit()
            val changed = HashSet<String>()
            for (k in cur.keys) if (mine(k) && k !in values) { e.remove(scoped(k) ?: k); changed += k }
            for ((k, v) in values) {
                if (!mine(k) || cur[k] == v) continue
                val to = scoped(k) ?: k
                when (v) {
                    is Boolean -> e.putBoolean(to, v); is Int -> e.putInt(to, v); is Long -> e.putLong(to, v)
                    is Float -> e.putFloat(to, v); is String -> e.putString(to, v)
                    is Set<*> -> e.putStringSet(to, v.filterIsInstance<String>().toSet())
                    else -> continue
                }
                changed += k
            }
            // apply(), not commit(): presets call this from a dialog tap on the main thread, and
            // commit() would write the whole file to disk there. apply() updates the in-memory
            // values at once, and every reader (sync included) reads those, never the file.
            if (changed.isNotEmpty()) { e.apply(); revCounter.incrementAndGet() }
            changed
        }.getOrDefault(emptySet())
    }
}
