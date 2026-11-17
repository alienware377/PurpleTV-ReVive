package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeMathTest {

    @Test fun straightCurveChangesNothing() {
        val t = ToneCurve.table(ToneCurve.parse(ToneCurve.LINEAR))
        for (i in 0..255) assertEquals(i.toFloat(), t[i], 0.01f)
    }

    @Test fun curvesNeverOvershootTheirPoints() {
        for (p in ToneCurve.PRESETS) for (s in listOf(p.m, p.r, p.g, p.b)) {
            val pts = ToneCurve.parse(s)
            val t = ToneCurve.table(pts)
            // Every preset rises from left to right, and so must its curve.
            for (i in 1..255) assertTrue("${p.name}: $s at $i", t[i] >= t[i - 1] - 0.01f)
            for (q in pts) assertEquals(q.y, t[q.x.toInt()], 0.6f)
        }
    }

    @Test fun parseKeepsBothEnds() {
        val pts = ToneCurve.parse("64,40;192,210")
        assertEquals(0f, pts.first().x, 0f)
        assertEquals(255f, pts.last().x, 0f)
        assertEquals("0,40;64,40;192,210;255,210", ToneCurve.format(pts))
    }

    @Test fun cubeFilesAreRead() {
        val identity = buildString {
            append("TITLE \"test\"\n# a comment\nLUT_3D_SIZE 2\n")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) append("$r.0 $g.0 $b.0\n")
        }
        val c = LutLibrary.parse(identity)
        assertNotNull(c)
        assertEquals(2, c!!.size)
        // Red changes fastest: the second entry is pure red.
        assertEquals(1f, c.rgb[3], 0f); assertEquals(0f, c.rgb[4], 0f); assertEquals(0f, c.rgb[5], 0f)
        // A domain of 0..2 is scaled into 0..1.
        val scaled = LutLibrary.parse(identity.replace("LUT_3D_SIZE 2\n", "LUT_3D_SIZE 2\nDOMAIN_MAX 2 2 2\n"))
        assertEquals(0.5f, scaled!!.rgb[3], 0f)
    }

    @Test fun badCubeFilesAreRefused() {
        assertNull(LutLibrary.parse("LUT_1D_SIZE 4\n0 0 0\n"))
        assertNull(LutLibrary.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n"))
        assertNull(LutLibrary.parse("LUT_3D_SIZE 200\n"))
        assertNull(LutLibrary.parse("hello"))
    }
}
