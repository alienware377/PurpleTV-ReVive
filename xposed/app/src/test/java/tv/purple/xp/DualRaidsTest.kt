package tv.purple.xp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DualRaidsTest {

    @Test fun readsARaid() {
        val e = DualRaids.parse("""{"type":"raid_go_v2","raid":{"id":"r1","creator_id":"7","target_login":"Bob","target_display_name":"Bob"}}""")!!
        assertEquals("raid_go_v2", e.type)
        assertEquals("r1", e.raidId)
        assertEquals("bob", e.targetLogin)
        assertEquals("7", e.creatorId)
    }

    @Test fun otherMessagesAreIgnored() {
        assertNull(DualRaids.parse("""{"type":"viewcount"}"""))
        assertNull(DualRaids.parse("not json"))
        assertNull(DualRaids.parse("""{"type":"raid_go_v2","raid":{}}"""))
    }

    @Test fun joinUsesTheRaidId() {
        val o = JSONObject(DualRaids.joinBody("r1"))
        assertEquals("JoinRaidMutation", o.getString("operationName"))
        assertEquals("r1", o.getJSONObject("variables").getString("input"))
    }

    @Test fun raidIdGoesOnTheStreamAddress() {
        val a = DualUsher.Access("t", "s", null, "1", "bob", "Bob", true, null, null, null)
        val url = DualUsher.usherUrl(null, "bob", a, 1L, "ab", "31.7.0", "r 1")
        assertEquals(true, url.endsWith("&raid_id=r%201"))
    }
}
