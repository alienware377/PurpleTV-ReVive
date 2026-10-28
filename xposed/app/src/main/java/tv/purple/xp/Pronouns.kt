package tv.purple.xp

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Pronoun tags from pronouns.alejo.io.
 *
 * Unlike badges there is no global manifest — pronouns are looked up per login — so the whole
 * design here is about making that cheap and keeping it OFF the render thread.
 *
 * [forLogin] never blocks and never performs I/O. On a cache miss it returns null immediately and
 * queues a background fetch, so the tag appears on that user's NEXT message. That one-message lag
 * is the price of not stalling chat layout, and it is worth paying.
 *
 * Endpoints re-verified live on 2026-07-27 (both API generations are up; v1 is used because only
 * it can express an alternate pronoun):
 *   GET api.pronouns.alejo.io/v1/pronouns       -> { "hehim": {name,subject,object,singular}, … }
 *   GET api.pronouns.alejo.io/v1/users/<login>  -> { channel_id, channel_login, pronoun_id,
 *                                                    alt_pronoun_id }   (404/empty when unset)
 */
object Pronouns {

    const val KEY = "pronouns"

    private const val TTL_HIT = 6L * 60 * 60 * 1000     // pronouns change rarely
    private const val TTL_MISS = 30L * 60 * 1000        // but a user who just set theirs should
                                                        // show up within the same session
    private const val MAX_ENTRIES = 2000
    private const val MIN_REQUEST_GAP_MS = 200L         // ~5 req/s
    private const val LABEL_RETRY_MS = 60_000L          // after a failed label list load

    fun enabled(): Boolean = Settings.get(KEY, true)

    /** id -> rendered label, e.g. "hehim" -> "He/Him". Fetched at startup, and retried about once
     *  a minute while it is still empty (a start with no network must not break tags all session). */
    private val labels = ConcurrentHashMap<String, String>()
    @Volatile private var labelsTriedAt = 0L

    /** [id] is null for a NEGATIVE entry (user has no pronoun set) — cached so a chatty user
     *  without pronouns isn't re-queried on every line. [alt] is the alternate pronoun id, if any.
     *  Ids, not text, are cached so a label list that loads late still fixes these entries. */
    private class Entry(val id: String?, val alt: String?, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Logins with a fetch already queued or running, so ten messages in a row fire one request. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val queue = LinkedBlockingQueue<String>()

    @Volatile private var started = false

    // Synchronized because enqueue can also start the worker, possibly from more than one thread.
    @Synchronized
    fun loadAsync() {
        if (started || !enabled()) return
        started = true
        Thread({
            ensureLabels()
            worker()
        }, "ptv-pronouns").apply { isDaemon = true }.start()
    }

    /** Loads the label list if it is still empty, at most once per [LABEL_RETRY_MS].
     *  Worker thread only: a failing request can take the full 10 s timeout. */
    private fun ensureLabels() {
        if (labels.isNotEmpty() || !enabled()) return
        val now = System.currentTimeMillis()
        if (now - labelsTriedAt < LABEL_RETRY_MS) return
        labelsTriedAt = now
        runCatching { loadLabels() }.onFailure { log("PRONOUN labels failed: $it") }
    }

    /**
     * Rendered pronoun label for [login], or null if unknown/unset/not yet fetched.
     * Safe to call from the render thread: pure map lookups plus an unbounded-queue offer (and,
     * at most once per process, starting the worker thread).
     */
    fun forLogin(login: String): String? {
        if (!enabled() || login.isEmpty()) return null
        val key = login.lowercase()
        val e = cache[key]
        val now = System.currentTimeMillis()
        if (e != null) {
            val fresh = now - e.at < (if (e.id == null) TTL_MISS else TTL_HIT)
            if (fresh) return render(e)
        }
        enqueue(key)
        // Serve the stale value while the refresh is in flight rather than blinking the tag off.
        return e?.let { render(it) }
    }

    /** Label text for a cached entry, built here rather than at fetch time so a late label load
     *  fixes entries already cached. Null while any label is missing, so a raw id like "sheher"
     *  never reaches chat. */
    private fun render(e: Entry): String? {
        val main = e.id?.let { labels[it] } ?: return null
        val alt = e.alt ?: return main
        val second = labels[alt] ?: return null
        // "She/They" style: subject of the primary, subject of the alternate. The subject is the
        // first component of a rendered label ("He/Him" -> "He").
        return main.substringBefore('/') + "/" + second.substringBefore('/')
    }

    private fun enqueue(login: String) {
        // Pronouns were off at startup and got switched on later (settings sync from another
        // device, for one), so nothing started the worker yet. forLogin already checked enabled().
        if (!started) loadAsync()
        if (!inFlight.add(login)) return
        queue.offer(login)
    }

    // GET /v1/pronouns -> { "<id>": {name, subject, object, singular} }
    private fun loadLabels() {
        val root = JSONObject(EmoteRepo.httpGet("https://api.pronouns.alejo.io/v1/pronouns"))
        for (id in root.keys()) {
            val o = root.optJSONObject(id) ?: continue
            val subject = o.optString("subject"); if (subject.isEmpty()) continue
            // v1 hands back the PARTS, not a label. "singular" ids ("any", "other") render as the
            // subject alone; everything else as "Subject/Object".
            labels[id] = if (o.optBoolean("singular")) subject
                         else subject + "/" + o.optString("object").ifEmpty { subject }
        }
        log("PRONOUN labels: ${labels.size}")
    }

    private fun worker() {
        while (true) {
            // poll with a timeout, not take: a quiet chat where every chatter is already cached
            // queues nothing, and a failed label load must still be retried there.
            val login = runCatching { queue.poll(LABEL_RETRY_MS, TimeUnit.MILLISECONDS) }
                .getOrElse { return }
            ensureLabels()
            if (login == null) continue
            runCatching { fetch(login) }
                .onFailure {
                    // Cache as a miss so one flaky lookup can't turn into a per-message retry storm.
                    put(login, null, null)
                    log("PRONOUN '$login' failed: $it")
                }
            inFlight.remove(login)
            runCatching { Thread.sleep(MIN_REQUEST_GAP_MS) }
        }
    }

    private fun fetch(login: String) {
        val body = runCatching {
            EmoteRepo.httpGet("https://api.pronouns.alejo.io/v1/users/$login")
        }.getOrNull()
        if (body.isNullOrBlank()) { put(login, null, null); return }   // 404 == no pronoun set
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (o == null) { put(login, null, null); return }
        val primary = o.optString("pronoun_id").takeIf { it.isNotEmpty() && it != "null" }
        if (primary == null) { put(login, null, null); return }
        val alt = o.optString("alt_pronoun_id").takeIf { it.isNotEmpty() && it != "null" }
        put(login, primary, alt)
    }

    private fun put(login: String, id: String?, alt: String?) {
        // Chat churn is high but bounded; a crude clear beats unbounded growth and is invisible
        // to the user (worst case a handful of logins are re-fetched).
        if (cache.size > MAX_ENTRIES) cache.clear()
        cache[login] = Entry(id, alt, System.currentTimeMillis())
    }
}
