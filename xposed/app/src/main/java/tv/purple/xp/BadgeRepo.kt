package tv.purple.xp

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Third-party chat badges (FFZ / Chatterino / DankChat / Chatsen / Homies / BTTV).
 *
 * Every supported provider ships a GLOBAL manifest keyed by numeric Twitch user id, so this is
 * fetch-once-at-startup: there is never a per-message or per-user HTTP call. That is the whole
 * reason these four were chosen.
 *
 * Endpoints re-verified live on 2026-07-27:
 *   FFZ        api.frankerfacez.com/v1/badges/ids   { badges:[{id,urls}], users:{ "<bid>":[uid,..] } }
 *   Chatterino api.chatterino.com/badges            { badges:[{tooltip,image2,users:["uid",..]}] }
 *   DankChat   flxrs.com/api/badges                 [ {type,url,users:["uid",..]} ]
 *   Chatsen    raw.githubusercontent.com/chatsen/…  { badges:[{name,image}], users:[{id,badges:[{badgeName}]}] }
 *   Homies     chatterinohomies.com/api/badges/list { badges:[{userId,tooltip,image1,image2,..}] } (2026-10-19)
 *   BTTV       api.betterttv.net/3/cached/badges/twitch [ {providerId, badge:{description,svg}} ] (2026-10-25)
 *
 * Homies is the odd one out: almost every badge is a personal one (about 9.6k users, 9.6k
 * images), so its images are NOT prefetched. The first message from a Homies user starts a
 * download of just that user's image, and the badge shows from their next message on.
 *
 * NOT supported, deliberately:
 *   (BTTV's images are SVG, which Android cannot decode; SvgBadge draws them instead.)
 *   7TV   — the bulk cosmetics endpoint (7tv.io/v3/cosmetics) returns 404. The only route left is
 *           one request per distinct chatter, which is exactly what this design refuses to do.
 *
 * Sizing is deliberately NOT shared with EmoteRepo: emotes render at 28dp, Twitch's own chat
 * badges are ~18dp, and reusing the emote height makes third-party badges tower over native ones.
 */
object BadgeRepo {

    const val KEY_FFZ = "ffz_badges"
    const val KEY_CHA = "cha_badges"
    const val KEY_DANK = "dankchat_badges"
    const val KEY_CHATSEN = "chatsen_badges"
    const val KEY_HOMIES = "homies_badges"
    const val KEY_BTTV = "bttv_badges"

    private val ALL_SRC = listOf("ffz" to KEY_FFZ, "cha" to KEY_CHA,
        "dank" to KEY_DANK, "chatsen" to KEY_CHATSEN, "homies" to KEY_HOMIES, "bttv" to KEY_BTTV)

    /** Sources whose images are fetched on first sight instead of up front. */
    private val LAZY = setOf("homies")

    /** A badge a user owns. [url] doubles as the image cache key. */
    class Badge(val src: String, val title: String, val url: String)

    /** userId -> ordered badge list. Written by the fetch thread, read from the render path. */
    private val byUser = ConcurrentHashMap<String, MutableList<Badge>>()

    /** url -> static ready-to-draw Drawable (bounds set). */
    private val static = ConcurrentHashMap<String, Drawable>()

    /** Source bytes for ANIMATED badges (DankChat and Chatsen both ship GIFs), keyed by url.
     *  An AnimatedImageDrawable is stateful — one frame cursor, one callback — so it cannot be
     *  shared across chat lines. Same approach as EmoteRepo.animBytes: keep the bytes, mint a
     *  fresh drawable per span. */
    private val animBytes = ConcurrentHashMap<String, ByteArray>()
    private val animBounds = ConcurrentHashMap<String, IntArray>()

    /** Badge row height in px, ~18dp to match Twitch's own badges. */
    @Volatile private var heightPx = 48

    /** Sources whose manifest has been fetched, so toggling one on later fetches only that one. */
    private val loaded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun enabled(src: String): Boolean = when (src) {
        "ffz" -> Settings.get(KEY_FFZ, true)
        "cha" -> Settings.get(KEY_CHA, true)
        "dank" -> Settings.get(KEY_DANK, true)
        "chatsen" -> Settings.get(KEY_CHATSEN, true)
        "homies" -> Settings.get(KEY_HOMIES, false)
        "bttv" -> Settings.get(KEY_BTTV, true)
        else -> false
    }

    fun anyEnabled(): Boolean = ALL_SRC.any { enabled(it.first) }

    /**
     * Ready-to-draw badges for [userId], honouring the per-source toggles. Never blocks and never
     * hits the network: a badge whose image hasn't been prefetched yet is simply skipped and will
     * appear on that user's next message.
     */
    fun forUser(userId: String): List<Drawable> {
        if (userId.isEmpty()) return emptyList()
        val list = byUser[userId] ?: return emptyList()
        val out = ArrayList<Drawable>(list.size)
        // Copy under the list's own lock: the fetch thread may still be appending.
        val snapshot = synchronized(list) { list.toList() }
        for (b in snapshot) {
            if (!enabled(b.src)) continue
            val d = makeAnimated(b.url) ?: static[b.url]
            if (d == null) { if (b.src in LAZY) fetchLater(b.url); continue }
            out.add(d)
        }
        return out
    }

    /** Fetch every enabled provider's manifest, then its images. Safe to call repeatedly. */
    fun loadAsync() {
        if (!anyEnabled()) return
        heightPx = (18f * Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(24)
        Thread({
            var fetched = false
            var failed = false
            for ((src, _) in ALL_SRC) {
                if (!enabled(src) || !loaded.add(src)) continue
                fetched = true
                runCatching {
                    when (src) {
                        "ffz" -> loadFfz()
                        "cha" -> loadChatterino()
                        "dank" -> loadDankChat()
                        "chatsen" -> loadChatsen()
                        "homies" -> loadHomies()
                        "bttv" -> loadBttv()
                    }
                }.onFailure { failed = true; loaded.remove(src); log("BADGE $src manifest failed: $it") }
            }
            // A server that timed out is asked again later, so its badges come back without a restart.
            if (failed && retries < RETRY_S.size) {
                val wait = RETRY_S[retries++]
                Thread({ runCatching { Thread.sleep(wait * 1000L) }; loadAsync() }, "ptv-badge-retry").apply { isDaemon = true }.start()
            }
            if (!fetched) return@Thread
            log("BADGE manifests ready: ${byUser.size} users tagged; prefetching images")
            prefetch()
            log("BADGE images ready: ${static.size}")
            // End-to-end self-test: only ~43k accounts worldwide carry a third-party badge, so a
            // quiet channel proves nothing either way. Resolve a few ids we KNOW are tagged and
            // report how many ready-to-draw badges come back, which exercises the whole path
            // (manifest -> toggle gate -> prefetched drawable) without needing the right chatter.
            runCatching {
                for (id in byUser.keys.take(3)) {
                    val list = byUser[id]
                    log("BADGE selftest user=$id mapped=${list?.size ?: 0} drawable=${forUser(id).size}")
                }
            }
        }, "ptv-badge-fetch").apply { isDaemon = true }.start()
    }

    private val RETRY_S = longArrayOf(30, 90, 300)
    @Volatile private var retries = 0

    /** Called when a badge toggle flips, so switching a source on fetches it without a restart. */
    fun refresh() = loadAsync()

    private fun add(userId: String, b: Badge) {
        // Chatterino's user lists carry sentinels ("-1") and even YouTube channel ids; every
        // provider here keys on a numeric Twitch id, so anything else is not addressable.
        if (userId.isEmpty() || userId == "-1" || !userId.all { it.isDigit() }) return
        byUser.getOrPut(userId) { java.util.Collections.synchronizedList(ArrayList(2)) }
            .let { synchronized(it) { it.add(b) } }
    }

    // { "badges":[{id,title,urls:{"1","2","4"}}], "users":{ "<badgeId>":[uid,...] } }
    // NOTE: /v1/badges/ids, not /v1/badges — the latter keys its user lists by LOGIN.
    private fun loadFfz() {
        val root = JSONObject(EmoteRepo.httpGet("https://api.frankerfacez.com/v1/badges/ids"))
        val meta = HashMap<String, Badge>()
        val arr = root.optJSONArray("badges") ?: return
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val raw = e.optJSONObject("urls")?.optString("2")?.takeIf { it.isNotEmpty() }
                ?: e.optString("image").takeIf { it.isNotEmpty() } ?: continue
            meta[e.optInt("id").toString()] = Badge(
                "ffz", e.optString("title"), if (raw.startsWith("//")) "https:$raw" else raw)
        }
        val users = root.optJSONObject("users") ?: return
        var n = 0
        for (bid in users.keys()) {
            val b = meta[bid] ?: continue
            val ids = users.optJSONArray(bid) ?: continue
            for (j in 0 until ids.length()) { add(ids.optLong(j).toString(), b); n++ }
        }
        log("BADGE ffz: ${meta.size} badges, $n assignments")
    }

    // { "badges":[ {tooltip, image1, image2, image3, users:["uid",...]} ] }
    private fun loadChatterino() {
        val arr = JSONObject(EmoteRepo.httpGet("https://api.chatterino.com/badges"))
            .optJSONArray("badges") ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val url = e.optString("image2").ifEmpty { e.optString("image1") }
            if (url.isEmpty()) continue
            val b = Badge("cha", e.optString("tooltip"), url)
            val users = e.optJSONArray("users") ?: continue
            for (j in 0 until users.length()) { add(users.optString(j), b); n++ }
        }
        log("BADGE cha: ${arr.length()} badges, $n assignments")
    }

    // [ { "type":"DuckerZ", "url":"…/ente.gif", "users":["uid",..] } ]
    private fun loadDankChat() {
        val arr = JSONArray(EmoteRepo.httpGet("https://flxrs.com/api/badges"))
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val url = e.optString("url"); if (url.isEmpty()) continue
            val b = Badge("dank", e.optString("type"), url)
            val users = e.optJSONArray("users") ?: continue
            for (j in 0 until users.length()) { add(users.optString(j), b); n++ }
        }
        log("BADGE dank: ${arr.length()} badges, $n assignments")
    }

    // { "badges":[{name,title,image}], "users":[ {id, badges:[{badgeName}]} ] }
    // Note the inverted shape vs the others: users is an ARRAY of user objects that each name
    // their badges, so the join runs user -> badgeName -> badge, not badge -> users.
    private fun loadChatsen() {
        val root = JSONObject(EmoteRepo.httpGet(
            "https://raw.githubusercontent.com/chatsen/resources/master/assets/data.json"))
        val meta = HashMap<String, Badge>()
        val defs = root.optJSONArray("badges") ?: return
        for (i in 0 until defs.length()) {
            val e = defs.optJSONObject(i) ?: continue
            val name = e.optString("name"); val url = e.optString("image")
            if (name.isEmpty() || url.isEmpty()) continue
            meta[name] = Badge("chatsen", e.optString("title").ifEmpty { name }, url)
        }
        val users = root.optJSONArray("users") ?: return
        var n = 0
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            val id = u.optString("id"); if (id.isEmpty()) continue
            val owned = u.optJSONArray("badges") ?: continue
            for (j in 0 until owned.length()) {
                val b = meta[owned.optJSONObject(j)?.optString("badgeName") ?: continue] ?: continue
                add(id, b); n++
            }
        }
        log("BADGE chatsen: ${meta.size} badges, $n assignments")
    }

    // { "badges":[ {userId, tooltip, image1, image2, image3, badgeFileType} ] }, one per user.
    private fun loadHomies() {
        val arr = JSONObject(EmoteRepo.httpGet("https://chatterinohomies.com/api/badges/list"))
            .optJSONArray("badges") ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val url = e.optString("image2").ifEmpty { e.optString("image1") }
            if (url.isEmpty()) continue
            add(e.optString("userId"), Badge("homies", e.optString("tooltip"), url)); n++
        }
        log("BADGE homies: $n assignments")
    }

    private val lazyPool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val inflight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lazyReady = java.util.concurrent.atomic.AtomicInteger()

    /** One background download per image, ever; a failure is not retried this session. */
    // [ {providerId, badge:{description, svg}} ]: one entry per badged user, only a few hundred.
    private fun loadBttv() {
        val arr = JSONArray(EmoteRepo.httpGet("https://api.betterttv.net/3/cached/badges/twitch"))
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val b = e.optJSONObject("badge") ?: continue
            val url = b.optString("svg").takeIf { it.isNotEmpty() } ?: continue
            add(e.optString("providerId"), Badge("bttv", b.optString("description"), url)); n++
        }
        log("BADGE bttv: $n assignments")
    }

    private fun fetchLater(url: String) {
        if (!inflight.add(url)) return
        lazyPool.execute {
            runCatching {
                static[url] = build(url, EmoteRepo.httpBytes(url), Resources.getSystem())
                    ?: error("decode failed")
                val n = lazyReady.incrementAndGet()
                if (n <= 3 || n % 50 == 0) log("BADGE lazy image ready #$n")
            }.onFailure { log("BADGE img fail '$url': $it") }
        }
    }

    /** Download + decode each DISTINCT badge image once. Small: all four providers together use
     *  well under a hundred images, however many users they cover. */
    private fun prefetch() {
        val res = Resources.getSystem()
        val seen = HashSet<String>()
        for (list in byUser.values) {
            val snapshot = synchronized(list) { list.toList() }
            for (b in snapshot) {
                if (!enabled(b.src) || b.src in LAZY) continue
                if (!seen.add(b.url) || static.containsKey(b.url)) continue
                runCatching {
                    static[b.url] = build(b.url, EmoteRepo.httpBytes(b.url), res)
                        ?: error("decode failed")
                }.onFailure { log("BADGE img fail '${b.url}': $it") }
            }
        }
    }

    /**
     * Decode badge [bytes] to [heightPx], preserving aspect ratio (most badges are square, but
     * FFZ ships a few that aren't). Animated sources are cached as bytes and returned as a static
     * first frame, exactly like EmoteRepo does for animated emotes.
     */
    private fun build(url: String, bytes: ByteArray, res: Resources): Drawable? {
        if (SvgBadge.isSvg(bytes)) {
            val bmp = SvgBadge.render(bytes, heightPx) ?: return null
            // Hidden test switch: save what was drawn, to check it against the real badge.
            if (Settings.get("dev_dump_svg", false)) runCatching {
                val dir = Settings.ctx()?.getExternalFilesDir(null) ?: return@runCatching
                java.io.FileOutputStream(java.io.File(dir, "svg_" + url.substringAfterLast('/') + ".png"))
                    .use { SvgBadge.render(bytes, 256)?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            return BitmapDrawable(res, bmp).apply { setBounds(0, 0, heightPx, heightPx) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val d = runCatching {
                var tw = heightPx
                val decoded = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ) { dec, info, _ ->
                    val iw = info.size.width; val ih = info.size.height
                    tw = if (ih > 0) (heightPx * iw / ih) else heightPx
                    dec.setTargetSize(tw.coerceAtLeast(1), heightPx)
                }
                if (decoded is AnimatedImageDrawable) {
                    animBytes[url] = bytes
                    animBounds[url] = intArrayOf(tw.coerceAtLeast(1), heightPx)
                    val first = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                    BitmapDrawable(res, first).apply { setBounds(0, 0, tw.coerceAtLeast(1), heightPx) }
                } else {
                    decoded.apply { setBounds(0, 0, tw.coerceAtLeast(1), heightPx) }
                }
            }.getOrNull()
            if (d != null) return d
        }
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("decode failed")
            val tw = if (bmp.height > 0) (heightPx * bmp.width / bmp.height) else heightPx
            BitmapDrawable(res, bmp).apply { setBounds(0, 0, tw.coerceAtLeast(1), heightPx) }
        }.getOrNull()
    }

    /** Fresh independently-animating drawable for [url], or null if the badge isn't animated. */
    private fun makeAnimated(url: String): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val bytes = animBytes[url] ?: return null
        val b = animBounds[url]
        return runCatching {
            val d = ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ) { dec, _, _ -> if (b != null) dec.setTargetSize(b[0], b[1]) }
            if (d is AnimatedImageDrawable) {
                if (b != null) d.setBounds(0, 0, b[0], b[1])
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d
            } else null
        }.getOrNull()
    }
}
