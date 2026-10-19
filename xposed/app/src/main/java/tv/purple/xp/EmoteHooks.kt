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
        if (EmoteRepo.drawables.isEmpty()) return
        val body = runCatching {
            XposedHelpers.getObjectField(model, Names.BODY_FIELD) as? CharSequence
        }.getOrNull() ?: return
        if (body.isEmpty()) return

        // already processed?
        if (body is Spanned && body.getSpans(0, body.length, Marker::class.java).isNotEmpty()) return

        // quick reject: any whole word a known (prefetched) emote?
        val hits = ArrayList<Triple<Int, Int, String>>(2)
        for (m in word.findAll(body)) {
            if (!EmoteRepo.enabled(m.value)) continue
            if (EmoteRepo.drawables.containsKey(m.value))
                hits.add(Triple(m.range.first, m.range.last + 1, m.value))
            // In the set but its picture is not here yet: jump it to the front of the queue so
            // the next time it is used it renders.
            else EmoteRepo.ensure(m.value)
        }
        if (hits.isEmpty()) return

        val out = SpannableStringBuilder(body) // copies existing (native emote) spans
        for ((s, e, name) in hits) {
            // Fresh animated copy when the emote is animated (independent frame cursor +
            // callback per line); else the shared static drawable. PtvEmoteSpan carries the
            // emote name so a tap can open the preview.
            val d = EmoteRepo.makeAnimated(name) ?: EmoteRepo.drawables[name] ?: continue
            out.setSpan(PtvEmoteSpan(d, name), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        out.setSpan(Marker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        XposedHelpers.setObjectField(model, Names.BODY_FIELD, out)
        log("injected ${hits.size} emote(s): ${hits.joinToString(",") { it.third }}")
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

    /** Attach a tap/long-press listener. Wired once per TextView (TOUCH_WIRED). We CLAIM the
     *  gesture only when DOWN lands on an emote (so chat scrolling / row clicks keep working),
     *  then: tap = preview popup; long-press on a favoritable Twitch emote = toggle favorite. */
    private fun wireTouch(tv: TextView) {
        if (!TOUCH_WIRED.add(tv)) return
        var downX = 0f; var downY = 0f
        var pending: Hit? = null            // emote the DOWN landed on (gesture claimed)
        var longFired = false               // long-press already handled this gesture
        var lpRunnable: Runnable? = null
        val slop = android.view.ViewConfiguration.get(tv.context).scaledTouchSlop
        val lpTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        fun cancelLp(t: TextView) { lpRunnable?.let { t.removeCallbacks(it) }; lpRunnable = null }
        tv.setOnTouchListener { v, ev ->
            val t = v as? TextView ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; longFired = false
                    val hit = findHitAt(t, ev.x, ev.y)
                    pending = hit
                    if (hit != null && hit.favoritable) {
                        val r = Runnable {
                            longFired = true
                            val now = Favorites.toggle(hit.name)
                            toast(t.context, if (now) "★ Favorited ${hit.name}" else "Removed ${hit.name}")
                        }
                        lpRunnable = r; t.postDelayed(r, lpTimeout)
                    }
                    hit != null
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pending != null && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop))
                        cancelLp(t)
                    pending != null
                }
                MotionEvent.ACTION_UP -> {
                    cancelLp(t)
                    val hit = pending; pending = null
                    if (hit != null && !longFired &&
                        abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop && hit.isPtv) {
                        showEmotePreview(t, hit.name)
                    }
                    hit != null
                }
                MotionEvent.ACTION_CANCEL -> { cancelLp(t); pending = null; false }
                else -> pending != null  // keep owning the gesture we claimed
            }
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
    private fun showEmotePreview(anchor: View, name: String) {
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
            }
            val dialog = AlertDialog.Builder(ctx).setView(box).create()
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

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
        ).toInt()
}
