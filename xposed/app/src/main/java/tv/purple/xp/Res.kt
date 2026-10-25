package tv.purple.xp

import android.content.Context
import android.view.View
import java.util.concurrent.ConcurrentHashMap

/**
 * Resource ids by name and names by id, each looked up once.
 *
 * Both lookups are slow, and a failed name lookup throws. Several features walk the whole view
 * tree on every layout, and asking Android for each view's name there (most views have no id, so
 * most lookups threw) blocked the UI thread for tens of milliseconds at a time. That stalled the
 * video picture while the player controls or chat were updating.
 */
object Res {
    /** The app's resource table keeps this package name even in builds with a renamed package. */
    private const val RES_PKG = "tv.twitch.android.app"
    private val ids = ConcurrentHashMap<String, Int>()
    private val names = ConcurrentHashMap<Int, String>()

    fun id(ctx: Context, name: String, type: String = "id"): Int = ids.getOrPut("$type/$name") {
        val r = ctx.resources
        r.getIdentifier(name, type, ctx.packageName).takeIf { it != 0 }
            ?: if (ctx.packageName != RES_PKG) r.getIdentifier(name, type, RES_PKG) else 0
    }

    /**
     * True for the player's control bar. Its gear shares the "settings_button" id with the
     * Settings gear on profile pages, so the player's own share button is what tells them apart.
     */
    fun isPlayerBar(bar: android.view.ViewGroup): Boolean {
        val share = id(bar.context, "share_button")
        return share != 0 && bar.findViewById<View>(share) != null
    }

    /** The resource entry name of [v]'s id, or null. Only the app's own ids (0x7f......) have one. */
    fun entry(v: View): String? {
        val id = v.id
        if (id == View.NO_ID || (id ushr 24) != 0x7f) return null
        return names.getOrPut(id) { runCatching { v.resources.getResourceEntryName(id) }.getOrDefault("") }
            .ifEmpty { null }
    }
}
