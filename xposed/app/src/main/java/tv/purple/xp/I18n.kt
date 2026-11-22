package tv.purple.xp

import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * PurpleTV's own text in the phone's language, or the one picked under Info.
 *
 * The English text itself is the key: i18n/<code>.json (Java resources in the module, made from
 * tools/extract_ui_strings.py) maps each English string to its translation. Text with a name or a
 * number in it is matched against templates, where {0}, {1}, ... stand for the parts that vary;
 * those parts are translated too when they are known strings (a language name, say), and the
 * translation may put them in another order. Anything not in the file stays English, so a
 * missing line is never worse than before.
 */
object I18n {

    /** The place in [LANGS] of the language picked; 0 follows the phone. */
    const val KEY = "app_language_i"

    /** Codes in menu order, each with its own name for itself. "" follows the phone. */
    val LANGS: List<Pair<String, String>> = listOf(
        "" to "Phone language", "en" to "English", "es" to "Español", "pt" to "Português", "de" to "Deutsch",
        "fr" to "Français", "it" to "Italiano", "ru" to "Русский", "zh" to "中文", "ar" to "العربية",
        "ja" to "日本語", "ko" to "한국어", "tl" to "Filipino", "lt" to "Lietuvių"
    )

    private class Table(val strings: Map<String, String>, val templates: List<Pair<Regex, String>>)

    @Volatile private var loaded: Pair<String, Table?>? = null
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * The language asked for: the one picked, else the phone's, whether or not there is a file
     * for it. Text is only shown in it when there is (see [shownLang]); otherwise it stays English.
     */
    fun lang(): String {
        val picked = if (Settings.isReady()) LANGS.getOrNull(Settings.getInt(KEY, 0))?.first.orEmpty() else ""
        if (picked.isNotEmpty()) return picked
        val l = Locale.getDefault()
        return when (val c = l.language) {
            "fil" -> "tl"
            "in" -> "id"
            else -> c
        }
    }

    /**
     * The language text is really shown in: [lang] when there is a file for it, else English. A
     * name put into English text (a language's, say) is written in this one, so a phone set to a
     * language PurpleTV has no file for gets "Portuguese", not its own word for it, in English text.
     */
    fun shownLang(): String = if (table() != null) lang() else "en"

    /** True for right to left languages, so a page can lay itself out that way. */
    fun rtl(): Boolean = lang() == "ar"

    /** [s] in the language in use. */
    fun t(s: CharSequence?): String {
        val text = s?.toString() ?: return ""
        if (text.isEmpty()) return text
        val table = table() ?: return text
        cache[text]?.let { return it }
        val out = table.strings[text] ?: template(table, text) ?: text
        if (cache.size > 2000) cache.clear()
        cache[text] = out
        return out
    }

    private fun template(table: Table, text: String): String? {
        for ((re, tr) in table.templates) {
            val m = re.matchEntire(text) ?: continue
            var out = tr
            for (i in 1..m.groupValues.lastIndex) {
                val part = m.groupValues[i]
                out = out.replace("{${i - 1}}", table.strings[part] ?: part)
            }
            return out
        }
        return null
    }

    private fun table(): Table? {
        val code = lang()
        val cur = loaded
        if (cur != null && cur.first == code) return cur.second
        cache.clear()
        val t = if (code == "en") null else runCatching { load(code) }.getOrNull()
        loaded = code to t
        return t
    }

    private fun load(code: String): Table? {
        val txt = I18n::class.java.classLoader?.getResourceAsStream("i18n/$code.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
        val o = JSONObject(txt)
        val strings = HashMap<String, String>()
        o.optJSONObject("strings")?.let { s -> for (k in s.keys()) s.optString(k).takeIf { it.isNotEmpty() }?.let { strings[k] = it } }
        val templates = ArrayList<Pair<Regex, String>>()
        o.optJSONObject("templates")?.let { s ->
            for (k in s.keys()) {
                val tr = s.optString(k).takeIf { it.isNotEmpty() } ?: continue
                templates.add(pattern(k) to tr)
            }
        }
        // Longer templates first: they say more, so they win over a short one that also matches.
        templates.sortByDescending { it.first.pattern.length }
        return Table(strings, templates)
    }

    /** "Download the {0}?" as a regex with one group per {n}, in order. */
    internal fun pattern(tpl: String): Regex {
        val sb = StringBuilder()
        var i = 0
        val holes = Regex("\\{\\d+\\}")
        for (m in holes.findAll(tpl)) {
            sb.append(Regex.escape(tpl.substring(i, m.range.first)))
            sb.append("(.+?)")
            i = m.range.last + 1
        }
        sb.append(Regex.escape(tpl.substring(i)))
        return Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * An AlertDialog.Builder that shows its title, message, buttons and list items through [I18n.t],
 * so every dialog of the module is translated without touching each one.
 */
class TBuilder(ctx: android.content.Context, theme: Int) : android.app.AlertDialog.Builder(ctx, theme) {
    override fun setTitle(title: CharSequence?): android.app.AlertDialog.Builder = super.setTitle(I18n.t(title))
    override fun setMessage(message: CharSequence?): android.app.AlertDialog.Builder = super.setMessage(I18n.t(message))
    override fun setPositiveButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setPositiveButton(I18n.t(text), listener)
    override fun setNegativeButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setNegativeButton(I18n.t(text), listener)
    override fun setNeutralButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setNeutralButton(I18n.t(text), listener)
    override fun setItems(items: Array<out CharSequence>?, listener: android.content.DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setItems(items?.map { I18n.t(it) }?.toTypedArray(), listener)
    override fun setSingleChoiceItems(items: Array<out CharSequence>?, checkedItem: Int, listener: android.content.DialogInterface.OnClickListener?): android.app.AlertDialog.Builder =
        super.setSingleChoiceItems(items?.map { I18n.t(it) }?.toTypedArray(), checkedItem, listener)
    override fun setMultiChoiceItems(items: Array<out CharSequence>?, checkedItems: BooleanArray?, listener: android.content.DialogInterface.OnMultiChoiceClickListener?): android.app.AlertDialog.Builder =
        super.setMultiChoiceItems(items?.map { I18n.t(it) }?.toTypedArray(), checkedItems, listener)
}
