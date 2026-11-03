package tv.purple.xp

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Clip and save: one tap in the live player makes a Twitch clip of the last moments (Twitch's own
 * clip system, so it is a normal clip on the channel), waits until Twitch has it ready, and saves
 * it as an MP4 into Movies/PurpleTV on the phone.
 *
 * The clip is made with Twitch's public Helix API as the signed-in user. If Twitch does not let
 * the app's sign-in do that, GQL's createClip is tried instead. The finished clip's MP4 address
 * comes from GQL, signed with the clip's own short-lived access token.
 */
object ClipSave {

    const val KEY = "clip_save_button"
    private const val TAG = "ptv_clip_save"
    /** Twitch takes some seconds to cut a clip. Looked at every 2.5 s, given up after a minute. */
    private const val POLL_MS = 2500L
    private const val POLL_TRIES = 24
    /** Up to two minutes of retries while a new clip's file is still being made (it took about 40 s). */
    private const val DOWNLOAD_TRIES = 48
    private const val CLIP_HASH = "36b89d2507fce29e5ca551df756d27c1cfe079e2609642b4390aa4c35796eb11"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    // ------------------------------------------------------------ the button

    /** From each ViewHider pass, after the refresh button: left of it, live streams only. */
    fun attach(root: View) {
        val on = Settings.get(KEY, true)
        val ctx = root.context
        val settingsId = Res.id(ctx, "settings_button"); if (settingsId == 0) return
        val bars = ArrayList<ViewGroup>()
        collectBars(root, settingsId, bars)
        val live = RefreshButton.liveLogin() != null
        for (bar in bars) {
            var btn = bar.findViewWithTag<ImageView>(TAG)
            if (!on) { if (btn != null) bar.removeView(btn); continue }
            val row = ArrayList<View>()
            for (i in 0 until bar.childCount) {
                val c = bar.getChildAt(i)
                if (c === btn || c.visibility != View.VISIBLE || c.width <= 0) continue
                if (c.tag == "ptv_refresh_button" || c.tag == "ptv_sleep_timer" || c.tag == "ptv_filters_button" ||
                    ROW.any { n -> Res.id(ctx, n).let { it != 0 && it == c.id } }) row.add(c)
            }
            val ref = sizeRef(bar, row)
            if (ref == null || !live || row.isEmpty()) { btn?.visibility = View.GONE; continue }
            if (btn == null) {
                btn = ImageView(bar.context).apply {
                    tag = TAG
                    setImageDrawable(SaveIcon())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
                    contentDescription = "Clip and save to phone"
                    setOnClickListener { start(it.context) }
                }
                bar.addView(btn, ViewGroup.LayoutParams(ref.height, ref.height))
            }
            matchSize(btn, ref)
            val x = row.minOf { it.x } - ref.height
            if (btn.x != x) btn.x = x
            if (btn.y != ref.y) btn.y = ref.y
            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
        }
    }

    private val ROW = arrayOf("create_clip_text_button", "create_clip_button_compose_view", "switch_to_vertical_view",
        "cast_button", "media_route_button", "share_button", "settings_button", "audio_and_subtitles")

    private fun collectBars(v: View, settingsId: Int, out: MutableList<ViewGroup>) {
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out && Res.isPlayerBar(it)) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectBars(v.getChildAt(i), settingsId, out)
    }

    /** A clapper board with a down arrow: clip, then save. */
    private class SaveIcon : FilterIcons.Icon() {
        override fun paint(c: Canvas, s: Float) {
            c.drawRoundRect(RectF(s * 0.16f, s * 0.36f, s * 0.84f, s * 0.84f), s * 0.06f, s * 0.06f, stroke)
            val top = Path().apply {
                moveTo(s * 0.16f, s * 0.30f); lineTo(s * 0.80f, s * 0.14f)
            }
            c.drawPath(top, stroke)
            val arrow = Path().apply {
                moveTo(s * 0.5f, s * 0.46f); lineTo(s * 0.5f, s * 0.72f)
                moveTo(s * 0.39f, s * 0.61f); lineTo(s * 0.5f, s * 0.73f); lineTo(s * 0.61f, s * 0.61f)
            }
            c.drawPath(arrow, stroke)
        }
    }

    // ------------------------------------------------------------ making and saving the clip

    /** Also used by DevBridge for a test without the button. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (busy) { toast(app, "Still saving the last clip"); return }
        val channel = EmoteRepo.currentChannel()
        if (channel.isNullOrBlank() || RefreshButton.liveLogin() == null) { toast(app, "Clips only work on a live stream"); return }
        busy = true
        toast(app, "Making a clip…")
        Thread({
            // Reading the sign-in opens every preferences file: never on the main thread.
            val token = EmoteRepo.authToken(app)
            val msg = if (token.isBlank()) "Sign in to Twitch to make clips" else runCatching { run(app, token, channel) }.getOrElse {
                log("CS failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
                it.message?.takeIf { m -> m.startsWith("!") }?.drop(1) ?: "Could not save the clip"
            }
            busy = false
            main.post { toast(app, msg) }
        }, "ptv-clip").start()
    }

    /** Blocking: returns the message for the user. Errors meant for the user start with "!". */
    private fun run(ctx: Context, token: String, channel: String): String {
        val slug = createHelix(token, channel) ?: createGql(token, channel)
            ?: error("!Twitch did not make the clip. Clips may be off on this channel")
        log("CS clip made: $slug")
        return save(ctx, token, slug)
    }

    /** Test command: save a clip that already exists, without making a new one. */
    fun devSave(ctx: Context, slug: String) {
        val app = ctx.applicationContext
        Thread({
            val token = EmoteRepo.authToken(app)
            val msg = runCatching { save(app, token, slug) }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}" }
            log("CS dev save $slug: $msg")
        }, "ptv-clip").start()
    }

    private fun save(ctx: Context, token: String, slug: String): String {
        var url: String? = null
        for (i in 0 until POLL_TRIES) {
            Thread.sleep(POLL_MS)
            url = mp4Url(token, slug)
            if (url != null) break
        }
        url ?: error("!Twitch is still making the clip. Try again from its page later")
        main.post { toast(ctx, "Saving the clip…") }
        val tmp = File(ctx.cacheDir, "ptv_clip_$slug.mp4")
        tmp.delete()
        // A clip made a moment ago is listed before its file is ready, so a refused download is
        // tried again a little later, with a freshly signed link.
        var link: String = url
        var failure: Throwable? = null
        for (i in 0 until DOWNLOAD_TRIES) {
            failure = runCatching { EmoteRepo.httpDownload(link, tmp) { _, _ -> } }.exceptionOrNull() ?: break
            tmp.delete()
            if (i == DOWNLOAD_TRIES - 1) break
            Thread.sleep(POLL_MS)
            link = mp4Url(token, slug) ?: link
        }
        // The message names the signed link; only the status before it goes to the log.
        failure?.let { error("download: ${it.message?.substringBefore(" for ") ?: it.javaClass.simpleName}") }
        val name = "${RefreshButton.liveLogin() ?: "twitch"}_${slug.take(40).replace(Regex("[^A-Za-z0-9_-]"), "_")}.mp4"
        val where = runCatching { publish(ctx, tmp, name) }.also { tmp.delete() }.getOrThrow()
        log("CS saved to $where")
        return "Clip saved to $where"
    }

    /** Helix: POST /clips. Gives the clip id (its slug), or null when the sign-in may not. */
    private fun createHelix(token: String, channel: String): String? {
        val (code, body) = EmoteRepo.helixPost(token, "clips?broadcaster_id=" + enc(channel)) ?: return null
        if (code !in 200..299) { log("CS helix HTTP $code"); return null }
        return runCatching {
            JSONObject(body ?: "").getJSONArray("data").getJSONObject(0).getString("id")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** GQL createClip, the way the web player makes a clip at the current point of the broadcast. */
    private fun createGql(token: String, channel: String): String? {
        val q = "query{user(id:\"" + channel.filter { it.isDigit() } + "\"){stream{id createdAt}}}"
        val stream = runCatching {
            JSONObject(EmoteRepo.gqlPost(token, JSONObject().put("query", q).toString()) ?: "")
                .getJSONObject("data").getJSONObject("user").getJSONObject("stream")
        }.getOrNull() ?: return null
        val broadcast = stream.optString("id").takeIf { it.isNotBlank() } ?: return null
        val started = runCatching { java.time.Instant.parse(stream.getString("createdAt")).toEpochMilli() }.getOrNull()
        val offset = if (started != null) ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0) else 0
        val m = "mutation(\$i:CreateClipInput!){createClip(input:\$i){clip{slug} error{code}}}"
        val vars = JSONObject().put("i", JSONObject()
            .put("broadcasterID", channel).put("broadcastID", broadcast).put("offsetSeconds", offset))
        val r = EmoteRepo.gqlPost(token, JSONObject().put("query", m).put("variables", vars).toString()) ?: return null
        return runCatching {
            val c = JSONObject(r).getJSONObject("data").getJSONObject("createClip")
            if (!c.isNull("error")) { log("CS gql error ${c.optJSONObject("error")?.optString("code")}"); null }
            else c.getJSONObject("clip").getString("slug")
        }.getOrElse { log("CS gql createClip: ${r.take(160)}"); null }
    }

    /** The best MP4 of a finished clip, signed; null while Twitch is still cutting it. */
    private fun mp4Url(token: String, slug: String): String? {
        val body = JSONObject()
            .put("operationName", "VideoAccessToken_Clip")
            .put("variables", JSONObject().put("slug", slug))
            .put("extensions", JSONObject().put("persistedQuery",
                JSONObject().put("version", 1).put("sha256Hash", CLIP_HASH)))
        val r = EmoteRepo.gqlPost(token, body.toString()) ?: return null
        return runCatching {
            val clip = JSONObject(r).getJSONObject("data").optJSONObject("clip") ?: return null
            val qs = clip.optJSONArray("videoQualities") ?: return null
            var best: String? = null; var bestQ = -1
            for (i in 0 until qs.length()) {
                val q = qs.getJSONObject(i)
                val n = q.optString("quality").filter { it.isDigit() }.toIntOrNull() ?: 0
                val u = q.optString("sourceURL")
                if (u.isNotBlank() && n > bestQ) { bestQ = n; best = u }
            }
            val tok = clip.optJSONObject("playbackAccessToken") ?: return null
            val b = best ?: return null
            b + (if ('?' in b) "&" else "?") + "sig=" + enc(tok.getString("signature")) + "&token=" + enc(tok.getString("value"))
        }.getOrNull()
    }

    /** Into the gallery: Movies/PurpleTV. Before Android 10 the app's own Movies folder is used. */
    private fun publish(ctx: Context, src: File, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PurpleTV")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: error("no gallery entry")
            try {
                cr.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } } ?: error("no gallery stream")
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) { runCatching { cr.delete(uri, null, null) }; throw t }
            return "Movies/PurpleTV"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PurpleTV").apply { mkdirs() }
        src.copyTo(File(dir, name), overwrite = true)
        return dir.path
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
