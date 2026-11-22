package tv.purple.xp

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emote names and pictures for any channel, kept apart from EmoteRepo's single live channel.
 *
 * EmoteRepo holds exactly one channel at a time, the one Twitch's own chat shows, and loading a
 * second channel there would swap the emotes of that chat too. The two-stream chats need any
 * channel, so each channel's 7TV, BTTV and FFZ lists are kept here by channel id, on top of a copy
 * of EmoteRepo's global lists (those are the same for every channel, so they are not fetched again).
 *
 * Pictures are kept by address rather than by name: two channels often use one word for two
 * different pictures. They are only fetched when a chat line actually uses them, newest request
 * first, since a whole channel set is hundreds of pictures and a chat shows a few dozen.
 *
 * Threading: lists are loaded and tracked on one private thread, the three providers of a channel
 * are asked side by side, pictures decode on a small pool of their own. Lookups ([urlOf],
 * [picture]) only read memory and are safe on the main thread.
 */
object SideEmotes {

    /** Channel sets kept at once. Two panes, plus a few recently swapped away from. */
    private const val MAX_SETS = 6
    /** A list that failed is asked again after this long, when a chat asks for the channel. */
    private const val RETRY_MS = 60_000L
    /** Times a failed list is asked again on its own, each wait twice the one before. */
    private const val MAX_RETRIES = 5
    /** A picture whose download failed is not asked for again before this. */
    private const val PICTURE_RETRY_MS = 2 * 60_000L
    /** Picture cache size in bytes: pixels, plus the file each animation keeps. A busy chat cycles
     *  through a few hundred emotes. */
    private const val PICTURE_BYTES = 24 * 1024 * 1024

    private val main = Handler(Looper.getMainLooper())

    /** Bookkeeping: which channels load, who waits for them, the global copy. */
    private val work = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ptv-dual-emotes").apply { isDaemon = true }
    }
    /** The provider requests themselves, side by side. Threads are made as needed and reused. */
    private val lists = Executors.newCachedThreadPool { r ->
        Thread(r, "ptv-dual-list").apply { isDaemon = true }
    }

    /**
     * Picture downloads, newest first. When a busy chat asks for fifty pictures in a second, the
     * ones on the lines just drawn at the bottom are the ones being looked at, so they jump the
     * queue instead of waiting for every older line's.
     */
    private val pics = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
        object : LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable): Boolean = offerFirst(e)
        }) { r -> Thread(r, "ptv-dual-img").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }

    /** One channel's own emotes: name -> picture address and name -> provider. [attempt] counts
     *  the retries in a row that led to this set, 0 when a chat or a setting asked for it. */
    private class ChannelSet(val urls: Map<String, String>, val src: Map<String, String>,
                             val quality: String, val complete: Boolean, val at: Long, val attempt: Int) {
        /** Last time a chat asked for this channel, for [trim]. */
        @Volatile var used = at
    }

    private val sets = ConcurrentHashMap<String, ChannelSet>()

    /** Channels an open chat shows, with the number of chats showing each. [trim] keeps these. */
    private val pinned = ConcurrentHashMap<String, Int>()

    // --- work thread only ---
    private val logins = HashMap<String, String>()
    private val loading = HashSet<String>()
    private val waiters = HashMap<String, MutableList<() -> Unit>>()

    /** Copy of EmoteRepo's global lists. Replaced whole, never changed in place. */
    @Volatile private var gUrls: Map<String, String> = emptyMap()
    @Volatile private var gSrc: Map<String, String> = emptyMap()
    /** The picture sizes the lists were loaded at; a change in the quality setting reloads them. */
    @Volatile private var loadedQuality = ""

    private val listening = AtomicBoolean(false)
    private val refreshQueued = AtomicBoolean(false)
    private val watchingMemory = AtomicBoolean(false)

    /** A chat starts showing [channelId]: its set stays loaded until the matching [unpin]. */
    fun pin(channelId: String) {
        if (channelId.isNotBlank()) pinned.merge(channelId, 1, Int::plus)
    }

    fun unpin(channelId: String) {
        if (channelId.isNotBlank()) pinned.computeIfPresent(channelId) { _, n -> if (n > 1) n - 1 else null }
    }

    /** Whether an open chat shows [channelId], so other per-channel caches can keep it too. */
    fun isPinned(channelId: String): Boolean = pinned.containsKey(channelId)

    /**
     * Load [channelId]'s emote lists (and the global copy, the first time), then call [done] on a
     * worker thread. Asking again for a channel already here only calls [done]. A blank
     * [channelId] just makes sure the globals are in. [login] lets BTTV name the channel as the
     * uploader of its own emotes, as EmoteRepo does.
     */
    fun load(channelId: String, login: String, done: () -> Unit) {
        listen()
        watchMemory()
        work.execute {
            runCatching {
                if (gUrls.isEmpty()) snapshotGlobals()
                if (loadedQuality.isEmpty()) loadedQuality = quality()
                if (channelId.isBlank()) { finish(done); return@runCatching }
                logins[channelId] = login
                val have = sets[channelId]
                val fresh = have != null && have.quality == loadedQuality &&
                    (have.complete || SystemClock.uptimeMillis() - have.at < RETRY_MS)
                if (fresh) { have?.used = SystemClock.uptimeMillis(); finish(done); return@runCatching }
                waiters.getOrPut(channelId) { ArrayList(2) }.add(done)
                if (loading.add(channelId)) { val q = loadedQuality; lists.execute { fetch(channelId, login, q, 0) } }
            }.onFailure { log("SE load failed: $it") }
        }
    }

    /**
     * The picture address [word] stands for in [channelId]'s chat, or null when it is no emote
     * there or its provider is switched off. The channel's own emote wins over a global one of the
     * same name, as in Twitch's chat with PurpleTV.
     */
    fun urlOf(channelId: String, word: String): String? {
        if (word.isEmpty()) return null
        val t = toggles()
        sets[channelId]?.let { s ->
            s.urls[word]?.let { u -> if (t.allows(s.src[word], word, channel = true)) return u }
        }
        gUrls[word]?.let { u -> if (t.allows(gSrc[word], word, channel = false)) return u }
        return null
    }

    /** A Twitch emote's picture address from its id, at the size the quality setting asks for. */
    fun twitchUrl(emoteId: String): String =
        "https://static-cdn.jtvnw.net/emoticons/v2/$emoteId/default/dark/${toggles().twitchSize}"

    // ---- pictures ----

    /**
     * Decoded pictures by address, shared by every line and both panes. Animated ones are one
     * AnimatedImageDrawable each: the module's animation driver redraws every row showing it and
     * runs it only while one of them can be seen.
     */
    private val cache = object : LruCache<String, Pic>(PICTURE_BYTES) {
        override fun sizeOf(key: String, value: Pic): Int = value.cost
    }

    /** A decoded picture and the bytes it holds on to while cached. */
    private class Pic(val d: Drawable, val cost: Int)

    /**
     * What keeping [d] costs. An animation holds three frames (the one shown, the next being
     * decoded and one to restore), and its decoder keeps its own copy of the whole file, which for
     * a long 7TV animation is far bigger than the frames.
     */
    private fun costOf(d: Drawable, fileBytes: Int): Int {
        val b = d.bounds
        val px = b.width().coerceAtLeast(1) * b.height().coerceAtLeast(1) * 4
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d is AnimatedImageDrawable) px * 3 + fileBytes else px
    }

    /** Let go of every cached picture, for when the two-stream screen closes. Rows still on screen
     *  keep their own, and a picture asked for again is fetched again. */
    fun clearPictures() = cache.evictAll()

    /** Give the pictures back when Android asks the app to use less memory. */
    private fun watchMemory() {
        val app = Settings.ctx() ?: return
        if (!watchingMemory.compareAndSet(false, true)) return
        runCatching {
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                // The RUNNING_ levels are deprecated on Android 14, but older phones still send them.
                @Suppress("DEPRECATION")
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) cache.evictAll()
                    else if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) cache.trimToSize(cache.maxSize() / 2)
                }
                override fun onLowMemory() = cache.evictAll()
                override fun onConfigurationChanged(c: Configuration) {}
            })
        }.onFailure { log("SE memory watch failed: $it") }
    }

    private val pictureLock = Any()
    /** Address -> callbacks waiting for it. An address in here is being fetched. */
    private val pictureWaiters = HashMap<String, MutableList<() -> Unit>>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** Addresses the CDN says no longer exist (404 or 410). Never asked for again. */
    private val gone: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The picture at [url], or null until it is here. A miss starts the download, and [onReady]
     * then runs once on the main thread when the picture can be drawn. The drawable's bounds are
     * already set (EmoteRepo's emote height, and the width that keeps its shape) and are shared:
     * callers scale it while drawing and never change them.
     */
    fun picture(url: String, onReady: () -> Unit): Drawable? {
        if (url.isEmpty()) return null
        cache.get(url)?.let { return it.d }
        if (url in gone) return null
        failedAt[url]?.let { if (SystemClock.uptimeMillis() - it < PICTURE_RETRY_MS) return null }
        synchronized(pictureLock) {
            // A download stores its picture before it takes the lock to call its waiters, so a
            // second look here cannot miss one that finished just now.
            cache.get(url)?.let { return it.d }
            val list = pictureWaiters[url]
            if (list != null) { if (onReady !in list) list.add(onReady); return null }
            pictureWaiters[url] = ArrayList<() -> Unit>(2).apply { add(onReady) }
        }
        pics.execute { fetchPicture(url) }
        return null
    }

    /** Forget [onReady] wherever it waits for a picture, so a closed chat is not kept alive (or
     *  called) by downloads that finish after it. */
    fun drop(onReady: () -> Unit) {
        synchronized(pictureLock) { for (l in pictureWaiters.values) l.remove(onReady) }
    }

    private fun fetchPicture(url: String) {
        var d: Drawable? = null
        try {
            val bytes = EmoteRepo.httpBytes(url)
            val pic = decode(bytes) ?: error("could not decode")
            d = pic
            cache.put(url, Pic(pic, costOf(pic, bytes.size)))
            failedAt.remove(url)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.startsWith("HTTP 404") || msg.startsWith("HTTP 410")) gone.add(url)
            else failedAt[url] = SystemClock.uptimeMillis()
            log("SE picture failed: ${t.javaClass.simpleName}")
        }
        val ready = synchronized(pictureLock) { pictureWaiters.remove(url) } ?: return
        if (d == null || ready.isEmpty()) return
        main.post { for (cb in ready) runCatching { cb() }.onFailure { log("SE picture callback failed: $it") } }
    }

    /**
     * Decode at the emote row height, keeping the picture's shape. Animated pictures come back as
     * an infinitely looping AnimatedImageDrawable that is not started yet. Still pictures are
     * decoded into ordinary memory rather than graphics memory: a graphics-only bitmap draws
     * nothing (or throws) on a software canvas, and a chat row can end up drawn on one.
     */
    private fun decode(bytes: ByteArray): Drawable? {
        val h = EmoteRepo.emoteHeight().coerceAtLeast(16)
        val res = Resources.getSystem()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val d = runCatching {
                var w = h
                val out = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { dec, info, _ ->
                    val iw = info.size.width; val ih = info.size.height
                    w = if (ih > 0) (h.toLong() * iw / ih).toInt().coerceIn(1, h * 8) else h
                    dec.setTargetSize(w, h)
                    if (!info.isAnimated) dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                if (out is AnimatedImageDrawable) out.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                out.setBounds(0, 0, w, h)
                out
            }.getOrNull()
            if (d != null) return d
        }
        return runCatching {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val w = if (bmp.height > 0) (h.toLong() * bmp.width / bmp.height).toInt().coerceIn(1, h * 8) else h
            BitmapDrawable(res, bmp).apply { setBounds(0, 0, w, h) }
        }.getOrNull()
    }

    // ---- lists (work thread unless said otherwise) ----

    /** One provider's list for one channel, loaded into maps of its own. */
    private class Part(val name: String, val load: (MutableMap<String, String>, MutableMap<String, String>, MutableMap<String, String>) -> Unit) {
        val urls = LinkedHashMap<String, String>()
        val src = HashMap<String, String>()
        @Volatile var error: Throwable? = null
        /** Set once the request has ended. Before that the maps may still be written to. */
        @Volatile var done = false
        fun run() {
            try { load(urls, src, HashMap()) } catch (t: Throwable) { error = t } finally { done = true }
        }
    }

    /** On a list thread: ask the three providers side by side, then hand the set to [work]. */
    private fun fetch(channelId: String, login: String, quality: String, attempt: Int) {
        val set = try {
            val parts = listOf(
                Part("7TV") { m, s, u -> EmoteRepo.loadSevenTvChannel(channelId, m, s, u) },
                Part("BTTV") { m, s, u -> EmoteRepo.loadBttvChannel(channelId, m, s, u, login.ifBlank { null }) },
                Part("FFZ") { m, s, u -> EmoteRepo.loadFfzChannel(channelId, m, s, u) })
            val latch = CountDownLatch(parts.size)
            for (p in parts) lists.execute { try { p.run() } finally { latch.countDown() } }
            // Every request ends on its own connect or read timeout; this is only a backstop.
            runCatching { latch.await(60, TimeUnit.SECONDS) }
            val urls = LinkedHashMap<String, String>()
            val src = HashMap<String, String>()
            var complete = true
            for (p in parts) {
                // Still running: counts as failed, and its maps are left alone.
                if (!p.done) { complete = false; log("SE ${p.name} list timed out for a side channel"); continue }
                val e = p.error
                if (e != null) {
                    // A 404 only means the channel has no account with that provider.
                    val missing = e.message.orEmpty().let { it.startsWith("HTTP 404") || it.startsWith("HTTP 410") }
                    if (!missing) { complete = false; log("SE ${p.name} list failed for a side channel: ${e.javaClass.simpleName}") }
                    continue
                }
                // In this order a later list wins a name two of them share, the same as EmoteRepo.
                for ((n, u) in p.urls) { urls[n] = u; p.src[n]?.let { src[n] = it } }
            }
            ChannelSet(urls, src, quality, complete, SystemClock.uptimeMillis(), attempt)
        } catch (t: Throwable) {
            // The channel must not stay marked as loading: an empty set ends the wait and is
            // asked for again like any failed list.
            log("SE lists failed for a side channel: $t")
            ChannelSet(emptyMap(), emptyMap(), quality, false, SystemClock.uptimeMillis(), attempt)
        }
        work.execute { runCatching { publish(channelId, set) }.onFailure { log("SE publish failed: $it") } }
    }

    private fun publish(channelId: String, set: ChannelSet) {
        loading.remove(channelId)
        // Loaded at a quality that has changed since: load again, and keep the callers waiting.
        if (set.quality != loadedQuality) {
            if (loading.add(channelId)) {
                val login = logins[channelId].orEmpty(); val q = loadedQuality
                lists.execute { fetch(channelId, login, q, 0) }
            }
            return
        }
        sets[channelId] = set
        log("SE channel $channelId: ${set.urls.size} emotes${if (set.complete) "" else " (a list is missing, asked again later)"}")
        trim(channelId)
        waiters.remove(channelId)?.forEach { finish(it) }
        // A server that failed is asked again on its own while a chat still shows the channel, so
        // that chat does not have to wait for the next time it is opened. Each wait is twice the
        // last, and after a few it is left to the next load().
        if (!set.complete && set.attempt < MAX_RETRIES && pinned.containsKey(channelId)) work.schedule({
            runCatching {
                val now = sets[channelId]
                if (now === set && pinned.containsKey(channelId) && !loading.contains(channelId) &&
                    set.quality == loadedQuality && loading.add(channelId)) {
                    val login = logins[channelId].orEmpty(); val q = loadedQuality; val n = set.attempt + 1
                    lists.execute { fetch(channelId, login, q, n) }
                }
            }
        }, RETRY_MS shl set.attempt, TimeUnit.MILLISECONDS)
    }

    /**
     * Keep at most [MAX_SETS] channels, dropping the ones asked for longest ago. A channel an open
     * chat shows is never dropped; when only those are left the limit gives way.
     */
    private fun trim(keep: String) {
        while (sets.size > MAX_SETS) {
            // containsKey, not `in`: ConcurrentHashMap's own contains() looks at the values.
            val oldest = sets.entries.filter { it.key != keep && it.key !in loading && !pinned.containsKey(it.key) }
                .minByOrNull { it.value.used }?.key ?: return
            sets.remove(oldest)
            logins.remove(oldest)
        }
    }

    private fun finish(done: () -> Unit) {
        runCatching { done() }.onFailure { log("SE done callback failed: $it") }
    }

    /** Copy EmoteRepo's globals. An empty copy (EmoteRepo is reloading them) keeps the old one. */
    private fun snapshotGlobals() {
        val u = runCatching { EmoteRepo.globalUrls() }.getOrDefault(emptyMap())
        if (u.isEmpty()) return
        val s = runCatching { EmoteRepo.globalSrc() }.getOrDefault(emptyMap())
        gUrls = HashMap(u)
        gSrc = HashMap(s)
    }

    /**
     * Follow EmoteRepo: its lists change when its globals arrive or come back after a failure, and
     * when the quality setting reloads everything. The copy is refreshed a moment after the last
     * change of a burst, and a new quality reloads every channel kept here.
     */
    private fun listen() {
        if (!listening.compareAndSet(false, true)) return
        EmoteRepo.addPickerListener {
            if (refreshQueued.compareAndSet(false, true)) work.schedule({
                refreshQueued.set(false)
                runCatching { refresh() }.onFailure { log("SE refresh failed: $it") }
            }, 1, TimeUnit.SECONDS)
        }
    }

    private fun refresh() {
        snapshotGlobals()
        val q = quality()
        if (q == loadedQuality) return
        loadedQuality = q
        cache.evictAll()
        log("SE emote quality changed; reloading ${sets.size} side channel(s)")
        for (id in sets.keys.toList()) {
            if (!loading.add(id)) continue
            val login = logins[id].orEmpty()
            lists.execute { fetch(id, login, q, 0) }
        }
    }

    private fun quality(): String =
        "${EmoteQuality.sevenTv}|${EmoteQuality.bttv}|${EmoteQuality.ffz}|${EmoteQuality.twitch}"

    // ---- source switches ----

    /** The per-source switches, read again only when a setting changes: [urlOf] runs for every
     *  word of every line drawn. */
    private class Toggles(val rev: Int, val stv: Boolean, val stvGlobal: Boolean, val bttv: Boolean,
                          val ffz: Boolean, val homies: Boolean, val twitchSize: String) {
        /** Same rules as EmoteRepo.enabled, told whether the word came from the channel's own
         *  list, which EmoteRepo can only guess from its 7TV global set. */
        fun allows(src: String?, word: String, channel: Boolean): Boolean = when (src) {
            "7tv" -> stv && (channel || stvGlobal || word !in EmoteRepo.stvGlobal)
            "bttv" -> bttv
            "ffz" -> ffz
            "homies" -> homies
            else -> true
        }
    }

    @Volatile private var toggleCache: Toggles? = null

    private fun toggles(): Toggles {
        val r = Settings.rev
        toggleCache?.let { if (it.rev == r) return it }
        return Toggles(r,
            stv = Settings.get(Settings.KEY_SRC_SEVENTV),
            stvGlobal = Settings.get(EmoteRepo.KEY_STV_GLOBAL, true),
            bttv = Settings.get(Settings.KEY_SRC_BTTV),
            ffz = Settings.get(Settings.KEY_SRC_FFZ),
            homies = Settings.get(EmoteRepo.KEY_HOMIES, false),
            twitchSize = EmoteQuality.twitch).also { toggleCache = it }
    }
}
