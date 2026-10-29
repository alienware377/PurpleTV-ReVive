package tv.purple.xp

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioFormat
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The two optional speech-to-text downloads, each fetched, checked and stored on its own:
 *  - the engine: sherpa-onnx's native library (ONNX Runtime is inside it), arm64 only;
 *  - the model: the Kroko English streaming speech model.
 * Both are assets of the separate GitHub pre-release PackPins.TAG on PurpleTV-ReVive, pinned by
 * exact size and SHA-256 in PackPins.
 *
 * Why so strict: the engine is native code that runs inside Twitch's process, where a damaged or
 * swapped file could crash Twitch. So the zip is checked against its pin while it downloads, only
 * the pinned entries are unpacked (never more bytes than pinned for each), every unpacked file is
 * checked against its own pin, a marker naming the zip's hash is written last, and the library is
 * checked once more right before it is loaded. Anything that does not match is deleted, never used.
 *
 * Where: getFilesDir()/ptv_stt/engine and /model, Twitch's private internal storage. External
 * storage is no good for this: other apps can change files there, and Android will not load code
 * from it. A download that stopped half way waits in ptv_stt/dl as a .part file and goes on from
 * where it stopped next time.
 *
 * Callbacks (progress and done) always arrive on the main thread.
 */
object SttPacks {

    private const val ROOT = "ptv_stt"
    private const val MARKER = "pack.ok"
    private const val URL_BASE = "https://github.com/alienware377/PurpleTV-ReVive/releases/download/"
    /** Both supported Twitch builds ship arm64-v8a only, and that is all the engine is built for. */
    private const val ABI = "arm64-v8a"
    /** Kept free on top of a pack's own size, so a download never fills the phone up. */
    private const val SPARE = 32L * 1024 * 1024

    internal class Pack(
        /** For messages: "engine" or "speech model". */
        val what: String,
        val dir: String,
        val asset: String,
        val sha: String,
        val bytes: Long,
        val entries: Map<String, PackPins.FilePin>
    ) {
        val url: String get() = URL_BASE + PackPins.TAG + "/" + asset
        val unpacked: Long = entries.values.sumOf { it.bytes }
        val busy = AtomicBoolean(false)
        @Volatile var progress = 0
        /** The last quick check, so the menu does not touch the disk on every draw. Null means ask again. */
        @Volatile var ready: Boolean? = null
        /** Every file was hashed and matched its pin in this process. */
        @Volatile var hashed = false
        /** The request running now, so deleteAll can cut a read stuck on a stalled connection. */
        @Volatile var call: okhttp3.Call? = null
    }

    internal val ENGINE = Pack("engine", "engine", PackPins.ENGINE_ASSET, PackPins.ENGINE_SHA256,
        PackPins.ENGINE_BYTES, PackPins.ENGINE_ENTRIES)
    internal val MODEL = Pack("speech model", "model", PackPins.MODEL_ASSET, PackPins.MODEL_SHA256,
        PackPins.MODEL_BYTES, PackPins.MODEL_ENTRIES)

    /** Held while files are swapped in or deleted, and while they are checked and loaded. */
    internal val swapLock = Any()
    /** Bumped by deleteAll, so a running download stops at its next read. */
    @Volatile private var cancelGen = 0
    /**
     * Space promised to the downloads running now (each one's need without SPARE), under
     * [spaceLock]. Both packs may download at once, and each must count the other, or together
     * they could fill the phone up although each check passed on its own.
     */
    private val spaceLock = Any()
    private var reserved = 0L

    private val main = Handler(Looper.getMainLooper())

    /**
     * Its own client, made on the first download only. No cache, so a 60 MB file never lands in
     * the emote picture cache, and the long read timeout suits a slow phone connection.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** The engine is arm64 only, and Twitch must run as a 64-bit process to load it. */
    fun supported(): Boolean = runCatching {
        Process.is64Bit() && Build.SUPPORTED_64_BIT_ABIS.any { it == ABI }
    }.getOrDefault(false)

    fun engineReady(ctx: Context): Boolean = ready(ctx, ENGINE)
    fun modelReady(ctx: Context): Boolean = ready(ctx, MODEL)

    /** True while either pack is downloading. */
    fun downloading(): Boolean = ENGINE.busy.get() || MODEL.busy.get()

    /** Credit lines for the settings page. The model's licence (CC-BY-SA) asks for its credit. */
    fun credits(): String = PackPins.ENGINE_CREDIT + "\n" + PackPins.MODEL_CREDIT

    /** One line per pack plus the engine's state, for the settings page. */
    fun status(ctx: Context): String {
        if (!supported()) return "Not available on this phone: speech-to-text needs a phone with a 64-bit ARM processor."
        Stt.brokenReason()?.let {
            return "Could not start on this phone ($it). Restart Twitch to try again. " +
                "Captions still come from the stream when it has its own."
        }
        val lines = ArrayList<String>()
        lines.add(line(ctx, ENGINE, "Engine (sherpa-onnx ${PackPins.SHERPA_VERSION})"))
        lines.add(line(ctx, MODEL, "Speech model (Kroko English)"))
        if (ready(ctx, ENGINE) && ready(ctx, MODEL)) lines.add(when {
            Stt.listening() -> "Listening to the small live picture now"
            Stt.loaded() -> "Loaded, waiting for the small live picture"
            Stt.hookPending(ctx) -> "Downloaded. " + Stt.RESTART_HINT
            else -> "Ready. It all runs on this phone and nothing is sent anywhere"
        })
        return lines.joinToString("\n")
    }

    private fun line(ctx: Context, p: Pack, label: String): String = label + ": " + when {
        p.busy.get() -> "downloading ${p.progress}%"
        ready(ctx, p) -> "downloaded and checked"
        else -> "not downloaded (${mb(p.bytes)} MB download, ${mb(p.unpacked)} MB on the phone)"
    }

    /**
     * One pack on its own, for its download row in the menu: its size and whether it is there,
     * downloading (with how far) or missing. [model] picks the speech model, otherwise the engine.
     * Cheap enough to ask every second while the row is on screen: the disk is only read once
     * after a download or a delete, then the answer is kept.
     */
    fun packLine(ctx: Context, model: Boolean): String {
        if (!supported()) return "Not available on this phone: it needs a 64-bit ARM processor"
        val p = if (model) MODEL else ENGINE
        val pct = p.progress
        return when {
            // The last tenth is the check and unpack, after the bytes are all in.
            p.busy.get() -> if (pct < 90) "Downloading: $pct%" else "Checking and unpacking: $pct%"
            // The engine row also says when speech to text is set up but not running, and why:
            // the reason is otherwise only in a toast, which waits while an ad is on.
            ready(ctx, p) -> "Downloaded and checked (${mb(p.unpacked)} MB on the phone)" +
                (if (model) null else Stt.menuNote(ctx))?.let { ". $it" }.orEmpty()
            else -> "${mb(p.bytes)} MB download, ${mb(p.unpacked)} MB on the phone. Not downloaded yet"
        }
    }

    /** True while this one pack is downloading. */
    fun packBusy(model: Boolean): Boolean = (if (model) MODEL else ENGINE).busy.get()

    private fun mb(b: Long) = (b + 500_000) / 1_000_000

    // ---- where the files live ----

    internal fun root(ctx: Context) = File(ctx.filesDir, ROOT)
    internal fun dir(ctx: Context, p: Pack) = File(root(ctx), p.dir)
    internal fun file(ctx: Context, p: Pack, entry: String) = File(dir(ctx, p), entry)

    private fun ready(ctx: Context, p: Pack): Boolean {
        p.ready?.let { return it }
        val ok = runCatching { quickCheck(ctx, p) }.getOrDefault(false)
        p.ready = ok
        // A finished zip can be left behind when Twitch stopped right after the unpack. The pack
        // is in place, so it is not needed any more. Never while this pack is downloading.
        if (ok && !p.busy.get()) runCatching { File(File(root(ctx), "dl"), p.asset + ".part").delete() }
        return ok
    }

    /** The marker for these exact pins, and every pinned file there at its pinned size. No hashing. */
    private fun quickCheck(ctx: Context, p: Pack): Boolean {
        val d = dir(ctx, p)
        val m = File(d, MARKER)
        if (!m.isFile || m.length() > 256 || m.readText().trim() != p.sha) return false
        return p.entries.all { (name, pin) -> File(d, name).let { it.isFile && it.length() == pin.bytes } }
    }

    /**
     * Hash every file of [p] once per process. Null when all match; otherwise the pack is deleted
     * (it can be downloaded again) and the reason comes back. Called with [swapLock] held.
     */
    internal fun ensureHashed(ctx: Context, p: Pack): String? {
        if (!quickCheck(ctx, p)) { p.ready = false; return "the ${p.what} is not downloaded" }
        if (p.hashed) return null
        for ((name, pin) in p.entries) {
            if (sha256(file(ctx, p, name)) != pin.sha256) {
                drop(ctx, p)
                return "the ${p.what} file $name failed its check and was deleted"
            }
        }
        p.hashed = true
        return null
    }

    /**
     * The engine's libraries in the order they must be loaded: a C++ runtime first if the pack
     * ever carries one, then any other helper, and the JNI library last. Today it is only the JNI
     * library, which has ONNX Runtime linked in and needs only Android's own system libraries.
     */
    internal fun engineLibs(): List<String> {
        val all = ENGINE.entries.keys.filter { it.endsWith(".so") && (it == PackPins.ENGINE_LIB || "/$ABI/" in it || '/' !in it) }
        val first = all.filter { it.substringAfterLast('/') == "libc++_shared.so" }
        val last = all.filter { it == PackPins.ENGINE_LIB }
        return first + (all - first.toSet() - last.toSet()).sorted() + last
    }

    /**
     * Loads one library for the module's own class loader. It must be called from this class:
     * System.load ties the library to the class loader of its caller, and the JNI functions of
     * com.k2fsa.sherpa.onnx are looked up through that loader (the module's, not Twitch's).
     */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    internal fun loadLib(f: File) {
        System.load(f.absolutePath)
    }

    internal fun drop(ctx: Context, p: Pack) {
        runCatching { dir(ctx, p).deleteRecursively() }
        p.ready = false
        p.hashed = false
    }

    // ---- downloads ----

    fun downloadEngine(ctx: Context, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) =
        download(ctx, ENGINE, progress, done)

    fun downloadModel(ctx: Context, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) =
        download(ctx, MODEL, progress, done)

    /**
     * Remove both packs. Downloads in flight stop, and the recognizer is released on its own
     * thread before the files go. The speech-to-text setting itself is left as it is.
     */
    fun deleteAll(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        cancelGen++
        // A read stuck on a stalled connection only sees the stop after its 60 s timeout; cutting
        // the call ends it at once. OkHttp's cancel is safe from any thread and does no network work.
        for (p in listOf(ENGINE, MODEL)) runCatching { p.call?.cancel() }
        Stt.afterUnload("packs deleted") {
            synchronized(swapLock) { runCatching { root(app).deleteRecursively() } }
            for (p in listOf(ENGINE, MODEL)) { p.ready = false; p.hashed = false }
            log("STT packs deleted")
        }
    }

    private class Fail(msg: String) : Exception(msg)

    private fun download(ctx: Context, p: Pack, progress: (Int) -> Unit, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext ?: ctx
        fun finish(ok: Boolean, msg: String) { main.post { runCatching { done(ok, msg) } } }
        if (!supported()) { finish(false, "Speech-to-text needs a phone with a 64-bit ARM processor"); return }
        if (!p.busy.compareAndSet(false, true)) { finish(false, "The ${p.what} is already downloading"); return }
        p.progress = 0
        val gen = cancelGen
        log("STT pack ${p.dir}: downloading ${p.asset}")
        Thread({
            var shown = -1
            val r = runCatching {
                fetch(app, p, gen) { pct ->
                    p.progress = pct
                    if (pct != shown) { shown = pct; main.post { runCatching { progress(pct) } } }
                }
            }
            p.busy.set(false)
            p.ready = null
            val err = r.exceptionOrNull()
            // Delete cuts the connection, so the error is then about that, not the real cause.
            val stopped = gen != cancelGen
            // Messages only, never the address or a stack: nothing private, and the log stays short.
            if (err == null) log("STT pack ${p.dir}: sha ok, unpacked and checked")
            else log("STT pack ${p.dir}: failed: ${if (stopped) "stopped" else if (err is Fail) err.message else err.javaClass.simpleName}")
            // The hooks never go in when a download ends (a stream is usually playing then), so
            // when they are still missing the message says how to start it.
            val okText = { "The ${p.what} is downloaded and checked" +
                (if (runCatching { Stt.hookPending(app) }.getOrDefault(false)) ". " + Stt.RESTART_HINT else "") }
            finish(r.isSuccess, r.fold({ okText() },
                { if (stopped) "The ${p.what} download was stopped" else failText(p, it) }))
            if (r.isSuccess) Stt.refresh()
        }, "ptv-stt-dl").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun failText(p: Pack, t: Throwable): String = when (t) {
        is Fail -> t.message ?: "The ${p.what} download failed"
        is IOException -> "The ${p.what} download failed. Check the connection and try again"
        else -> "The ${p.what} download failed (${t.javaClass.simpleName})"
    }

    /**
     * A write to the phone's storage. Its failure means the phone is full or its storage failed,
     * not the connection, so it says so rather than "check the connection".
     */
    private inline fun <T> disk(p: Pack, block: () -> T): T =
        try { block() } catch (e: IOException) { throw Fail("Could not save the ${p.what}. Check that the phone has free space") }

    /** Download thread: zip to .part (resuming), checked, then unpacked and swapped in. */
    private fun fetch(ctx: Context, p: Pack, gen: Int, pct: (Int) -> Unit) {
        val root = root(ctx)
        val dl = File(root, "dl")
        dl.mkdirs()
        // Only the current pins' two .part names are ever resumed. Anything else here (a part of
        // an older pin, a stray file) would only take space for good.
        val keep = setOf(ENGINE.asset + ".part", MODEL.asset + ".part")
        runCatching { dl.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() } }
        val part = File(dl, p.asset + ".part")
        var have = if (part.isFile) part.length() else 0L
        if (have > p.bytes) { part.delete(); have = 0L }
        val need = (p.bytes - have) + p.unpacked + SPARE
        val mine = need - SPARE
        synchronized(spaceLock) {
            val total = need + reserved
            val free = root.usableSpace
            if (free in 1 until total) throw Fail("Not enough free space: about ${mb(total)} MB is needed")
            reserved += mine
        }
        try {
            fetchChecked(ctx, p, gen, part, have, pct)
        } finally {
            synchronized(spaceLock) { reserved -= mine }
            p.call = null
        }
    }

    private fun fetchChecked(ctx: Context, p: Pack, gen: Int, part: File, from: Long, pct: (Int) -> Unit) {
        var have = from
        val md = MessageDigest.getInstance("SHA-256")
        // Resuming: what is already there goes into the hash first.
        if (have > 0) part.inputStream().use { digestAll(it, md) }
        var restarted = false
        // A complete .part (the zip came in, but the unpack did not finish: Twitch was stopped, or
        // the phone ran out of space) needs no network at all. The checks below decide whether it
        // is used, and a part that does not match is deleted there.
        while (have < p.bytes) {
            val req = Request.Builder().url(p.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            val call = http.newCall(req)
            p.call = call
            // Delete may have come just before the call was stored, so it could not cut it.
            if (gen != cancelGen) throw Fail("The ${p.what} download was stopped")
            val again = call.execute().use { r ->
                if (have > 0 && r.code == 416 && !restarted) {
                    // The server does not like the range: start over once.
                    restarted = true; part.delete(); have = 0L; md.reset()
                    return@use true
                }
                val resume = have > 0 && r.code == 206
                if (r.code != 200 && !resume) throw Fail("The ${p.what} download failed (HTTP ${r.code})")
                if (!resume && have > 0) { have = 0L; md.reset() }   // the whole file came again
                val body = r.body ?: throw Fail("The ${p.what} download came back empty")
                val len = body.contentLength()
                if (len >= 0 && have + len != p.bytes) throw Fail("The ${p.what} download is not the expected size")
                disk(p) { FileOutputStream(part, resume) }.use { out ->
                    body.byteStream().use { inp ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (gen != cancelGen) { call.cancel(); throw Fail("The ${p.what} download was stopped") }
                            // The network read stays outside disk(): its errors are the connection's.
                            val n = inp.read(buf)
                            if (n < 0) break
                            if (have + n > p.bytes) { part.delete(); throw Fail("The ${p.what} download is larger than expected and was deleted") }
                            disk(p) { out.write(buf, 0, n) }
                            md.update(buf, 0, n)
                            have += n
                            pct((have * 90 / p.bytes).toInt())
                        }
                    }
                    disk(p) { out.fd.sync() }
                }
                false
            }
            // One pass, apart from the 416 restart: a body that ended early is reported below.
            if (!again) break
        }
        if (have != p.bytes) throw Fail("The ${p.what} download stopped early. Try again to continue it")
        if (hex(md.digest()) != p.sha) {
            part.delete()
            throw Fail("The ${p.what} download did not match its checksum and was deleted")
        }
        unpack(ctx, p, part, gen, pct)
        part.delete()
    }

    /** Only allow-listed entries, each capped at its pinned size and checked, into a fresh folder. */
    private fun unpack(ctx: Context, p: Pack, zip: File, gen: Int, pct: (Int) -> Unit) {
        val tmp = File(root(ctx), p.dir + ".tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val base = tmp.canonicalPath + File.separator
        try {
            val seen = HashSet<String>()
            var done = 0L
            ZipFile(zip).use { z ->
                for (e in Collections.list(z.entries())) {
                    if (e.isDirectory) continue
                    val name = e.name
                    // Not on the allow-list: never written anywhere.
                    val pin = p.entries[name] ?: continue
                    if (!seen.add(name)) throw Fail("The ${p.what} download holds a file twice")
                    val out = File(tmp, name)
                    if (".." in name || !out.canonicalPath.startsWith(base)) throw Fail("The ${p.what} download holds a bad file name")
                    out.parentFile?.mkdirs()
                    val md = MessageDigest.getInstance("SHA-256")
                    var n = 0L
                    z.getInputStream(e).use { inp ->
                        FileOutputStream(out).use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (gen != cancelGen) throw Fail("The ${p.what} download was stopped")
                                val k = inp.read(buf)
                                if (k < 0) break
                                n += k
                                if (n > pin.bytes) throw Fail("A file in the ${p.what} is larger than expected")
                                o.write(buf, 0, k)
                                md.update(buf, 0, k)
                            }
                            o.fd.sync()
                        }
                    }
                    if (n != pin.bytes || hex(md.digest()) != pin.sha256) throw Fail("A file in the ${p.what} failed its check")
                    out.setReadOnly()
                    done += n
                    pct(90 + (done * 10 / maxOf(1L, p.unpacked)).toInt().coerceAtMost(10))
                }
            }
            if (seen.size != p.entries.size) throw Fail("The ${p.what} download is missing files")
            // Written last: a pack without it is never used.
            FileOutputStream(File(tmp, MARKER)).use { it.write((p.sha + "\n").toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            synchronized(swapLock) {
                val dst = dir(ctx, p)
                dst.deleteRecursively()
                if (!tmp.renameTo(dst)) throw Fail("Could not store the ${p.what}")
                p.ready = null
                // Every file was hashed just now.
                p.hashed = true
            }
        } catch (e: IOException) {
            // The zip was already checked, so this is the phone's storage (full, or failing), not
            // the connection. The checked zip stays, so the next try only unpacks again.
            throw Fail("Could not unpack the ${p.what}. Check that the phone has free space and try again")
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    // ---- checks ----

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { digestAll(it, md) }
        return hex(md.digest())
    }

    private fun digestAll(inp: java.io.InputStream, md: MessageDigest) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun hex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xff
            c[i * 2] = HEX[v ushr 4]
            c[i * 2 + 1] = HEX[v and 15]
        }
        return String(c)
    }

    /** Test aid (DevBridge): hash both packs now. A pack that fails is deleted. */
    fun verify(ctx: Context): String = listOf(ENGINE, MODEL).joinToString(", ") { p ->
        p.hashed = false
        val r = synchronized(swapLock) { runCatching { ensureHashed(ctx, p) }.getOrElse { "error ${it.javaClass.simpleName}" } }
        "${p.dir}: " + (r ?: "ok")
    }

    /** Test aid (DevBridge): flip one byte in the middle of the engine library on disk. */
    fun devCorrupt(ctx: Context): String {
        val f = file(ctx, ENGINE, PackPins.ENGINE_LIB)
        if (!f.isFile) return "no engine library to change"
        f.setWritable(true, true)
        RandomAccessFile(f, "rw").use { r ->
            val at = r.length() / 2
            r.seek(at); val b = r.read()
            r.seek(at); r.write(b xor 0x5a)
        }
        f.setReadOnly()
        ENGINE.hashed = false
        return "flipped one byte of ${f.name}"
    }
}

/**
 * Speech to text for the small muted live picture Twitch shows beside an ad (Picture-by-Picture),
 * on the phone, with nothing sent anywhere. Text goes to CaptionHub.onSttText, which shows it and
 * hands it on to read-aloud.
 *
 * How the sound is reached, without touching any player:
 *  - IVS (Twitch's player) hands every piece of decoded 16-bit sound to AudioTrackRenderer.render
 *    before it goes to Android. The volume is applied later, on the AudioTrack, so the PbyP
 *    player's sound arrives at full level even though Twitch keeps that player muted. Nothing of
 *    the player is changed: its mute, its volume and the phone's volume stay as Twitch set them,
 *    and no extra copy of the stream is fetched.
 *  - Only the PbyP player's sound is copied. Every renderer carries its player's audio session id,
 *    and ptv-stt asks the PbyP player for its id (CorePlayerImpl.getAudioSessionId, a plain field
 *    read) every 300 ms while the break runs. If the id stays unknown (IVS gives up asking its
 *    audio thread after a second), the renderer is found through the audio sink IVS built for that
 *    player instead.
 *  - The hooks go in when Twitch starts with the option on, or when it is switched on in the menu
 *    outside any ad, and are never taken out. Adding or removing a hook pauses every thread for a
 *    moment, which could make the stream's or the ad's sound skip, so that never happens at a
 *    break's edges or when a download ends. With nothing to copy, the hook returns after one check.
 *  - While the stream sends its own captions, the copying rests: those captions are used instead.
 *
 * Threads:
 *  - IVS's audio thread only copies bytes into a fixed ring of slots made in advance: no waiting,
 *    no locks, no new objects. A big piece is spread over several slots; when the ring is full the
 *    piece is dropped and the gap is marked.
 *  - Our own "ptv-stt" thread, at background priority, does the rest: mixing down to mono,
 *    resampling to 16 kHz, the recognizer (one thread, so it never competes with the two video
 *    decoders of the break), and every load and release of native memory. sherpa's objects free
 *    their memory in finalize(), so they are always released here, stream first and recognizer
 *    second, and never left to the garbage collector while in use.
 *
 * When: the model (well over 70 MB of memory once loaded) is loaded when a PbyP break starts and
 * unloaded 60 s after it ends, at once when the phone is low on memory or too hot, and right away
 * when the option is switched off.
 */
object Stt {

    /** Speech to text when the stream has no captions of its own. Needs both downloads. */
    const val KEY = "stt_captions"
    /** Test aid: log speed, drops and the recognised text. */
    const val KEY_LOG = "dev_stt_log"
    /** Test aid: recognizer threads, 1 to 4. One unless the measured speed asks for more. */
    const val KEY_THREADS = "dev_stt_threads"

    private const val UNLOAD_MS = 60_000L
    private const val TICK_MS = 100L
    private const val POLL_MS = 300L
    /** How long the session id may stay unknown before the audio sink is asked instead. */
    private const val FALLBACK_MS = 3_000L
    /** Partial text at most 4 times a second. */
    private const val PARTIAL_MS = 250L
    private const val STATS_MS = 30_000L
    /** 0.4 s of silence after an utterance, so the model gives out its last words. */
    private const val TAIL_SAMPLES = 6_400
    private const val MAX_TAP_ERRORS = 20
    /** 128 slots of 8 KB: about 2.7 s of 48 kHz stereo in typical 4 KB pieces. */
    private const val SLOTS = 128
    private const val SLOT_BYTES = 8_192

    // ---- shared with IVS's audio threads ----

    /** Audio session of the PbyP player to copy. 0 means copy nothing. */
    @Volatile private var target = 0
    @Volatile private var ring: PcmRing? = null
    @Volatile private var tapOff = false
    @Volatile private var tapErrors = 0
    @Volatile private var fSession: Field? = null
    @Volatile private var fFormat: Field? = null
    @Volatile private var badFormat = false
    // The format of the renderer being copied. Only touched while holding ring.writing.
    private var lastFormat: MediaFormat? = null
    private var fmtRate = 0
    private var fmtCh = 0

    // ---- set at install ----

    @Volatile private var installed = false
    @Volatile private var cl: ClassLoader? = null
    @Volatile private var hooked = false
    @Volatile private var hookError: String? = null
    @Volatile private var hookDeferred = false
    @Volatile private var mSession: Method? = null
    @Volatile private var fPlatform: Field? = null
    @Volatile private var fRenderer: Field? = null
    @Volatile private var fComponents: Field? = null
    @Volatile private var filterCls: Class<*>? = null
    /** IVS Platform (one per player) to the AudioRendererFilter its audio sink built, for the fallback. */
    private val filters = WeakHashMap<Any, WeakReference<Any>>()

    // ---- ptv-stt thread only (fields read elsewhere are @Volatile) ----

    @Volatile private var bgStarted = false
    private val bg: Handler by lazy {
        bgStarted = true
        Handler(HandlerThread("ptv-stt", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    }
    private val main = Handler(Looper.getMainLooper())

    private var rec: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    /** Native objects whose release failed: kept forever, so the GC never frees them a second time. */
    private val leaked = ArrayList<Any>()
    @Volatile private var nativeOk = false
    @Volatile private var broken: String? = null
    @Volatile private var loadedNow = false
    @Volatile private var inBreak = false
    @Volatile private var memoryHold = false
    @Volatile private var thermalHold = false
    @Volatile private var devBreak = false
    @Volatile private var devFeeding = false
    /** Test aid (DEV_STT feed_main): copy the main player's sound instead of the PbyP player's. */
    @Volatile private var devMain = false
    private var ticking = false
    private var watchers = false
    private var saidMissing = false
    private var curPlayer: WeakReference<Any>? = null
    private var playerSince = 0L
    private var lastPoll = 0L
    private val res = Resampler16k()
    private var curRate = 0
    private var curCh = 0
    private var mono = FloatArray(4096)
    /** 16 kHz samples given to the stream since its last reset. */
    private var fed = 0L
    private var lastPartial = ""
    private var lastPartialAt = 0L
    private var decodeNs = 0L
    private var audioSamples = 0L
    private var statsAt = 0L
    private var statsDropped = 0
    private var statsErrors = 0

    // ---- the setting ----

    @Volatile private var onRev = Int.MIN_VALUE
    @Volatile private var onCached = false

    /**
     * On, and somebody wants the text: the caption box or read-aloud. Re-read only after a
     * settings write, because the listener and the tick ask often.
     */
    private fun on(): Boolean {
        if (!Settings.isReady()) return false
        val r = Settings.rev
        if (r != onRev) {
            onCached = Settings.get(KEY, false) &&
                (Settings.get(CaptionHub.KEY, true) || Settings.getInt(ReadAloud.KEY, 0) != 0)
            onRev = r
        }
        return onCached
    }

    internal fun brokenReason(): String? = broken
    internal fun loaded(): Boolean = loadedNow
    internal fun listening(): Boolean = loadedNow && target > 0

    /** What the menu and the download message say while the hooks wait (see [maybeHook]). */
    const val RESTART_HINT = "Restart Twitch, or switch speech-to-text off and on, to start it"

    /**
     * On, with both downloads in place, but the hooks are not in yet: they only go in at start-up
     * or from the switch, so this waits for one of those. Cheap: settings and pack state are cached.
     */
    internal fun hookPending(ctx: Context): Boolean =
        installed && !hooked && hookError == null && on() &&
            SttPacks.supported() && SttPacks.engineReady(ctx) && SttPacks.modelReady(ctx)

    /** For the menu: why speech to text is set up but not running. Null when nothing is wrong. */
    internal fun menuNote(ctx: Context): String? {
        broken?.let { return "Speech-to-text could not start ($it). Restart Twitch to try again" }
        if (hookPending(ctx)) return RESTART_HINT
        return null
    }

    // ---- install and refresh ----

    /**
     * From module start. Hooks IVS only when the option is on and both packs are there; otherwise
     * nothing runs and no thread is started until [refresh] is called.
     */
    fun install(lp: LoadPackageParam) {
        if (installed) return
        installed = true
        cl = lp.classLoader
        runCatching { PbypWatch.addListener { kick() } }.onFailure { log("STT PbyP listener failed: $it") }
        if (!on()) return
        log("STT speech to text is on, checking the downloads")
        bg.post { safely("install") { maybeHook(); prepare(); evaluate() } }
    }

    /**
     * After the option, the caption or read-aloud setting, or a download changed (from the menu,
     * DevBridge or sync), and the model is loaded or released to match. The hooks go in only when
     * [hookNow] says the user (or a test command) just changed a switch; sync and a finished
     * download never pass it, see [maybeHook].
     */
    fun refresh(hookNow: Boolean = false) {
        if (!installed) return
        if (!on() && !bgStarted) return
        bg.post { safely("refresh") { if (hookNow) maybeHook(); prepare(); evaluate() } }
    }

    /** PbyP changed: look again on ptv-stt. Coalesced, because it may fire on every layout pass. */
    private val evalPosted = AtomicBoolean(false)

    private fun kick() {
        if (!installed) return
        if (!on() && !loadedNow && !devBreak && !devFeeding && !devMain) return
        if (evalPosted.compareAndSet(false, true)) bg.post {
            evalPosted.set(false)
            safely("evaluate") { evaluate() }
        }
    }

    /** Release the recognizer on its own thread, then run [then] there (deleteAll). */
    internal fun afterUnload(why: String, then: () -> Unit) {
        bg.post { safely("unload") { unload(why); then(); evaluate() } }
    }

    /**
     * Puts the IVS hooks in. Adding a hook pauses every thread for a moment, and IVS's audio thread
     * may be inside a blocking write then, so the sound can skip. So it happens only at moments the
     * user picked: start-up, a switch changed in the menu, or a test command. Never at a break's
     * end or when a download finishes (a stream is usually playing then), and never while any ad
     * is on, PbyP or not. Until then the menu says to restart Twitch or switch speech-to-text off
     * and on ([hookPending]).
     */
    private fun maybeHook() {
        if (hooked || hookError != null || !on()) return
        val ctx = Settings.ctx() ?: return
        if (!SttPacks.supported() || !SttPacks.engineReady(ctx) || !SttPacks.modelReady(ctx)) return
        if (AdTime.now()) {
            if (!hookDeferred) log("STT hooks wait: an ad is on (restart Twitch or switch it off and on later)")
            hookDeferred = true
            return
        }
        hookDeferred = false
        hookIvs()
    }

    /**
     * The once-per-process checks: hash and System.load the engine, hash the model. Done right
     * after the hooks go in or a download finishes, outside any ad, so a break start only has to
     * make the recognizer. Loading a library holds Android's loader lock, and hashing about 95 MB
     * keeps the disk and a core busy: not what the ad and PbyP decoders need as a break starts. If
     * a break comes first, load() still does both then, as before.
     */
    private fun prepare() {
        if (!hooked || broken != null || (nativeOk && SttPacks.MODEL.hashed) || !on()) return
        if (inBreak || AdTime.now()) return
        val ctx = Settings.ctx() ?: return
        if (!SttPacks.supported() || !SttPacks.engineReady(ctx) || !SttPacks.modelReady(ctx)) return
        val t0 = SystemClock.uptimeMillis()
        if (!loadNative(ctx)) return
        // A break that started meanwhile: the model's check waits for load().
        if (AdTime.now()) return
        val why = synchronized(SttPacks.swapLock) { SttPacks.ensureHashed(ctx, SttPacks.MODEL) }
        log("STT checked and loaded the engine outside a break in ${SystemClock.uptimeMillis() - t0} ms" +
            (why?.let { ", but $it" } ?: ""))
    }

    private fun hookIvs() {
        val c = cl ?: return
        try {
            val atr = c.loadClass("com.amazonaws.ivs.player.AudioTrackRenderer")
            val core = c.loadClass("com.amazonaws.ivs.player.CorePlayerImpl")
            val plat = c.loadClass("com.amazonaws.ivs.player.Platform")
            fSession = atr.getDeclaredField("audioSessionId").apply { isAccessible = true }
            fFormat = atr.getDeclaredField("format").apply { isAccessible = true }
            mSession = core.getMethod("getAudioSessionId")
            // Not the static preloadPlatform: the player's own.
            fPlatform = core.declaredFields.firstOrNull { it.type == plat && !Modifier.isStatic(it.modifiers) }
                ?.apply { isAccessible = true }
            ensureRing()
            val render = atr.getDeclaredMethod("render", ByteBuffer::class.java,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val configure = atr.getDeclaredMethod("configure", MediaFormat::class.java)
            val release = atr.getDeclaredMethod("release")
            XposedBridge.hookMethod(render, renderHook)
            XposedBridge.hookMethod(configure, markGapHook(false))
            XposedBridge.hookMethod(release, markGapHook(true))
        } catch (t: Throwable) {
            hookError = t.javaClass.simpleName
            log("STT hook AudioTrackRenderer failed, speech to text is off: $t")
            return
        }
        // Fallback for an unknown session id. Large and rarely called, so ART does not inline it.
        val fb = runCatching {
            val trk = c.loadClass("com.amazonaws.ivs.player.MediaSink\$Track")
            val fc = c.loadClass("com.amazonaws.ivs.player.AudioRendererFilter")
            fRenderer = fc.getDeclaredField("renderer").apply { isAccessible = true }
            fComponents = trk.getField("components")
            filterCls = fc
            val m = trk.declaredMethods.first { it.name == "configureAudio" && it.parameterTypes.size == 4 }
            XposedBridge.hookMethod(m, configureAudioHook)
            "ok"
        }.getOrElse { "off ($it)" }
        hooked = true
        log("STT hooks installed: render, configure, release; sink fallback $fb")
    }

    @Synchronized private fun ensureRing(): PcmRing = ring ?: PcmRing(SLOTS, SLOT_BYTES).also { ring = it }

    // ---- hooks (IVS threads) ----

    private val renderHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val want = target
            if (want <= 0 || tapOff) return
            try {
                onRender(p, want)
            } catch (_: Throwable) {
                if (++tapErrors > MAX_TAP_ERRORS) tapOff = true
            }
        }
    }

    /** Copy this piece if it is the PbyP player's. Must not wait, lock, allocate or throw out. */
    private fun onRender(p: XC_MethodHook.MethodHookParam, want: Int) {
        val r = p.thisObject ?: return
        val fs = fSession ?: return
        if (fs.getInt(r) != want) return
        val rg = ring ?: return
        val buf = p.args[0] as? ByteBuffer ?: return
        val size = p.args[1] as? Int ?: return
        // Two renderers of one session can overlap for a moment when IVS rebuilds its decoder:
        // the second one skips this piece rather than wait.
        if (!rg.writing.compareAndSet(false, true)) { rg.dropped++; rg.gapNext = true; return }
        try {
            val fmt = fFormat?.get(r) as? MediaFormat ?: return
            if (fmt !== lastFormat) { readFormat(fmt); rg.gapNext = true }
            if (fmtRate <= 0 || fmtCh <= 0) return
            val pos = buf.position()
            val n = minOf(size, buf.limit() - pos)
            if (n <= 0) return
            // Relative reads move the position Twitch's write starts from, so it is always put back.
            // Through Buffer: ByteBuffer.position(int) does not exist on older Android.
            val b: Buffer = buf
            try { rg.put(buf, n, fmtRate, fmtCh) } finally { b.position(pos) }
        } finally {
            rg.writing.set(false)
        }
    }

    private fun readFormat(fmt: MediaFormat) {
        lastFormat = fmt
        fmtRate = 0; fmtCh = 0
        try {
            // IVS builds its track for 16-bit sound; anything else is not ours to read.
            if (fmt.containsKey("pcm-encoding") && fmt.getInteger("pcm-encoding") != AudioFormat.ENCODING_PCM_16BIT) {
                badFormat = true
                return
            }
            fmtRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            fmtCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } catch (_: Throwable) {
            fmtRate = 0; fmtCh = 0; badFormat = true
        }
    }

    /**
     * configure (after) and release (before) on the copied renderer only mark a gap. configure
     * calls release itself, and a playback rate change calls configure, so neither may stop the
     * copying: the session stays the same, and the format is read again on the next piece.
     */
    private fun markGapHook(before: Boolean) = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) { if (before) mark(p) }
        override fun afterHookedMethod(p: MethodHookParam) { if (!before) mark(p) }
        private fun mark(p: MethodHookParam) {
            try {
                val want = target
                if (want > 0 && fSession?.getInt(p.thisObject) == want) ring?.gapNext = true
            } catch (_: Throwable) {}
        }
    }

    /** MediaSink$Track.configureAudio: remember which filter belongs to which player's Platform. */
    private val configureAudioHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try {
                val platform = p.args.getOrNull(1) ?: return
                val fc = filterCls ?: return
                val comps = fComponents?.get(p.thisObject) as? List<*> ?: return
                val f = comps.firstOrNull { it != null && fc.isInstance(it) } ?: return
                synchronized(filters) { filters[platform] = WeakReference(f) }
            } catch (_: Throwable) {}
        }
    }

    // ---- the break (ptv-stt) ----

    private val unloadTask = Runnable { safely("unload") { if (!inBreak) unload("60 s after the break") } }

    private val tickTask = Runnable {
        ticking = false
        safely("tick") { tick() }
    }

    private fun ensureTick() {
        if (ticking) return
        ticking = true
        bg.postDelayed(tickTask, TICK_MS)
    }

    /** Load, unload and the tap target, from the PbyP state and the settings. */
    private fun evaluate() {
        val want = on()
        val breakNow = devBreak || devFeeding || devMain || PbypWatch.active || PbypWatch.visible
        if (breakNow != inBreak) {
            inBreak = breakNow
            if (breakNow) {
                bg.removeCallbacks(unloadTask)
                saidMissing = false
                if (want) log("STT break started")
            } else {
                memoryHold = false
                if (rec != null) {
                    log("STT break over, unloading in ${UNLOAD_MS / 1000} s")
                    bg.postDelayed(unloadTask, UNLOAD_MS)
                }
                // No hooks here even when they waited: the main stream is starting up again
                // now. They wait for a restart or the switch (see maybeHook).
            }
        }
        if (!want) {
            if (rec != null) unload("switched off")
            setTarget(0, "switched off")
            return
        }
        if (!inBreak) {
            setTarget(0, "break over")
            // One more pass for what is still waiting, so the last line comes out as final text.
            if (rec != null && (fed > 0 || res.outN > 0 || ring?.empty() == false)) ensureTick()
            return
        }
        if (memoryHold || thermalHold) {
            if (rec != null) unload(if (memoryHold) "low memory" else "phone too hot")
            setTarget(0, "paused")
            return
        }
        if (broken != null) return
        // Hooks not in yet (they wait for a restart or the switch): nothing to hear, so no model either.
        if (!hooked && !devFeeding && !devBreak) return
        if (rec == null && !load()) return
        ensureTick()
    }

    private fun tick() {
        evaluate()
        if (rec == null) return
        pollTarget()
        process()
        val rg = ring
        if (target <= 0 && (rg == null || rg.empty()) && (fed > 0 || res.outN > 0)) endUtterance()
        stats()
        if (inBreak || (rg != null && !rg.empty()) || fed > 0) ensureTick()
    }

    /**
     * Which session to copy: the PbyP player's, only while its picture is on screen. The test
     * command feed_main points it at the main player instead, so the recognizer can be tried on
     * ordinary stream sound without waiting for an ad. Copying is read-only either way.
     */
    private fun pollTarget() {
        val useMain = devMain
        // The stream's own captions win (CaptionHub drops our text then anyway), so nothing is
        // copied or decoded for them; the line in progress is finished by the tick.
        val cc = !useMain && CaptionHub.ccRecent()
        if (!inBreak || devFeeding || !hooked || tapOff || cc || (!useMain && !PbypWatch.visible)) {
            setTarget(0, when {
                devFeeding -> "test file"
                cc -> "the stream has its own captions"
                !useMain && !PbypWatch.visible -> "not shown"
                else -> "off"
            })
            return
        }
        val p = (if (useMain) mainPlayer() else PbypWatch.player()) ?: run { setTarget(0, "no player"); return }
        val now = SystemClock.uptimeMillis()
        if (curPlayer?.get() !== p) {
            curPlayer = WeakReference(p)
            playerSince = now
            lastPoll = 0L
            log("STT ${if (useMain) "main (test)" else "PbyP"} player ${tag(p)}")
        }
        if (now - lastPoll < POLL_MS) return
        lastPoll = now
        var s = sessionOf(p)
        if (s <= 0 && now - playerSince > FALLBACK_MS) s = fallbackSession(p)
        setTarget(if (s > 0) s else 0, "no session yet")
    }

    private fun setTarget(s: Int, why: String) {
        if (s == target) return
        target = s
        // Whatever comes next does not follow on from what came before.
        ring?.gapNext = true
        log(if (s > 0) "STT tap session $s" else "STT tap off ($why)")
    }

    /** Public and plain Java (Platform's stored id or its renderer's), so safe from any thread. */
    private fun sessionOf(p: Any): Int = runCatching {
        val m = mSession?.takeIf { it.declaringClass.isInstance(p) } ?: p.javaClass.getMethod("getAudioSessionId")
        m.invoke(p) as? Int ?: 0
    }.getOrDefault(0)

    /** The renderer of the audio sink IVS built for this player's Platform, read directly. */
    private fun fallbackSession(p: Any): Int = runCatching {
        val platform = fPlatform?.get(p) ?: return 0
        val f = synchronized(filters) { filters[platform]?.get() } ?: return 0
        val r = fRenderer?.get(f) ?: return 0
        fSession?.getInt(r) ?: 0
    }.getOrDefault(0)

    // ---- loading (ptv-stt) ----

    private fun load(): Boolean {
        val ctx = Settings.ctx() ?: return false
        if (!SttPacks.supported()) return false
        if (!SttPacks.engineReady(ctx) || !SttPacks.modelReady(ctx)) {
            if (!saidMissing) { saidMissing = true; log("STT not loaded: the downloads are not complete") }
            return false
        }
        if (!loadNative(ctx)) return false
        val t0 = SystemClock.uptimeMillis()
        var r: OnlineRecognizer? = null
        try {
            val why = synchronized(SttPacks.swapLock) {
                SttPacks.ensureHashed(ctx, SttPacks.MODEL)?.let { return@synchronized it }
                val d = SttPacks.dir(ctx, SttPacks.MODEL)
                fun path(role: String) = File(d, PackPins.MODEL_FILES[role] ?: error("no $role in the model pins")).absolutePath
                val threads = Settings.getInt(KEY_THREADS, 1).coerceIn(1, 4)
                val cfg = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = path("encoder"), decoder = path("decoder"), joiner = path("joiner")),
                        tokens = path("tokens"),
                        numThreads = threads,
                        provider = "cpu",
                        modelType = PackPins.MODEL_TYPE
                    ),
                    // Final text after 0.8 s of quiet following speech, 2 s of quiet at all, or
                    // 8 s of talking without a pause, so lines never grow forever over game noise.
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.0f, 0.0f),
                        rule2 = EndpointRule(true, 0.8f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 8.0f)
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search"
                )
                r = OnlineRecognizer(null, cfg)
                null
            }
            if (why != null) { log("STT not loaded: $why"); return false }
            val made = r ?: return false
            stream = made.createStream()
            rec = made
        } catch (t: Throwable) {
            r?.let { release(it) }
            r = null
            fail("the speech model could not be loaded", t)
            return false
        }
        loadedNow = true
        res.reset(16000)
        curRate = 0; curCh = 0; fed = 0L; lastPartial = ""
        ring?.let { it.clear(); it.gapNext = true }
        log("STT model loaded in ${SystemClock.uptimeMillis() - t0} ms, threads ${Settings.getInt(KEY_THREADS, 1).coerceIn(1, 4)}")
        registerWatchers(ctx)
        return true
    }

    /**
     * Once per process: re-hash the engine's libraries, System.load them in order, and make sure
     * the library is the version the vendored Kotlin classes were taken from. A failure here
     * leaves speech to text off until Twitch restarts, with a clear status, never a crash.
     */
    private fun loadNative(ctx: Context): Boolean {
        if (nativeOk) return true
        if (broken != null) return false
        val bad: String? = synchronized(SttPacks.swapLock) {
            val libs = SttPacks.engineLibs()
            if (libs.isEmpty()) return@synchronized "the engine pins name no library"
            for (name in libs) {
                val pin = SttPacks.ENGINE.entries[name] ?: return@synchronized "no pin for $name"
                val f = SttPacks.file(ctx, SttPacks.ENGINE, name)
                val ok = runCatching { f.length() == pin.bytes && SttPacks.sha256(f) == pin.sha256 }.getOrDefault(false)
                if (!ok) {
                    SttPacks.drop(ctx, SttPacks.ENGINE)
                    log("STT engine file $name failed its check and was deleted")
                    // Not broken: it can be downloaded again.
                    return false
                }
            }
            try {
                for (name in libs) SttPacks.loadLib(SttPacks.file(ctx, SttPacks.ENGINE, name))
                null
            } catch (t: Throwable) {
                log("STT System.load failed: $t")
                "the engine could not be loaded (${t.javaClass.simpleName})"
            }
        }
        if (bad != null) { fail(bad, null); return false }
        val v = try {
            VersionInfo.version
        } catch (t: Throwable) {
            fail("the engine does not answer (${t.javaClass.simpleName})", t)
            return false
        }
        // The native side finds the Kotlin classes and fields by name, so another version could
        // read the wrong fields and crash. Never call into a library that does not match.
        if (v != PackPins.SHERPA_VERSION) { fail("engine $v does not match ${PackPins.SHERPA_VERSION}", null); return false }
        nativeOk = true
        val ort = runCatching { VersionInfo.onnxruntimeVersion }.getOrDefault("?")
        log("STT native loaded: sherpa-onnx $v, onnxruntime $ort")
        return true
    }

    private fun fail(why: String, t: Throwable?) {
        broken = why
        log("STT off until Twitch restarts: $why" + (t?.let { " ($it)" } ?: ""))
        // This usually happens as a break starts: the toast waits until the ad is over, and the
        // engine's row in the menu says the same meanwhile.
        AdTime.toast("Speech-to-text could not start: $why")
    }

    /** Stream first, then the recognizer, then both references dropped. Always on ptv-stt. */
    private fun unload(why: String) {
        setTarget(0, why)
        ring?.clear()
        bg.removeCallbacks(unloadTask)
        val s = stream
        val r = rec
        stream = null
        rec = null
        loadedNow = false
        if (s != null) release(s)
        if (r != null) release(r)
        res.reset(16000)
        fed = 0L; lastPartial = ""; curPlayer = null
        if (s != null || r != null) log("STT unloaded ($why)")
    }

    private fun release(o: Any) {
        val ok = runCatching {
            when (o) {
                is OnlineStream -> o.release()
                is OnlineRecognizer -> o.release()
            }
        }.isSuccess
        // A failed release may not have cleared its pointer, and the finalizer would free it again.
        if (!ok) { leaked.add(o); log("STT a native release failed, kept it to stay safe") }
    }

    private fun registerWatchers(ctx: Context) {
        if (watchers) return
        watchers = true
        val app = ctx.applicationContext ?: ctx
        // Called on the main thread; the release itself must run on ptv-stt.
        runCatching {
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                // The RUNNING_ levels are deprecated on Android 14, but older phones still send them.
                @Suppress("DEPRECATION")
                override fun onTrimMemory(level: Int) {
                    val low = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                    if (low) lowMemory("trim level $level")
                }
                override fun onLowMemory() = lowMemory("low memory")
                override fun onConfigurationChanged(c: Configuration) {}
            })
        }.onFailure { log("STT memory watch failed: $it") }
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val pm = app.getSystemService(PowerManager::class.java)
            pm?.addThermalStatusListener(Executor { bg.post(it) }) { st ->
                safely("thermal") {
                    val hot = st >= PowerManager.THERMAL_STATUS_SEVERE
                    if (hot != thermalHold) { thermalHold = hot; log("STT thermal status $st"); evaluate() }
                }
            }
        }.onFailure { log("STT thermal watch failed: $it") }
    }

    private fun lowMemory(why: String) {
        bg.post {
            safely("memory") {
                if (rec == null) return@safely
                // Held until this break ends, so the model is not loaded straight back in.
                memoryHold = inBreak
                unload(why)
            }
        }
    }

    // ---- recognition (ptv-stt) ----

    private fun process() {
        val rg = ring ?: return
        if (rec == null) return
        val h = rg.head.get()
        var t = rg.tail.get()
        while (t != h) {
            val i = t and rg.mask
            val rate = rg.rate[i]
            val ch = rg.ch[i]
            if (rg.gap[i] || rate != curRate || ch != curCh) {
                endUtterance()
                curRate = rate; curCh = ch
                res.reset(rate)
            }
            val frames = if (rate > 0) downmix(rg.data[i], rg.len[i], ch) else 0
            t++
            // The slot's bytes are copied out: it is free for the audio thread again.
            rg.tail.lazySet(t)
            if (frames > 0) res.process(mono, frames)
            if (res.outN >= 16_000) feed()
        }
        feed()
        // Nothing given to the stream since its last reset (the tap is off, or the stream has
        // captions of its own): there is nothing to decode, so the recognizer is not asked.
        if (fed == 0L) return
        val r = rec ?: return
        val s = stream ?: return
        decodeAll(r, s)
        val text = r.getResult(s).text.trim()
        val now = SystemClock.uptimeMillis()
        if (r.isEndpoint(s)) {
            if (text.isNotEmpty()) emit(text, true)
            r.reset(s)
            fed = 0L; lastPartial = ""
            return
        }
        if (text != lastPartial && now - lastPartialAt >= PARTIAL_MS) {
            lastPartial = text
            lastPartialAt = now
            if (text.isNotEmpty()) emit(text, false)
        }
    }

    /** Hand the resampled audio to the stream. A copy of exactly that length, as JNI needs. */
    private fun feed() {
        val s = stream ?: return
        val n = res.outN
        if (n <= 0) return
        s.acceptWaveform(res.out.copyOf(n), 16000)
        res.outN = 0
        fed += n
        audioSamples += n
    }

    private fun decodeAll(r: OnlineRecognizer, s: OnlineStream) {
        val t0 = System.nanoTime()
        var k = 0
        while (k++ < 64 && r.isReady(s)) r.decode(s)
        decodeNs += System.nanoTime() - t0
    }

    /** A gap, a new format or the tap stopping: finish the current line as final text. */
    private fun endUtterance() {
        val r = rec ?: return
        val s = stream ?: return
        feed()
        if (fed <= 0L) return
        s.acceptWaveform(FloatArray(TAIL_SAMPLES), 16000)
        decodeAll(r, s)
        val text = r.getResult(s).text.trim()
        if (text.isNotEmpty()) emit(text, true)
        r.reset(s)
        fed = 0L; lastPartial = ""
    }

    /** To CaptionHub on the main thread, where its state and views live. */
    private fun emit(text: String, fin: Boolean) {
        if (Settings.get(KEY_LOG, false)) log("STT ${if (fin) "final" else "partial"}: $text")
        main.post { runCatching { CaptionHub.onSttText(text, fin) }.onFailure { log("STT caption hand-off failed: $it") } }
    }

    /** Signed 16-bit little-endian, any number of channels, averaged to mono floats in [mono]. */
    private fun downmix(b: ByteArray, len: Int, ch: Int): Int {
        if (ch <= 0) return 0
        val frames = len / (2 * ch)
        if (mono.size < frames) mono = FloatArray(frames)
        val scale = 1f / (32768f * ch)
        var k = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                sum += (b[k].toInt() and 0xff) or (b[k + 1].toInt() shl 8)
                k += 2
            }
            mono[f] = sum * scale
        }
        return frames
    }

    private fun stats() {
        val now = SystemClock.uptimeMillis()
        if (now - statsAt < STATS_MS) return
        statsAt = now
        val rg = ring
        val dropped = rg?.dropped ?: 0
        val errors = tapErrors
        if (errors != statsErrors) { log("STT copy errors: $errors" + if (tapOff) ", copying stopped" else ""); statsErrors = errors }
        if (!Settings.get(KEY_LOG, false)) { statsDropped = dropped; return }
        log("STT speed ${rtf()} of real time, audio ${audioSamples / 16000} s, dropped +${dropped - statsDropped}, " +
            "waiting ${rg?.used() ?: 0} of $SLOTS slots, ${curRate} Hz ${curCh} ch, target $target" +
            if (badFormat) ", unsupported format seen" else "")
        statsDropped = dropped
    }

    private fun rtf(): String =
        if (audioSamples <= 0) "-" else String.format(Locale.US, "%.2f", decodeNs / 1e9 / (audioSamples / 16000.0))

    // ---- test commands (DevBridge DEV_STT) ----

    /**
     * do=status: logs the state.
     * do=load / do=unload: load the model now as if a break had started, or release it now.
     * do=wav path=/sdcard/Android/data/<pkg>/files/ptv_test/talk.wav [realtime=true]: feeds a 16-bit
     *   PCM WAV through the same ring, resampler and recognizer (no real PbyP needed).
     * do=pack_download [which=engine|model|both]: downloads like the menu does.
     * do=pack_verify: hashes both packs now; a pack that fails is deleted.
     * do=pack_delete: deletes both packs.
     * do=pack_corrupt: flips one byte of the engine library on disk, to test pack_verify.
     * do=feed_main: copies the MAIN player's sound (the stream on screen) into the recognizer, so
     *   speech to text can be tried outside an ad. Needs stt_captions on and both packs; set
     *   dev_stt_log to see the text in logcat. Ends with do=stop.
     * do=stop: ends feed_main and any WAV feed; the model unloads 60 s later as after a break.
     * "op" works as well as "do".
     */
    fun dev(i: Intent) {
        val op = (i.getStringExtra("do") ?: i.getStringExtra("op")).orEmpty()
        val ctx = Settings.ctx() ?: run { log("STT dev: no context yet"); return }
        when (op) {
            "status", "stats" -> bg.post { safely("status") { devStatus() } }
            "load" -> { devBreak = true; bg.post { safely("dev load") { maybeHook(); evaluate() } } }
            "unload" -> { devBreak = false; bg.post { safely("dev unload") { unload("test command"); evaluate() } } }
            "feed_main" -> {
                if (!installed) { log("STT feed_main: speech to text is not installed (is PurpleTV on?)"); return }
                if (!on()) {
                    log("STT feed_main: switch it on first: DEV_SET --es key stt_captions --ez b true " +
                        "(and pbyp_captions or read_aloud must be on)")
                    return
                }
                devMain = true
                log("STT feed_main: copying the main player's sound into the recognizer until do=stop" +
                    if (Settings.get(KEY_LOG, false)) "" else " (DEV_SET dev_stt_log true shows the text)")
                bg.post { safely("dev feed_main") { maybeHook(); evaluate() } }
            }
            "stop" -> {
                val was = devMain || devFeeding || devBreak
                devMain = false; devFeeding = false; devBreak = false
                log("STT stop: " + if (was) "test feeds ended" else "nothing was running")
                bg.post { safely("dev stop") { evaluate() } }
            }
            "wav" -> devWav(i.getStringExtra("path").orEmpty(), i.getBooleanExtra("realtime", true))
            "pack_download" -> devDownload(ctx, i.getStringExtra("which") ?: "both")
            "pack_verify" -> Thread({ log("STT verify: " + SttPacks.verify(ctx)) }, "ptv-stt-dl").apply { isDaemon = true }.start()
            "pack_delete" -> SttPacks.deleteAll(ctx)
            "pack_corrupt" -> log("STT corrupt: " + runCatching { SttPacks.devCorrupt(ctx) }.getOrElse { "failed: $it" })
            else -> log("STT dev: unknown do '$op' (status, feed_main, stop, load, unload, wav, pack_download, pack_verify, pack_delete, pack_corrupt)")
        }
    }

    /**
     * The main player for feed_main: the one drawing to the screen, never the PbyP player (it
     * takes a surface too while it is shown), else the one Twitch last loaded a stream into.
     */
    private fun mainPlayer(): Any? {
        val pbyp = PbypWatch.player()
        return PlayerStats.shownPlayer()?.takeIf { it !== pbyp }
            ?: RefreshButton.currentPlayer()?.takeIf { it !== pbyp }
    }

    private fun devDownload(ctx: Context, which: String) {
        val model = { SttPacks.downloadModel(ctx, {}, { ok, m -> log("STT dev model: ok=$ok $m") }) }
        when (which) {
            "engine" -> SttPacks.downloadEngine(ctx, {}, { ok, m -> log("STT dev engine: ok=$ok $m") })
            "model" -> model()
            else -> SttPacks.downloadEngine(ctx, {}, { ok, m -> log("STT dev engine: ok=$ok $m"); if (ok) model() })
        }
    }

    private fun devStatus() {
        val rg = ring
        log("STT status on=${on()} hooked=$hooked" + (hookError?.let { " hookError=$it" } ?: "") +
            " deferred=$hookDeferred native=$nativeOk broken=$broken loaded=${rec != null} break=$inBreak" +
            " active=${PbypWatch.active} visible=${PbypWatch.visible} target=$target" +
            " ring=${rg?.used() ?: "-"}/$SLOTS dropped=${rg?.dropped ?: 0} errors=$tapErrors tapOff=$tapOff" +
            " badFormat=$badFormat format=$curRate/$curCh speed=${rtf()} holds=$memoryHold/$thermalHold dev=$devBreak/$devFeeding/$devMain")
        Settings.ctx()?.let { log("STT packs: " + SttPacks.status(it).replace('\n', ';')) }
    }

    /** Feeds a WAV file through the ring as if it were the PbyP player's sound. Test aid only. */
    private fun devWav(path: String, realtime: Boolean) {
        if (devFeeding) { log("STT wav: already feeding"); return }
        Thread({
            try {
                RandomAccessFile(File(path), "r").use { f ->
                    val w = readWav(f)
                    log("STT wav: ${w.rate} Hz, ${w.ch} ch, ${w.len / (2L * w.ch * w.rate)} s, realtime=$realtime")
                    devFeeding = true
                    kick()
                    // Wait for the model, as a real break would.
                    val until = SystemClock.uptimeMillis() + 20_000
                    while (!loadedNow && SystemClock.uptimeMillis() < until) Thread.sleep(100)
                    if (!loadedNow) { log("STT wav: the model did not load, see the lines above"); return@use }
                    val rg = ensureRing()
                    val frame = 2 * w.ch
                    val chunk = maxOf(frame, w.rate / 50 * frame)
                    val buf = ByteArray(chunk)
                    var left = w.len - w.len % frame
                    f.seek(w.off)
                    while (left > 0 && devFeeding) {
                        val n = minOf(left, chunk.toLong()).toInt()
                        f.readFully(buf, 0, n)
                        while (!rg.putArray(buf, n, w.rate, w.ch)) Thread.sleep(10)
                        left -= n
                        if (realtime) Thread.sleep(20)
                    }
                    log("STT wav: done")
                }
            } catch (t: Throwable) {
                log("STT wav failed: $t")
            } finally {
                // Let the last words come out before the break counts as over.
                runCatching { Thread.sleep(1500) }
                devFeeding = false
                kick()
            }
        }, "ptv-stt-wav").apply { isDaemon = true }.start()
    }

    private class Wav(val rate: Int, val ch: Int, val off: Long, val len: Long)

    private fun readWav(f: RandomAccessFile): Wav {
        val h = ByteArray(12)
        f.readFully(h)
        if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" || String(h, 8, 4, Charsets.US_ASCII) != "WAVE") error("not a WAV file")
        var rate = 0
        var ch = 0
        var pcm16 = false
        val c = ByteArray(8)
        while (true) {
            f.readFully(c)
            val id = String(c, 0, 4, Charsets.US_ASCII)
            val size = le32(c, 4).toLong() and 0xffffffffL
            if (id == "fmt ") {
                val b = ByteArray(size.toInt().coerceIn(16, 64))
                f.readFully(b)
                val tag = le16(b, 0)
                ch = le16(b, 2); rate = le32(b, 4)
                pcm16 = (tag == 1 || tag == 0xFFFE) && le16(b, 14) == 16
                f.seek(f.filePointer - b.size + size + (size and 1))
            } else if (id == "data") {
                if (!pcm16 || ch <= 0 || rate <= 0) error("only 16-bit PCM WAV files")
                return Wav(rate, ch, f.filePointer, minOf(size, f.length() - f.filePointer))
            } else {
                f.seek(f.filePointer + size + (size and 1))
            }
        }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    // ---- small helpers ----

    private fun tag(o: Any) = Integer.toHexString(System.identityHashCode(o))

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { log("STT $what failed: $t") }
    }
}

/**
 * Fixed ring of byte slots between one writer (IVS's audio thread, or the WAV test feeder) and
 * ptv-stt. Everything is made up front; the writer only copies bytes and moves [head], the reader
 * only moves [tail]. lazySet publishes a slot after its bytes and details are written.
 */
private class PcmRing(val slots: Int, private val slotBytes: Int) {
    val mask = slots - 1
    val data = Array(slots) { ByteArray(slotBytes) }
    val len = IntArray(slots)
    val rate = IntArray(slots)
    val ch = IntArray(slots)
    val gap = BooleanArray(slots)
    val head = AtomicInteger()
    val tail = AtomicInteger()
    /** One writer at a time; a second one drops its piece instead of waiting. */
    val writing = AtomicBoolean(false)
    @Volatile var dropped = 0
    @Volatile var gapNext = true

    fun used() = head.get() - tail.get()
    fun empty() = used() == 0

    /** Reader only: forget whatever waits. */
    fun clear() { tail.set(head.get()) }

    /**
     * Writer, holding [writing]: [n] bytes from [src]'s position, whole frames only, split over
     * as many slots as needed. When the ring is full the rest is dropped and the gap marked.
     */
    fun put(src: ByteBuffer, n: Int, r: Int, c: Int) {
        val frame = 2 * c
        val per = slotBytes / frame * frame
        if (per <= 0) return
        var left = n - n % frame
        var h = head.get()
        val t = tail.get()
        while (left > 0) {
            if (h - t >= slots) { dropped++; gapNext = true; return }
            val i = h and mask
            val k = minOf(left, per)
            src.get(data[i], 0, k)
            len[i] = k; rate[i] = r; ch[i] = c; gap[i] = gapNext
            gapNext = false
            h++
            head.lazySet(h)
            left -= k
        }
    }

    /** Test feeder: like [put] from an array. False when the ring has no room (try again). */
    fun putArray(src: ByteArray, n: Int, r: Int, c: Int): Boolean {
        if (!writing.compareAndSet(false, true)) return false
        try {
            val frame = 2 * c
            val per = slotBytes / frame * frame
            if (per <= 0) return true
            val need = (n + per - 1) / per
            var h = head.get()
            if (slots - (h - tail.get()) < need) return false
            var off = 0
            var left = n - n % frame
            while (left > 0) {
                val i = h and mask
                val k = minOf(left, per)
                System.arraycopy(src, off, data[i], 0, k)
                len[i] = k; rate[i] = r; ch[i] = c; gap[i] = gapNext
                gapNext = false
                h++
                head.lazySet(h)
                off += k; left -= k
            }
            return true
        } finally {
            writing.set(false)
        }
    }
}

/**
 * Mono float samples at any rate in, 16 kHz out, for the recognizer. A 63-tap windowed-sinc
 * low-pass at 7 kHz (Blackman window) keeps sound above 8 kHz from folding back into the speech
 * band, then the filtered signal is read at steps of rate/16000: every third sample for 48 kHz,
 * and with linear interpolation between filtered samples for 44.1 kHz. Only the outputs that are
 * kept are filtered. 16 kHz passes straight through. State carries across pieces and starts again
 * on reset (a gap or a new format). ptv-stt only.
 */
private class Resampler16k {
    var out = FloatArray(16_384)
    var outN = 0
    private var x = FloatArray(16_384)
    private var n = 0
    private var pos = 0.0
    private var step = 1.0
    private var taps = floatArrayOf(1f)
    private var half = 0
    private var rate = 16000

    fun reset(inRate: Int) {
        rate = inRate
        step = inRate / 16000.0
        if (inRate > 16000) {
            half = HALF
            taps = lowpass(CUT_HZ / inRate, half)
        } else {
            half = 0
            taps = floatArrayOf(1f)
        }
        // Half a filter of silence first, so the first output lines up with the first input.
        n = half
        java.util.Arrays.fill(x, 0, n, 0f)
        pos = half.toDouble()
    }

    fun process(inp: FloatArray, len: Int) {
        if (len <= 0 || rate <= 0) return
        if (rate == 16000) {
            ensureOut(len)
            System.arraycopy(inp, 0, out, outN, len)
            outN += len
            return
        }
        if (n + len > x.size) x = x.copyOf(maxOf(x.size * 2, n + len))
        System.arraycopy(inp, 0, x, n, len)
        n += len
        ensureOut(((n - pos) / step).toInt() + 2)
        while (true) {
            val i = pos.toInt()
            if (i + 1 + half >= n) break
            val f = (pos - i).toFloat()
            val a = fir(i)
            out[outN++] = if (f < 1e-4f) a else a + (fir(i + 1) - a) * f
            pos += step
        }
        // Keep only what the next outputs still need.
        val drop = pos.toInt() - half
        if (drop > 0) {
            System.arraycopy(x, drop, x, 0, n - drop)
            n -= drop
            pos -= drop
        }
    }

    private fun fir(c: Int): Float {
        var s = 0f
        val base = c - half
        for (j in taps.indices) s += taps[j] * x[base + j]
        return s
    }

    private fun ensureOut(more: Int) {
        if (outN + more > out.size) out = out.copyOf(maxOf(out.size * 2, outN + more))
    }

    companion object {
        const val HALF = 31
        const val CUT_HZ = 7000.0

        /** Blackman-windowed sinc low-pass, [fc] as a fraction of the input rate, unity gain. */
        fun lowpass(fc: Double, half: Int): FloatArray {
            val len = 2 * half + 1
            val h = DoubleArray(len)
            var sum = 0.0
            for (k in 0 until len) {
                val m = k - half
                val sinc = if (m == 0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
                val w = 0.42 - 0.5 * cos(2 * PI * k / (len - 1)) + 0.08 * cos(4 * PI * k / (len - 1))
                h[k] = sinc * w
                sum += h[k]
            }
            return FloatArray(len) { (h[it] / sum).toFloat() }
        }
    }
}
