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

    /** The slider's value, whether or not the effect is switched off. */
    fun rawAmount(key: String) = Settings.getInt("af_$key", 0)
    /** What the effect does right now: its value, or 0 while it is switched off. */
    fun amount(key: String) = if (fxOn(key)) rawAmount(key) else 0
    fun setAmount(key: String, v: Int) = Settings.setInt("af_$key", v)
    fun fxOn(key: String) = !Settings.get("af_${key}_off", false)
    fun setFxOn(key: String, on: Boolean) = Settings.set("af_${key}_off", !on)
    fun resetAll() {
        for (f in FX) { setAmount(f.key, 0); if (!fxOn(f.key)) setFxOn(f.key, true) }
        resetEq(); if (!eqOn()) setEqOn(true)
    }

    /** The full equaliser replaces Bass and Treble while it is switched on. */
    const val KEY_EQ_FULL = "af_eq_full"
    fun fullEq() = Settings.get(KEY_EQ_FULL, false)
    fun inUse(fx: Fx) = !(fullEq() && (fx.key == "bass" || fx.key == "treble"))

    fun anyActive(): Boolean = supported() && Settings.get(KEY_ON, true) &&
        (FX.any { inUse(it) && amount(it.key) != 0 } || (fullEq() && EQ_HZ.indices.any { eqGain(it) != 0 }))

    // ---- the 16-band equaliser ----

    /** Centre of each equaliser band, in Hz. */
    val EQ_HZ = intArrayOf(30, 40, 60, 100, 200, 300, 500, 1000, 2000, 3000, 4000, 5000, 6000, 8000, 12000, 16000)
    const val EQ_MAX = 12
    const val EQ_HELP = "Turn single frequency ranges up or down. The low numbers are bass and rumble (30 to 200 Hz), " +
        "the middle is where voices live (300 Hz to 4k), and the high numbers are treble, hiss and sparkle (5k to 16k)."

    fun rawEqGain(i: Int) = Settings.getInt("af_eq_$i", 0).coerceIn(-EQ_MAX, EQ_MAX)
    fun eqGain(i: Int) = if (eqOn()) rawEqGain(i) else 0
    fun eqOn() = !Settings.get("af_eq_off", false)
    fun setEqOn(on: Boolean) = Settings.set("af_eq_off", !on)
    fun setEqGain(i: Int, v: Int) = Settings.setInt("af_eq_$i", v.coerceIn(-EQ_MAX, EQ_MAX))
    fun resetEq() { for (i in EQ_HZ.indices) Settings.setInt("af_eq_$i", 0) }
    fun eqLabel(i: Int): String { val hz = EQ_HZ[i]; return if (hz >= 1000) "${hz / 1000}k" else "$hz Hz" }

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

    /** Where each equaliser band ends: halfway (on a log scale) to the next centre, the last at 20 kHz. */
    private val CUTS = FloatArray(16) { i ->
        if (i == 15) 20000f else kotlin.math.sqrt(EQ_HZ[i].toFloat() * EQ_HZ[i + 1]).toFloat()
    }

    private fun apply(dp: DynamicsProcessing) {
        val on = anyActive()
        dp.enabled = on
        if (!on) return
        val boost = amount("boost").coerceIn(0, 12).toFloat()
        val lvl = amount("level").coerceIn(0, 100) / 100f
        val cmp = amount("comp").coerceIn(0, 100) / 100f
        val lim = amount("limit").coerceIn(0, 100) / 100f
        val voice = amount("voice").coerceIn(0, 100) / 100f
        val low = amount("lowcut").coerceIn(0, 100) / 100f

        // Equaliser: the full equaliser's bands, or Bass and Treble as gentle shelves over the
        // same bands, plus what Voice clarity and Rumble cut add.
        val full = fullEq()
        val bass = amount("bass").coerceIn(-12, 12).toFloat()
        val treble = amount("treble").coerceIn(-12, 12).toFloat()
        val gains = FloatArray(EQ_HZ.size) { i ->
            val hz = EQ_HZ[i]
            var g = if (full) eqGain(i).toFloat() else {
                val b = when { hz <= 100 -> 1f; hz <= 200 -> 0.6f; hz <= 300 -> 0.25f; else -> 0f }
                val t = when { hz >= 8000 -> 1f; hz >= 6000 -> 0.8f; hz >= 5000 -> 0.6f; hz >= 4000 -> 0.35f; else -> 0f }
                bass * b + treble * t
            }
            if (hz <= 60) g -= 24f * low else if (hz == 100) g -= 8f * low
            if (hz in 100..300) g -= 2.5f * voice
            if (hz in 2000..5000) g += 5f * voice else if (hz in 6000..8000) g += 1.5f * voice
            g
        }
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
