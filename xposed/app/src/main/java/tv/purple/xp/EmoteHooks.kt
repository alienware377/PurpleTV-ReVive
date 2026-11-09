package tv.purple.xp

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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
 * Live-chat emote injection.
 *
 * The statically-traced kn5/MessageToken chain does not fire for live chat (proven on
 * device: kn5.a tokenizes only startup UI; MessageToken$EmoticonToken ctor never fires
 * for chat). The real live render chain (found by hooking az6.y and dumping caller
 * stacks for native emote ids) is:
 *
 *     ik5.b -> ckm.g -> ckm.j -> az6.y     (during DraggableConstraintLayout.onLayout)
 *
 * ik5.b returns the per-chat-line model `hn5`:
 *     hn5.a = timestamp,  hn5.c = "username: ",  hn5.e = message body,  hn5.f = bool
 *
 * Hook: after ik5.b, read hn5.e (the body, already carrying Twitch's own emote spans),
 * scan it for 7TV/BTTV/FFZ emote words, splice in our pre-fetched ImageSpans, and write
 * the result back to hn5.e. Emote bitmaps are pre-downloaded (EmoteRepo.drawables) so the
 * spans are ready-to-draw: we have no host TextView here to invalidate on async load.
 */
object EmoteHooks {

    private val word = Regex("\\S+")

    /** Marker span so we don't re-process an already-injected body. */
    private object Marker

    /** Our injected emote span, carrying the emote name so a tap can open the preview. Subclasses
     *  ImageSpan so the existing animation driver (getSpans(ImageSpan)) still finds it. [url] is
     *  the address the name had when the picture was put on, so a later pass can tell when the
     *  name has since moved to another picture (a channel emote that reuses a global name). */
    class PtvEmoteSpan(d: Drawable, val emoteName: String, val url: String? = null) : ImageSpan(d, ALIGN_BASELINE)

    /** TextViews we've already attached the tap-to-preview touch listener to. */
    private val TOUCH_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<TextView, Boolean>()
    )

    fun install(lp: LoadPackageParam) {
        val asm = Names.cls(lp, Names.ASSEMBLER) ?: return
        XposedBridge.hookAllMethods(asm, Names.ASSEMBLER_METHOD, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // Decided first so a hidden line can skip the decoration below, but applied last:
                // ChatLineStyle replaces the body wholesale when it recovers a deleted message,
                // which would take the marker with it.
                val hide = runCatching { Blacklist.matches(param.args) }
                    .onFailure { log("blacklist match error: $it") }
                    .getOrDefault(false)

                if (!hide) {
                    runCatching { injectBody(param.result) }.onFailure { log("inject error: $it") }
                    // Badges + pronouns ride the same anchor: one of this call's arguments is the
                    // chat message, which carries the author's id and login. No second pin needed.
                    runCatching { ChatIdentity.inject(param.result, param.args) }
                        .onFailure { log("identity inject error: $it") }
                    runCatching { markLine(param.result) }
                }
                runCatching { ChatLineStyle.apply(param.result, param.args) }
                    .onFailure { log("line style error: $it") }
                if (hide) {
                    runCatching { Blacklist.mark(param.result) }
                        .onFailure { log("blacklist mark error: $it") }
                } else {
                    runCatching { ChatHighlight.apply(param.result, param.args) }
                        .onFailure { log("highlight error: $it") }
                    // Only for shown lines: a hidden user mentioning you must not buzz the phone.
                    runCatching { Mentions.onLine(param.args) }
                        .onFailure { log("mention buzz error: $it") }
                    runCatching { ChatFeed.onLine(param.args) }
                        .onFailure { log("chat feed error: $it") }
                    runCatching { ChatTranslate.onLine(param.result, param.args) }
                        .onFailure { log("chat translate error: $it") }
                }
                // Last: the deleted-message carrier captures the finished body, and rides a slot
                // that ChatIdentity rewrites wholesale, so anything earlier would be dropped.
                runCatching { ChatLineStyle.plantOriginal(param.result) }
                    .onFailure { log("carrier plant error: $it") }
            }
        })
        log("emote inject hook installed on ${Names.ASSEMBLER}.${Names.ASSEMBLER_METHOD} (field .${Names.BODY_FIELD})")
        installAnimationDriver()
        installTouchKeeper()
        installStrictLinks()
    }

    /**
     * Drive animated emote spans. Our injected AnimatedImageDrawables have no host View to
     * invalidate from inside the obfuscated render chain, so we hook the framework
     * TextView.setText (obfuscation-immune): whenever a TextView receives a Spanned whose
     * ImageSpans wrap AnimatedImageDrawables, AnimPause records that the TextView now shows them,
     * points their callback at whatever views show them, and runs them only while one of those
     * views can be seen.
     */
    private fun installAnimationDriver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        AnimPause.install()
        runCatching {
            XposedHelpers.findAndHookMethod(
                TextView::class.java, "setText",
                CharSequence::class.java, TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    // Deleted messages are restored before the call, by rewriting the argument.
                    // Doing it afterwards would mean calling setText again from inside setText.
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val cs = param.args[0] as? CharSequence ?: return
                        runCatching { ChatLineStyle.reviveDeleted(tv.context, cs) }
                            .onFailure { log("revive deleted error: $it") }
                            .getOrNull()?.let { param.args[0] = it }
                        // A line drawn before its emotes were in gets them now, wherever it shows.
                        (param.args[0] as? Spanned)?.let { s ->
                            runCatching { refillWaiting(tv, s) }.onFailure { log("refill error: $it") }
                                .getOrNull()?.let { param.args[0] = it }
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val cs = param.args[0] as? CharSequence ?: return
                        // First: a blacklisted row is collapsed here, and nothing below is worth
                        // doing to a line that will not be drawn.
                        val hidden = runCatching { BlacklistRows.onSetText(tv, cs) }
                            .onFailure { log("blacklist row error: $it") }
                            .getOrDefault(false)
                        if (hidden) {
                            // Its pictures are not drawn, and the ones of the line it showed before
                            // must not keep running for it.
                            runCatching { AnimPause.onSetText(tv, null) }
                            return
                        }
                        runCatching { wireLinks(tv, cs) }
                        runCatching { AnimPause.onSetText(tv, cs) }
                            .onFailure { log("anim pause error: $it") }
                        runCatching { DeletedTip.onText(tv, cs) }
                        // Wire tap/long-press once per TextView that carries an emote we care about:
                        // our injected 7TV/etc spans (tap = preview) or a native Twitch emote span
                        // the user owns (long-press = favorite, tap = preview).
                        // Badges too, for the badge info sheet.
                        if (cs is Spanned && (hasWirableEmote(cs) || BadgeInfo.hasBadge(cs))) {
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
        var downOn: android.text.style.ClickableSpan? = null
        val slop = android.view.ViewConfiguration.get(tv.context).scaledTouchSlop
        var dx = 0f; var dy = 0f
        tv.setOnTouchListener(ChainedTouch(currentTouch(tv)) { self, v, ev ->
            val t = v as TextView
            if (t.movementMethod != null) return@ChainedTouch self.pass(v, ev)
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
            if (mine) true else self.pass(v, ev)
        })
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

    /** Marks a chat line (on its name slot), so its link taps can be judged strictly. */
    private object ChatLineMark

    /**
     * Chat lines open links (the username, links, tap-to-reveal) through Android's
     * LinkMovementMethod, which clicks whatever tap target touches the character nearest the
     * finger. A tap just right of a name, on a pronoun, or on an emote next to the name opened the
     * user card. On chat lines only, a tap now has to land on the target itself; a miss is left
     * to Twitch's own handling of a plain tap, as if there were no link there.
     */
    private fun installStrictLinks() {
        runCatching {
            XposedHelpers.findAndHookMethod(android.text.method.LinkMovementMethod::class.java, "onTouchEvent",
                TextView::class.java, android.text.Spannable::class.java, MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ev = param.args[2] as? MotionEvent ?: return
                        val action = ev.action
                        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return
                        val buf = param.args[1] as? android.text.Spannable ?: return
                        if (buf.getSpans(0, buf.length, ChatLineMark::class.java).isEmpty()) return
                        val tv = param.args[0] as? TextView ?: return
                        val (_, exact) = strictLink(tv, ev.x, ev.y) ?: return
                        if (action == MotionEvent.ACTION_UP && exact != null) runCatching { exact.onClick(tv) }
                        param.result = exact != null
                    }
                })
        }.onFailure { log("strict links failed: $it") }
    }

    private fun markLine(model: Any?) {
        model ?: return
        val name = XposedHelpers.getObjectField(model, Names.NAME_FIELD) as? CharSequence ?: return
        if (name.isEmpty()) return
        if (name is Spanned && name.getSpans(0, name.length, ChatLineMark::class.java).isNotEmpty()) return
        val out = name as? android.text.Spannable ?: android.text.SpannableString(name)
        out.setSpan(ChatLineMark, 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (out !== name) XposedHelpers.setObjectField(model, Names.NAME_FIELD, out)
    }

    /**
     * What a tap at (x, y) would click, judged the strict way. Android's link handling takes the
     * character nearest the finger and any tap target touching it, so a tap just right of a name,
     * on a pronoun or on an emote beside it still opened the user card. Returns null when that
     * standard handling is right; otherwise (true, span to click instead, or null for nothing).
     */
    private fun strictLink(tv: TextView, x: Float, y: Float): Pair<Boolean, android.text.style.ClickableSpan?>? {
        val layout = tv.layout ?: return null
        val cs = tv.text as? Spanned ?: return null
        val lx = x - tv.totalPaddingLeft + tv.scrollX
        val ly = y - tv.totalPaddingTop + tv.scrollY
        val line = layout.getLineForVertical(ly.toInt())
        val off = layout.getOffsetForHorizontal(line, lx)
        val near = cs.getSpans(off, off, android.text.style.ClickableSpan::class.java)
        if (near.isEmpty()) return null
        val onLine = ly >= layout.getLineTop(line) && ly <= layout.getLineBottom(line) &&
            lx >= layout.getLineLeft(line) && lx <= layout.getLineRight(line)
        val ls = layout.getLineStart(line); val le = layout.getLineVisibleEnd(line)
        // The span's own area on this line, as the text would be highlighted: right even where it
        // wraps in mixed left-to-right and right-to-left text.
        val path = android.graphics.Path()
        val clip = android.graphics.Region(layout.getLineLeft(line).toInt() - 1, layout.getLineTop(line),
            layout.getLineRight(line).toInt() + 1, layout.getLineBottom(line))
        fun exact(sp: Any): Boolean {
            if (!onLine) return false
            val st = maxOf(cs.getSpanStart(sp), ls); val en = minOf(cs.getSpanEnd(sp), le)
            if (en <= st) return false
            path.reset()
            layout.getSelectionPath(st, en, path)
            val area = android.graphics.Region()
            area.setPath(path, clip)
            return area.contains(lx.toInt(), ly.toInt())
        }
        val hit = near.firstOrNull { exact(it) }
        return when {
            hit == null -> true to null
            hit === near[0] -> null
            else -> true to hit
        }
    }

    private fun injectBody(model: Any?) {
        model ?: return
        // A replayed line is filled for its own channel, which may not be the loaded one yet.
        val waiting = fill(model, first = true, waitFor = Channels.replayChannel.get())
        // Drawn before its channel's emotes or their pictures arrived: filled in once they do.
        if (waiting || EmoteRepo.channelPending() || EmoteRepo.listsLate()) LateEmotes.add(model)
    }

    /**
     * Put an emote picture on each word of the line's body that has one. [first] is the line's
     * first pass, which skips a body already done; later passes add words still drawn as text and
     * swap a picture whose name now points somewhere else. Returns true when a word is an emote
     * whose picture is still downloading, or one that must wait for the new channel's emotes.
     */
    internal fun fill(model: Any, first: Boolean, waitFor: String? = null): Boolean {
        val body = runCatching {
            XposedHelpers.getObjectField(model, Names.BODY_FIELD) as? CharSequence
        }.getOrNull() ?: return false
        if (body.isEmpty()) return false
        // already processed?
        if (first && body is Spanned && body.getSpans(0, body.length, Marker::class.java).isNotEmpty()) return false
        // A replayed line for a channel that is not the loaded one: only global emotes for now.
        val elsewhere = waitFor != null && waitFor != EmoteRepo.currentChannel()
        val (out, found) = fillText(body, first, hold = elsewhere)
        val waiting = found || elsewhere || EmoteRepo.channelPending() || EmoteRepo.listsLate()
        val marked = markWait(out ?: body, waiting, waitFor ?: EmoteRepo.currentChannel())
        if (out != null || marked != null) XposedHelpers.setObjectField(model, Names.BODY_FIELD, marked ?: out)
        return waiting
    }

    /**
     * Marks a body still waiting for emotes, so that wherever the finished line is shown later
     * (VOD chat replay keeps only the finished line, and shows it again and again) it is filled in
     * then. Returns [text] with the mark put on or taken off, or null when nothing changed.
     */
    private fun markWait(text: CharSequence, waiting: Boolean, ch: String?): CharSequence? {
        val has = text is Spanned && text.getSpans(0, text.length, WaitMark::class.java).isNotEmpty()
        if (has == waiting) return null
        val out = text as? SpannableStringBuilder ?: SpannableStringBuilder(text)
        // Not growing at its ends: Twitch adds bidi marks right after the body in a right to left
        // layout, which would otherwise become part of the last word.
        if (waiting) out.setSpan(WaitMark(ch, SystemClock.uptimeMillis()), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        else for (m in out.getSpans(0, out.length, WaitMark::class.java)) out.removeSpan(m)
        return out
    }

    /**
     * Marks the part of a chat line that still waits for its emotes, for channel [ch], since
     * [since]. See [markWait]. Lines stop being refreshed a minute after they were made, so a
     * picture that never arrives does not keep its row busy.
     */
    internal class WaitMark(val ch: String?, val since: Long)

    /** Chat rows showing a line that still waits, to show again when its emotes arrive. */
    private val waitingViews = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TextView, Boolean>())
    private const val WAIT_VIEW_MS = 60_000L

    fun hasWaitingViews() = synchronized(waitingViews) { waitingViews.isNotEmpty() }

    /** From LateEmotes on the main thread: shows each waiting row's line again, which fills it in. */
    fun refreshWaitingViews() {
        val views = synchronized(waitingViews) { waitingViews.toList().also { waitingViews.clear() } }
        for (tv in views) {
            val t = tv.text as? Spanned ?: continue
            if (!tv.isAttachedToWindow || t.getSpans(0, t.length, WaitMark::class.java).isEmpty()) continue
            runCatching { tv.text = t }
        }
    }

    /** A line about to be shown that still waits: its body filled in now if it can be, or null. */
    private fun refillWaiting(tv: TextView, cs: Spanned): CharSequence? {
        val mark = cs.getSpans(0, cs.length, WaitMark::class.java).firstOrNull() ?: return null
        val fresh = SystemClock.uptimeMillis() - mark.since < WAIT_VIEW_MS
        fun keep(): CharSequence? {
            if (fresh) { synchronized(waitingViews) { waitingViews.add(tv) }; LateEmotes.poke() }
            return null
        }
        if (EmoteRepo.channelPending() || EmoteRepo.listsLate()) return keep()
        // Its channel is not the loaded one yet (the live chat still has the emotes).
        if (mark.ch != null && mark.ch != EmoteRepo.currentChannel()) return keep()
        var ws = cs.getSpanStart(mark); var we = cs.getSpanEnd(mark)
        while (we > ws && cs[we - 1].category == CharCategory.FORMAT) we--
        while (ws < we && cs[ws].category == CharCategory.FORMAT) ws++
        if (ws < 0 || we <= ws) return null
        val (filled, waiting) = fillText(cs.subSequence(ws, we), first = false)
        if (filled == null && waiting) return keep()
        val out = SpannableStringBuilder(cs)
        if (filled != null) for (sp in filled.getSpans(0, filled.length, PtvEmoteSpan::class.java)) {
            val s = ws + filled.getSpanStart(sp); val e = ws + filled.getSpanEnd(sp)
            for (old in out.getSpans(s, e, PtvEmoteSpan::class.java)) out.removeSpan(old)
            out.setSpan(sp, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (waiting && fresh) keep() else for (m in out.getSpans(0, out.length, WaitMark::class.java)) out.removeSpan(m)
        return out
    }

    /**
     * The emote pictures for the words of [body]: a copy with them on, or null when there are none
     * to put on, and whether a word still waits (its channel loading, or its picture downloading).
     * [hold] keeps the loaded channel's own emotes off, for a line from another channel.
     */
    private fun fillText(body: CharSequence, first: Boolean, hold: Boolean = false): Pair<SpannableStringBuilder?, Boolean> {
        val spanned = body as? Spanned

        // quick reject: any whole word a known (prefetched) emote?
        val hits = ArrayList<Triple<Int, Int, String>>(2)
        var waiting = false
        // Read once so every word of the line follows the same decision.
        val pending = EmoteRepo.channelPending() || hold
        for (m in word.findAll(body)) {
            if (!EmoteRepo.map.containsKey(m.value) || !EmoteRepo.enabled(m.value)) continue
            // While the new channel loads, the live set still holds the old channel's own emotes.
            // Drawing one now would stick (later passes skip words that already have a picture),
            // so leave the word for the late pass, which runs once the new channel is in.
            if (pending && m.value in EmoteRepo.chNames) { waiting = true; continue }
            val s = m.range.first; val e = m.range.last + 1
            // A word that already has a picture keeps it while the name still points at that
            // picture. A global drawn while the new channel loaded does not, when that channel
            // turns out to have its own emote by the same name: it is drawn again below, and the
            // old picture stays up until the new one has downloaded.
            if (!first && spanned != null) {
                val now = EmoteRepo.map[m.value]
                if (spanned.getSpans(s, e, PtvEmoteSpan::class.java).any { it.url == now }) continue
            }
            // Twitch already draws its own emote here (a sub emote with the same name as a 7TV
            // one): Twitch's picture stays, and a tap keeps opening Twitch's card.
            if (spanned != null && spanned.getSpans(s, e, android.text.style.ImageSpan::class.java).any { it !is PtvEmoteSpan && it !is ChatIdentity.PtvBadgeSpan }) continue
            if (EmoteRepo.drawables.containsKey(m.value)) hits.add(Triple(s, e, m.value))
            // In the set but its picture is not here yet: jump it to the front of the queue so
            // the line can be filled in when it lands. One whose picture is gone for good is not
            // waited for.
            else if (!EmoteRepo.pictureGone(m.value)) { waiting = true; EmoteRepo.ensure(m.value) }
        }
        if (hits.isEmpty()) return null to waiting

        val out = SpannableStringBuilder(body) // copies existing (native emote) spans
        for ((s, e, name) in hits) {
            // The emote's one shared animated picture (AnimPause redraws every row showing it and
            // starts and stops it with them), else the shared static drawable. PtvEmoteSpan carries
            // the emote name so a tap can open the preview. The url is read before the picture: if a
            // channel publishes in between, the span records the older url and a later pass swaps
            // it, rather than recording the new url over the old picture for good.
            val u = EmoteRepo.map[name]
            val d = EmoteRepo.chatAnimated(name) ?: EmoteRepo.drawables[name] ?: continue
            // A picture already on this word is an outdated one being replaced (see above). Two
            // pictures on one word would draw unpredictably, so the old one goes.
            for (old in out.getSpans(s, e, PtvEmoteSpan::class.java)) out.removeSpan(old)
            out.setSpan(PtvEmoteSpan(d, name, u), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (out.getSpans(0, out.length, Marker::class.java).isEmpty())
            out.setSpan(Marker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        log("injected ${hits.size} emote(s)${if (first) "" else " late"}: ${hits.joinToString(",") { it.third }}")
        return out to waiting
    }

    // --- tap-to-preview + long-press-to-favorite ---

    /** An emote located under a touch point: its [name] and whether it can be favorited
     *  (true only for the user's own Twitch/sub emotes). */
    private data class Hit(val name: String, val favoritable: Boolean, val isPtv: Boolean = false,
                           val badge: BadgeInfo.Hit? = null)

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

    fun isWired(tv: TextView) = TOUCH_WIRED.contains(tv)

    /** Twitch's own listener at the end of our chain on [tv], for the test hook. */
    fun prevOf(tv: TextView): View.OnTouchListener? {
        var l = currentTouch(tv)
        while (l is ChainedTouch) l = l.prev
        return l
    }

    /**
     * One of our touch listeners. It hands every touch it does not keep to [prev], whatever was on
     * the TextView before it. The chain lives in the listeners the TextView itself holds, so
     * nothing static keeps a chat row alive.
     */
    private class ChainedTouch(var prev: View.OnTouchListener?,
                               private val body: (ChainedTouch, View, MotionEvent) -> Boolean) : View.OnTouchListener {
        override fun onTouch(v: View, ev: MotionEvent) = body(this, v, ev)
        fun pass(v: View, ev: MotionEvent) = prev?.onTouch(v, ev) ?: false
    }

    private val listenerInfo by lazy { View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true } }
    private var touchField: java.lang.reflect.Field? = null

    /** The touch listener [v] holds right now. */
    private fun currentTouch(v: View): View.OnTouchListener? = runCatching {
        val li = listenerInfo.get(v) ?: return null
        val f = touchField ?: li.javaClass.getDeclaredField("mOnTouchListener").apply { isAccessible = true }.also { touchField = it }
        f.get(li) as? View.OnTouchListener
    }.getOrNull()

    /** Attach a tap/long-press listener. Wired once per TextView (TOUCH_WIRED).
     *
     *  Our own 7TV/BTTV/FFZ emotes: the gesture is claimed on DOWN, and a tap opens our preview.
     *  Twitch's own emotes: the gesture is not claimed, so a tap still reaches Twitch and opens
     *  its emote card. Claiming it used to swallow the tap and do nothing, which made Twitch
     *  emotes in chat look dead except at their very edge. Only a long press is ours there: it
     *  toggles the favorite, and the TextView gets a CANCEL so Twitch does not act on it too.
     *  Badges are not claimed either, so a long press on one still opens Twitch's message menu;
     *  a quick tap opens the badge sheet, and Twitch is told the gesture is over. */
    private fun wireTouch(tv: TextView) {
        if (!TOUCH_WIRED.add(tv)) return
        var downX = 0f; var downY = 0f
        var pending: Hit? = null            // emote the DOWN landed on
        var claimed = false                 // gesture owned by us (our own emotes only)
        var longFired = false               // long-press already handled this gesture
        var deep = false                    // a firm press, which Twitch takes as a long press
        var downAt = 0L                     // when we saw the DOWN
        var lpRunnable: Runnable? = null
        val slop = android.view.ViewConfiguration.get(tv.context).scaledTouchSlop
        val systemLp = android.view.ViewConfiguration.getLongPressTimeout()
        // A little shorter than the system long press, so ours fires first and can cancel it.
        val lpTimeout = (systemLp - 60L).coerceAtLeast(300L)
        fun cancelLp(t: TextView) { lpRunnable?.let { t.removeCallbacks(it) }; lpRunnable = null }
        // Twitch may already have its own touch handler on this line (links, mentions). Setting
        // ours replaced it, which broke tapping links on any line that also had an emote. It is
        // kept and handed every touch we do not claim. Twitch can also set its handler after ours
        // (VOD chat replay rows do); installTouchKeeper then puts it at the end of our chain.
        // Our long press on a Twitch emote: Twitch's own handlers are told the gesture is over,
        // the chained listener too, so its own long press (a message menu) does not fire as well.
        fun cancelTwitch(t: TextView, self: ChainedTouch) {
            val now = SystemClock.uptimeMillis()
            val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            runCatching { t.onTouchEvent(c) }
            runCatching { self.pass(t, c) }
            c.recycle()
        }
        val ours = ChainedTouch(currentTouch(tv)) { self, v, ev ->
            val t = v as? TextView ?: return@ChainedTouch false
            val mine = when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; longFired = false; deep = false
                    downAt = SystemClock.uptimeMillis()
                    val hit = findHitAt(t, ev.x, ev.y)
                    pending = hit
                    claimed = hit?.isPtv == true && hit.badge == null
                    if (hit != null && hit.favoritable) {
                        val r = Runnable {
                            longFired = true
                            if (!claimed) cancelTwitch(t, self)
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
                    // A firm press opens Twitch's long press at once on Android 10 and newer.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ev.classification == MotionEvent.CLASSIFICATION_DEEP_PRESS) deep = true
                    claimed || longFired
                }
                MotionEvent.ACTION_UP -> {
                    cancelLp(t)
                    val hit = pending; pending = null
                    val still = abs(ev.x - downX) <= slop && abs(ev.y - downY) <= slop
                    // Held long enough for Twitch's long press, measured both from the finger and
                    // from when we saw it, or Twitch's menu already took the screen: not ours.
                    val quick = ev.eventTime - ev.downTime < systemLp - 50 &&
                        SystemClock.uptimeMillis() - downAt < systemLp - 50 && !deep && t.hasWindowFocus()
                    if (hit?.badge != null && !longFired && still && quick) {
                        // A quick tap on a badge: ours. A held one was Twitch's long press.
                        cancelTwitch(t, self)
                        BadgeInfo.show(t, hit.badge)
                        true
                    } else {
                        if (hit != null && claimed && !longFired && still) {
                            showEmotePreview(t, hit.name)
                        } else if (hit != null && hit.badge == null && !claimed && !longFired && still) {
                            // A Twitch emote: Twitch opens its own card, and the stats join it there.
                            EmoteStats.noteNativeTap(hit.name)
                        }
                        claimed || longFired
                    }
                }
                MotionEvent.ACTION_CANCEL -> { cancelLp(t); pending = null; claimed = false; false }
                else -> claimed
            }
            if (mine) true else self.pass(v, ev)
        }
        tv.setOnTouchListener(ours)
    }

    /**
     * Keeps our listeners on a TextView when Twitch sets one of its own later: Twitch's goes to the
     * end of our chain instead of replacing it. Ours always add themselves in front, so the chain
     * can never loop back on itself.
     */
    private fun installTouchKeeper() {
        runCatching {
            XposedHelpers.findAndHookMethod(View::class.java, "setOnTouchListener", View.OnTouchListener::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tv = param.thisObject as? TextView ?: return
                        val l = param.args[0]
                        if (l is ChainedTouch) return
                        val head = currentTouch(tv) as? ChainedTouch ?: return
                        var last: ChainedTouch = head
                        while (true) { last = last.prev as? ChainedTouch ?: break }
                        last.prev = l as? View.OnTouchListener
                        param.args[0] = head
                    }
                })
        }.onFailure { log("touch keeper failed: $it") }
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
        // A badge, ours or Twitch's: claimed like our emotes, a tap opens the badge sheet.
        if (BadgeInfo.enabled()) for (sp in cs.getSpans(off, off, ImageSpan::class.java)) {
            val b = BadgeInfo.hitOf(sp) ?: continue
            if (within(sp)) return Hit(b.title ?: "", false, isPtv = true, badge = b)
        }
        for (sp in cs.getSpans(off, off, ImageSpan::class.java)) {
            // A badge's covered text is a lone non-breaking space, so it could never match an
            // emote name, but skipping it explicitly keeps tap handling honest.
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
                EmoteStats.section(ctx, name)?.let { addView(it) }
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
                    // Runs while the dialog shows, stops once it is closed.
                    AnimPause.onImage(img, d)
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
