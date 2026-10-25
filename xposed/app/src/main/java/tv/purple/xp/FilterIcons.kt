package tv.purple.xp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small line icons for the video filters, drawn in code so they stay sharp at any size. */
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

    /** Sun: brightness. */
    class Sun : Icon() {
        override fun paint(c: Canvas, s: Float) {
            val cx = s / 2f
            c.drawCircle(cx, cx, s * 0.17f, stroke)
            for (k in 0 until 8) {
                val a = k * Math.PI / 4
                val r1 = s * 0.29f; val r2 = s * 0.42f
                c.drawLine(cx + (r1 * cos(a)).toFloat(), cx + (r1 * sin(a)).toFloat(),
                    cx + (r2 * cos(a)).toFloat(), cx + (r2 * sin(a)).toFloat(), stroke)
            }
        }
    }

    /** Half-filled circle: contrast. */
    class HalfCircle : Icon() {
        override fun paint(c: Canvas, s: Float) {
            val r = s * 0.36f
            val box = RectF(s / 2f - r, s / 2f - r, s / 2f + r, s / 2f + r)
            c.drawArc(box, 90f, 180f, true, fill)
            c.drawCircle(s / 2f, s / 2f, r, stroke)
        }
    }

    /** Mountains: clarity (local contrast on shapes). */
    class Peaks : Icon() {
        override fun paint(c: Canvas, s: Float) {
            val p = Path().apply {
                moveTo(s * 0.1f, s * 0.78f); lineTo(s * 0.38f, s * 0.32f); lineTo(s * 0.55f, s * 0.58f)
                lineTo(s * 0.68f, s * 0.44f); lineTo(s * 0.9f, s * 0.78f); close()
            }
            c.drawPath(p, stroke)
            c.drawCircle(s * 0.74f, s * 0.24f, s * 0.07f, fill)
        }
    }

    /** Dot grid: texture (fine detail). */
    class Dots : Icon() {
        override fun paint(c: Canvas, s: Float) {
            for (i in 0..2) for (j in 0..2)
                c.drawCircle(s * (0.25f + i * 0.25f), s * (0.25f + j * 0.25f), s * 0.06f, fill)
        }
    }

    /** Droplet: saturation. */
    class Drop : Icon() {
        override fun paint(c: Canvas, s: Float) {
            val p = Path().apply {
                moveTo(s * 0.5f, s * 0.12f)
                cubicTo(s * 0.5f, s * 0.12f, s * 0.2f, s * 0.48f, s * 0.2f, s * 0.6f)
                cubicTo(s * 0.2f, s * 0.78f, s * 0.34f, s * 0.9f, s * 0.5f, s * 0.9f)
                cubicTo(s * 0.66f, s * 0.9f, s * 0.8f, s * 0.78f, s * 0.8f, s * 0.6f)
                cubicTo(s * 0.8f, s * 0.48f, s * 0.5f, s * 0.12f, s * 0.5f, s * 0.12f)
                close()
            }
            c.drawPath(p, stroke)
        }
    }

    /** Three overlapping colour circles: vibrance. Always drawn in colour. */
    class Rgb : Icon() {
        override fun paint(c: Canvas, s: Float) {
            val cols = intArrayOf(0xCCFF5C7A.toInt(), 0xCC5CE1A0.toInt(), 0xCC6FA8FF.toInt())
            val pts = arrayOf(floatArrayOf(0.5f, 0.36f), floatArrayOf(0.36f, 0.62f), floatArrayOf(0.64f, 0.62f))
            for (k in 0..2) {
                fill.color = cols[k]
                c.drawCircle(s * pts[k][0], s * pts[k][1], s * 0.2f, fill)
            }
        }
    }

    fun forFilter(i: Int): Icon = when (i) {
        0 -> Sun(); 1 -> HalfCircle(); 2 -> Peaks(); 3 -> Dots(); 4 -> Drop(); else -> Rgb()
    }
}
