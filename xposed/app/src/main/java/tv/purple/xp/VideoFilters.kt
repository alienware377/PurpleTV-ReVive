package tv.purple.xp

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Picture filters for the live and VOD player. The filter list and settings are in [FilterDefs],
 * the maths in [FilterShader].
 *
 * Twitch draws the video into a plain framework TextureView inside "playback_view_container".
 * On Android 13+ the filters are a RuntimeShader RenderEffect on that view: the GPU applies them
 * as each frame is composited, nothing touches the decoder, and nothing obfuscated is involved.
 * Older Android cannot run a custom shader on a view; there [GlVideoFilter] takes over if it is
 * available, otherwise a colour matrix covers what it can.
 */
object VideoFilters {

    const val KEY_BUTTON = "show_filters_button"
    const val KEY_ON = "vf_on"
    /** A new key for the audio announcement, so people who closed the first tip see it once more. */
    const val KEY_TIP = "vf_tip_audio_dismissed"

    val FILTERS get() = FilterDefs.FILTERS

    fun shaderSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Filters the colour-matrix fallback can do when no shader path is available. */
    private val MATRIX_KEYS = setOf("exposure", "bright", "contrast", "sat", "temp", "tint", "hue", "invert", "fade", "mono")

    fun fullSupport() = shaderSupported() || GlVideoFilter.available()

    fun usable(i: Int): Boolean {
        // Dynamic light measures one picture at a time, so it stays with Twitch's own player.
        if (Settings.inFxScope() && FilterDefs.FILTERS[i].group == FilterDefs.Group.DYNAMIC) return false
        return fullSupport() || FilterDefs.FILTERS[i].key in MATRIX_KEYS
    }

    /** True when the master switch is on and at least one usable filter is moved off zero. */
    fun anyActive(): Boolean =
        Settings.get(KEY_ON, true) && FilterDefs.FILTERS.indices.any { usable(it) && FilterDefs.amount(it) != 0 }

    // ---- applying ----

    /** What each video view currently has applied, so unchanged views are left alone. */
    private val applied = WeakHashMap<TextureView, String>()
    private val views = WeakHashMap<TextureView, Boolean>()
    private val handler = Handler(Looper.getMainLooper())

    /** Called from every ViewHider pass, and right away whenever something in the panel changes. */
    fun attach(root: View) {
        val ctx = root.context
        val id = Res.id(ctx, "playback_view_container")
        applyPanes()
        // Twitch's own player follows the shared settings, even with a stream's filter panel open.
        if (id != 0) Settings.withFxScope(null) {
            // Which path draws the filters is part of the key: when the GL path gives up (or the
            // GL switch is flipped) the settings stay the same, so without it no view would ever
            // be handed over to the RuntimeShader or colour matrix path.
            val sig = cachedSignature() + (if (GlVideoFilter.available()) "|gl" else "|fb")
            val found = ArrayList<TextureView>()
            collectVideos(root, id, found)
            for (tv in found) {
                views[tv] = true
                val s = sig + "|" + tv.width + "x" + tv.height
                if (applied[tv] == s) continue
                applied[tv] = s
                runCatching { apply(tv) }.onFailure { log("filters apply failed: $it") }
            }
            // Only screens with a player start the tickers; the grain one stops by itself once
            // no video is showing.
            if (found.isNotEmpty()) { scheduleGrain(); DynLight.ensureRunning() }
        }
        runCatching { attachButton(root) }.onFailure { log("filters button: $it") }
    }

    /** Re-run [attach] on the screen in front, after a change made in the panel. */
    fun refresh(anyView: View) {
        // The row is fitted again after, or the button would sit where it places itself.
        anyView.rootView?.let { r -> runCatching { attach(r) }; runCatching { BarFit.apply(r) } }
    }

    private var sigKey = -1
    private var sigCache = "off"

    /** [signature], recomputed only when a setting changed: it reads every filter's settings. */
    private fun cachedSignature(): String {
        val key = Settings.rev * 2 + (if (GlVideoFilter.available()) 1 else 0)
        if (key != sigKey) { sigCache = signature(); sigKey = key }
        return sigCache
    }

    private fun signature(): String {
        if (!anyActive()) return "off"
        val sb = StringBuilder()
        for (i in FilterDefs.FILTERS.indices) {
            sb.append(FilterDefs.amount(i)).append(':').append(FilterDefs.lo(i)).append(':').append(FilterDefs.hi(i)).append(',')
        }
        // Every other stored filter setting, whatever its type. A hand-picked list read with typed
        // getters missed the switches (stored as Booleans) and the bokeh, speed and moving grain
        // settings, so changing those never re-applied the filters. Panel layout and the tip are
        // left out so they do not rebuild the effect, and the GL switch is already in the cache key
        // and in the path attach() adds to each view's key.
        for ((k, v) in Settings.all().toSortedMap())
            if (k.startsWith("vf_") && !k.startsWith("vf_ui_") && !k.startsWith("vf_tip") && k != GlVideoFilter.KEY_FORCE)
                sb.append(k).append('=').append(v).append(';')
        return sb.toString()
    }

    private fun collectVideos(v: View, containerId: Int, out: MutableList<TextureView>) {
        if (v.id == containerId) { textureViewsIn(v, out); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectVideos(v.getChildAt(i), containerId, out)
    }

    private fun textureViewsIn(v: View, out: MutableList<TextureView>) {
        if (v is TextureView) { out.add(v); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) textureViewsIn(v.getChildAt(i), out)
    }

    // ---- two-streams players: each channel has its own filter settings ----

    private val panes = WeakHashMap<TextureView, String>()
    private var panesSeen = ""

    fun addPane(tv: TextureView, login: String) {
        panes[tv] = login
        views[tv] = true
        applied.remove(tv)
        panesSeen = ""
    }

    fun dropPane(tv: TextureView) {
        panes.remove(tv)
        views.remove(tv)
        applied.remove(tv)
        panesSeen = ""
    }

    fun paneScope(tv: TextureView): String? = panes[tv]

    /** The two-streams player showing [st], for the GL path. */
    fun paneFor(st: android.graphics.SurfaceTexture): TextureView? = panes.keys.firstOrNull { it.surfaceTexture === st }

    /** Some two-streams player has filters to draw. */
    fun anyPaneActive(): Boolean = panes.values.toSet().any { s -> Settings.withFxScope(s) { anyActive() } }

    private fun anyAnimated(): Boolean =
        Settings.withFxScope(null) { animated() } || panes.values.toSet().any { s -> Settings.withFxScope(s) { animated() } }

    private fun applyPanes() {
        if (panes.isEmpty()) return
        val now = Settings.rev.toString() + (if (GlVideoFilter.available()) "g" else "f") +
            panes.keys.joinToString { "${it.width}x${it.height}" }
        if (now == panesSeen) return
        panesSeen = now
        for ((tv, s) in panes.entries.toList()) {
            if (!tv.isAttachedToWindow || tv.width == 0) { panesSeen = ""; continue }
            val sig = Settings.withFxScope(s) { signature() } + (if (GlVideoFilter.available()) "|gl" else "|fb") + "|${tv.width}x${tv.height}"
            if (applied[tv] == sig) continue
            applied[tv] = sig
            runCatching { apply(tv) }.onFailure { log("filters apply failed: $it") }
        }
        if (anyPaneActive()) { scheduleGrain(); DynLight.ensureRunning() }
    }

    private fun apply(tv: TextureView) = Settings.withFxScope(panes[tv]) { applyNow(tv) }

    private fun applyNow(tv: TextureView) {
        val on = anyActive()
        when {
            GlVideoFilter.available() -> {
                if (shaderSupported()) tv.setRenderEffect(null)
                // A view filtered by the colour matrix before the GL path was ready would
                // otherwise keep it on top of the GL picture, doing those filters twice.
                tv.setLayerPaint(null)
                GlVideoFilter.update(tv, on)
            }
            shaderSupported() -> {
                if (!on) { tv.setRenderEffect(null); return }
                tv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(buildShader(tv), "content"))
            }
            else -> {
                if (!on) { tv.setLayerPaint(null); return }
                tv.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(fallbackMatrix()) })
            }
        }
    }

    // ---- shader inputs, shared by the RuntimeShader and GL paths ----

    @Volatile var seed = 0f

    /** Seconds for animated effects, wrapped so float precision stays good. */
    fun now(): Float = (android.os.SystemClock.uptimeMillis() % 3_600_000L) / 1000f

    /** Whether any active filter moves by itself, so the picture must be redrawn over time. */
    fun animated(): Boolean {
        if (!anyActive()) return false
        for (i in FilterDefs.FILTERS.indices) {
            val f = FilterDefs.FILTERS[i]
            if (FilterDefs.amount(i) == 0 || !usable(i)) continue
            if (f.group == FilterDefs.Group.GLITCH || f.key == "bokeh") return true
            if (f.key == "grain" && FilterDefs.extraBool("grain", "animated", true)) return true
        }
        return false
    }

    /** All uniforms as name -> values, for whichever shader path is running. */
    fun uniforms(w: Int, h: Int): Map<String, FloatArray> {
        val m = HashMap<String, FloatArray>()
        val on = Settings.get(KEY_ON, true)
        for (i in FilterDefs.FILTERS.indices) {
            val f = FilterDefs.FILTERS[i]
            val a = if (on && usable(i)) FilterDefs.amount(i) / 100f else 0f
            m["f_" + f.key] = when {
                // Animated effects carry their speed (and bokeh its size) where the tone range would be.
                f.group == FilterDefs.Group.GLITCH ->
                    floatArrayOf(a, FilterDefs.extraInt(f.key, "speed", 50).coerceIn(0, 100) / 100f, 0f)
                // Dynamic light carries its live correction, measured from the stream.
                f.group == FilterDefs.Group.DYNAMIC -> DynLight.yz(f.key).let { floatArrayOf(a, it[0], it[1]) }
                f.key == "bokeh" -> floatArrayOf(a, FilterDefs.extraInt("bokeh", "speed", 40).coerceIn(0, 100) / 100f,
                    FilterDefs.extraInt("bokeh", "size", 45).coerceIn(0, 100) / 100f)
                // These carry their balance or size where the tone range would be.
                f.key == "calib" || f.key == "split" -> floatArrayOf(a, FilterDefs.extraInt(f.key, "bal", 0).coerceIn(-100, 100) / 100f, 0f)
                f.key == "glow" -> floatArrayOf(a, FilterDefs.extraInt("glow", "size", 50).coerceIn(0, 100) / 100f, 0f)
                else -> floatArrayOf(a, FilterDefs.lo(i) / 100f, FilterDefs.hi(i) / 100f)
            }
        }
        // Colour mixer: hue, saturation and brightness per colour, -1..1, four colours per vec4.
        fun hsl(f: String, from: Int) = FloatArray(4) { FilterDefs.extraInt("hsl", "$f${from + it}", 0).coerceIn(-100, 100) / 100f }
        m["uHslH0"] = hsl("h", 0); m["uHslH1"] = hsl("h", 4)
        m["uHslS0"] = hsl("s", 0); m["uHslS1"] = hsl("s", 4)
        m["uHslL0"] = hsl("l", 0); m["uHslL1"] = hsl("l", 4)
        val cal = calibMatrix()
        m["uCal0"] = cal.copyOfRange(0, 3); m["uCal1"] = cal.copyOfRange(3, 6); m["uCal2"] = cal.copyOfRange(6, 9)
        m["uSplit"] = floatArrayOf(
            FilterDefs.extraInt("split", "sh", 200).coerceIn(0, 360) / 360f, FilterDefs.extraInt("split", "ss", 0).coerceIn(0, 100) / 100f,
            FilterDefs.extraInt("split", "hh", 35).coerceIn(0, 360) / 360f, FilterDefs.extraInt("split", "hs", 0).coerceIn(0, 100) / 100f)
        m["uLutN"] = floatArrayOf(lutBitmap()?.height?.toFloat() ?: 0f)
        m["uTime"] = floatArrayOf(now())
        m["uBokehMode"] = floatArrayOf(FilterDefs.extraInt("bokeh", "mode", 0).toFloat())
        val bc = FilterDefs.extraInt("bokeh", "color", 0xFFFF8FD8.toInt())
        m["uBokehColor"] = floatArrayOf(Color.red(bc) / 255f, Color.green(bc) / 255f, Color.blue(bc) / 255f)
        m["uBokehDensity"] = floatArrayOf(0.15f + FilterDefs.extraInt("bokeh", "density", 45).coerceIn(0, 100) / 100f * 0.75f)
        m["uSize"] = floatArrayOf(w.toFloat().coerceAtLeast(1f), h.toFloat().coerceAtLeast(1f))
        m["uBlur"] = floatArrayOf((h * 0.012f).coerceIn(4f, 40f))
        m["uBlend"] = floatArrayOf(FilterDefs.extraInt("ovl", "blend", 12).toFloat())
        m["uSeed"] = floatArrayOf(seed)
        m["uGrainSize"] = floatArrayOf(FilterDefs.extraInt("grain", "size", 2).coerceIn(1, 8) * (h / 1080f).coerceAtLeast(0.5f))
        m["uGrainColor"] = floatArrayOf(if (FilterDefs.extraBool("grain", "color", false)) 1f else 0f)
        m["uVigSize"] = floatArrayOf(0.1f + FilterDefs.extraInt("vignette", "size", 50).coerceIn(0, 100) / 100f * 0.7f)
        return m
    }

    fun overlaySource(): String {
        val def = TextureLibrary.builtIns().firstOrNull()?.let { "tex:" + it.file } ?: "color:${0xFFA970FF.toInt()}"
        return FilterDefs.extraStr("ovl", TextureLibrary.KEY_SRC, def)
    }

    fun overlayTiled(): Boolean {
        val src = overlaySource()
        val def = if (src.startsWith("tex:")) TextureLibrary.find(src.removePrefix("tex:"))?.tile ?: true else false
        return FilterDefs.extraBool("ovl", "tile", def)
    }

    /** The overlay layer as a shader in view pixel coordinates. */
    fun overlayShader(w: Int, h: Int): Shader {
        val src = overlaySource()
        if (src.startsWith("color:")) {
            val c = src.removePrefix("color:").toIntOrNull() ?: Color.MAGENTA
            return LinearGradient(0f, 0f, 1f, 0f, c, c, Shader.TileMode.CLAMP)
        }
        val bmp = TextureLibrary.bitmap(src) ?: return LinearGradient(0f, 0f, 1f, 0f, 0, 0, Shader.TileMode.CLAMP)
        val m = Matrix()
        return if (overlayTiled()) {
            val s = (h / 1080f).coerceAtLeast(0.3f) * FilterDefs.extraInt("ovl", "scale", 50).coerceIn(10, 400) / 100f
            m.setScale(s, s)
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(m) }
        } else {
            // Cover the whole picture, centred, cropping the excess.
            val s = maxOf(w / bmp.width.toFloat(), h / bmp.height.toFloat()) *
                FilterDefs.extraInt("ovl", "scale", 100).coerceIn(10, 400) / 100f
            m.setScale(s, s)
            m.postTranslate((w - bmp.width * s) / 2f, (h - bmp.height * s) / 2f)
            BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        }
    }

    /**
     * Calibration as a colour matrix, row by row: each primary (pure red, green, blue) turned
     * around the colour wheel by up to 30 degrees and made more or less saturated, then every row
     * scaled so white stays white.
     */
    fun calibMatrix(): FloatArray {
        fun v(f: String) = FilterDefs.extraInt("calib", f, 0).coerceIn(-100, 100) / 100f
        val hues = floatArrayOf(v("rh"), v("gh"), v("bh"))
        val sats = floatArrayOf(v("rs"), v("gs"), v("bs"))
        val lw = floatArrayOf(0.299f, 0.587f, 0.114f)
        val cols = Array(3) { i ->
            val rad = Math.toRadians(hues[i] * 30.0)
            val cs = Math.cos(rad).toFloat(); val sn = Math.sin(rad).toFloat()
            // The luminance keeping hue rotation the colour matrix fallback uses too.
            val rot = floatArrayOf(
                0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f,
                0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f,
                0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f)
            val c = FloatArray(3) { r -> rot[r * 3 + i] }
            val l = c[0] * lw[0] + c[1] * lw[1] + c[2] * lw[2]
            FloatArray(3) { r -> l + (c[r] - l) * (1f + sats[i]) }
        }
        return FloatArray(9).also { out ->
            for (r in 0..2) {
                val sum = cols[0][r] + cols[1][r] + cols[2][r]
                val k = if (sum > 0.05f) 1f / sum else 1f
                for (c in 0..2) out[r * 3 + c] = cols[c][r] * k
            }
        }
    }

    /** The chosen LUT as the shader's picture, or null when the LUT filter has no file. */
    fun lutBitmap(): Bitmap? = LutLibrary.bitmap(FilterDefs.extraStr("lut", "file", ""))

    private val blank by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    fun lutShader(): Shader = BitmapShader(lutBitmap() ?: blank, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    /** The tone curves as 256 colours; a straight line when the Curves filter is off. */
    fun curveColors(): IntArray =
        if (FilterDefs.amount(FilterDefs.index("curves")) == 0) IntArray(256) { Color.rgb(it, it, it) } else ToneCurve.lut()

    fun curveShader(): Shader = BitmapShader(rampBitmap(curveColors()), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    fun gradientColors() = FilterDefs.currentGradient()
    fun monoColors(): IntArray = intArrayOf(Color.BLACK, FilterDefs.monoColor(), Color.WHITE)

    /** A 256 wide gradient ramp, used both as a shader (AGSL) and as a texture (GL). */
    fun rampShader(colors: IntArray): Shader = LinearGradient(0f, 0f, 256f, 0f, colors, null, Shader.TileMode.CLAMP)

    fun rampBitmap(colors: IntArray): Bitmap {
        // 256 colours are a table, one per pixel, rather than stops of a gradient.
        if (colors.size == 256) return Bitmap.createBitmap(colors, 256, 1, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).drawRect(0f, 0f, 256f, 1f, Paint().apply { shader = rampShader(colors) })
        return b
    }

    /** Shaders built for a given set of active filters, most recently used last. Building one
     *  takes a moment, so the last few are kept for switching back and forth in the panel. */
    private val shaders = object : LinkedHashMap<String, RuntimeShader>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, RuntimeShader>?) = size > 6
    }

    /** Keys of the filters that currently do something. */
    fun activeKeys(): Set<String> {
        val out = HashSet<String>()
        if (!Settings.get(KEY_ON, true)) return out
        for (i in FilterDefs.FILTERS.indices) if (usable(i) && FilterDefs.amount(i) != 0) out.add(FilterDefs.FILTERS[i].key)
        return out
    }

    /** What each shader's inputs were last filled for: settings revision, size, and dynamic light. */
    private val filledFor = java.util.WeakHashMap<RuntimeShader, String>()

    private fun buildShader(tv: TextureView): RuntimeShader = Settings.withFxScope(panes[tv]) { buildShaderNow(tv) }

    private fun buildShaderNow(tv: TextureView): RuntimeShader {
        val active = activeKeys()
        // Per channel: two players with the same filters on still hold different values.
        val id = panes[tv].orEmpty() + "|" + active.sorted().joinToString(",")
        val s = shaders[id] ?: RuntimeShader(FilterShader.agsl(active)).also { shaders[id] = it }
        val w = tv.width.takeIf { it > 0 } ?: 1920
        val h = tv.height.takeIf { it > 0 } ?: 1080
        // The grain tick runs 24 times a second. When neither the settings nor the size changed
        // and no dynamic light is on, only the clock and the grain seed move: those two are set,
        // and the rest (every setting, the overlay and colour shaders) stays as it was filled.
        val dyn = active.any { k -> FilterDefs.FILTERS.firstOrNull { it.key == k }?.group == FilterDefs.Group.DYNAMIC }
        val sig = "${Settings.rev}|$w|$h"
        if (!dyn && filledFor[s] == sig) {
            s.setFloatUniform("uTime", now())
            s.setFloatUniform("uSeed", seed)
            return s
        }
        filledFor[s] = sig
        for ((k, v) in uniforms(w, h)) {
            if (k.startsWith("f_") && k.substring(2) !in active) continue
            when (v.size) {
                1 -> s.setFloatUniform(k, v[0])
                2 -> s.setFloatUniform(k, v[0], v[1])
                3 -> s.setFloatUniform(k, v[0], v[1], v[2])
                else -> s.setFloatUniform(k, v[0], v[1], v[2], v[3])
            }
        }
        s.setInputShader("ovl", overlayShader(w, h))
        s.setInputShader("grad", rampShader(gradientColors()))
        s.setInputShader("mono", rampShader(monoColors()))
        s.setInputShader("curve", curveShader())
        s.setInputShader("lut", lutShader())
        return s
    }

    /** The video views seen so far that are still around. */
    fun videoViews(): List<TextureView> = weakKeys(views)

    /** Redraw every video with fresh shader inputs (Android 13+ path; the GL path reads them per frame). */
    fun reapplyEffects() {
        if (!shaderSupported() || GlVideoFilter.available()) return
        for (tv in weakKeys(views)) {
            if (!tv.isAttachedToWindow || !Settings.withFxScope(panes[tv]) { anyActive() }) continue
            runCatching { tv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(buildShader(tv), "content")) }
        }
    }

    // ---- moving grain ----

    private var grainRunning = false
    private val grainTick = object : Runnable {
        override fun run() {
            // Stop when no video is really on screen. A stopped activity (Twitch playing audio in
            // the background) keeps its player attached, so checking that alone ran this forever.
            // The next ViewHider pass starts it again through attach().
            val live = weakKeys(views).filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE && it.isShown }
            if (live.isEmpty() || GlVideoFilter.available() || !anyAnimated()) { grainRunning = false; return }
            seed = (seed + 1.37f) % 997f
            for (tv in live) {
                if (!Settings.withFxScope(panes[tv]) { anyActive() }) continue
                if (shaderSupported()) runCatching {
                    tv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(buildShader(tv), "content"))
                }
            }
            handler.postDelayed(this, 42)
        }
    }

    private fun scheduleGrain() {
        // The GL path moves its grain on every drawn frame by itself.
        if (grainRunning || GlVideoFilter.available()) return
        if (anyAnimated()) {
            grainRunning = true
            handler.postDelayed(grainTick, 42)
        }
    }

    // ---- colour matrix fallback ----

    private fun amt(key: String) = FilterDefs.amount(FilterDefs.index(key)) / 100f

    /** What a colour matrix can do: exposure, brightness, contrast, saturation, temperature, tint,
     *  hue, invert, fade and a monotone approximation, all on the whole picture. */
    fun fallbackMatrix(): ColorMatrix {
        val m = ColorMatrix()
        fun scale(r: Float, g: Float, b: Float) =
            m.postConcat(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        val e = Math.pow(2.0, amt("exposure") * 2.0).toFloat() * Math.pow(2.0, amt("bright") * 1.0).toFloat()
        scale(e, e, e)
        val c = 1f + amt("contrast") * 1.5f
        val t = 127.5f * (1f - c)
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val tp = amt("temp"); val ti = amt("tint")
        scale((1f + tp * 0.25f) * (1f + ti * 0.12f), (1f + tp * 0.03f) * (1f - ti * 0.2f), (1f - tp * 0.25f) * (1f + ti * 0.12f))
        val hue = amt("hue") * 180f
        if (hue != 0f) {
            val rad = Math.toRadians(hue.toDouble())
            val cs = Math.cos(rad).toFloat(); val sn = Math.sin(rad).toFloat()
            m.postConcat(ColorMatrix(floatArrayOf(
                0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f, 0f, 0f,
                0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f, 0f, 0f,
                0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f)))
        }
        m.postConcat(ColorMatrix().apply { setSaturation((1f + amt("sat")).coerceAtLeast(0f)) })
        val mono = amt("mono")
        if (mono > 0f) {
            val col = FilterDefs.monoColor()
            val mc = ColorMatrix().apply { setSaturation(0f) }
            mc.postConcat(ColorMatrix(floatArrayOf(Color.red(col) / 128f, 0f, 0f, 0f, 0f, 0f, Color.green(col) / 128f, 0f, 0f, 0f,
                0f, 0f, Color.blue(col) / 128f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            m.postConcat(lerp(ColorMatrix(), mc, mono))
        }
        val inv = amt("invert")
        if (inv > 0f) m.postConcat(lerp(ColorMatrix(), ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)), inv))
        val fade = amt("fade")
        if (fade > 0f) {
            val k = 1f - fade * 0.35f; val o = fade * 0.22f * 255f
            m.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)))
        }
        return m
    }

    private fun lerp(a: ColorMatrix, b: ColorMatrix, t: Float): ColorMatrix {
        val x = a.array; val y = b.array
        return ColorMatrix(FloatArray(20) { x[it] + (y[it] - x[it]) * t })
    }

    // ---- the player button and its one-time tip ----

    private const val TAG = "ptv_filters_button"
    private const val TIP_TAG = "ptv_filters_tip"
    private val ROW = arrayOf("create_clip_text_button", "create_clip_button_compose_view", "switch_to_vertical_view",
        "cast_button", "media_route_button",
        "share_button", "settings_button", "audio_and_subtitles")
    private var added = false
    private val seenBars = java.util.Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    private fun attachButton(root: View) {
        val on = Settings.get(KEY_BUTTON, true)
        if (!on && !added) return
        val ctx = root.context
        fun ids(n: String) = Res.id(ctx, n)
        val settingsId = ids("settings_button")
        if (settingsId == 0) return
        val bars = ArrayList<ViewGroup>()
        collectBars(root, settingsId, bars)
        for (bar in bars) {
            var btn = bar.findViewWithTag<ImageView>(TAG)
            if (!on) {
                if (btn != null) bar.removeView(btn)
                bar.findViewWithTag<View>(TIP_TAG)?.let { bar.removeView(it) }
                continue
            }
            val row = ROW.mapNotNull { n -> ids(n).takeIf { it != 0 }?.let { bar.findViewById<View>(it) } }
                .filter { it.parent === bar && it.visibility == View.VISIBLE && it.width > 0 }
                .toMutableList()
            // Sit left of the sleep timer when that is showing too.
            bar.findViewWithTag<View>("ptv_sleep_timer")
                ?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { row.add(it) }
            val ref = sizeRef(bar, row)
            if (ref == null) {
                btn?.visibility = View.GONE
                bar.findViewWithTag<View>(TIP_TAG)?.visibility = View.GONE
                if (seenBars.add(bar)) log("filters: bar ${bar.javaClass.simpleName} has no visible controls yet")
                continue
            }
            if (btn == null) {
                btn = ImageView(bar.context).apply {
                    tag = TAG
                    setImageDrawable(FilterIcons.Tune())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
                    contentDescription = "Video and audio filters"
                    setOnClickListener { FiltersPanel.show(it) }
                }
                bar.addView(btn, ViewGroup.LayoutParams(ref.height, ref.height))
                added = true
            }
            matchSize(btn, ref)
            val x = row.minOf { it.x } - ref.height
            if (btn.x != x) btn.x = x
            if (btn.y != ref.y) btn.y = ref.y
            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
            (btn.drawable as? FilterIcons.Tune)?.setActive(cachedSignature() != "off" || AudioFilters.anyActive())
            runCatching { placeTip(bar, btn, ref) }
        }
    }

    /**
     * A one-time speech bubble under the button announcing the feature. It lives in the same
     * control bar, so it only shows while the player controls do and fades out with them. Its X
     * hides it for good; tapping the bubble itself opens the panel.
     */
    private fun placeTip(bar: ViewGroup, btn: View, ref: View) {
        var tip = bar.findViewWithTag<View>(TIP_TAG)
        if (Settings.get(KEY_TIP, false)) { if (tip != null) bar.removeView(tip); return }
        // The two buttons sit side by side, so their bubbles take turns, the newer feature first.
        if (DualTip.pending() && RefreshButton.liveShown()) { tip?.visibility = View.GONE; return }
        val ctx = bar.context
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
        if (tip == null) {
            val w = dp(250f)
            val box = LinearLayout(ctx)
            tip = box.apply {
                tag = TIP_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = BubbleDrawable(0xF2241B31.toInt(), FilterIcons.PURPLE, dp(12f).toFloat(),
                    dp(1.5f).toFloat(), dp(9f).toFloat())
                setPadding(dp(12f), dp(9f) + dp(9f), dp(4f), dp(9f))
                elevation = dp(6f).toFloat()
                addView(TextView(ctx).apply {
                    text = "New: video and audio filters! Tap here to tweak brightness, colours and textures, or even out the sound and make voices clearer."
                    setTextColor(Color.WHITE); textSize = 13f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(ctx).apply {
                    text = "✕"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                    contentDescription = I18n.t("Dismiss tip")
                    setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
                    setOnClickListener {
                        Settings.set(KEY_TIP, true)
                        (box.parent as? ViewGroup)?.removeView(box)
                    }
                })
                setOnClickListener { FiltersPanel.show(btn) }
            }
            bar.addView(tip, ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (tip.visibility != View.VISIBLE) tip.visibility = View.VISIBLE
        moveTip(tip, btn.x, btn.width.toFloat(), btn.y + btn.height)
        // Bringing a view forward asks for a new layout even when it is already in front, and this
        // runs after every layout.
        if (bar.getChildAt(bar.childCount - 1) !== tip) tip.bringToFront()
        DualTip.track(tip)
    }

    /**
     * Puts the tip under the button drawn at [left] with [width], its top at [top]. BarFit calls it
     * again once it has fitted the row, since the button may be drawn smaller or on a second line.
     */
    fun moveTip(bar: ViewGroup, left: Float, width: Float, top: Float) {
        val tip = bar.findViewWithTag<View>(TIP_TAG)?.takeIf { it.visibility == View.VISIBLE } ?: return
        moveTip(tip, left, width, top)
    }

    private fun moveTip(tip: View, left: Float, width: Float, top: Float) {
        val tw = tip.layoutParams.width.toFloat()
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, tip.resources.displayMetrics)
        val tx = (left + width - tw).coerceAtLeast(margin)
        // Point the bubble's arrow at the middle of the button.
        (tip.background as? BubbleDrawable)?.let { b ->
            val ax = left + width / 2f - tx
            if (b.arrowX != ax) { b.arrowX = ax; b.invalidateSelf() }
        }
        if (tip.x != tx) tip.x = tx
        if (tip.y != top) tip.y = top
    }

    /** A rounded speech bubble with a small arrow on top, at [arrowX] from its left edge. */
    class BubbleDrawable(val fill: Int, val strokeColor: Int, val radius: Float, val strokeW: Float,
                                 val arrowH: Float) : android.graphics.drawable.Drawable() {
        var arrowX = -1f
        private val fp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        private val sp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor; style = android.graphics.Paint.Style.STROKE; strokeWidth = strokeW
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun draw(c: android.graphics.Canvas) { runCatching { paint(c) } }

        private fun paint(c: android.graphics.Canvas) {
            val b = bounds
            val h = strokeW / 2
            val top = b.top + arrowH
            if (b.width() <= (radius + arrowH) * 2 || b.height() <= arrowH * 2) return
            val lo = radius + arrowH; val hi = b.width() - radius - arrowH
            val want = if (arrowX < 0) b.width() - radius * 2 else arrowX
            val ax = (if (want < lo) lo else if (want > hi) hi else want) + b.left
            val path = android.graphics.Path().apply {
                addRoundRect(android.graphics.RectF(b.left + h, top + h, b.right - h, b.bottom - h), radius, radius,
                    android.graphics.Path.Direction.CW)
                val tri = android.graphics.Path().apply {
                    moveTo(ax - arrowH, top + h + 1); lineTo(ax, b.top + h); lineTo(ax + arrowH, top + h + 1); close()
                }
                op(tri, android.graphics.Path.Op.UNION)
            }
            c.drawPath(path, fp)
            c.drawPath(path, sp)
        }
        override fun setAlpha(a: Int) { fp.alpha = a; sp.alpha = a }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { fp.colorFilter = cf; sp.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun collectBars(v: View, settingsId: Int, out: MutableList<ViewGroup>) {
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out && Res.isPlayerBar(it)) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectBars(v.getChildAt(i), settingsId, out)
    }
}
