package tv.purple.xp

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Keeps PurpleTV's settings the same on all your devices, through one small file in your cloud
 * storage (Google Drive, or anything else Android's file picker offers).
 *
 * No sign-in and no Google setup: Android's own file picker hands Twitch lasting permission to
 * that one file, and the Drive app moves it between devices. Each device writes the file a few
 * seconds after a setting changes and reads it when Twitch opens or comes back to the front. The
 * newest copy wins. Settings that only make sense on one device (update state, test switches,
 * which panel sections are open) stay local.
 */
object SettingsSync {

    private const val KEY_URI = "sync_uri"
    private const val KEY_NAME = "sync_name"
    /** Time stamp of the copy this device last wrote or applied. */
    private const val KEY_AT = "sync_at"
    /** When a synced setting last changed here. */
    private const val KEY_CHANGED = "sync_changed_at"

    private const val REQ_CREATE = 0x5071
    private const val REQ_OPEN = 0x5072
    private const val FILE_NAME = "purpletv-settings.json"
    private const val PUSH_DELAY = 4000L
    private const val PULL_AFTER_AWAY = 30_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var applying = false
    @Volatile private var installed = false
    private var lastPausedAt = 0L
    private var dialog: WeakReference<AlertDialog>? = null

    /** Device-local settings, never synced. */
    fun local(key: String) = key.startsWith("sync_") || key.startsWith("update_") || key.startsWith("dev_") ||
        key == GlVideoFilter.KEY_FORCE || key.startsWith("vf_ui_") || key.startsWith("af_ui_")

    private fun uri(): Uri? = Settings.getString(KEY_URI, "").takeIf { it.isNotEmpty() }?.let(Uri::parse)

    fun enabled() = uri() != null

    // ---- wiring ----

    fun install(app: Application) {
        if (installed) return
        installed = true
        // Results from the file picker. Twitch's activities pass codes they do not know on to here.
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onActivityResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val code = p.args.getOrNull(0) as? Int ?: return
                    if (code != REQ_CREATE && code != REQ_OPEN) return
                    val a = p.thisObject as Activity
                    val ok = p.args.getOrNull(1) as? Int == Activity.RESULT_OK
                    val data = p.args.getOrNull(2) as? Intent
                    p.result = null
                    if (ok && data?.data != null) picked(a, code, data)
                }
            })
        }.onFailure { log("SY result hook failed: $it") }
        // Backup for an activity that handles results without passing unknown ones on: the
        // framework's own dispatch, before the activity sees it. The first one to see it wins.
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "dispatchActivityResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (p.args.getOrNull(0) != null) return   // meant for a fragment
                    val code = p.args.getOrNull(1) as? Int ?: return
                    if (code != REQ_CREATE && code != REQ_OPEN) return
                    val a = p.thisObject as Activity
                    val ok = p.args.getOrNull(2) as? Int == Activity.RESULT_OK
                    val data = p.args.getOrNull(3) as? Intent
                    p.result = null
                    if (ok && data?.data != null) picked(a, code, data)
                }
            })
        }
        // Read the shared copy when Twitch opens, and when it comes back after a while away.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                val away = lastPausedAt == 0L || android.os.SystemClock.uptimeMillis() - lastPausedAt > PULL_AFTER_AWAY
                lastPausedAt = -1L
                if (away && enabled()) pull(a.applicationContext, quiet = true)
            }
            override fun onActivityPaused(a: Activity) { lastPausedAt = android.os.SystemClock.uptimeMillis() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        log("SY settings sync ready (${if (enabled()) "on: " + Settings.getString(KEY_NAME, "?") else "off"})")
    }

    /** From every settings write. */
    fun changed(key: String) {
        if (applying || local(key) || !enabled()) return
        Settings.setString(KEY_CHANGED, System.currentTimeMillis().toString())
        main.removeCallbacks(pushLater)
        main.postDelayed(pushLater, PUSH_DELAY)
    }

    private val pushLater = Runnable { Settings.ctx()?.let { push(it, quiet = true) } }

    // ---- the file ----

    private fun snapshot(at: Long): String {
        val o = JSONObject()
        for ((k, v) in Settings.all()) {
            if (local(k)) continue
            val t = when (v) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"
                is Set<*> -> "ss"; else -> continue
            }
            o.put(k, JSONArray().put(t).put(if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
        }
        return JSONObject().put("app", "purpletv-revive").put("v", 1).put("at", at)
            .put("device", Build.MODEL).put("settings", o).toString(1)
    }

    private fun parse(json: String): Pair<Long, Map<String, Any?>>? = runCatching {
        val root = JSONObject(json)
        if (root.optString("app") != "purpletv-revive") return null
        val o = root.getJSONObject("settings")
        val out = HashMap<String, Any?>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            out[k] = when (a.optString(0)) {
                "b" -> a.getBoolean(1); "i" -> a.getInt(1); "l" -> a.getLong(1)
                "f" -> a.getDouble(1).toFloat(); "s" -> a.getString(1)
                "ss" -> a.getJSONArray(1).let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                else -> continue
            }
        }
        root.getLong("at") to out
    }.getOrNull()

    private fun read(ctx: Context, u: Uri): String? =
        ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun write(ctx: Context, u: Uri, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val os = runCatching { ctx.contentResolver.openOutputStream(u, "wt") }.getOrNull()
            ?: ctx.contentResolver.openOutputStream(u, "w") ?: error("cannot write the sync file")
        os.use { it.write(bytes) }
    }

    private fun localAt() = maxOf(Settings.getString(KEY_AT, "0").toLongOrNull() ?: 0L,
        Settings.getString(KEY_CHANGED, "0").toLongOrNull() ?: 0L)

    /** Write this device's settings to the file. */
    fun push(ctx: Context, quiet: Boolean, done: ((Boolean) -> Unit)? = null) {
        val u = uri() ?: return
        Thread({
            val ok = runCatching {
                val at = System.currentTimeMillis()
                write(ctx, u, snapshot(at))
                applying = true
                try { Settings.setString(KEY_AT, at.toString()) } finally { applying = false }
                log("SY pushed settings")
            }.onFailure { log("SY push failed: $it") }.isSuccess
            main.post {
                if (!quiet) toast(ctx, if (ok) "Settings saved to ${Settings.getString(KEY_NAME, "the sync file")}" else "Could not save the sync file")
                done?.invoke(ok)
            }
        }, "ptv-sync").apply { isDaemon = true }.start()
    }

    /** Read the file and take it if it is newer than what this device has; push if this device is newer. */
    fun pull(ctx: Context, quiet: Boolean, force: Boolean = false) {
        val u = uri() ?: return
        Thread({
            val r = runCatching {
                val text = read(ctx, u) ?: error("cannot read the sync file")
                if (text.isBlank()) return@runCatching -1
                val (at, values) = parse(text) ?: error("not a PurpleTV settings file")
                if (!force && at <= localAt()) return@runCatching -1
                applying = true
                try {
                    val n = Settings.replace(values) { !local(it) }
                    Settings.setString(KEY_AT, at.toString())
                    n
                } finally { applying = false }
            }
            val n = r.getOrNull()
            r.onFailure { log("SY pull failed: $it") }
            if (n != null && n > 0) log("SY applied $n setting(s) from the sync file")
            main.post {
                if (n != null && n > 0) runCatching { ViewHider.reapply() }
                // The file is older (or empty): this device has the newest settings, so share them.
                if (n == -1 && (Settings.getString(KEY_CHANGED, "0").toLongOrNull() ?: 0L) > (Settings.getString(KEY_AT, "0").toLongOrNull() ?: 0L))
                    push(ctx, quiet = true)
                if (!quiet) toast(ctx, when {
                    n == null -> "Could not read the sync file"
                    n > 0 -> "Settings loaded ($n changed). A few take effect after restarting Twitch."
                    else -> "Settings are up to date"
                })
            }
        }, "ptv-sync").apply { isDaemon = true }.start()
    }

    // ---- the settings row ----

    fun show(ctx: Context) {
        val a = (ctx as? Activity) ?: ViewHider.currentActivity() ?: return
        val u = uri()
        val b = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("Sync settings")
        if (u == null) {
            b.setMessage("Keep your PurpleTV settings the same on all your devices, through one small file in your Google Drive " +
                "(or any cloud storage your device offers). No sign-in needed.\n\n" +
                "On your first device, tap \"Create sync file\" and save it in Google Drive. " +
                "On your other devices, tap \"Use existing file\" and pick that same file.\n\n" +
                "After that, each device saves its settings to the file when you change them, and loads them when Twitch opens.")
            b.setPositiveButton("Create sync file") { _, _ ->
                pick(a, Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, FILE_NAME), REQ_CREATE)
            }
            b.setNeutralButton("Use existing file") { _, _ ->
                pick(a, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), REQ_OPEN)
            }
            b.setNegativeButton("Close", null)
        } else {
            val at = Settings.getString(KEY_AT, "0").toLongOrNull() ?: 0L
            val ago = if (at == 0L) "not yet" else android.text.format.DateUtils.getRelativeTimeSpanString(at).toString()
            b.setMessage("Syncing with \"${Settings.getString(KEY_NAME, FILE_NAME)}\".\nLast synced: $ago.\n\n" +
                "Changes are saved a few seconds after you make them, and loaded when Twitch opens. " +
                "Things like update reminders and test switches stay on each device.")
            b.setPositiveButton("Sync now") { _, _ -> pull(a, quiet = false) }
            b.setNeutralButton("Stop syncing") { _, _ -> stop(a) }
            b.setNegativeButton("Close", null)
        }
        dialog = WeakReference(b.show())
    }

    private fun pick(a: Activity, i: Intent, code: Int) {
        runCatching { a.startActivityForResult(i, code) }
            .onFailure { toast(a, "No file picker found on this device"); log("SY picker: $it") }
    }

    private fun picked(a: Activity, code: Int, data: Intent) {
        val u = data.data ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { a.contentResolver.takePersistableUriPermission(u, flags) }
            .onFailure { log("SY could not keep access: $it") }
        val name = runCatching {
            a.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: FILE_NAME
        applying = true
        try {
            Settings.setString(KEY_URI, u.toString())
            Settings.setString(KEY_NAME, name)
            Settings.setString(KEY_AT, "0")
        } finally { applying = false }
        log("SY ${if (code == REQ_CREATE) "created" else "picked"} sync file $name")
        if (code == REQ_CREATE) {
            push(a, quiet = true) { ok ->
                toast(a, if (ok) "Sync file created. On your other devices, choose \"Use existing file\" and pick $name."
                    else "Could not write to $name")
            }
        } else pull(a, quiet = false, force = true)
    }

    private fun stop(ctx: Context) {
        uri()?.let { u ->
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        applying = true
        try { for (k in listOf(KEY_URI, KEY_NAME, KEY_AT, KEY_CHANGED)) Settings.setString(k, "") } finally { applying = false }
        toast(ctx, "Stopped syncing. Your settings stay as they are.")
    }
}
