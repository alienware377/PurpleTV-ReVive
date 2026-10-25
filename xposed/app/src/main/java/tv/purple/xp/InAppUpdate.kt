package tv.purple.xp

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.lang.ref.WeakReference

/**
 * Updating without leaving Twitch, for the ready-made patched Twitch.
 *
 * The new patched Twitch is downloaded in the background while a slim bar at the top of the
 * screen shows the progress (it lets every touch through). When it is done, a popup offers
 * Install now or Remind me later; a reminder comes back every 3 hours and whenever Twitch is
 * started fresh. Installing hands the file to Android's own installer.
 *
 * This needs Twitch to be allowed to ask for app installs, which stock Twitch is not: the
 * ready-made patched Twitch from 0.9.4 on carries that permission. Without it (LSPosed, or an
 * older patched build) the update opens in the browser as before.
 */
object InAppUpdate {

    private const val KEY_FILE = "update_ready_file"
    private const val KEY_TAG = "update_ready_tag"
    private const val KEY_REMIND_AT = "update_remind_at"
    private const val REMIND_MS = 3 * 3600_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var downloading = false
    @Volatile private var progress = 0
    private var label = ""
    private var bar: WeakReference<View>? = null
    private var current: WeakReference<Activity>? = null
    private var popupShown: WeakReference<Dialog>? = null
    private var installed = false
    private var startChecked = false

    /** Whether this Twitch may install apps itself (the patched builds that carry the permission). */
    fun canInstall(ctx: Context): Boolean = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        pi.requestedPermissions?.contains("android.permission.REQUEST_INSTALL_PACKAGES") == true
    }.getOrDefault(false)

    /** From startup: follow the screen in front, and offer an already downloaded update once. */
    private var installTries = 0

    fun install(ctx: Context) {
        if (installed) return
        // Called while Twitch is still starting, before Android has made its Application; it
        // exists a moment later.
        val app = ctx as? Application ?: ctx.applicationContext as? Application
        if (app == null) { if (++installTries < 20) main.postDelayed({ install(ctx) }, 250); return }
        installed = true
        log("update: watching for downloaded updates")
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                current = WeakReference(a)
                if (downloading) showBar(a)
                checkReady(a, fresh = !startChecked)
                startChecked = true
            }
            override fun onActivityPaused(a: Activity) { if (current?.get() === a) hideBar() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // While Twitch stays open, remind every 3 hours.
        main.postDelayed(object : Runnable {
            override fun run() {
                current?.get()?.let { checkReady(it, fresh = false) }
                main.postDelayed(this, 10 * 60_000L)
            }
        }, 10 * 60_000L)
    }

    /** Start downloading [url] (the patched Twitch for [tag]). */
    fun start(ctx: Context, url: String, tag: String, versionLabel: String) {
        if (downloading) { Toast.makeText(ctx, "The update is already downloading", Toast.LENGTH_SHORT).show(); return }
        val dir = ctx.getExternalFilesDir("updates") ?: File(ctx.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }   // only ever keep the newest
        val out = File(dir, url.substringAfterLast('/'))
        downloading = true; progress = 0; label = versionLabel
        (ctx as? Activity ?: current?.get())?.let { showBar(it) }
        log("update: downloading $url")
        Thread({
            val ok = runCatching {
                EmoteRepo.httpDownload(url, out) { done, total ->
                    val p = if (total > 0) (done * 100 / total).toInt() else 0
                    if (p != progress) { progress = p; main.post { updateBar() } }
                }
            }.onFailure { log("update: download failed: $it") }.isSuccess
            main.post {
                downloading = false
                hideBar()
                val a = current?.get()
                if (!ok) { a?.let { Toast.makeText(it, "The update download failed. It will be offered again later.", Toast.LENGTH_LONG).show() }; return@post }
                Settings.setString(KEY_FILE, out.path)
                Settings.setString(KEY_TAG, tag)
                Settings.setString(KEY_REMIND_AT, "0")
                log("update: ready at ${out.path} (${out.length() / 1024} KB)")
                a?.let { showReady(it) }
            }
        }, "ptv-update-dl").apply { isDaemon = true }.start()
    }

    private fun readyFile(): File? = Settings.getString(KEY_FILE, "").takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() && it.length() > 0 }

    /** Offer a downloaded update: on a fresh start always, otherwise when the reminder is due. */
    private fun checkReady(a: Activity, fresh: Boolean) {
        val f = readyFile() ?: return
        // Already installed (the version in its name is no longer newer): clean up.
        if (!isNewer(f.name)) { f.delete(); Settings.setString(KEY_FILE, ""); return }
        val at = Settings.getString(KEY_REMIND_AT, "0").toLongOrNull() ?: 0L
        if (!fresh && System.currentTimeMillis() < at) return
        if (popupShown?.get()?.isShowing == true) return
        main.postDelayed({ if (!a.isFinishing && a.hasWindowFocus()) showReady(a) }, if (fresh) 4000 else 500)
    }

    /** "twitch-31.7.0-purpletv-revive-0.9.4-patched.apk" is newer than the running module? */
    private fun isNewer(fileName: String): Boolean {
        val m = Regex("revive-(\\d+)\\.(\\d+)\\.(\\d+)").find(fileName) ?: return false
        val v = m.groupValues.drop(1).map { it.toInt() }
        val c = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(BuildConfig.VERSION_NAME)?.groupValues?.drop(1)?.map { it.toInt() } ?: return true
        for (i in 0 until 3) if (v[i] != c[i]) return v[i] > c[i]
        return false
    }

    // ---- the progress bar ----

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun showBar(a: Activity) {
        val content = a.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout ?: return
        bar?.get()?.let { if (it.parent === content) { updateBar(); return } else (it.parent as? ViewGroup)?.removeView(it) }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xE6241B31.toInt()); cornerRadius = dp(a, 14f).toFloat(); setStroke(dp(a, 1f), FilterIcons.PURPLE) }
            setPadding(dp(a, 12f), dp(a, 6f), dp(a, 12f), dp(a, 7f))
            minimumWidth = dp(a, 260f)
            elevation = dp(a, 6f).toFloat()
            isClickable = false; isFocusable = false
            addView(TextView(a).apply { tag = "text"; setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "progress"; max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(FilterIcons.PURPLE)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 6f)).apply { topMargin = dp(a, 4f) })
        }
        content.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(a, 30f) })
        bar = WeakReference(box)
        updateBar()
    }

    private fun updateBar() {
        val b = bar?.get() ?: return
        b.findViewWithTag<TextView>("text")?.text = "Downloading ReVive $label   $progress%"
        b.findViewWithTag<ProgressBar>("progress")?.progress = progress
    }

    private fun hideBar() { bar?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }; bar = null }

    // ---- the ready popup ----

    private fun showReady(a: Activity) {
        val f = readyFile() ?: return
        val name = Regex("revive-(\\d+\\.\\d+\\.\\d+)").find(f.name)?.groupValues?.get(1) ?: "update"
        val d = Dialog(a); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFF18181B.toInt()); cornerRadius = dp(a, 16f).toFloat(); setStroke(dp(a, 1.5f), 0xFF9146FF.toInt()) }
            setPadding(dp(a, 20f), dp(a, 18f), dp(a, 20f), dp(a, 12f))
            addView(TextView(a).apply { text = "UPDATE READY"; setTextColor(0xFFBF94FF.toInt()); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f })
            addView(TextView(a).apply { text = "PurpleTV ReVive $name is ready to install"; setTextColor(0xFFEFEFF1.toInt()); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(a, 4f), 0, dp(a, 6f)) })
            addView(TextView(a).apply {
                text = "Installing updates Twitch in place: you stay signed in and your settings are kept. Twitch closes and opens again."
                setTextColor(0xFFADADB8.toInt()); textSize = 13f; setPadding(0, 0, 0, dp(a, 10f))
            })
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            row.addView(TextView(a).apply {
                text = "Remind me later"; setTextColor(0xFFADADB8.toInt()); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(a, 12f), dp(a, 10f), dp(a, 12f), dp(a, 10f))
                setOnClickListener { Settings.setString(KEY_REMIND_AT, (System.currentTimeMillis() + REMIND_MS).toString()); d.dismiss() }
            })
            row.addView(TextView(a).apply {
                text = "Install now"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { setColor(0xFF9146FF.toInt()); cornerRadius = dp(a, 8f).toFloat() }
                setPadding(dp(a, 18f), dp(a, 10f), dp(a, 18f), dp(a, 10f))
                setOnClickListener { d.dismiss(); installNow(a, f) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(a, 6f) })
            addView(row)
        }
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(minOf(a.resources.displayMetrics.widthPixels - dp(a, 32f), dp(a, 440f)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.setOnCancelListener { Settings.setString(KEY_REMIND_AT, (System.currentTimeMillis() + REMIND_MS).toString()) }
        popupShown = WeakReference(d)
        d.show()
    }

    private fun installNow(a: Activity, f: File) {
        // Android asks once to allow Twitch to install apps; send the user there first if needed.
        if (android.os.Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow Twitch to install apps, then come back", Toast.LENGTH_LONG).show()
            Settings.setString(KEY_REMIND_AT, "0")
            runCatching {
                a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            }
            return
        }
        runCatching {
            val uri = androidx_uri(a, f)
            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            log("update: installer opened for ${f.name}")
        }.onFailure {
            log("update: could not open the installer: $it")
            Toast.makeText(a, "Could not open the installer", Toast.LENGTH_SHORT).show()
        }
    }

    /** A content:// address for [f] through Twitch's own file provider (it shares external storage). */
    private fun androidx_uri(ctx: Context, f: File): Uri {
        val cls = ctx.classLoader.loadClass("androidx.core.content.FileProvider")
        return cls.getMethod("getUriForFile", Context::class.java, String::class.java, File::class.java)
            .invoke(null, ctx, "${ctx.packageName}.fileprovider", f) as Uri
    }
}
