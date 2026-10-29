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
 * Ad volume (AdVolume), set the level first with DEV_SET --es key ad_volume --ei i 25 (100, 50,
 * 25, 15, 10 or 5), then read the "AV" lines in logcat:
 *   ... -a tv.purple.xp.DEV_ADVOL --es op start [--ei s 15]   an ad window on the main player's
 *        audio session for s seconds (default 30), restored by itself after that
 *   ... -a tv.purple.xp.DEV_ADVOL --es op start --es via cue  the same through the DATERANGE parser
 *        (a twitch-stitched-ad cue of s seconds; its watchdog adds 20 s)
 *   ... -a tv.purple.xp.DEV_ADVOL --es op end                 restores everything now
 *   ... -a tv.purple.xp.DEV_ADVOL --es op end --es via cue    the stream-source "live" cue instead
 *   ... -a tv.purple.xp.DEV_ADVOL --es op state --es kind client   Twitch's own ad state, as its
 *        presenter pushes it (client, audio, surestream or none)
 *   ... -a tv.purple.xp.DEV_ADVOL --es op status              logs players, sessions and track gains
 * None of them calls an IVS native method; status only reads Java fields.
 *
 * Picture-by-Picture ad breaks (PbypPreview, "PP" lines):
 *   ... -a tv.purple.xp.DEV_PBYP --es op start     a simulated break on the current stream screen:
 *        a ticking TEST LIVE stand-in in Twitch's live box (Twitch's own player is never moved)
 *   ... -a tv.purple.xp.DEV_PBYP --es op end       ends it, everything goes back
 *   ... -a tv.purple.xp.DEV_PBYP --es op dump      logs where every view is (Java state only)
 *        (--ei on 1 or 0 works as start or end too)
 * Captions (CaptionHub, "CH" lines). Injected text shows in the box while a break (or the
 * simulation) shows it, and finished lines go on to read-aloud:
 *   ... -a tv.purple.xp.DEV_CAPTION --es text "hello there" [--ez final false]   as speech-to-text
 *        output (final by default; false is a live guess, drawn dimmer)
 *   ... -a tv.purple.xp.DEV_CAPTION --es op cc --es text "HELLO THERE"   as Twitch's own captions
 *        (or --es json '{"caption":{"text":"..."}}' through the real parser)
 *   ... -a tv.purple.xp.DEV_CAPTION --es op demo [--es src stt]   12 sample lines 1.5 s apart
 *   ... -a tv.purple.xp.DEV_CAPTION --es op clear | status
 * Speech to text (Stt, "STT" lines; "do" works in place of "op"):
 *   ... -a tv.purple.xp.DEV_STT --es op status
 *   ... -a tv.purple.xp.DEV_STT --es op feed_main  feeds the MAIN player's sound (the stream on
 *        screen) to the recognizer, to test it outside an ad. Needs stt_captions on and both
 *        downloads; DEV_SET --es key dev_stt_log --ez b true logs the text it hears
 *   ... -a tv.purple.xp.DEV_STT --es op stop       ends feed_main (and a WAV feed)
 *   ... -a tv.purple.xp.DEV_STT --es op load | unload | wav --es path P | pack_download
 *        [--es which engine|model|both] | pack_verify | pack_delete | pack_corrupt
 * Read-aloud (ReadAloud, "RA" lines):
 *   ... -a tv.purple.xp.DEV_TTS --es op say --es text "hello" [--ez force true]   force says it
 *        now, like the menu's Test voice; without it, only during a shown break
 *   ... -a tv.purple.xp.DEV_TTS --es op stop | engines | probe | status | shutdown
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
    private const val ACTION_SYNC = "tv.purple.xp.DEV_SYNC"
    private const val ACTION_EMINFO = "tv.purple.xp.DEV_EMINFO"
    private const val ACTION_ADVOL = "tv.purple.xp.DEV_ADVOL"
    private const val ACTION_PBYP = "tv.purple.xp.DEV_PBYP"
    private const val ACTION_CAPTION = "tv.purple.xp.DEV_CAPTION"
    private const val ACTION_STT = "tv.purple.xp.DEV_STT"
    private const val ACTION_TTS = "tv.purple.xp.DEV_TTS"

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
                        ACTION_DUMP -> dump(app, i.getStringExtra("re"))
                        ACTION_PROBE -> probe(i.getStringExtra("id").orEmpty())
                        ACTION_ORIENT -> orient(i.getIntExtra("o", -1))
                        ACTION_AT -> at(i.getIntExtra("x", 0), i.getIntExtra("y", 0))
                        ACTION_TIMER -> SleepTimer.start(app, i.getIntExtra("s", 10) * 1000L)
                        ACTION_UPDATE -> ViewHider.currentActivity()?.let { InAppUpdate.start(it, i.getStringExtra("url").orEmpty(), "test", i.getStringExtra("label") ?: "test") }
                        ACTION_EMOTE -> ViewHider.currentActivity()?.window?.decorView?.let { EmoteHooks.showEmotePreview(it, i.getStringExtra("name").orEmpty()) }
                        ACTION_MODLOG -> ModLogs.test(i.getStringExtra("text").orEmpty())
                        ACTION_FREQ -> FrequentEmotes.test(app, i.getStringExtra("name").orEmpty())
                        ACTION_RAID -> RaidCard.simulate(i.getStringExtra("src").orEmpty(), i.getStringExtra("dst").orEmpty(), i.getIntExtra("n", 5))
                        ACTION_EMINFO -> for (n in i.getStringExtra("names").orEmpty().split(",").filter { it.isNotBlank() }) log("DEV eminfo $n: " + EmoteRepo.debugInfo(n) + " available=" + Favorites.isAvailable(n))
                        ACTION_SYNC -> SettingsSync.debugRead(app, i.getStringExtra("re"))
                        ACTION_ADVOL -> AdVolume.dev(i)
                        ACTION_PBYP -> pbyp(i)
                        ACTION_CAPTION -> CaptionHub.dev(i)
                        ACTION_STT -> Stt.dev(i)
                        ACTION_TTS -> ReadAloud.dev(i)
                        ACTION_VIEW -> view(i.getStringExtra("re").orEmpty(), i.getBooleanExtra("click", false), i.getStringExtra("text"))
                        ACTION_FIND -> find(i.getStringExtra("re").orEmpty(), i.getBooleanExtra("click", false),
                            i.getStringExtra("url"))
                    }
                }.onFailure { log("DEV error: $it") }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SET); addAction(ACTION_DUMP); addAction(ACTION_PROBE)
            addAction(ACTION_ORIENT); addAction(ACTION_FIND); addAction(ACTION_TIMER); addAction(ACTION_AT); addAction(ACTION_RAID); addAction(ACTION_FREQ); addAction(ACTION_MODLOG); addAction(ACTION_EMOTE); addAction(ACTION_UPDATE); addAction(ACTION_VIEW); addAction(ACTION_SYNC); addAction(ACTION_EMINFO)
            addAction(ACTION_ADVOL)
            addAction(ACTION_PBYP); addAction(ACTION_CAPTION); addAction(ACTION_STT); addAction(ACTION_TTS)
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
        if (key == AdVolume.KEY || key.startsWith("af_")) runCatching { AdVolume.refresh() }
        if (key == EmoteQuality.KEY || key == EmoteQuality.KEY_BTTV_WEBP || key == EmoteRepo.KEY_HOMIES)
            EmoteRepo.refetch()
        // The preview, read-aloud and speech to text, as the menu's switches do it.
        AdBreakSettings.changed(key, byUser = true)
    }

    /** DEV_PBYP: op start, end or dump (or --ei on 1 / 0). The simulation posts to the main thread. */
    private fun pbyp(i: Intent) {
        val op = i.getStringExtra("op") ?: when (i.getIntExtra("on", -1)) {
            1 -> "start"
            0 -> "end"
            else -> "dump"
        }
        when (op) {
            "start" -> PbypPreview.simulateStart()
            "end" -> PbypPreview.simulateEnd()
            "dump" -> PbypPreview.dump()
            else -> log("DEV pbyp: unknown op '$op' (start, end or dump)")
        }
    }

    /**
     * Every setting, or only the keys matching [re]. One log line holds about 4 KB, so lines are
     * filled up to a size rather than a count: one large value (sync_mtimes) used to push its whole
     * line past the limit, and every key after it in that line was cut off. A value too big for a
     * line of its own is sent in numbered parts, as key[1/3]=..., key[2/3]=... and so on.
     */
    private fun dump(ctx: Context, re: String?) {
        val rx = re?.let { runCatching { Regex(it) }.getOrNull() }
        val all = ctx.getSharedPreferences("ptv_settings", Context.MODE_PRIVATE).all
            .filterKeys { rx == null || rx.containsMatchIn(it) }
        val line = StringBuilder()
        fun flush() { if (line.isNotEmpty()) { log("DEV dump: $line"); line.setLength(0) } }
        for ((k, v) in all.toSortedMap()) {
            val e = "$k=$v"
            if (e.length > DUMP_PART) {
                flush()
                // In characters: at most 3 bytes each in UTF-8, so a part always fits a line.
                val parts = v.toString().chunked(DUMP_PART)
                parts.forEachIndexed { i, p -> log("DEV dump: $k[${i + 1}/${parts.size}]=$p") }
                continue
            }
            if (line.isNotEmpty() && line.length + 2 + e.length > DUMP_PART) flush()
            if (line.isNotEmpty()) line.append("; ")
            line.append(e)
        }
        flush()
        if (all.isEmpty()) log("DEV dump: (none)")
    }

    /** Characters per dump line: well inside logcat's 4 KB even if every one takes 3 bytes. */
    private const val DUMP_PART = 1000

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
