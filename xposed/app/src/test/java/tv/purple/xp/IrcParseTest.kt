package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines below follow what irc-ws.chat.twitch.tv sends with the tags and commands capabilities;
 *  ids and timestamps are made up. */
class IrcParseTest {

    private val privmsg = "@badge-info=subscriber/14;badges=subscriber/12,premium/1;client-nonce=4c3d2b1a;" +
        "color=#1E90FF;display-name=Viewer_One;emotes=25:0-4,12-16/1902:6-10;first-msg=0;flags=;" +
        "id=8f2b6a1e-1111-4c2d-9e3f-0a1b2c3d4e5f;mod=0;returning-chatter=0;room-id=71092938;subscriber=1;" +
        "tmi-sent-ts=1793739191108;turbo=0;user-id=40012345;user-type= " +
        ":viewer_one!viewer_one@viewer_one.tmi.twitch.tv PRIVMSG #SomeChannel :Kappa Keepo Kappa"

    /** One character outside the Basic Multilingual Plane, as the surrogate pair Java stores it. */
    private fun cp(codePoint: Int) = String(Character.toChars(codePoint))

    @Test fun privmsgWithTags() {
        val m = IrcParse.parse(privmsg)!!
        assertEquals("PRIVMSG", m.command)
        assertEquals("somechannel", m.channel)
        assertEquals("viewer_one", m.nick)
        assertEquals("Kappa Keepo Kappa", m.text)
        assertEquals("Viewer_One", m.tag("display-name"))
        assertEquals("#1E90FF", m.tag("color"))
        assertEquals("71092938", m.tag("room-id"))
        assertNull("empty tag reads as absent", m.tag("flags"))
        assertNull(m.tag("user-type"))
        assertEquals("", m.tags["flags"])
        assertNull(m.tag("no-such-tag"))
        assertEquals(listOf("subscriber" to "12", "premium" to "1"), IrcParse.badges(m.tag("badges")))
        assertEquals(
            listOf(EmoteRange("25", 0, 5), EmoteRange("1902", 6, 11), EmoteRange("25", 12, 17)),
            IrcParse.emoteRanges(m.tag("emotes"), m.text!!))
    }

    @Test fun emojiBeforeEmoteShiftsTheRange() {
        // U+1F602 is one code point but two UTF-16 units; Twitch counts it as one.
        val text = cp(0x1F602) + " hi Kappa"
        assertEquals(11, text.length)
        val line = "@badges=;color=;display-name=a;emotes=25:5-9;id=x;room-id=1;user-id=2 " +
            ":a!a@a.tmi.twitch.tv PRIVMSG #chan :$text"
        val m = IrcParse.parse(line)!!
        assertEquals(text, m.text)
        val r = IrcParse.emoteRanges(m.tag("emotes"), m.text!!).single()
        assertEquals(EmoteRange("25", 6, 11), r)
        assertEquals("Kappa", text.substring(r.start, r.end))
    }

    @Test fun severalEmojiAndAFlagBetweenEmotes() {
        // A flag is two code points (four UTF-16 units), and an emote sits after each emoji run.
        val text = "Kappa " + cp(0x1F1EB) + cp(0x1F1F7) + " LUL " + cp(0x1F600) + cp(0x1F600) + " PogChamp"
        // Code points: Kappa 0-4, space 5, flag 6-7, space 8, LUL 9-11, space 12, faces 13-14, space 15, PogChamp 16-23
        val ranges = IrcParse.emoteRanges("25:0-4/425618:9-11/305954156:16-23", text)
        assertEquals(listOf("Kappa", "LUL", "PogChamp"), ranges.map { text.substring(it.start, it.end) })
    }

    @Test fun emoteRangesDropsBadInput() {
        val text = "Kappa hi"
        assertTrue(IrcParse.emoteRanges(null, text).isEmpty())
        assertTrue(IrcParse.emoteRanges("", text).isEmpty())
        assertTrue("past the end", IrcParse.emoteRanges("25:6-10", text).isEmpty())
        assertTrue("backwards", IrcParse.emoteRanges("25:4-0", text).isEmpty())
        assertTrue("not numbers", IrcParse.emoteRanges("25:a-b", text).isEmpty())
        assertTrue("no ranges", IrcParse.emoteRanges("25:", text).isEmpty())
        assertEquals(listOf(EmoteRange("25", 0, 5)), IrcParse.emoteRanges("25:0-4,0-2/emotesv2_abc:3-5", text))
        assertEquals(listOf(EmoteRange("emotesv2_1a2b3c", 0, 5)), IrcParse.emoteRanges("emotesv2_1a2b3c:0-4", text))
    }

    @Test fun actionLine() {
        val line = "@badges=;color=#FF69B4;display-name=Waver;emotes=25:6-10;id=y;room-id=1;user-id=3 " +
            ":waver!waver@waver.tmi.twitch.tv PRIVMSG #chan :\u0001ACTION waves Kappa\u0001"
        val m = IrcParse.parse(line)!!
        val words = IrcParse.action(m.text!!)
        assertEquals("waves Kappa", words)
        val r = IrcParse.emoteRanges(m.tag("emotes"), words!!).single()
        assertEquals("Kappa", words.substring(r.start, r.end))
        assertNull(IrcParse.action("just text"))
        assertNull(IrcParse.action("\u0001ACTIONwaves\u0001"))
        assertEquals("", IrcParse.action("\u0001ACTION\u0001"))
        assertEquals("no closing marker", "waves", IrcParse.action("\u0001ACTION waves"))
    }

    @Test fun usernoticeSub() {
        val line = "@badge-info=subscriber/1;badges=subscriber/0,premium/1;color=;display-name=NewSub;emotes=;" +
            "flags=;id=0d7c5a7e-2222-4b33-8c44-9d55e66f7a88;login=newsub;mod=0;msg-id=sub;" +
            "msg-param-cumulative-months=1;msg-param-months=0;msg-param-multimonth-duration=1;" +
            "msg-param-multimonth-tenure=0;msg-param-should-share-streak=0;" +
            "msg-param-sub-plan-name=Channel\\sSubscription\\s(somechannel);msg-param-sub-plan=Prime;" +
            "msg-param-was-gifted=false;room-id=71092938;subscriber=1;" +
            "system-msg=NewSub\\ssubscribed\\swith\\sPrime.;tmi-sent-ts=1793739192000;user-id=40099999;" +
            "user-type=;vip=0 :tmi.twitch.tv USERNOTICE #somechannel"
        val m = IrcParse.parse(line)!!
        assertEquals("USERNOTICE", m.command)
        assertEquals("somechannel", m.channel)
        assertNull("server line has no chatter", m.nick)
        assertNull("a sub without a message has no text", m.text)
        assertEquals("sub", m.tag("msg-id"))
        assertEquals("NewSub subscribed with Prime.", m.tag("system-msg"))
        assertEquals("Channel Subscription (somechannel)", m.tag("msg-param-sub-plan-name"))
        assertNull(m.tag("color"))
    }

    @Test fun usernoticeResubWithMessage() {
        val line = "@badges=subscriber/24;display-name=Old;msg-id=resub;msg-param-cumulative-months=25;" +
            "system-msg=Old\\ssubscribed\\sat\\sTier\\s1.\\sThey've\\ssubscribed\\sfor\\s25\\smonths!;" +
            "room-id=1;user-id=4 :tmi.twitch.tv USERNOTICE #chan :Still here, love the stream"
        val m = IrcParse.parse(line)!!
        assertEquals("Still here, love the stream", m.text)
        assertEquals("Old subscribed at Tier 1. They've subscribed for 25 months!", m.tag("system-msg"))
    }

    @Test fun usernoticeRaid() {
        val line = "@badge-info=;badges=partner/1;color=#9ACD32;display-name=Raider;emotes=;flags=;" +
            "id=3c1d2e3f-3333-4a44-8b55-6c77d88e99f0;login=raider;mod=0;msg-id=raid;" +
            "msg-param-displayName=Raider;msg-param-login=raider;" +
            "msg-param-profileImageURL=https://static-cdn.jtvnw.net/jtv_user_pictures/raider-profile_image-70x70.png;" +
            "msg-param-viewerCount=42;room-id=71092938;subscriber=0;" +
            "system-msg=42\\sraiders\\sfrom\\sRaider\\shave\\sjoined!;tmi-sent-ts=1793739193000;" +
            "user-id=40077777;user-type=;vip=0 :tmi.twitch.tv USERNOTICE #somechannel"
        val m = IrcParse.parse(line)!!
        assertEquals("raid", m.tag("msg-id"))
        assertEquals("42", m.tag("msg-param-viewerCount"))
        assertEquals("42 raiders from Raider have joined!", m.tag("system-msg"))
        assertEquals("https://static-cdn.jtvnw.net/jtv_user_pictures/raider-profile_image-70x70.png",
            m.tag("msg-param-profileImageURL"))
        assertEquals(listOf("partner" to "1"), IrcParse.badges(m.tag("badges")))
    }

    @Test fun clearchatWithTarget() {
        val line = "@ban-duration=600;room-id=71092938;target-user-id=40012345;tmi-sent-ts=1793739194000 " +
            ":tmi.twitch.tv CLEARCHAT #somechannel :viewer_one"
        val m = IrcParse.parse(line)!!
        assertEquals("CLEARCHAT", m.command)
        assertEquals("somechannel", m.channel)
        assertEquals("viewer_one", m.text)
        assertEquals("600", m.tag("ban-duration"))
        assertEquals("40012345", m.tag("target-user-id"))
    }

    @Test fun clearchatWholeChat() {
        val m = IrcParse.parse("@room-id=71092938;tmi-sent-ts=1793739195000 :tmi.twitch.tv CLEARCHAT #somechannel")!!
        assertEquals("CLEARCHAT", m.command)
        assertEquals("somechannel", m.channel)
        assertNull(m.text)
        assertNull(m.tag("target-user-id"))
    }

    @Test fun clearmsg() {
        val line = "@login=viewer_one;room-id=;target-msg-id=8f2b6a1e-1111-4c2d-9e3f-0a1b2c3d4e5f;" +
            "tmi-sent-ts=1793739196000 :tmi.twitch.tv CLEARMSG #somechannel :Kappa Keepo Kappa"
        val m = IrcParse.parse(line)!!
        assertEquals("CLEARMSG", m.command)
        assertEquals("8f2b6a1e-1111-4c2d-9e3f-0a1b2c3d4e5f", m.tag("target-msg-id"))
        assertEquals("viewer_one", m.tag("login"))
        assertNull(m.tag("room-id"))
        assertEquals("Kappa Keepo Kappa", m.text)
    }

    @Test fun roomstate() {
        val m = IrcParse.parse("@emote-only=0;followers-only=-1;r9k=0;room-id=71092938;slow=0;subs-only=0 " +
            ":tmi.twitch.tv ROOMSTATE #somechannel")!!
        assertEquals("ROOMSTATE", m.command)
        assertEquals("somechannel", m.channel)
        assertEquals("71092938", m.tag("room-id"))
        assertEquals("-1", m.tag("followers-only"))
        assertNull(m.text)
    }

    @Test fun userstate() {
        val m = IrcParse.parse("@badge-info=;badges=moderator/1;color=#8A2BE2;display-name=Me_Myself;" +
            "emote-sets=0,300374282,c3a1b2d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d;mod=1;subscriber=0;user-type=mod " +
            ":tmi.twitch.tv USERSTATE #somechannel")!!
        assertEquals("USERSTATE", m.command)
        assertEquals("somechannel", m.channel)
        assertEquals("#8A2BE2", m.tag("color"))
        assertEquals("Me_Myself", m.tag("display-name"))
        assertEquals(listOf("moderator" to "1"), IrcParse.badges(m.tag("badges")))
        assertNull(m.text)
    }

    @Test fun notices() {
        val ch = IrcParse.parse("@msg-id=msg_channel_suspended :tmi.twitch.tv NOTICE #gone :" +
            "This channel does not exist or has been suspended.")!!
        assertEquals("NOTICE", ch.command)
        assertEquals("gone", ch.channel)
        assertEquals("msg_channel_suspended", ch.tag("msg-id"))
        assertEquals("This channel does not exist or has been suspended.", ch.text)

        val auth = IrcParse.parse(":tmi.twitch.tv NOTICE * :Login authentication failed")!!
        assertEquals("NOTICE", auth.command)
        assertNull("a sign-in notice belongs to no chat", auth.channel)
        assertEquals("Login authentication failed", auth.text)
        assertTrue(auth.tags.isEmpty())
    }

    @Test fun pingPongReconnect() {
        val ping = IrcParse.parse("PING :tmi.twitch.tv")!!
        assertEquals("PING", ping.command)
        assertNull(ping.prefix)
        assertEquals(listOf("tmi.twitch.tv"), ping.params)
        assertNull(ping.channel)
        assertNull(ping.text)

        val pong = IrcParse.parse(":tmi.twitch.tv PONG tmi.twitch.tv :tmi.twitch.tv")!!
        assertEquals("PONG", pong.command)
        assertEquals(listOf("tmi.twitch.tv", "tmi.twitch.tv"), pong.params)

        val rc = IrcParse.parse(":tmi.twitch.tv RECONNECT\r\n")!!
        assertEquals("RECONNECT", rc.command)
        assertTrue(rc.params.isEmpty())
        assertEquals("tmi.twitch.tv", rc.prefix)
    }

    @Test fun welcomeJoinAndNames() {
        val w = IrcParse.parse(":tmi.twitch.tv 001 justinfan12345 :Welcome, GLHF!")!!
        assertEquals("001", w.command)
        assertEquals("justinfan12345", w.params[0])
        assertNull(w.channel)

        val join = IrcParse.parse(":justinfan12345!justinfan12345@justinfan12345.tmi.twitch.tv JOIN #somechannel")!!
        assertEquals("JOIN", join.command)
        assertEquals("justinfan12345", join.nick)
        assertEquals("somechannel", join.channel)

        val names = IrcParse.parse(":justinfan12345.tmi.twitch.tv 353 justinfan12345 = #somechannel :justinfan12345")!!
        assertEquals("somechannel", names.channel)
        assertNull("a server name is not a chatter", names.nick)

        val cap = IrcParse.parse(":tmi.twitch.tv CAP * ACK :twitch.tv/tags twitch.tv/commands")!!
        assertEquals(listOf("*", "ACK", "twitch.tv/tags twitch.tv/commands"), cap.params)
    }

    @Test fun escapedTagValues() {
        assertEquals("a b;c\\d\re\nf", IrcParse.unescapeTag("a\\sb\\:c\\\\d\\re\\nf"))
        assertEquals("unknown escapes stand for themselves", "xy", IrcParse.unescapeTag("\\xy"))
        assertEquals("a lone trailing backslash is dropped", "end", IrcParse.unescapeTag("end\\"))
        assertEquals("plain", IrcParse.unescapeTag("plain"))
        val m = IrcParse.parse("@system-msg=semi\\:colon\\sand\\sspace;empty;flag= :tmi.twitch.tv USERNOTICE #c")!!
        assertEquals("semi;colon and space", m.tag("system-msg"))
        assertEquals("", m.tags["empty"])
        assertEquals("", m.tags["flag"])
    }

    @Test fun oddButValidShapes() {
        val noTrailing = IrcParse.parse(":a!a@a.tmi.twitch.tv PRIVMSG #c hello")!!
        assertEquals("hello", noTrailing.text)
        val emptyTrailing = IrcParse.parse(":a!a@a.tmi.twitch.tv PRIVMSG #c :")!!
        assertEquals("", emptyTrailing.text)
        val colonsInside = IrcParse.parse(":a!a@a.tmi.twitch.tv PRIVMSG #c :look: https://twitch.tv :)")!!
        assertEquals("look: https://twitch.tv :)", colonsInside.text)
        val extraSpaces = IrcParse.parse("@a=1  :srv.tmi.twitch.tv   NOTICE   #c   :hi  there")!!
        assertEquals("NOTICE", extraSpaces.command)
        assertEquals("hi  there", extraSpaces.text)
    }

    @Test fun rejectsLinesWithoutACommand() {
        assertNull(IrcParse.parse(""))
        assertNull(IrcParse.parse("\r\n"))
        assertNull(IrcParse.parse("@a=b"))
        assertNull(IrcParse.parse("@a=b :tmi.twitch.tv"))
        assertNull(IrcParse.parse(":tmi.twitch.tv"))
    }

    @Test fun linesSplitsFrames() {
        assertEquals(listOf("PING :a", "PING :b"), IrcParse.lines("PING :a\r\nPING :b\r\n"))
        assertEquals(listOf("one", "two"), IrcParse.lines("one\ntwo"))
        assertEquals(listOf("solo"), IrcParse.lines("solo"))
        assertTrue(IrcParse.lines("").isEmpty())
        assertTrue(IrcParse.lines("\r\n\r\n").isEmpty())
    }

    @Test fun badgesTag() {
        assertTrue(IrcParse.badges(null).isEmpty())
        assertTrue(IrcParse.badges("").isEmpty())
        assertEquals(listOf("broadcaster" to "1", "subscriber" to "3012"), IrcParse.badges("broadcaster/1,subscriber/3012"))
        assertEquals(listOf("ok" to "1"), IrcParse.badges("broken,ok/1,/2,set/"))
        assertEquals(listOf("predictions" to "blue-1"), IrcParse.badges("predictions/blue-1"))
    }

    @Test fun cleanChannel() {
        assertEquals("somechannel", IrcParse.cleanChannel(" #SomeChannel "))
        assertEquals("a_b_1", IrcParse.cleanChannel("a_b_1"))
        assertNull(IrcParse.cleanChannel(null))
        assertNull(IrcParse.cleanChannel("#"))
        assertNull(IrcParse.cleanChannel("two words"))
        assertNull(IrcParse.cleanChannel("x\r\nJOIN #other"))
        assertNull(IrcParse.cleanChannel("a".repeat(26)))
    }

    @Test fun outgoingLines() {
        assertEquals("PRIVMSG #chan :hello there", IrcParse.privmsg("#Chan", "  hello there "))
        assertEquals("PRIVMSG #chan :\u0001ACTION waves\u0001", IrcParse.privmsg("chan", "/me waves"))
        assertEquals("line breaks cannot start a second command",
            "PRIVMSG #chan :hi PART #chan", IrcParse.privmsg("chan", "hi\r\nPART #chan"))
        assertEquals("PRIVMSG #chan :/mean thing", IrcParse.privmsg("chan", "/mean thing"))
        assertNull(IrcParse.privmsg("chan", "   "))
        assertNull(IrcParse.privmsg("chan", "/me"))
        assertNull(IrcParse.privmsg("chan", "/me   "))
        assertNull(IrcParse.privmsg("bad channel", "hi"))
        val back = IrcParse.parse(IrcParse.privmsg("chan", "/me waves")!!)!!
        assertEquals("waves", IrcParse.action(back.text!!))
    }
}
