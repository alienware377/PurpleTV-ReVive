package tv.purple.xp

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * Looks for a newer PurpleTV ReVive release on GitHub when Twitch starts (at most every few hours)
 * and offers it: what changed since the installed version, Skip this version, Later, and Update,
 * which downloads the file that matches how the module is installed. Switched off in settings.
 */
object UpdateChecker {

    const val KEY = "update_check"
    private const val KEY_SKIP = "update_skip_version"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_LAST_SHOWN = "update_last_shown"
    private const val API = "https://api.github.com/repos/alienware377/purpletv-revive/releases?per_page=15"
    private const val PAGE = "https://github.com/alienware377/purpletv-revive/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    /** "Later" waits this long before the same version is offered again. */
    private const val REMIND_AFTER_MS = 24 * 3600_000L

    private const val BG = 0xFF18181B.toInt()
    private const val PURPLE = 0xFF9146FF.toInt()
    private const val LILAC = 0xFFBF94FF.toInt()
    private const val TEXT = 0xFFEFEFF1.toInt()
    private const val TEXT_DIM = 0xFFADADB8.toInt()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var startedThisRun = false

    class Release(val version: IntArray, val name: String, val tag: String, val body: String,
                  val page: String, val assets: List<Pair<String, String>>)

    /** The installed module version, e.g. [0, 8, 0]. */
    private val current: IntArray by lazy { parse(BuildConfig.VERSION_NAME) ?: intArrayOf(0, 0, 0) }
    private val currentName: String get() = BuildConfig.VERSION_NAME.substringBefore("-twitch")

    /** From startup: one quiet check, a little after the app has settled. */
    fun schedule() {
        if (startedThisRun) return
        startedThisRun = true
        main.postDelayed({ check(manual = null) }, 12_000)
    }

    /** From settings: check now, ignoring the timer and a skipped version, and say what happened. */
    fun checkNow(ctx: Context) {
        Toast.makeText(ctx, "Checking for updates…", Toast.LENGTH_SHORT).show()
        check(manual = ctx)
    }

    private fun check(manual: Context?) {
        if (manual == null) {
            if (!Settings.get(KEY, true)) return
            val last = Settings.getString(KEY_LAST_CHECK, "0").toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        }
        Thread({
            val newer = runCatching { fetchNewer() }
                .onFailure { log("update check failed: $it") }
                .getOrNull()
            Settings.setString(KEY_LAST_CHECK, System.currentTimeMillis().toString())
            main.post {
                if (newer == null) {
                    manual?.let { Toast.makeText(it, "Could not reach GitHub to check for updates", Toast.LENGTH_SHORT).show() }
                    return@post
                }
                if (newer.isEmpty()) {
                    log("update check: up to date ($currentName)")
                    manual?.let { Toast.makeText(it, "You have the newest version ($currentName)", Toast.LENGTH_SHORT).show() }
                    return@post
                }
                val latest = newer.first()
                log("update check: ${latest.tag} is out (have $currentName)")
                if (manual == null) {
                    if (Settings.getString(KEY_SKIP, "") == latest.tag) return@post
                    val shown = Settings.getString(KEY_LAST_SHOWN, "")
                    val (tag, at) = shown.substringBefore('|') to (shown.substringAfter('|', "0").toLongOrNull() ?: 0L)
                    if (tag == latest.tag && System.currentTimeMillis() - at < REMIND_AFTER_MS) return@post
                }
                showWhenReady(newer, manual, tries = 0)
            }
        }, "ptv-update").apply { isDaemon = true }.start()
    }

    private fun fetchNewer(): List<Release> {
        val arr = JSONArray(EmoteRepo.httpGet(API))
        val out = ArrayList<Release>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            val v = parse(tag) ?: continue
            if (compare(v, current) <= 0) continue
            val assets = ArrayList<Pair<String, String>>()
            r.optJSONArray("assets")?.let { a ->
                for (j in 0 until a.length()) a.optJSONObject(j)?.let { assets.add(it.optString("name") to it.optString("browser_download_url")) }
            }
            out.add(Release(v, r.optString("name").ifEmpty { tag }, tag, r.optString("body"), r.optString("html_url").ifEmpty { PAGE }, assets))
        }
        return out.sortedWith { a, b -> compare(b.version, a.version) }
    }

    private fun parse(s: String): IntArray? =
        Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(s)?.groupValues?.drop(1)?.map { it.toInt() }?.toIntArray()

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    private fun showWhenReady(newer: List<Release>, manual: Context?, tries: Int) {
        // Asked for in settings: show right over the settings screen. Otherwise wait for a moment
        // when the app's own screen is in front, not a dialog or the keyboard.
        val act = (manual as? Activity) ?: ViewHider.currentActivity()
        if (act == null || act.isFinishing || act.isDestroyed || (manual == null && !act.hasWindowFocus())) {
            if (tries < 30) main.postDelayed({ showWhenReady(newer, manual, tries + 1) }, 2000)
            return
        }
        runCatching { show(act, newer) }.onFailure { log("update dialog failed: $it") }
    }

    // ---- the dialog ----

    private fun show(act: Activity, newer: List<Release>) {
        val latest = newer.first()
        Settings.setString(KEY_LAST_SHOWN, latest.tag + "|" + System.currentTimeMillis())
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.resources.displayMetrics).toInt()
        val dlg = Dialog(act)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG); cornerRadius = dp(16f).toFloat(); setStroke(dp(1.5f), PURPLE)
            }
            setPadding(dp(20f), dp(18f), dp(20f), dp(12f))
        }
        card.addView(TextView(act).apply {
            text = "UPDATE AVAILABLE"
            setTextColor(LILAC); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        })
        card.addView(TextView(act).apply {
            text = "PurpleTV ReVive ${latest.version.joinToString(".")}"
            setTextColor(TEXT); textSize = 21f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2f), 0, 0)
        })
        card.addView(TextView(act).apply {
            text = "You have $currentName" + (if (newer.size > 1) ". ${newer.size} new versions since then." else ".")
            setTextColor(TEXT_DIM); textSize = 13f
            setPadding(0, dp(2f), 0, dp(10f))
        })
        val notes = TextView(act).apply {
            text = changelog(newer)
            setTextColor(TEXT); textSize = 14f
            setLineSpacing(0f, 1.15f)
        }
        val maxH = (act.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val scroll = object : ScrollView(act) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { addView(notes); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(16f)) }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(TextView(act).apply {
            text = "You can turn off update checks in PurpleTV settings, under Updates."
            setTextColor(TEXT_DIM); textSize = 12f; setTypeface(typeface, Typeface.ITALIC)
            setPadding(0, dp(10f), 0, dp(6f))
        })
        val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        fun textButton(label: String, onClick: () -> Unit) = TextView(act).apply {
            text = label; setTextColor(TEXT_DIM); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnClickListener { onClick() }
        }
        buttons.addView(textButton("Skip this version") {
            Settings.setString(KEY_SKIP, latest.tag); dlg.dismiss()
        })
        buttons.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(textButton("Later") { dlg.dismiss() })
        buttons.addView(TextView(act).apply {
            text = "Update"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(PURPLE); cornerRadius = dp(8f).toFloat() }
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            val url = downloadFor(act, latest)
            // The patched Twitch with install permission downloads and installs inside the app.
            val inApp = url.endsWith("-patched.apk") && InAppUpdate.canInstall(act)
            log("update: Update would ${if (inApp) "download in the app" else "open"} $url")
            setOnClickListener {
                if (inApp) InAppUpdate.start(act, url, latest.tag, latest.version.joinToString("."))
                else open(act, url)
                dlg.dismiss()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f) })
        card.addView(buttons)

        dlg.setContentView(card)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = minOf(act.resources.displayMetrics.widthPixels - dp(32f), dp(480f))
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    /**
     * The file to download for this install: the ready-made patched Twitch when the module runs
     * inside an LSPatch build of the same Twitch version, otherwise the module itself (LSPosed).
     * Anything unclear goes to the release page, where both files and the instructions are.
     */
    private fun downloadFor(ctx: Context, r: Release): String {
        if (isLspatch(ctx)) {
            val twitch = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            // A release can carry a patched Twitch for each supported version: take the one for this Twitch.
            val patched = twitch?.let { v -> r.assets.firstOrNull { it.first.endsWith("-patched.apk") && it.first.startsWith("twitch-$v-") } }
            return patched?.second ?: r.page
        }
        return r.assets.firstOrNull { it.first.contains("-module-") || it.first == "purpletv-xp.apk" }?.second ?: r.page
    }

    /** Whether this Twitch is an LSPatch build with the module inside, rather than LSPosed. LSPatch
     *  hides parts of itself at run time, so several of its traces are tried. */
    private fun isLspatch(ctx: Context): Boolean {
        val factory = runCatching { ctx.applicationInfo.appComponentFactory?.contains("lspatch", true) == true }.getOrDefault(false)
        val zip = runCatching {
            java.util.zip.ZipFile(ctx.applicationInfo.sourceDir).use { it.getEntry("assets/lspatch/config.json") != null }
        }.getOrDefault(false)
        val loader = UpdateChecker::class.java.classLoader?.toString().orEmpty()
        log("update: lspatch factory=$factory zip=$zip loader=${loader.take(160)}")
        return factory || zip || loader.contains("lspatch", true)
    }

    private fun open(ctx: Context, url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Toast.makeText(ctx, "Could not open the download", Toast.LENGTH_SHORT).show() }
    }

    // ---- release notes to text ----

    /** Sections every release repeats, left out of the "what's new" list. */
    private val BOILERPLATE = listOf("no ad blocking", "passkeys", "which file")

    private fun changelog(newer: List<Release>): CharSequence {
        val sb = SpannableStringBuilder()
        for ((i, r) in newer.take(6).withIndex()) {
            if (newer.size > 1) {
                if (sb.isNotEmpty()) sb.append("\n")
                val start = sb.length
                sb.append("Version ${r.version.joinToString(".")}\n")
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.15f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(TEXT), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            appendBody(sb, r.body, keepTwitchNote = i == 0)
        }
        while (sb.isNotEmpty() && sb.last() == '\n') sb.delete(sb.length - 1, sb.length)
        return sb
    }

    private fun appendBody(sb: SpannableStringBuilder, body: String, keepTwitchNote: Boolean) {
        var skipping = false
        var blank = true
        for (raw in body.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            if (line.startsWith("### ") || line.startsWith("## ")) {
                val title = line.trimStart('#', ' ')
                skipping = BOILERPLATE.any { title.contains(it, true) }
                if (skipping) continue
                if (!blank) sb.append("\n")
                val start = sb.length
                appendInline(sb, title)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(LILAC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Counts as a break, so the blank line under a heading adds no extra gap.
                sb.append("\n"); blank = true
                continue
            }
            if (skipping) continue
            // The opening title line and the closing small print are the same every release.
            if (line.startsWith("**PurpleTV ReVive") || line.startsWith("Android 8.0 or newer")) continue
            if (line.startsWith("|")) continue
            if (line.isBlank()) { if (!blank) { sb.append("\n"); blank = true }; continue }
            if (line.startsWith(">")) {
                if (!keepTwitchNote) continue
                val start = sb.length
                appendInline(sb, line.trimStart('>', ' '))
                sb.setSpan(ForegroundColorSpan(TEXT_DIM), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n"); blank = false
                continue
            }
            val bullet = Regex("^\\s*[-*] ").find(line)
            if (bullet != null) {
                sb.append("•  "); appendInline(sb, line.substring(bullet.range.last + 1))
            } else appendInline(sb, line)
            sb.append("\n"); blank = false
        }
    }

    /** Markdown inline bits: **bold**, `code`, [text](link). */
    private fun appendInline(sb: SpannableStringBuilder, text: String) {
        val t = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        var i = 0
        while (i < t.length) {
            when {
                t.startsWith("**", i) -> {
                    val end = t.indexOf("**", i + 2)
                    if (end < 0) { sb.append(t.substring(i)); return }
                    val start = sb.length
                    sb.append(t, i + 2, end)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                }
                t[i] == '`' -> {
                    val end = t.indexOf('`', i + 1)
                    if (end < 0) { sb.append(t.substring(i)); return }
                    val start = sb.length
                    sb.append(t, i + 1, end)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                }
                else -> { sb.append(t[i]); i++ }
            }
        }
    }
}
