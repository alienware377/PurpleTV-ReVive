package tv.purple.xp

/**
 * Emote image quality (settings key `emote_quality`).
 *
 * Every third-party CDN exposes the same emote at several resolutions, but each spells the size
 * differently, so the level is mapped per provider here rather than scattered through [EmoteRepo].
 * Lower quality means smaller downloads and less decode work — worth having on slow devices, since
 * a busy chat can decode dozens of animated emotes per second.
 *
 * The level only affects URLs built AFTER it changes, so [EmoteRepo.refetch] re-runs the loaders
 * when the user picks a new value.
 */
object EmoteQuality {

    const val KEY = "emote_quality"
    /** 0 = Low, 1 = Medium, 2 = Large. Medium matches the sizes used before this was configurable. */
    const val DEFAULT = 1
    /** Ask BTTV for its WEBP rendition instead of the original GIF/PNG. */
    const val KEY_BTTV_WEBP = "bttv_webp"

    private fun level() = Settings.getInt(KEY, DEFAULT).coerceIn(0, 2)

    /** cdn.7tv.app/emote/<id>/<size>.webp */
    val sevenTv: String get() = SEVENTV[level()]
    /** cdn.betterttv.net/emote/<id>/<size>, or <size>.webp when [KEY_BTTV_WEBP] is on. */
    val bttv: String get() = BTTV[level()] + if (Settings.get(KEY_BTTV_WEBP, false)) ".webp" else ""
    /** static-cdn.jtvnw.net/emoticons/v2/<id>/default/dark/<size> */
    val twitch: String get() = TWITCH[level()]
    /** FrankerFaceZ `urls` object keys. FFZ publishes 1/2/4 — there is no 3. */
    val ffz: String get() = FFZ[level()]
    /** emotes.adamcy.pl `size` field. Also 1x/2x/4x, no 3x. */
    val adamcy: String get() = ADAMCY[level()]

    private val SEVENTV = arrayOf("1x", "2x", "3x")
    private val BTTV = arrayOf("1x", "2x", "3x")
    private val TWITCH = arrayOf("1.0", "2.0", "3.0")
    private val FFZ = arrayOf("1", "2", "4")
    private val ADAMCY = arrayOf("1x", "2x", "4x")
}
