package tv.purple.xp

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings as SysSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Swipe up or down on the fullscreen player to change brightness (left half) or volume (right
 * half), like most video players.
 *
 * Touches are read at the very top of the chain, Activity.dispatchTouchEvent, so nothing inside
 * Twitch can intercept them first. A touch is only claimed once it is clearly vertical and began
 * over the player; from then on it is consumed, and Twitch gets a CANCEL so its own tap and drag
 * handling resets. Anything else passes through untouched.
 *
 * Landscape only. In portrait a downward drag on the player is Twitch's minimise gesture, and
 * stealing it would break the app's own navigation.
 */
object PlayerGestures {

    const val KEY_VOLUME = "volume_gesture_v2"
    const val KEY_BRIGHTNESS = "brightness_gesture_v2"
    private const val DEV_PORTRAIT = "dev_gestures_portrait"

    private enum class Mode { NONE, BRIGHTNESS, VOLUME }

    private var mode = Mode.NONE
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var startLeft = false
    private var height = 1
    /** Where the player sat when the gesture began, so the label can be centred on it. */
    private val player = Rect()
    /** Volume moves in whole steps, so fractions of a step are carried between move events. */
    private var volCarry = 0f
    private var bubble: WeakReference<TextView>? = null
    private val hideBubble = Runnable { bubble?.get()?.visibility = View.GONE }

    fun install(@Suppress("UNUSED_PARAMETER") lp: LoadPackageParam) {
        XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val a = param.thisObject as? Activity ?: return
                val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                val claimed = runCatching { onTouch(a, ev) }
                    .onFailure { log("gesture error: $it"); mode = Mode.NONE }
                    .getOrDefault(false)
                if (!claimed) return
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && justClaimed) {
                    justClaimed = false
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    runCatching { XposedBridge.invokeOriginalMethod(param.method, a, arrayOf(cancel)) }
                    cancel.recycle()
                }
                param.result = true
            }
        })
    }

    private var justClaimed = false

    private fun enabledAny() =
        Settings.get(KEY_VOLUME, false) || Settings.get(KEY_BRIGHTNESS, false)

    /** True when this event belongs to a gesture and must not reach Twitch. */
    private fun onTouch(a: Activity, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.NONE; tracking = false
                if (!enabledAny()) return false
                // DEV_PORTRAIT is a hidden switch for testing on a device locked to portrait.
                if (a.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
                    !Settings.get(DEV_PORTRAIT, false)) return false
                player.set(playerRect(a) ?: return false)
                if (!player.contains(ev.rawX.toInt(), ev.rawY.toInt())) return false
                // Keep clear of the system gesture strips at the very top and bottom.
                val edge = (24 * a.resources.displayMetrics.density).toInt()
                if (ev.rawY < player.top + edge || ev.rawY > player.bottom - edge) return false
                tracking = true
                downX = ev.rawX; downY = ev.rawY; lastY = ev.rawY
                startLeft = ev.rawX < player.centerX()
                height = player.height().coerceAtLeast(1)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) {
                    if (!tracking || ev.pointerCount > 1) return false
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    val slop = ViewConfiguration.get(a).scaledTouchSlop * 2
                    if (abs(dy) < slop) return false
                    if (abs(dy) < abs(dx) * 2) { tracking = false; return false }
                    mode = when {
                        startLeft && Settings.get(KEY_BRIGHTNESS, false) -> Mode.BRIGHTNESS
                        !startLeft && Settings.get(KEY_VOLUME, false) -> Mode.VOLUME
                        else -> { tracking = false; return false }
                    }
                    justClaimed = true
                    volCarry = 0f
                    lastY = ev.rawY
                    return true
                }
                // Up is more. A full player-height swipe covers the whole range.
                val frac = (lastY - ev.rawY) / height
                lastY = ev.rawY
                if (mode == Mode.BRIGHTNESS) brightness(a, frac) else volume(a, frac)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                val was = mode != Mode.NONE
                mode = Mode.NONE
                return was
            }
            else -> return mode != Mode.NONE
        }
    }

    /** Screen rect of the visible player, or null when there is none on screen. */
    private fun playerRect(a: Activity): Rect? {
        val root = a.window?.decorView ?: return null
        for (name in arrayOf("player_overlay_container", "player_pane", "player_container")) {
            val id = a.resources.getIdentifier(name, "id", a.packageName)
            if (id == 0) continue
            val v = root.findViewById<View>(id) ?: continue
            if (!v.isShown || v.width == 0 || v.height == 0) continue
            val r = Rect()
            if (v.getGlobalVisibleRect(r) && r.height() > 0) return r
        }
        return null
    }

    private fun brightness(a: Activity, frac: Float) {
        val w = a.window ?: return
        val lp = w.attributes
        var cur = lp.screenBrightness
        if (cur < 0f) {
            // No override yet: start from the system level so the first swipe does not jump.
            cur = runCatching {
                SysSettings.System.getInt(a.contentResolver, SysSettings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)
        }
        val next = (cur + frac).coerceIn(0.01f, 1f)
        lp.screenBrightness = next
        w.attributes = lp
        show(a, "Brightness ${(next * 100).toInt()}%")
    }

    private fun volume(a: Activity, frac: Float) {
        val am = a.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volCarry += frac * max
        val steps = volCarry.toInt()
        if (steps != 0) {
            volCarry -= steps
            val next = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
        }
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        show(a, "Volume ${now * 100 / max}%")
    }

    /** A small centred label, reused, that fades out shortly after the last change. */
    private fun show(a: Activity, text: String) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        var tv = bubble?.get()
        if (tv == null || tv.parent !== decor) {
            tv = TextView(a).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                background = GradientDrawable().apply {
                    cornerRadius = pad.toFloat()
                    setColor(0xB3000000.toInt())
                }
            }
            decor.addView(tv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER))
            bubble = WeakReference(tv)
        }
        tv.text = text
        tv.visibility = View.VISIBLE
        // Centre on the player, not the screen: in portrait the player is only the top part.
        val loc = IntArray(2).also { decor.getLocationOnScreen(it) }
        tv.translationX = (player.centerX() - loc[0] - decor.width / 2).toFloat()
        tv.translationY = (player.centerY() - loc[1] - decor.height / 2).toFloat()
        tv.bringToFront()
        tv.removeCallbacks(hideBubble)
        tv.postDelayed(hideBubble, 900)
    }
}
