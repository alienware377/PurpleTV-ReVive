package tv.purple.xp

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Injects the "PurpleTV" row into Twitch's Settings screen (profile → settings).
 *
 * Anchor: the resource entry name `settings_wrapper`, the root LinearLayout of Twitch's
 * settings_activity layout. It is the only layout in the whole resource table using that name, so
 * matching on it identifies the settings screen unambiguously without touching a single obfuscated
 * class name.
 *
 * Why a pinned row and not a list item: as of Twitch 28.x the top-level settings list is rendered
 * with Jetpack Compose, so its rows carry no resource ids and cannot be found by view walking or
 * extended via an adapter. `settings_wrapper` is still a classic View, so we insert our row into it
 * directly, immediately below the toolbar. Sub-screens (Preferences, Account, …) are still
 * RecyclerView-based, but pinning at the top level keeps a single code path.
 */
object SettingsEntry {

    /** settings_wrapper containers already injected into (weak: activities come and go). */
    private val INJECTED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<ViewGroup, Boolean>()
    )
    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun install(lp: LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                scheduleScan(act)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", hook)
            log("SettingsEntry installed (Activity onResume)")
        }.onFailure { log("SettingsEntry hook failed: $it") }
    }

    private fun scheduleScan(act: Activity) {
        // The settings fragment attaches after onResume, so retry briefly, then keep a layout
        // listener for the activity's life (navigating between sub-screens re-inflates the host).
        for (delay in longArrayOf(0L, 200L, 600L, 1200L)) {
            handler.postDelayed({ runCatching { scan(act) }.onFailure { log("SE scan: $it") } }, delay)
        }
        if (GL_WIRED.add(act)) {
            runCatching {
                val root = act.window?.decorView ?: return
                root.viewTreeObserver.addOnGlobalLayoutListener { runCatching { scan(act) } }
            }
        }
    }

    private fun scan(act: Activity) {
        val decor = act.window?.decorView ?: return
        // settings_wrapper identifies the screen. Its immediate child is an id-less, full-height
        // LinearLayout holding [app_bar_layout, fragment_container]; inserting into the wrapper
        // itself puts the row either above the toolbar or below the match_parent content and off
        // screen. So anchor on the app bar and insert into ITS parent, directly beneath it.
        if (findByEntry(decor, "settings_wrapper") == null) return
        val appBar = findByEntry(decor, "app_bar_layout") ?: return
        val host = appBar.parent as? ViewGroup ?: return
        if (!INJECTED.add(host)) return
        val ctx = host.context ?: return

        val insertAt = host.indexOfChild(appBar) + 1
        host.addView(buildRow(ctx), insertAt)
        log("SE PurpleTV row injected below app_bar_layout at index $insertAt")
    }

    private fun buildRow(ctx: Context): View {
        val title = TextView(ctx).apply {
            text = "PurpleTV"
            setTextColor(0xFFEFEFF1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        val sub = TextView(ctx).apply {
            text = "Emotes, chat and player tweaks"
            setTextColor(0xFFADADB8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(ctx, 2f), 0, 0)
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(sub)
        }
        val glyph = TextView(ctx).apply {
            text = "◆"                       // ◆ — stands in for the mod's icon
            setTextColor(0xFF9146FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, dp(ctx, 14f), 0)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1F1F23.toInt())
            setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 16f), dp(ctx, 14f))
            isClickable = true
            addView(glyph)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "›"                   // ›
                setTextColor(0xFFADADB8.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            })
            setOnClickListener { runCatching { PurpleMenu.show(ctx) }.onFailure {
                log("SE menu open failed: $it"); toast(ctx, "Couldn't open PurpleTV settings")
            } }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Depth-first search for the first descendant whose resource entry name == [entry]. */
    private fun findByEntry(root: View, entry: String): View? {
        val e = Res.entry(root)
        if (e == entry) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByEntry(root.getChildAt(i) ?: continue, entry)?.let { return it }
            }
        }
        return null
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
