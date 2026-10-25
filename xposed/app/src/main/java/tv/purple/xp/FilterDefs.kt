package tv.purple.xp

/**
 * Every video filter, its settings, and the presets used by the stylise filters.
 *
 * Settings live in the module's own store under "vf_<key>_<field>": amount ("a", -100..100 or
 * 0..100), tone range ("lo"/"hi", 0..100), plus filter-specific extras. The six original keys
 * (bright, contrast, clarity, texture, sat, vib) are unchanged, so earlier settings carry over.
 */
object FilterDefs {

    enum class Group(val title: String) {
        LIGHT("Light"), DETAIL("Detail"), COLOR("Colour"), STYLE("Style"), EFFECTS("Effects")
    }

    /**
     * @param bipolar slider runs -100..100 around a centre of 0; otherwise 0..100.
     * @param ranged offers a tone range (shadows to highlights).
     * @param colorIcon draws its icon in colour, because it changes colour.
     */
    class Filter(
        val key: String, val name: String, val group: Group, val help: String,
        val bipolar: Boolean = true, val ranged: Boolean = true, val colorIcon: Boolean = false
    )

    val FILTERS = listOf(
        // ---- Light ----
        Filter("exposure", "Exposure", Group.LIGHT,
            "Like opening or closing a camera's aperture: everything gets lighter or darker together, highlights included."),
        Filter("bright", "Brightness", Group.LIGHT,
            "Makes the picture lighter or darker while protecting the brightest parts a little. Handy for dark horror streams."),
        Filter("contrast", "Contrast", Group.LIGHT,
            "Pushes darks darker and lights lighter for more punch. Lower it to soften a harsh picture."),
        Filter("highlights", "Highlights", Group.LIGHT,
            "Brightens or tames only the bright parts, like sky, lamps and white text. Lower it to rescue blown-out areas."),
        Filter("shadows", "Shadows", Group.LIGHT,
            "Lifts or deepens only the dark parts. Raise it to see into dark corners without touching the rest."),
        Filter("gamma", "Gamma", Group.LIGHT,
            "Bends the middle tones lighter or darker while keeping pure black and pure white where they are."),
        Filter("fade", "Fade", Group.LIGHT,
            "Lifts the blacks into a soft, matte, film-like look.", bipolar = false),
        // ---- Detail ----
        Filter("clarity", "Clarity", Group.DETAIL,
            "Adds contrast between neighbouring areas so shapes and faces stand out, without changing the overall brightness. A lot of it can look crunchy."),
        Filter("texture", "Sharpen", Group.DETAIL,
            "Sharpens fine detail like hair, fur and small text. Lower it to smooth out blocky compression noise."),
        Filter("soft", "Soft focus", Group.DETAIL,
            "A dreamy blur that softens the picture. Great with a tone range on the highlights for a glow.",
            bipolar = false),
        // ---- Colour ----
        Filter("temp", "Temperature", Group.COLOR,
            "Warmer (more orange) or cooler (more blue), like a camera's white balance.", colorIcon = true),
        Filter("tint", "Tint", Group.COLOR,
            "Shifts colours towards magenta or green, to fix a green or pink cast.", colorIcon = true),
        Filter("hue", "Hue", Group.COLOR,
            "Spins every colour around the colour wheel: reds become oranges, blues become purples and so on.",
            colorIcon = true),
        Filter("sat", "Saturation", Group.COLOR,
            "Makes every colour stronger or weaker. All the way down is black and white.", colorIcon = true),
        Filter("vib", "Vibrance", Group.COLOR,
            "A gentler saturation: boosts dull colours more than ones that are already strong, so skin tones stay natural.",
            colorIcon = true),
        // ---- Style ----
        Filter("mono", "Monotone", Group.STYLE,
            "Turns the picture into shades of one colour you pick, from black through your colour to white.",
            bipolar = false, colorIcon = true),
        Filter("gmap", "Gradient map", Group.STYLE,
            "Replaces each brightness level with a colour from a gradient, like Photoshop's gradient map. Dark parts take the left colour, bright parts the right.",
            bipolar = false, colorIcon = true),
        Filter("poster", "Posterize", Group.STYLE,
            "Reduces the number of colour steps for a flat, poster or cel-shaded look.", bipolar = false),
        Filter("invert", "Invert", Group.STYLE,
            "Flips colours to their opposites, like a photo negative.", bipolar = false, colorIcon = true),
        // ---- Effects ----
        Filter("ovl", "Overlay", Group.EFFECTS,
            "Lays a texture, your own picture, or a solid colour over the video, mixed in with a blend mode, like a Photoshop layer. The slider is its opacity.",
            bipolar = false),
        Filter("grain", "Noise", Group.EFFECTS,
            "Adds film grain or digital noise. A little can hide compression blockiness.", bipolar = false),
        Filter("vignette", "Vignette", Group.EFFECTS,
            "Darkens the edges to draw your eye to the middle, or lightens them for a faded look.",
            ranged = false),
        Filter("chroma", "Colour fringe", Group.EFFECTS,
            "Splits red and blue slightly apart, like a cheap lens or a glitchy VHS tape.",
            bipolar = false, ranged = false, colorIcon = true),
        Filter("pixel", "Pixelate", Group.EFFECTS,
            "Turns the picture into big square pixels for a retro game look.", bipolar = false, ranged = false)
    )

    fun index(key: String) = FILTERS.indexOfFirst { it.key == key }

    const val RANGE_HELP =
        "Choose which tones this filter touches, from shadows on the left to highlights on the right. " +
        "For example, brighten only the shadows to see into dark scenes without washing out the rest."

    // ---- stored values ----

    private fun k(i: Int, f: String) = "vf_${FILTERS[i].key}_$f"
    fun amount(i: Int): Int {
        val v = Settings.getInt(k(i, "a"), 0)
        return if (FILTERS[i].bipolar) v.coerceIn(-100, 100) else v.coerceIn(0, 100)
    }
    fun lo(i: Int) = Settings.getInt(k(i, "lo"), 0).coerceIn(0, 100)
    fun hi(i: Int) = Settings.getInt(k(i, "hi"), 100).coerceIn(0, 100)
    fun setAmount(i: Int, v: Int) = Settings.setInt(k(i, "a"), v)
    fun setRange(i: Int, l: Int, h: Int) { Settings.setInt(k(i, "lo"), l); Settings.setInt(k(i, "hi"), h) }

    fun extraInt(key: String, f: String, def: Int) = Settings.getInt("vf_${key}_$f", def)
    fun setExtraInt(key: String, f: String, v: Int) = Settings.setInt("vf_${key}_$f", v)
    fun extraStr(key: String, f: String, def: String) = Settings.getString("vf_${key}_$f", def)
    fun setExtraStr(key: String, f: String, v: String) = Settings.setString("vf_${key}_$f", v)
    fun extraBool(key: String, f: String, def: Boolean) = Settings.get("vf_${key}_$f", def)
    fun setExtraBool(key: String, f: String, v: Boolean) = Settings.set("vf_${key}_$f", v)

    fun resetAll() {
        for (i in FILTERS.indices) { setAmount(i, 0); setRange(i, 0, 100) }
    }

    // ---- gradient maps ----

    class Gradient(val name: String, val colors: IntArray)

    val GRADIENTS = listOf(
        Gradient("Noir", intArrayOf(0xFF000000.toInt(), 0xFF3A3A3A.toInt(), 0xFFFFFFFF.toInt())),
        Gradient("Sepia", intArrayOf(0xFF1E1208.toInt(), 0xFF7A5230.toInt(), 0xFFF2E3C6.toInt())),
        Gradient("Sunset", intArrayOf(0xFF1B0B3A.toInt(), 0xFF8E2A6E.toInt(), 0xFFF06B3B.toInt(), 0xFFFFD27A.toInt())),
        Gradient("Cyberpunk", intArrayOf(0xFF0B0630.toInt(), 0xFF6A12B8.toInt(), 0xFFFF2E9A.toInt(), 0xFF35F0FF.toInt())),
        Gradient("Vaporwave", intArrayOf(0xFF241047.toInt(), 0xFFB4489E.toInt(), 0xFF5CE1E6.toInt(), 0xFFFFF0FA.toInt())),
        Gradient("Ocean", intArrayOf(0xFF02101F.toInt(), 0xFF0B4F7A.toInt(), 0xFF39B4C8.toInt(), 0xFFE6FFFB.toInt())),
        Gradient("Forest", intArrayOf(0xFF07140B.toInt(), 0xFF2F5A2A.toInt(), 0xFFA8C46B.toInt(), 0xFFF4F7DC.toInt())),
        Gradient("Infrared", intArrayOf(0xFF000000.toInt(), 0xFF7A0010.toInt(), 0xFFFF5A1F.toInt(), 0xFFFFF27A.toInt())),
        Gradient("Thermal", intArrayOf(0xFF000020.toInt(), 0xFF2B1BB8.toInt(), 0xFFC21FB0.toInt(), 0xFFFF4A1A.toInt(), 0xFFFFE15A.toInt(), 0xFFFFFFFF.toInt())),
        Gradient("Night vision", intArrayOf(0xFF000800.toInt(), 0xFF0E5A12.toInt(), 0xFF7DFF6A.toInt(), 0xFFE9FFE0.toInt())),
        Gradient("Duotone purple", intArrayOf(0xFF1A0B2E.toInt(), 0xFFFF8FD8.toInt())),
        Gradient("Golden hour", intArrayOf(0xFF2A1406.toInt(), 0xFFB5652A.toInt(), 0xFFFFC879.toInt(), 0xFFFFF4DC.toInt())),
        Gradient("Mint", intArrayOf(0xFF07211C.toInt(), 0xFF2E8C76.toInt(), 0xFFB8F5DC.toInt())),
        Gradient("Custom", intArrayOf(0xFF140A26.toInt(), 0xFFFFB3DE.toInt()))
    )

    /** The gradient currently chosen for the gradient map, with custom colours applied. */
    fun currentGradient(): IntArray = currentGradientFor(extraInt("gmap", "preset", 3))

    fun currentGradientFor(index: Int): IntArray {
        val g = GRADIENTS[index.coerceIn(0, GRADIENTS.size - 1)]
        if (g.name != "Custom") return g.colors
        return intArrayOf(extraInt("gmap", "c1", g.colors[0]), extraInt("gmap", "c2", g.colors[1]))
    }

    fun monoColor() = extraInt("mono", "color", 0xFFA970FF.toInt())

    // ---- blend modes, in Photoshop's order ----

    val BLEND_MODES = listOf(
        "Normal", "Dissolve",
        "Darken", "Multiply", "Color Burn", "Linear Burn", "Darker Color",
        "Lighten", "Screen", "Color Dodge", "Linear Dodge (Add)", "Lighter Color",
        "Overlay", "Soft Light", "Hard Light", "Vivid Light", "Linear Light", "Pin Light", "Hard Mix",
        "Difference", "Exclusion", "Subtract", "Divide",
        "Hue", "Saturation", "Color", "Luminosity"
    )

    /** Where Photoshop draws a divider line in its blend list, by index of the first of a group. */
    val BLEND_GROUP_STARTS = setOf(2, 7, 12, 19, 23)

    val BLEND_HELP = mapOf(
        "Normal" to "Just lays the layer on top.",
        "Dissolve" to "Scatters random pixels of the layer over the video, more as opacity rises.",
        "Darken" to "Keeps whichever is darker, the video or the layer.",
        "Multiply" to "Darkens like stacking two slides. White in the layer does nothing.",
        "Color Burn" to "Darkens with strong contrast and saturation.",
        "Linear Burn" to "Darkens more evenly than Color Burn.",
        "Darker Color" to "Keeps whichever colour is darker overall.",
        "Lighten" to "Keeps whichever is lighter.",
        "Screen" to "Lightens like two projectors on one screen. Black in the layer does nothing.",
        "Color Dodge" to "Brightens with strong glow and saturation.",
        "Linear Dodge (Add)" to "Adds the layer's light to the video.",
        "Lighter Color" to "Keeps whichever colour is lighter overall.",
        "Overlay" to "Adds contrast: darks get darker, lights get lighter. Mid grey does nothing.",
        "Soft Light" to "A gentler Overlay, like shining a soft light on the video.",
        "Hard Light" to "A harsher Overlay driven by the layer.",
        "Vivid Light" to "Burns or dodges strongly depending on the layer.",
        "Linear Light" to "Like Vivid Light but steadier.",
        "Pin Light" to "Swaps in the layer only where it is much lighter or darker.",
        "Hard Mix" to "Reduces everything to bold primary colours.",
        "Difference" to "Subtracts colours for a trippy, inverted look.",
        "Exclusion" to "A softer, greyer Difference.",
        "Subtract" to "Removes the layer's colour from the video.",
        "Divide" to "Brightens by dividing the video by the layer.",
        "Hue" to "Takes only the layer's hue.",
        "Saturation" to "Takes only the layer's colour strength.",
        "Color" to "Takes the layer's hue and colour strength, keeping the video's brightness. Great for tinting.",
        "Luminosity" to "Takes only the layer's brightness."
    )
}
