package tv.purple.xp

import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * Audio filters for the stream, aimed at talk streams: volume boost, auto volume, compressor,
 * limiter, voice clarity, bass, treble and rumble cut.
 *
 * Built only on Android's own DynamicsProcessing effect (Android 9+), which has an equaliser, a
 * compressor and a limiter in one: no DSP code of our own. It is attached to every audio track the
 * Twitch app creates (the player's), found by hooking AudioTrack's constructors, and only affects
 * Twitch's sound.
 */
object AudioFilters {

    const val KEY_ON = "af_on"

    class Fx(val key: String, val name: String, val help: String, val min: Int, val max: Int, val unit: String)

    val FX = listOf(
        Fx("boost", "Volume boost",
            "Makes the whole stream louder, for a quiet streamer or a quiet speaker. The limiter keeps it from distorting.",
            0, 12, " dB"),
        Fx("level", "Auto volume",
            "Evens out the volume over time: quiet talking comes up and shouting comes down, so you stop reaching for the volume buttons.",
            0, 100, "%"),
        Fx("comp", "Compressor",
            "Quickly squeezes loud and quiet moments closer together, for a fuller, more even voice. Stronger than Auto volume and faster to react.",
            0, 100, "%"),
        Fx("limit", "Limiter",
            "A ceiling for sudden loud sounds like screams, explosions and alerts, so they can't blast your ears. Higher means a lower ceiling.",
            0, 100, "%"),
        Fx("voice", "Voice clarity",
            "Brings voices forward: lifts the range speech lives in and trims the muddy low end, so words are easier to make out over game sound.",
            0, 100, "%"),
        Fx("bass", "Bass", "Turns the low end up or down.", -12, 12, " dB"),
        Fx("treble", "Treble", "Turns the high end up or down. Down softens a harsh mic or hiss.", -12, 12, " dB"),
        Fx("lowcut", "Rumble cut",
            "Removes deep rumble and hum: desk bumps, fans, mic handling and air conditioning.",
            0, 100, "%")
    )

    fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun amount(key: String) = Settings.getInt("af_$key", 0)
    fun setAmount(key: String, v: Int) = Settings.setInt("af_$key", v)
    fun resetAll() { for (f in FX) setAmount(f.key, 0) }

    fun anyActive(): Boolean = supported() && Settings.get(KEY_ON, true) && FX.any { amount(it.key) != 0 }

    // ---- attaching ----

    /** One effect per audio session the app plays on. */
    private val effects = HashMap<Int, Any>()

    fun install() {
        if (!supported()) return
        XposedBridge.hookAllConstructors(AudioTrack::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val t = p.thisObject as? AudioTrack ?: return
                runCatching { attach(t.audioSessionId) }.onFailure { log("AF attach failed: $it") }
            }
        })
        XposedBridge.hookAllMethods(AudioTrack::class.java, "release", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val t = p.thisObject as? AudioTrack ?: return
                runCatching { detach(t.audioSessionId) }
            }
        })
        log("AF audio filters installed")
    }

    private fun attach(session: Int) {
        if (session <= 0) return
        synchronized(effects) {
            if (effects.containsKey(session)) return
            val dp = create(session) ?: return
            effects[session] = dp
            log("AF effect on audio session $session")
            apply(dp as DynamicsProcessing)
        }
    }

    private fun detach(session: Int) {
        synchronized(effects) {
            (effects.remove(session) as? DynamicsProcessing)?.let { runCatching { it.release() } }
        }
    }

    private fun create(session: Int): DynamicsProcessing? {
        for (channels in intArrayOf(2, 1)) {
            val dp = runCatching {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, channels,
                    true, CUTS.size,   // equaliser
                    true, 1,           // one-band compressor
                    false, 0,          // no second equaliser
                    true               // limiter
                ).build()
                DynamicsProcessing(0, session, cfg)
            }.onFailure { if (channels == 1) log("AF could not create the effect: $it") }.getOrNull()
            if (dp != null) return dp
        }
        return null
    }

    /** True when another app (an equaliser or a rootless audio app) controls Twitch's sound
     *  effect slot, so these settings cannot take effect right now. */
    fun blocked(): Boolean = supported() && synchronized(effects) {
        effects.values.any { runCatching { !(it as DynamicsProcessing).hasControl() }.getOrDefault(false) }
    }

    /** Push the current settings to every attached effect. Called when a slider moves. */
    fun refresh() {
        if (!supported()) return
        synchronized(effects) { for (e in effects.values) runCatching { apply(e as DynamicsProcessing) } }
    }

    // ---- settings to effect parameters ----

    /** Equaliser band tops in Hz: rumble, bass, low mids, presence, treble, air. */
    private val CUTS = floatArrayOf(80f, 250f, 2000f, 5000f, 10000f, 20000f)

    private fun apply(dp: DynamicsProcessing) {
        val on = anyActive()
        dp.enabled = on
        if (!on) return
        val boost = amount("boost").coerceIn(0, 12).toFloat()
        val lvl = amount("level").coerceIn(0, 100) / 100f
        val cmp = amount("comp").coerceIn(0, 100) / 100f
        val lim = amount("limit").coerceIn(0, 100) / 100f
        val voice = amount("voice").coerceIn(0, 100) / 100f
        val bass = amount("bass").coerceIn(-12, 12).toFloat()
        val treble = amount("treble").coerceIn(-12, 12).toFloat()
        val low = amount("lowcut").coerceIn(0, 100) / 100f

        // Equaliser.
        val gains = floatArrayOf(
            bass * 0.6f - 24f * low,
            bass - 2.5f * voice,
            0f,
            5f * voice,
            treble + 1.5f * voice,
            treble
        )
        val eq = DynamicsProcessing.Eq(true, true, CUTS.size)
        for (i in CUTS.indices) eq.setBand(i, DynamicsProcessing.EqBand(true, CUTS[i], gains[i]))
        dp.setPreEqAllChannelsTo(eq)

        // Compressor: auto volume is slow and gentle, the compressor fast and firmer; together the
        // firmer settings win. Make-up gain brings the level back up after squeezing.
        var threshold = 0f; var ratio = 1f; var attack = 10f; var release = 200f; var makeup = 0f
        if (lvl > 0f || cmp > 0f) {
            val tA = -20f - 15f * lvl; val rA = 1f + 5f * lvl
            val tC = -12f - 18f * cmp; val rC = 1f + 7f * cmp
            threshold = if (lvl > 0f && cmp > 0f) minOf(tA, tC) else if (lvl > 0f) tA else tC
            ratio = maxOf(if (lvl > 0f) rA else 1f, if (cmp > 0f) rC else 1f)
            attack = (lvl * 40f + cmp * 5f) / (lvl + cmp)
            release = (lvl * 700f + cmp * 120f) / (lvl + cmp)
            makeup = (-threshold) * (1f - 1f / ratio) * 0.5f
        }
        val band = DynamicsProcessing.MbcBand(true, 20000f, attack, release, ratio, threshold, 6f,
            if (lvl > 0f) -55f else -90f,     // keep silence and hiss from being pulled up
            if (lvl > 0f) 2f else 1f, 0f, makeup)
        val mbc = DynamicsProcessing.Mbc(true, true, 1)
        mbc.setBand(0, band)
        dp.setMbcAllChannelsTo(mbc)

        // Limiter: the chosen ceiling, or a safety one whenever anything adds loudness.
        val ceiling = when {
            lim > 0f -> -1f - 11f * lim
            boost > 0f || makeup > 0f || gains.any { it > 0f } -> -0.5f
            else -> null
        }
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, ceiling != null, 0, 1f, 60f, 10f, ceiling ?: 0f, 0f))
        dp.setInputGainAllChannelsTo(boost)
    }
}
