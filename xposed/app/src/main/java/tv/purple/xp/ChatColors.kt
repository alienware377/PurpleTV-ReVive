package tv.purple.xp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Chat name colours the way the Twitch app picks them, for the chats the module draws itself.
 *
 * Pure maths with no Android classes, so the numbers can be checked on the JVM. [readable] is the
 * rule the app applies when its "readable colours" option is on: a colour that is already easy to
 * read on the background is kept; one too dark for a dark background (or too light for a light
 * one) to rescue becomes a plain grey; anything else is moved lighter or darker in CIELAB
 * lightness, ten percent at a time, until it reaches the WCAG contrast of 4.5 for normal text.
 * Moving only the lightness keeps the hue, so a green name stays green, just brighter.
 */
object ChatColors {

    /** Twitch's default name colours, for chatters who never picked one. The order matters:
     *  [forName] indexes it the way the app does, so a chatter gets the same colour as there. */
    val PALETTE: IntArray = intArrayOf(
        0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF008000.toInt(), 0xFFB22222.toInt(), 0xFFFF7F50.toInt(),
        0xFF9ACD32.toInt(), 0xFFFF4500.toInt(), 0xFF2E8B57.toInt(), 0xFFDAA520.toInt(), 0xFFD2691E.toInt(),
        0xFF5F9EA0.toInt(), 0xFF1E90FF.toInt(), 0xFFFF69B4.toInt(), 0xFF8A2BE2.toInt(), 0xFF00FF7F.toInt(),
    )

    private const val MIN_CONTRAST = 4.5
    private const val MAX_STEPS = 50

    /** The greys used when a colour is too close to the background to be moved into contrast. */
    private const val GREY_ON_DARK = 0xFF7A7A7A.toInt()
    private const val GREY_ON_NONE = 0xFFEFEFF1.toInt()
    private const val GREY_ON_LIGHT = 0xFF717171.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Luminance limits for those greys: 50 on a 0 to 255 scale, eased by a 1.1 gamma, from each end. */
    private val DARK_FLOOR = 50.0.pow(1.0 / 1.1) / 255.0
    private val LIGHT_CEIL = (255.0 - 50.0.pow(1.0 / 1.1)) / 255.0

    /** CIE XYZ of the D65 white point, scaled to 100. */
    private const val WHITE_X = 95.047
    private const val WHITE_Y = 100.0
    private const val WHITE_Z = 108.883
    private const val EPSILON = 0.008856
    private const val KAPPA = 903.3

    /** Results by (colour, background). A chat shows the same few hundred names over and over,
     *  and the stepping can take dozens of rounds of maths per name. */
    private const val CACHE_SIZE = 512
    private val cache = object : LinkedHashMap<Long, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Int>?) = size > CACHE_SIZE
    }

    /** The default colour for a chatter without one, picked from the login like the app does. */
    fun forName(login: String): Int = PALETTE[abs(login.hashCode() % PALETTE.size)]

    /** "#1E90FF" (or "1E90FF", or 8 digits with alpha) as an opaque colour int; null for an empty
     *  or unreadable value, which is what IRC sends for a chatter who never picked a colour. */
    fun parse(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 && h.length != 8) return null
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }

    /**
     * The colour a chatter's name is drawn in, as the app does it: their own colour made
     * [readable] on [bg], or the palette colour for [login] when they have none. Palette colours
     * are used as they are, like in the app.
     */
    fun nameColor(tag: String?, login: String, bg: Int): Int {
        val own = parse(tag) ?: return forName(login)
        return readable(own, bg)
    }

    /**
     * [color] adjusted to be readable on [bg]. A [bg] of 0 (no background) counts as black.
     * The background is taken as opaque; a translucent one has no single contrast to aim for.
     */
    fun readable(color: Int, bg: Int): Int {
        val noBg = bg == 0
        val back = if (noBg) BLACK else bg or BLACK
        val key = (color.toLong() shl 32) or (back.toLong() and 0xFFFFFFFFL)
        synchronized(cache) { cache[key] }?.let { return it }
        val out = compute(color, back, noBg)
        synchronized(cache) { cache[key] = out }
        return out
    }

    private fun compute(color: Int, back: Int, noBg: Boolean): Int {
        val dark = luminance(back) < 0.5
        var contrast = contrast(color, back)
        if (contrast > MIN_CONTRAST) return color
        if (dark && luminance(color) < DARK_FLOOR) return if (noBg) GREY_ON_NONE else GREY_ON_DARK
        if (!dark && luminance(color) > LIGHT_CEIL) return GREY_ON_LIGHT
        val lab = toLab(color)
        var l = lab[0]
        var out = color
        var budget = MAX_STEPS
        while (budget >= 0 && contrast < MIN_CONTRAST) {
            val step = 0.1 * l
            l = if (dark) l + step else l - step
            out = fromLab(l, lab[1], lab[2])
            contrast = contrast(out, back)
            budget--
        }
        return out
    }

    /** Relative luminance, 0 for black to 1 for white. */
    internal fun luminance(c: Int): Double {
        val r = linear(red(c)); val g = linear(green(c)); val b = linear(blue(c))
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) * 100.0 / 100.0
    }

    /** WCAG contrast ratio, 1 to 21. A translucent [fg] is first laid over [bg]. */
    internal fun contrast(fg: Int, bg: Int): Double {
        val f = if (alpha(fg) < 255) composite(fg, bg) else fg
        val a = luminance(f) + 0.05
        val b = luminance(bg) + 0.05
        return max(a, b) / min(a, b)
    }

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v < 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toLab(c: Int): DoubleArray {
        val r = linear(red(c)); val g = linear(green(c)); val b = linear(blue(c))
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) * 100.0
        val y = (0.2126 * r + 0.7152 * g + 0.0722 * b) * 100.0
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) * 100.0
        val fx = pivot(x / WHITE_X)
        val fy = pivot(y / WHITE_Y)
        val fz = pivot(z / WHITE_Z)
        return doubleArrayOf(max(0.0, 116.0 * fy - 16.0), 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun pivot(v: Double): Double = if (v > EPSILON) v.pow(1.0 / 3.0) else (KAPPA * v + 16.0) / 116.0

    private fun fromLab(l: Double, a: Double, b: Double): Int {
        val fy = (l + 16.0) / 116.0
        val fx = a / 500.0 + fy
        val fz = fy - b / 200.0
        val fx3 = fx.pow(3.0)
        val xr = if (fx3 > EPSILON) fx3 else (116.0 * fx - 16.0) / KAPPA
        val yr = if (l > EPSILON * KAPPA) fy.pow(3.0) else l / KAPPA
        val fz3 = fz.pow(3.0)
        val zr = if (fz3 > EPSILON) fz3 else (116.0 * fz - 16.0) / KAPPA
        val x = xr * WHITE_X; val y = yr * WHITE_Y; val z = zr * WHITE_Z
        val r = (3.2406 * x + -1.5372 * y + -0.4986 * z) / 100.0
        val g = (-0.9689 * x + 1.8758 * y + 0.0415 * z) / 100.0
        val bl = (0.0557 * x + -0.2040 * y + 1.0570 * z) / 100.0
        return BLACK or (channel(r) shl 16) or (channel(g) shl 8) or channel(bl)
    }

    /** Linear light back to a 0 to 255 sRGB channel. */
    private fun channel(v: Double): Int {
        val s = if (v > 0.0031308) 1.055 * v.pow(1.0 / 2.4) - 0.055 else 12.92 * v
        return Math.round(s * 255.0).toInt().coerceIn(0, 255)
    }

    private fun composite(fg: Int, bg: Int): Int {
        val fa = alpha(fg); val ba = alpha(bg)
        val a = 0xFF - (0xFF - ba) * (0xFF - fa) / 0xFF
        fun mix(f: Int, b: Int) = if (a == 0) 0 else (0xFF * f * fa + b * ba * (0xFF - fa)) / (a * 0xFF)
        return (a shl 24) or (mix(red(fg), red(bg)) shl 16) or (mix(green(fg), green(bg)) shl 8) or mix(blue(fg), blue(bg))
    }

    private fun alpha(c: Int) = c ushr 24
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
}
