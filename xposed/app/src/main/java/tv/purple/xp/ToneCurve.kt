package tv.purple.xp

/**
 * Tone curves for the Curves filter: one curve over all three colours ("m") and one per colour
 * ("r", "g", "b"). Each is stored as "x,y;x,y;..." with x and y from 0 to 255, and drawn through
 * its points with a monotone cubic, so it never overshoots between two points.
 */
object ToneCurve {

    val CHANNELS = listOf("m", "r", "g", "b")
    const val LINEAR = "0,0;255,255"

    class Point(var x: Float, var y: Float)

    fun parse(s: String): MutableList<Point> {
        val pts = s.split(';').mapNotNull { p ->
            val xy = p.split(',')
            val x = xy.getOrNull(0)?.trim()?.toFloatOrNull() ?: return@mapNotNull null
            val y = xy.getOrNull(1)?.trim()?.toFloatOrNull() ?: return@mapNotNull null
            Point(x.coerceIn(0f, 255f), y.coerceIn(0f, 255f))
        }.sortedBy { it.x }.toMutableList()
        // At least the two ends, at 0 and 255.
        if (pts.isEmpty() || pts.first().x > 0f) pts.add(0, Point(0f, pts.firstOrNull()?.y ?: 0f))
        if (pts.last().x < 255f) pts.add(Point(255f, if (pts.size > 1) pts.last().y else 255f))
        return pts
    }

    fun format(pts: List<Point>): String =
        pts.joinToString(";") { "${Math.round(it.x)},${Math.round(it.y)}" }

    fun stored(ch: String): String = FilterDefs.extraStr("curves", ch, LINEAR)
    fun store(ch: String, pts: List<Point>) = FilterDefs.setExtraStr("curves", ch, format(pts))

    /** The curve through [pts] at 256 places, 0..255 (Fritsch and Carlson's monotone cubic). */
    fun table(pts: List<Point>): FloatArray {
        val out = FloatArray(256)
        val n = pts.size
        if (n < 2) { for (i in 0..255) out[i] = i.toFloat(); return out }
        val d = FloatArray(n - 1) { k -> (pts[k + 1].y - pts[k].y) / (pts[k + 1].x - pts[k].x).coerceAtLeast(1e-3f) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
        for (k in 0 until n - 1) {
            if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f; continue }
            val a = m[k] / d[k]; val b = m[k + 1] / d[k]
            val h = a * a + b * b
            if (h > 9f) { val t = 3f / Math.sqrt(h.toDouble()).toFloat(); m[k] = t * a * d[k]; m[k + 1] = t * b * d[k] }
        }
        var k = 0
        for (i in 0..255) {
            val x = i.toFloat()
            while (k < n - 2 && x > pts[k + 1].x) k++
            val x0 = pts[k].x; val x1 = pts[k + 1].x
            out[i] = when {
                x <= x0 -> pts[k].y
                x >= x1 -> pts[k + 1].y
                else -> {
                    val hh = x1 - x0; val t = (x - x0) / hh
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * pts[k].y + (t3 - 2 * t2 + t) * hh * m[k] +
                        (-2 * t3 + 3 * t2) * pts[k + 1].y + (t3 - t2) * hh * m[k + 1]
                }
            }.coerceIn(0f, 255f)
        }
        return out
    }

    /**
     * The whole filter as 256 colours for the shader: for each input level, the red, green and
     * blue outputs, each through the all-colours curve and then its own.
     */
    fun lut(): IntArray {
        val m = table(parse(stored("m")))
        val r = table(parse(stored("r")))
        val g = table(parse(stored("g")))
        val b = table(parse(stored("b")))
        fun at(t: FloatArray, v: Float): Int {
            val i = v.toInt().coerceIn(0, 255); val j = (i + 1).coerceAtMost(255); val f = v - i
            return Math.round(t[i] + (t[j] - t[i]) * f).coerceIn(0, 255)
        }
        return IntArray(256) { i ->
            val v = m[i]
            android.graphics.Color.rgb(at(r, v), at(g, v), at(b, v))
        }
    }

    /** True when every curve is the straight line, so the filter would do nothing. */
    fun allLinear(): Boolean = CHANNELS.all { table(parse(stored(it))).withIndex().all { (i, v) -> Math.abs(v - i) < 0.5f } }

    /** Ready-made looks: a name and the four curves (all colours, red, green, blue). */
    class Preset(val name: String, val m: String = LINEAR, val r: String = LINEAR, val g: String = LINEAR, val b: String = LINEAR)

    val PRESETS = listOf(
        Preset("Straight"),
        Preset("Soft contrast", "0,0;64,56;192,200;255,255"),
        Preset("Strong contrast", "0,0;64,40;192,216;255,255"),
        Preset("Matte", "0,36;64,72;192,198;255,238"),
        Preset("Faded film", "0,28;70,76;190,196;255,242", b = "0,10;255,248"),
        Preset("Cool film", "0,8;64,60;192,200;255,250", r = "0,0;128,118;255,248", b = "0,16;128,138;255,255"),
        Preset("Warm film", "0,8;64,60;192,200;255,250", r = "0,10;128,140;255,255", b = "0,0;128,114;255,236"),
        Preset("Cross process", "0,0;255,255", r = "0,0;64,48;192,212;255,255", g = "0,0;64,56;192,206;255,255",
            b = "0,36;128,120;255,212"),
        Preset("Teal and orange", "0,0;64,58;192,204;255,255", r = "0,0;80,70;180,196;255,255",
            b = "0,18;80,96;180,170;255,240"),
        Preset("Night", "0,0;96,60;200,170;255,230", r = "0,0;255,226", b = "0,22;255,255")
    )

    fun apply(p: Preset) {
        FilterDefs.setExtraStr("curves", "m", p.m); FilterDefs.setExtraStr("curves", "r", p.r)
        FilterDefs.setExtraStr("curves", "g", p.g); FilterDefs.setExtraStr("curves", "b", p.b)
    }
}
