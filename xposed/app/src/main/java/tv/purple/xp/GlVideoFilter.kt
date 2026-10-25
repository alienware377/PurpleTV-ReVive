package tv.purple.xp

import android.view.TextureView

/**
 * Full filters on Android 12 and older, through our own GL pass. Filled in once the approach is
 * proven on a device; until then [available] is false and the colour matrix fallback is used.
 */
object GlVideoFilter {
    fun available(): Boolean = false
    fun update(tv: TextureView, on: Boolean) {}
}
