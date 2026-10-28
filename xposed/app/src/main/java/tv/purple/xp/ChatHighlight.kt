package tv.purple.xp

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import de.robv.android.xposed.XposedHelpers

/**
 * Keyword highlighting and @mention colouring.
 *
 * Both work off the chat message's TOKEN list rather than the rendered text (see [ChatTokens]),
 * which is what makes mention detection exact: a mention token carries an isLocalUser flag, so
 * "did this message mention me" needs no knowledge of the logged-in account and no substring
 * matching against a username that might also appear as ordinary text.
 *
 * The highlight itself is drawn with a [LineBackgroundSpan], which paints the full width of the
 * TextView line. A BackgroundColorSpan would only cover the glyphs, leaving a ragged edge on
 * wrapped messages, and colouring the row View directly would fight both Twitch's own background
 * writes and [ChatAppearance]'s alternating rows.
 */
object ChatHighlight {

    const val KEY_MENTION_ENABLED = "mention_highlight"
    const val KEY_MENTION_COLOR = "user_mention_color"
    const val KEY_HIGHLIGHT_WORDS = "highlight_keywords"

    /** Twitch's own mention tint, used until the user picks something else. */
    const val MENTION_COLOR_DEFAULT = 0x4D9146FF

    private object Marker

    /** Keyword match modes, mirroring the original's three types. */
    enum class Type { USERNAME, SENSITIVE, INSENSITIVE }

    class Keyword(val word: String, val type: Type, val color: Int)

    // ---------------------------------------------------------------- store
    //
    // Serialised one entry per line as "type|color|word". The word goes last so it may itself
    // contain the separator without needing escaping.

    fun keywords(): List<Keyword> {
        val raw = Settings.getString(KEY_HIGHLIGHT_WORDS)
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Keyword>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val a = line.indexOf('|'); if (a < 0) continue
            val b = line.indexOf('|', a + 1); if (b < 0) continue
            val type = when (line.substring(0, a)) {
                "U" -> Type.USERNAME
                "S" -> Type.SENSITIVE
                else -> Type.INSENSITIVE
            }
            val color = line.substring(a + 1, b).toIntOrNull() ?: continue
            val word = line.substring(b + 1)
            if (word.isNotBlank()) out.add(Keyword(word, type, color))
        }
        return out
    }

    fun save(list: List<Keyword>) {
        Settings.setString(KEY_HIGHLIGHT_WORDS, list.joinToString("\n") {
            val t = when (it.type) {
                Type.USERNAME -> "U"; Type.SENSITIVE -> "S"; Type.INSENSITIVE -> "I"
            }
            "$t|${it.color}|${it.word}"
        })
    }

    fun add(k: Keyword) = save(keywords() + k)
    fun remove(index: Int) = save(keywords().filterIndexed { i, _ -> i != index })

    // ---------------------------------------------------------------- render

    fun apply(model: Any?, args: Array<Any?>?) {
        model ?: return
        val mentionOn = Settings.get(KEY_MENTION_ENABLED, false)
        val words = keywords()
        if (!mentionOn && words.isEmpty()) return

        val msg = ChatTokens.message(args) ?: return
        val color = matchColor(msg, args, mentionOn, words) ?: return

        // Paint from the BODY slot: it is the one slot present on every line and it spans the
        // wrapped remainder, so the bar covers the whole message.
        val body = XposedHelpers.getObjectField(model, Names.BODY_FIELD) as? CharSequence ?: return
        if (body is Spanned && body.getSpans(0, body.length, Marker::class.java).isNotEmpty()) return
        val out = SpannableStringBuilder(body)
        out.setSpan(Bar(color), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(Marker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        XposedHelpers.setObjectField(model, Names.BODY_FIELD, out)

        // The line's first visual row starts at the timestamp, so without a second span the bar
        // would begin part-way across. Overdraw where they meet is harmless.
        val head = XposedHelpers.getObjectField(model, Names.TIME_FIELD) as? CharSequence
        if (head != null && head.isNotEmpty()) {
            val h = SpannableStringBuilder(head)
            h.setSpan(Bar(color), 0, h.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            XposedHelpers.setObjectField(model, Names.TIME_FIELD, h)
        }
    }

    /** First matching colour, checking @mention, then username rules, then per-word rules. */
    private fun matchColor(
        msg: Any, args: Array<Any?>?, mentionOn: Boolean, words: List<Keyword>
    ): Int? {
        if (mentionOn && ChatTokens.mentionsLocalUser(msg))
            return Settings.getInt(KEY_MENTION_COLOR, MENTION_COLOR_DEFAULT)
        if (words.isEmpty()) return null

        val userRules = words.filter { it.type == Type.USERNAME }
        if (userRules.isNotEmpty()) {
            // Hand over the real hook args, not a one-item array: Sender caches where the
            // message sits, and a different layout here made it rescan and log on every line.
            val login = ChatIdentity.Sender.of(args)?.login?.lowercase()
            if (login != null) userRules.firstOrNull { it.word.lowercase() == login }
                ?.let { return it.color }
        }

        val sensitive = words.filter { it.type == Type.SENSITIVE }
        val insensitive = words.filter { it.type == Type.INSENSITIVE }
        if (sensitive.isEmpty() && insensitive.isEmpty()) return null

        for (text in ChatTokens.textOf(msg)) {
            for (w in text.split(' ', '\t', '\n')) {
                if (w.isBlank()) continue
                sensitive.firstOrNull { it.word == w }?.let { return it.color }
                val lw = w.lowercase()
                insensitive.firstOrNull { it.word.lowercase() == lw }?.let { return it.color }
            }
        }
        return null
    }

    /** Full-width row tint. Paints behind each covered line across the whole TextView width. */
    private class Bar(private val color: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, p: Paint, left: Int, right: Int, top: Int,
            baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int
        ) {
            val prev = p.color
            p.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), p)
            p.color = prev
        }
    }
}
