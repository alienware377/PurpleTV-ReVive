package tv.purple.xp

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Picture filters for the live and VOD player: brightness, contrast, clarity, texture,
 * saturation and vibrance, each limited to a band of tones (shadows to highlights).
 *
 * The maths is the band-limited filter stack from the Lume Pad 3D Everywhere app, ported from
 * GLSL to AGSL. Twitch draws the video into a plain framework TextureView, so the filters are a
 * RenderEffect on that view: the GPU applies them to every frame as it is composited, nothing
 * touches the decoder, and nothing obfuscated is involved.
 *
 * Android 13+ runs the full shader. Android 12 and older cannot run a custom shader on a view, so
 * there brightness, contrast and saturation fall back to a colour matrix on the TextureView's
 * layer paint (whole picture only, no tone band), and the other three are unavailable.
 */
object VideoFilters {

    const val KEY_BUTTON = "show_filters_button"
    const val KEY_ON = "vf_on"

    class Filter(val key: String, val name: String, val help: String, val needsShader: Boolean)

    val FILTERS = listOf(
        Filter("bright", "Brightness",
            "Makes the picture lighter or darker, like a camera's exposure. Handy for dark horror streams.",
            false),
        Filter("contrast", "Contrast",
            "Pushes darks darker and lights lighter for more punch. Lower it to soften a harsh picture.",
            false),
        Filter("clarity", "Clarity",
            "Adds contrast between neighbouring areas so shapes and faces stand out, without changing the overall brightness. A lot of it can look crunchy.",
            true),
        Filter("texture", "Texture",
            "Sharpens fine detail like hair, fur and small text. Lower it to smooth out blocky compression noise.",
            true),
        Filter("sat", "Saturation",
            "Makes every colour stronger or weaker. All the way down is black and white.",
            false),
        Filter("vib", "Vibrance",
            "A gentler saturation: boosts dull colours more than ones that are already strong, so skin tones stay natural.",
            true)
    )

    const val RANGE_HELP =
        "Choose which tones this filter touches, from shadows on the left to highlights on the right. " +
        "For example, brighten only the shadows to see into dark scenes without washing out the rest."

    fun shaderSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun amount(i: Int) = Settings.getInt("vf_${FILTERS[i].key}_a", 0).coerceIn(-100, 100)
    fun lo(i: Int) = Settings.getInt("vf_${FILTERS[i].key}_lo", 0).coerceIn(0, 100)
    fun hi(i: Int) = Settings.getInt("vf_${FILTERS[i].key}_hi", 100).coerceIn(0, 100)
    fun setAmount(i: Int, v: Int) = Settings.setInt("vf_${FILTERS[i].key}_a", v)
    fun setRange(i: Int, l: Int, h: Int) {
        Settings.setInt("vf_${FILTERS[i].key}_lo", l); Settings.setInt("vf_${FILTERS[i].key}_hi", h)
    }

    fun usable(i: Int) = !FILTERS[i].needsShader || shaderSupported()

    /** True when the master switch is on and at least one usable filter is moved off zero. */
    fun anyActive(): Boolean =
        Settings.get(KEY_ON, true) && FILTERS.indices.any { usable(it) && amount(it) != 0 }

    fun resetAll() {
        for (i in FILTERS.indices) { setAmount(i, 0); setRange(i, 0, 100) }
    }

    // ---- applying ----

    /** What each video view currently has applied, so unchanged views are left alone. */
    private val applied = WeakHashMap<TextureView, String>()

    /** Called from every ViewHider pass, and right away whenever a slider moves. */
    fun attach(root: View) {
        val ctx = root.context
        val id = ctx.resources.getIdentifier("playback_view_container", "id", ctx.packageName)
        if (id != 0) {
            val sig = signature()
            val views = ArrayList<TextureView>()
            collectVideos(root, id, views)
            for (tv in views) {
                if (applied[tv] == sig) continue
                applied[tv] = sig
                runCatching { apply(tv) }.onFailure { log("filters apply failed: $it") }
            }
        }
        runCatching { attachButton(root) }.onFailure { log("filters button: $it") }
    }

    /** Re-run [attach] on the screen in front, after a change made in the panel. */
    fun refresh(anyView: View) {
        anyView.rootView?.let { r -> runCatching { attach(r) } }
    }

    private fun signature(): String {
        if (!anyActive()) return "off"
        return FILTERS.indices.joinToString(",") { "${amount(it)}:${lo(it)}:${hi(it)}" }
    }

    private fun collectVideos(v: View, containerId: Int, out: MutableList<TextureView>) {
        if (v.id == containerId) { textureViewsIn(v, out); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectVideos(v.getChildAt(i), containerId, out)
    }

    private fun textureViewsIn(v: View, out: MutableList<TextureView>) {
        if (v is TextureView) { out.add(v); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) textureViewsIn(v.getChildAt(i), out)
    }

    private fun apply(tv: TextureView) {
        val on = anyActive()
        if (shaderSupported()) {
            if (!on) { tv.setRenderEffect(null); return }
            val s = RuntimeShader(SHADER)
            for (i in FILTERS.indices) {
                s.setFloatUniform("f$i", amount(i) / 100f, lo(i) / 100f, hi(i) / 100f)
            }
            val h = tv.height.takeIf { it > 0 } ?: 1080
            s.setFloatUniform("blurR", (h * 0.012f).coerceIn(4f, 40f))
            s.setFloatUniform("px", 1.25f)
            tv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"))
        } else {
            if (!on) { tv.setLayerPaint(null); return }
            tv.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(fallbackMatrix()) })
        }
    }

    /** Brightness, contrast and saturation as one colour matrix, for Android 12 and older. */
    private fun fallbackMatrix(): ColorMatrix {
        val m = ColorMatrix()
        val b = Math.pow(2.0, amount(0) / 100.0 * 1.5).toFloat()
        m.postConcat(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        val c = 1f + amount(1) / 100f * 1.5f
        val t = 127.5f * (1f - c)
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        m.postConcat(ColorMatrix().apply { setSaturation((1f + amount(4) / 100f).coerceAtLeast(0f)) })
        return m
    }

    // ---- the player button ----

    private const val TAG = "ptv_filters_button"
    private val ROW = arrayOf("create_clip_text_button", "create_clip_button_compose_view", "switch_to_vertical_view",
        "cast_button", "media_route_button",
        "share_button", "settings_button", "audio_and_subtitles")
    private var added = false
    private val seenBars = java.util.Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    private fun attachButton(root: View) {
        val on = Settings.get(KEY_BUTTON, true)
        if (!on && !added) return
        val ctx = root.context
        fun ids(n: String) = ctx.resources.getIdentifier(n, "id", ctx.packageName)
        val settingsId = ids("settings_button")
        if (settingsId == 0) return
        val bars = ArrayList<ViewGroup>()
        collectBars(root, settingsId, bars)
        for (bar in bars) {
            var btn = bar.findViewWithTag<ImageView>(TAG)
            if (!on) { if (btn != null) bar.removeView(btn); continue }
            val row = ROW.mapNotNull { n -> ids(n).takeIf { it != 0 }?.let { bar.findViewById<View>(it) } }
                .filter { it.parent === bar && it.visibility == View.VISIBLE && it.width > 0 }
                .toMutableList()
            // Sit left of the sleep timer when that is showing too.
            bar.findViewWithTag<View>("ptv_sleep_timer")
                ?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { row.add(it) }
            val ref = row.firstOrNull { it.height > 0 }
            if (ref == null) {
                btn?.visibility = View.GONE
                if (seenBars.add(bar)) log("filters: bar ${bar.javaClass.simpleName} has no visible controls yet")
                continue
            }
            if (btn == null) {
                btn = ImageView(bar.context).apply {
                    tag = TAG
                    setImageDrawable(FilterIcons.Tune())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(ref.paddingLeft, ref.paddingTop, ref.paddingRight, ref.paddingBottom)
                    contentDescription = "Video filters"
                    setOnClickListener { FiltersPanel.show(it) }
                }
                bar.addView(btn, ViewGroup.LayoutParams(ref.height, ref.height))
                added = true
            }
            val x = row.minOf { it.x } - ref.height
            if (btn.x != x) btn.x = x
            if (btn.y != ref.y) btn.y = ref.y
            if (btn.visibility != View.VISIBLE) btn.visibility = View.VISIBLE
            (btn.drawable as? FilterIcons.Tune)?.setActive(anyActive())
        }
    }

    private fun collectBars(v: View, settingsId: Int, out: MutableList<ViewGroup>) {
        if (v.id == settingsId) { (v.parent as? ViewGroup)?.let { if (it !in out) out.add(it) }; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectBars(v.getChildAt(i), settingsId, out)
    }

    /**
     * AGSL port of the Lume Pad 3D Everywhere filter stack. Each fN is (amount -1..1, band low,
     * band high); a filter at amount 0 is skipped. Clarity compares each pixel with a ring-sampled
     * local average; texture is a small unsharp mask.
     */
    private const val SHADER = """
uniform shader content;
uniform float blurR;
uniform float px;
uniform float3 f0;
uniform float3 f1;
uniform float3 f2;
uniform float3 f3;
uniform float3 f4;
uniform float3 f5;
const float3 LW = float3(0.299, 0.587, 0.114);

float bandw(float3 f, float L) {
    return smoothstep(f.y - 0.15, f.y, L) * (1.0 - smoothstep(f.z, f.z + 0.15, L));
}
float lum(float2 p) { return dot(float3(content.eval(p).rgb), LW); }

half4 main(float2 p) {
    half4 src = content.eval(p);
    float3 own = float3(src.rgb);
    float L0 = dot(own, LW);
    float a = f0.x;
    if (abs(a) > 0.003) own = clamp(own * pow(2.0, a * 1.5 * bandw(f0, L0)), 0.0, 1.0);
    a = f1.x;
    if (abs(a) > 0.003) own = clamp(mix(own, (own - 0.5) * (1.0 + a * 1.5) + 0.5, bandw(f1, L0)), 0.0, 1.0);
    a = f2.x;
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        float Lb = L0;
        for (int k = 0; k < 8; k++) {
            float ang = float(k) * 0.7853981;
            float2 d = float2(cos(ang), sin(ang));
            Lb += lum(p + d * blurR) + lum(p + d * blurR * 0.5);
        }
        Lb = Lb / 17.0;
        own = clamp(own * (1.0 + a * 2.5 * (L - Lb) * bandw(f2, L0) / max(L, 0.05)), 0.0, 1.0);
    }
    a = f3.x;
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        float ln = lum(p + float2(px, 0.0)) + lum(p - float2(px, 0.0))
                 + lum(p + float2(0.0, px)) + lum(p - float2(0.0, px));
        float lap = L0 - ln * 0.25;
        own = clamp(own * (1.0 + a * 3.0 * lap * bandw(f3, L0) / max(L, 0.05)), 0.0, 1.0);
    }
    a = f4.x;
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        own = clamp(mix(float3(L), own, 1.0 + a * bandw(f4, L0)), 0.0, 1.0);
    }
    a = f5.x;
    if (abs(a) > 0.003) {
        float L = dot(own, LW);
        float cs = max(own.r, max(own.g, own.b)) - min(own.r, min(own.g, own.b));
        own = clamp(mix(float3(L), own, 1.0 + a * (1.0 - cs) * bandw(f5, L0)), 0.0, 1.0);
    }
    return half4(half3(own), src.a);
}
"""
}
