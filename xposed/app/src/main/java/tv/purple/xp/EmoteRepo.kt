package tv.purple.xp

import android.content.Context
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.nio.ByteBuffer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Loads one emote list into (name -> url, name -> source, name -> uploader) maps. */
private typealias ListLoad = (MutableMap<String, String>, MutableMap<String, String>, MutableMap<String, String>) -> Unit

/**
 * 7TV / BTTV / FFZ emote source (task #9 fetch half — trivial HTTP, obfuscation-immune).
 *
 * Holds a name -> full-image-URL map. Hooks read [map]; we populate global emotes on
 * startup and (later) channel emotes once a channel id is known. Names are matched
 * case-sensitively against whitespace-delimited words, exactly like native emotes.
 */
object EmoteRepo {
    val map = ConcurrentHashMap<String, String>()

    /** name -> source tag ("7tv" | "bttv" | "ffz") for the entries in [map], so the
     *  suggestion strip can honour the per-source Settings toggles. */
    val srcOf = ConcurrentHashMap<String, String>()
    /** Who uploaded each third-party emote, for the emote popup. */
    val uploaderOf = ConcurrentHashMap<String, String>()
    /** This channel's own third-party emote names (the rest are global). */
    @Volatile var chNames: Set<String> = emptySet()

    /** Names contributed by the 7TV GLOBAL set. Tracked separately so "7TV global emotes" can be
     *  switched off while channel 7TV emotes stay on. */
    val stvGlobal: MutableSet<String> = ConcurrentHashMap.newKeySet()

    const val KEY_STV_GLOBAL = "stv_global_emotes"
    const val KEY_HOMIES = "homies_emotes"

    /** Parsed Homies list. One small file holds the globals AND every channel's set. */
    @Volatile private var homies: JSONObject? = null

    /**
     * Whether [name] should render at all, given the per-source toggles.
     *
     * Used by BOTH the autocomplete strip and the chat injector, so switching a source off
     * removes those emotes from chat too — previously the toggles only filtered suggestions
     * while chat kept rendering them.
     */
    fun enabled(name: String): Boolean = when (srcOf[name]) {
        "7tv" -> Settings.get(Settings.KEY_SRC_SEVENTV) &&
            (name !in stvGlobal || Settings.get(KEY_STV_GLOBAL, true))
        "bttv" -> Settings.get(Settings.KEY_SRC_BTTV)
        "ffz" -> Settings.get(Settings.KEY_SRC_FFZ)
        "homies" -> Settings.get(KEY_HOMIES, false)
        else -> true
    }

    /** name -> ready-to-draw Drawable (bounds set). Populated by prefetch so injected
     *  ImageSpans render instantly without needing the host TextView for invalidation. */
    val drawables = ConcurrentHashMap<String, Drawable>()

    /** Twitch native emotes harvested from the app's emotes.db. Kept SEPARATE from [map]
     *  so we never draw our own ImageSpan over a span Twitch already renders natively.
     *  Used ONLY to feed the autocomplete suggestion strip. name -> CDN url. */
    val twitch = ConcurrentHashMap<String, String>()

    /** Twitch emotes known to belong to a CHANNEL (sub, follower, bits tier), as opposed to
     *  globals, hype train, Prime, Turbo and the like. Only orders the suggestion strip. */
    private val channelOwned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Twitch emotes known NOT to be channel emotes, so a later channel source cannot relabel them. */
    private val notChannel: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val CHANNEL_TYPES = setOf("SUBSCRIPTIONS", "FOLLOWER", "BITS_BADGE_TIERS")

    /** Drawables for the suggestion strip: union of [drawables] (7TV/BTTV/FFZ) and the
     *  Twitch harvest. Separate map so injection never picks these up. */
    val suggestDrawables = ConcurrentHashMap<String, Drawable>()

    /** Raw image bytes for emotes detected as ANIMATED (animated WebP / GIF). Keyed by name.
     *  A single AnimatedImageDrawable is STATEFUL (one callback, one frame cursor) so it can't be
     *  shared across chat lines — instead we keep the source bytes and decode a FRESH animated
     *  drawable per injected span ([makeAnimated]). The [drawables]/[suggestDrawables] maps still
     *  hold a static first-frame BitmapDrawable for these names as a fallback + size anchor. */
    val animBytes = ConcurrentHashMap<String, ByteArray>()
    /** name -> [width,height] px at our row height, for sizing freshly-decoded animated drawables. */
    private val animBounds = ConcurrentHashMap<String, IntArray>()
    /** name -> the url its cached picture was decoded from. Two channels often use the same word
     *  for different images, so a picture only counts if it came from the url in use now. */
    private val builtFrom = ConcurrentHashMap<String, String>()

    // ---- emote picker sections ----

    /** Global emote names in each provider's own order, as loaded. */
    private val globalOrder: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    /** This channel's emote names in provider order, and which channel they belong to. */
    @Volatile private var chOrder: List<String> = emptyList()
    @Volatile private var chOrderFor: String? = null
    @Volatile private var pickerVer = 0
    private val pickerListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addPickerListener(l: () -> Unit) { pickerListeners.add(l) }
    private fun bumpPicker() { pickerVer++; for (l in pickerListeners) runCatching { l() } }

    /** Changes whenever the lists or the source toggles change. */
    fun pickerVersion(): Int = pickerVer * 31 + listOf(Settings.KEY_SRC_SEVENTV, Settings.KEY_SRC_BTTV,
        Settings.KEY_SRC_FFZ, KEY_STV_GLOBAL, KEY_HOMIES).fold(0) { a, k -> a * 2 + if (Settings.get(k, k != KEY_HOMIES)) 1 else 0 }

    private val PROVIDER_TITLES = linkedMapOf("7tv" to "7TV", "bttv" to "BTTV", "ffz" to "FFZ", "homies" to "Homies")

    /**
     * Sections for the emote picker, in order: this channel's 7TV, BTTV, FFZ and Homies emotes,
     * then the global ones. Empty sections are left out; switched-off sources are left out.
     */
    fun pickerGroups(): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        val ch = if (chOrderFor != null && chOrderFor == activeChannel) chOrder else emptyList()
        val chSet = ch.toHashSet()
        for ((src, title) in PROVIDER_TITLES) {
            val names = ch.filter { srcOf[it] == src && enabled(it) && pickable(it) }
            if (names.isNotEmpty()) out += "$title channel emotes" to names
        }
        val globals = synchronized(globalOrder) { globalOrder.toList() }
        for ((src, title) in PROVIDER_TITLES) {
            val names = globals.filter { it !in chSet && srcOf[it] == src && enabled(it) && pickable(it) }.distinct()
            if (names.isNotEmpty()) out += "$title global emotes" to names
        }
        return out
    }

    /** Has a url that still exists. While a channel publishes, a name can be in [srcOf] for a
     *  moment with no url yet, and asking the [gone] set about a null url throws. */
    private fun pickable(name: String): Boolean = map[name]?.let { it !in gone } ?: false

    /** A drawable for one picker cell: a fresh animated copy, or a private copy of the static one. */
    fun pickerDrawable(name: String): Drawable? {
        makeAnimated(name)?.let { return it }
        val d = drawables[name] ?: suggestDrawables[name] ?: return null
        return d.constantState?.newDrawable()?.mutate() ?: d
    }

    /** The url [name] resolves to in the current chat, or null if it is not available here. */
    fun urlOf(name: String): String? = map[name] ?: twitch[name]

    /** Numeric id of the channel whose emotes are loaded, or null outside a channel. Also while a
     *  quality change reloads the emotes, so per-channel favorites and pins stay on that channel. */
    fun currentChannel(): String? = activeChannel ?: reloadingFor

    // --- global snapshots (authoritative GLOBAL-only emote set) ---
    // The public maps above are the LIVE combined view (globals + the CURRENT channel) that the
    // inject hook (EmoteHooks reads [drawables]) and [suggest] read. When the user switches
    // channels we rebuild the live maps from these snapshots in loadChannelAsync's publish, so the
    // previous channel's 7TV/BTTV/FFZ/Twitch emotes can never leak into a channel that doesn't
    // have them. Globals persist; only channel-scoped entries are dropped.
    private val gMap = ConcurrentHashMap<String, String>()
    private val gSrc = ConcurrentHashMap<String, String>()
    private val gUploader = ConcurrentHashMap<String, String>()
    private val gDraw = ConcurrentHashMap<String, Drawable>()
    private val gTwitch = ConcurrentHashMap<String, String>()
    private val gSuggestDraw = ConcurrentHashMap<String, Drawable>()
    /** Numeric id of the channel whose emotes are currently merged into the live maps. */
    @Volatile private var activeChannel: String? = null
    /** The channel whose emotes are in the live set; differs from [activeChannel] while loading. */
    @Volatile private var publishedFor: String? = null

    /** True while a channel's own emotes are still on their way. */
    fun channelPending(): Boolean = activeChannel != null && activeChannel != publishedFor
    /** Login of [activeChannel], kept so [refetch] can reload the same channel. */
    @Volatile private var activeName: String? = null
    /** The channel [refetch] is about to load again, while [activeChannel] is null for it. */
    @Volatile private var reloadingFor: String? = null
    /** Counts channel loads, so a late or retried list of an older load (another channel, or the
     *  same one before a quality change) knows it is no longer wanted. */
    private val chGen = java.util.concurrent.atomic.AtomicInteger()
    /** The load ([chGen]) whose channel is published while a list that puts emotes in chat is
     *  still on its way, or 0. Before the lists loaded side by side, the channel only published
     *  once every list was back, so chat lines waited for all of them. */
    private val lateGen = java.util.concurrent.atomic.AtomicInteger()

    /** True while the current channel is published but one of its 7TV/BTTV/FFZ/Homies lists has
     *  not joined yet. Lines drawn now may hold that list's words, so they are kept and filled
     *  again once it is in. */
    fun listsLate(): Boolean { val g = lateGen.get(); return g != 0 && g == chGen.get() }

    @Volatile private var globalsLoaded = false
    @Volatile private var twitchLoaded = false

    // Target emote height in px; set once we know screen density.
    @Volatile private var emoteHeightPx = 56

    /**
     * Built lazily so it can find the app's cache directory, which does not exist yet when this
     * object is first touched.
     *
     * Images go through a disk cache. Before this, every emote picture was downloaded again on
     * every launch, so a big channel spent minutes re-fetching hundreds of images it already had,
     * and on a slow connection the later ones were simply never ready.
     */
    private val http: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
        Settings.ctx()?.cacheDir?.let { dir ->
            // 512MB, measured rather than guessed: a logged in account's suggestion emotes plus a
            // big channel's set came to over 130MB, so a 128MB cap was evicting emotes as fast as
            // it saved them and many were missing again on the next launch. It is the app's cache
            // directory, which Android reclaims by itself when the device runs low on space.
            runCatching { b.cache(Cache(File(dir, "ptv-img"), 512L * 1024 * 1024)) }
            // The emote CDNs do not all send caching headers, and an uncacheable response is
            // never written to disk. Image responses are marked cacheable for a week, which is
            // safe because an emote's url changes whenever its picture does.
            b.addNetworkInterceptor { chain ->
                val r = chain.proceed(chain.request())
                val type = r.header("Content-Type").orEmpty()
                if (r.isSuccessful && type.startsWith("image/"))
                    r.newBuilder().header("Cache-Control", "public, max-age=604800")
                        .removeHeader("Pragma").build()
                else r
            }
        }
        b.build()
    }

    /**
     * Image downloads run several at a time instead of one after another. A large channel has
     * close to a thousand emotes, and fetching them in a single line meant the last ones could
     * take many minutes to appear.
     */
    private val imgPool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "ptv-img").apply { isDaemon = true }
    }

    /** Suggestion and favorites pictures get their own lane. There can be thousands of them (a
     *  logged in account's whole emote list), and sharing a queue with chat meant chat emotes
     *  waited behind every one of them before a single picture appeared in chat. */
    private val sugPool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "ptv-img-sug").apply { isDaemon = true }
    }

    /** A separate small lane for emotes chat is using right now, so they do not queue behind
     *  the thousand-odd background downloads a big channel kicks off. */
    private val hotPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ptv-img-hot").apply { isDaemon = true }
    }

    /** Image urls the CDN says no longer exist (404/410): an emote deleted upstream but still
     *  listed in a set. Never retried, since every chat line using it would otherwise refetch,
     *  and left out of suggestions, where it could only ever show as bare text. */
    private val gone: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun isGone(t: Throwable) =
        t.message?.let { it.startsWith("HTTP 404") || it.startsWith("HTTP 410") } == true

    /** Names whose download has actually STARTED. A task that is merely queued is not in here,
     *  which is what lets the fast lane overtake it. */
    private val inflight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * When lookups or connections start failing (the network waking up, a busy Private DNS), the
     * picture downloads pause instead of racing through the whole queue. Before this, one bad
     * moment turned into hundreds of failed lookups in two seconds, which also starved Twitch's
     * own requests: chat took half a minute to load on a phone. Pauses grow from 2 s to 60 s.
     */
    @Volatile private var netDownUntil = 0L
    @Volatile private var netBackoff = 2000L

    private fun isNetTrouble(t: Throwable) = t is java.net.UnknownHostException ||
        t is java.net.ConnectException || t is java.net.SocketTimeoutException || t is java.net.NoRouteToHostException

    private fun waitForNetwork() {
        val wait = netDownUntil - android.os.SystemClock.uptimeMillis()
        if (wait > 0) runCatching { Thread.sleep(wait.coerceAtMost(60_000)) }
    }

    private fun noteNetTrouble(t: Throwable) = synchronized(this) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now >= netDownUntil) {
            netDownUntil = now + netBackoff
            log("img: network trouble (${t.javaClass.simpleName}), pausing picture downloads ${netBackoff / 1000}s")
            netBackoff = (netBackoff * 2).coerceAtMost(60_000)
        }
    }

    /** Download and decode one emote, unless it is already here or already downloading. A queued
     *  background task re-checks at start, so if the fast lane got there first it just skips. */
    private fun fetch(name: String) {
        if (drawables.containsKey(name)) return
        var url = map[name] ?: return
        if (url in gone) return
        if (!inflight.add(name)) return
        try {
            var tries = 0
            var hops = 0
            while (true) {
                waitForNetwork()
                try {
                    val b = buildDrawable(getBytes(url), Resources.getSystem())
                    netBackoff = 2000L
                    // The channel or the quality can change while the picture downloads. It is only
                    // kept if the name still means this url: otherwise the old chat's picture stuck
                    // to the word, since nothing fetches a name that already has a picture.
                    val now = synchronized(publishLock) {
                        val cur = map[name]
                        if (cur == url && b != null) {
                            builtFrom[name] = url; drawables[name] = b.d; suggestDrawables.putIfAbsent(name, b.d)
                            keepAnim(name, b)
                        }
                        cur
                    }
                    // It means another picture now. The new chat's prefetch skipped this name while
                    // it was downloading here, so fetch that one instead of leaving it as text.
                    if (now == null || now == url || now in gone || drawables.containsKey(name) || ++hops > 3) break
                    url = now; tries = 0
                } catch (t: Throwable) {
                    if (isNetTrouble(t) && ++tries < 4) { noteNetTrouble(t); continue }
                    if (isGone(t)) gone.add(url)
                    if (isNetTrouble(t)) noteNetTrouble(t)
                    log("img fail '$name': $t")
                    break
                }
            }
        } finally {
            inflight.remove(name)
        }
    }

    /**
     * Make sure this emote's picture is on its way, at high priority.
     *
     * Called when chat actually uses an emote that has no picture yet, which is also what makes a
     * failed download recoverable. Previously a fetch that timed out was never tried again, so one
     * slow response left that emote as plain text for the rest of the session.
     */
    fun ensure(name: String) {
        if (drawables.containsKey(name) || inflight.contains(name) || !map.containsKey(name)) return
        hotPool.execute { fetch(name) }
    }

    /**
     * Drop every cached emote and re-run the loaders. Called when the quality dropdown changes,
     * since the size is baked into each CDN url at fetch time. The globals are reloaded first,
     * then the channel the user is in, which the channel dedup would otherwise skip and leave
     * with no channel emotes until the next channel switch.
     */
    fun refetch() {
        Thread({
            runCatching {
                // Under the lock, so a picture finishing its download right now cannot be stored
                // between its "same url still" check and these clears.
                val (ch, chName) = synchronized(publishLock) {
                    val c = activeChannel ?: reloadingFor
                    // Kept for currentChannel() while activeChannel is null, so per-channel
                    // favorites and pins do not fall back to the shared lists during the reload.
                    reloadingFor = c
                    activeChannel = null; publishedFor = null
                    map.clear(); srcOf.clear(); stvGlobal.clear(); uploaderOf.clear()
                    gMap.clear(); gSrc.clear(); gUploader.clear()
                    drawables.clear(); gDraw.clear(); gSuggestDraw.clear()
                    animBytes.clear(); animBounds.clear(); builtFrom.clear(); suggestDrawables.clear()
                    // Twitch emote urls have the size in them too, and their loaders only run once.
                    resizeTwitch(gTwitch); resizeTwitch(twitch)
                    homies = null
                    globalOrder.clear(); chOrder = emptyList()
                    c to activeName
                }
                globalsLoaded = true
                log("emote cache cleared; refetching at quality=${Settings.getInt(EmoteQuality.KEY, EmoteQuality.DEFAULT)}")
                val failed = runCatching { loadGlobalsNow() }.onFailure { log("refetch globals failed: $it") }
                    .getOrDefault(emptyList())
                // The channel goes back in before any retries: those can sleep for minutes, and the
                // channel had no emotes of its own all that time.
                if (ch != null && activeChannel == null) loadChannelAsync(ch, chName)
                if (failed.isNotEmpty()) retryGlobals(failed)
            }.onFailure { log("refetch failed: $it") }
        }, "ptv-emote-refetch").apply { isDaemon = true }.start()
    }

    private val TWITCH_SIZE = Regex("^(https://static-cdn\\.jtvnw\\.net/emoticons/v[12]/.+/)[1-4]\\.0$")

    /** Point every Twitch CDN url in [m] at the size the quality setting asks for now. */
    private fun resizeTwitch(m: MutableMap<String, String>) {
        for ((n, u) in m) {
            val r = TWITCH_SIZE.find(u) ?: continue
            val sized = r.groupValues[1] + EmoteQuality.twitch
            if (sized != u) m[n] = sized
        }
    }

    fun loadGlobalsAsync() {
        if (globalsLoaded) return
        globalsLoaded = true
        Thread({
            val failed = loadGlobalsNow()
            if (failed.isNotEmpty()) retryGlobals(failed)
        }, "ptv-emote-fetch").start()
    }

    /** Load the global sets and their pictures. Returns the sets whose server failed; the caller
     *  retries them, so [refetch] can put the channel back first. */
    private fun loadGlobalsNow(): List<Pair<String, ListLoad>> {
        emoteHeightPx = (28f * Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(36)
        val failed = ArrayList<Pair<String, ListLoad>>()
        // Built off the live maps and merged after each set. Written straight into the live map,
        // a channel that published meanwhile wiped the globals loaded so far, and its own emotes
        // were then snapshotted as globals and showed up in every chat for the session.
        val gm = HashMap<String, String>(); val gs = HashMap<String, String>(); val gu = HashMap<String, String>()
        runCatching { loadSevenTvGlobal(gm, gs, gu) }.onFailure { failed += "7TV" to ::loadSevenTvGlobal; log("7TV global failed: $it") }
        mergeGlobals(gm, gs, gu)
        runCatching { loadBttvGlobal(gm, gs, gu) }.onFailure { failed += "BTTV" to ::loadBttvGlobal; log("BTTV global failed: $it") }
        mergeGlobals(gm, gs, gu)
        runCatching { loadFfzGlobal(gm, gs, gu) }.onFailure { failed += "FFZ" to ::loadFfzGlobal; log("FFZ global failed: $it") }
        if (Settings.get(KEY_HOMIES, false))
            runCatching { loadHomies(null, gm, gs) }.onFailure { log("Homies global failed: $it") }
        mergeGlobals(gm, gs, gu)
        bumpPicker()
        log("emote map ready: ${gm.size} global emotes; prefetching images (h=${emoteHeightPx}px)")
        prefetchDrawables()
        snapshotGlobalPictures(gm.keys)
        log("emote drawables ready: ${drawables.size}/${map.size}")
        return failed
    }

    /**
     * Add global emotes to the global snapshot, and to the live set where the channel does not
     * use the name already. Under the publish lock, so a channel publishing at the same time can
     * neither lose them nor be taken for globals. Returns the names new to the snapshot.
     */
    private fun mergeGlobals(gm: Map<String, String>, gs: Map<String, String>, gu: Map<String, String>): List<String> {
        val added = ArrayList<String>()
        synchronized(publishLock) {
            for ((n, u) in gm) {
                if (gMap.putIfAbsent(n, u) != null) continue
                added += n
                gs[n]?.let { gSrc[n] = it }
                gu[n]?.let { gUploader[n] = it }
                if (map.putIfAbsent(n, u) == null) {
                    gs[n]?.let { srcOf[n] = it }
                    gu[n]?.let { uploaderOf[n] = it }
                }
            }
        }
        return added
    }

    /** Keep the pictures of global [names] for every later channel, but only a picture made from
     *  the global url. A channel using the same word for its own picture must not leave that
     *  picture behind as the global one. */
    private fun snapshotGlobalPictures(names: Collection<String>) = synchronized(publishLock) {
        for (n in names) {
            val u = gMap[n] ?: continue
            val d = drawables[n] ?: continue
            if (builtFrom[n] != u) continue
            gDraw[n] = d; gSuggestDraw.putIfAbsent(n, d)
        }
    }

    /**
     * Ask again for the global sets whose server timed out at startup (FFZ is often slow), at
     * growing intervals, so they come back without restarting the app. New names join the global
     * snapshot, and the live set too unless the channel already uses that name.
     */
    private fun retryGlobals(failed: List<Pair<String, ListLoad>>) {
        val todo = ArrayList(failed)
        for (wait in longArrayOf(20, 60, 180, 600)) {
            runCatching { Thread.sleep(wait * 1000L) }
            val it = todo.iterator()
            while (it.hasNext()) {
                val (name, load) = it.next()
                // Into maps of its own: comparing the live map before and after also caught the
                // emotes of a channel published meanwhile, which then became globals.
                val gm = HashMap<String, String>(); val gs = HashMap<String, String>(); val gu = HashMap<String, String>()
                if (runCatching { load(gm, gs, gu) }.onFailure { log("$name global retry failed: $it") }.isFailure) continue
                it.remove()
                val added = mergeGlobals(gm, gs, gu)
                log("$name global back on retry: +${added.size}")
                bumpPicker()
                prefetchDrawables()
                snapshotGlobalPictures(added)
            }
            if (todo.isEmpty()) return
        }
    }

    /**
     * Harvest the user's Twitch native emotes from the app's Room DB `emotes.db`,
     * table `frequent_emotes(token TEXT = name, id TEXT = emote id, ...)`. Best-effort,
     * read-only. These feed ONLY the suggestion strip (never injected — Twitch draws them
     * natively). Twitch CDN: https://static-cdn.jtvnw.net/emoticons/v2/<id>/default/dark/2.0
     */
    fun harvestTwitchAsync(ctx: Context) {
        if (twitchLoaded) return
        twitchLoaded = true
        Thread({
            runCatching { harvestTwitch(ctx) }.onFailure { log("twitch harvest failed: $it") }
            log("twitch emotes harvested: ${twitch.size}; prefetching suggestion drawables")
            prefetchSuggestDrawables()
            log("suggest drawables ready: ${suggestDrawables.size}")
        }, "ptv-twitch-harvest").start()
    }

    private fun harvestTwitch(ctx: Context) {
        val dbFile = ctx.getDatabasePath("emotes.db") ?: return
        // List sibling db files so we can find where the FULL owned/available emote set lives
        // (frequent_emotes only holds recently used emotes — empty for many users).
        runCatching {
            dbFile.parentFile?.listFiles()?.forEach {
                if (it.name.endsWith(".db")) log("db file: ${it.name} (${it.length()} bytes)")
            }
        }
        if (!dbFile.exists()) { log("emotes.db missing"); return }
        val got = HashMap<String, String>()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use { d ->
            // One-time schema discovery: every table + columns + row count.
            runCatching {
                d.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { tc ->
                    while (tc.moveToNext()) {
                        val t = tc.getString(0) ?: continue
                        val cols = runCatching {
                            d.rawQuery("PRAGMA table_info($t)", null).use { pc ->
                                val sb = StringBuilder()
                                while (pc.moveToNext()) { sb.append(pc.getString(1)); sb.append(',') }
                                sb.toString()
                            }
                        }.getOrDefault("?")
                        val cnt = runCatching {
                            d.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { cc ->
                                if (cc.moveToNext()) cc.getInt(0) else -1
                            }
                        }.getOrDefault(-1)
                        log("emotes.db table '$t' rows=$cnt cols=[$cols]")
                    }
                }
            }.onFailure { log("emotes.db schema dump failed: $it") }

            runCatching {
                d.rawQuery("SELECT token, id FROM frequent_emotes", null).use { cur ->
                    while (cur.moveToNext()) {
                        val name = cur.getString(0) ?: continue
                        val id = cur.getString(1) ?: continue
                        if (name.isEmpty() || id.isEmpty()) continue
                        got[name] =
                            "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/${EmoteQuality.twitch}"
                    }
                }
            }.onFailure { log("frequent_emotes query failed: $it") }
        }
        // Straight into the user-wide set. They only reached it by being copied over from the live
        // set later, so a channel that published first wiped them.
        addTwitchGlobals(got)
        log("twitch frequent_emotes: ${got.size}")
    }

    /** Add user-wide Twitch emotes: to the global snapshot, so channel switches keep them, and to
     *  the live set. Under the publish lock, like everything a channel publish rebuilds. */
    private fun addTwitchGlobals(m: Map<String, String>) = synchronized(publishLock) {
        for ((n, u) in m) { gTwitch.putIfAbsent(n, u); twitch.putIfAbsent(n, u) }
        keepTwitchPictures(m.keys)
    }

    /** Keep the pictures already here for user-wide Twitch emotes [names] across channel switches.
     *  Not for a name 7TV/BTTV/FFZ also use, whose picture here could be a channel's emote. */
    private fun keepTwitchPictures(names: Collection<String>) = synchronized(publishLock) {
        for (n in names) if (!map.containsKey(n)) suggestDrawables[n]?.let { gSuggestDraw.putIfAbsent(n, it) }
    }

    /**
     * Fetch the user-agnostic GLOBAL Twitch emote set from emotes.adamcy.pl (public, no auth).
     * Provider 0 == Twitch. These feed ONLY the suggestion strip (never injected — Twitch draws
     * its own emotes natively). Sends nothing user-specific. name -> CDN url (2x).
     */
    @Volatile private var twitchGlobalLoaded = false
    fun loadTwitchGlobalAsync() {
        if (twitchGlobalLoaded) return
        twitchGlobalLoaded = true
        Thread({
            val got = HashMap<String, String>()
            runCatching {
                val arr = JSONArray(get("https://emotes.adamcy.pl/v1/global/emotes/all"))
                addAdamcyTwitch(arr, got)
            }.onFailure { log("adamcy global failed: $it") }
            // Only these become user-wide. Copying the whole live set also copied the channel open
            // at the time, and its sub emotes were then suggested in every other channel.
            addTwitchGlobals(got)
            log("twitch global emotes: +${got.size} (total ${twitch.size}); prefetching")
            prefetchSuggestDrawables()
            log("suggest drawables ready: ${suggestDrawables.size}")
        }, "ptv-twitch-global").start()
    }

    // --- personal emote set via Twitch's own GQL (task #9: cross-channel subs/follower/bits) ---
    // adamcy gives a channel's PUBLIC emote set but cannot know which channels the USER is
    // subscribed to. To suggest the user's *personal* available emotes (every sub across every
    // channel, follower emotes, bits, Turbo) we read the app's own OAuth token from its
    // SharedPreferences (key authToken_v2, written by the host) and issue the same UserEmotes
    // GQL request the app itself makes on launch. The token is read locally and sent ONLY to
    // gql.twitch.tv — the exact endpoint the host already uses — and is never logged.
    //
    // Multi-user / public-release safe: the token belongs to whatever account is logged in on
    // THIS device; nothing is hardcoded. Re-read on every fetch so a re-login / refresh is picked
    // up. No-ops silently when logged out (empty token).
    private const val GQL_URL = "https://gql.twitch.tv/gql"
    private const val GQL_CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp"
    private const val USER_EMOTES_QUERY =
        "query UserEmotes { currentUser { id emoteSets { __typename ...EmoteSetFragment } } }  " +
        "fragment EmoteFragment on Emote { id setID token assetType type }  " +
        "fragment EmoteOwnerFragment on User { id login displayName profileImageURL(width: 28) }  " +
        "fragment EmoteSetFragment on EmoteSet { id emotes { __typename ...EmoteFragment modifiers { code } } owner { __typename ...EmoteOwnerFragment } }"

    @Volatile private var personalLoaded = false

    /** Fetch the logged-in user's full personal Twitch emote set and merge into the suggestion
     *  set (suggestion-only — Twitch renders these natively). User-wide, so it lives in the
     *  global Twitch snapshot and survives channel switches. Gated at suggest() by KEY_SRC_TWITCH. */
    fun loadPersonalEmotesAsync(ctx: Context) {
        if (personalLoaded) return
        personalLoaded = true
        Thread({
            // A dropped connection at startup (a sleeping tablet, a VPN reconnecting) used to leave
            // every sub emote unknown for the whole session, so favorites said "not available".
            for (wait in longArrayOf(0, 15, 45, 120, 300, 900)) {
                if (wait > 0) runCatching { Thread.sleep(wait * 1000L) }
                val ok = runCatching {
                    val token = readAuthToken(ctx)
                    if (token.isBlank()) { log("personal emotes: not logged in (no token), skipped"); return@Thread }
                    val personal = HashMap<String, String>()
                    fetchUserEmotes(token, personal)
                    if (personal.isEmpty()) { log("personal emotes: none returned"); return@Thread }
                    val before = twitch.size
                    gTwitch.putAll(personal); twitch.putAll(personal)
                    log("personal emotes: +${twitch.size - before} (total twitch ${twitch.size}); prefetching")
                    runCatching { Favorites.channelChanged() }
                    // Only these names' pictures. Copying every picture also kept the open channel's
                    // emotes as if they were user-wide.
                    keepTwitchPictures(personal.keys)
                    prefetchSuggestDrawables()
                    log("suggest drawables ready: ${suggestDrawables.size}")
                }.onFailure { log("personal emotes failed (will try again): $it") }.isSuccess
                if (ok) return@Thread
            }
        }, "ptv-personal-emotes").start()
    }

    /** Twitch emotes another device knew (from the synced favorites), until this one learns them. */
    fun addKnownTwitch(urls: Map<String, String>) {
        for ((n, u) in urls) if (!twitch.containsKey(n) && !map.containsKey(n)) { gTwitch.putIfAbsent(n, u); twitch.putIfAbsent(n, u) }
    }

    /** The picture address of a Twitch emote this device knows, for sharing with the others. */
    fun twitchUrl(name: String): String? = if (map.containsKey(name)) null else twitch[name]

    /** Public OAuth-token accessor for sibling modules (e.g. channel-points auto-claim). Returns
     *  "" when logged out. The token is read locally and must only ever be sent to gql.twitch.tv. */
    fun authToken(ctx: Context): String = readAuthToken(ctx)

    /** POST a raw GQL operation body to Twitch's gateway with the host Client-ID + the user's
     *  OAuth token, reusing the shared HTTP client. Returns the response JSON string, or null on
     *  failure. The token is sent ONLY to gql.twitch.tv (the endpoint the host already uses) and
     *  is never logged. */
    fun gqlPost(token: String, jsonBody: String): String? = runCatching {
        val req = Request.Builder()
            .url(GQL_URL)
            .header("Client-ID", GQL_CLIENT_ID)
            .header("Authorization", "OAuth $token")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GQL HTTP ${r.code}")
            r.body?.string()
        }
    }.getOrElse { log("gqlPost failed: $it"); null }

    /** Read the host's OAuth token (SharedPreferences key authToken_v2) by scanning the app's own
     *  shared_prefs dir — no hardcoded file name, obfuscation-immune. Returns "" if absent. */
    private fun readAuthToken(ctx: Context): String {
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val files = dir.listFiles { f -> f.name.endsWith(".xml") } ?: return ""
        for (f in files) {
            val name = f.name.removeSuffix(".xml")
            val t = runCatching {
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).getString("authToken_v2", null)
            }.getOrNull()
            if (!t.isNullOrBlank()) return t
        }
        return ""
    }

    /** POST UserEmotes to Twitch GQL and collect name -> CDN url into [out]. */
    private fun fetchUserEmotes(token: String, out: MutableMap<String, String>) {
        val body = JSONObject()
            .put("operationName", "UserEmotes")
            .put("variables", JSONObject())
            .put("query", USER_EMOTES_QUERY)
            .toString()
        val req = Request.Builder()
            .url(GQL_URL)
            .header("Client-ID", GQL_CLIENT_ID)
            .header("Authorization", "OAuth $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GQL HTTP ${r.code}")
            r.body?.string() ?: error("empty GQL body")
        }
        val sets = JSONObject(resp).optJSONObject("data")
            ?.optJSONObject("currentUser")?.optJSONArray("emoteSets") ?: return
        for (i in 0 until sets.length()) {
            val emotes = sets.optJSONObject(i)?.optJSONArray("emotes") ?: continue
            for (j in 0 until emotes.length()) {
                val e = emotes.optJSONObject(j) ?: continue
                val name = e.optString("token"); val id = e.optString("id")
                if (name.isEmpty() || id.isEmpty()) continue
                out[name] = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/${EmoteQuality.twitch}"
                if (e.optString("type") in CHANNEL_TYPES) { channelOwned.add(name); notChannel.remove(name) }
                else { notChannel.add(name); channelOwned.remove(name) }
            }
        }
    }

    /** Fetch a channel's Twitch (provider 0) emote set from adamcy by LOGIN name into [out]. */
    private fun loadTwitchChannel(login: String, out: MutableMap<String, String>) {
        if (login.isBlank()) return
        runCatching {
            val arr = JSONArray(get("https://emotes.adamcy.pl/v1/channel/$login/emotes/all"))
            addAdamcyTwitch(arr, out)
            // A channel's own set: its emotes are channel emotes unless Twitch said otherwise.
            for (name in out.keys) if (name !in notChannel) channelOwned.add(name)
        }.onFailure { log("adamcy channel '$login' failed: $it") }
    }

    /** Parse an adamcy emote array, keeping only provider==0 (Twitch), picking the 2x url. */
    private fun addAdamcyTwitch(arr: JSONArray, out: MutableMap<String, String>) {
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optInt("provider", -1) != 0) continue
            val name = e.optString("code")
            if (name.isEmpty()) continue
            val urls = e.optJSONArray("urls") ?: continue
            var pick = ""; var fallback = ""
            for (j in 0 until urls.length()) {
                val u = urls.optJSONObject(j) ?: continue
                val url = u.optString("url"); if (url.isEmpty()) continue
                fallback = url
                if (u.optString("size") == EmoteQuality.adamcy) { pick = url; break }
            }
            val chosen = pick.ifEmpty { fallback }
            if (chosen.isNotEmpty()) out[name] = darkUrl(chosen)
        }
    }

    /**
     * Twitch's CDN keeps each emote for a light and a dark theme. Every other source gives the dark
     * one, but adamcy gives light, so on a streamer's own channel their emotes had a second url:
     * the moving picture decoded for the first was then dropped as another chat's, and favorites
     * stayed still there.
     */
    private fun darkUrl(u: String) =
        if (u.contains("static-cdn.jtvnw.net/emoticons/")) u.replace("/light/", "/dark/") else u

    /** A single autocomplete suggestion: emote name + optional preview drawable. */
    data class Sug(val name: String, val drawable: Drawable?, val pinned: Boolean = false)

    /**
     * Unified emote name suggestions across 7TV/BTTV/FFZ ([map]) and Twitch ([twitch]).
     * Case-insensitive; startsWith ranks before contains; deduped by name. No colon needed —
     * the caller passes the raw word the user is typing.
     */
    fun suggest(prefix: String, limit: Int = 30): List<Sug> {
        if (prefix.length < 2) return emptyList()
        val p = prefix.lowercase()
        val onTwitch = Settings.get(Settings.KEY_SRC_TWITCH)
        // Groups, in order: pinned, then 7TV/BTTV/FFZ/Homies, then channel (sub) emotes, then
        // every other Twitch emote (globals, hype train, Prime...). Within a group, names that
        // START with what was typed come first, then names that merely contain it; each part
        // alphabetical, ignoring case.
        val pins = Pins.all()
        val keyed = HashMap<String, Int>()   // name -> group
        for ((name, url) in map) {
            if (!enabled(name) || url in gone) continue
            if (name.lowercase().contains(p)) keyed[name] = 1
        }
        if (onTwitch) for (name in twitch.keys) {
            if (keyed.containsKey(name) || !name.lowercase().contains(p)) continue
            keyed[name] = if (name in channelOwned) 2 else 3
        }
        val order = compareBy<Map.Entry<String, Int>>(
            { if (it.key in pins) 0 else it.value },
            { if (it.key.lowercase().startsWith(p)) 0 else 1 },
            { it.key.lowercase() },
            { it.key })
        return keyed.entries.sortedWith(order).take(limit)
            .map { Sug(it.key, suggestDrawables[it.key], pinned = it.key in pins) }
    }

    /** Prefetch suggestion-strip drawables: 7TV/BTTV/FFZ (reuse [drawables]) + Twitch.
     *  If [guard] is non-null, aborts as soon as [activeChannel] != guard (user switched away). */
    /**
     * Fetch pictures for [names] right now, ahead of the background queue, and call [onReady]
     * (on a worker thread) each time one arrives. The background queue fetches thousands of
     * pictures in no particular order, so on a fresh install the few emotes actually on screen
     * (a suggestion list, the favorites bar) could wait minutes for theirs.
     */
    /** Test aid: where an emote name is known from. */
    fun debugInfo(name: String) = "twitch=${twitch.containsKey(name)} global=${gTwitch.containsKey(name)} 3p=${map.containsKey(name)} " +
        "channelOwned=${name in channelOwned} pic=${suggestDrawables.containsKey(name)} channel=$activeName twitchTotal=${twitch.size} " +
        "anim=${animBytes.containsKey(name)} built=${builtFrom[name]?.takeLast(40)} url=${urlOf(name)?.takeLast(40)}"

    fun wantPictures(names: Collection<String>, onReady: () -> Unit) {
        val res = Resources.getSystem()
        for (name in names) {
            if (suggestDrawables.containsKey(name)) continue
            val url = twitch[name] ?: map[name] ?: continue
            if (url in gone || !inflight.add("sug:$name")) continue
            hotPool.execute {
                try {
                    val b = buildDrawable(getBytes(url), res)
                    if (b != null && keepSuggestion(name, url, b)) onReady()
                } catch (t: Throwable) {
                    if (isGone(t)) gone.add(url)
                } finally {
                    inflight.remove("sug:$name")
                }
            }
        }
    }

    private fun prefetchSuggestDrawables(guard: String? = null) {
        for ((name, d) in drawables) suggestDrawables.putIfAbsent(name, d)
        val res = Resources.getSystem()
        for ((name, url) in twitch) {
            if (guard != null && activeChannel != guard) return
            if (suggestDrawables.containsKey(name)) continue
            if (!inflight.add("sug:$name")) continue
            sugPool.execute {
                try {
                    val b = buildDrawable(getBytes(url), res)
                    if (b != null) keepSuggestion(name, url, b)
                } catch (t: Throwable) {
                    log("sug img fail '$name': $t")
                } finally {
                    inflight.remove("sug:$name")
                }
            }
        }
    }

    /**
     * Store a suggestion picture downloaded from [url], unless [name] has meant another picture
     * since (a channel switch or a quality change). Returns whether it was kept.
     */
    private fun keepSuggestion(name: String, url: String, b: Built): Boolean = synchronized(publishLock) {
        if ((twitch[name] ?: map[name]) != url) return@synchronized false
        suggestDrawables[name] = b.d
        // User-wide emotes keep their picture across channel switches.
        if (gTwitch.containsKey(name)) gSuggestDraw[name] = b.d
        // Chat uses the same animation, so it is only kept when chat would use this url too.
        // Stored for a Twitch url under a 7TV name, chat showed the Twitch emote moving.
        if (urlOf(name) == url) { builtFrom[name] = url; keepAnim(name, b) }
        true
    }

    /** Store (or clear) the animation that goes with a picture just kept for [name], so it always
     *  belongs to the url in [builtFrom]. Called under [publishLock]. */
    private fun keepAnim(name: String, b: Built) {
        if (b.anim != null && b.bounds != null) { animBytes[name] = b.anim; animBounds[name] = b.bounds }
        else { animBytes.remove(name); animBounds.remove(name) }
    }

    // --- channel emotes (keyed by Twitch numeric channel id) ---
    private val publishLock = Any()

    /** Runs a channel's emote lists side by side. Threads are made as needed and reused. */
    private val chPool = Executors.newCachedThreadPool { r ->
        Thread(r, "ptv-ch-list").apply { isDaemon = true }
    }

    /** How long a channel waits for all its lists before it publishes the ones that are back. */
    private const val CH_WAIT_MS = 8_000L

    /** One channel emote list, loaded into maps of its own so the lists can load side by side.
     *  [gaps]: only adds names no other list took (Homies). [isTwitch]: goes to [twitch]. */
    private class ChList(val name: String, val gaps: Boolean = false, val isTwitch: Boolean = false,
                         val load: ListLoad) {
        val map = LinkedHashMap<String, String>()
        val src = HashMap<String, String>()
        val up = HashMap<String, String>()
        @Volatile var error: Throwable? = null
        private val done = java.util.concurrent.CountDownLatch(1)

        fun run() {
            try { load(map, src, up) } catch (t: Throwable) { error = t } finally { done.countDown() }
        }

        /** Waits until [until] (uptime ms) at most. True once it has finished. */
        fun waitUntil(until: Long): Boolean = runCatching {
            done.await((until - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        /** A fresh copy, for asking again. */
        fun again() = ChList(name, gaps, isTwitch, load)
    }

    /** Put [l]'s emotes into the channel's set: a later list wins a name two lists share, as when
     *  they loaded one after another, except a [ChList.gaps] list. */
    private fun mergeList(l: ChList, out: MutableMap<String, String>, src: MutableMap<String, String>,
                          up: MutableMap<String, String>) {
        for ((n, u) in l.map) {
            if (l.gaps && out.containsKey(n)) continue
            out[n] = u
            l.src[n]?.let { src[n] = it }
            l.up[n]?.let { up[n] = it }
        }
    }

    /**
     * Load a channel's 7TV/BTTV/FFZ + Twitch emote sets and make them the ONLY channel emotes
     * merged on top of the globals. Anchor: ChannelChatConnectionKey.channelId.
     *
     * CRITICAL (state-leak fix): emotes are built into LOCAL maps off the live ones, then
     * published ATOMICALLY (live = globals + this channel) only if this is still the active
     * channel. This prevents a previous channel's emotes from leaking into a channel that
     * doesn't have them — including the race where the OLD channel's slow image prefetch / Twitch
     * fetch finishes AFTER the user already switched. The chat-connect ctor fires in a ~13× burst
     * per channel; we dedup on [activeChannel] so only the first of a burst proceeds. That also
     * skips opening the same channel again, so a list that failed is asked again on a timer
     * instead ([finishChannel]).
     */
    fun loadChannelAsync(channelId: String, channelName: String?) {
        if (channelId.isBlank()) return
        if (channelId == activeChannel) return // same channel (ctor burst / no switch) — ignore
        activeChannel = channelId
        activeName = channelName
        reloadingFor = null
        val myGen = chGen.incrementAndGet()
        Thread({
            val myId = channelId
            // Every list loads at the same time, into maps of its own. One after another, a single
            // slow server (FFZ timing out after 10 s) held back the whole channel set.
            val lists = arrayListOf(
                ChList("7TV") { m, s, u -> loadSevenTvChannel(myId, m, s, u) },
                ChList("BTTV") { m, s, u -> loadBttvChannel(myId, m, s, u, channelName) },
                ChList("FFZ") { m, s, u -> loadFfzChannel(myId, m, s, u) })
            if (Settings.get(KEY_HOMIES, false))
                lists += ChList("Homies", gaps = true) { m, s, _ -> loadHomies(myId, m, s) }
            if (!channelName.isNullOrBlank())
                lists += ChList("Twitch", isTwitch = true) { m, _, _ -> loadTwitchChannel(channelName, m) }
            for (l in lists) chPool.execute { l.run() }
            // Build this channel's set in LOCAL maps: no writes to the live maps yet. Merged in
            // the fixed order above, so a name two lists share resolves as it always did.
            val cMap = LinkedHashMap<String, String>()   // keeps each provider's own order for the picker
            val cSrc = HashMap<String, String>()
            val cUp = HashMap<String, String>()
            val cTwitch = HashMap<String, String>()
            val late = ArrayList<ChList>()
            val failed = ArrayList<ChList>()
            val until = android.os.SystemClock.uptimeMillis() + CH_WAIT_MS
            for (l in lists) {
                if (!l.waitUntil(until)) { late += l; continue }
                val e = l.error
                when {
                    // A 404 just means the channel has no account there: nothing to ask again.
                    e != null -> if (isGone(e)) log("${l.name} ch: none for this channel") else { log("${l.name} ch failed: $e"); failed += l }
                    l.isTwitch -> cTwitch.putAll(l.map)
                    else -> mergeList(l, cMap, cSrc, cUp)
                }
            }
            if (late.isNotEmpty()) log("ch $myId: ${late.joinToString { it.name }} not back yet, they join when they are")
            if (activeChannel != myId || chGen.get() != myGen) { log("ch $myId superseded before publish, discarded"); return@Thread }
            // Publish atomically: live = globals + this channel only. Drops any prior channel.
            synchronized(publishLock) {
                // The generation too: after a quality change the same channel loads again, and
                // this older load must not publish urls of the old size over it.
                if (activeChannel != myId || chGen.get() != myGen) { log("ch $myId superseded at publish, discarded"); return@Thread }
                map.clear(); map.putAll(gMap); map.putAll(cMap)
                srcOf.clear(); srcOf.putAll(gSrc); srcOf.putAll(cSrc)
                uploaderOf.clear(); uploaderOf.putAll(gUploader); uploaderOf.putAll(cUp)
                chNames = cMap.keys.toSet()
                twitch.clear(); twitch.putAll(gTwitch); twitch.putAll(cTwitch)
                // Raised together with the publish, never after it: in between, a chat line would
                // see neither the channel loading nor a list late, and be given up on. Twitch's own
                // list never goes into chat lines, so it does not hold them.
                lateGen.set(if (late.any { !it.isTwitch }) myGen else 0)
                publishedFor = myId
                drawables.clear(); drawables.putAll(gDraw)
                suggestDrawables.clear(); suggestDrawables.putAll(gSuggestDraw)
                // A channel emote that reuses a global name has its own picture: drop the
                // global one so the channel's is fetched instead of the global staying put.
                for ((n, u) in cMap) if (gMap[n] != u) { drawables.remove(n); suggestDrawables.remove(n) }
                // Animated pictures decoded for a different url are another chat's. The still copy
                // goes with them, or the name would keep it and never be fetched again from the url
                // that moves.
                for (n in animBytes.keys) {
                    val from = builtFrom[n] ?: continue
                    if (from != urlOf(n)) {
                        animBytes.remove(n); animBounds.remove(n); builtFrom.remove(n)
                        if (!map.containsKey(n)) suggestDrawables.remove(n)
                    }
                }
                // Inside the lock, so a list arriving late always finds the order it adds to.
                chOrder = cMap.keys.toList(); chOrderFor = myId
            }
            bumpPicker()
            runCatching { Favorites.channelChanged() }
            runCatching { android.os.Handler(android.os.Looper.getMainLooper()).post { PinnedChat.channelChanged() } }
            log("channel $myId ($channelName) published: +${cMap.size} inject, +${cTwitch.size} twitch (map=${map.size}); prefetching")
            if (late.isNotEmpty() || failed.isNotEmpty()) Thread({
                try {
                    runCatching { finishChannel(myId, myGen, late, failed) }.onFailure { log("ch $myId late lists failed: $it") }
                } finally {
                    // However it ended, lines must not wait on this load any more. Only its own
                    // flag, though: a newer channel may have raised one of its own by now.
                    lateGen.compareAndSet(myGen, 0)
                }
            }, "ptv-ch-retry-$myId").apply { isDaemon = true }.start()
            // Download channel images into the live maps; aborts mid-loop if the user switches.
            // A short head start first, so Twitch's own chat and stream requests go out before
            // hundreds of picture downloads join them.
            runCatching { Thread.sleep(2500) }
            if (activeChannel != myId) return@Thread
            prefetchDrawables(myId)
            if (activeChannel != myId) return@Thread
            prefetchSuggestDrawables(myId)
            log("channel $myId drawables ready: ${drawables.size}/${map.size}")
        }, "ptv-ch-fetch-$channelId").start()
    }

    /**
     * The channel lists that missed the publish: the ones still loading join as they arrive, and
     * the ones whose server failed are asked again at growing intervals, like [retryGlobals].
     * Before, a failed list stayed missing until the user visited another channel, since opening
     * the same channel again is skipped. Stops as soon as another channel load starts.
     */
    private fun finishChannel(myId: String, myGen: Int, late: List<ChList>, failed: List<ChList>) {
        val todo = ArrayList(failed)
        for (l in late) {
            if (chGen.get() != myGen) return
            // Every request ends on its own connect or read timeout; this cap is only a backstop.
            if (!l.waitUntil(android.os.SystemClock.uptimeMillis() + 120_000)) { todo += l.again(); continue }
            val e = l.error
            if (e == null) mergeLate(myId, myGen, l)
            else if (isGone(e)) log("${l.name} ch: none for this channel")
            else { log("${l.name} ch failed: $e"); todo += l }
        }
        // Every late list is in or given up on. Chat lines do not wait through the retries below,
        // which take minutes; the 45 s a line is kept would run out long before.
        lateGen.compareAndSet(myGen, 0)
        for (wait in longArrayOf(20, 60, 180, 600)) {
            if (todo.isEmpty() || chGen.get() != myGen) return
            runCatching { Thread.sleep(wait * 1000L) }
            val it = todo.iterator()
            while (it.hasNext()) {
                if (chGen.get() != myGen) return
                val l = it.next().again()
                l.run()
                val e = l.error
                if (e != null) { log("${l.name} ch retry failed: $e"); if (isGone(e)) it.remove(); continue }
                it.remove()
                mergeLate(myId, myGen, l)
            }
        }
    }

    /** Add a list that came back after its channel was published. It only adds names, so what the
     *  channel already shows keeps its picture and its place. */
    private fun mergeLate(myId: String, myGen: Int, l: ChList) {
        val added = ArrayList<String>()
        synchronized(publishLock) {
            if (chGen.get() != myGen || activeChannel != myId || publishedFor != myId) return
            if (l.isTwitch) {
                for ((n, u) in l.map) if (twitch.putIfAbsent(n, u) == null) added += n
            } else {
                val have = chNames
                for ((n, u) in l.map) {
                    if (n in have) continue
                    map[n] = u
                    l.src[n]?.let { srcOf[n] = it }
                    l.up[n]?.let { uploaderOf[n] = it }
                    // As in the publish: a channel emote reusing a global name has its own picture.
                    if (gMap[n] != u) { drawables.remove(n); suggestDrawables.remove(n) }
                    added += n
                }
                chNames = have + added
                if (chOrderFor == myId) chOrder = chOrder + added
            }
        }
        log("${l.name} ch joined late: +${added.size}")
        if (added.isEmpty()) return
        bumpPicker()
        // An open favorites bar worked out which favorites this chat has when it was drawn, and
        // only redraws on a Favorites change. A list joining late can make some of them usable.
        runCatching { Favorites.channelChanged() }
        if (!l.isTwitch) for (n in added) imgPool.execute { if (chGen.get() == myGen) fetch(n) }
    }

    // 7TV channel: https://7tv.io/v3/users/twitch/<id> -> { emote_set:{ emotes:[ {name,id} ] } }
    private fun loadSevenTvChannel(id: String, out: MutableMap<String, String>, src: MutableMap<String, String>,
                                   up: MutableMap<String, String>) {
        val root = JSONObject(get("https://7tv.io/v3/users/twitch/$id"))
        val arr = root.optJSONObject("emote_set")?.optJSONArray("emotes") ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("name"); val eid = e.optString("id")
            if (name.isEmpty() || eid.isEmpty()) continue
            out[name] = "https://cdn.7tv.app/emote/$eid/${EmoteQuality.sevenTv}.webp"; src[name] = "7tv"; n++
            ownerName(e.optJSONObject("data")?.optJSONObject("owner"))?.let { up[name] = it }
        }
        log("7TV channel: $n")
    }

    // BTTV channel: https://api.betterttv.net/3/cached/users/twitch/<id> -> { channelEmotes:[], sharedEmotes:[] }
    private fun loadBttvChannel(id: String, out: MutableMap<String, String>, src: MutableMap<String, String>,
                                up: MutableMap<String, String>, channelName: String?) {
        val root = JSONObject(get("https://api.betterttv.net/3/cached/users/twitch/$id"))
        var n = 0
        for (key in arrayOf("channelEmotes", "sharedEmotes")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val name = e.optString("code"); val eid = e.optString("id")
                if (name.isEmpty() || eid.isEmpty()) continue
                out[name] = "https://cdn.betterttv.net/emote/$eid/${EmoteQuality.bttv}"; src[name] = "bttv"; n++
                // Shared emotes name their uploader; the channel's own were uploaded by the channel.
                (e.optJSONObject("user")?.let { u -> u.optString("displayName").ifEmpty { u.optString("name") } }
                    ?.takeIf { it.isNotEmpty() } ?: if (key == "channelEmotes") channelName else null)?.let { up[name] = it }
            }
        }
        log("BTTV channel: $n")
    }

    // FFZ room: https://api.frankerfacez.com/v1/room/id/<id> -> { sets:{ "<id>":{ emoticons:[...] } } }
    private fun loadFfzChannel(id: String, out: MutableMap<String, String>, src: MutableMap<String, String>,
                               up: MutableMap<String, String>) {
        val root = JSONObject(get("https://api.frankerfacez.com/v1/room/id/$id"))
        val sets = root.optJSONObject("sets") ?: return
        var n = 0
        for (key in sets.keys()) {
            val emotes = sets.getJSONObject(key).optJSONArray("emoticons") ?: continue
            for (i in 0 until emotes.length()) {
                val e = emotes.getJSONObject(i)
                val name = e.optString("name"); val urls = e.optJSONObject("urls")
                if (name.isEmpty() || urls == null) continue
                val pick = urls.optString(EmoteQuality.ffz).ifEmpty { urls.optString("2") }.ifEmpty { urls.optString("1") }
                if (pick.isEmpty()) continue
                out[name] = if (pick.startsWith("//")) "https:$pick" else pick
                src[name] = "ffz"; n++
                ownerName(e.optJSONObject("owner"))?.let { up[name] = it }
            }
        }
        log("FFZ channel: $n")
    }

    /** A decoded emote picture, plus the source bytes and row size when it is animated. */
    private class Built(val d: Drawable, val anim: ByteArray? = null, val bounds: IntArray? = null)

    /**
     * Decode emote [bytes] into a ready-to-draw Drawable scaled to our row height. Animated
     * sources (animated WebP / GIF) are detected here: their bytes + size come back with it (so
     * [makeAnimated] can mint fresh independently-animating copies) next to a STATIC first-frame
     * BitmapDrawable as the stored fallback. The caller stores them ([keepAnim]) only once it
     * knows the url is still the one in use. Static sources return a plain BitmapDrawable. Uses
     * ImageDecoder on API 28+ (animation requires it); below that, or on any failure, falls back
     * to a static BitmapFactory decode.
     */
    private fun buildDrawable(bytes: ByteArray, res: Resources): Built? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val d = runCatching {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                var tw = emoteHeightPx
                val decoded = ImageDecoder.decodeDrawable(src) { dec, info, _ ->
                    val iw = info.size.width; val ih = info.size.height
                    tw = if (ih > 0) (emoteHeightPx * iw / ih) else emoteHeightPx
                    dec.setTargetSize(tw.coerceAtLeast(1), emoteHeightPx)
                }
                if (decoded is AnimatedImageDrawable) {
                    // Static first frame for the cached map (fallback + injection default).
                    val first = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                    Built(BitmapDrawable(res, first).apply { setBounds(0, 0, tw.coerceAtLeast(1), emoteHeightPx) },
                        bytes, intArrayOf(tw.coerceAtLeast(1), emoteHeightPx))
                } else {
                    Built(decoded.apply { setBounds(0, 0, tw.coerceAtLeast(1), emoteHeightPx) })
                }
            }.getOrNull()
            if (d != null) return d
        }
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("decode failed")
            val tw = if (bmp.height > 0) (emoteHeightPx * bmp.width / bmp.height) else emoteHeightPx
            Built(BitmapDrawable(res, bmp).apply { setBounds(0, 0, tw.coerceAtLeast(1), emoteHeightPx) })
        }.getOrNull()
    }

    /**
     * Mint a FRESH, independently-animating drawable for [name] if it is an animated emote,
     * else null (caller falls back to the static cached drawable). Each call decodes a new
     * AnimatedImageDrawable from the cached source bytes so every chat line / preview gets its
     * own frame cursor + callback. Infinite-looping. Bounds set to the emote's row size.
     */
    fun makeAnimated(name: String): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val bytes = animBytes[name] ?: return null
        // Decoded for another channel's emote of the same name: not this chat's picture.
        val from = builtFrom[name]
        if (from != null && from != urlOf(name)) return null
        val b = animBounds[name]
        return runCatching {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val d = ImageDecoder.decodeDrawable(src) { dec, _, _ ->
                if (b != null) dec.setTargetSize(b[0], b[1])
            }
            if (d is AnimatedImageDrawable) {
                if (b != null) d.setBounds(0, 0, b[0], b[1])
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d
            } else null
        }.getOrNull()
    }

    /**
     * Build a LARGE preview drawable for [name] at NATIVE resolution (for the tap-to-zoom popup).
     * Animated emotes return a fresh infinite-looping AnimatedImageDrawable (caller starts it);
     * static emotes a plain BitmapDrawable. Source bytes come from the animated cache if present,
     * else the static cache's already-downloaded image isn't kept, so we re-download from the URL
     * ([map] for 7TV/BTTV/FFZ, else [twitch]). Runs on a worker thread (network) — never the UI
     * thread. Bounds are set to the drawable's intrinsic size. Returns null on failure.
     */
    fun loadPreviewDrawable(name: String): Drawable? {
        val cached = animBytes[name]
        val bytes = cached ?: runCatching {
            val url = map[name] ?: twitch[name] ?: return null
            getBytes(url)
        }.getOrNull() ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val d = runCatching {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeDrawable(src)
            }.getOrNull()
            if (d != null) {
                if (d is AnimatedImageDrawable) d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d.setBounds(0, 0, d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1))
                return d
            }
        }
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("decode failed")
            BitmapDrawable(Resources.getSystem(), bmp)
                .apply { setBounds(0, 0, bmp.width.coerceAtLeast(1), bmp.height.coerceAtLeast(1)) }
        }.getOrNull()
    }

    /** Download + decode every emote image into a bounds-set Drawable. Best-effort.
     *  If [guard] is non-null, aborts as soon as [activeChannel] != guard (user switched away). */
    private fun prefetchDrawables(guard: String? = null) {
        // Callers rely on this returning only once the batch is done: the global set is
        // snapshotted straight afterwards, and that snapshot is what every channel switch restores
        // from. So the downloads run in parallel, but this still waits for them.
        val names = map.keys.filter { !drawables.containsKey(it) }
        if (names.isEmpty()) return
        val started = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(names.size)
        for (name in names) imgPool.execute {
            try {
                if (guard == null || activeChannel == guard) fetch(name)
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.MINUTES)
        val c = runCatching { http.cache }.getOrNull()
        log("img batch: ${names.count { drawables.containsKey(it) }}/${names.size} ready in " +
            "${(System.currentTimeMillis() - started) / 1000}s" +
            (if (c != null) " (cache hits ${c.hitCount()}, network ${c.networkCount()}, " +
                "stored ${c.size() / 1024}KB)" else " (no cache)"))
    }

    /** Shared HTTP for sibling repos (BadgeRepo, Pronouns) so the module keeps ONE OkHttpClient
     *  — a second client would mean a second connection pool and dispatcher thread set. */
    fun httpGet(url: String): String = get(url)
    fun httpBytes(url: String): ByteArray = getBytes(url)

    /** Stream [url] into [out] (for app updates), reporting (bytes so far, total). Uses the shared
     *  connection pool but no cache, so a 140 MB file never lands in the emote picture cache. */
    fun httpDownload(url: String, out: File, onProgress: (Long, Long) -> Unit) {
        val c = http.newBuilder().cache(null).readTimeout(60, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            val body = r.body ?: error("empty body for $url")
            val total = body.contentLength()
            val tmp = File(out.path + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n; onProgress(done, total)
                    }
                }
            }
            if (total > 0 && tmp.length() != total) error("download incomplete: ${tmp.length()} of $total")
            if (!tmp.renameTo(out)) error("could not save ${out.path}")
        }
    }

    private fun getBytes(url: String): ByteArray {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            return r.body?.bytes() ?: error("empty body for $url")
        }
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            return r.body?.string() ?: error("empty body for $url")
        }
    }

    // Homies: https://itzalex.github.io/emotes -> { data:{ global_emotes:[{id,name}],
    //   channel_emotes:{ "<twitch id>":{ emotes:[{id,name}] } } } }. The ids are 7TV emote ids.
    // [channelId] null loads the globals.
    private fun loadHomies(channelId: String?, out: MutableMap<String, String>, src: MutableMap<String, String>) {
        val data = homies ?: JSONObject(get("https://itzalex.github.io/emotes"))
            .optJSONObject("data")?.also { homies = it } ?: return
        val arr = if (channelId == null) data.optJSONArray("global_emotes")
            else data.optJSONObject("channel_emotes")?.optJSONObject(channelId)?.optJSONArray("emotes")
        arr ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optString("name"); val id = e.optString("id")
            if (name.isEmpty() || id.isEmpty() || out.containsKey(name)) continue
            out[name] = "https://cdn.7tv.app/emote/$id/${EmoteQuality.sevenTv}.webp"; src[name] = "homies"; n++
            if (channelId == null) globalOrder.add(name)
        }
        log("Homies ${channelId ?: "global"}: $n")
    }

    /** An emote owner's display name (7TV "display_name"/"username", FFZ "display_name"/"name"). */
    private fun ownerName(o: JSONObject?): String? = o?.let {
        it.optString("display_name").ifEmpty { it.optString("username") }.ifEmpty { it.optString("name") }
    }?.takeIf { it.isNotEmpty() }

    /** The emote's own page on its service's website, worked out from its picture address. */
    fun pageOf(name: String): String? {
        val url = map[name] ?: return null
        fun id(re: String) = Regex(re).find(url)?.groupValues?.get(1)
        return when (srcOf[name]) {
            "7tv", "homies" -> id("cdn\\.7tv\\.app/emote/([^/]+)/")?.let { "https://7tv.app/emotes/$it" }
            "bttv" -> id("cdn\\.betterttv\\.net/emote/([^/]+)")?.let { "https://betterttv.com/emotes/$it" }
            "ffz" -> id("frankerfacez\\.com/emote/(\\d+)/")?.let { "https://www.frankerfacez.com/emoticon/$it-$name" }
            else -> null
        }
    }

    /** For the emote popup: (service, uploader, whether it is a global emote rather than this channel's). */
    fun infoOf(name: String): Triple<String, String?, Boolean>? {
        val src = srcOf[name] ?: return null
        val label = when (src) { "7tv" -> "7TV"; "bttv" -> "BetterTTV"; "ffz" -> "FrankerFaceZ"; "homies" -> "Homies"; else -> src.uppercase() }
        return Triple(label, uploaderOf[name], name !in chNames)
    }

    // 7TV: https://7tv.io/v3/emote-sets/global -> { emotes:[ {name,id} ] }
    private fun loadSevenTvGlobal(out: MutableMap<String, String>, src: MutableMap<String, String>,
                                  up: MutableMap<String, String>) {
        val root = JSONObject(get("https://7tv.io/v3/emote-sets/global"))
        val arr = root.optJSONArray("emotes") ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("name")
            val id = e.optString("id")
            if (name.isEmpty() || id.isEmpty()) continue
            out.putIfAbsent(name, "https://cdn.7tv.app/emote/$id/${EmoteQuality.sevenTv}.webp")
            src.putIfAbsent(name, "7tv"); globalOrder.add(name)
            stvGlobal.add(name)
            ownerName(e.optJSONObject("data")?.optJSONObject("owner"))?.let { up.putIfAbsent(name, it) }
            n++
        }
        log("7TV global: $n")
    }

    // BTTV: https://api.betterttv.net/3/cached/emotes/global -> [ {id,code} ]
    private fun loadBttvGlobal(out: MutableMap<String, String>, src: MutableMap<String, String>,
                               up: MutableMap<String, String>) {
        val arr = JSONArray(get("https://api.betterttv.net/3/cached/emotes/global"))
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("code")
            val id = e.optString("id")
            if (name.isEmpty() || id.isEmpty()) continue
            out.putIfAbsent(name, "https://cdn.betterttv.net/emote/$id/${EmoteQuality.bttv}")
            src.putIfAbsent(name, "bttv"); globalOrder.add(name)
            up.putIfAbsent(name, "BetterTTV")
            n++
        }
        log("BTTV global: $n")
    }

    // FFZ: https://api.frankerfacez.com/v1/set/global -> { sets:{ "<id>":{ emoticons:[ {name,urls:{"1","2","4"}} ] } } }
    private fun loadFfzGlobal(out: MutableMap<String, String>, src: MutableMap<String, String>,
                              up: MutableMap<String, String>) {
        val root = JSONObject(get("https://api.frankerfacez.com/v1/set/global"))
        val sets = root.optJSONObject("sets") ?: return
        var n = 0
        for (key in sets.keys()) {
            val emotes = sets.getJSONObject(key).optJSONArray("emoticons") ?: continue
            for (i in 0 until emotes.length()) {
                val e = emotes.getJSONObject(i)
                val name = e.optString("name")
                val urls = e.optJSONObject("urls")
                if (name.isEmpty() || urls == null) continue
                val pick = urls.optString(EmoteQuality.ffz).ifEmpty { urls.optString("2") }.ifEmpty { urls.optString("1") }
                if (pick.isEmpty()) continue
                val full = if (pick.startsWith("//")) "https:$pick" else pick
                out.putIfAbsent(name, full)
                src.putIfAbsent(name, "ffz"); globalOrder.add(name)
                ownerName(e.optJSONObject("owner"))?.let { up.putIfAbsent(name, it) }
                n++
            }
        }
        log("FFZ global: $n")
    }
}
