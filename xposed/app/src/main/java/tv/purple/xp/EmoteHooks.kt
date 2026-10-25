package tv.purple.xp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlin.math.abs

/**
 * Live-chat emote injection (task #9 display half).
 *
 * The statically-traced kn5/MessageToken chain does NOT fire for live chat (proven on
 * device: kn5.a tokenizes only startup UI; MessageToken$EmoticonToken ctor never fires
 * for chat). The REAL live render chain — found by hooking az6.y and dumping caller
 * stacks for native emote ids — is:
 *
 *     ik5.b -> ckm.g -> ckm.j -> az6.y     (during DraggableConstraintLayout.onLayout)
 *
 * ik5.b returns the per-chat-line model `hn5`:
 *     hn5.a = timestamp,  hn5.c = "username: ",  hn5.e = MESSAGE BODY,  hn5.f = bool
 *
 * HOOK: after ik5.b, read hn5.e (the body, already carrying Twitch's own emote spans),
 * scan it for 7TV/BTTV/FFZ emote words, splice in our pre-fetched ImageSpans, and write
 * the result back to hn5.e. Emote bitmaps are pre-downloaded (EmoteRepo.drawables) so the
 * spans are ready-to-draw — we have no host TextView here to invalidate on async load.
 */
object EmoteHooks {

    private val word = Regex("\\S+")

    /** Marker span so we don't re-process an already-injected body. */
    private object Marker

    /** Our injected emote span, carrying the emote name so a tap can open the preview. Subclasses
     *  ImageSpan so the existing animation driver (getSpans(ImageSpan)) still finds it. */
    class PtvEmoteSpan(d: Drawable, val emoteName: String) : ImageSpan(d, ALIGN_BASELINE)

    /** TextViews we've already attached the tap-to-preview touch listener to. */
    private val TOUCH_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<TextView, Boolean>()
    )

    fun install(lp: LoadPackageParam) {
        val asm = Names.cls(lp, Names.ASSEMBLER) ?: return
        XposedBridge.hookAllMethods(asm, Names.ASSEMBLER_METHOD, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // Decided first so a hidden line can skip the decoration below, but APPLIED last:
                // ChatLineStyle replaces the body wholesale when it recovers a deleted message,
                // which would take the marker with it.
                val hide = runCatching { Blacklist.matches(param.args) }
                    .onFailure { log("blacklist match error: $it") }
                    .getOrDefault(false)

                if (!hide) {
                    runCatching { injectBody(param.result) }.onFailure { log("inject error: $it") }
                    // Badges + pronouns ride the SAME anchor: one of this call's arguments is the
                    // chat message, which carries the author's id and login. No second pin needed.
                    runCatching { ChatIdentity.inject(param.result, param.args) }
                        .onFailure { log("identity inject error: $it") }
                }
                runCatching { ChatLineStyle.apply(param.result, param.args) }
                    .onFailure { log("line style error: $it") }
                if (hide) {
                    runCatching { Blacklist.mark(param.result) }
                        .onFailure { log("blacklist mark error: $it") }
                } else {
                    runCatching { ChatHighlight.apply(param.result, param.args) }
                        .onFailure { log("highlight error: $it") }
                }
                // LAST: the deleted-message carrier captures the finished body, and rides a slot
                // that ChatIdentity rewrites wholesale, so anything earlier would be dropped.
                runCatching { Mentions.onLine(param.args) }
                    .onFailure { log("mention buzz error: $it") }
                runCatching { ChatLineStyle.plantOriginal(param.result) }
                    .onFailure { log("carrier plant error: $it") }
            }
        })
        log("emote inject hook installed on ${Names.ASSEMBLER}.${Names.ASSEMBLER_METHOD} (field .${Names.BODY_FIELD})")
        installAnimationDriver()
    }

    /**
     * Drive animated emote spans. Our injected AnimatedImageDrawables have no host View to
     * invalidate from inside the obfuscated render chain, so we hook the FRAMEWORK
     * TextView.setText (obfuscation-immune): whenever a TextView receives a Spanned whose
     * ImageSpans wrap AnimatedImageDrawables, we wire each drawable's callback to that TextView
     * (so per-frame invalidateSelf repaints it) and start() it. Every animated span is a fresh
     * instance (EmoteRepo.makeAnimated), so no cross-line callback contention.
     */
    private fun installAnimationDriver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                TextView::class.java, "setText",
                CharSequence::class.java, TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    // Deleted messages are restored BEFORE the call, by rewriting the argument.
                    // Doing it afterwards would mean calling setText again from inside setText.
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val cs = param.args[0] as? CharSequence ?: return
                        runCatching { ChatLineStyle.reviveDeleted(tv.context, cs) }
                            .onFailure { log("revive deleted error: $it") }
                            .getOrNull()?.let { param.args[0] = it }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val cs = param.args[0] as? CharSequence ?: return
                        // First: a blacklisted row is collapsed here, and nothing below is worth
                        // doing to a line that will not be drawn.
                        val hidden = runCatching { BlacklistRows.onSetText(tv, cs) }
                            .onFailure { log("blacklist row error: $it") }
                            .getOrDefault(false)
                        if (hidden) return
                        runCatching { wireLinks(tv, cs) }
                        runCatching { startAnimatedSpans(tv, cs) }
                        // Wire tap/long-press once per TextView that carries an emote we care about:
                        // our injected 7TV/etc spans (tap = preview) OR a native Twitch emote span
                        // the user owns (long-press = favorite, tap = preview).
                        if (cs is Spanned && hasWirableEmote(cs)) {
                            runCatching { wireTouch(tv) }
                        }
                    }
                }
            )
            log("animation driver installed (TextView.setText)")
        }.onFailure { log("animation driver failed: $it") }
    }

    private val LINKS_WIRED = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TextView, Boolean>())

    /**
     * Make links tappable on chat lines Twitch forgot to wire. Ordinary lines get Twitch's own
     * link handler (a movement method), but announcement lines carry the same link spans with no
     * handler at all, so tapping a link there did nothing. When a line has link spans and no
     * movement method, a tap that lands on a link runs that link's own click, exactly as Twitch
     * does elsewhere. Every other touch passes through untouched.
     */
    private fun wireLinks(tv: TextView, cs: CharSequence) {
        if (cs !is Spanned || tv.movementMethod != null) return
        val spans = cs.getSpans(0, cs.length, android.text.style.ClickableSpan::class.java)
        if (spans.none { it.javaClass.name.contains("URL", ignoreCase = true) }) return
        if (!LINKS_WIRED.add(tv)) return
        val prev: View.OnTouchListener? = runCatching {
            val li = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(tv)
            li?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }
                ?.get(li) as? View.OnTouchListener
        }.getOrNull()
        var downOn: android.text.style.ClickableSpan? = null
        val slop = android.view.ViewConfiguration.get(tv.context).scaledTouchSlop
        var dx = 0f; var dy = 0f
        tv.setOnTouchListener { v, ev ->
            val t = v as TextView
            if (t.movementMethod != null) return@setOnTouchListener prev?.onTouch(v, ev) ?: false
            val mine = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = ev.x; dy = ev.y; downOn = linkAt(t, ev.x, ev.y); downOn != null }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.x - dx) > slop || abs(ev.y - dy) > slop) downOn = null
                    downOn != null
                }
                MotionEvent.ACTION_UP -> {
                    val sp = downOn; downOn = null
                    if (sp != null && linkAt(t, ev.x, ev.y) === sp) { runCatching { sp.onClick(t) }; true } else false
                }
                MotionEvent.ACTION_CANCEL -> { downOn = null; false }
                else -> downOn != null
            }
            if (mine) true else prev?.onTouch(v, ev) ?: false
        }
    }

    private fun linkAt(tv: TextView, x: Float, y: Float): android.text.style.ClickableSpan? {
        val layout = tv.layout ?: return null
        val cs = tv.text as? Spanned ?: return null
        val lx = x - tv.totalPaddingLeft + tv.scrollX
        val ly = y - tv.totalPaddingTop + tv.scrollY
        val line = layout.getLineForVertical(ly.toInt())
        if (lx < layout.getLineLeft(line) || lx > layout.getLineRight(line)) return null
        val off = layout.getOffsetForHorizontal(line, lx)
        return cs.getSpans(off, off, android.text.style.ClickableSpan::class.java)
            .firstOrNull { it.javaClass.name.contains("URL", ignoreCase = true) }
    }

    private fun startAnimatedSpans(tv: TextView, cs: CharSequence) {
        if (cs !is Spanned) return
        val spans = cs.getSpans(0, cs.length, ImageSpan::class.java)
        if (spans.isEmpty()) return
        var cb: Drawable.Callback? = null
        for (sp in spans) {
            val dr = sp.drawable
            if (dr is AnimatedImageDrawable) {
                if (dr.callback == null) {
                    if (cb == null) cb = viewCallback(tv)
                    dr.callback = cb
                }
                if (!dr.isRunning) dr.start()
            }
        }
    }

    /** Callback that repaints [v] each animation frame. Full invalidate() so it works regardless
     *  of TextView.verifyDrawable (span drawables aren't "verified"). */
    private fun viewCallback(v: View): Drawable.Callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { v.invalidate() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
            v.postDelayed(what, time - SystemClock.uptimeMillis())
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { v.removeCallbacks(what) }
    }

    private fun injectBody(model: Any?) {
        model ?: return
        val waiting = fill(model, first = true)
        // Drawn before its channel's emotes or their pictures arrived: filled in once they do.
        if (waiting || EmoteRepo.channelPending()) LateEmotes.add(model)
    }

    /**
     * Put an emote picture on each word of the line's body that has one. [first] is the line's
     * first pass, which skips a body already done; later passes only add words still drawn as
     * text. Returns true when a word is an emote whose picture is still downloading.
     */
    internal fun fill(model: Any, first: Boolean): Boolean {
        val body = runCatching {
            XposedHelpers.getObjectField(model, Names.BODY_FIELD) as? CharSequence
        }.getOrNull() ?: return false
        if (body.isEmpty()) return false
        val spanned = body as? Spanned

        // already processed?
        if (first && spanned != null && spanned.getSpans(0, body.length, Marker::class.java).isNotEmpty()) return false

        // quick reject: any whole word a known (prefetched) emote?
        val hits = ArrayList<Triple<Int, Int, String>>(2)
        var waiting = false
        for (m in word.findAll(body)) {
            if (!EmoteRepo.map.containsKey(m.value) || !EmoteRepo.enabled(m.value)) continue
            val s = m.range.first; val e = m.range.last + 1
            if (!first && spanned != null && spanned.getSpans(s, e, PtvEmoteSpan::class.java).isNotEmpty()) continue
            if (EmoteRepo.drawables.containsKey(m.value)) hits.add(Triple(s, e, m.value))
            // In the set but its picture is not here yet: jump it to the front of the queue so
            // the line can be filled in when it lands.
            else { waiting = true; EmoteRepo.ensure(m.value) }
        }
        if (hits.isEmpty()) return waiting

        val out = SpannableStringBuilder(body) // copies existing (native emote) spans
        for ((s, e, name) in hits) {
            // Fresh animated copy when the emote is animated (independent frame cursor +
            // callback per line); else the shared static drawable. PtvEmoteSpan carries the
            // emote name so a tap can open the preview.
            val d = EmoteRepo.makeAnimated(name) ?: EmoteRepo.drawables[name] ?: continue
            out.setSpan(PtvEmoteSpan(d, name), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (out.getSpans(0, out.length, Marker::class.java).isEmpty())
            out.setSpan(Marker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        XposedHelpers.setObjectField(model, Names.BODY_FIELD, out)
        log("injected ${hits.size} emote(s)${if (first) "" else " late"}: ${hits.joinToString(",") { it.third }}")
        return waiting
    }

    // --- tap-to-preview + long-press-to-favorite ---

    /** An emote located under a touch point: its [name] and whether it can be favorited
     *  (true only for the user's own Twitch/sub emotes). */
    private data class Hit(val name: String, val favoritable: Boolean, val isPtv: Boolean = false)

    /** True if [cs] carries an emote we should wire touch for: one of our injected spans, or a
     *  native Twitch ImageSpan whose covered text is an emote the user owns (favoritable). */
    private fun hasWirableEmote(cs: Spanned): Boolean {
        if (cs.getSpans(0, cs.length, PtvEmoteSpan::class.java).isNotEmpty()) return true
        for (sp in cs.getSpans(0, cs.length, ImageSpan::class.java)) {
            if (sp is PtvEmoteSpan || sp is ChatIdentity.PtvBadgeSpan) continue
            val st = cs.getSpanStart(sp); val en = cs.getSpanEnd(sp)
            if (st in 0 until en) {
                val name = cs.subSequence(st, en).toString().trim()
                if (name.length in 2..30 && EmoteRepo.twitch.containsKey(name)) return true
            }
        }
        return false
    }

    /** Attach a tap/long-press listener. Wired once per TextView (TOUCH_WIRED).
     *
     *  Our own 7TV/BTTV/FFZ emotes: the gesture is claimed on DOWN, and a tap opens our preview.
     *  Twitch's own emotes: the gesture is NOT claimed, so a tap still reaches Twitch and opens
     *  its emote card. Claiming it used to swallow the tap and do nothing, which made Twitch
     *  emotes in chat look dead except at their very edge. Only a long press is ours there: it
     *  toggles the favorite, and the TextView gets a CANCEL so Twitch does not act on it too. */
    private fun wireTouch(tv: TextView) {
        if (!TOUCH_WIRED.add(tv)) return
        var downX = 0f; var downY = 0f
        var pending: Hit? = null            // emote the DOWN landed on
        var claimed = false                 // gesture owned by us (our own emotes only)
        var longFired = false               // long-press already handled this gesture
        var lpRunnable: Runnable? = null
        val slop = android.view.ViewConfiguration.get(tv.context).scaledTouchSlop
        // A little shorter than the system long press, so ours fires first and can cancel it.
        val lpTimeout = (android.view.ViewConfiguration.getLongPressTimeout() - 60L).coerceAtLeast(300L)
        fun cancelLp(t: TextView) { lpRunnable?.let { t.removeCallbacks(it) }; lpRunnable = null }
        fun cancelTwitch(t: TextView) {
            val now = SystemClock.uptimeMillis()
            val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            runCatching { t.onTouchEvent(c) }
            c.recycle()
        }
        // Twitch may already have its own touch handler on this line (links, mentions). Setting
        // ours replaced it, which broke tapping links on any line that also had an emote. It is
        // kept and handed every touch we do not claim.
        val prev: View.OnTouchListener? = runCatching {
            val li = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }.get(tv)
            li?.javaClass?.getDeclaredField("mOnTouchListener")?.apply { isAccessible = true }
                ?.get(li) as? View.OnTouchListener
        }.getOrNull()
        tv.setOnTouchListener { v, ev ->
            val t = v as? TextView ?: return@setOnTouchListener false
            val mine = when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; longFired = false
                    val hit = findHitAt(t, ev.x, ev.y)
                    pending = hit
                    claimed = hit?.isPtv == true
                    if (hit != null && hit.favoritable) {
                        val r = Runnable {
                            longFired = true
                            if (!claimed) cancelTwitch(t)
                            val now = Favorites.toggle(hit.name)
                            toast(t.context, if (now) "★ Favorited ${hit.name}" else "Removed ${hit.name}")
                        }
                        lpRunnable = r; t.postDelayed(r, lpTimeout)
                    }
                    claimed
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pending != null && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop))
                        cancelLp(t)
                    claimed || longFired
                }
                MotionEvent.ACTION_UP -> {
                    cancelLp(t)
                    val hit = pending; pending = null
                    if (hit != null && claimed && !longFired &&
                        abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop) {
                        showEmotePreview(t, hit.name)
                    }
                    claimed || longFired
                }
                MotionEvent.ACTION_CANCEL -> { cancelLp(t); pending = null; claimed = false; false }
                else -> claimed
            }
            if (mine) true else prev?.onTouch(v, ev) ?: false
        }
    }

    /** Find the emote under touch point (x,y) in [tv], or null. Checks our PtvEmoteSpans first,
     *  then native Twitch ImageSpans (covered text = emote name). Verifies x lies within the
     *  span's drawn horizontal range (getOffsetForHorizontal snaps to nearest char past line end). */
    private fun findHitAt(tv: TextView, x: Float, y: Float): Hit? {
        val layout = tv.layout ?: return null
        val cs = tv.text as? Spanned ?: return null
        val lx = x - tv.totalPaddingLeft + tv.scrollX
        val ly = y - tv.totalPaddingTop + tv.scrollY
        val line = layout.getLineForVertical(ly.toInt())
        if (lx < layout.getLineLeft(line) || lx > layout.getLineRight(line)) return null
        val off = layout.getOffsetForHorizontal(line, lx)
        fun within(sp: Any): Boolean {
            val st = cs.getSpanStart(sp); val en = cs.getSpanEnd(sp)
            if (st < 0 || en < 0) return false
            val left = layout.getPrimaryHorizontal(st)
            val right = layout.getPrimaryHorizontal(en)
            return lx in minOf(left, right)..maxOf(left, right)
        }
        for (sp in cs.getSpans(off, off, PtvEmoteSpan::class.java)) {
            if (within(sp)) return Hit(sp.emoteName, Favorites.isFavoritable(sp.emoteName), isPtv = true)
        }
        for (sp in cs.getSpans(off, off, ImageSpan::class.java)) {
            // A badge's covered text is a lone non-breaking space, so it could never match an
            // emote name — but skipping it explicitly keeps tap handling honest.
            if (sp is PtvEmoteSpan || sp is ChatIdentity.PtvBadgeSpan) continue
            val st = cs.getSpanStart(sp); val en = cs.getSpanEnd(sp)
            if (st < 0 || en <= st) continue
            val name = cs.subSequence(st, en).toString().trim()
            if (name.isNotEmpty() && EmoteRepo.twitch.containsKey(name) && within(sp))
                return Hit(name, true)
        }
        return null
    }

    /** Show a centered dark popup with a large emote image + its name. Image is loaded at native
     *  size on a worker thread (network for non-animated); animated emotes start looping. Tap to
     *  dismiss. */
    fun showEmotePreview(anchor: View, name: String) {
        val ctx = anchor.context ?: return
        runCatching {
            val img = ImageView(ctx).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 140f)
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            val label = TextView(ctx).apply {
                text = name
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(ctx, 10f), 0, 0)
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val p = dp(ctx, 20f); setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 16f).toFloat()
                    setColor(Color.parseColor("#F21F1925"))
                }
                addView(img); addView(label)
                // Where the emote comes from: the service, global or this channel's, and who made it.
                EmoteRepo.infoOf(name)?.let { (svc, by, global) ->
                    val page = EmoteRepo.pageOf(name)
                    addView(TextView(ctx).apply {
                        val label = svc + if (global) " global emote" else " channel emote"
                        // A link to the emote's own page on 7TV, BetterTTV or FrankerFaceZ.
                        text = if (page != null) android.text.SpannableString(label).apply {
                            setSpan(android.text.style.UnderlineSpan(), 0, label.length, 0)
                        } else label
                        setTextColor(Color.parseColor("#BF94FF"))
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(dp(ctx, 8f), dp(ctx, 6f), dp(ctx, 8f), dp(ctx, 2f))
                        if (page != null) {
                            contentDescription = "Open $name on $svc"
                            setOnClickListener { openPage(ctx, page); dismissPreview() }
                        }
                    })
                    if (page != null && !Settings.get(KEY_LINK_TIP, false)) addView(linkTip(ctx, svc, page))
                    if (!by.isNullOrBlank()) addView(TextView(ctx).apply {
                        text = "Uploaded by $by"
                        setTextColor(Color.parseColor("#ADADB8"))
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(ctx, 2f), 0, 0)
                    })
                }
            }
            val dialog = AlertDialog.Builder(ctx).setView(box).create()
            openPreview = java.lang.ref.WeakReference(dialog)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            box.setOnClickListener { runCatching { dialog.dismiss() } }
            dialog.show()

            // Load the big image off the UI thread, then apply on the anchor's thread.
            Thread({
                val d = runCatching { EmoteRepo.loadPreviewDrawable(name) }.getOrNull()
                anchor.post {
                    if (d == null) { img.setImageDrawable(null); return@post }
                    img.setImageDrawable(d)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        d is AnimatedImageDrawable) d.start()
                }
            }, "ptv-preview-$name").start()
            log("emote preview opened: $name")
        }.onFailure { log("emote preview error: $it") }
    }

    /** Set once the "tap here for the emote's page" tip has been closed. */
    const val KEY_LINK_TIP = "emote_link_tip_dismissed"
    private var openPreview: java.lang.ref.WeakReference<android.app.Dialog>? = null
    private fun dismissPreview() { runCatching { openPreview?.get()?.dismiss() } }

    private fun openPage(ctx: Context, url: String) {
        runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(ctx, "Could not open the emote's page") }
    }

    /** A one-time speech bubble under the purple line, pointing up at it. Its X closes it for good. */
    private fun linkTip(ctx: Context, svc: String, page: String): View {
        val box = LinearLayout(ctx)
        return box.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val arrow = dp(ctx, 8f).toFloat()
            background = VideoFilters.BubbleDrawable(0xF2241B31.toInt(), FilterIcons.PURPLE, dp(ctx, 10f).toFloat(),
                dp(ctx, 1.5f).toFloat(), arrow)
            setPadding(dp(ctx, 12f), dp(ctx, 8f) + arrow.toInt(), dp(ctx, 4f), dp(ctx, 8f))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 250f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(ctx, 2f); bottomMargin = dp(ctx, 4f)
            }
            addView(TextView(ctx).apply {
                text = "New: tap the purple line to open this emote's page on $svc."
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "\u2715"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER; contentDescription = "Dismiss tip"
                setPadding(dp(ctx, 10f), dp(ctx, 2f), dp(ctx, 10f), dp(ctx, 2f))
                setOnClickListener { Settings.set(KEY_LINK_TIP, true); (box.parent as? ViewGroup)?.removeView(box) }
            })
            setOnClickListener { openPage(ctx, page); dismissPreview() }
            // Point the arrow at the middle, where the purple line sits.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                (v.background as? VideoFilters.BubbleDrawable)?.let { d ->
                    val x = v.width / 2f
                    if (d.arrowX != x) { d.arrowX = x; d.invalidateSelf() }
                }
            }
        }
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
        ).toInt()
}
