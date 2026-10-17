package tv.purple.xp

import android.content.Context

/**
 * Standalone PurpleTV settings, backed by the module's own SharedPreferences
 * ("ptv_settings", MODE_PRIVATE) inside the host app's data dir. No dependency on
 * Twitch's obfuscated settings screen — fully self-contained.
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

    /** Favorited Twitch/sub emote NAMES, persisted as a separator-joined ordered list. */
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
     * when this changes -- [get] and friends hit SharedPreferences every call, which is fine for a
     * settings screen and much too expensive for a hook that runs on every TextView write in the
     * app.
     */
    @Volatile var rev: Int = 0
        private set

    fun get(key: String, def: Boolean = true): Boolean {
        val c = appCtx ?: return def
        return runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, def)
        }.getOrDefault(def)
    }

    fun set(key: String, value: Boolean) {
        val c = appCtx ?: return
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply()
        }
        rev++
    }

    fun getInt(key: String, def: Int): Int {
        val c = appCtx ?: return def
        return runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, def)
        }.getOrDefault(def)
    }

    fun setInt(key: String, value: Int) {
        val c = appCtx ?: return
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(key, value).apply()
        }
        rev++
    }

    fun getString(key: String, def: String = ""): String {
        val c = appCtx ?: return def
        return runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, def) ?: def
        }.getOrDefault(def)
    }

    fun setString(key: String, value: String) {
        val c = appCtx ?: return
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        }
        rev++
    }
}
