package tv.purple.xp

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The language of the channel being watched, for the Auto stream language (SttModels.chosen), so
 * each stream is heard by its own language's model rather than by one small model guessing the
 * language of every sentence. That guessing was the old Auto, and on stream sound it was wrong
 * often enough to spoil the captions of every stream.
 *
 * Two sources:
 *  - the language the streamer set on Twitch (broadcastSettings.language, from Twitch's GraphQL
 *    on a worker thread, once per channel, without signing in). Right for most channels, but some
 *    leave it at a default, and some tags ("OTHER", "ASL") name no spoken language;
 *  - when Whisper, the all-languages model, is downloaded: a short listen as the channel starts.
 *    Whisper hears its first sentences with no language given and says which language each one is
 *    in. Two in the tag's language confirm it; two of the first three in another language, with
 *    none in the tag's, overrule it (see [decide]). Stt then frees Whisper and loads that
 *    language's model. Whisper only goes on guessing sentence by sentence when no language could
 *    be found at all.
 *
 * Nothing here waits: callers get what is known now, and Stt is poked when more is known. The
 * answers are kept per channel until Twitch restarts (the tag is asked again after a while).
 * The pure parts (code mapping, reading the reply, the vote) are tested by StreamLangTest.
 */
object StreamLang {

    /** The GraphQL query for a channel's language tag; no sign-in needed. */
    const val QUERY = "query(\$l:String!){user(login:\$l){broadcastSettings{language}}}"

    /** A tag is asked for again after this long, when its channel is watched again. */
    private const val TAG_MS = 30 * 60_000L
    /** After a failed ask, the next try waits this long. */
    private const val RETRY_MS = 60_000L
    /** How long Stt waits for a new channel's tag before it loads a model without it. */
    private const val WAIT_MS = 5_000L
    /** Sentences shorter than this say too little about their language to count. */
    const val MIN_S = 2f
    /**
     * Letters a sentence must have per second to count. Talk has 5 to 15; Whisper over music or
     * mumbling writes a word or two in several seconds, and its language then means little.
     * A Chinese, Japanese or Korean character counts as [WIDE] letters.
     */
    const val TALK_RATE = 3f
    private const val WIDE = 3
    /** Sentences that count, after which the tag decides when no two agree. */
    const val VOTES = 3
    /** Sentences heard in all (counted or not), after which the check gives up and the tag decides. */
    const val MAX_HEARD = 12
    /** How long the "has its own model" note stays in the caption box. */
    private const val TIP_MS = 10_000L
    /** Channels remembered at most; past this the oldest answers are simply asked again. */
    private const val KEEP = 200
    /** How long after a channel change the old stream's sound can still reach the check. */
    private const val SWITCH_MS = 2_000L

    /** [lang] null: the tag names no spoken language, or the channel was not found. [ok] false: the ask failed. */
    private class Tag(val lang: String?, val ok: Boolean, val at: Long)

    private val tags = ConcurrentHashMap<String, Tag>()
    /** Channels whose tag is being asked for now, with when the ask started. */
    private val asking = ConcurrentHashMap<String, Long>()
    /** The check's answer per channel. [lang] null: no language could be told. */
    private class Heard(val lang: String?)
    private val heard = ConcurrentHashMap<String, Heard>()

    /** A check in progress: the languages of the sentences that counted, and how many were heard. */
    private class Check(val votes: ArrayList<String> = ArrayList(), var tries: Int = 0)
    private val checks = HashMap<String, Check>()

    /** The language whose "has its own model" note waits to be shown, and until when it is up (0: not shown yet). */
    @Volatile private var tipLang: String? = null
    @Volatile private var tipUntil = 0L
    private val tipped = ConcurrentHashMap.newKeySet<String>()

    // ---- pure parts ----

    private val CODE = Regex("[a-z]{2,3}")

    /**
     * Twitch's language tag as a language code: "EN" -> "en", "ZH_HK" -> "zh". Null for tags that
     * name no spoken language ("OTHER", "ASL") and anything else that is not a language code.
     */
    fun fromTwitch(tag: String?): String? {
        val t = tag?.trim()?.lowercase(Locale.ROOT)?.substringBefore('_')?.substringBefore('-') ?: return null
        if (!t.matches(CODE)) return null
        return when (t) {
            "asl" -> null
            // Old and other forms of codes Whisper writes its own way.
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            "jv" -> "jw"
            "nb" -> "no"
            "fil" -> "tl"
            "yue" -> "zh"
            else -> t
        }
    }

    /** The request body for EmoteRepo.gqlPost. */
    fun body(login: String): String =
        JSONObject().put("query", QUERY).put("variables", JSONObject().put("l", login)).toString()

    /**
     * The reply to [body]. Null when it is not an answer (an error): the ask is tried again later.
     * A missing channel or a tag naming no spoken language is an answer with no language.
     *
     * When a service behind Twitch's GraphQL fails, the reply can carry part of the data next to
     * an "errors" list, with the channel or its settings as null. That says nothing about the
     * channel, so it counts as a failed ask too, not as a channel with no language for half an hour.
     */
    fun parse(json: String): Parsed? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null
        val errors = (root.optJSONArray("errors")?.length() ?: 0) > 0
        if (!data.has("user")) return null
        if (data.isNull("user")) return if (errors) null else Parsed(null, null)
        val bs = data.optJSONObject("user")?.optJSONObject("broadcastSettings")
            ?: return if (errors) null else Parsed(null, null)
        val tag = if (bs.isNull("language")) null else bs.optString("language").ifEmpty { null }
        if (tag == null && errors) return null
        return Parsed(fromTwitch(tag), tag)
    }

    /** A reply: the language code, and the tag as Twitch wrote it (for the log). */
    class Parsed(val lang: String?, val tag: String?)

    /** Whisper's name for the language it heard ("en", or "<|en|>"), when it is one Whisper may be told. */
    fun fromWhisper(heard: String): String? =
        heard.trim().removePrefix("<|").removeSuffix("|>").lowercase(Locale.ROOT).takeIf { it in SttModels.WHISPER_LANGS }

    /**
     * True when a sentence of [seconds] that Whisper wrote as [text] counts for the check: long
     * enough, and with the letters of talk rather than of music or noise.
     */
    fun counts(text: String, seconds: Float): Boolean {
        if (seconds < MIN_S) return false
        var letters = 0
        for (c in text) if (c.isLetter()) letters += if (c.code >= 0x2E80) WIDE else 1
        return letters >= TALK_RATE * seconds
    }

    /** The check's answer: the stream's language, or null when none could be told. */
    class Answer(val lang: String?)

    /**
     * The check after another sentence: [votes] are the languages of the sentences that counted, in
     * order, [heard] all sentences heard, [tag] the channel's tag (null: none). Null while more
     * sentences are needed.
     *  - Two sentences in the tag's language confirm it.
     *  - Another language needs two of the first [VOTES] sentences, and none of them in the tag's
     *    language. A song in another language as the channel starts made two sentences agree on
     *    the song's language on a recorded stream; the third, the streamer's own, kept the tag.
     *  - With no tag, the first language two sentences agree on.
     *  - Otherwise, after [VOTES] sentences, or [MAX_HEARD] heard, the tag stays.
     */
    fun decide(votes: List<String>, tag: String?, heard: Int = votes.size): Answer? {
        val n = votes.groupingBy { it }.eachCount()
        if (tag == null || (n[tag] ?: 0) >= 2 || votes.size >= VOTES) {
            val two = n.entries.firstOrNull { it.value >= 2 && (tag == null || it.key == tag || (n[tag] ?: 0) == 0) }
            if (two != null) return Answer(two.key)
        }
        if (votes.size >= VOTES || heard >= MAX_HEARD) return Answer(tag)
        return null
    }

    // ---- the channel being watched ----

    private fun login(): String? = RefreshButton.playingLogin()?.lowercase(Locale.ROOT)

    private fun now() = SystemClock.uptimeMillis()

    /** [login]'s language as far as it is known: the check's answer, else the tag. Asks for the tag when needed. */
    private fun lang(login: String?): String? {
        if (login == null) return null
        return heard[login]?.lang ?: tag(login)
    }

    private fun tag(login: String): String? {
        val t = tags[login]
        if (t == null || now() - t.at > (if (t.ok) TAG_MS else RETRY_MS)) fetch(login)
        return t?.lang
    }

    private fun whisperReady(ctx: Context?): Boolean =
        ctx != null && SttPacks.ready(ctx, SttPacks.modelPack(SttModels.WHISPER))

    /**
     * What Auto uses now for the channel being watched. While the check is to be made, Whisper with
     * no language, whose sentences are the check. A model that would not load in this run is
     * passed over (see SttModels.autoPick), so Whisper told the language can stand in for it.
     */
    fun pick(): SttModels.AutoPick {
        val ctx = Settings.ctx()
        val l = login()
        val lang = lang(l)
        if (l != null && !heard.containsKey(l) && whisperReady(ctx)) return SttModels.AutoPick(SttModels.WHISPER, lang, null)
        return SttModels.autoPick(lang, bad = { Stt.bad(it.lang) }) { m -> ctx != null && SttPacks.ready(ctx, SttPacks.modelPack(m)) }
    }

    fun model(): SttModel = pick().model

    /**
     * True for a moment after a new channel opens, while its tag is on its way. Stt then keeps the
     * model it has and loads none, rather than one for no language that is replaced a moment later.
     */
    fun waiting(): Boolean {
        if (!SttModels.auto()) return false
        val l = login() ?: return false
        if (tags.containsKey(l)) return false
        val since = asking[l] ?: return false
        return now() - since < WAIT_MS
    }

    /**
     * The channel whose check a sentence of [seconds] that just ended goes to, or null when there
     * is no check to make. A sentence that began before the channel changed, or just after while
     * the old stream's sound is still on its way through, says nothing about the new channel.
     */
    fun checkFor(seconds: Float): String? {
        val l = login() ?: return null
        if (!SttModels.auto() || heard.containsKey(l) || RefreshButton.clipHeard()) return null
        val began = now() - (seconds * 1000f).toLong()
        return l.takeIf { began >= RefreshButton.playingSince() + SWITCH_MS }
    }

    /**
     * One sentence of the check for [login]: Whisper heard [text] in [lang]. True when this settles
     * the channel's language, so Stt loads its model. Any thread.
     */
    fun heard(login: String, lang: String, text: String, seconds: Float): Boolean {
        if (heard.containsKey(login)) return false
        var votes = emptyList<String>()
        val answer = synchronized(checks) {
            if (login !in checks && checks.size >= KEEP) checks.clear()
            val c = checks.getOrPut(login) { Check() }
            c.tries++
            val code = fromWhisper(lang)
            if (code != null && counts(text, seconds)) c.votes.add(code)
            votes = c.votes.toList()
            decide(c.votes, tags[login]?.lang, c.tries)?.also { checks.remove(login) }
        } ?: return false
        if (heard.size >= KEEP) heard.clear()
        heard[login] = Heard(answer.lang)
        log("SL $login: heard ${votes.ifEmpty { listOf("nothing") }.joinToString(",")}, tag ${tags[login]?.lang ?: "none"}, " +
            "so ${answer.lang ?: "no language known"}")
        return true
    }

    /**
     * The check can not be made for the channel being watched (Whisper would not load): its tag
     * decides. True only when that is new for the channel, so Stt tries the tag's model once at
     * once rather than waiting for something else to look again.
     */
    fun skipCheck(): Boolean {
        val l = login() ?: return false
        if (heard.putIfAbsent(l, Heard(null)) != null) return false
        log("SL $l: no language check, the tag decides")
        return true
    }

    /**
     * Stt loaded [m]. When that is Whisper told a language whose own model is not downloaded, the
     * caption box says so for a few seconds, once per language until Twitch restarts. It is only
     * kept for later here: the few seconds start, and the language counts as told, when the box
     * first shows it ([tip]). A model loaded during an ad, or with live captions off, would
     * otherwise use the note up with no box there to show it.
     */
    fun loaded(m: SttModel) {
        if (m.whisperLang.isEmpty() || !SttModels.auto()) return
        val p = pick()
        if (p.missing == null || p.model.key != m.key || m.whisperLang in tipped) return
        tipUntil = 0L
        tipLang = m.whisperLang
    }

    /**
     * The note of [loaded], for the live caption box, which asks only while it is on screen. Its
     * first answer starts the few seconds it stays up. Nothing once Auto has moved on to another
     * model (another channel, or the language's own model downloaded meanwhile). Main thread.
     */
    fun tip(): String? {
        val lang = tipLang ?: return null
        val p = pick()
        val own = p.missing
        if (own == null || p.model.whisperLang != lang) { tipLang = null; return null }
        if (tipUntil == 0L) {
            if (!tipped.add(lang)) { tipLang = null; return null }
            tipUntil = now() + TIP_MS
        }
        if (now() >= tipUntil) { tipLang = null; return null }
        return "${own.label} has its own speech model, which gives much better captions. Download it under Player, During ad breaks"
    }

    /** For the caption box when a download is missing in Auto: which language it is for, when that is known. */
    fun missingNote(): String? {
        if (!SttModels.auto()) return null
        val lang = lang(login()) ?: return null
        return "Captions in ${SttModels.langName(lang, I18n.shownLang())} need a download first: Player, During ad breaks"
    }

    /** One line for the test status command. */
    fun status(): String {
        val l = login()
        val c = synchronized(checks) { l?.let { checks[it]?.votes?.toList() } }
        return "auto=${SttModels.auto()} channel=$l tag=${l?.let { tags[it]?.lang }} heard=${l?.let { heard[it]?.lang }} " +
            "checking=${c ?: "-"} pick=${runCatching { pick().model.key }.getOrDefault("?")}"
    }

    /** Asks Twitch for [login]'s tag on a worker thread, once at a time, then pokes Stt. */
    private fun fetch(login: String) {
        if (asking.putIfAbsent(login, now()) != null) return
        Thread({
            val raw = runCatching { EmoteRepo.gqlPost("", body(login)) }.getOrNull()
            val got = raw?.let { parse(it) }
            if (tags.size >= KEEP) tags.clear()
            // A reply that is not the answer counts as a failed ask, tried again after RETRY_MS.
            // A tag known from before stays meanwhile, so a failed refresh never switches models.
            tags[login] = if (got != null) Tag(got.lang, true, now()) else Tag(tags[login]?.lang, false, now())
            asking.remove(login)
            log("SL $login: " + if (got == null) "language tag not known (ask failed)" else "language tag ${got.tag ?: "none"}")
            runCatching { Stt.poke() }
        }, "ptv-stream-lang").apply { isDaemon = true }.start()
    }
}
