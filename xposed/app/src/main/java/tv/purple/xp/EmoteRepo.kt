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

    /** The url [name] resolves to in the current chat, or null if it is not available here. */
    fun urlOf(name: String): String? = map[name] ?: twitch[name]

    /** Numeric id of the channel whose emotes are loaded, or null outside a channel. */
    fun currentChannel(): String? = activeChannel

    // --- global snapshots (authoritative GLOBAL-only emote set) ---
    // The public maps above are the LIVE combined view (globals + the CURRENT channel) that the
    // inject hook (EmoteHooks reads [drawables]) and [suggest] read. When the user switches
    // channels we rebuild the live maps from these snapshots in loadChannelAsync's publish, so the
    // previous channel's 7TV/BTTV/FFZ/Twitch emotes can never leak into a channel that doesn't
    // have them. Globals persist; only channel-scoped entries are dropped.
    private val gMap = ConcurrentHashMap<String, String>()
    private val gSrc = ConcurrentHashMap<String, String>()
    private val gDraw = ConcurrentHashMap<String, Drawable>()
    private val gTwitch = ConcurrentHashMap<String, String>()
    private val gSuggestDraw = ConcurrentHashMap<String, Drawable>()
    /** Numeric id of the channel whose emotes are currently merged into the live maps. */
    @Volatile private var activeChannel: String? = null
    /** Login of [activeChannel], kept so [refetch] can reload the same channel. */
    @Volatile private var activeName: String? = null

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
    private val imgPool = Executors.newFixedThreadPool(6) { r ->
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

    /** Download and decode one emote, unless it is already here or already downloading. A queued
     *  background task re-checks at start, so if the fast lane got there first it just skips. */
    private fun fetch(name: String) {
        if (drawables.containsKey(name)) return
        val url = map[name] ?: return
        if (url in gone) return
        if (!inflight.add(name)) return
        try {
            val d = buildDrawable(name, getBytes(url), Resources.getSystem())
            if (d != null) { builtFrom[name] = url; drawables[name] = d; suggestDrawables.putIfAbsent(name, d) }
        } catch (t: Throwable) {
            if (isGone(t)) gone.add(url)
            log("img fail '$name': $t")
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
                val ch = activeChannel; val chName = activeName
                activeChannel = null
                map.clear(); srcOf.clear(); stvGlobal.clear()
                gMap.clear(); gSrc.clear()
                drawables.clear(); gDraw.clear(); gSuggestDraw.clear()
                animBytes.clear(); suggestDrawables.clear()
                homies = null
                globalsLoaded = true
                log("emote cache cleared; refetching at quality=${Settings.getInt(EmoteQuality.KEY, EmoteQuality.DEFAULT)}")
                loadGlobalsNow()
                if (ch != null && activeChannel == null) loadChannelAsync(ch, chName)
            }.onFailure { log("refetch failed: $it") }
        }, "ptv-emote-refetch").apply { isDaemon = true }.start()
    }

    fun loadGlobalsAsync() {
        if (globalsLoaded) return
        globalsLoaded = true
        Thread({ loadGlobalsNow() }, "ptv-emote-fetch").start()
    }

    private fun loadGlobalsNow() {
        emoteHeightPx = (28f * Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(36)
        run {
            runCatching { loadSevenTvGlobal() }.onFailure { log("7TV global failed: $it") }
            runCatching { loadBttvGlobal() }.onFailure { log("BTTV global failed: $it") }
            runCatching { loadFfzGlobal() }.onFailure { log("FFZ global failed: $it") }
            if (Settings.get(KEY_HOMIES, false))
                runCatching { loadHomies(null, map, srcOf) }.onFailure { log("Homies global failed: $it") }
            // Snapshot the global NAMES now (before any channel can merge in) so a later
            // channel switch can restore exactly the global-only set.
            gMap.putAll(map); gSrc.putAll(srcOf)
            log("emote map ready: ${map.size} global emotes; prefetching images (h=${emoteHeightPx}px)")
            prefetchDrawables()
            gDraw.putAll(drawables); gSuggestDraw.putAll(suggestDrawables)
            log("emote drawables ready: ${drawables.size}/${map.size}")
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
                        twitch[name] =
                            "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/${EmoteQuality.twitch}"
                    }
                }
            }.onFailure { log("frequent_emotes query failed: $it") }
        }
        log("twitch frequent_emotes: ${twitch.size}")
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
            val before = twitch.size
            runCatching {
                val arr = JSONArray(get("https://emotes.adamcy.pl/v1/global/emotes/all"))
                addAdamcyTwitch(arr, twitch)
            }.onFailure { log("adamcy global failed: $it") }
            log("twitch global emotes: +${twitch.size - before} (total ${twitch.size}); prefetching")
            prefetchSuggestDrawables()
            gTwitch.putAll(twitch); gSuggestDraw.putAll(suggestDrawables)
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
            runCatching {
                val token = readAuthToken(ctx)
                if (token.isBlank()) { log("personal emotes: not logged in (no token) — skipped"); return@Thread }
                val personal = HashMap<String, String>()
                fetchUserEmotes(token, personal)
                if (personal.isEmpty()) { log("personal emotes: none returned"); return@Thread }
                val before = twitch.size
                gTwitch.putAll(personal); twitch.putAll(personal)
                log("personal emotes: +${twitch.size - before} (total twitch ${twitch.size}); prefetching")
                prefetchSuggestDrawables()
                gSuggestDraw.putAll(suggestDrawables)
                log("suggest drawables ready: ${suggestDrawables.size}")
            }.onFailure { log("personal emotes failed: $it") }
        }, "ptv-personal-emotes").start()
    }

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
            if (chosen.isNotEmpty()) out[name] = chosen
        }
    }

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
    private fun prefetchSuggestDrawables(guard: String? = null) {
        for ((name, d) in drawables) suggestDrawables.putIfAbsent(name, d)
        val res = Resources.getSystem()
        for ((name, url) in twitch) {
            if (guard != null && activeChannel != guard) return
            if (suggestDrawables.containsKey(name)) continue
            if (!inflight.add("sug:$name")) continue
            sugPool.execute {
                try {
                    val d = buildDrawable(name, getBytes(url), res)
                    if (d != null) suggestDrawables[name] = d
                } catch (t: Throwable) {
                    log("sug img fail '$name': $t")
                } finally {
                    inflight.remove("sug:$name")
                }
            }
        }
    }

    // --- channel emotes (keyed by Twitch numeric channel id) ---
    private val publishLock = Any()

    /**
     * Load a channel's 7TV/BTTV/FFZ + Twitch emote sets and make them the ONLY channel emotes
     * merged on top of the globals. Anchor: ChannelChatConnectionKey.channelId.
     *
     * CRITICAL (state-leak fix): emotes are built into LOCAL maps off the live ones, then
     * published ATOMICALLY (live = globals + this channel) only if this is still the active
     * channel. This prevents a previous channel's emotes from leaking into a channel that
     * doesn't have them — including the race where the OLD channel's slow image prefetch / Twitch
     * fetch finishes AFTER the user already switched. The chat-connect ctor fires in a ~13× burst
     * per channel; we dedup on [activeChannel] so only the first of a burst proceeds. Re-entering
     * a channel re-fetches it (cheap, upstream-cached).
     */
    fun loadChannelAsync(channelId: String, channelName: String?) {
        if (channelId.isBlank()) return
        if (channelId == activeChannel) return // same channel (ctor burst / no switch) — ignore
        activeChannel = channelId
        activeName = channelName
        Thread({
            val myId = channelId
            // Build this channel's set in LOCAL maps — no writes to the live maps yet.
            val cMap = HashMap<String, String>()
            val cSrc = HashMap<String, String>()
            val cTwitch = HashMap<String, String>()
            runCatching { loadSevenTvChannel(myId, cMap, cSrc) }.onFailure { log("7TV ch failed: $it") }
            runCatching { loadBttvChannel(myId, cMap, cSrc) }.onFailure { log("BTTV ch failed: $it") }
            runCatching { loadFfzChannel(myId, cMap, cSrc) }.onFailure { log("FFZ ch failed: $it") }
            if (Settings.get(KEY_HOMIES, false))
                runCatching { loadHomies(myId, cMap, cSrc) }.onFailure { log("Homies ch failed: $it") }
            if (!channelName.isNullOrBlank())
                runCatching { loadTwitchChannel(channelName, cTwitch) }.onFailure { log("twitch ch failed: $it") }
            if (activeChannel != myId) { log("ch $myId superseded before publish — discarded"); return@Thread }
            // Publish atomically: live = globals + this channel only. Drops any prior channel.
            synchronized(publishLock) {
                if (activeChannel != myId) { log("ch $myId superseded at publish — discarded"); return@Thread }
                map.clear(); map.putAll(gMap); map.putAll(cMap)
                srcOf.clear(); srcOf.putAll(gSrc); srcOf.putAll(cSrc)
                twitch.clear(); twitch.putAll(gTwitch); twitch.putAll(cTwitch)
                drawables.clear(); drawables.putAll(gDraw)
                suggestDrawables.clear(); suggestDrawables.putAll(gSuggestDraw)
                // A channel emote that reuses a global name has its own picture: drop the
                // global one so the channel's is fetched instead of the global staying put.
                for ((n, u) in cMap) if (gMap[n] != u) { drawables.remove(n); suggestDrawables.remove(n) }
                // Animated pictures decoded for a different url are another chat's.
                for (n in animBytes.keys) {
                    val from = builtFrom[n] ?: continue
                    if (from != urlOf(n)) { animBytes.remove(n); animBounds.remove(n); builtFrom.remove(n) }
                }
            }
            runCatching { Favorites.channelChanged() }
            log("channel $myId ($channelName) published: +${cMap.size} inject, +${cTwitch.size} twitch (map=${map.size}); prefetching")
            // Download channel images into the live maps; aborts mid-loop if the user switches.
            prefetchDrawables(myId)
            if (activeChannel != myId) return@Thread
            prefetchSuggestDrawables(myId)
            log("channel $myId drawables ready: ${drawables.size}/${map.size}")
        }, "ptv-ch-fetch-$channelId").start()
    }

    // 7TV channel: https://7tv.io/v3/users/twitch/<id> -> { emote_set:{ emotes:[ {name,id} ] } }
    private fun loadSevenTvChannel(id: String, out: MutableMap<String, String>, src: MutableMap<String, String>) {
        val root = JSONObject(get("https://7tv.io/v3/users/twitch/$id"))
        val arr = root.optJSONObject("emote_set")?.optJSONArray("emotes") ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("name"); val eid = e.optString("id")
            if (name.isEmpty() || eid.isEmpty()) continue
            out[name] = "https://cdn.7tv.app/emote/$eid/${EmoteQuality.sevenTv}.webp"; src[name] = "7tv"; n++
        }
        log("7TV channel: $n")
    }

    // BTTV channel: https://api.betterttv.net/3/cached/users/twitch/<id> -> { channelEmotes:[], sharedEmotes:[] }
    private fun loadBttvChannel(id: String, out: MutableMap<String, String>, src: MutableMap<String, String>) {
        val root = JSONObject(get("https://api.betterttv.net/3/cached/users/twitch/$id"))
        var n = 0
        for (key in arrayOf("channelEmotes", "sharedEmotes")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val name = e.optString("code"); val eid = e.optString("id")
                if (name.isEmpty() || eid.isEmpty()) continue
                out[name] = "https://cdn.betterttv.net/emote/$eid/${EmoteQuality.bttv}"; src[name] = "bttv"; n++
            }
        }
        log("BTTV channel: $n")
    }

    // FFZ room: https://api.frankerfacez.com/v1/room/id/<id> -> { sets:{ "<id>":{ emoticons:[...] } } }
    private fun loadFfzChannel(id: String, out: MutableMap<String, String>, src: MutableMap<String, String>) {
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
            }
        }
        log("FFZ channel: $n")
    }

    /**
     * Decode emote [bytes] into a ready-to-draw Drawable scaled to our row height. Animated
     * sources (animated WebP / GIF) are detected here: we cache their bytes in [animBytes] +
     * size in [animBounds] (so [makeAnimated] can mint fresh independently-animating copies) and
     * return a STATIC first-frame BitmapDrawable as the stored fallback. Static sources return a
     * plain BitmapDrawable. Uses ImageDecoder on API 28+ (animation requires it); below that, or
     * on any failure, falls back to a static BitmapFactory decode.
     */
    private fun buildDrawable(name: String, bytes: ByteArray, res: Resources): Drawable? {
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
                    animBytes[name] = bytes
                    animBounds[name] = intArrayOf(tw.coerceAtLeast(1), emoteHeightPx)
                    // Static first frame for the cached map (fallback + injection default).
                    val first = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                    BitmapDrawable(res, first).apply { setBounds(0, 0, tw.coerceAtLeast(1), emoteHeightPx) }
                } else {
                    decoded.apply { setBounds(0, 0, tw.coerceAtLeast(1), emoteHeightPx) }
                }
            }.getOrNull()
            if (d != null) return d
        }
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("decode failed")
            val tw = if (bmp.height > 0) (emoteHeightPx * bmp.width / bmp.height) else emoteHeightPx
            BitmapDrawable(res, bmp).apply { setBounds(0, 0, tw.coerceAtLeast(1), emoteHeightPx) }
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
        }
        log("Homies ${channelId ?: "global"}: $n")
    }

    // 7TV: https://7tv.io/v3/emote-sets/global -> { emotes:[ {name,id} ] }
    private fun loadSevenTvGlobal() {
        val root = JSONObject(get("https://7tv.io/v3/emote-sets/global"))
        val arr = root.optJSONArray("emotes") ?: return
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("name")
            val id = e.optString("id")
            if (name.isEmpty() || id.isEmpty()) continue
            map.putIfAbsent(name, "https://cdn.7tv.app/emote/$id/${EmoteQuality.sevenTv}.webp")
            srcOf.putIfAbsent(name, "7tv")
            stvGlobal.add(name)
            n++
        }
        log("7TV global: $n")
    }

    // BTTV: https://api.betterttv.net/3/cached/emotes/global -> [ {id,code} ]
    private fun loadBttvGlobal() {
        val arr = JSONArray(get("https://api.betterttv.net/3/cached/emotes/global"))
        var n = 0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("code")
            val id = e.optString("id")
            if (name.isEmpty() || id.isEmpty()) continue
            map.putIfAbsent(name, "https://cdn.betterttv.net/emote/$id/${EmoteQuality.bttv}")
            srcOf.putIfAbsent(name, "bttv")
            n++
        }
        log("BTTV global: $n")
    }

    // FFZ: https://api.frankerfacez.com/v1/set/global -> { sets:{ "<id>":{ emoticons:[ {name,urls:{"1","2","4"}} ] } } }
    private fun loadFfzGlobal() {
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
                map.putIfAbsent(name, full)
                srcOf.putIfAbsent(name, "ffz")
                n++
            }
        }
        log("FFZ global: $n")
    }
}
