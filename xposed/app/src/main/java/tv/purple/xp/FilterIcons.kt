package tv.purple.xp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Small icons for the video filters, drawn in code so they stay sharp at any size.
 * Filters that change colour get colourful icons; the rest are plain white line icons.
 */
object FilterIcons {

    const val PURPLE = 0xFFA970FF.toInt()

    /** Base: a square drawing area, a stroke paint and a fill paint in [color]. */
    abstract class Icon(var color: Int = Color.WHITE) : Drawable() {
        protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        protected val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val s = min(b.width(), b.height()).toFloat()
            if (s <= 0f) return
            stroke.color = color; fill.color = color
            stroke.shader = null; fill.shader = null
            stroke.strokeWidth = s * 0.08f
            canvas.save()
            canvas.translate(b.exactCenterX() - s / 2f, b.exactCenterY() - s / 2f)
            paint(canvas, s)
            canvas.restore()
        }

        /** Draw within (0, 0, s, s). */
        protected abstract fun paint(c: Canvas, s: Float)

        override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { stroke.colorFilter = cf; fill.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = 96
        override fun getIntrinsicHeight() = 96
    }

    private fun icon(block: Icon.(Canvas, Float, Paint, Paint) -> Unit): Icon = object : Icon() {
        override fun paint(c: Canvas, s: Float) = block(c, s, stroke, fill)
    }

    /** Three slider lines with knobs: the player button. Purple while any filter is on. */
    class Tune : Icon() {
        fun setActive(on: Boolean) {
            val c = if (on) PURPLE else Color.WHITE
            if (c != color) { color = c; invalidateSelf() }
        }
        override fun paint(c: Canvas, s: Float) {
            val knobs = floatArrayOf(0.65f, 0.32f, 0.55f)
            for (i in 0..2) {
                val y = s * (0.27f + i * 0.23f)
                c.drawLine(s * 0.18f, y, s * 0.82f, y, stroke)
                c.drawCircle(s * knobs[i], y, s * 0.085f, fill)
            }
        }
    }

    private fun sun(c: Canvas, s: Float, st: Paint, r0: Float = 0.17f) {
        val cx = s / 2f
        c.drawCircle(cx, cx, s * r0, st)
        for (k in 0 until 8) {
            val a = k * Math.PI / 4
            val r1 = s * 0.29f; val r2 = s * 0.42f
            c.drawLine(cx + (r1 * cos(a)).toFloat(), cx + (r1 * sin(a)).toFloat(),
                cx + (r2 * cos(a)).toFloat(), cx + (r2 * sin(a)).toFloat(), st)
        }
    }

    private fun rainbow(s: Float) = SweepGradient(s / 2f, s / 2f,
        intArrayOf(0xFFFF4D6D.toInt(), 0xFFFFC14D.toInt(), 0xFF7BE35A.toInt(), 0xFF4DD6FF.toInt(),
            0xFF8A6BFF.toInt(), 0xFFFF5CD6.toInt(), 0xFFFF4D6D.toInt()), null)

    fun forKey(key: String): Icon = when (key) {
        "exposure" -> icon { c, s, st, f ->
            c.drawCircle(s / 2, s / 2, s * 0.36f, st)
            c.drawLine(s * 0.3f, s * 0.38f, s * 0.46f, s * 0.38f, st)
            c.drawLine(s * 0.38f, s * 0.3f, s * 0.38f, s * 0.46f, st)
            c.drawLine(s * 0.54f, s * 0.64f, s * 0.7f, s * 0.64f, st)
            c.drawLine(s * 0.26f, s * 0.74f, s * 0.74f, s * 0.26f, st)
        }
        "bright" -> icon { c, s, st, _ -> sun(c, s, st) }
        "contrast" -> icon { c, s, st, f ->
            val r = s * 0.36f
            c.drawArc(RectF(s / 2 - r, s / 2 - r, s / 2 + r, s / 2 + r), 90f, 180f, true, f)
            c.drawCircle(s / 2, s / 2, r, st)
        }
        "highlights" -> icon { c, s, st, f ->
            sun(c, s, st, 0.12f)
            c.drawCircle(s / 2, s / 2, s * 0.12f, f)
        }
        "shadows" -> icon { c, s, st, f ->
            val p = Path().apply {
                addCircle(s * 0.5f, s * 0.5f, s * 0.34f, Path.Direction.CW)
            }
            c.drawPath(p, st)
            val r = s * 0.34f
            c.drawArc(RectF(s / 2 - r, s / 2 - r, s / 2 + r, s / 2 + r), -90f, 180f, true, f)
        }
        "gamma" -> icon { c, s, st, _ ->
            val p = Path().apply { moveTo(s * 0.15f, s * 0.85f); cubicTo(s * 0.25f, s * 0.2f, s * 0.6f, s * 0.15f, s * 0.85f, s * 0.15f) }
            c.drawPath(p, st)
            st.strokeWidth = s * 0.04f
            c.drawLine(s * 0.15f, s * 0.85f, s * 0.85f, s * 0.15f, st.apply { alpha = 110 })
        }
        "fade" -> icon { c, s, st, f ->
            f.shader = LinearGradient(0f, s * 0.8f, 0f, s * 0.2f, 0x99FFFFFF.toInt(), 0x22FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f), s * 0.1f, s * 0.1f, f)
            c.drawRoundRect(RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f), s * 0.1f, s * 0.1f, st)
        }
        "clarity" -> icon { c, s, st, f ->
            val p = Path().apply {
                moveTo(s * 0.1f, s * 0.78f); lineTo(s * 0.38f, s * 0.32f); lineTo(s * 0.55f, s * 0.58f)
                lineTo(s * 0.68f, s * 0.44f); lineTo(s * 0.9f, s * 0.78f); close()
            }
            c.drawPath(p, st)
            c.drawCircle(s * 0.74f, s * 0.24f, s * 0.07f, f)
        }
        "texture" -> icon { c, s, st, _ ->
            val p = Path().apply { moveTo(s * 0.5f, s * 0.12f); lineTo(s * 0.82f, s * 0.5f); lineTo(s * 0.5f, s * 0.88f); lineTo(s * 0.18f, s * 0.5f); close() }
            c.drawPath(p, st)
            c.drawLine(s * 0.5f, s * 0.12f, s * 0.5f, s * 0.88f, st)
        }
        "soft" -> icon { c, s, _, f ->
            for (k in 3 downTo 1) {
                f.alpha = 70 + (3 - k) * 70
                c.drawCircle(s / 2, s / 2, s * (0.14f + k * 0.08f), f)
            }
        }
        "temp" -> icon { c, s, st, f ->
            f.shader = LinearGradient(0f, s * 0.15f, 0f, s * 0.85f, 0xFFFF7A3D.toInt(), 0xFF4DA6FF.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(s * 0.4f, s * 0.12f, s * 0.6f, s * 0.7f), s * 0.1f, s * 0.1f, f)
            c.drawCircle(s * 0.5f, s * 0.74f, s * 0.15f, f)
        }
        "tint" -> icon { c, s, _, f ->
            f.color = 0xFF5CE07A.toInt(); c.drawArc(RectF(s * 0.14f, s * 0.14f, s * 0.86f, s * 0.86f), 90f, 180f, true, f)
            f.color = 0xFFFF5CC8.toInt(); c.drawArc(RectF(s * 0.14f, s * 0.14f, s * 0.86f, s * 0.86f), -90f, 180f, true, f)
        }
        "hue" -> icon { c, s, st, _ ->
            st.shader = rainbow(s); st.strokeWidth = s * 0.16f
            c.drawCircle(s / 2, s / 2, s * 0.3f, st)
        }
        "sat" -> icon { c, s, _, f ->
            f.shader = LinearGradient(s * 0.2f, s * 0.9f, s * 0.8f, s * 0.15f, 0xFFFF4D9A.toInt(), 0xFF6F7BFF.toInt(), Shader.TileMode.CLAMP)
            val p = Path().apply {
                moveTo(s * 0.5f, s * 0.12f)
                cubicTo(s * 0.5f, s * 0.12f, s * 0.2f, s * 0.48f, s * 0.2f, s * 0.6f)
                cubicTo(s * 0.2f, s * 0.78f, s * 0.34f, s * 0.9f, s * 0.5f, s * 0.9f)
                cubicTo(s * 0.66f, s * 0.9f, s * 0.8f, s * 0.78f, s * 0.8f, s * 0.6f)
                cubicTo(s * 0.8f, s * 0.48f, s * 0.5f, s * 0.12f, s * 0.5f, s * 0.12f)
                close()
            }
            c.drawPath(p, f)
        }
        "vib" -> icon { c, s, _, f ->
            val cols = intArrayOf(0xCCFF5C7A.toInt(), 0xCC5CE1A0.toInt(), 0xCC6FA8FF.toInt())
            val pts = arrayOf(floatArrayOf(0.5f, 0.36f), floatArrayOf(0.36f, 0.62f), floatArrayOf(0.64f, 0.62f))
            for (k in 0..2) { f.color = cols[k]; c.drawCircle(s * pts[k][0], s * pts[k][1], s * 0.2f, f) }
        }
        "mono" -> icon { c, s, st, f ->
            f.shader = LinearGradient(s * 0.15f, 0f, s * 0.85f, 0f, intArrayOf(Color.BLACK, FilterDefs.monoColor(), Color.WHITE),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(s / 2, s / 2, s * 0.36f, f)
            st.strokeWidth = s * 0.05f; c.drawCircle(s / 2, s / 2, s * 0.36f, st)
        }
        "gmap" -> icon { c, s, st, f ->
            f.shader = LinearGradient(s * 0.12f, 0f, s * 0.88f, 0f, FilterDefs.currentGradient(), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(s * 0.12f, s * 0.3f, s * 0.88f, s * 0.7f), s * 0.08f, s * 0.08f, f)
            st.strokeWidth = s * 0.05f
            c.drawRoundRect(RectF(s * 0.12f, s * 0.3f, s * 0.88f, s * 0.7f), s * 0.08f, s * 0.08f, st)
        }
        "poster" -> icon { c, s, _, f ->
            for (k in 0..3) {
                f.alpha = 70 + k * 60
                c.drawRect(s * (0.14f + k * 0.18f), s * (0.7f - k * 0.13f), s * (0.3f + k * 0.18f), s * 0.82f, f)
            }
        }
        "invert" -> icon { c, s, st, f ->
            f.color = 0xFF2B2B2B.toInt()
            c.drawCircle(s / 2, s / 2, s * 0.36f, f)
            f.shader = rainbow(s)
            val r = s * 0.36f
            c.drawArc(RectF(s / 2 - r, s / 2 - r, s / 2 + r, s / 2 + r), -45f, 180f, true, f)
            st.strokeWidth = s * 0.05f; c.drawCircle(s / 2, s / 2, r, st)
        }
        "ovl" -> icon { c, s, st, f ->
            c.drawRoundRect(RectF(s * 0.14f, s * 0.36f, s * 0.64f, s * 0.86f), s * 0.07f, s * 0.07f, st)
            f.alpha = 150
            c.drawRoundRect(RectF(s * 0.36f, s * 0.14f, s * 0.86f, s * 0.64f), s * 0.07f, s * 0.07f, f)
        }
        "grain" -> icon { c, s, _, f ->
            val rnd = java.util.Random(7)
            for (k in 0 until 22) c.drawCircle(s * (0.14f + rnd.nextFloat() * 0.72f), s * (0.14f + rnd.nextFloat() * 0.72f), s * 0.035f, f)
        }
        "vignette" -> icon { c, s, st, f ->
            c.drawRoundRect(RectF(s * 0.1f, s * 0.2f, s * 0.9f, s * 0.8f), s * 0.08f, s * 0.08f, st)
            f.alpha = 200; c.drawOval(RectF(s * 0.26f, s * 0.32f, s * 0.74f, s * 0.68f), f)
        }
        "chroma" -> icon { c, s, st, _ ->
            st.strokeWidth = s * 0.07f
            st.color = 0xFFFF4D6D.toInt(); c.drawCircle(s * 0.42f, s * 0.5f, s * 0.25f, st)
            st.color = 0xFF4DD6FF.toInt(); c.drawCircle(s * 0.58f, s * 0.5f, s * 0.25f, st)
        }
        "pixel" -> icon { c, s, _, f ->
            for (i in 0..2) for (j in 0..2) {
                f.alpha = if ((i + j) % 2 == 0) 255 else 110
                c.drawRect(s * (0.16f + i * 0.23f), s * (0.16f + j * 0.23f), s * (0.37f + i * 0.23f), s * (0.37f + j * 0.23f), f)
            }
        }
        else -> icon { c, s, st, _ -> c.drawCircle(s / 2, s / 2, s * 0.3f, st) }
    }
}
