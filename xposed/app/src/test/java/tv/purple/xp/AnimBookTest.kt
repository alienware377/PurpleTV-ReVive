package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimBookTest {

    private class Pic(val name: String) { var running = false; override fun toString() = name }
    private class View(val name: String) { override fun toString() = name }

    private var flushAsked = 0
    private val book = AnimBook<View, Pic>(
        isRunning = { it.running },
        start = { it.running = true },
        stop = { it.running = false },
        requestFlush = { flushAsked++ },
    )

    @Test fun startsOnlyWhileShowing() {
        val row = View("row"); val a = Pic("a")
        book.show(row, listOf(a), showing = false)
        assertFalse("a detached row does not run its picture", a.running)
        book.setShowing(row, true)
        assertTrue(a.running)
        book.setShowing(row, false)
        assertTrue("the stop waits for the flush", a.running)
        book.flush()
        assertFalse(a.running)
        book.setShowing(row, true)
        assertTrue("shown again, it runs again", a.running)
    }

    @Test fun recycledRowStopsTheOldLinesPictures() {
        val row = View("row"); val a = Pic("a"); val b = Pic("b")
        book.show(row, listOf(a), showing = true)
        book.show(row, listOf(b), showing = true)
        book.flush()
        assertFalse(a.running); assertTrue(b.running)
        // Rebound to a line with no animated picture.
        book.show(row, emptyList(), showing = true)
        book.flush()
        assertFalse(b.running)
        assertEquals(0, book.stats().running)
    }

    @Test fun pictureMovedToAnotherRowKeepsRunningWithoutRestart() {
        val r1 = View("r1"); val r2 = View("r2"); val a = Pic("a")
        book.show(r1, listOf(a), showing = true)
        val startsBefore = book.starts
        // The line moves to r2 (not attached yet), then r1 scrolls away, then r2 attaches,
        // all before the next main loop turn.
        book.show(r2, listOf(a), showing = false)
        book.setShowing(r1, false)
        book.setShowing(r2, true)
        book.flush()
        assertTrue(a.running)
        assertEquals("never stopped and started again", startsBefore, book.starts)
        assertEquals(0L, book.stops)
        // The callback now reaches both rows; the old one is harmless to redraw.
        val hosts = book.hostsOf(a)!!.mapNotNull { it.get() }
        assertTrue(hosts.any { it === r2 })
    }

    @Test fun sharedPictureRunsWhileEitherViewShows() {
        val r1 = View("r1"); val r2 = View("r2"); val a = Pic("a")
        book.show(r1, listOf(a), showing = true)
        book.show(r2, listOf(a), showing = true)
        book.setShowing(r1, false); book.flush()
        assertTrue(a.running)
        book.setShowing(r2, false); book.flush()
        assertFalse(a.running)
        book.setShowing(r1, true)
        assertTrue(a.running)
    }

    @Test fun samePictureTwiceOnOneLineIsLinkedOnce() {
        val row = View("row"); val a = Pic("a")
        book.show(row, listOf(a, a), showing = true)
        assertEquals(1, book.hostsOf(a)!!.size)
        book.show(row, listOf(a), showing = true)
        assertEquals(1, book.hostsOf(a)!!.size)
        assertEquals(1L, book.starts)
    }

    @Test fun backgroundAndBack() {
        val rows = (0 until 5).map { View("r$it") }
        val pics = rows.map { r -> Pic("p$r").also { book.show(r, listOf(it), showing = true) } }
        assertEquals(5, book.stats().running)
        for (r in rows) book.setShowing(r, false)
        assertEquals("one flush asked for the whole batch", 1, flushAsked)
        book.flush()
        val s = book.stats()
        assertEquals(0, s.running); assertEquals(5, s.stopped); assertEquals(5L, s.stops)
        for (r in rows) book.setShowing(r, true)
        assertTrue(pics.all { it.running })
        assertEquals(5, book.stats().showingHosts)
    }

    @Test fun pictureWithNoHostLeftIsForgotten() {
        val row = View("row"); val a = Pic("a")
        book.show(row, listOf(a), showing = true)
        book.show(row, emptyList(), showing = true)
        book.flush()
        assertNull(book.hostsOf(a))
        assertTrue(book.isHost(row))
    }

    @Test fun unknownViewIsIgnored() {
        book.setShowing(View("x"), true)
        assertFalse(book.isHost(View("y")))
        assertEquals(0, book.stats().hosts)
    }

    @Test fun hostsAreHeldWeakly() {
        val a = Pic("a")
        var row: View? = View("row")
        book.show(row!!, listOf(a), showing = true)
        @Suppress("UNUSED_VALUE")
        row = null
        // The row is gone without ever being detached (a test of the weak keys, not a real case).
        repeat(20) { System.gc(); Thread.sleep(10) }
        val other = View("other")
        book.show(other, listOf(a), showing = false)
        book.flush()
        val live = book.hostsOf(a)!!.mapNotNull { it.get() }
        assertEquals(1, live.size)
        assertSame(other, live[0])
        assertFalse("its only showing host is gone", a.running)
    }
}
