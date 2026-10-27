package tv.purple.xp

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Timestamp reformatting, `/me` action styling and deleted-message styling.
 *
 * All three need values that live on the chat MESSAGE rather than the rendered line: the real
 * message time (the rendered timestamp slot is already formatted down to minutes, so seconds can
 * never be recovered from it), and the isDeleted / isAction flags.
 *
 * Those fields are reached WITHOUT pinning any obfuscated name. The message is a Kotlin data class
 * whose generated toString() keeps the original labels verbatim through R8, and its declared-field
 * order matches that label order -- independently confirmed, since resolving `sender` purely by
 * label matching landed on exactly the field the smali shows at that position. So:
 *
 *   1. Parse timestampSeconds out of toString() ONCE and find the sole Long field holding that
 *      value. That is self-verifying -- a wrong field cannot hold the right epoch.
 *   2. The four booleans (isDeleted, isDeletedMessageClickable, isAction,
 *      isMessageEffectAnimationsEnabled) are declared immediately after it, in that order, so they
 *      are addressed as offsets from a field we just proved rather than by name or absolute index.
 *
 * If any of that fails to line up the whole object goes inert and chat renders untouched.
 */
object ChatLineStyle {

    const val KEY_TIMESTAMP = "timestamps_v2"
    const val KEY_ME_STYLE = "me_style"
    const val KEY_DELETED = "deleted_messages"

    /** Matches the "Timestamp Format" dropdown order in [PurpleMenu]. Index 0 leaves Twitch alone. */
    private val TIME_PATTERNS = arrayOf(
        "",            // Default
        "h:mm", "h:mm:ss",
        "H:mm", "H:mm:ss",
        "hh:mm", "hh:mm:ss",
        "HH:mm", "HH:mm:ss"
    )

    private const val ME_DISABLED = 0
    private const val ME_COLORED = 1
    private const val ME_ITALIC = 2
    private const val ME_ITALIC_COLORED = 3

    private const val DEL_DEFAULT = 0
    private const val DEL_MOD = 1
    private const val DEL_STRIKE = 2
    private const val DEL_GREY = 3

    private const val GREY = 0xFF6E6E78.toInt()

    /** Marks a line we've already styled -- Twitch re-assembles each line several times. */
    private object Marker

    /** "Username display style": Twitch's own "Name (login)", the international name alone, or
     *  the login alone. Only differs from Twitch for people whose display name is not their
     *  login, which is mostly anyone with a non-Latin display name. */
    const val KEY_NAME_STYLE = "display_name"
    private const val NAME_BOTH = 0
    private const val NAME_INTL = 1
    private const val NAME_LOGIN = 2

    /**
     * Rewrite "DisplayName (login)" in the username slot.
     *
     * Found by the login, which is read off the message, rather than by pattern alone, so a
     * bracketed word in a display name cannot be mistaken for it. Display names cannot contain
     * spaces, so the name is the unbroken run just before the brackets; badges ride in front of
     * it on non-breaking spaces and image placeholders, which end the run.
     *
     * Edited in place on a copy so the name's own colour span survives.
     */
    private fun applyNameStyle(model: Any, args: Array<Any?>?, mode: Int) {
        val login = ChatIdentity.Sender.of(args)?.login?.takeIf { it.isNotBlank() } ?: return
        val slot = XposedHelpers.getObjectField(model, Names.NAME_FIELD) as? CharSequence ?: return
        val text = slot.toString()
        val tag = " ($login)"
        val at = text.indexOf(tag, ignoreCase = true)
        if (at <= 0) return
        val out = SpannableStringBuilder(slot)
        if (mode == NAME_INTL) {
            out.delete(at, at + tag.length)
        } else {
            var start = at
            while (start > 0) {
                val c = text[start - 1]
                if (c.isWhitespace() || c == ' ' || c == '￼') break
                start--
            }
            out.replace(start, at + tag.length, login)
        }
        XposedHelpers.setObjectField(model, Names.NAME_FIELD, out)
    }

    /** Marks a time we prepended to the username slot, so it is never added twice. */
    private object TimeMark

    fun apply(model: Any?, args: Array<Any?>?) {
        model ?: return
        val tsMode = Settings.getInt(KEY_TIMESTAMP, 0)
        val meMode = Settings.getInt(KEY_ME_STYLE, 0)
        val nameMode = Settings.getInt(KEY_NAME_STYLE, NAME_BOTH)
        if (nameMode != NAME_BOTH)
            runCatching { applyNameStyle(model, args, nameMode) }.onFailure { log("CLS name: $it") }
        // Deleted messages are deliberately not consulted here: they are handled at the TextView,
        // so this hook has nothing to do for them.
        if (tsMode == 0 && meMode == ME_DISABLED) return

        val msg = Fields.message(args) ?: return
        if (tsMode != 0) runCatching { applyTimestamp(model, msg, tsMode) }
            .onFailure { log("CLS timestamp: $it") }
        if (meMode != ME_DISABLED)
            runCatching { applyBodyStyle(model, msg, meMode) }
                .onFailure { log("CLS body: $it") }
    }

    /**
     * Rewrite the timestamp slot from the message's own epoch seconds.
     *
     * Shown whenever a format is picked, including when Twitch's own timestamps are off.
     */
    private fun applyTimestamp(model: Any, msg: Any, mode: Int) {
        val pattern = TIME_PATTERNS.getOrNull(mode) ?: return
        if (pattern.isEmpty()) return
        // Shown even when Twitch's own chat timestamps are switched off. Picking a format here
        // used to do nothing at all in that case, since there was nothing to reformat, which is
        // indistinguishable from the setting being broken. The original PurpleTV showed them.
        val existing = XposedHelpers.getObjectField(model, Names.TIME_FIELD) as? CharSequence
        val seconds = Fields.timestampSeconds(msg) ?: return
        val text = runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
                .format(java.util.Date(seconds * 1000L))
        }.getOrNull() ?: return
        // Twitch's own slot carries a trailing space that separates it from the badges.
        val out = "$text "
        if (existing != null && existing.isNotEmpty()) {
            if (out == existing.toString()) return
            XposedHelpers.setObjectField(model, Names.TIME_FIELD, out)
            return
        }
        // Twitch's own timestamps are off. Its line builder then leaves the time slot out of the
        // line altogether (confirmed on device: writing it changed nothing on screen), so the time
        // leads the username slot instead, which is always drawn. Muted, like Twitch's own.
        // Lead whichever slot is drawn first: Twitch's badge slot when the sender has badges,
        // otherwise the username. Matches where Twitch puts its own time, before the badges.
        val badges = runCatching {
            XposedHelpers.getObjectField(model, Names.BADGE_FIELD) as? CharSequence
        }.getOrNull()
        val field = if (badges != null && badges.isNotEmpty()) Names.BADGE_FIELD else Names.NAME_FIELD
        val slot = XposedHelpers.getObjectField(model, field) as? CharSequence ?: return
        if (slot is Spanned && slot.getSpans(0, slot.length, TimeMark::class.java).isNotEmpty()) return
        val sb = SpannableStringBuilder(out)
        sb.setSpan(ForegroundColorSpan(GREY), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TimeMark, 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(slot)
        XposedHelpers.setObjectField(model, field, sb)
    }

    /**
     * Style the message body for `/me` actions.
     *
     * Deleted messages are deliberately NOT handled here -- see [reviveDeleted]. The message's own
     * isDeleted flag still reads false at this point, and the placeholder that replaces the body is
     * built further down the render chain, so anything written here would be styling text that is
     * about to be thrown away. That is exactly how this feature failed the first time.
     */
    private fun applyBodyStyle(model: Any, msg: Any, meMode: Int) {
        if (meMode == ME_DISABLED || Fields.isAction(msg) != true) return

        val body = XposedHelpers.getObjectField(model, Names.BODY_FIELD) as? CharSequence ?: return
        if (body.isEmpty()) return
        if (body is Spanned && body.getSpans(0, body.length, Marker::class.java).isNotEmpty()) return

        val out = SpannableStringBuilder(body)
        val end = out.length

        if (meMode == ME_ITALIC || meMode == ME_ITALIC_COLORED)
            out.setSpan(StyleSpan(Typeface.ITALIC), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (meMode == ME_COLORED || meMode == ME_ITALIC_COLORED)
            // Twitch tints an action line in the sender's own chat colour; reuse it when the
            // sender has one set, and otherwise leave the default colour alone.
            Fields.senderColor(msg)?.let {
                out.setSpan(ForegroundColorSpan(it), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

        out.setSpan(Marker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        XposedHelpers.setObjectField(model, Names.BODY_FIELD, out)
        if (meSeen++ < 3) log("CLS /me styled (mode $meMode): ${body.take(40)}")
    }

    private var meSeen = 0

    /**
     * Put a deleted message's original text back, at the moment the line reaches its TextView.
     *
     * Rendering is the last point where the answer is knowable, and the only one that catches all
     * the ways a deleted line reaches the screen: deleted while on screen, already deleted when it
     * arrived, and re-bound from scrollback.
     *
     * Recovery needs nothing obfuscated. When Twitch builds the placeholder it hands the original
     * rendered text to the ClickableSpan that powers its own tap-to-reveal, then lays that span
     * over exactly the placeholder's range. ClickableSpan is a framework class, and the original is
     * the only declared field on it whose TYPE is android.text.SpannedString -- so the span is found
     * by type, the text by type, and the span's own bounds say precisely what to replace. Nothing
     * here depends on a name R8 can rewrite; if the shape ever changes, nothing matches and the
     * placeholder is left exactly as Twitch rendered it.
     *
     * @return replacement text, or null to leave [cs] untouched.
     */
    fun reviveDeleted(ctx: android.content.Context, cs: CharSequence): CharSequence? {
        val mode = delMode()
        if (mode == DEL_DEFAULT) return null

        // This runs on EVERY TextView write in the whole app, so it rejects in O(1) before doing
        // anything that costs. Both renderers put the placeholder last, so a line that does not end
        // in '>' (or the bidi isolate that wraps it in RTL) cannot be one. The rejection ORDER here
        // is deliberate: mode, then length, then last char, then the string compare, then spans.
        val n = cs.length
        if (n < 3) return null
        val last = cs[n - 1]
        if (last != '>' && last != PDI) return null

        val spanned = cs as? Spanned ?: return null
        if (spanned.getSpans(0, spanned.length, Marker::class.java).isNotEmpty()) return null

        val needle = needle(ctx) ?: return null
        val cut = when {
            n >= needle.length && endsAt(cs, n - needle.length, needle) -> n - needle.length
            // RTL wraps the same placeholder in bidi isolates.
            last == PDI && n >= needle.length + 2 && cs[n - needle.length - 2] == FSI &&
                endsAt(cs, n - needle.length - 1, needle) -> n - needle.length - 2
            else -> return null
        }
        if (cut <= 0) return null

        val body = fromCarrier(spanned, cut) ?: fromRevealSpan(spanned, cut) ?: run {
            if (!tripwire) {
                tripwire = true
                log("CLS saw a deleted line but recovered nothing — carrier did not survive")
            }
            return null
        }

        val out = SpannableStringBuilder(cs)
        out.replace(cut, n, body)
        when (mode) {
            DEL_STRIKE -> out.setSpan(StrikethroughSpan(), cut, cut + body.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            DEL_GREY -> out.setSpan(ForegroundColorSpan(GREY), cut, cut + body.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            DEL_MOD -> Unit   // recovered text, unstyled: what a moderator sees
        }
        out.setSpan(Marker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (deletedLogged < 3) {
            deletedLogged++
            log("CLS revived a deleted message (${body.length} chars, mode $mode)")
        }
        return out
    }

    /**
     * Our own carrier, and the reason this works for ordinary viewers.
     *
     * Twitch attaches its tap-to-reveal span only for moderators, so for everyone else the original
     * text is genuinely gone by the time the line is rendered -- confirmed on device, where a
     * deleted line arrived carrying nothing at all. But the renderer builds the placeholder by
     * SLICING the assembled line up to the end of the username and appending to that slice, and a
     * slice copies every span overlapping it. So a span parked on the username slot survives into
     * the placeholder, still holding the body the renderer discarded. That is also why a deleted
     * line still shows the sender's badges and name colour.
     *
     * The carrier must end EXACTLY where the placeholder begins, which is what makes the match
     * exact rather than a guess.
     */
    private fun fromCarrier(spanned: Spanned, cut: Int): CharSequence? {
        for (sp in spanned.getSpans(0, spanned.length, Original::class.java)) {
            if (spanned.getSpanEnd(sp) == cut && sp.body.isNotEmpty()) return sp.body
        }
        return null
    }

    /** Twitch's own tap-to-reveal span, present for moderators. Falls back to this so a moderator
     *  still gets the original if our carrier was ever dropped. */
    private fun fromRevealSpan(spanned: Spanned, cut: Int): CharSequence? {
        for (sp in spanned.getSpans(0, spanned.length, android.text.style.ClickableSpan::class.java)) {
            val original = originalOf(sp) ?: continue
            // This span carries the message body on one render path and the WHOLE assembled line,
            // header and all, on the other. Told apart by asking the text: if what came back opens
            // with this line's own header, it is a whole line, and the body is what follows.
            val header = spanned.subSequence(0, cut).toString()
            if (original.length >= header.length &&
                original.subSequence(0, header.length).toString() == header) {
                return original.subSequence(header.length, original.length)
            }
            return original
        }
        return null
    }

    @Volatile private var tripwire = false

    /**
     * Rides the chat line's USERNAME slot carrying that line's real message body.
     *
     * A fresh instance per line, deliberately: a SpannableStringBuilder copies a span object only
     * when it is not already attached to the destination, so a shared singleton would land on the
     * first line and silently skip every one after it.
     */
    private class Original(val body: CharSequence)

    /**
     * Park the body on the username slot, so it is still there if this line is later deleted.
     *
     * Must run LAST in the chat-line hook: [ChatIdentity] rewrites this slot wholesale for badges
     * and pronouns and would drop a carrier planted earlier, and the body must already carry our
     * injected emotes and highlight before it is captured.
     *
     * SPAN_INCLUSIVE_EXCLUSIVE is load-bearing rather than a style choice. An inclusive end is a
     * POINT, so text appended at exactly that offset falls INSIDE the span -- and both the line
     * assembler and the placeholder renderer append at exactly this offset. With an inclusive end
     * the carrier would swallow them and its end would no longer mark where the placeholder begins.
     */
    fun plantOriginal(model: Any?) {
        model ?: return
        if (delMode() == DEL_DEFAULT) return
        var body = XposedHelpers.getObjectField(model, Names.BODY_FIELD) as? CharSequence ?: return
        if (body.isEmpty()) return
        // A message that was ALREADY deleted when it arrived (backlog on joining a channel) has its
        // placeholder in place by now, so saving the body would just save "<message deleted>"
        // again. On that path Twitch parks the real text on its tap-to-reveal span, for everyone,
        // so take that instead. Ordinary bodies only carry link and mention spans, which have no
        // SpannedString field, so this cannot misfire on a normal line.
        (body as? Spanned)?.let { sp ->
            sp.getSpans(0, sp.length, android.text.style.ClickableSpan::class.java)
                .firstNotNullOfOrNull { originalOf(it) }?.let { body = it }
        }
        val name = XposedHelpers.getObjectField(model, Names.NAME_FIELD) as? CharSequence ?: return
        if (name.isEmpty()) return
        // Copied so the badge, pronoun and colour spans already on the slot survive.
        val out = SpannableStringBuilder(name)
        out.setSpan(Original(body), 0, out.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        XposedHelpers.setObjectField(model, Names.NAME_FIELD, out)
    }

    /** The bidi isolate the renderer wraps the placeholder in under an RTL layout direction. */
    private const val FSI = '⁨'

    /** "<message deleted>", read from the app's own resources so it follows the app's language.
     *  Cached against the Resources it came from, so a locale or configuration change re-reads. */
    @Volatile private var needleText: String? = null
    @Volatile private var needleRes: android.content.res.Resources? = null

    private fun needle(ctx: android.content.Context): String? {
        val res = ctx.resources ?: return null
        needleText?.let { if (needleRes === res) return it }
        return runCatching {
            val id = res.getIdentifier("chat_message_deleted", "string", ctx.packageName)
            if (id == 0) return null
            ("<" + res.getString(id) + ">").also { needleText = it; needleRes = res }
        }.getOrNull()
    }

    /** True for Twitch's own deleted-message line, shown because "Deleted messages" is on Default. */
    fun isPlainPlaceholder(ctx: android.content.Context, cs: CharSequence): Boolean {
        if (delMode() != DEL_DEFAULT) return false
        val needle = needle(ctx) ?: return false
        return cs.length > needle.length && endsAt(cs, cs.length - needle.length, needle)
    }

    private fun endsAt(cs: CharSequence, at: Int, s: String): Boolean {
        if (at < 0 || at + s.length > cs.length) return false
        for (i in s.indices) if (cs[at + i] != s[i]) return false
        return true
    }

    private const val PDI = '⁩'

    /** [KEY_DELETED], re-read only when the settings actually change. */
    @Volatile private var delRev = -1
    @Volatile private var delModeCache = DEL_DEFAULT

    private fun delMode(): Int {
        val r = Settings.rev
        if (delRev != r) {
            delModeCache = Settings.getInt(KEY_DELETED, DEL_DEFAULT)
            // Don't latch the default that comes back before the module has a context, or the
            // feature would stay off until the next settings write.
            if (Settings.isReady()) delRev = r
        }
        return delModeCache
    }

    /** The pre-deletion text carried by a tap-to-reveal span, or null if this is not one. */
    private fun originalOf(sp: Any): CharSequence? = runCatching {
        for (f in sp.javaClass.declaredFields) {
            if (f.type != android.text.SpannedString::class.java) continue
            f.isAccessible = true
            val original = f.get(sp) as? CharSequence ?: continue
            if (original.isNotEmpty()) return@runCatching original
        }
        null
    }.getOrNull()

    @Volatile private var deletedLogged = 0


    /** Structural, one-time resolution of the message fields this file needs. */
    private object Fields {

        private const val MSG_PREFIX = "LiveChatMessage("
        private val TS = Regex("timestampSeconds=(\\d+)")

        @Volatile private var argIndex = -1
        @Volatile private var msgClass: Class<*>? = null
        @Volatile private var resolved = false
        @Volatile private var failed = false

        private var fTimestamp: Field? = null
        private var fDeleted: Field? = null
        private var fAction: Field? = null
        private var fColor: Field? = null

        fun message(args: Array<Any?>?): Any? {
            args ?: return null
            if (failed) return null
            val cached = msgClass
            if (cached != null) {
                val a = args.getOrNull(argIndex)
                if (a != null && cached.isInstance(a)) { ensure(a); return if (failed) null else a }
            }
            for (i in args.indices) {
                val a = args[i] ?: continue
                val cn = a.javaClass.name
                if (cn.startsWith("java.") || cn.startsWith("android.")) continue
                val s = runCatching { a.toString() }.getOrNull() ?: continue
                if (!s.startsWith(MSG_PREFIX)) continue
                argIndex = i; msgClass = a.javaClass
                ensure(a)
                return if (failed) null else a
            }
            return null
        }

        private fun ensure(msg: Any) {
            if (resolved || failed) return
            synchronized(this) {
                if (resolved || failed) return
                runCatching { resolve(msg) }.onFailure { log("CLS resolve: $it") }
                if (fTimestamp == null) {
                    failed = true
                    log("CLS could not locate the message timestamp — line styling disabled")
                } else {
                    resolved = true
                    log("CLS fields: ts=${fTimestamp?.name} deleted=${fDeleted?.name}" +
                        " action=${fAction?.name} color=${fColor?.name}")
                }
            }
        }

        private fun resolve(msg: Any) {
            val seconds = TS.find(msg.toString())?.groupValues?.get(1)?.toLongOrNull() ?: return
            val declared = msg.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }

            // Anchor: the one field actually holding that epoch. Value-matched, so it cannot
            // silently bind to the wrong field the way a positional guess could.
            var anchor = -1
            for ((idx, f) in declared.withIndex()) {
                if (f.type != java.lang.Long::class.java && f.type != java.lang.Long.TYPE) continue
                val v = runCatching { f.isAccessible = true; f.get(msg) }.getOrNull()
                if ((v as? Long) == seconds) { anchor = idx; fTimestamp = f; break }
            }
            if (anchor < 0) return

            // isDeleted, isDeletedMessageClickable, isAction, isMessageEffectAnimationsEnabled are
            // declared immediately after it, in that order. Offsets from a proven field, never
            // absolute positions.
            fun boolAt(i: Int): Field? = declared.getOrNull(i)
                ?.takeIf { it.type == java.lang.Boolean.TYPE || it.type == java.lang.Boolean::class.java }
                ?.also { it.isAccessible = true }
            fDeleted = boolAt(anchor + 1)
            fAction = boolAt(anchor + 3)

            // senderColor is the only Integer on the message.
            fColor = declared.firstOrNull { it.type == java.lang.Integer::class.java }
                ?.also { it.isAccessible = true }
        }

        fun timestampSeconds(msg: Any): Long? =
            runCatching { fTimestamp?.get(msg) as? Long }.getOrNull()

        fun isDeleted(msg: Any): Boolean? =
            runCatching { fDeleted?.get(msg) as? Boolean }.getOrNull()

        fun isAction(msg: Any): Boolean? =
            runCatching { fAction?.get(msg) as? Boolean }.getOrNull()

        fun senderColor(msg: Any): Int? =
            runCatching { (fColor?.get(msg) as? Int)?.let { 0xFF000000.toInt() or it } }.getOrNull()
    }
}
