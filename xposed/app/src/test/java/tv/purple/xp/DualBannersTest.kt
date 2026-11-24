package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DualBannersTest {

    private fun pin(id: String, ends: String) = """{"node":{"id":"$id","endsAt":"$ends","pinnedMessage":{"content":{"text":"hello"},"sender":{"displayName":"Bob","login":"bob"}}}}"""

    @Test fun readsEveryKindInOrder() {
        val json = """{"data":{"channel":{"mod":{"edges":[${pin("p1", "2030-01-01T00:00:00Z")}]},"paid":null,
            "viewerDropCampaigns":[{"id":"d1","name":"Camp","eventBasedDrops":[{"id":"e","name":"n","missionName":"m","missionDescription":"Watch to earn"}]}]},
            "user":{"channel":{"hypeTrain":{"execution":{"id":"h1","isActive":true,"progress":{"level":{"value":3},"goal":200,"progression":50}}}}}}}"""
        val out = DualBanners.parse(json, 1_000L)!!
        assertEquals(listOf(DualBanners.Kind.HYPE, DualBanners.Kind.PINNED, DualBanners.Kind.DROPS), out.map { it.kind })
        assertEquals(3, out[0].level)
        assertEquals(0.25f, out[0].progress!!, 0.001f)
        assertEquals("Bob: hello", out[1].text)
        assertEquals("Watch to earn", out[2].text)
    }

    @Test fun endedPinsAndTrainsAreLeftOut() {
        val json = """{"data":{"channel":{"mod":{"edges":[${pin("p1", "2020-01-01T00:00:00Z")}]}},
            "user":{"channel":{"hypeTrain":{"execution":{"id":"h1","isActive":false,"progress":{"goal":1,"progression":1}}}}}}}"""
        assertTrue(DualBanners.parse(json, System.currentTimeMillis())!!.isEmpty())
    }

    @Test fun notJsonIsNoAnswer() {
        assertNull(DualBanners.parse("nope", 0L))
    }
}
