package tv.purple.xp

/**
 * The video filter maths, written once and turned into either AGSL (RuntimeShader, Android 13+)
 * or GLSL ES (our own GL pass, older Android).
 *
 * The body works in pixel coordinates `p` and only reaches the outside world through four
 * functions each dialect supplies: SRC(p) the video colour, OVL(p) the overlay layer colour,
 * GRAD(t) the gradient map colour at 0..1, and MONO(t) the monotone ramp at 0..1.
 *
 * Every filter is skipped when its amount is 0, so an unused filter costs one branch.
 */
object FilterShader {

    /** Uniforms each filter owns: amount -1..1 (or 0..1), tone range low, tone range high. */
    val FILTER_UNIFORMS = FilterDefs.FILTERS.map { "f_" + it.key }

    /** Shared helpers and main body. Uses vec types, which AGSL accepts as aliases. */
    private val BODY = """
const vec3 LW = vec3(0.299, 0.587, 0.114);

float bandw(vec3 f, float L) {
    return smoothstep(f.y - 0.15, f.y, L) * (1.0 - smoothstep(f.z, f.z + 0.15, L));
}
float lum3(vec3 c) { return dot(c, LW); }
float hash(vec2 q) { return fract(sin(dot(q, vec2(12.9898, 78.233))) * 43758.5453); }

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// Photoshop's non-separable blend helpers.
vec3 clipColor(vec3 c) {
    float l = lum3(c);
    float n = min(c.r, min(c.g, c.b));
    float x = max(c.r, max(c.g, c.b));
    if (n < 0.0) c = l + (c - l) * l / max(l - n, 1.0e-5);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1.0e-5);
    return c;
}
vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum3(c))); }
float sat3(vec3 c) { return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)); }
vec3 setSat(vec3 c, float s) {
    float mn = min(c.r, min(c.g, c.b));
    float mx = max(c.r, max(c.g, c.b));
    float d = mx - mn;
    if (d <= 1.0e-5) return vec3(0.0);
    return (c - mn) * s / d;
}

float burn(float b, float s) { return s <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s); }
float dodge(float b, float s) { return s >= 1.0 ? 1.0 : min(1.0, b / (1.0 - s)); }
float softl(float b, float s) {
    float d = b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return s <= 0.5 ? b - (1.0 - 2.0 * s) * b * (1.0 - b) : b + (2.0 * s - 1.0) * (d - b);
}
float hardl(float b, float s) { return s <= 0.5 ? b * 2.0 * s : 1.0 - (1.0 - b) * (1.0 - (2.0 * s - 1.0)); }
float vivid(float b, float s) { return s <= 0.5 ? burn(b, 2.0 * s) : dodge(b, 2.0 * s - 1.0); }
float pinl(float b, float s) { return s <= 0.5 ? min(b, 2.0 * s) : max(b, 2.0 * s - 1.0); }

vec3 blendMode(vec3 b, vec3 s, float m) {
    if (m < 1.5) return s;                                   // 0 Normal, 1 Dissolve (handled by caller)
    if (m < 2.5) return min(b, s);                           // 2 Darken
    if (m < 3.5) return b * s;                               // 3 Multiply
    if (m < 4.5) return vec3(burn(b.r, s.r), burn(b.g, s.g), burn(b.b, s.b));   // 4 Color Burn
    if (m < 5.5) return max(b + s - 1.0, 0.0);               // 5 Linear Burn
    if (m < 6.5) return lum3(s) < lum3(b) ? s : b;           // 6 Darker Color
    if (m < 7.5) return max(b, s);                           // 7 Lighten
    if (m < 8.5) return 1.0 - (1.0 - b) * (1.0 - s);         // 8 Screen
    if (m < 9.5) return vec3(dodge(b.r, s.r), dodge(b.g, s.g), dodge(b.b, s.b)); // 9 Color Dodge
    if (m < 10.5) return min(b + s, 1.0);                    // 10 Linear Dodge (Add)
    if (m < 11.5) return lum3(s) > lum3(b) ? s : b;          // 11 Lighter Color
    if (m < 12.5) return vec3(hardl(s.r, b.r), hardl(s.g, b.g), hardl(s.b, b.b)); // 12 Overlay
    if (m < 13.5) return vec3(softl(b.r, s.r), softl(b.g, s.g), softl(b.b, s.b)); // 13 Soft Light
    if (m < 14.5) return vec3(hardl(b.r, s.r), hardl(b.g, s.g), hardl(b.b, s.b)); // 14 Hard Light
    if (m < 15.5) return vec3(vivid(b.r, s.r), vivid(b.g, s.g), vivid(b.b, s.b)); // 15 Vivid Light
    if (m < 16.5) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0); // 16 Linear Light
    if (m < 17.5) return vec3(pinl(b.r, s.r), pinl(b.g, s.g), pinl(b.b, s.b));    // 17 Pin Light
    if (m < 18.5) return step(1.0, b + s);                   // 18 Hard Mix
    if (m < 19.5) return abs(b - s);                         // 19 Difference
    if (m < 20.5) return b + s - 2.0 * b * s;                // 20 Exclusion
    if (m < 21.5) return max(b - s, 0.0);                    // 21 Subtract
    if (m < 22.5) return min(b / max(s, vec3(1.0e-4)), 1.0); // 22 Divide
    if (m < 23.5) return setLum(setSat(s, sat3(b)), lum3(b)); // 23 Hue
    if (m < 24.5) return setLum(setSat(b, sat3(s)), lum3(b)); // 24 Saturation
    if (m < 25.5) return setLum(s, lum3(b));                 // 25 Color
    return setLum(b, lum3(s));                               // 26 Luminosity
}

float lumAt(vec2 q) { return lum3(SRC(q)); }

vec4 filterMain(vec2 p) {
    // ---- where to sample: pixelate, then colour fringe ----
    vec2 q = p;
    if (f_pixel.x > 0.003) {
        float bs = 2.0 + f_pixel.x * 46.0;
        q = (floor(p / bs) + 0.5) * bs;
    }
    vec3 own = SRC(q);
    if (f_chroma.x > 0.003) {
        vec2 off = vec2(f_chroma.x * 14.0, 0.0);
        own = vec3(SRC(q + off).r, own.g, SRC(q - off).b);
    }
    float L0 = lum3(own);
    float a = 0.0;
    float w = 0.0;

    // ---- light ----
    a = f_exposure.x;
    if (abs(a) > 0.003) own = clamp(own * pow(2.0, a * 2.0 * bandw(f_exposure, L0)), 0.0, 1.0);
    a = f_bright.x;
    if (abs(a) > 0.003) {
        // Lift or lower with a gentle roll-off near white, so highlights do not clip as fast.
        w = bandw(f_bright, L0);
        vec3 up = 1.0 - pow(1.0 - own, vec3(1.0 + a * 1.5));
        vec3 dn = pow(own, vec3(1.0 - a * 1.5));
        own = mix(own, a > 0.0 ? up : dn, w);
    }
    a = f_contrast.x;
    if (abs(a) > 0.003) own = clamp(mix(own, (own - 0.5) * (1.0 + a * 1.5) + 0.5, bandw(f_contrast, L0)), 0.0, 1.0);
    a = f_highlights.x;
    if (abs(a) > 0.003) {
        float L = lum3(own);
        w = smoothstep(0.45, 1.0, L) * bandw(f_highlights, L0);
        own = clamp(own * (1.0 + a * 0.6 * w), 0.0, 1.0);
    }
    a = f_shadows.x;
    if (abs(a) > 0.003) {
        float L = lum3(own);
        w = (1.0 - smoothstep(0.0, 0.55, L)) * bandw(f_shadows, L0);
        own = clamp(own + a * 0.35 * w * (1.0 - own), 0.0, 1.0);
        if (a < 0.0) own = clamp(own * (1.0 + a * 0.6 * w), 0.0, 1.0);
    }
    a = f_gamma.x;
    if (abs(a) > 0.003) own = mix(own, pow(own, vec3(pow(2.0, -a * 1.5))), bandw(f_gamma, L0));
    a = f_fade.x;
    if (a > 0.003) {
        w = bandw(f_fade, L0);
        own = mix(own, own * (1.0 - a * 0.35) + a * 0.22, w);
    }

    // ---- detail ----
    a = f_clarity.x;
    if (abs(a) > 0.003) {
        float L = lum3(own);
        float Lb = L0;
        for (int k = 0; k < 8; k++) {
            float ang = float(k) * 0.7853981;
            vec2 d = vec2(cos(ang), sin(ang));
            Lb += lumAt(q + d * uBlur) + lumAt(q + d * uBlur * 0.5);
        }
        Lb = Lb / 17.0;
        own = clamp(own * (1.0 + a * 2.5 * (L - Lb) * bandw(f_clarity, L0) / max(L, 0.05)), 0.0, 1.0);
    }
    a = f_texture.x;
    if (abs(a) > 0.003) {
        float L = lum3(own);
        float ln = lumAt(q + vec2(1.25, 0.0)) + lumAt(q - vec2(1.25, 0.0))
                 + lumAt(q + vec2(0.0, 1.25)) + lumAt(q - vec2(0.0, 1.25));
        float lap = L0 - ln * 0.25;
        own = clamp(own * (1.0 + a * 3.0 * lap * bandw(f_texture, L0) / max(L, 0.05)), 0.0, 1.0);
    }
    a = f_soft.x;
    if (a > 0.003) {
        vec3 acc = own;
        float r = uBlur * (0.4 + a);
        for (int k = 0; k < 8; k++) {
            float ang = float(k) * 0.7853981 + 0.39;
            vec2 d = vec2(cos(ang), sin(ang));
            acc += SRC(q + d * r) + SRC(q + d * r * 0.5);
        }
        acc = acc / 17.0;
        own = mix(own, max(own, acc) * 0.5 + acc * 0.5, a * bandw(f_soft, L0));
    }

    // ---- colour ----
    a = f_temp.x;
    if (abs(a) > 0.003) {
        w = bandw(f_temp, L0);
        own = clamp(own * mix(vec3(1.0), vec3(1.0 + a * 0.25, 1.0 + a * 0.03, 1.0 - a * 0.25), w), 0.0, 1.0);
    }
    a = f_tint.x;
    if (abs(a) > 0.003) {
        w = bandw(f_tint, L0);
        own = clamp(own * mix(vec3(1.0), vec3(1.0 + a * 0.12, 1.0 - a * 0.2, 1.0 + a * 0.12), w), 0.0, 1.0);
    }
    a = f_hue.x;
    if (abs(a) > 0.003) {
        vec3 h = rgb2hsv(own);
        h.x = fract(h.x + a * 0.5);
        own = mix(own, hsv2rgb(h), bandw(f_hue, L0));
    }
    a = f_sat.x;
    if (abs(a) > 0.003) {
        float L = lum3(own);
        own = clamp(mix(vec3(L), own, 1.0 + a * bandw(f_sat, L0)), 0.0, 1.0);
    }
    a = f_vib.x;
    if (abs(a) > 0.003) {
        float L = lum3(own);
        float cs = sat3(own);
        own = clamp(mix(vec3(L), own, 1.0 + a * (1.0 - cs) * bandw(f_vib, L0)), 0.0, 1.0);
    }

    // ---- style ----
    a = f_mono.x;
    if (a > 0.003) own = mix(own, MONO(lum3(own)), a * bandw(f_mono, L0));
    a = f_gmap.x;
    if (a > 0.003) own = mix(own, GRAD(lum3(own)), a * bandw(f_gmap, L0));
    a = f_poster.x;
    if (a > 0.003) {
        float lv = floor(mix(24.0, 2.0, a));
        own = mix(own, floor(own * lv + 0.5) / lv, bandw(f_poster, L0));
    }
    a = f_invert.x;
    if (a > 0.003) own = mix(own, 1.0 - own, a * bandw(f_invert, L0));

    // ---- effects ----
    a = f_ovl.x;
    if (a > 0.003) {
        vec4 s = OVL(p);
        float op = a * s.a * bandw(f_ovl, L0);
        if (uBlend < 0.5 || uBlend > 1.5) {
            own = mix(own, clamp(blendMode(own, s.rgb, uBlend), 0.0, 1.0), op);
        } else {
            own = hash(floor(p) + uSeed) < op ? s.rgb : own;      // Dissolve
        }
    }
    a = f_grain.x;
    if (a > 0.003) {
        vec2 g = floor(p / uGrainSize) + uSeed;
        float n = hash(g) - 0.5;
        vec3 nc = uGrainColor > 0.5 ? vec3(hash(g + 17.0), hash(g + 31.0), hash(g + 47.0)) - 0.5 : vec3(n);
        // Film grain shows most in the midtones.
        float mid = 1.0 - abs(lum3(own) * 2.0 - 1.0) * 0.6;
        own = clamp(own + nc * a * 0.35 * mid * bandw(f_grain, L0), 0.0, 1.0);
    }
    a = f_vignette.x;
    if (abs(a) > 0.003) {
        vec2 c = (p / uSize - 0.5) * vec2(uSize.x / uSize.y, 1.0);
        float r = length(c) / length(vec2(uSize.x / uSize.y, 1.0) * 0.5);
        float v = smoothstep(uVigSize, uVigSize + 0.55, r);
        own = a > 0.0 ? own * (1.0 - a * v) : mix(own, vec3(1.0), -a * v * 0.85);
    }
    return vec4(own, 1.0);
}
"""

    private val COMMON_UNIFORMS = listOf("uSize", "uBlur", "uBlend", "uSeed", "uGrainSize",
        "uGrainColor", "uVigSize")

    /** AGSL for RuntimeShader. Children: content (video), ovl, grad, mono. */
    val AGSL: String by lazy {
        buildString {
            append("uniform shader content;\nuniform shader ovl;\nuniform shader grad;\nuniform shader mono;\n")
            append("uniform vec2 uSize;\nuniform float uBlur;\nuniform float uBlend;\nuniform float uSeed;\n")
            append("uniform float uGrainSize;\nuniform float uGrainColor;\nuniform float uVigSize;\n")
            for (u in FILTER_UNIFORMS) append("uniform vec3 ").append(u).append(";\n")
            append("vec3 SRC(vec2 p) { return content.eval(p).rgb; }\n")
            append("vec4 OVL(vec2 p) { return ovl.eval(p); }\n")
            append("vec3 GRAD(float t) { return grad.eval(vec2(clamp(t, 0.0, 1.0) * 255.0 + 0.5, 0.5)).rgb; }\n")
            append("vec3 MONO(float t) { return mono.eval(vec2(clamp(t, 0.0, 1.0) * 255.0 + 0.5, 0.5)).rgb; }\n")
            append(BODY)
            append("half4 main(vec2 p) { half4 src = content.eval(p); vec4 o = filterMain(p); return half4(half3(o.rgb), src.a); }\n")
        }
    }

    /**
     * GLSL ES 3.0 fragment shader for our own GL pass. The video comes in as an external OES
     * texture with its SurfaceTexture transform; overlay, gradient and monotone ramps are plain 2D
     * textures. vUv is 0..1 over the output, uSize the output size in pixels.
     */
    val GLSL: String by lazy {
        buildString {
            append("#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision highp float;\n")
            append("in vec2 vUv;\nout vec4 fragColor;\n")
            append("uniform samplerExternalOES uVideo;\nuniform mat4 uTexMatrix;\n")
            append("uniform sampler2D uOvl;\nuniform vec2 uOvlScale;\nuniform vec2 uOvlOff;\nuniform sampler2D uGrad;\nuniform sampler2D uMono;\n")
            append("uniform vec2 uSize;\nuniform float uBlur;\nuniform float uBlend;\nuniform float uSeed;\n")
            append("uniform float uGrainSize;\nuniform float uGrainColor;\nuniform float uVigSize;\n")
            for (u in FILTER_UNIFORMS) append("uniform vec3 ").append(u).append(";\n")
            append("vec3 SRC(vec2 p) { vec2 uv = clamp(p / uSize, 0.0, 1.0); uv.y = 1.0 - uv.y; ")
            append("return texture(uVideo, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb; }\n")
            append("vec4 OVL(vec2 p) { return texture(uOvl, p * uOvlScale + uOvlOff); }\n")
            append("vec3 GRAD(float t) { return texture(uGrad, vec2(clamp(t, 0.0, 1.0), 0.5)).rgb; }\n")
            append("vec3 MONO(float t) { return texture(uMono, vec2(clamp(t, 0.0, 1.0), 0.5)).rgb; }\n")
            append(BODY)
            append("void main() { vec2 p = vec2(vUv.x, 1.0 - vUv.y) * uSize; fragColor = filterMain(p); }\n")
        }
    }

    val ALL_UNIFORMS: List<String> get() = COMMON_UNIFORMS + FILTER_UNIFORMS
}
