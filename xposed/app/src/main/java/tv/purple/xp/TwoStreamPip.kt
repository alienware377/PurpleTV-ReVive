package tv.purple.xp

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import kotlin.math.abs

/**
 * Picture-in-picture for the two streams screen: both players, one over the other, in one small
 * window when the app is left.
 *
 * Twitch keeps telling Android how its own player should shrink: the shape of its video, where it
 * sits on screen, its pause button, and that it may go there by itself. While two streams are open
 * those calls would describe a player hidden under ours, so they are held back (and remembered) and
 * ours are sent instead: an 8:9 window, which is exactly two 16:9 players stacked, no buttons, and
 * on Android 12 and newer the same "go there by itself" Twitch uses. Older Android has no such
 * switch, so there the app is moved into the small window by hand when it is left, as Twitch does.
 *
 * Android merges these calls field by field and never forgets a field once it was set. So on close
 * a plain reset goes first, then Twitch's calls are played back in the order it made them, and
 * Twitch's latest wishes are what Android keeps. Only Twitch's newest call for each set of fields
 * is kept, which ends in the same place as playing back every call it ever made.
 */
object TwoStreamPip {

    private const val VLA = "tv.twitch.android.feature.viewer.landing.ViewerLandingActivity"
    /** A cap for when the fields of a call cannot be read and nothing can be dropped safely. */
    private const val HISTORY = 24
    /** Two 16:9 players stacked. */
    private val SHAPE = Rational(8, 9)

    @Volatile private var host: WeakReference<Activity>? = null
    /** True while one of our own calls is on its way, so the hooks let it through untouched. */
    private var ours = false
    /** When we last moved the app into the small window ourselves, to swallow Twitch's own try right after. */
    private var enteredAt = 0L
    private val twitch = ArrayDeque<PictureInPictureParams>()
    private var lastHint: Rect? = null
    private var held = 0
    private var supported: Boolean? = null

    /** Where both players are, in window coordinates, or null while that is not known yet. */
    var hint: () -> Rect? = { null }
    /** The host went into picture-in-picture (true) or came back out of it (false). */
    var onPip: (Activity, Boolean) -> Unit = { _, _ -> }
    /** The host is about to be left: a good moment to close menus and the keyboard. */
    var onLeaving: (Activity) -> Unit = {}

    private fun isHost(a: Any?) = a != null && host?.get() === a
    private fun isVla(a: Any?) = a != null && a.javaClass.name == VLA

    fun install() {
        val act = Activity::class.java
        val pp = PictureInPictureParams::class.java
        XposedHelpers.findAndHookMethod(act, "setPictureInPictureParams", pp, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (ours || !isVla(p.thisObject)) return
                (p.args[0] as? PictureInPictureParams)?.let(::remember)
                if (isHost(p.thisObject)) { p.result = null; held++ }
            }
        })
        XposedHelpers.findAndHookMethod(act, "enterPictureInPictureMode", pp, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (ours) return
                val a = p.thisObject as? Activity ?: return
                if (!isHost(a)) {
                    // Entering merges its values into the stored ones too, so it belongs in the replay.
                    if (isVla(a)) (p.args[0] as? PictureInPictureParams)?.let(::remember)
                    return
                }
                if (a.isInPictureInPictureMode || SystemClock.uptimeMillis() - enteredAt < 2000) { p.result = true; return }
                p.args[0] = params()
            }
        })
        XposedHelpers.findAndHookMethod(act, "onPictureInPictureModeChanged",
            Boolean::class.javaPrimitiveType, Configuration::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val a = p.thisObject as? Activity ?: return
                    if (!isHost(a)) return
                    val on = p.args[0] as? Boolean ?: return
                    runCatching { onPip(a, on) }.onFailure { log("PIP mode change: $it") }
                }
            })
        XposedHelpers.findAndHookMethod(act, "onUserLeaveHint", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val a = p.thisObject as? Activity ?: return
                if (!isHost(a)) return
                runCatching { onLeaving(a) }
                if (Build.VERSION.SDK_INT < 31) enterNow(a)
            }
        })
        // Some launchers and TVs ask the app itself whether it wants the small window.
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            XposedHelpers.findAndHookMethod(act, "onPictureInPictureRequested", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val a = p.thisObject as? Activity ?: return
                    if (isHost(a)) p.result = enterNow(a)
                }
            })
        }
        log("PIP hooks in")
    }

    /** The two streams screen opened on [a]: from now on our shape is the one Android keeps. */
    fun open(a: Activity) {
        host = WeakReference(a)
        lastHint = null
        held = 0
        push(a, params())
    }

    /** After a layout of the screen: tell Android where the players are now, when that moved. */
    fun refresh(a: Activity) {
        if (!isHost(a) || a.isInPictureInPictureMode) return
        val h = hintNow()
        if (h == lastHint) return
        lastHint = h
        push(a, params())
    }

    /** The screen closed: a reset, then Twitch's own calls again in the order it made them. */
    fun close(a: Activity?) {
        host = null
        lastHint = null
        if (a == null || a.isDestroyed) return
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(emptyList())
        if (Build.VERSION.SDK_INT >= 31) { b.setAutoEnterEnabled(false); b.setSeamlessResizeEnabled(true) }
        push(a, b.build())
        for (x in twitch.toList()) push(a, x)
        log("PIP handed back to Twitch (${twitch.size} of its calls replayed, $held held while open)")
    }

    /** Into the small window now. False when the phone has none or Android said no. */
    fun enterNow(a: Activity): Boolean {
        if (!supported(a)) return false
        ours = true
        return try {
            val ok = a.enterPictureInPictureMode(params())
            if (ok) enteredAt = SystemClock.uptimeMillis()
            ok
        } catch (t: Throwable) {
            log("PIP enter: $t")
            false
        } finally {
            ours = false
        }
    }

    fun status(a: Activity?): String =
        "pip: supported ${a?.let { supported(it) }}, now ${a?.isInPictureInPictureMode}, " +
            "Twitch calls held $held, kept ${twitch.size}, hint ${lastHint?.toShortString() ?: "-"}"

    private fun supported(a: Activity): Boolean = supported ?: runCatching {
        a.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }.getOrDefault(false).also { supported = it }

    /** The players' place, only when it has the window's shape: any other hint makes a poor animation. */
    private fun hintNow(): Rect? = runCatching { hint() }.getOrNull()?.takeIf {
        !it.isEmpty && abs(it.width().toFloat() / it.height() - 8f / 9f) < 0.06f
    }

    private fun params(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(SHAPE).setActions(emptyList())
        hintNow()?.let { b.setSourceRectHint(it) }
        // Seamless resizing is meant for content that redraws itself smoothly at any size; video
        // looks better when Android cross-fades instead.
        if (Build.VERSION.SDK_INT >= 31) { b.setAutoEnterEnabled(true); b.setSeamlessResizeEnabled(false) }
        return b.build()
    }

    private fun push(a: Activity, x: PictureInPictureParams) {
        if (!supported(a)) return
        ours = true
        try { a.setPictureInPictureParams(x) } catch (t: Throwable) { log("PIP params: $t") } finally { ours = false }
    }

    /**
     * The fields Android only copies from a call that set them, and that Twitch sets. Null when they
     * cannot be read, and then the plain capped history is kept instead.
     */
    private val setFields: List<Field>? by lazy {
        runCatching {
            val names = listOf("mAspectRatio", "mSourceRectHint", "mUserActions") +
                if (Build.VERSION.SDK_INT >= 31) listOf("mAutoEnterEnabled") else emptyList()
            names.map { PictureInPictureParams::class.java.getDeclaredField(it).apply { isAccessible = true } }
        }.onFailure { log("PIP fields: $it") }.getOrNull()
    }

    /** One bit per field in [setFields] that [x] sets, or null when that cannot be told. */
    private fun maskOf(x: PictureInPictureParams): Int? {
        val fs = setFields ?: return null
        return runCatching {
            fs.foldIndexed(0) { i, m, f ->
                val v = f.get(x)
                // An empty hint is never copied, so it does not count as set.
                if (v != null && !(v is Rect && v.isEmpty)) m or (1 shl i) else m
            }
        }.getOrNull()
    }

    /**
     * An older call that sets exactly the same fields as a newer one changes nothing once the newer
     * one is played after it, so it is dropped. That way Twitch's frequent pause/play button updates
     * never push out its rare call that sets the shape, the hint and auto-enter.
     */
    private fun remember(x: PictureInPictureParams) {
        val k = maskOf(x)
        if (k != null) twitch.removeAll { maskOf(it) == k }
        twitch.addLast(x)
        while (twitch.size > HISTORY) twitch.removeFirst()
    }
}
