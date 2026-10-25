package tv.purple.xp

import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Notes in the log what the channel points button shows and for how long, so a short-lived look
 * (the gold Watch Streak flame, the green bonus chest) can be identified after the fact. Twitch
 * itself logs none of this. Read only: nothing on the button is changed or pressed.
 */
object PointsWatch {

    private var streakSince = 0L
    private var chestSince = 0L

    /** From each layout pass. */
    fun check(root: View) {
        val ctx = root.context
        val button = (root as? ViewGroup)?.findViewById<ViewGroup>(Res.id(ctx, "community_points_button_layout"))
        val now = SystemClock.uptimeMillis()

        val streak = button?.findViewById<View>(Res.id(ctx, "watch_streak_icon"))?.takeIf { it.isShown }
        if (streak != null && streakSince == 0L) {
            streakSince = now
            val count = button.findViewById<TextView>(Res.id(ctx, "watch_streak_count"))?.text
            log("CP points button turned gold (Watch Streak): shows '${count ?: ""}', " +
                "colour ${colour(button)}, on ${EmoteRepo.currentChannel()}")
        } else if (streak == null && streakSince != 0L) {
            log("CP points button back to normal after ${(now - streakSince) / 1000}s")
            streakSince = 0L
        }

        // The bonus chest sits on the same button while a bonus is waiting to be collected.
        val chest = button?.findViewById<View>(Res.id(ctx, "overlay_icon"))?.takeIf { it.isShown }
        if (chest != null && chestSince == 0L) {
            chestSince = now
            log("CP points button shows its overlay icon (bonus chest?), colour ${colour(button)}, on ${EmoteRepo.currentChannel()}")
        } else if (chest == null && chestSince != 0L) {
            log("CP overlay icon gone after ${(now - chestSince) / 1000}s")
            chestSince = 0L
        }
    }

    private fun colour(v: View): String {
        val bg = v.background ?: (v.parent as? View)?.background
        val c = (bg as? GradientDrawable)?.color?.defaultColor ?: return bg?.javaClass?.simpleName ?: "none"
        return "#%08X".format(c)
    }
}
