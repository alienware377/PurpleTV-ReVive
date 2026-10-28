package tv.purple.xp

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The "Dynamic light" filters: exposure, gamma, contrast and black point that follow the stream.
 *
 * A few times a second a tiny copy of the current video frame (48x27) is measured: its average
 * brightness and where its darkest and brightest two percent sit. Each filter turns that into a
 * correction, eased in over its own reaction time so scene changes never flicker, and hands it to
 * the shader in the two slots other filters use for their tone range.
 *
 * The corrections are worked out in shader order (black point, exposure, gamma, contrast), each on
 * the picture as the earlier ones leave it, so stacking them does not fix the same thing twice.
 * Measuring always reads the stream itself, never the filtered picture, so nothing feeds back.
 */
object DynLight {

    /** In the order the shader applies them. */
    val KEYS = listOf("d_black", "d_exp", "d_gamma", "d_con")

    /** Default for each filter's "target" extra, 0..100. */
    val DEF_TARGET = mapOf("d_black" to 10, "d_exp" to 45, "d_gamma" to 50, "d_con" to 60)
    const val DEF_SPEED = 40

    const val W = 48
    const val H = 27
    private const val TICK_MS = 150L

    private val lock = Any()
    private val out = HashMap<String, FloatArray>()
    private val sent = HashMap<String, FloatArray>()
    private var lastAt = 0L
    private var logged = 0L

    fun anyOn(): Boolean = Settings.get(VideoFilters.KEY_ON, true) &&
        KEYS.any { FilterDefs.amount(FilterDefs.index(it)) > 0 }

    fun active() = VideoFilters.fullSupport() && anyOn()

    /** The shader's second and third values for [key]: the live correction. */
    fun yz(key: String): FloatArray = synchronized(lock) { out[key]?.copyOf() } ?: neutral(key)

    private fun neutral(key: String) = if (key == "d_con") floatArrayOf(0f, 0.5f) else floatArrayOf(0f, 0f)

    // ---- measuring ----

    private val hist = IntArray(256)

    /** Pixels from Bitmap.getPixels (ARGB). */
    fun feedArgb(px: IntArray, n: Int) = synchronized(lock) {
        hist.fill(0); var sum = 0L
        for (i in 0 until n) {
            val c = px[i]
            val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
            hist[l]++; sum += l
        }
        fromHist(n, sum)
    }

    /** Pixels from glReadPixels (RGBA bytes). */
    fun feedRgba(buf: ByteBuffer, n: Int) = synchronized(lock) {
        hist.fill(0); var sum = 0L
        for (i in 0 until n) {
            val r = buf.get(i * 4).toInt() and 0xFF; val g = buf.get(i * 4 + 1).toInt() and 0xFF
            val b = buf.get(i * 4 + 2).toInt() and 0xFF
            val l = (r * 77 + g * 150 + b * 29) shr 8
            hist[l]++; sum += l
        }
        fromHist(n, sum)
    }

    private fun fromHist(n: Int, sum: Long) {
        if (n <= 0) return
        // A black frame is the stream loading or switching, not a dark scene: correcting for it
        // would boost the first real frames to full strength.
        if (sum < n * 2L && hist[0] + hist[1] + hist[2] + hist[3] > n * 0.97f) return
        fun pct(p: Float): Float {
            val want = (n * p).toInt(); var acc = 0
            for (v in 0..255) { acc += hist[v]; if (acc > want) return v / 255f }
            return 1f
        }
        update(sum / (n * 255f), pct(0.02f), pct(0.98f))
    }

    /** Turn one measurement into eased corrections, each seeing the earlier ones' effect. */
    private fun update(mean0: Float, black0: Float, white0: Float) {
        val now = SystemClock.uptimeMillis()
        // A first measurement, or the first after a pause (player closed, light turned off).
        val fresh = lastAt == 0L || now - lastAt > 5000
        val dt = if (lastAt == 0L) 1f else ((now - lastAt) / 1000f).coerceIn(0.01f, 1f)
        lastAt = now
        var m = mean0; var b = black0; var w = white0
        for (key in KEYS) {
            val a = FilterDefs.amount(FilterDefs.index(key)) / 100f
            if (a <= 0f) { out.remove(key); continue }
            val t = FilterDefs.extraInt(key, "target", DEF_TARGET[key] ?: 50).coerceIn(0, 100) / 100f
            val raw = when (key) {
                // How far the darkest parts sit above the wanted black; negative lifts crushed blacks.
                "d_black" -> floatArrayOf((b - t * 0.12f).coerceIn(-0.08f, 0.4f), 0f)
                // Stops of light to bring the average to the target brightness.
                "d_exp" -> floatArrayOf(log2((0.25f + t * 0.35f) / max(m, 0.01f)).coerceIn(-1.5f, 2f), 0f)
                // The power that moves the average to the target midtone, as log2 of the exponent.
                "d_gamma" -> {
                    val g = (ln(0.3f + t * 0.3f) / ln(m.coerceIn(0.02f, 0.98f))).coerceIn(0.45f, 2.2f)
                    floatArrayOf(log2(g), 0f)
                }
                // How much to stretch the spread of tones around its middle.
                else -> floatArrayOf(((0.5f + t * 0.45f) / max(w - b, 0.05f)).coerceIn(0.75f, 2.2f) - 1f,
                    ((b + w) / 2f).coerceIn(0.1f, 0.9f))
            }
            val speed = FilterDefs.extraInt(key, "speed", DEF_SPEED).coerceIn(0, 100) / 100f
            val tau = 0.25f + 3f * (1f - speed)
            val k = 1f - exp(-dt / tau)
            // Start from no correction and ease in, rather than jumping on the first measurement.
            val cur = out[key] ?: neutral(key).let { if (key == "d_con") floatArrayOf(0f, raw[1]) else it }
            cur[0] += (raw[0] - cur[0]) * k; cur[1] += (raw[1] - cur[1]) * k
            out[key] = cur
            // Carry the measurement through this correction, as the shader will apply it.
            when (key) {
                "d_black" -> {
                    val bp = a * cur[0]
                    val f = { x: Float -> ((x - bp) / max(1f - bp, 0.05f)).coerceIn(0f, 1f) }
                    m = f(m); b = f(b); w = f(w)
                }
                "d_exp" -> { val g = 2f.pow(a * cur[0]); m = knee(m * g); b = knee(b * g); w = knee(w * g) }
                "d_gamma" -> { val e = 2f.pow(a * cur[0]); m = m.pow(e); b = b.pow(e); w = w.pow(e) }
            }
        }
        // Every 5 s filled a release log with these lines while the light was on. A release
        // build logs when measuring starts and then once a minute.
        if (fresh || now - logged > (if (BuildConfig.DEBUG) 5_000L else 60_000L)) {
            logged = now
            log("DL mean=%.2f black=%.2f white=%.2f -> %s".format(mean0, black0, white0,
                out.entries.joinToString(" ") { "${it.key}=%.2f/%.2f".format(it.value[0], it.value[1]) }))
        }
    }

    /** The shader's highlight roll-off for dynamic exposure. */
    fun knee(t: Float) = if (t <= 0.8f) t else 0.8f + 0.2f * (t - 0.8f) / ((t - 0.8f) + 0.2f)

    private fun log2(x: Float) = (ln(x.toDouble()) / ln(2.0)).toFloat()

    /** True when the corrections moved enough since last sent to the screen to be worth redrawing. */
    private fun moved(): Boolean = synchronized(lock) {
        var d = 0f
        for ((k, v) in out) { val s = sent[k]; d = max(d, if (s == null) 1f else max(abs(v[0] - s[0]), abs(v[1] - s[1]))) }
        if (sent.keys.any { it !in out }) d = 1f
        if (d < 0.004f) return false
        sent.clear(); for ((k, v) in out) sent[k] = v.copyOf()
        true
    }

    // ---- the Android 13+ path: measure the video view from the UI thread ----

    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var bmp: Bitmap? = null
    private val px = IntArray(W * H)

    /** From each VideoFilters pass. The GL path measures inside its own drawing instead. */
    fun ensureRunning() {
        if (running || !active() || GlVideoFilter.available()) return
        running = true
        main.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            // Stop when no player is really on screen. Twitch in the background keeps its player
            // attached with its window hidden, so checking attachment alone measured forever, and a
            // closed player leaves nothing to measure. The next VideoFilters.attach() pass starts
            // this again through ensureRunning().
            val live: List<TextureView> = runCatching { if (active() && !GlVideoFilter.available()) liveViews() else emptyList() }
                .getOrDefault(emptyList())
            if (live.isEmpty()) { running = false; return }
            runCatching { measureView(live) }.onFailure { log("DL measure: $it") }
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Video views that are attached, in a visible window and not hidden by a parent. */
    private fun liveViews(): List<TextureView> = VideoFilters.videoViews()
        .filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE && it.isShown }

    private fun measureView(live: List<TextureView>) {
        val tv = live.firstOrNull { it.isAvailable && it.width > 0 } ?: return
        val b = bmp ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also { bmp = it }
        // The view's own frame, before its RenderEffect, so the filters never measure themselves.
        tv.getBitmap(b) ?: return
        b.getPixels(px, 0, W, 0, 0, W, H)
        feedArgb(px, W * H)
        if (moved()) VideoFilters.reapplyEffects()
    }
}
