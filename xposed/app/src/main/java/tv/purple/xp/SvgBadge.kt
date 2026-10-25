package tv.purple.xp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a simple SVG to a bitmap, for BTTV's badges, which are only published as SVG and which
 * Android's image decoders cannot read. Only what flat badge art uses is supported: <path>,
 * <circle> and <rect> with a solid fill, inside a viewBox. Anything else is skipped.
 */
object SvgBadge {

    fun isSvg(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).trimStart()
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))
    }

    /** Renders [bytes] to a [size]x[size] bitmap (keeping the aspect ratio), or null. */
    fun render(bytes: ByteArray, size: Int): Bitmap? = runCatching {
        val src = String(bytes, Charsets.UTF_8)
        val svgTag = Regex("<svg\\b[^>]*>").find(src)?.value ?: return null
        val vb = attr(svgTag, "viewBox")?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
        val box = if (vb != null && vb.size == 4) RectF(vb[0], vb[1], vb[0] + vb[2], vb[1] + vb[3])
            else RectF(0f, 0f, num(attr(svgTag, "width")) ?: 18f, num(attr(svgTag, "height")) ?: 18f)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val m = Matrix().apply { setRectToRect(box, RectF(0f, 0f, size.toFloat(), size.toFloat()), Matrix.ScaleToFit.CENTER) }
        c.concat(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (tag in Regex("<(path|circle|rect)\\b[^>]*>").findAll(src)) {
            val t = tag.value
            val fill = attr(t, "fill") ?: styleFill(t) ?: "black"
            if (fill == "none") continue
            paint.color = color(fill) ?: continue
            attr(t, "opacity")?.toFloatOrNull()?.let { paint.alpha = (it * 255).toInt().coerceIn(0, 255) }
            val p = when (tag.groupValues[1]) {
                "path" -> parsePath(attr(t, "d") ?: continue)
                "circle" -> Path().apply { addCircle(num(attr(t, "cx")) ?: 0f, num(attr(t, "cy")) ?: 0f, num(attr(t, "r")) ?: 0f, Path.Direction.CW) }
                else -> Path().apply {
                    val x = num(attr(t, "x")) ?: 0f; val y = num(attr(t, "y")) ?: 0f
                    addRect(x, y, x + (num(attr(t, "width")) ?: 0f), y + (num(attr(t, "height")) ?: 0f), Path.Direction.CW)
                }
            }
            if (attr(t, "fill-rule") == "evenodd") p.fillType = Path.FillType.EVEN_ODD
            c.drawPath(p, paint)
        }
        bmp
    }.getOrNull()

    private fun attr(tag: String, name: String): String? =
        Regex("\\s$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun styleFill(tag: String): String? =
        attr(tag, "style")?.let { Regex("fill\\s*:\\s*([^;]+)").find(it)?.groupValues?.get(1)?.trim() }

    private fun num(s: String?): Float? = s?.trim()?.removeSuffix("px")?.toFloatOrNull()

    private fun color(s: String): Int? {
        val v = s.trim()
        if (v.startsWith("#") && v.length == 4) {
            val r = v[1]; val g = v[2]; val b = v[3]
            return runCatching { Color.parseColor("#$r$r$g$g$b$b") }.getOrNull()
        }
        return runCatching { Color.parseColor(v) }.getOrNull()
    }

    // ---- path data ----

    private class Reader(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun atNumber(): Boolean { skip(); return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.') }
        fun flag(): Float { skip(); val c = s[i++]; return if (c == '1') 1f else 0f }
        fun number(): Float {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) {
                    i++; if (s[i] == '-' || s[i] == '+') i++
                } else break
            }
            return s.substring(start, i).toFloat()
        }
    }

    fun parsePath(d: String): Path {
        val p = Path()
        val r = Reader(d)
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f      // last control point, for S and T
        var cmd = ' '; var prev = ' '
        while (true) {
            r.skip()
            if (r.i >= d.length) break
            val ch = d[r.i]
            if (ch.isLetter() && ch != 'e' && ch != 'E') { cmd = ch; r.i++ }
            else if (cmd == ' ') break
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f; val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + r.number(); cy = oy + r.number(); p.moveTo(cx, cy); sx = cx; sy = cy
                    cmd = if (rel) 'l' else 'L'   // further pairs are lines
                }
                'L' -> { cx = ox + r.number(); cy = oy + r.number(); p.lineTo(cx, cy) }
                'H' -> { cx = (if (rel) cx else 0f) + r.number(); p.lineTo(cx, cy) }
                'V' -> { cy = (if (rel) cy else 0f) + r.number(); p.lineTo(cx, cy) }
                'C' -> {
                    val x1 = ox + r.number(); val y1 = oy + r.number()
                    val x2 = ox + r.number(); val y2 = oy + r.number()
                    cx = ox + r.number(); cy = oy + r.number()
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'S' -> {
                    val x1 = if (prev.uppercaseChar() in "CS") 2 * cx - lcx else cx
                    val y1 = if (prev.uppercaseChar() in "CS") 2 * cy - lcy else cy
                    val x2 = ox + r.number(); val y2 = oy + r.number()
                    cx = ox + r.number(); cy = oy + r.number()
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'Q' -> {
                    val x1 = ox + r.number(); val y1 = oy + r.number()
                    cx = ox + r.number(); cy = oy + r.number()
                    p.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1
                }
                'T' -> {
                    val x1 = if (prev.uppercaseChar() in "QT") 2 * cx - lcx else cx
                    val y1 = if (prev.uppercaseChar() in "QT") 2 * cy - lcy else cy
                    cx = ox + r.number(); cy = oy + r.number()
                    p.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1
                }
                'A' -> {
                    val rx = r.number(); val ry = r.number(); val rot = r.number()
                    val large = r.flag() != 0f; val sweep = r.flag() != 0f
                    val x = ox + r.number(); val y = oy + r.number()
                    arc(p, cx, cy, x, y, rx, ry, rot, large, sweep)
                    cx = x; cy = y
                }
                'Z' -> { p.close(); cx = sx; cy = sy }
                else -> break
            }
            prev = cmd
            if (cmd.uppercaseChar() == 'Z') { if (!r.atNumber()) continue else break }
        }
        return p
    }

    /** SVG endpoint arc, converted to cubic Beziers (SVG spec, appendix F.6). */
    private fun arc(p: Path, x0: Float, y0: Float, x: Float, y: Float, rx0: Float, ry0: Float,
                    angle: Float, large: Boolean, sweep: Boolean) {
        if (x0 == x && y0 == y) return
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x, y); return }
        val phi = Math.toRadians(angle.toDouble())
        val cp = cos(phi); val sp = sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1 = cp * dx + sp * dy; val y1 = -sp * dx + cp * dy
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var coef = sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1 / ry; val cyp = -coef * ry * x1 / rx
        val ccx = cp * cxp - sp * cyp + (x0 + x) / 2.0
        val ccy = sp * cxp + cp * cyp + (y0 + y) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val t1 = ang(1.0, 0.0, (x1 - cxp) / rx, (y1 - cyp) / ry)
        var dt = ang((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * PI else if (sweep && dt < 0) dt += 2 * PI
        val segs = ceil(abs(dt) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / segs
        val k = 4.0 / 3.0 * kotlin.math.tan(step / 4)
        var t = t1
        for (s in 0 until segs) {
            val c1 = cos(t); val s1 = sin(t); val c2 = cos(t + step); val s2 = sin(t + step)
            fun px(ex: Double, ey: Double) = (cp * rx * ex - sp * ry * ey + ccx).toFloat()
            fun py(ex: Double, ey: Double) = (sp * rx * ex + cp * ry * ey + ccy).toFloat()
            p.cubicTo(px(c1 - k * s1, s1 + k * c1), py(c1 - k * s1, s1 + k * c1),
                px(c2 + k * s2, s2 - k * c2), py(c2 + k * s2, s2 - k * c2),
                px(c2, s2), py(c2, s2))
            t += step
        }
    }
}
