package tv.purple.xp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames below are trimmed copies of what events.7tv.io sent on 2026-11-03. */
class SevenTvEventsTest {

    private val badgeDef = """{"d":{"type":"cosmetic.create","body":{"id":"01K7C9D55TY03E1SV9W4YF3VX1","kind":10,
        "object":{"id":"01K7C9D55TY03E1SV9W4YF3VX1","kind":"BADGE","data":{"id":"01K7C9D55TY03E1SV9W4YF3VX1",
        "name":"CS2 Pickems Glorp","tag":"","tooltip":"CS2 Pickems Glorp Badge",
        "host":{"url":"//cdn.7tv.app/badge/01K7C9D55TY03E1SV9W4YF3VX1","files":[
        {"name":"1x.webp","static_name":"1x.webp","width":18,"height":18,"frame_count":1,"format":"WEBP"},
        {"name":"2x.webp","static_name":"2x.webp","width":36,"height":36,"frame_count":1,"format":"WEBP"},
        {"name":"3x.avif","static_name":"3x.avif","width":54,"height":54,"frame_count":1,"format":"AVIF"},
        {"name":"3x.webp","static_name":"3x.webp","width":54,"height":54,"frame_count":1,"format":"WEBP"},
        {"name":"4x.webp","static_name":"4x.webp","width":72,"height":72,"frame_count":1,"format":"WEBP"}]}}}}},
        "op":0,"t":1793739191108,"s":4}"""

    private fun entitlement(type: String, kind: String = "BADGE") = """{"d":{"type":"$type","body":{
        "id":"00000000000000000000000000","kind":10,"object":{"id":"00000000000000000000000000","kind":"$kind",
        "ref_id":"01K7C9D55TY03E1SV9W4YF3VX1","user":{"id":"01K4PX2SEBAC8PTPWNHKBFE5Z2","username":"tailwhipvr",
        "style":{"badge_id":"01K7C9D55TY03E1SV9W4YF3VX1"},"connections":[
        {"id":"513786600","platform":"TWITCH","username":"tailwhipvr"},
        {"id":"1297748","platform":"KICK","username":"TailwhipVR"},
        {"id":"UCLSMzok6Q3oDoN6tL_gy9dQ","platform":"YOUTUBE"}]}}}},"op":0,"t":1,"s":8}"""

    @Test fun hello() {
        val m = SevenTvEvents.parse("""{"d":{"heartbeat_interval":48990,"session_id":"01M6HJZT47GEZ6A7J7XJ6CAY1Q",
            "subscription_limit":500},"op":1,"t":1,"s":0}""", 54)
        m as SevenTvEvents.Hello
        assertEquals("01M6HJZT47GEZ6A7J7XJ6CAY1Q", m.sessionId)
        assertEquals(48990L, m.heartbeatMs)
    }

    @Test fun controlFrames() {
        assertSame(SevenTvEvents.Heartbeat, SevenTvEvents.parse("""{"d":{"count":3},"op":2}""", 54))
        assertSame(SevenTvEvents.Reconnect, SevenTvEvents.parse("""{"d":{},"op":4}""", 54))
        val end = SevenTvEvents.parse("""{"d":{"code":4006,"message":"restart"},"op":7}""", 54)
        assertEquals(4006, (end as SevenTvEvents.End).code)
        assertTrue(SevenTvEvents.parse("""{"d":{"message":"bad"},"op":6}""", 54) is SevenTvEvents.Error)
        // A subscribe ack is nothing the badges care about.
        assertSame(SevenTvEvents.Other, SevenTvEvents.parse(
            """{"d":{"command":"SUBSCRIBE","data":{"id":2}},"op":5}""", 54))
    }

    @Test fun badgeDefinitionPicksWebpAtLeastAsTallAsWanted() {
        val m = SevenTvEvents.parse(badgeDef, 54) as SevenTvEvents.BadgeDef
        assertEquals("01K7C9D55TY03E1SV9W4YF3VX1", m.id)
        assertEquals("CS2 Pickems Glorp Badge", m.tooltip)
        assertEquals("https://cdn.7tv.app/badge/01K7C9D55TY03E1SV9W4YF3VX1/3x.webp", m.url)
        val small = SevenTvEvents.parse(badgeDef, 30) as SevenTvEvents.BadgeDef
        assertTrue(small.url.endsWith("/2x.webp"))
        val huge = SevenTvEvents.parse(badgeDef, 200) as SevenTvEvents.BadgeDef
        assertTrue(huge.url.endsWith("/4x.webp"))
    }

    @Test fun pickUrlWithoutFilesFallsBackTo3x() {
        assertEquals("https://cdn.7tv.app/badge/abc/3x.webp",
            SevenTvEvents.pickUrl("//cdn.7tv.app/badge/abc", null, 54))
        assertEquals("https://cdn.7tv.app/badge/abc/3x.webp",
            SevenTvEvents.pickUrl("https://cdn.7tv.app/badge/abc/", JSONArray(), 54))
        assertNull(SevenTvEvents.pickUrl("", null, 54))
    }

    @Test fun paintsAreIgnored() {
        val paint = """{"d":{"type":"cosmetic.create","body":{"object":{"id":"p","kind":"PAINT",
            "data":{"id":"p","name":"Valentine"}}}},"op":0}"""
        assertSame(SevenTvEvents.Other, SevenTvEvents.parse(paint, 54))
        assertSame(SevenTvEvents.Other, SevenTvEvents.parse(entitlement("entitlement.create", "PAINT"), 54))
    }

    @Test fun entitlementCreateKeepsOnlyTwitchIds() {
        val m = SevenTvEvents.parse(entitlement("entitlement.create"), 54) as SevenTvEvents.Entitlement
        assertTrue(m.create)
        assertEquals("01K7C9D55TY03E1SV9W4YF3VX1", m.badgeId)
        assertEquals(listOf("513786600"), m.twitchIds)
    }

    @Test fun entitlementDelete() {
        val m = SevenTvEvents.parse(entitlement("entitlement.delete"), 54) as SevenTvEvents.Entitlement
        assertTrue(!m.create)
        assertEquals(listOf("513786600"), m.twitchIds)
    }

    @Test fun otherDispatchesAndGarbageAreIgnored() {
        assertSame(SevenTvEvents.Other, SevenTvEvents.parse(
            """{"d":{"type":"emote_set.update","body":{"id":"x"}},"op":0}""", 54))
        assertSame(SevenTvEvents.Other, SevenTvEvents.parse("not json", 54))
        assertSame(SevenTvEvents.Other, SevenTvEvents.parse("{}", 54))
    }

    @Test fun subscribeAndUnsubscribeFrames() {
        val sub = JSONObject(SevenTvEvents.subscription("entitlement.create", "71092938"))
        assertEquals(35, sub.getInt("op"))
        val d = sub.getJSONObject("d")
        assertEquals("entitlement.create", d.getString("type"))
        val c = d.getJSONObject("condition")
        assertEquals("channel", c.getString("ctx"))
        assertEquals("TWITCH", c.getString("platform"))
        assertEquals("71092938", c.getString("id"))
        assertEquals(36, JSONObject(SevenTvEvents.subscription("cosmetic.create", "1", false)).getInt("op"))
    }

    @Test fun presenceIsPassiveAndCarriesTheSession() {
        val p = JSONObject(SevenTvEvents.presenceBody("SESSION", "513786600"))
        assertEquals(1, p.getInt("kind"))
        assertTrue(p.getBoolean("passive"))
        assertEquals("SESSION", p.getString("session_id"))
        assertEquals("513786600", p.getJSONObject("data").getString("id"))
    }
}
