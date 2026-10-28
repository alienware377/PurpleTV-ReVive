package tv.purple.xp

import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field

/**
 * Third-party badges and pronoun tags on the chat line's USERNAME slot.
 *
 * The hard part of this feature was never the badge APIs — it was getting the author's numeric
 * user id (badges key on it) and login (pronouns key on it) without pinning another obfuscated
 * name. It turns out we already had them: [Names.ASSEMBLER].[Names.ASSEMBLER_METHOD] is static and
 * one of its arguments IS the chat message, which carries a sender object holding both.
 *
 * [Sender] resolves that chain STRUCTURALLY, with zero pinned field names, by exploiting the one
 * thing R8 does not rewrite: string constants. Both classes are Kotlin data classes, so their
 * generated toString() embeds the ORIGINAL field labels — "LiveChatMessage(messageId=…" and
 * "ChatMessageUser(userId=…, username=…" — verbatim in the dex no matter how the class, method and
 * field names were mangled. We find the message argument and the sender field by matching those
 * labels once, cache the reflected Field, and read ids off toString() from then on.
 *
 * So this feature adds NO new obfuscation pin. It rides entirely on the ik5.b anchor that emote
 * injection already carries, and degrades to a clean no-op if that anchor ever goes stale.
 */
object ChatIdentity {

    /** Marks a username slot we've already processed, so a RecyclerView rebind can't double-inject.
     *  Deliberately distinct from EmoteHooks' body marker — the two features write different
     *  fields and must never see each other's marks. */
    private object NameMarker

    /** Our badge span. Subclasses ImageSpan so the existing animation driver still starts animated
     *  (DankChat / Chatsen GIF) badges, but is its own type so emote hit-testing can skip it. */
    class PtvBadgeSpan(d: Drawable, val title: String) : ImageSpan(d, ALIGN_BASELINE)

    /** Non-breaking space: a one-character carrier for a badge ImageSpan. It never matches an
     *  emote name, so the tap-to-preview scan can't mistake a badge for an emote. */
    private const val CARRIER = " "

    private const val TAG_COLOR = 0xFFADADB8.toInt()

    @Volatile private var probeLogged = false

    private const val TRACE_LIMIT = 25
    @Volatile private var traced = 0
    private val tracedLogins: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Prepend badges and append a pronoun tag to the chat line's username slot.
     * [args] are the arguments of the hooked assembler call, which carry the chat message.
     */
    fun inject(model: Any?, args: Array<Any?>) {
        model ?: return
        val wantBadges = BadgeRepo.anyEnabled()
        val wantPronouns = Pronouns.enabled()
        if (!wantBadges && !wantPronouns) return

        val name = runCatching {
            XposedHelpers.getObjectField(model, Names.NAME_FIELD) as? CharSequence
        }.getOrNull() ?: return
        if (name.isEmpty()) return
        if (name is Spanned &&
            name.getSpans(0, name.length, NameMarker::class.java).isNotEmpty()) return

        val sender = Sender.of(args) ?: return
        probeSlots(model)
        Sender.probeMessageLabels(args)

        val badges = if (wantBadges) BadgeRepo.forUser(sender.userId) else emptyList()
        val pronoun = if (wantPronouns) Pronouns.forLogin(sender.login) else null
        // Bounded trace of the first few lines so a silent no-op is distinguishable from
        // "these particular chatters simply have no badges or pronouns set".
        // Deduped by login: Twitch re-assembles each line several times, so an undeduped trace
        // burns its whole budget on three or four chatters.
        if (traced < TRACE_LIMIT && tracedLogins.add(sender.login)) {
            traced++
            log("ID ${sender.login}#${sender.userId} badges=${badges.size} pronoun=$pronoun")
        }
        if (badges.isEmpty() && pronoun == null) return

        val out = SpannableStringBuilder(name)  // copies Twitch's own colour/bold name spans

        // Pronoun first, so its insertion point isn't shifted by the badge inserts at index 0.
        // The slot reads "username: ", so the tag belongs just before the colon. A /me action line
        // ends the slot with a single space instead (Twitch picks isAction ? " " : ": "), and the
        // body follows with no space of its own, so there the tag goes before that trailing space
        // or it would run straight into the message text. (The action flag lives on the MESSAGE,
        // not on this line model; the line model's boolean is the layout direction. See
        // ChatLineStyle.)
        if (pronoun != null) {
            val colon = out.lastIndexOf(": ")
            val at = if (colon >= 0) colon else {
                var e = out.length
                while (e > 0 && out[e - 1].isWhitespace()) e--
                e
            }
            val tag = " $pronoun"
            out.insert(at, tag)
            out.setSpan(RelativeSizeSpan(0.8f), at, at + tag.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(TAG_COLOR), at, at + tag.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Insert in reverse at index 0 so the rendered left-to-right order matches manifest order.
        // SpannableStringBuilder keeps every existing span's offsets correct across an insert, so
        // Twitch's username colouring survives untouched.
        for (i in badges.indices.reversed()) {
            out.insert(0, CARRIER)
            out.setSpan(PtvBadgeSpan(badges[i], ""), 0, CARRIER.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        out.setSpan(NameMarker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        XposedHelpers.setObjectField(model, Names.NAME_FIELD, out)
    }

    /**
     * One-shot diagnostic over the chat-line model's CharSequence slots.
     *
     * Slot 'b' sits between the timestamp and the username, which is structurally where Twitch's
     * OWN badges must live — but that could not be proven from the dex (the value reaches the
     * constructor through a recycled scratch register). Everything above therefore writes to slot
     * 'c', which is proven, so a wrong guess about 'b' costs nothing. This log is how we confirm
     * it: the badge slot is the one carrying ImageSpans and almost no text.
     */
    private fun probeSlots(model: Any) {
        if (probeLogged) return
        probeLogged = true
        runCatching {
            for (f in listOf("a", "b", "c", "d")) {
                val cs = runCatching {
                    XposedHelpers.getObjectField(model, f) as? CharSequence
                }.getOrNull() ?: continue
                val imgs = (cs as? Spanned)?.getSpans(0, cs.length, ImageSpan::class.java)?.size ?: 0
                log("SLOT $f len=${cs.length} imageSpans=$imgs text='${cs.toString().take(40)}'")
            }
        }
    }

    /**
     * Pulls the author's numeric user id and login off the hooked call's arguments.
     *
     * Resolution is one-time and self-verifying: we look for the argument whose toString() starts
     * with "LiveChatMessage(", then the field on it whose value's toString() starts with
     * "ChatMessageUser(". Those literals are Kotlin-generated and survive R8 verbatim. After that
     * every message costs one field read plus a toString() of a three-field object.
     */
    object Sender {

        class Info(val userId: String, val login: String)

        private const val MSG_PREFIX = "LiveChatMessage("
        private const val USER_PREFIX = "ChatMessageUser("

        private val ID = Regex("userId=(\\d+)")
        private val LOGIN = Regex("username=([^,)]*)")

        @Volatile private var argIndex = -1
        @Volatile private var msgClass: Class<*>? = null
        @Volatile private var senderField: Field? = null
        /** Failed resolution attempts. A message CAN legitimately have no sender (system notices,
         *  moderation events), so one miss proves nothing — but a stale anchor would otherwise
         *  make us rescan every declared field on every chat line forever. Give up after enough
         *  consecutive failures that a real message must have come through by now. */
        private const val GIVE_UP_AFTER = 40
        @Volatile private var failures = 0
        @Volatile private var gaveUp = false

        fun of(args: Array<Any?>?): Info? {
            args ?: return null
            if (gaveUp) return null
            val msg = message(args) ?: return null
            val f = senderField ?: resolveSenderField(msg) ?: return null
            val user = runCatching { f.get(msg) }.getOrNull() ?: return null
            val s = runCatching { user.toString() }.getOrNull() ?: return null
            val id = ID.find(s)?.groupValues?.get(1) ?: return null
            val login = LOGIN.find(s)?.groupValues?.get(1)?.trim() ?: return null
            if (login.isEmpty() || login == "null") return null
            return Info(id, login)
        }

        private val LABEL = Regex("([A-Za-z_][A-Za-z0-9_]*)=")
        @Volatile private var labelsLogged = false

        /**
         * One-shot dump of the chat message's Kotlin data-class field LABELS.
         *
         * Only the names are emitted, never the values -- the message toString carries the actual
         * chat text, and there is no reason to put a stranger's messages in logcat to learn what
         * fields exist. Used to find a real message timestamp: the rendered timestamp slot is
         * already formatted ("2:28 "), so seconds can't be recovered from it.
         */
        fun probeMessageLabels(args: Array<Any?>?) {
            if (labelsLogged) return
            args ?: return
            val msg = runCatching { message(args) }.getOrNull() ?: return
            labelsLogged = true
            runCatching {
                val s = msg.toString()
                log("MSG fields: " + LABEL.findAll(s).map { it.groupValues[1] }.distinct()
                    .joinToString(","))
            }
        }

        private fun message(args: Array<Any?>): Any? {
            val cached = msgClass
            if (cached != null) {
                val a = args.getOrNull(argIndex)
                if (a != null && cached.isInstance(a)) return a
            }
            for (i in args.indices) {
                val a = args[i] ?: continue
                // Cheap pre-filter: framework and primitive-ish arguments can't be the message,
                // and calling toString() on them is pointless.
                val cn = a.javaClass.name
                if (cn.startsWith("java.") || cn.startsWith("android.")) continue
                val s = runCatching { a.toString() }.getOrNull() ?: continue
                if (!s.startsWith(MSG_PREFIX)) continue
                argIndex = i; msgClass = a.javaClass
                log("SENDER message arg=$i class=$cn")
                return a
            }
            return null
        }

        private fun resolveSenderField(msg: Any): Field? {
            for (f in msg.javaClass.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive) continue
                val v = runCatching { f.isAccessible = true; f.get(msg) }.getOrNull() ?: continue
                val s = runCatching { v.toString() }.getOrNull() ?: continue
                if (!s.startsWith(USER_PREFIX)) continue
                senderField = f
                log("SENDER field='${f.name}' type=${f.type.name}")
                return f
            }
            if (++failures >= GIVE_UP_AFTER) {
                gaveUp = true
                log("SENDER unresolved on ${msg.javaClass.name} after $failures messages" +
                    " — badges/pronouns off (anchor likely stale)")
            }
            return null
        }
    }
}
