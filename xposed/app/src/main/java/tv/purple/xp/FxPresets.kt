package tv.purple.xp

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Presets for the video and audio filters: a few built in, and your own, saved from whatever the
 * panel is set to now. A preset holds every setting of its tab (values, tone ranges, extras and
 * on/off switches); applying one replaces the tab's settings, so filters it does not mention go
 * back to off. Your presets are ordinary settings, so they sync between devices too.
 */
object FxPresets {

    enum class Kind(val title: String, val prefix: String, val key: String) {
        VIDEO("Video", "vf_", "fx_presets_video"), AUDIO("Audio", "af_", "fx_presets_audio")
    }

    /** Settings a preset covers: the tab's own, minus panel layout, tips and the master switch. */
    private fun covers(kind: Kind, k: String) = k.startsWith(kind.prefix) &&
        !k.startsWith(kind.prefix + "ui_") && !k.startsWith(kind.prefix + "tip") &&
        k != VideoFilters.KEY_ON && k != AudioFilters.KEY_ON && k != GlVideoFilter.KEY_FORCE

    class Preset(val name: String, val values: Map<String, Any?>, val builtIn: Boolean)

    // Built-in looks, written as "filter key" to amount (and "key_lo"/"key_hi" for a tone range).
    private fun v(vararg p: Pair<String, Int>) = p.associate { (k, n) -> (if ('_' in k && (k.endsWith("_lo") || k.endsWith("_hi"))) "vf_$k" else "vf_${k}_a") to n }
    private fun a(vararg p: Pair<String, Int>) = p.associate { (k, n) -> "af_$k" to n }

    private val BUILT_IN = mapOf(
        Kind.VIDEO to listOf(
            Preset("Brighten dark streams", v("d_exp" to 70, "d_gamma" to 40, "shadows" to 25), true),
            Preset("Fix washed-out streams", v("d_black" to 80, "d_con" to 60, "vib" to 20), true),
            Preset("Vivid", v("vib" to 45, "sat" to 10, "contrast" to 15, "clarity" to 20), true),
            Preset("Cinema", v("contrast" to 20, "fade" to 15, "temp" to 8, "vignette" to 35, "grain" to 12), true),
            Preset("Black and white", v("sat" to -100, "contrast" to 25, "clarity" to 15, "vignette" to 25), true),
            Preset("Soft glow", v("soft" to 35, "soft_lo" to 60, "bright" to 10, "vib" to 15), true)
        ),
        Kind.AUDIO to listOf(
            Preset("Clear voices", a("voice" to 60, "lowcut" to 50, "level" to 40), true),
            Preset("Even volume", a("level" to 70, "comp" to 50, "limit" to 60), true),
            Preset("Night mode", a("comp" to 70, "limit" to 80, "level" to 50, "bass" to -3), true),
            Preset("Bass boost", a("bass" to 6, "limit" to 40), true)
        )
    )

    fun mine(kind: Kind): List<Preset> = runCatching {
        val arr = JSONArray(Settings.getString(kind.key, "[]").ifBlank { "[]" })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Preset(o.getString("name"), decode(o.getJSONObject("values")), false)
        }
    }.getOrDefault(emptyList())

    private fun saveMine(kind: Kind, list: List<Preset>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("name", p.name).put("values", encode(p.values)))
        Settings.setString(kind.key, arr.toString())
    }

    private fun encode(m: Map<String, Any?>) = JSONObject().also { o ->
        for ((k, v) in m) when (v) {
            is Boolean -> o.put(k, JSONArray().put("b").put(v)); is Int -> o.put(k, JSONArray().put("i").put(v))
            is Long -> o.put(k, JSONArray().put("l").put(v)); is Float -> o.put(k, JSONArray().put("f").put(v.toDouble()))
            is String -> o.put(k, JSONArray().put("s").put(v))
        }
    }

    private fun decode(o: JSONObject): Map<String, Any?> = o.keys().asSequence().mapNotNull { k ->
        val a = o.optJSONArray(k) ?: return@mapNotNull null
        k to when (a.optString(0)) {
            "b" -> a.getBoolean(1); "i" -> a.getInt(1); "l" -> a.getLong(1)
            "f" -> a.getDouble(1).toFloat(); "s" -> a.getString(1); else -> return@mapNotNull null
        }
    }.toMap()

    /** The tab's settings as they are now. */
    fun capture(kind: Kind): Map<String, Any?> = Settings.all().filterKeys { covers(kind, it) }

    /** Make the tab's settings exactly the preset's. */
    fun apply(kind: Kind, p: Preset) {
        Settings.replace(p.values) { covers(kind, it) }
        log("FX applied ${kind.title.lowercase()} preset ${p.name}")
    }

    // ---- the dialog ----

    /** Pick, save or manage presets for [kind]; [after] redraws the panel once something changed. */
    fun show(ctx: Context, kind: Kind, after: () -> Unit) {
        val built = BUILT_IN[kind].orEmpty()
        val own = mine(kind)
        val labels = ArrayList<String>()
        labels += "＋ Save the current settings as a preset"
        own.forEach { labels += "★ " + it.name }
        built.forEach { labels += it.name }
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("${kind.title} presets")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> nameDialog(ctx, "Save as a preset", "") { name ->
                        saveMine(kind, own.filter { it.name != name } + Preset(name, capture(kind), false))
                        toast(ctx, "Saved \"$name\"")
                    }
                    which <= own.size -> manage(ctx, kind, own[which - 1], after)
                    else -> { apply(kind, built[which - 1 - own.size]); after() }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun manage(ctx: Context, kind: Kind, p: Preset, after: () -> Unit) {
        val own = mine(kind)
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(p.name)
            .setItems(arrayOf("Apply", "Replace with the current settings", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> { apply(kind, p); after() }
                    1 -> { saveMine(kind, own.map { if (it.name == p.name) Preset(p.name, capture(kind), false) else it }); toast(ctx, "Updated \"${p.name}\"") }
                    2 -> nameDialog(ctx, "Rename preset", p.name) { name ->
                        saveMine(kind, own.filter { it.name != name }.map { if (it.name == p.name) Preset(name, it.values, false) else it })
                    }
                    3 -> saveMine(kind, own.filter { it.name != p.name })
                }
            }
            .show()
    }

    private fun nameDialog(ctx: Context, title: String, start: String, done: (String) -> Unit) {
        val e = EditText(ctx).apply { setText(start); hint = "Preset name"; isSingleLine = true; setSelection(start.length) }
        val box = LinearLayout(ctx).apply {
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
