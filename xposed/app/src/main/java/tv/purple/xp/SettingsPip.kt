package tv.purple.xp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Keeps the stream on screen while Twitch's main settings are open. Opening them from the app with
 * a stream up puts the stream into picture-in-picture first and runs settings in a task of its
 * own, because an activity started from a picture-in-picture task would land inside that small
 * window. When settings closes, its result is handed to the app as usual (signing out travels that
 * way) and the stream comes back full size.
 *
 * Also adds a settings button to Twitch's chat settings sheet.
 */
internal object SettingsPip {

    private const val SETTINGS = "tv.twitch.android.settings.SettingsActivity"
    private const val HOST = "tv.twitch.android.feature.viewer.landing.ViewerLandingActivity"
    private const val CHAT_SHEET = "tv.twitch.android.shared.chat.settings.entry.ChatSettingsFragment"
    const val EXTRA = "ptv_over_pip"
    /** Long enough for the small window to be up before the next screen starts. */
    private const val PIP_WAIT_MS = 380L

    private val main = Handler(Looper.getMainLooper())
    private var host: WeakReference<Activity>? = null
    private var hostCode = -1
    private var resultCode = Activity.RESULT_CANCELED
    private var resultData: Intent? = null
    /** What settings answered, waiting for the stream screen to be in front again. */
    private var pending: Triple<Int, Int, Intent?>? = null

    fun install(lp: LoadPackageParam) {
        val start = Activity::class.java.getMethod("startActivityForResult", Intent::class.java,
            Int::class.javaPrimitiveType, Bundle::class.java)
        XposedBridge.hookMethod(start, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val a = p.thisObject as? Activity ?: return
                val i = p.args[0] as? Intent ?: return
                if (i.component?.className != SETTINGS || i.hasExtra(EXTRA) || a.javaClass.name != HOST) return
                if (a.isInPictureInPictureMode || !Settings.get(KEY, true)) return
                if (!TwoStreams.isOpen() && !RefreshButton.liveShown()) return
                if (runCatching { overPip(a, i, p.args[1] as Int) }.onFailure { log("SP failed: $it") }.getOrDefault(false))
                    p.result = null
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "setResult", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val a = p.thisObject as? Activity ?: return
                if (a.intent?.hasExtra(EXTRA) != true) return
                resultCode = p.args[0] as Int
                resultData = p.args.getOrNull(1) as? Intent
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val a = p.thisObject as? Activity ?: return
                if (a.javaClass.name == SETTINGS && a.isFinishing && a.intent?.hasExtra(EXTRA) == true) closed(a)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val a = p.thisObject as? Activity ?: return
                if (a.javaClass.name != HOST) return
                val r = pending ?: return
                pending = null
                main.post { deliver(a, r) }
            }
        })
        // The way back from settings is not a link to open: Twitch must stay on the screen it was on.
        var c: Class<*>? = lp.classLoader.loadClass(HOST)
        while (c != null && c != Activity::class.java) {
            val m = runCatching { c!!.getDeclaredMethod("onNewIntent", Intent::class.java) }.getOrNull()
            if (m != null) {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if ((p.args[0] as? Intent)?.hasExtra(EXTRA) == true) p.result = null
                    }
                })
                break
            }
            c = c.superclass
        }
        runCatching { addChatButton(lp) }.onFailure { log("SP chat sheet button failed: $it") }
        log("SP settings over picture-in-picture ready")
    }

    /** False when no small window came up, and Twitch's own start goes ahead untouched. */
    private fun overPip(a: Activity, i: Intent, code: Int): Boolean {
        val up = if (TwoStreams.isOpen()) TwoStreamPip.enterNow(a) else streamScreenUp(a) && TwoStreamPip.enterForTwitch(a)
        if (!up) return false
        host = WeakReference(a)
        hostCode = code
        resultCode = Activity.RESULT_CANCELED
        resultData = null
        val out = Intent(i).putExtra(EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        val app = a.applicationContext
        main.postDelayed({ runCatching { app.startActivity(out) }.onFailure { log("SP start failed: $it") } }, PIP_WAIT_MS)
        log("SP settings over picture-in-picture")
        return true
    }

    /** Twitch's stream screen exists, full size or as the mini player. A feed preview has none. */
    private fun streamScreenUp(a: Activity): Boolean {
        val id = Res.id(a, "fullscreen_layout")
        if (id == 0) return false
        val v = a.findViewById<ViewGroup>(id) ?: return false
        return v.childCount > 0
    }

    /**
     * Settings closed. The stream screen comes to the front first, or starts again if its small
     * window was closed meanwhile, and gets the answer once it is there: signing out is one.
     */
    private fun closed(from: Activity) {
        host = null
        pending = if (hostCode >= 0) Triple(hostCode, resultCode, resultData) else null
        resultData = null
        val app = from.applicationContext
        main.post {
            runCatching {
                app.startActivity(Intent().setClassName(app, HOST).putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.onFailure { log("SP return failed: $it") }
        }
    }

    private fun deliver(a: Activity, r: Triple<Int, Int, Intent?>) {
        if (a.isFinishing || a.isDestroyed) return
        runCatching {
            Activity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
                .apply { isAccessible = true }.invoke(a, r.first, r.second, r.third)
        }.onFailure { log("SP result failed: ${it.cause ?: it}") }
    }

    /** The sheet is all Compose, so the button sits in a frame around it, beside its close button. */
    private fun addChatButton(lp: LoadPackageParam) {
        val c = lp.classLoader.loadClass(CHAT_SHEET)
        val m = c.declaredMethods.first { it.name == "onCreateView" && it.parameterTypes.size == 3 }
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val sheet = p.result as? View ?: return
                if (!Settings.get(KEY, true)) return
                val ctx = sheet.context
                fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
                val gear = ImageView(ctx).apply {
                    setImageDrawable(DualIcons.Gear())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
                    contentDescription = I18n.t("Settings")
                    isClickable = true
                    setOnClickListener {
                        val a = activityOf(ctx) ?: return@setOnClickListener
                        runCatching { p.thisObject.javaClass.getMethod("dismiss").invoke(p.thisObject) }
                        DualCards.settings(a)
                    }
                }
                p.result = FrameLayout(ctx).apply {
                    addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(gear, FrameLayout.LayoutParams(dp(42f), dp(42f), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(8f)
                        marginEnd = dp(58f)
                    })
                }
            }
        })
    }

    private fun activityOf(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    const val KEY = "settings_over_pip"
}
