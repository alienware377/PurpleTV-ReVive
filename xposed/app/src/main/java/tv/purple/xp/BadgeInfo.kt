package tv.purple.xp

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * Badge info sheet (issue #6): tap a badge in chat, chat history or VOD chat replay and a sheet
 * shows it bigger, with its name, where it comes from and a Copy name button.
 *
 * Our own badges (7TV, FFZ, BTTV, Chatterino and friends) carry their title and source on the
 * span. Twitch's badges only carry their picture: its drawable keeps the badge's CDN address in a
 * String field, found by value so no obfuscated name is pinned. That address is looked up in
 * Twitch's global badges and the current channel's own, fetched once each from GQL, which also
 * tells a channel badge from a global one.
 */
object BadgeInfo {

    const val KEY = "badge_info"

    fun enabled() = Settings.get(KEY, true)

    /** What was tapped. [title] is null for a Twitch badge until it has been looked up. */
    class Hit(val title: String?, val src: String, val url: String)

    private val UUID = Regex("/badges/v1/([0-9a-fA-F-]{36})")

    /**
     * Drawable class -> its own String fields. Twitch draws badges and emotes with the same class,
     * so which field holds a badge address cannot be learned from one picture: every field is
     * read each time, and only the list of fields is kept.
     */
    private val stringFields = ConcurrentHashMap<Class<*>, List<Field>>()

    private class Info(val title: String, val description: String?, val channel: Boolean)

    /** Badge uuid -> info. Globals load once, the channel's once per channel. */
    private val globals = ConcurrentHashMap<String, Info>()
    @Volatile private var globalsLoaded = false
    private val channel = ConcurrentHashMap<String, Info>()
    @Volatile private var channelFor: String? = null

    /** The Twitch badge address carried by [span]'s picture, or null if it is not a Twitch badge. */
    fun twitchUrl(span: ImageSpan): String? {
        val d = span.drawable ?: return null
        for (f in stringFields.getOrPut(d.javaClass) { fieldsOf(d.javaClass) }) {
            val u = runCatching { f.get(d) as? String }.getOrNull() ?: continue
            if (UUID.containsMatchIn(u)) return u
        }
        return null
    }

    private fun fieldsOf(cls: Class<*>): List<Field> {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Drawable::class.java && !c.name.startsWith("android.")) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java || java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                runCatching { f.isAccessible = true; out.add(f) }
            }
            c = c.superclass
        }
        return out
    }

    /** True if [cs] shows a badge a tap can explain. Cheap: run for every chat line drawn. */
    fun hasBadge(cs: Spanned): Boolean {
        if (!enabled()) return false
        for (sp in cs.getSpans(0, cs.length, ImageSpan::class.java)) {
            if (sp is ChatIdentity.PtvBadgeSpan) return true
            if (sp is EmoteHooks.PtvEmoteSpan) continue
            if (twitchUrl(sp) != null) return true
        }
        return false
    }

    /** The badge [sp] stands for, or null when it is not a badge. */
    fun hitOf(sp: ImageSpan): Hit? {
        if (sp is ChatIdentity.PtvBadgeSpan) return Hit(sp.title.ifBlank { null }, sp.src, sp.url)
        if (sp is EmoteHooks.PtvEmoteSpan) return null
        val u = twitchUrl(sp) ?: return null
        return Hit(null, "twitch", u)
    }

    private fun sourceName(src: String) = when (src) {
        "7tv" -> "7TV"
        "ffz" -> "FrankerFaceZ"
        "bttv" -> "BetterTTV"
        "cha" -> "Chatterino"
        "homies" -> "Chatterino Homies"
        "dank" -> "DankChat"
        "chatsen" -> "Chatsen"
        else -> "Twitch"
    }

    // ---- Twitch's own badge names ----

    private const val Q_GLOBAL =
        "query { badges { setID version title description imageURL(size: QUADRUPLE) } }"
    private const val Q_CHANNEL =
        "query(\$id: ID!) { user(id: \$id) { broadcastBadges { setID version title description imageURL(size: QUADRUPLE) } } }"

    private fun fill(into: MutableMap<String, Info>, list: org.json.JSONArray?, isChannel: Boolean) {
        list ?: return
        for (i in 0 until list.length()) {
            val b = list.optJSONObject(i) ?: continue
            val id = UUID.find(b.optString("imageURL"))?.groupValues?.get(1) ?: continue
            val title = b.optString("title").ifBlank { b.optString("setID") }
            into[id.lowercase()] = Info(title, b.optString("description").ifBlank { null }, isChannel)
        }
    }

    private fun query(ctx: Context, q: String, vars: JSONObject?): JSONObject? {
        val body = JSONObject().put("query", q).apply { if (vars != null) put("variables", vars) }.toString()
        val resp = EmoteRepo.gqlPost(EmoteRepo.authToken(ctx), body) ?: return null
        return runCatching { JSONObject(resp).optJSONObject("data") }.getOrNull()
    }

    /** Blocking: the name, description and kind of the Twitch badge at [url]. */
    private fun lookup(ctx: Context, url: String): Info? {
        val id = UUID.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        val ch = EmoteRepo.currentChannel()
        if (ch != null && ch != channelFor) {
            channel.clear()
            query(ctx, Q_CHANNEL, JSONObject().put("id", ch))?.optJSONObject("user")?.let {
                fill(channel, it.optJSONArray("broadcastBadges"), true)
                channelFor = ch
            }
        }
        channel[id]?.let { return it }
        if (!globalsLoaded) {
            query(ctx, Q_GLOBAL, null)?.let { fill(globals, it.optJSONArray("badges"), false); globalsLoaded = true }
        }
        return globals[id]
    }

    // ---- the sheet ----

    fun show(anchor: View, hit: Hit) {
        val ctx = anchor.context ?: return
        runCatching {
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val source = if (hit.src == "twitch") "Twitch" else sourceName(hit.src)
            // A Twitch badge's name is only known once it has been looked up; until then (or if
            // that fails) there is no name to copy.
            var known = hit.title != null
            val title = TextView(ctx).apply {
                text = hit.title ?: "$source badge"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                paint.isFakeBoldText = true
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }
            val kind = TextView(ctx).apply {
                text = if (hit.src == "twitch") "Twitch badge" else "${sourceName(hit.src)} badge"
                setTextColor(Color.parseColor("#ADADB8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setPadding(0, dp(ctx, 2f), 0, 0)
            }
            val about = TextView(ctx).apply {
                setTextColor(Color.parseColor("#ADADB8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(0, dp(ctx, 12f), 0, 0)
                visibility = View.GONE
            }
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(img, LinearLayout.LayoutParams(dp(ctx, 64f), dp(ctx, 64f)).apply { rightMargin = dp(ctx, 16f) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title); addView(kind)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val dialog = Dialog(ctx)
            val copy = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 14f), 0, dp(ctx, 14f))
                isClickable = true
                contentDescription = "Copy name"
                addView(View(ctx).apply { background = CopyIcon(dp(ctx, 1.8f).toFloat()) },
                    LinearLayout.LayoutParams(dp(ctx, 22f), dp(ctx, 22f)).apply { rightMargin = dp(ctx, 18f) })
                addView(TextView(ctx).apply {
                    text = "Copy name"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                })
                setOnClickListener {
                    if (!known) { toast(ctx, "This badge's name isn't known yet"); return@setOnClickListener }
                    val name = title.text.toString()
                    runCatching {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Badge name", name))
                    }
                    toast(ctx, "Copied $name")
                    runCatching { dialog.dismiss() }
                }
            }
            val sheet = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 24f), dp(ctx, 10f), dp(ctx, 24f), dp(ctx, 8f))
                background = GradientDrawable().apply {
                    val r = dp(ctx, 18f).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    setColor(Color.parseColor("#1F1F23"))
                }
                addView(View(ctx).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(ctx, 2f).toFloat(); setColor(Color.parseColor("#53535F")) }
                }, LinearLayout.LayoutParams(dp(ctx, 36f), dp(ctx, 4f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(ctx, 18f)
                })
                addView(head)
                addView(about)
                addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#2F2F35")) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f)).apply { topMargin = dp(ctx, 18f) })
                addView(copy)
            }
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(sheet)
            dialog.setCanceledOnTouchOutside(true)
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.BOTTOM)
                setLayout(sheetWidth(ctx), ViewGroup.LayoutParams.WRAP_CONTENT)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.45f)
            }
            dialog.show()

            Thread({
                val info = if (hit.src == "twitch") runCatching { lookup(ctx, hit.url) }.getOrNull() else null
                val big = runCatching { EmoteRepo.loadPreviewUrl(hit.url) }.getOrNull()?.let { d ->
                    BadgeRepo.backgroundOf(hit.url)?.let { BadgeRepo.withBackground(d, it, ctx.resources) } ?: d
                }
                // Through the main thread, not the chat row: the row may have scrolled away.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!dialog.isShowing) return@post
                    if (big != null) { img.setImageDrawable(big); AnimPause.onImage(img, big) }
                    if (info != null) {
                        known = true
                        title.text = info.title
                        kind.text = if (info.channel) "Twitch channel badge" else "Twitch global badge"
                        if (!info.description.isNullOrBlank() && info.description != info.title) {
                            about.text = info.description; about.visibility = View.VISIBLE
                        }
                    }
                }
            }, "ptv-badge-info").start()
            log("badge info opened: src=${hit.src} title=${hit.title}")
        }.onFailure { log("badge info error: $it") }
    }

    /**
     * Test hook (DEV_BADGE): logs the badges on screen with the point a real tap would use, so a
     * test can tap one with `input tap`. Extras: src (only that source), open (int: open the sheet
     * for that badge directly).
     */
    fun dev(i: android.content.Intent) {
        val root = ViewHider.currentActivity()?.window?.decorView ?: run { log("DEV badge: no activity"); return }
        val only = i.getStringExtra("src")
        val open = i.getIntExtra("open", -1)
        val links = i.getBooleanExtra("links", false)
        var n = 0
        fun walk(v: View) {
            if (n >= 40) return
            if (links && v is TextView && v.isShown) {
                val cs = v.text as? Spanned
                val layout = v.layout
                if (cs != null && layout != null) for (sp in cs.getSpans(0, cs.length, android.text.style.ClickableSpan::class.java)) {
                    val st = cs.getSpanStart(sp); val en = cs.getSpanEnd(sp)
                    val line = layout.getLineForOffset(st)
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    val ox = loc[0] + v.totalPaddingLeft - v.scrollX
                    val y = loc[1] + v.totalPaddingTop - v.scrollY + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
                    log("DEV link #$n ${sp.javaClass.name} '${cs.subSequence(st, en)}' x=${(ox + layout.getPrimaryHorizontal(st)).toInt()}..${(ox + layout.getPrimaryHorizontal(en)).toInt()} y=$y after='${cs.subSequence(en, minOf(cs.length, en + 12))}'")
                    n++
                }
            }
            if (links) { if (v is ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k)); return }
            if (v is TextView && v.isShown) {
                val cs = v.text as? Spanned
                val layout = v.layout
                if (cs != null && layout != null) for (sp in cs.getSpans(0, cs.length, ImageSpan::class.java)) {
                    val h = hitOf(sp) ?: continue
                    if (only != null && h.src != only) continue
                    val st = cs.getSpanStart(sp); val en = cs.getSpanEnd(sp)
                    val line = layout.getLineForOffset(st)
                    val x = (layout.getPrimaryHorizontal(st) + layout.getPrimaryHorizontal(en)) / 2 + v.totalPaddingLeft - v.scrollX
                    val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + v.totalPaddingTop - v.scrollY
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    val chain = generateSequence(v.parent) { it.parent }.take(4)
                        .joinToString(">") { p -> (p as? ViewGroup)?.javaClass?.name?.substringAfterLast('.') ?: "?" }
                    log("DEV badge #$n src=${h.src} title=${h.title} url=${h.url.take(80)} tap=${(loc[0] + x).toInt()},${(loc[1] + y).toInt()} " +
                        "wired=${EmoteHooks.isWired(v)} hasBadge=${hasBadge(cs)} tv=${v.javaClass.name.substringAfterLast('.')} parents=$chain " +
                        "listener=${touchListenerOf(v)?.javaClass?.name} prev=${EmoteHooks.prevOf(v)?.javaClass?.name} " +
                        "movement=${v.movementMethod?.javaClass?.name} clickable=${cs.getSpans(0, cs.length, android.text.style.ClickableSpan::class.java).joinToString { it.javaClass.name }}")
                    if (n == open) show(v, h)
                    // Extra "press": hold the finger on this badge for that many ms, through the
                    // row's own touch handling (ours, then Twitch's).
                    val press = i.getIntExtra("press", -1)
                    if (press >= 0 && n == i.getIntExtra("at", 0)) {
                        val px = x; val py = y
                        val t0 = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, px, py, 0)
                        v.dispatchTouchEvent(down); down.recycle()
                        v.postDelayed({
                            val up = android.view.MotionEvent.obtain(t0, android.os.SystemClock.uptimeMillis(),
                                android.view.MotionEvent.ACTION_UP, px, py, 0)
                            v.dispatchTouchEvent(up); up.recycle()
                            log("DEV badge pressed #$n for ${press}ms")
                        }, press.toLong())
                    }
                    n++
                }
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) walk(v.getChildAt(k))
        }
        walk(root)
        log("DEV badge: $n found")
    }

    private fun touchListenerOf(v: View): Any? = runCatching {
        val li = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(v)
        li?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }?.get(li)
    }.getOrNull()

    /** Full width on a phone, a comfortable column on a tablet or in landscape. */
    private fun sheetWidth(ctx: Context): Int {
        val m = ctx.resources.displayMetrics
        val max = dp(ctx, 520f)
        return if (m.widthPixels > max) max else ViewGroup.LayoutParams.MATCH_PARENT
    }

    /** Two overlapping rounded squares, the usual copy icon. */
    private class CopyIcon(private val stroke: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = Color.WHITE
        }
        override fun draw(c: Canvas) {
            val b = bounds
            val s = b.width() * 0.66f
            val r = b.width() * 0.14f
            val h = stroke / 2
            c.drawRoundRect(RectF(b.left + h, b.top + h, b.left + s, b.top + s), r, r, p)
            c.drawRoundRect(RectF(b.right - s, b.bottom - s, b.right - h, b.bottom - h), r, r, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
