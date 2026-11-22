package tv.purple.xp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class DualUsherTest {

    private val tokenValue = """{"adblock":false,"authorization":{"forbidden":false,"reason":""},"channel":"somechannel","channel_id":123,"expires":1793739191}"""

    private fun reply(stream: String = """{"id":"401","title":"Speedruns & chill","viewersCount":1234,"game":{"displayName":"Celeste"}}""",
                      auth: String = """{"forbiddenReasonCode":"NONE"}""",
                      user: String? = null): String {
        val u = user ?: """{"id":"123","login":"SomeChannel","displayName":"SomeChannel","profileImageURL":"https://x/p.png","stream":$stream}"""
        return """{"data":{"streamPlaybackAccessToken":{"__typename":"PlaybackAccessToken","signature":"abc123sig",
            "value":${JSONObject.quote(tokenValue)},"authorization":$auth},"user":$u},
            "extensions":{"durationMilliseconds":42,"operationName":"StreamAccessTokenQuery"}}"""
    }

    private fun access(live: Boolean = true) = DualUsher.Access(tokenValue, "abc123sig", null, "123", "somechannel",
        "SomeChannel", live, null, null, null)

    /** The query part as ordered (key, decoded value) pairs. */
    private fun params(url: String): List<Pair<String, String>> =
        url.substringAfter('?').split('&').map { val k = it.substringBefore('='); k to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }

    private fun keys(url: String) = params(url).map { it.first }
    private fun value(url: String, k: String) = params(url).firstOrNull { it.first == k }?.second

    // --- token request ---

    @Test fun tokenBodyCarriesQueryAndVariables() {
        val b = JSONObject(DualUsher.tokenBody("  SomeChannel "))
        assertEquals("StreamAccessTokenQuery", b.getString("operationName"))
        assertEquals(DualUsher.TOKEN_QUERY, b.getString("query"))
        val v = b.getJSONObject("variables")
        assertEquals("somechannel", v.getString("channelName"))
        assertEquals("somechannel", v.getString("login"))
        val p = v.getJSONObject("params")
        assertEquals("android", p.getString("platform"))
        assertEquals("mobile_player", p.getString("playerType"))
        assertEquals(2, p.length())
    }

    @Test fun tokenQueryAsksForEverythingParsed() {
        val q = DualUsher.TOKEN_QUERY
        for (f in listOf("streamPlaybackAccessToken", "signature", "value", "forbiddenReasonCode", "user(login: \$login)",
            "displayName", "viewersCount", "title", "game { displayName }")) assertTrue(f, f in q)
    }

    @Test fun parseLive() {
        val a = DualUsher.parseAccess(reply(), "somechannel")!!
        assertEquals(tokenValue, a.value)
        assertEquals("abc123sig", a.signature)
        assertNull(a.forbidden)
        assertEquals("123", a.id)
        assertEquals("somechannel", a.login)
        assertEquals("SomeChannel", a.displayName)
        assertTrue(a.live)
        assertEquals("Speedruns & chill", a.title)
        assertEquals(1234, a.viewers)
        assertEquals("Celeste", a.game)
    }

    @Test fun parseOffline() {
        val a = DualUsher.parseAccess(reply(stream = "null"), "somechannel")!!
        assertFalse(a.live)
        assertNull(a.title)
        assertNull(a.viewers)
        assertNull(a.game)
        assertEquals("SomeChannel", a.displayName)
    }

    @Test fun parseLiveWithoutGameOrTitle() {
        val a = DualUsher.parseAccess(reply(stream = """{"id":"1","title":null,"viewersCount":0,"game":null}"""), "x")!!
        assertTrue(a.live)
        assertNull(a.title)
        assertEquals(0, a.viewers)
        assertNull(a.game)
    }

    @Test fun parseForbidden() {
        assertEquals("GEOBLOCKED", DualUsher.parseAccess(reply(auth = """{"forbiddenReasonCode":"GEOBLOCKED"}"""), "x")!!.forbidden)
        assertNull(DualUsher.parseAccess(reply(auth = """{"forbiddenReasonCode":"none"}"""), "x")!!.forbidden)
        assertNull(DualUsher.parseAccess(reply(auth = "null"), "x")!!.forbidden)
        assertNull(DualUsher.parseAccess(reply(auth = """{"forbiddenReasonCode":null}"""), "x")!!.forbidden)
    }

    @Test fun nullsAreNotTheWordNull() {
        val u = """{"id":123,"login":null,"displayName":null,"stream":null}"""
        val a = DualUsher.parseAccess(reply(user = u), "Typed_Name")!!
        assertNull(a.displayName)
        assertEquals("typed_name", a.login)
        // A numeric id is still read as text.
        assertEquals("123", a.id)
    }

    @Test fun parseErrors() {
        assertNull(DualUsher.parseAccess("""{"errors":[{"message":"service timeout","path":["streamPlaybackAccessToken"]}],"data":null}""", "x"))
        assertNull(DualUsher.parseAccess("""{"error":"Unauthorized","status":401,"message":"The \"Authorization\" token is invalid."}""", "x"))
        assertNull(DualUsher.parseAccess("""{"data":{"streamPlaybackAccessToken":null,"user":null}}""", "x"))
        assertNull(DualUsher.parseAccess("""{"data":{"streamPlaybackAccessToken":{"signature":"s","value":null},"user":{"id":"1"}}}""", "x"))
        assertNull(DualUsher.parseAccess("""{"data":{"streamPlaybackAccessToken":{"signature":"","value":"v"},"user":{"id":"1"}}}""", "x"))
        assertNull(DualUsher.parseAccess("<html>502 Bad Gateway</html>", "x"))
        assertNull(DualUsher.parseAccess("", "x"))
    }

    @Test fun missingChannelIsNull() {
        assertNull(DualUsher.parseAccess(reply(user = "null"), "nobody"))
    }

    @Test fun partialErrorWithTokenIsUsed() {
        val withError = reply().trimEnd().removeSuffix("}") + ""","errors":[{"message":"failed to load profile image"}]}"""
        val a = DualUsher.parseAccess(withError, "somechannel")
        assertNotNull(a)
        assertTrue(a!!.live)
    }

    // --- usher address ---

    @Test fun usherMinimal() {
        val url = DualUsher.usherUrl(null, "SomeChannel", access(), 1793739191000L, "0123456789abcdef0123456789abcdef", "31.7.0")
        assertTrue(url, url.startsWith("https://usher.ttvnw.net/api/channel/hls/somechannel.m3u8?token="))
        assertEquals(listOf("token", "sig", "player_backend", "cache_buster", "fast_bread", "allow_source", "allow_audio_only",
            "autoplayed", "force_preroll", "play_session_id", "os", "transcode_mode", "app_version", "include_unavailable"), keys(url))
        assertEquals(tokenValue, value(url, "token"))
        assertEquals("abc123sig", value(url, "sig"))
        assertEquals("Core", value(url, "player_backend"))
        assertEquals("1793739191000", value(url, "cache_buster"))
        assertEquals("0123456789abcdef0123456789abcdef", value(url, "play_session_id"))
        assertEquals("31.7.0", value(url, "app_version"))
        assertEquals("false", value(url, "autoplayed"))
        // The JSON token is fully escaped: no raw braces, quotes, spaces or plus signs in the address.
        for (ch in listOf('{', '}', '"', ' ', '+')) assertFalse("raw $ch", ch in url)
    }

    @Test fun usherMinimalWithoutVersion() {
        val url = DualUsher.usherUrl(null, "somechannel", access(), 1L, "ab", " ")
        assertFalse("app_version" in keys(url))
        assertEquals("include_unavailable", keys(url).last())
    }

    @Test fun spaceEncodedLikeUriBuilder() {
        val a = access().copy(value = "a b+c")
        val url = DualUsher.usherUrl(null, "somechannel", a, 1L, "ab", "31.7.0")
        assertTrue(url, url.contains("token=a%20b%2Bc&"))
    }

    private val twitchUri = "https://usher.ttvnw.net/api/channel/hls/otherchannel.m3u8?token=%7B%22old%22%3Atrue%7D&sig=oldsig" +
        "&player_backend=Core&cache_buster=1700000000000&fast_bread=true&allow_source=true&allow_audio_only=true" +
        "&autoplayed=true&mobile_cellular=false&cdm=null&force_creative_id=null&force_preroll=false&raid_id=r123" +
        "&play_session_id=ffffffffffffffffffffffffffffffff&dt=tablet&dmk=samsung&dmdl=SM-X610&os=Android" +
        "&acmb=eyJNb2JpbGVBcHBWZXJzaW9uIjoiMzEuNy4wIn0%3D&transcode_mode=cbr_v1&app_version=31.7.0" +
        "&multigroup_video=false&lang=en&include_unavailable=true#frag"

    @Test fun usherFromTwitchTemplate() {
        val url = DualUsher.usherUrl(twitchUri, "SomeChannel", access(), 1793739191000L, "0123456789abcdef0123456789abcdef", "9.9.9")
        assertTrue(url, url.startsWith("https://usher.ttvnw.net/api/channel/hls/somechannel.m3u8?token="))
        assertEquals(listOf("token", "sig", "player_backend", "cache_buster", "fast_bread", "allow_source", "allow_audio_only",
            "autoplayed", "mobile_cellular", "cdm", "force_creative_id", "force_preroll", "play_session_id", "dt", "dmk", "dmdl",
            "os", "acmb", "transcode_mode", "app_version", "multigroup_video", "lang", "include_unavailable"), keys(url))
        assertEquals(tokenValue, value(url, "token"))
        assertEquals("abc123sig", value(url, "sig"))
        assertEquals("1793739191000", value(url, "cache_buster"))
        assertEquals("0123456789abcdef0123456789abcdef", value(url, "play_session_id"))
        assertEquals("false", value(url, "autoplayed"))
        // Twitch's own values are kept as sent, including its app version and encoded extras.
        assertEquals("31.7.0", value(url, "app_version"))
        assertEquals("tablet", value(url, "dt"))
        assertTrue(url, url.contains("&acmb=eyJNb2JpbGVBcHBWZXJzaW9uIjoiMzEuNy4wIn0%3D&"))
        for (gone in listOf("oldsig", "1700000000000", "ffffffff", "raid_id", "otherchannel", "frag")) assertFalse(gone, gone in url)
    }

    @Test fun templateWithoutClockOrSessionGetsThem() {
        val t = "https://usher.ttvnw.net/api/channel/hls/x.m3u8?token=t&sig=s&dt=phone&lang=de"
        val url = DualUsher.usherUrl(t, "somechannel", access(), 5L, "cd", "31.1.2")
        assertEquals(listOf("token", "sig", "dt", "lang", "cache_buster", "play_session_id"), keys(url))
        assertEquals("5", value(url, "cache_buster"))
        assertEquals("cd", value(url, "play_session_id"))
    }

    @Test fun otherUsherHostKept() {
        val t = "https://video-weaver.ams03.hls.ttvnw.net/api/channel/hls/x.m3u8?dt=phone"
        val url = DualUsher.usherUrl(t, "somechannel", access(), 5L, "cd", "31.7.0")
        assertTrue(url, url.startsWith("https://video-weaver.ams03.hls.ttvnw.net/api/channel/hls/somechannel.m3u8?"))
    }

    @Test fun badTemplatesFallBackToMinimal() {
        val minimal = DualUsher.usherUrl(null, "somechannel", access(), 5L, "cd", "31.7.0")
        for (t in listOf(
            "", "   ", "not a url",
            "http://usher.ttvnw.net/api/channel/hls/x.m3u8?dt=phone",
            "https://example.com/api/channel/hls/x.m3u8?dt=phone",
            "https://usher.ttvnw.net.example.com/api/channel/hls/x.m3u8?dt=phone",
            "https://eviltwitchttvnw.net/api/channel/hls/x.m3u8?dt=phone",
            "https://usher.ttvnw.net:8443/api/channel/hls/x.m3u8?dt=phone",
            "https://usher.ttvnw.net/api/channel/hls/x.m3u8",
            "https://usher.ttvnw.net/api/channel/hls/x.m3u8?token=a&sig=b&cache_buster=1&play_session_id=2&raid_id=3",
        )) assertEquals(t, minimal, DualUsher.usherUrl(t, "somechannel", access(), 5L, "cd", "31.7.0"))
    }

    // --- followed channels ---

    @Test fun followedBodyCarriesQuery() {
        val b = JSONObject(DualUsher.followedBody())
        assertEquals("FollowedLiveUsers", b.getString("operationName"))
        assertEquals(DualUsher.FOLLOWED_QUERY, b.getString("query"))
        assertTrue("followedLiveUsers(first: 50)" in DualUsher.FOLLOWED_QUERY)
    }

    private fun user(login: String?, viewers: Int?, live: Boolean = true, id: String? = "id_$login") =
        """{"__typename":"User","id":${id?.let { "\"$it\"" } ?: "null"},"login":${login?.let { "\"$it\"" } ?: "null"},
        "displayName":"${login?.uppercase()}","stream":${if (live) """{"id":"s","title":"t $login","viewersCount":${viewers ?: "null"},
        "game":{"displayName":"g"}}""" else "null"}}"""

    @Test fun followedEdges() {
        val json = """{"data":{"currentUser":{"id":"9","followedLiveUsers":{"edges":[
            {"cursor":"1","node":${user("small", 5)}},
            {"cursor":"2","node":${user("big", 50000)}},
            {"cursor":"3","node":${user("offline", 10, live = false)}},
            {"cursor":"4","node":null},
            {"cursor":"5","node":${user(null, 7)}},
            {"cursor":"6","node":${user("Mid", 300)}},
            {"cursor":"7","node":${user("big", 1)}},
            {"cursor":"8","node":${user("noid", 9, id = null)}},
            {"cursor":"9","node":${user("nocount", null)}}
        ]}}}}"""
        val list = DualUsher.parseFollowed(json)!!
        assertEquals(listOf("big", "mid", "small", "nocount"), list.map { it.login })
        val big = list[0]
        assertEquals("id_big", big.id)
        assertEquals("BIG", big.displayName)
        assertEquals(50000, big.viewers)
        assertEquals("t big", big.title)
        assertEquals("g", big.game)
        assertNull(list.last().viewers)
    }

    @Test fun followedNodes() {
        val json = """{"data":{"currentUser":{"followedLiveUsers":{"nodes":[${user("a", 1)},${user("b", 2)},${user("c", 2)}]}}}}"""
        assertEquals(listOf("b", "c", "a"), DualUsher.parseFollowed(json)!!.map { it.login })
    }

    @Test fun followedDisplayNameFallsBackToLogin() {
        val json = """{"data":{"currentUser":{"followedLiveUsers":{"nodes":[{"id":"1","login":"quiet","displayName":null,"stream":{"id":"2"}}]}}}}"""
        val l = DualUsher.parseFollowed(json)!!.single()
        assertEquals("quiet", l.displayName)
        assertNull(l.title)
        assertNull(l.game)
    }

    @Test fun followedEmptyCases() {
        assertNull(DualUsher.parseFollowed("""{"data":{"currentUser":null}}"""))
        assertNull(DualUsher.parseFollowed("""{"errors":[{"message":"unauthorized"}],"data":null}"""))
        assertTrue(DualUsher.parseFollowed("""{"data":{"currentUser":{"followedLiveUsers":{"edges":[]}}}}""")!!.isEmpty())
        assertNull(DualUsher.parseFollowed("""{"data":{"currentUser":{"followedLiveUsers":null}}}"""))
        assertNull(DualUsher.parseFollowed("not json"))
        assertNull(DualUsher.parseFollowed(""))
    }

    // --- typed names ---

    @Test fun cleanLoginAccepts() {
        val cases = mapOf(
            "Name" to "name",
            "  some_Name42  " to "some_name42",
            "@name" to "name",
            "#name" to "name",
            "twitch.tv/name" to "name",
            "www.twitch.tv/Name" to "name",
            "https://www.twitch.tv/name?x=1" to "name",
            "https://m.twitch.tv/name/videos" to "name",
            "http://twitch.tv/name#chat" to "name",
            "HTTPS://WWW.TWITCH.TV/NAME" to "name",
            "twitch://stream/name" to "name",
            "ab" to "ab",
            "a".repeat(25) to "a".repeat(25),
        )
        for ((input, want) in cases) assertEquals(input, want, DualUsher.cleanLogin(input))
    }

    @Test fun cleanLoginRejects() {
        for (input in listOf("", "   ", "a", "a".repeat(26), "two words", "name!", "na-me", "https://example.com/name",
            "eviltwitch.tv/name", "twitch.tv/", "https://www.twitch.tv/", "name/extra", "@", "caf" + 0xE9.toChar()))
            assertNull(input, DualUsher.cleanLogin(input))
    }

    @Test fun missingChannelIsTold() {
        assertTrue(DualUsher.userMissing("""{"data":{"streamPlaybackAccessToken":null,"user":null}}"""))
        assertFalse(DualUsher.userMissing("""{"data":{"user":{"id":"1","login":"a"}}}"""))
        assertFalse(DualUsher.userMissing("""{"errors":[{"message":"service timeout"}]}"""))
        assertFalse(DualUsher.userMissing("not json"))
    }
}
