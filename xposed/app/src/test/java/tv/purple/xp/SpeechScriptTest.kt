package tv.purple.xp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Most lines here are what the many-language model wrote for real Twitch streams. */
class SpeechScriptTest {

    @Test fun anyKeepsEverything() {
        val f = SpeechScript.Filter(SpeechScript.Mode.ANY)
        assertEquals("手前でおもろい", f.take("手前でおもろい", true, 0))
        assertEquals("ت", f.take("ت", true, 0))
    }

    @Test fun latinDropsOtherScripts() {
        val f = SpeechScript.Filter(SpeechScript.Mode.LATIN)
        assertEquals("lke may bagay n hndi iya lke may makesen kagaya nyan",
            f.take("lke may مga bagay n hndi iya lke may makesen kagaya nyan", true, 0))
        assertEquals("", f.take("ما تتعقس نباق ومتقلبطو", true, 0))
        assertEquals("", f.take("ل", true, 0))
        // Mostly noise: what is left of it is dropped too.
        assertEquals("", f.take("لwng  np gن ds لا", true, 0))
        assertEquals("ayaw ko pa naman", f.take("ayaw ko pa naman", false, 0))
        assertEquals("žinios trumpai lietuvoje", f.take("žinios trumpai lietuvoje", true, 0))
    }

    @Test fun autoLearnsTheStreamsScript() {
        val f = SpeechScript.Filter(SpeechScript.Mode.AUTO)
        var t = 0L
        fun take(s: String, fin: Boolean = true) = f.take(s, fin, t).also { t += 3_000 }
        // Nothing known yet: a short line waits for the next one in its script.
        assertEquals("", take("前でつらい"))
        assertEquals("ついましせて前はつい", take("ついましせて前はつい"))
        assertEquals("手前が低く読み得るとったらぼりわり手まへ", take("手前が低く読み得るとったらぼりわり手まへ"))
        // Noise in other scripts is left out.
        assertEquals("", take("ت"))
        assertEquals("", take("मएतस थमयद"))
        // In Japanese, written without spaces, only the stray letter goes.
        assertEquals("おれします", take("おれرします"))
        // Partial lines only follow what is known.
        assertEquals("", take("مبص", false))
        assertEquals("前にゆったら好いが", take("前にゆったら好いが", false))
    }

    @Test fun autoFollowsAStreamThatChangesLanguage() {
        val f = SpeechScript.Filter(SpeechScript.Mode.AUTO)
        var t = 0L
        fun take(s: String) = f.take(s, true, t).also { t += 3_000 }
        assertEquals("", take("このゲームは難しいです"))
        assertEquals("このゲームは難しいです本当に", take("このゲームは難しいです本当に"))
        // One long line in another script is a change of language on its own.
        assertEquals("okay chat we are going to try this boss one more time",
            take("okay chat we are going to try this boss one more time"))
        // Both are known now: either is kept.
        assertEquals("もう一回", take("もう一回"))
        assertEquals("lets go", take("lets go"))
    }

    @Test fun shortNoiseInANewScriptStaysOut() {
        val f = SpeechScript.Filter(SpeechScript.Mode.AUTO)
        var t = 0L
        fun take(s: String) = f.take(s, true, t).also { t += 3_000 }
        assertEquals("ayaw ko pa naman ibitinin mamaya kasi ano last one hour nalang",
            take("ayaw ko pa naman ibitinin mamaya kasi ano last one hour nalang"))
        assertEquals("", take("كد نت سي"))
        assertEquals("", take("لwng  np gن ds لا"))
        // Two Arabic lines in a row, but one is too short to count.
        assertEquals("", take("مومين"))
        // Too long after the one before.
        t += 60_000
        assertEquals("", take("كاو م جيبي"))
        // The stream's own language goes on as before.
        assertEquals("ano yng tawag na sa dalawa", take("ano yng tawag na sa dalawa"))
    }
}
