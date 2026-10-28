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
 * newest copy wins. Settings that only make sense on one device (the on/off switch, update state,
 * test switches, which panel sections are open) stay local.
 */
object SettingsSync {

    private const val KEY_URI = "sync_uri"
    private const val KEY_NAME = "sync_name"
    /** Time stamp of the copy this device last wrote or applied. */
    private const val KEY_AT = "sync_at"

    private const val REQ_CREATE = 0x5071
    private const val REQ_OPEN = 0x5072
    private const val FILE_NAME = "purpletv-settings.json"
    private const val PUSH_DELAY = 4000L
    private const val PULL_AFTER_AWAY = 30_000L
    private const val PERIODIC_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    /** Guards the change times, which the main thread and the sync thread both rewrite. */
    private val lock = Any()
    @Volatile private var installed = false
    private var lastPausedAt = 0L
    private var dialog: WeakReference<AlertDialog>? = null

    /** Device-local settings, never synced. The master switch is one: a device switched off never
     *  syncs again, so it could not pick up being switched back on. */
    fun local(key: String) = key.startsWith("sync_") || key.startsWith("update_") || key.startsWith("dev_") ||
        key == GlVideoFilter.KEY_FORCE || key.startsWith("vf_ui_") || key.startsWith("af_ui_") ||
        key == Settings.KEY_ENABLED

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
                if (away && enabled()) {
                    sync(a.applicationContext, quiet = true)
                    // Drive may still be fetching the newest copy: look once more shortly after.
                    main.postDelayed({ if (enabled()) sync(a.applicationContext, quiet = true) }, 25_000)
                }
            }
            override fun onActivityPaused(a: Activity) { lastPausedAt = android.os.SystemClock.uptimeMillis() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        main.postDelayed(periodic, PERIODIC_MS)
        log("SY settings sync ready (${if (enabled()) "on: " + Settings.getString(KEY_NAME, "?") else "off"})")
    }

    /** From every settings write: note when this key changed here, and sync a moment later. */
    fun changed(key: String) {
        if (local(key) || !enabled()) return
        synchronized(lock) { val m = mtimes(); m[key] = System.currentTimeMillis(); saveMtimes(m) }
        main.removeCallbacks(syncLater)
        main.postDelayed(syncLater, PUSH_DELAY)
    }

    private val syncLater = Runnable { Settings.ctx()?.let { sync(it, quiet = true) } }

    // ---- when each setting last changed on this device ----

    private const val KEY_MTIMES = "sync_mtimes"

    private fun mtimes(): MutableMap<String, Long> = runCatching {
        val o = JSONObject(Settings.getString(KEY_MTIMES, "{}").ifBlank { "{}" })
        o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun saveMtimes(m: Map<String, Long>) {
        Settings.setString(KEY_MTIMES, JSONObject(m as Map<*, *>).toString())
    }

    // ---- the file ----

    private class Remote(val at: Long, val device: String, val values: Map<String, Any?>, val mtimes: Map<String, Long>)

    private fun snapshot(at: Long, values: Map<String, Any?>, mt: Map<String, Long>): String {
        val o = JSONObject(); val m = JSONObject()
        for ((k, v) in values) {
            if (local(k)) continue
            val t = when (v) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"
                is Set<*> -> "ss"; else -> continue
            }
            o.put(k, JSONArray().put(t).put(if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
            m.put(k, mt[k] ?: 0L)
        }
        return JSONObject().put("app", "purpletv-revive").put("v", 2).put("at", at)
            .put("device", Build.MODEL).put("settings", o).put("mtimes", m).toString(1)
    }

    private fun parse(json: String): Remote? = runCatching {
        val root = JSONObject(json)
        if (root.optString("app") != "purpletv-revive") return null
        val at = root.getLong("at")
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
        // Files from 1.2.0 have no per-setting times: every setting counts as changed when the file was.
        val mo = root.optJSONObject("mtimes")
        val mt = out.keys.associateWith { k -> mo?.optLong(k, at) ?: at }
        Remote(at, root.optString("device"), out, mt)
    }.getOrNull()

    private fun read(ctx: Context, u: Uri): String? {
        // Ask the provider for its newest copy first; Drive can otherwise hand back a cached one.
        runCatching { ctx.contentResolver.refresh(u, null, null) }
        return ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun write(ctx: Context, u: Uri, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val os = runCatching { ctx.contentResolver.openOutputStream(u, "wt") }.getOrNull()
            ?: ctx.contentResolver.openOutputStream(u, "w") ?: error("cannot write the sync file")
        os.use { it.write(bytes) }
    }

    @Volatile private var syncing = false

    /**
     * Read the file, merge setting by setting (whichever device changed it last wins), take what is
     * newer there and write back what is newer here. Merging per setting means two devices changing
     * different things in between never undo each other. [take] (picking an existing file) takes
     * every setting the file has, keeping only this device's settings the file does not know yet.
     */
    fun sync(ctx: Context, quiet: Boolean, take: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val u = uri() ?: return
        if (syncing) { main.postDelayed({ sync(ctx, quiet, take, done) }, 1500); return }
        syncing = true
        Thread({
            var took = 0; var wrote = false
            val r = runCatching {
                val text = read(ctx, u)
                val remote = if (text.isNullOrBlank()) Remote(0, "", emptyMap(), emptyMap())
                    else parse(text) ?: error("not a PurpleTV settings file")
                val here = Settings.all().filterKeys { !local(it) }
                val lm = mtimes()
                // A setting with no change time here has not changed since this device last synced.
                val base = Settings.getString(KEY_AT, "0").toLongOrNull() ?: 0L
                val merged = HashMap<String, Any?>(here); val mm = HashMap(lm)
                val taken = HashMap<String, Any?>()
                var newerHere = false
                for (k in remote.values.keys + here.keys) {
                    // A file from an older version can still hold settings that are now device-only.
                    if (local(k)) continue
                    val inFile = k in remote.values
                    val rt = remote.mtimes[k] ?: 0L
                    // A setting this device has never had can only come from the file. Counting its
                    // absence as newer would drop it from the file, and the device that has it would
                    // put it back on every sync.
                    val lt = lm[k] ?: if (k in here) base else Long.MIN_VALUE
                    val same = inFile && here[k] == remote.values[k]
                    when {
                        same -> {}
                        // Newer in the file, or a tie (on a tie the file wins, so devices settle on one value).
                        inFile && (take || rt >= lt) -> {
                            took++; merged[k] = remote.values[k]; taken[k] = remote.values[k]; mm[k] = maxOf(rt, lt)
                        }
                        else -> newerHere = true
                    }
                }
                if (took > 0) {
                    // Write only what was taken, and skip a setting the user changed while this sync
                    // ran: that change is newer. Writing all of `here` back would undo such changes.
                    val cur = Settings.all()
                    val keep = taken.filterKeys { cur[it] == here[it] }
                    Settings.replace(keep) { it in keep }
                    // The playing stream's sound effect only reads the settings when told to.
                    if (keep.keys.any { it.startsWith("af_") }) runCatching { AudioFilters.refresh() }
                }
                // Untracked settings go out with this device's last sync time, never "now", so an
                // old value can not pass itself off as a new change on the other devices.
                for (k in merged.keys) mm.putIfAbsent(k, base)
                // Save over a fresh copy, so a change made here while this sync ran keeps its newer time.
                synchronized(lock) {
                    val m = mtimes()
                    for ((k, t) in mm) m[k] = maxOf(m[k] ?: t, t)
                    saveMtimes(m)
                }
                val now = System.currentTimeMillis()
                if (newerHere) { write(ctx, u, snapshot(now, merged, mm)); wrote = true }
                Settings.setString(KEY_AT, (if (wrote) now else remote.at).toString())
                log("SY sync: took $took from the file (${remote.device.ifEmpty { "empty" }})" + if (wrote) ", saved this device's newer settings" else "")
            }
            syncing = false
            r.onFailure { log("SY sync failed: $it") }
            val ok = r.isSuccess
            main.post {
                if (took > 0) runCatching { ViewHider.reapply() }
                if (!quiet) toast(ctx, when {
                    !ok -> "Could not reach the sync file"
                    took > 0 -> "Settings synced ($took loaded). A few take effect after restarting Twitch."
                    wrote -> "Settings saved to ${Settings.getString(KEY_NAME, "the sync file")}"
                    else -> "Settings are up to date"
                })
                done?.invoke(ok)
            }
        }, "ptv-sync").apply { isDaemon = true }.start()
    }

    /** While Twitch is in front, check the file now and then for other devices' changes. */
    private val periodic = object : Runnable {
        override fun run() {
            if (lastPausedAt == -1L && enabled()) Settings.ctx()?.let { sync(it, quiet = true) }
            main.postDelayed(this, PERIODIC_MS)
        }
    }

    /** Test aid: log what the sync file holds (its time, device, and the keys matching [re]). */
    fun debugRead(ctx: Context, re: String?) {
        val u = uri() ?: run { log("SY debug: not syncing"); return }
        Thread({
            runCatching {
                val text = read(ctx, u) ?: error("cannot read")
                val root = JSONObject(text)
                val rx = re?.let { Regex(it) }
                val o = root.getJSONObject("settings")
                val keys = o.keys().asSequence().filter { rx == null || rx.containsMatchIn(it) }.toList()
                log("SY debug: file at=${root.optLong("at")} (${java.util.Date(root.optLong("at"))}) from ${root.optString("device")}, " +
                    "${o.length()} settings; local at=${Settings.getString(KEY_AT, "0")}; file: ${where(ctx, u)}")
                for (k in keys) log("SY debug: $k=${o.get(k).toString().replace("", " | ").take(600)}")
            }.onFailure { log("SY debug read failed: $it") }
        }, "ptv-sync").apply { isDaemon = true }.start()
    }

    /** "account: My Drive / folder / file", as far as the provider will say. */
    private fun where(ctx: Context, u: Uri): String = runCatching {
        val cr = ctx.contentResolver
        val path = android.provider.DocumentsContract.findDocumentPath(cr, u) ?: return@runCatching "?"
        fun nameOf(id: String) = cr.query(android.provider.DocumentsContract.buildDocumentUri(u.authority, id),
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "?"
        val acct = path.rootId?.let { rid ->
            cr.query(android.provider.DocumentsContract.buildRootsUri(u.authority), null, null, null, null)?.use { c ->
                val iId = c.getColumnIndex(android.provider.DocumentsContract.Root.COLUMN_ROOT_ID)
                val iSum = c.getColumnIndex(android.provider.DocumentsContract.Root.COLUMN_SUMMARY)
                var out: String? = null
                while (c.moveToNext()) if (c.getString(iId) == rid) out = c.getString(iSum)
                out
            }
        } ?: "?"
        acct + ": " + path.path.joinToString(" / ") { nameOf(it) }
    }.getOrElse { "? ($it)" }

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
                "Things like the PurpleTV on/off switch, update reminders and test switches stay on each device.")
            b.setPositiveButton("Sync now") { _, _ -> sync(a, quiet = false) }
            b.setNegativeButton("Stop syncing") { _, _ -> stop(a) }
            b.setNeutralButton("Use another file") { _, _ ->
                pick(a, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_OPEN)
            }
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
        Settings.setString(KEY_URI, u.toString())
        Settings.setString(KEY_NAME, name)
        Settings.setString(KEY_AT, "0")
        log("SY ${if (code == REQ_CREATE) "created" else "picked"} sync file $name")
        if (code == REQ_CREATE) {
            sync(a, quiet = true) { ok ->
                toast(a, if (ok) "Sync file created. On your other devices, choose \"Use existing file\" and pick $name."
                    else "Could not write to $name")
            }
        } else sync(a, quiet = false, take = true)
    }

    private fun stop(ctx: Context) {
        uri()?.let { u ->
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        for (k in listOf(KEY_URI, KEY_NAME, KEY_AT)) Settings.setString(k, "")
        toast(ctx, "Stopped syncing. Your settings stay as they are.")
    }
}
