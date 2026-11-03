package tv.purple.xp

import ai.onnxruntime.NativeLibs
import ai.onnxruntime.OrtEnvironment
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Translation of chat lines and live captions, for the rest of the module to call.
 *
 * Engines plug in through [Engine]: today the offline Opus-MT one ([OpusEngine], everything on the
 * phone after a download), and any added later. [translate] asks each registered engine
 * in turn and uses the first that is ready for the pair.
 *
 * Threads: [translate] blocks for as long as the model needs (a chat line takes some tenths of a
 * second on a phone, the first call per model a few seconds more to load it), so call it from a
 * worker thread, never the main thread. It is safe to call from several threads at once; each
 * model works on one line at a time. Download callbacks arrive on the main thread.
 */
object Translator {

    /** A translation engine. Every method may be called from any thread. */
    interface Engine {
        /** Short id for logs and settings, such as "opus". */
        val id: String
        /** The language codes it can translate into. */
        fun targets(): Set<String>
        /** True when it can translate [from] into [to] right now, without a download. Cheap. */
        fun ready(ctx: Context, from: String, to: String): Boolean
        /** True when it can translate any language it knows into [to] right now. Cheap. */
        fun readyInto(ctx: Context, to: String): Boolean
        /**
         * Blocking. Null when it could not (not ready, failed, or [cancel] said stop). [from] is a
         * real language code here, never null or LangGuess.UNKNOWN.
         */
        fun translate(ctx: Context, text: String, from: String, to: String, cancel: () -> Boolean): String?
        /** Frees whatever it holds in memory. It loads again on the next [translate]. */
        fun unload()
    }

    /** A language the settings page offers as a target. */
    class Language(val code: String, val name: String)

    /** The targets, in the order the settings page shows them. English first. */
    val LANGUAGES: List<Language> = OpusEngine.TARGETS.map { Language(it.code, it.name) }

    /** A guess below this is treated as unknown, and the line is left alone. */
    const val MIN_CONFIDENCE = 0.4f
    /** Longer text is cut to this (Twitch's own chat limit is 500). */
    private const val MAX_CHARS = 500

    private val engines = java.util.concurrent.CopyOnWriteArrayList<Engine>(listOf(OpusEngine))

    /** Adds an engine. [first] puts it ahead of the others, so it is asked first. */
    fun register(engine: Engine, first: Boolean = false) {
        if (engines.any { it.id == engine.id }) return
        if (first) engines.add(0, engine) else engines.add(engine)
    }

    fun languages(): List<Language> = LANGUAGES

    fun name(code: String): String = LANGUAGES.firstOrNull { it.code == code }?.name ?: code

    /**
     * True when some engine can translate into [target] now. For the offline engine that means
     * the engine pack and the model for [target] are downloaded, plus an into English model
     * (the general one or a chat language's own; chat in other languages goes through English).
     */
    fun isReady(target: String): Boolean = Settings.ctx()?.let { isReady(it, target) } ?: false

    fun isReady(ctx: Context, target: String): Boolean =
        engines.any { e -> runCatching { e.readyInto(ctx, target) }.getOrDefault(false) }

    /**
     * Translates [text] into [to]. [from] null means guess it with LangGuess. Blocking: call it
     * from a worker thread. Null when nothing should be shown: the language is unknown or already
     * [to], no engine is ready, it failed, or [cancel] said stop.
     */
    fun translate(text: String, from: String?, to: String, cancel: () -> Boolean = { false }): String? {
        val ctx = Settings.ctx() ?: return null
        val t = text.trim().let { if (it.length > MAX_CHARS) it.substring(0, MAX_CHARS) else it }
        if (t.isEmpty()) return null
        val src = from ?: LangGuess.guess(t).takeIf { it.known && it.confidence >= MIN_CONFIDENCE }?.lang ?: return null
        if (src == to || src == LangGuess.UNKNOWN) return null
        val key = "$src>$to\u0000$t"
        cache.get(key)?.let { return it }
        for (e in engines) {
            if (cancel()) return null
            if (!runCatching { e.ready(ctx, src, to) }.getOrDefault(false)) continue
            val t0 = System.nanoTime()
            val out = try {
                e.translate(ctx, t, src, to, cancel)
            } catch (c: java.util.concurrent.CancellationException) {
                return null
            } catch (x: Throwable) {
                safeLog("TR ${e.id} $src>$to failed: ${x.javaClass.simpleName}")
                null
            }
            if (out.isNullOrBlank()) continue
            // Lengths and time only, never the chat text itself.
            safeLog("TR ${e.id} $src>$to ${t.length}>${out.length} chars in ${(System.nanoTime() - t0) / 1_000_000} ms")
            cache.put(key, out)
            return out
        }
        return null
    }

    /** Frees every engine's models. They load again on the next translate. */
    fun unload() { for (e in engines) runCatching { e.unload() } }

    fun clearCache() = cache.clear()

    // ---- downloads, for the settings page (offline engine) ----

    fun download(ctx: Context, lang: String, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) =
        TranslatePacks.download(ctx, lang, progress, done)

    /** Removes the model only [lang] uses. The engine and the shared into English model stay. */
    fun delete(ctx: Context, lang: String) = TranslatePacks.delete(ctx, lang)

    /** Removes every translation download, the engine too. */
    fun deleteAll(ctx: Context) = TranslatePacks.deleteAll(ctx)

    /** One line for [lang]'s row: download size, or downloaded, or how far the download is. */
    fun packLine(ctx: Context, lang: String): String = TranslatePacks.packLine(ctx, lang)

    /** What is downloaded and how much space it takes, for the settings page. */
    fun sizeSummary(ctx: Context): String = TranslatePacks.sizeSummary(ctx)

    fun downloading(): Boolean = TranslatePacks.downloading()

    /** Credit lines (the models' CC-BY licence asks for them). */
    fun credits(): String = TranslatePins.ENGINE_CREDIT + "\n" + TranslatePins.MODEL_CREDIT

    // ---- cache ----

    private val cache = Lru(256)

    private class Lru(private val max: Int) {
        private val map = object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > max
        }
        @Synchronized fun get(k: String): String? = map[k]
        @Synchronized fun put(k: String, v: String) { map[k] = v }
        @Synchronized fun clear() = map.clear()
    }
}

/** log() goes through XposedBridge, which is not there in JVM tests. */
internal fun safeLog(msg: String) { runCatching { log(msg) } }

/**
 * The translation downloads, stored and checked by SttPacks' pack code in their own group
 * (getFilesDir()/ptv_tr): the engine (ONNX Runtime) and one pack per Opus-MT model.
 */
object TranslatePacks {

    internal val GROUP = SttPacks.Group("ptv_tr", "TR", "ptv-tr-dl")

    internal val ENGINE = SttPacks.Pack("translation engine", "engine", TranslatePins.ENGINE_ASSET,
        TranslatePins.ENGINE_SHA256, TranslatePins.ENGINE_BYTES, TranslatePins.ENGINE_ENTRIES, GROUP, TranslatePins.TAG)

    // Each pack is fetched from the release its pin names: translate-pack-1 for the first models,
    // translate-pack-2 for the into English models per chat language.
    internal val MODELS: Map<String, SttPacks.Pack> = TranslatePins.MODELS.mapValues { (name, m) ->
        SttPacks.Pack("translation model $name", "model-$name", m.asset, m.sha256, m.bytes, m.entries, GROUP, m.tag)
    }

    private const val UNSUPPORTED = "Offline translation needs a phone with a 64-bit ARM processor"
    private val main = Handler(Looper.getMainLooper())

    fun supported(): Boolean = SttPacks.supported()
    fun engineReady(ctx: Context): Boolean = SttPacks.ready(ctx, ENGINE)
    fun modelReady(ctx: Context, model: String): Boolean = MODELS[model]?.let { SttPacks.ready(ctx, it) } ?: false
    fun downloading(): Boolean = ENGINE.busy.get() || MODELS.values.any { it.busy.get() }

    internal fun modelDir(ctx: Context, model: String): File = SttPacks.dir(ctx, MODELS.getValue(model))

    /** Every pack translating into [lang] needs: the engine, the into English model, and [lang]'s own. */
    internal fun packsFor(lang: String): List<SttPacks.Pack> {
        val own = OpusEngine.target(lang)?.model ?: return emptyList()
        return listOfNotNull(ENGINE, MODELS[OpusEngine.INTO_EN], MODELS[own]).distinct()
    }

    /** Downloads what [lang] still misses, one pack after the other, with one overall progress. */
    fun download(ctx: Context, lang: String, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) {
        val all = packsFor(lang)
        if (all.isEmpty()) { main.post { done(false, "There is no offline model for ${Translator.name(lang)}") }; return }
        downloadAll(ctx, all, "${Translator.name(lang)} is downloaded and checked", progress, done)
    }

    // ---- into English per chat language (translate-pack-2) ----

    /** The own into English pack of chat language [lang], or null when it has none. */
    internal fun chatPack(lang: String): SttPacks.Pack? = OpusEngine.chatLang(lang)?.let { MODELS[it.model] }

    /** True when chat language [lang] has its own into English model on the phone. */
    fun chatLangReady(ctx: Context, lang: String): Boolean = chatPack(lang)?.let { SttPacks.ready(ctx, it) } ?: false

    /** What [downloadChatLangs] would fetch for [langs], in bytes: their models and maybe the engine. */
    fun chatLangBytes(ctx: Context, langs: Collection<String>): Long =
        chatPacks(langs).filter { !SttPacks.ready(ctx, it) }.sumOf { it.bytes }

    /** The engine (a model is no use without it) and the own into English models of [langs]. */
    private fun chatPacks(langs: Collection<String>): List<SttPacks.Pack> =
        (listOf(ENGINE) + langs.mapNotNull { chatPack(it) }).distinct()

    /**
     * Downloads the own into English models of chat languages [langs], and the engine if it is not
     * there yet (a model is no use without it). The general mul-en model is not needed for these.
     */
    fun downloadChatLangs(ctx: Context, langs: Collection<String>, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) {
        val names = langs.joinToString(", ") { Translator.name(it) }
        downloadAll(ctx, chatPacks(langs), "Better translation from $names is downloaded and checked", progress, done)
    }

    /** Removes the own into English models of chat languages [langs]. Lines in them fall back to mul-en. */
    fun deleteChatLangs(ctx: Context, langs: Collection<String>) {
        val packs = langs.mapNotNull { chatPack(it) }.distinct()
        if (packs.isNotEmpty()) drop(ctx, packs, "chat language models deleted: ${langs.joinToString(",")}")
    }

    /** Downloads the packs of [all] that are not on the phone yet, one after the other. */
    private fun downloadAll(ctx: Context, all: List<SttPacks.Pack>, okMsg: String, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext ?: ctx
        val todo = all.filter { !SttPacks.ready(app, it) }
        if (todo.isEmpty()) { main.post { done(true, okMsg) }; return }
        val total = todo.sumOf { it.bytes }.coerceAtLeast(1)
        // A delete between two packs must stop the rest too, though nothing is downloading just then.
        val gen = GROUP.cancelGen
        fun step(i: Int, before: Long) {
            if (gen != GROUP.cancelGen) { done(false, "The download was stopped"); return }
            if (i >= todo.size) { done(true, okMsg); return }
            val p = todo[i]
            SttPacks.downloadPack(app, p, UNSUPPORTED, { pct ->
                progress(((before + p.bytes * pct / 100) * 100 / total).toInt())
            }, { ok, msg ->
                if (ok) step(i + 1, before + p.bytes) else done(false, msg)
            })
        }
        step(0, 0L)
    }

    /** Removes the model only [lang] uses, after unloading it. English removes the general into English model. */
    fun delete(ctx: Context, lang: String) {
        val model = OpusEngine.target(lang)?.model ?: return
        // A model another language shares (none today) is kept.
        if (OpusEngine.TARGETS.count { it.model == model } > 1 && lang != "en") return
        val p = MODELS[model] ?: return
        drop(ctx, listOf(p), "model $model deleted")
    }

    fun deleteAll(ctx: Context) = drop(ctx, listOf(ENGINE) + MODELS.values, "all deleted")

    private fun drop(ctx: Context, packs: List<SttPacks.Pack>, why: String) {
        val app = ctx.applicationContext ?: ctx
        // A download in this group stops at its next read; cutting the call ends a stalled one.
        // Always bumped: a multi-pack download between two packs has nothing busy, yet must stop.
        GROUP.cancelGen++
        if (packs.any { it.busy.get() }) for (p in packs) runCatching { p.call?.cancel() }
        Thread({
            // Marked gone first, so a translation starting now does not load it again in between.
            for (p in packs) p.ready = false
            for (p in packs) OpusEngine.unloadPack(p)
            synchronized(SttPacks.swapLock) {
                for (p in packs) {
                    SttPacks.drop(app, p)
                    runCatching { File(File(SttPacks.root(app, GROUP), "dl"), p.asset + ".part").delete() }
                }
                if (packs.contains(ENGINE)) runCatching { SttPacks.root(app, GROUP).deleteRecursively() }
            }
            // A load that slipped in before the files went is let go now.
            for (p in packs) OpusEngine.unloadPack(p)
            Translator.clearCache()
            safeLog("TR packs: $why")
        }, "ptv-tr-del").apply { isDaemon = true }.start()
    }

    fun packLine(ctx: Context, lang: String): String {
        if (!supported()) return "Not available on this phone: it needs a 64-bit ARM processor"
        val packs = packsFor(lang)
        if (packs.isEmpty()) return "No offline model"
        val busy = packs.filter { it.busy.get() }
        if (busy.isNotEmpty()) return "Downloading: ${busy.first().progress}%"
        val missing = packs.filter { !SttPacks.ready(ctx, it) }
        if (missing.isEmpty()) return "Downloaded and checked (${mb(packs.sumOf { it.unpacked })} MB on the phone with the shared parts)"
        return "${mb(missing.sumOf { it.bytes })} MB download, ${mb(missing.sumOf { it.unpacked })} MB on the phone. Not downloaded yet"
    }

    fun sizeSummary(ctx: Context): String {
        if (!supported()) return "Offline translation is not available on this phone: it needs a 64-bit ARM processor."
        val lines = ArrayList<String>()
        lines.add("Engine (ONNX Runtime ${TranslatePins.ORT_VERSION}): " + state(ctx, ENGINE))
        for (t in OpusEngine.TARGETS) {
            val p = MODELS[t.model] ?: continue
            val label = if (t.code == "en") "Into English (general, from any language)" else "English to ${t.name}"
            lines.add("$label: " + state(ctx, p))
        }
        for (c in OpusEngine.CHAT_LANGS) {
            val p = MODELS[c.model] ?: continue
            // Only the ones on the phone or on their way, so the list stays short.
            if (p.busy.get() || SttPacks.ready(ctx, p)) lines.add("${c.name} into English: " + state(ctx, p))
        }
        val have = (listOf(ENGINE) + MODELS.values).filter { SttPacks.ready(ctx, it) }
        lines.add("On the phone: ${mb(have.sumOf { it.unpacked })} MB")
        return lines.joinToString("\n")
    }

    private fun state(ctx: Context, p: SttPacks.Pack): String = when {
        p.busy.get() -> "downloading ${p.progress}%"
        SttPacks.ready(ctx, p) -> "downloaded (${mb(p.unpacked)} MB)"
        else -> "not downloaded (${mb(p.bytes)} MB download)"
    }

    private fun mb(b: Long) = (b + 500_000) / 1_000_000
}

/**
 * The offline engine: Helsinki-NLP's Opus-MT models on ONNX Runtime, on the phone. Into English
 * with the chat language's own model when it is downloaded (es-en, de-en, ...: much better), or
 * else with the general opus-mt-mul-en; out of English with one model per language. Between two
 * other languages it goes through English (two models, so a little slower and a little worse).
 *
 * A chat with several foreign languages can make the into English models take turns in memory
 * (each load takes a few seconds). The English into target model is used for every line, so it
 * stays loaded; only the into English slot changes.
 *
 * Memory: a loaded model takes about 250 MB (both quantized graphs, about 110 MB on disk, plus ONNX
 * Runtime's working memory; MarianModel hands the arena back after each line). So at most
 * [MAX_LOADED] stay loaded (a line between two other languages needs two), and all are let go after
 * [IDLE_MS] without use or when Translator.unload is called.
 */
object OpusEngine : Translator.Engine {

    /** A target: its code, its name, the model that translates English into it, and the model's language token. */
    class Target(val code: String, val name: String, val model: String, val token: String?)

    const val INTO_EN = "mul-en"

    val TARGETS: List<Target> = listOf(
        Target("en", "English", INTO_EN, null),
        Target("es", "Spanish", "en-es", null),
        // opus-mt-en-ROMANCE serves several languages and needs the target as a first token.
        Target("pt", "Portuguese", "en-ROMANCE", ">>pt_br<<"),
        Target("de", "German", "en-de", null),
        Target("fr", "French", "en-fr", null),
        Target("it", "Italian", "en-it", null),
        Target("ru", "Russian", "en-ru", null),
        Target("zh", "Chinese", "en-zh", ">>cmn_Hans<<"),
        Target("ar", "Arabic", "en-ar", ">>ara<<")
    )

    fun target(code: String): Target? = TARGETS.firstOrNull { it.code == code }

    /**
     * A chat language with an into English model of its own (translate-pack-2). [model] is the one
     * the settings page downloads for it; [fallbacks] are others that also know it, tried next.
     */
    class ChatLang(val code: String, val name: String, val model: String, val fallbacks: List<String> = emptyList())

    /** One per target language except English, in the same order. */
    val CHAT_LANGS: List<ChatLang> = listOf(
        ChatLang("es", "Spanish", "es-en", listOf("ROMANCE-en")),
        // There is no Xenova pt-en; ROMANCE-en knows Portuguese (and Spanish, French, Italian).
        ChatLang("pt", "Portuguese", "ROMANCE-en"),
        ChatLang("de", "German", "de-en"),
        ChatLang("fr", "French", "fr-en", listOf("ROMANCE-en")),
        ChatLang("it", "Italian", "it-en", listOf("ROMANCE-en")),
        ChatLang("ru", "Russian", "ru-en"),
        ChatLang("zh", "Chinese", "zh-en"),
        ChatLang("ar", "Arabic", "ar-en")
    )

    fun chatLang(code: String): ChatLang? = CHAT_LANGS.firstOrNull { it.code == code }

    /**
     * The model that takes chat language [from] into English, on this phone right now: its own
     * model, then one that also knows it, then the general mul-en. Null when none is downloaded.
     */
    internal fun intoEnglish(ctx: Context, from: String, keep: String? = null): String? {
        val own = chatLang(from)
        val tries = (if (own == null) listOf(INTO_EN) else listOf(own.model) + own.fallbacks + INTO_EN)
            .filter { TranslatePacks.modelReady(ctx, it) }
        // Only two models fit at once. When the best one is not loaded and loading it would push
        // out a model still needed ([keep], the English into target one), a usable one that is
        // already loaded is taken for this line instead. Two languages taking turns in chat
        // would otherwise reload a model (seconds, ~250 MB) on almost every line.
        val best = tries.firstOrNull() ?: return null
        val roomy = models.containsKey(best) || models.size < MAX_LOADED - (if (keep != null && !models.containsKey(keep)) 1 else 0)
        val pick = if (roomy) best else tries.firstOrNull { models.containsKey(it) } ?: best
        // Remembered so the settings page can suggest the better download.
        if (own != null && pick != own.model) missed.add(own.code)
        return pick
    }

    /** Chat languages translated lately without their own model (since Twitch started), for a hint in settings. */
    private val missed: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    fun missedLangs(ctx: Context): List<String> = CHAT_LANGS.map { it.code }.filter { it in missed && !TranslatePacks.chatLangReady(ctx, it) }

    /** True when some into English model is downloaded: the general one or any chat language's own. */
    private fun anyIntoEnglish(ctx: Context): Boolean =
        TranslatePacks.modelReady(ctx, INTO_EN) || CHAT_LANGS.any { TranslatePacks.modelReady(ctx, it.model) }

    private const val MAX_LOADED = 2
    private const val IDLE_MS = 3 * 60_000L

    override val id = "opus"
    override fun targets(): Set<String> = TARGETS.map { it.code }.toSet()

    private val models = ConcurrentHashMap<String, MarianModel>()
    /** Last use per loaded model, for picking which one to let go. */
    private val used = ConcurrentHashMap<String, Long>()
    private val loadLock = Any()
    @Volatile private var nativeOk = false
    /** Why the engine can not run in this process; set once, until Twitch restarts. */
    @Volatile var broken: String? = null
        private set

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ptv-tr-idle").apply { isDaemon = true } }
    @Volatile private var idleTask: ScheduledFuture<*>? = null

    override fun ready(ctx: Context, from: String, to: String): Boolean {
        if (broken != null || !TranslatePacks.supported() || !TranslatePacks.engineReady(ctx)) return false
        val t = target(to) ?: return false
        if (from != "en" && intoEnglish(ctx, from) == null) return false
        return to == "en" || TranslatePacks.modelReady(ctx, t.model)
    }

    /**
     * The engine, some into English model (chat in other languages goes through English) and,
     * unless [to] is English, the English into [to] model are there. With only some chat
     * languages' own models and no general one, lines in other languages are simply left alone.
     */
    override fun readyInto(ctx: Context, to: String): Boolean {
        if (broken != null || !TranslatePacks.supported() || !TranslatePacks.engineReady(ctx)) return false
        val t = target(to) ?: return false
        return anyIntoEnglish(ctx) && (to == "en" || TranslatePacks.modelReady(ctx, t.model))
    }

    override fun translate(ctx: Context, text: String, from: String, to: String, cancel: () -> Boolean): String? {
        val t = target(to) ?: return null
        if (!loadNative(ctx)) return null
        val keep = if (to == "en") null else t.model
        val english = if (from == "en") text else {
            val name = intoEnglish(ctx, from, keep) ?: return null
            (model(ctx, name, keep) ?: return null).translate(text, null, cancel)
        }
        val out = if (to == "en") english else (model(ctx, t.model) ?: return null).translate(EnglishTidy.prep(english, to), t.token, cancel)
        scheduleIdle()
        return out
    }

    /** The loaded model, loading it (checked against its pins once per process) when needed. */
    private fun model(ctx: Context, name: String, keep: String? = null): MarianModel? {
        models[name]?.let { used[name] = System.currentTimeMillis(); return it }
        synchronized(loadLock) {
            models[name]?.let { return it }
            // Make room first: the oldest loaded model goes.
            // Picked from what is loaded, so a stale "used" entry can never be picked forever.
            while (models.size >= MAX_LOADED) {
                // The English into target model is needed for every line: it goes last.
                val old = models.keys.filter { it != keep }.minByOrNull { used[it] ?: 0L } ?: models.keys.minByOrNull { used[it] ?: 0L } ?: break
                release(old, "room for $name")
            }
            val p = TranslatePacks.MODELS[name] ?: return null
            val t0 = System.nanoTime()
            val m = synchronized(SttPacks.swapLock) {
                val why = SttPacks.ensureHashed(ctx, p)
                if (why != null) { safeLog("TR model $name: $why"); return null }
                try {
                    MarianModel(SttPacks.dir(ctx, p), THREADS)
                } catch (t: Throwable) {
                    safeLog("TR model $name failed to load: $t")
                    return null
                }
            }
            models[name] = m
            used[name] = System.currentTimeMillis()
            safeLog("TR model $name loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return m
        }
    }

    /**
     * Checks both libraries against their pins, then hands their paths to the patched ONNX
     * Runtime loader, which System.load()s them on first use: libonnxruntime first, then the JNI
     * glue. A failure leaves offline translation off until Twitch restarts, never a crash.
     */
    private fun loadNative(ctx: Context): Boolean {
        if (nativeOk) return true
        if (broken != null) return false
        synchronized(loadLock) {
            if (nativeOk) return true
            val bad: String? = synchronized(SttPacks.swapLock) {
                val e = TranslatePacks.ENGINE
                for (name in listOf(TranslatePins.ENGINE_ORT_LIB, TranslatePins.ENGINE_JNI_LIB)) {
                    val pin = e.entries[name] ?: return@synchronized "no pin for $name"
                    val f = SttPacks.file(ctx, e, name)
                    val ok = runCatching { f.length() == pin.bytes && SttPacks.sha256(f) == pin.sha256 }.getOrDefault(false)
                    if (!ok) {
                        SttPacks.drop(ctx, e)
                        safeLog("TR engine file $name failed its check and was deleted")
                        // Not broken: it can be downloaded again.
                        return false
                    }
                }
                try {
                    NativeLibs.set(SttPacks.file(ctx, e, TranslatePins.ENGINE_ORT_LIB), SttPacks.file(ctx, e, TranslatePins.ENGINE_JNI_LIB))
                    initOrt()
                    null
                } catch (t: Throwable) {
                    safeLog("TR ONNX Runtime failed to load: $t")
                    "the engine could not be loaded (${t.javaClass.simpleName})"
                }
            }
            if (bad != null) { broken = bad; safeLog("TR off until Twitch restarts: $bad"); return false }
            val v = runCatching { OrtEnvironment.getEnvironment().version }.getOrDefault("?")
            if (v != TranslatePins.ORT_VERSION) {
                broken = "engine $v does not match ${TranslatePins.ORT_VERSION}"
                safeLog("TR off until Twitch restarts: $broken")
                return false
            }
            nativeOk = true
            safeLog("TR native loaded: onnxruntime $v")
            return true
        }
    }

    /**
     * The first ORT call, which loads the libraries. It runs from this module class, so System.load
     * ties them to the module's class loader, where the ai.onnxruntime classes live.
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun initOrt() {
        OrtEnvironment.getEnvironment()
    }

    @Synchronized
    private fun scheduleIdle() {
        idleTask?.cancel(false)
        idleTask = timer.schedule({ unload() }, IDLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun release(name: String, why: String) {
        used.remove(name)
        val m = models.remove(name) ?: return
        runCatching { m.close() }
        safeLog("TR model $name unloaded ($why)")
    }

    /** Lets go of the model in [p], if it is one and loaded. Before its files are deleted. */
    internal fun unloadPack(p: SttPacks.Pack) {
        synchronized(loadLock) {
            val name = TranslatePacks.MODELS.entries.firstOrNull { it.value === p }?.key ?: return
            release(name, "deleted")
        }
    }

    override fun unload() {
        synchronized(loadLock) { for (n in models.keys.toList()) release(n, "unload") }
    }

    /** ONNX Runtime threads per model run: enough to be quick, few enough to leave the video alone. */
    private const val THREADS = 2
}
