package tv.purple.xp

/**
 * The ad volume math, kept free of Android and Xposed so it can be unit tested on the JVM.
 * AdVolume is the only caller.
 */
internal object AdGain {
    /** What each option stores: the percentage of the ad's normal loudness. 100 is Twitch's own. */
    val VALUES = listOf(100, 50, 25, 15, 10, 5)
    val OPTIONS = listOf("Normal", "50%", "25%", "15%", "10%", "5%")
    /** Ads are mixed much louder than most streams, so 25% sits about level with the stream. */
    const val DEF = 25

    /** About -30 dB: the floor for our gain, unless Twitch's own value is already lower. */
    const val MIN_GAIN = 0.03f

    /** The stored setting as a factor: anything that is not one of [values] means [def]. */
    fun factorOf(stored: Int, values: List<Int>, def: Int): Float =
        (if (stored in values) stored else def) / 100f

    /** Twitch's own gain [raw] turned down by [f], never below [MIN_GAIN] (or raw, if lower). */
    fun scaled(raw: Float, f: Float): Float =
        if (raw.isNaN() || raw <= 0f || f >= 1f) raw else maxOf(raw * f, minOf(raw, MIN_GAIN))

    /** The factor as a cut in dB for the limiter's postGain: 0 for Normal, negative otherwise. */
    fun dbOf(f: Float): Float =
        if (f < 1f && f > 0f) (20.0 * Math.log10(f.toDouble())).toFloat() else 0f
}
