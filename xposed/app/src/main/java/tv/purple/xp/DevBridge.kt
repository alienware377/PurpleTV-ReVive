package tv.purple.xp

import android.view.View

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Set PurpleTV settings from a connected computer, for testing.
 *
 *   adb shell am broadcast -p tv.twitch.android.app -a tv.purple.xp.DEV_SET --es key KEY --ez b true
 *   adb shell am broadcast -p tv.twitch.android.app -a tv.purple.xp.DEV_SET --es key KEY --ei i 12
 *   adb shell am broadcast -p tv.twitch.android.app -a tv.purple.xp.DEV_SET --es key KEY --es s TEXT
 *   adb shell am broadcast -p tv.twitch.android.app -a tv.purple.xp.DEV_DUMP
 *
 * Only something holding android.permission.DUMP can send these, which in practice means the adb
 * shell or the system. An ordinary app on the phone cannot reach it.
 *
 * Wired before the master switch is checked, so it can also turn the whole module back on.
 */
object DevBridge {

    private const val ACTION_SET = "tv.purple.xp.DEV_SET"
    private const val ACTION_DUMP = "tv.purple.xp.DEV_DUMP"
    private const val ACTION_PROBE = "tv.purple.xp.DEV_PROBE"
    private const val ACTION_ORIENT = "tv.purple.xp.DEV_ORIENT"
    private const val ACTION_FIND = "tv.purple.xp.DEV_FIND"
    private const val ACTION_TIMER = "tv.purple.xp.DEV_TIMER"
    private const val ACTION_AT = "tv.purple.xp.DEV_AT"
    private const val ACTION_RAID = "tv.purple.xp.DEV_RAID"
    private const val ACTION_FREQ = "tv.purple.xp.DEV_FREQ"
    private const val ACTION_MODLOG = "tv.purple.xp.DEV_MODLOG"
    private const val ACTION_EMOTE = "tv.purple.xp.DEV_EMOTE"
    private const val ACTION_UPDATE = "tv.purple.xp.DEV_UPDATE"
    private const val ACTION_VIEW = "tv.purple.xp.DEV_VIEW"

    /** The activity on screen, so a probe can look at its views. */
    @Volatile private var top: java.lang.ref.WeakReference<android.app.Activity>? = null

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext ?: ctx
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching {
                    when (i.action) {
                        ACTION_SET -> set(i)
                        ACTION_DUMP -> dump(app)
                        ACTION_PROBE -> probe(i.getStringExtra("id").orEmpty())
                        ACTION_ORIENT -> orient(i.getIntExtra("o", -1))
                        ACTION_AT -> at(i.getIntExtra("x", 0), i.getIntExtra("y", 0))
                        ACTION_TIMER -> SleepTimer.start(app, i.getIntExtra("s", 10) * 1000L)
                        ACTION_UPDATE -> ViewHider.currentActivity()?.let { InAppUpdate.start(it, i.getStringExtra("url").orEmpty(), "test", i.getStringExtra("label") ?: "test") }
                        ACTION_EMOTE -> ViewHider.currentActivity()?.window?.decorView?.let { EmoteHooks.showEmotePreview(it, i.getStringExtra("name").orEmpty()) }
                        ACTION_MODLOG -> ModLogs.test(i.getStringExtra("text").orEmpty())
                        ACTION_FREQ -> FrequentEmotes.test(app, i.getStringExtra("name").orEmpty())
                        ACTION_RAID -> RaidCard.simulate(i.getStringExtra("src").orEmpty(), i.getStringExtra("dst").orEmpty(), i.getIntExtra("n", 5))
                        ACTION_VIEW -> view(i.getStringExtra("re").orEmpty(), i.getBooleanExtra("click", false), i.getStringExtra("text"))
                        ACTION_FIND -> find(i.getStringExtra("re").orEmpty(), i.getBooleanExtra("click", false),
                            i.getStringExtra("url"))
                    }
                }.onFailure { log("DEV error: $it") }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SET); addAction(ACTION_DUMP); addAction(ACTION_PROBE)
            addAction(ACTION_ORIENT); addAction(ACTION_FIND); addAction(ACTION_TIMER); addAction(ACTION_AT); addAction(ACTION_RAID); addAction(ACTION_FREQ); addAction(ACTION_MODLOG); addAction(ACTION_EMOTE); addAction(ACTION_UPDATE); addAction(ACTION_VIEW)
        }
        (app as? android.app.Application)?.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: android.app.Activity) {
                    top = java.lang.ref.WeakReference(a)
                }
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                override fun onActivityStarted(a: android.app.Activity) {}
                override fun onActivityPaused(a: android.app.Activity) {}
                override fun onActivityStopped(a: android.app.Activity) {}
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {}
            })
        runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                app.registerReceiver(receiver, filter, "android.permission.DUMP", null,
                    Context.RECEIVER_EXPORTED)
            else
                app.registerReceiver(receiver, filter, "android.permission.DUMP", null)
            log("DEV bridge ready")
        }.onFailure { log("DEV bridge failed: $it") }
    }

    private fun set(i: Intent) {
        val key = i.getStringExtra("key") ?: return
        when {
            i.hasExtra("b") -> Settings.set(key, i.getBooleanExtra("b", false))
            i.hasExtra("i") -> Settings.setInt(key, i.getIntExtra("i", 0))
            i.hasExtra("s") -> Settings.setString(key, i.getStringExtra("s").orEmpty())
            else -> return
        }
        log("DEV set $key")
        // The same live refreshes the settings menu triggers, so most changes show at once.
        runCatching { ViewHider.reapply() }
        runCatching { ChatAppearance.reapply() }
        runCatching { HomeTabs.reapply() }
        if (key.startsWith("af_")) runCatching { AudioFilters.refresh() }
        if (key == EmoteQuality.KEY || key == EmoteQuality.KEY_BTTV_WEBP || key == EmoteRepo.KEY_HOMIES)
            EmoteRepo.refetch()
    }

    private fun dump(ctx: Context) {
        val all = ctx.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE).all
        log("DEV dump: " + all.toSortedMap().entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    /**
     * Report every view with this resource entry name on the current screen: whether it is set
     * visible, whether it is actually shown, and its size. Read from inside the app, because the
     * accessibility dump waits for the screen to go idle and was giving stale answers.
     */
    private fun probe(entry: String) {
        val act = top?.get() ?: ViewHider.currentActivity()
            ?: run { log("DEV probe: no activity"); return }
        act.runOnUiThread {
            val root = act.window?.decorView ?: return@runOnUiThread
            val out = ArrayList<String>()
            val stack = ArrayDeque<android.view.View>(); stack.add(root)
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                val e = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (e == entry) out.add("vis=${v.visibility} shown=${v.isShown} ${v.width}x${v.height}")
                if (v is android.view.ViewGroup) for (k in 0 until v.childCount) stack.add(v.getChildAt(k))
            }
            log("DEV probe $entry: ${out.size} -> ${out.joinToString(" | ")}")
        }
    }

    /**
     * Log every shown TextView whose text matches [re], with its centre on screen. With [click],
     * the first match that carries a link has that link's own onClick run, which is exactly
     * what a tap on the link does, so link handling can be tested without hunting for pixels.
     */
    /**
     * Test aid for screens with live video, where uiautomator cannot dump: list the shown views
     * (in every window of the app, picker sheets included) whose id name, description or hint
     * matches [re], with their centres; [click] taps the first; [text] replaces an EditText's
     * text (never sends anything).
     */
    @Suppress("UNCHECKED_CAST")
    private fun view(re: String, click: Boolean, text: String?) {
        val rx = runCatching { Regex(re) }.getOrNull() ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val roots = runCatching {
                val g = Class.forName("android.view.WindowManagerGlobal")
                val inst = g.getMethod("getInstance").invoke(null)
                (g.getDeclaredField("mRoots").apply { isAccessible = true }.get(inst) as List<Any>)
                    .mapNotNull { r -> runCatching { r.javaClass.getMethod("getView").invoke(r) as? View }.getOrNull() }
            }.getOrNull() ?: listOfNotNull(ViewHider.currentActivity()?.window?.decorView)
            var n = 0; var done = false
            for (root in roots) {
                val stack = ArrayDeque<View>(); stack.add(root)
                while (stack.isNotEmpty()) {
                    val v = stack.removeLast()
                    if (v is android.view.ViewGroup) for (k in 0 until v.childCount) stack.add(v.getChildAt(k))
                    if (!v.isShown) continue
                    val name = Res.entry(v).orEmpty()
                    val desc = v.contentDescription?.toString().orEmpty()
                    val hint = (v as? android.widget.TextView)?.hint?.toString().orEmpty()
                    val label = listOf(name, desc, hint).filter { it.isNotEmpty() }.joinToString(" | ")
                    if (label.isEmpty() || !rx.containsMatchIn(label)) continue
                    val loc = IntArray(2).also { v.getLocationOnScreen(it) }
                    val shown = (v as? android.widget.TextView)?.text?.toString()?.take(80)
                    log("DEV view: (${loc[0] + v.width / 2},${loc[1] + v.height / 2}) $label${shown?.let { " text=\"$it\"" } ?: ""}")
                    n++
                    if (!done && text != null && v is android.widget.EditText) { v.setText(text); v.setSelection(v.text.length); done = true }
                    else if (!done && click) { v.performClick(); log("DEV view: clicked $label"); done = true }
                }
            }
            log("DEV view '$re': $n match(es)")
        }
    }

    private fun find(re: String, click: Boolean, url: String?) {
        val act = top?.get() ?: ViewHider.currentActivity() ?: return
        val rx = runCatching { Regex(re) }.getOrNull() ?: return
        act.runOnUiThread {
            val root = act.window?.decorView ?: return@runOnUiThread
            val stack = ArrayDeque<android.view.View>(); stack.add(root)
            var n = 0; var clicked = false
            while (stack.isNotEmpty()) {
                val v = stack.removeLast()
                if (v is android.view.ViewGroup) for (k in 0 until v.childCount) stack.add(v.getChildAt(k))
                val tv = v as? android.widget.TextView ?: continue
                if (!tv.isShown) continue
                val t = tv.text ?: continue
                if (!rx.containsMatchIn(t)) continue
                val loc = IntArray(2).also { tv.getLocationOnScreen(it) }
                log("DEV find: (${loc[0] + tv.width / 2},${loc[1] + tv.height / 2}) ${t.toString().take(120)}")
                val touch = runCatching {
                    val li = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(tv)
                    li?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }?.get(li)
                }.getOrNull()
                log("DEV find:   movement=${tv.movementMethod?.javaClass?.name} touch=${touch?.javaClass?.name} clickable=${tv.isClickable} spans=${(t as? android.text.Spanned)?.getSpans(0, t.length, android.text.style.ClickableSpan::class.java)?.joinToString { it.javaClass.simpleName }}")
                n++
                if (click && !clicked && t is android.text.Spanned) {
                    // The span that covers the matched text, not the first one (that is the name).
                    val link = t.getSpans(0, t.length, android.text.style.ClickableSpan::class.java)
                        .firstOrNull { "URL" in it.javaClass.name &&
                            rx.containsMatchIn(t.subSequence(t.getSpanStart(it), t.getSpanEnd(it))) }
                        ?: continue
                    // Test aid: point the link somewhere else first, by rewriting whichever
                    // String field of the span holds a URL.
                    if (url != null) {
                        var c: Class<*>? = link.javaClass
                        while (c != null && c != Any::class.java) {
                            for (f in c.declaredFields) {
                                f.isAccessible = true
                                val cur = runCatching { f.get(link) }.getOrNull() ?: continue
                                log("DEV find: field ${f.name}:${f.type.simpleName}=${cur.toString().take(80)}")
                                if (f.type == String::class.java && (cur as String).startsWith("http")) {
                                    f.set(link, url); log("DEV find: url ${f.name} -> $url")
                                } else if (f.type == android.net.Uri::class.java) {
                                    f.set(link, android.net.Uri.parse(url)); log("DEV find: uri ${f.name} -> $url")
                                }
                            }
                            c = c.superclass
                        }
                    }
                    link.onClick(tv); clicked = true
                    log("DEV find: clicked ${link.javaClass.name}")
                }
            }
            log("DEV find '$re': $n match(es)")
        }
    }

    /** Log every shown view under screen point (x, y), outermost first, with its entry name. */
    private fun at(x: Int, y: Int) {
        val act = top?.get() ?: ViewHider.currentActivity() ?: return
        act.runOnUiThread {
            val root = act.window?.decorView ?: return@runOnUiThread
            val stack = ArrayDeque<android.view.View>(); stack.add(root)
            val r = android.graphics.Rect()
            while (stack.isNotEmpty()) {
                val v = stack.removeFirst()
                if (!v.isShown || !v.getGlobalVisibleRect(r) || !r.contains(x, y)) continue
                val e = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (e != null || v is android.view.SurfaceView || v is android.view.TextureView)
                    log("DEV at: $e ${v.javaClass.name} $r")
                if (v is android.view.ViewGroup) for (k in 0 until v.childCount) stack.add(v.getChildAt(k))
            }
            log("DEV at ($x,$y) done")
        }
    }

    /**
     * Ask the current Twitch screen for an orientation: 0 landscape, 1 portrait, -1 back to
     * normal. An in-app request on this activity only, so the device's own rotation settings are
     * left alone, and the landscape-only features can be tested without touching them.
     */
    private fun orient(o: Int) {
        val act = top?.get() ?: ViewHider.currentActivity() ?: return
        act.runOnUiThread {
            act.requestedOrientation = when (o) {
                0 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            log("DEV orientation request $o")
        }
    }
}
