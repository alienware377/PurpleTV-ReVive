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
    private fun v(vararg p: Pair<String, Int>) = p.associate { (k, n) ->
        (if (k.endsWith("_lo") || k.endsWith("_hi") || k.endsWith("_speed")) "vf_$k" else "vf_${k}_a") to n
    }
    private fun a(vararg p: Pair<String, Int>) = p.associate { (k, n) -> "af_$k" to n }

    private val BUILT_IN = mapOf(
        Kind.VIDEO to listOf(
            Preset("Brighten dark streams", v("d_exp" to 70, "d_gamma" to 40, "shadows" to 25), true),
            Preset("Fix washed-out streams", v("d_black" to 80, "d_con" to 60, "vib" to 20), true),
            Preset("Vivid", v("vib" to 45, "sat" to 10, "contrast" to 15, "clarity" to 20), true),
            Preset("Cinema", v("contrast" to 20, "fade" to 15, "temp" to 8, "vignette" to 35, "grain" to 12), true),
            Preset("Black and white", v("sat" to -100, "contrast" to 25, "clarity" to 15, "vignette" to 25), true),
            Preset("Soft glow", v("soft" to 35, "soft_lo" to 60, "bright" to 10, "vib" to 15), true),
            Preset("Glitchy", v("g_rgb" to 37, "g_rgb_speed" to 39, "g_vhs" to 36, "g_shake" to 19, "g_shake_speed" to 48,
                "g_wave" to 18, "g_wave_speed" to 100, "g_scan" to 18, "g_scan_speed" to 76, "g_flicker" to 18,
                "g_shift" to 11, "g_shift_speed" to 80, "g_static" to 11, "g_blocks" to 4), true)
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

    /**
     * Make the tab's settings the preset's. Settings it leaves out are set back to their start
     * values, not removed: a device still on an older build cannot pass a removal on, so a removed
     * setting would come back from it. That includes settings this device never stored, so another
     * device's older change to one of them cannot come back over the preset at the next sync.
     *
     * Picked colours, gradients and overlay pictures have no single start value. A built-in look
     * keeps them, like the reset buttons do. Your own preset was saved with every setting stored at
     * the time, so a pick it lacks was still at its start value then: it is removed, and the start
     * value comes back.
     *
     * [Settings.replace] skips sync, so every setting the preset decides is stamped here, changed or
     * not, the same as Reset all; without a fresh change time the next sync would put the file's
     * older values back. Kept picks are not stamped, so a newer pick on another device still wins.
     */
    fun apply(kind: Kind, p: Preset) {
        val target = HashMap<String, Any?>()
        val decided = HashSet<String>()
        for ((k, v) in runCatching { startValues(kind) }.getOrDefault(emptyMap())) { target[k] = v; decided += k }
        for ((k, v) in capture(kind)) {
            val n = neutral(k, v)
            when {
                n != null -> { target[k] = n; decided += k }
                // Left out of your own preset's target, so replace() removes it.
                p.builtIn -> target[k] = v
            }
        }
        for ((k, v) in p.values) if (covers(kind, k)) { target[k] = v; decided += k }
        val changed = Settings.replace(target) { covers(kind, it) }
        // Only what really holds the preset's value now: if the write failed, stamping the old
        // values as new would push them over other devices' changes.
        val stored = Settings.all()
        SettingsSync.changed(changed + decided.filter { stored[it] == target[it] })
        log("FX applied ${kind.title.lowercase()} preset ${p.name}")
    }

    /**
     * Every setting a reset writes, at the value it writes ([FilterDefs.resetFilter],
     * [AudioFilters.resetAll]), with every on/off switch at on and the full equaliser off. A preset
     * writes these even where this device never stored them. The extras per filter are the same
     * ones [FilterDefs.resetFilter] sets.
     */
    private fun startValues(kind: Kind): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        if (kind == Kind.AUDIO) {
            for (f in AudioFilters.FX) { m["af_${f.key}"] = 0; m["af_${f.key}_off"] = false }
            for (i in AudioFilters.EQ_HZ.indices) m["af_eq_$i"] = 0
            m["af_eq_off"] = false; m[AudioFilters.KEY_EQ_FULL] = false
        } else {
            for (f in FilterDefs.FILTERS) {
                val extras = when {
                    f.group == FilterDefs.Group.DYNAMIC -> listOf("target", "speed")
                    f.group == FilterDefs.Group.GLITCH -> listOf("speed")
                    f.key == "grain" || f.key == "vignette" -> listOf("size")
                    f.key == "bokeh" -> listOf("size", "density", "speed")
                    else -> emptyList()
                }
                for (s in listOf("a", "lo", "hi", "off") + extras) {
                    val k = "vf_${f.key}_$s"
                    neutral(k, null)?.let { m[k] = it }
                }
            }
            for (g in FilterDefs.Group.values()) m["vf_grp_${g.name}_off"] = false
        }
        return m.filterKeys { covers(kind, it) }
    }

    /**
     * Where a setting the preset leaves out goes: the start value the panel's reset buttons use
     * ([FilterDefs.resetFilter], [AudioFilters.resetAll]). Null means it has no single start value:
     * picked colours, gradients and overlay pictures, which a reset keeps too. [apply] keeps those
     * for a built-in look and removes them for your own preset.
     */
    private fun neutral(k: String, cur: Any?): Any? {
        // Every audio setting starts at 0 or switched off.
        if (k.startsWith("af_")) return when (cur) { is Boolean -> false; is Int -> 0; else -> null }
        val f = k.removePrefix("vf_").substringBeforeLast('_')
        return when {
            k.endsWith("_a") || k.endsWith("_lo") -> 0
            k.endsWith("_hi") -> 100
            k.endsWith("_off") -> false
            k.endsWith("_target") -> DynLight.DEF_TARGET[f] ?: 50
            k.endsWith("_speed") -> when {
                f == "bokeh" -> 40
                FilterDefs.FILTERS.getOrNull(FilterDefs.index(f))?.group == FilterDefs.Group.DYNAMIC -> DynLight.DEF_SPEED
                else -> 50
            }
            k == "vf_grain_size" -> 2
            k == "vf_vignette_size" -> 50
            k == "vf_bokeh_size" || k == "vf_bokeh_density" -> 45
            else -> null
        }
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
                        // Unchanged: the filter below would drop the preset itself.
                        if (name == p.name) return@nameDialog
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
