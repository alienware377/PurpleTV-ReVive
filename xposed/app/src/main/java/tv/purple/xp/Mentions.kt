package tv.purple.xp

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Buzz the phone when someone mentions you in chat.
 *
 * The detection is free: a mention token carries an isLocalUser flag, so [ChatTokens] already
 * answers "was I mentioned" exactly, with no knowledge of the logged in account and no substring
 * matching against a name that might also be ordinary text.
 *
 * The only real work here is not buzzing repeatedly for the same message. A chat line is
 * assembled more than once (rebinds, scrollback), so each message's own id is remembered for a
 * while and a repeat is ignored.
 */
object Mentions {

    const val KEY_ENABLED = "vibrate_on_mention"
    const val KEY_DURATION = "vibration_duration"
    const val DURATION_DEFAULT = 100

    /** Ids already buzzed for. Bounded, because chat never stops. */
    private const val MEMORY = 256
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
            size > MEMORY
    }

    fun onLine(args: Array<Any?>?) {
        if (!Settings.get(KEY_ENABLED, false)) return
        val msg = ChatTokens.message(args) ?: return
        if (!ChatTokens.mentionsLocalUser(msg)) return

        val id = ChatTokens.messageId(msg) ?: return
        synchronized(seen) {
            if (seen.put(id, true) != null) return
        }
        buzz()
    }

    private fun buzz() {
        val ctx: Context = Settings.ctx() ?: return
        val ms = Settings.getInt(KEY_DURATION, DURATION_DEFAULT).coerceIn(10, 1000).toLong()
        runCatching {
            val vib: Vibrator? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                        ?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            if (vib == null || !vib.hasVibrator()) return
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { log("MN vibrate failed: $it") }
    }
}
