package tv.purple.xp

import java.util.Locale

/**
 * A small language guesser for chat lines, with no model to download: the writing system first
 * (Hangul, kana, Han, Arabic, Cyrillic and a few more say a lot on their own), then, for Latin and
 * Cyrillic text, lists of each language's most frequent words and a few letters only some
 * languages use.
 *
 * Twitch chat is noisy: emotes, names, links, English slang in every language, one word lines. So
 * it is cautious on purpose. It drops what is not language (links, @names, emote looking words in
 * CamelCase or all caps, numbers, chat slang everyone uses), and says [UNKNOWN] for anything short
 * or unclear rather than guess. A wrong guess costs a pointless (or silly) translation; "unknown"
 * only costs a line left as it is.
 *
 * Plain JVM code, tested by LangGuessTest.
 */
object LangGuess {

    const val UNKNOWN = "unknown"

    /** [lang] is a language code ("en", "es", ...) or [UNKNOWN]; [confidence] is from 0 to 1. */
    data class Result(val lang: String, val confidence: Float) {
        val known: Boolean get() = lang != UNKNOWN
    }

    private val NONE = Result(UNKNOWN, 0f)

    /** The languages this can name, for the settings page and tests. */
    val LANGS: Set<String> = setOf("en", "es", "pt", "de", "fr", "it", "nl", "pl", "tr", "ru", "uk",
        "zh", "ja", "ko", "ar", "fa", "he", "el", "hi", "th")

    /** Letters needed before Latin or Cyrillic text is judged at all. */
    private const val MIN_LETTERS = 8
    /** Words from the lists needed for a Latin or Cyrillic guess. */
    private const val MIN_HITS = 2

    fun guess(text: String): Result {
        if (text.isBlank()) return NONE
        val words = clean(text)
        if (words.isEmpty()) return NONE
        // Count letters by writing system.
        var latin = 0; var cyr = 0; var han = 0; var kana = 0; var hangul = 0; var arabic = 0
        var hebrew = 0; var greek = 0; var deva = 0; var thai = 0; var persian = 0
        for (w in words) {
            var i = 0
            while (i < w.length) {
                val cp = w.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                when (Character.UnicodeScript.of(cp)) {
                    Character.UnicodeScript.LATIN -> latin++
                    Character.UnicodeScript.CYRILLIC -> cyr++
                    Character.UnicodeScript.HAN -> han++
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                    Character.UnicodeScript.HANGUL -> hangul++
                    Character.UnicodeScript.ARABIC -> { arabic++; if (cp in PERSIAN) persian++ }
                    Character.UnicodeScript.HEBREW -> hebrew++
                    Character.UnicodeScript.GREEK -> greek++
                    Character.UnicodeScript.DEVANAGARI -> deva++
                    Character.UnicodeScript.THAI -> thai++
                    else -> {}
                }
            }
        }
        val total = latin + cyr + han + kana + hangul + arabic + hebrew + greek + deva + thai
        if (total == 0) return NONE
        // Scripts where one character carries a lot: two or three are enough.
        val cjk = han + kana + hangul
        if (cjk * 2 > total && cjk >= 2) {
            val share = cjk.toFloat() / total
            return when {
                hangul >= kana && hangul >= han -> Result("ko", conf(share, hangul, 3))
                // Japanese mixes kanji with kana; Chinese has no kana at all.
                kana > 0 && kana * 5 >= cjk -> Result("ja", conf(share, cjk, 3))
                kana == 0 -> Result("zh", conf(share, han, 3))
                else -> NONE
            }
        }
        for ((n, code) in listOf(arabic to "ar", hebrew to "he", greek to "el", deva to "hi", thai to "th")) {
            if (n * 2 > total && n >= 4) {
                // Persian is written with Arabic letters plus four of its own.
                if (code == "ar" && persian > 0) return Result("fa", conf(n.toFloat() / total, n, 6) * 0.8f)
                return Result(code, conf(n.toFloat() / total, n, 6))
            }
        }
        if (cyr * 2 > total) return byWords(words, cyr, CYRILLIC_LISTS, CYRILLIC_HINTS)
        if (latin * 2 > total) return byWords(words, latin, LATIN_LISTS, LATIN_HINTS)
        return NONE
    }

    /** Confidence from how much of the line is in the script and how many letters there are. */
    private fun conf(share: Float, n: Int, enough: Int): Float =
        (share * minOf(1f, n.toFloat() / enough)).coerceIn(0f, 1f)

    private fun byWords(words: List<String>, letters: Int, lists: Map<String, Set<String>>,
                        hints: Map<String, String>): Result {
        if (letters < MIN_LETTERS) return NONE
        val score = HashMap<String, Float>()
        val hitsBest = HashMap<String, Int>()
        for (w in words) {
            for ((lang, set) in lists) if (w in set) {
                score[lang] = (score[lang] ?: 0f) + 1f
                hitsBest[lang] = (hitsBest[lang] ?: 0) + 1
            }
            // Letters only a few languages use. Half a word each, once per word.
            for ((lang, chars) in hints) if (w.any { it in chars }) score[lang] = (score[lang] ?: 0f) + 0.5f
        }
        val ranked = score.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return NONE
        val second = ranked.getOrNull(1)?.value ?: 0f
        val hits = hitsBest[best.key] ?: 0
        // At least two listed words, and clearly ahead of the runner up.
        if (hits < MIN_HITS && !(hits >= 1 && best.value >= 2f)) return NONE
        if (best.value - second < 1f) return NONE
        // How much of the line the best language explains, times how far ahead it is.
        val cover = minOf(1f, best.value / words.size)
        val lead = (best.value - second) / best.value
        val c = (0.4f + 0.6f * cover) * lead * minOf(1f, words.size / 6f)
        return Result(best.key, c.coerceIn(0f, 1f))
    }

    /** Lower case words with links, @names, emote looking words, numbers and chat slang removed. */
    internal fun clean(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split(SPLIT)) {
            if (raw.isEmpty()) continue
            if (raw.startsWith("@") || raw.startsWith("http") || raw.startsWith("www.") || '/' in raw) continue
            val w = raw.trim { !Character.isLetterOrDigit(it) && it != '\'' }
            if (w.isEmpty()) continue
            if (w.any { it.isDigit() }) continue
            if (looksLikeEmote(w)) continue
            val l = w.lowercase(Locale.ROOT).trim('\'')
            if (l.isEmpty() || l in SLANG || isLaugh(l)) continue
            out.add(l)
        }
        return out
    }

    /**
     * Twitch emotes are mostly CamelCase (PogChamp, monkaS, catJAM) or all caps (LUL, KEKW). Real
     * words are seldom either, apart from shouting, which then counts for nothing; that is fine.
     * Only Latin letters are judged: other scripts have no case or no emotes like this.
     */
    private fun looksLikeEmote(w: String): Boolean {
        if (w.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) return false
        val letters = w.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return true
        // An upper case letter after a lower case one: catJAM, monkaS, forsenE.
        for (i in 1 until w.length) if (w[i].isUpperCase() && w[i - 1].isLowerCase()) return true
        return w.lowercase(Locale.ROOT) in EMOTES
    }

    /** "hahaha", "jajaja", "kkkk", "xddd" and friends say nothing about the language. */
    private fun isLaugh(w: String): Boolean {
        if (w.length >= 3 && w.all { it == 'k' }) return true
        if (w.length >= 4 && LAUGH.matches(w)) return true
        return false
    }

    private val SPLIT = Regex("[\\s]+")
    private val LAUGH = Regex("(?:[hjxa]a?|[hj]e|ha|ja|je|he|ah|eh)+[hjx]?")
    private val PERSIAN = setOf(0x067e, 0x0686, 0x0698, 0x06af)

    /** Slang and words used in chat in every language, so they never count for any one. */
    private val SLANG = setOf(
        "lol", "lmao", "lmfao", "rofl", "xd", "xdd", "gg", "ggs", "wp", "ez", "omg", "wtf", "pog", "poggers",
        "kek", "kekw", "ok", "okay", "oke", "yes", "no", "si", "ya", "ja", "nah", "brb", "afk", "gl", "hf",
        "glhf", "ty", "thx", "pls", "plz", "bro", "bruh", "sus", "hype", "stream", "streamer", "chat", "clip",
        "sub", "subs", "follow", "raid", "mod", "mods", "emote", "emotes", "ban", "hi", "hey", "hello",
        "bye", "lul", "kappa", "o7", "w", "l", "f", "x", "oh", "ah", "eh", "uh", "hm", "hmm", "wow"
    )

    /** Common emote names that are plain lower case. */
    private val EMOTES = setOf("pepega", "sadge", "copium", "hopium", "aware", "clueless", "monka", "pogu",
        "pepelaugh", "omegalul", "lulw", "residentsleeper", "biblethump", "kreygasm", "jebaited")

    private fun words(s: String): Set<String> = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val EN = words(
        "the be to of and a in that have it for not on with he as you do at this but his by from they we " +
        "say her she or an will my one all would there their what so up out if about who get which go me " +
        "when make can like time just him know take people into year your good some could them see other " +
        "than then now look only come its over think also back after use two how our work first well way " +
        "even new want because any these give day most us is are was were been has had did does doing " +
        "i'm don't can't it's that's you're isn't didn't won't i im dont cant thats youre why where " +
        "really very much thing something nothing never always game play playing gonna wanna got love " +
        "here there's need should please thanks thank sorry right yeah yep " +
        // Short English chat words that are also on other lists (German "man", "am", "hat", ...),
        // so a line like "nice hat man" is not taken for German.
        "man am um hat war bin also art die so in an hand wind was is nice great stuff bro dude lol lmao"
    )
    private val ES = words(
        "de la que el en y a los se del las un por con no una su para es al lo como más pero sus le ya " +
        "o este sí porque esta entre cuando muy sin sobre también me hasta hay donde quien desde todo " +
        "nos durante todos uno les ni contra otros ese eso ante ellos e esto mí antes algunos qué unos " +
        "yo otro otras otra él tanto esa estos mucho quienes nada muchos cual poco ella estar estas " +
        "algunas algo nosotros mi mis tú te ti tu tus ellas vosotros os mío mía tuyo suyo bien está " +
        "estoy estás son soy eres fue era tiene tengo hace hacer puedo quiero creo vamos hola gracias " +
        "jaja pues bueno así aquí ahora cómo dónde verdad juego jugar amigo"
    )
    private val PT = words(
        "de a o que e do da em um para é com não uma os no se na por mais as dos como mas foi ao ele " +
        "das tem à seu sua ou ser quando muito há nos já está eu também só pelo pela até isso ela entre " +
        "era depois sem mesmo aos ter seus quem nas me esse eles estão você tinha foram essa num nem " +
        "suas meu às minha têm numa pelos elas havia seja qual será nós tenho lhe deles essas esses " +
        "pelas este fosse dele tu te vocês vos lhes meus minhas teu tua teus tuas nosso nossa isto " +
        "aqui agora então tá né vai vou tô obrigado obrigada valeu cara mano jogo jogar porque onde " +
        "fazer pode bom tudo nada coisa gente"
    )
    private val DE = words(
        "der die und in den von zu das mit sich des auf für ist im dem nicht ein eine als auch es an " +
        "werden aus er hat dass sie nach wird bei einer um am sind noch wie einem über einen so zum war " +
        "haben nur oder aber vor zur bis mehr durch man sein wurde sei hatte kann gegen vom können " +
        "schon wenn habe seine ihre dann unter wir soll ich eines du mich mir dich dir uns euch ihr " +
        "was wer warum nein jetzt hier gut sehr bin bist hast mal doch ganz immer nichts heute danke " +
        "bitte spiel spielen gibt geht weiß glaube wirklich"
    )
    private val FR = words(
        "de la le et les des en un du une que est pour qui dans par plus pas au sur ne se ce il sont " +
        "avec ou son aux d'un cette d'une ont ses mais comme on tout nous sa été aussi leur bien peut " +
        "ces y deux ans être fait elle très je tu vous moi toi lui eux mon ma mes ton ta tes notre " +
        "votre c'est j'ai suis es sommes êtes ai as avons avez quoi pourquoi comment où quand oui non " +
        "merci bonjour salut aussi encore jamais rien toujours ici là ça faire jeu jouer trop mdr " +
        "vraiment déjà parce alors"
    )
    private val IT = words(
        "di e il la che in a per un è del non so sono le con una si da al lo dei della gli come più " +
        "ma ha nel anche alla questo se essere o tra perché ci delle cosa io tu lui lei noi voi loro " +
        "mi ti ci vi mio mia tuo tua suo sua nostro vostro sei siamo siete ho hai abbiamo avete hanno " +
        "era fare fatto molto tutto niente sempre mai qui adesso ora ciao grazie bene quando dove " +
        "chi quale questa quello quella gioco giocare allora proprio davvero"
    )
    private val NL = words(
        "de en van ik te dat die in een hij het niet zijn is was op aan met als voor had er maar om " +
        "hem dan zou of wat mijn men dit zo door over ze zich bij ook tot je mij uit der daar haar " +
        "naar heb hoe heeft hebben deze u want nog zal me zij nu ge geen omdat iets worden toch al " +
        "waren veel meer doen toen moet ben zonder kan hun dus alles onder ja eens hier wie werd " +
        "altijd doch wordt wezen kunnen ons zelf tegen na reeds wil kon niets uw iemand geweest " +
        "andere bedankt dank jij jullie spel spelen echt"
    )
    private val PL = words(
        "i w nie na się z do to że a o jak ale po co tak za od jest jak go już tylko mnie mi przez " +
        "być tym ten czy jego może jej ja ty on ona my wy oni ich nam wam was nas jestem jesteś są " +
        "był była było mam masz ma mamy macie mają bardzo jeszcze teraz tutaj tu gdzie kiedy dlaczego " +
        "bo więc no tak nic wszystko zawsze nigdy dzięki dziękuję cześć dobrze gra grać kurwa serio"
    )
    private val TR = words(
        "bir ve bu da de için ile çok ne mi ben sen o biz siz onlar var yok gibi daha ama değil " +
        "kadar sonra olarak her şey en ki ya veya mı mu mü nasıl neden niye nerede ne zaman şimdi " +
        "burada orada evet hayır teşekkürler sağol merhaba selam iyi güzel oyun oynamak abi kanka " +
        "olur oldu olsun benim senin onun bizim sizin artık hep hiç böyle şöyle diye bile çünkü"
    )
    private val RU = words(
        "и в не на я что он с а как это по но они к у мы из за вы так же от все она то бы был мне " +
        "о только его еще ещё нет да ты меня уже для вот если когда или ну даже было там чтобы здесь " +
        "тут кто их была будет можно нас при них очень тоже без сейчас потом себя ничего этот эта " +
        "эти почему где всё всегда тебя тебе ему ей им какой какая просто спасибо привет хорошо " +
        "конечно может быть есть надо игра играть типа короче блин реально"
    )
    private val UK = words(
        "і в не на я що він з а як це по але вони до у ми із за ви так же від все вона то б був мені " +
        "о тільки його ще ні так ти мене вже для ось якщо коли або ну навіть було там щоб тут хто їх " +
        "була буде можна нас при них дуже теж без зараз потім себе нічого цей ця ці чому де завжди " +
        "тебе тобі йому їй їм який яка просто дякую привіт добре звичайно може бути є треба гра грати"
    )

    private val LATIN_LISTS: Map<String, Set<String>> = mapOf(
        "en" to EN, "es" to ES, "pt" to PT, "de" to DE, "fr" to FR, "it" to IT, "nl" to NL, "pl" to PL, "tr" to TR
    )
    private val CYRILLIC_LISTS: Map<String, Set<String>> = mapOf("ru" to RU, "uk" to UK)

    /** Letters that point at a language (some at two). */
    private val LATIN_HINTS: Map<String, String> = mapOf(
        "es" to "ñ¿¡",
        "pt" to "ãõç",
        "de" to "ßäöü",
        "fr" to "çœèêëîïûù",
        "it" to "ìò",
        "pl" to "łąęśźżćń",
        "tr" to "ğşıçöü"
    )
    private val CYRILLIC_HINTS: Map<String, String> = mapOf(
        "ru" to "ыэъё",
        "uk" to "іїєґ"
    )
}
