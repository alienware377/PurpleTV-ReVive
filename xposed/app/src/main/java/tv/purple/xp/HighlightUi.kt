package tv.purple.xp

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Editor screens for the highlighter: the keyword list, and the @mention colour.
 *
 * Deliberately built from plain framework dialogs rather than [PurpleMenu]'s static row renderer,
 * because these screens are dynamic: entries are added and removed at runtime, and the static
 * tree has no way to express that.
 */
object HighlightUi {

    /** Row tints, kept semi-transparent so chat text stays legible on top of them. */
    private val SWATCHES = linkedMapOf(
        "Purple" to 0x4D9146FF,
        "Red" to 0x4DE91916,
        "Orange" to 0x4DFF7D00,
        "Yellow" to 0x4DFFD100,
        "Green" to 0x4D00B37D,
        "Blue" to 0x4D1F69FF,
        "Pink" to 0x4DFF69B4,
        "Grey" to 0x4D8A8A94
    )

    private val TYPE_LABELS = listOf(
        "Contains word (any case)",
        "Contains word (exact case)",
        "From username"
    )

    fun showKeywords(ctx: Context) {
        val list = ChatHighlight.keywords()
        val labels = list.map {
            val kind = when (it.type) {
                ChatHighlight.Type.USERNAME -> "user"
                ChatHighlight.Type.SENSITIVE -> "exact"
                ChatHighlight.Type.INSENSITIVE -> "word"
            }
            "${it.word}   ($kind)"
        }.toTypedArray()

        val b = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Highlighter")
            .setPositiveButton("Add") { _, _ -> showAdd(ctx) }
            .setNegativeButton("Close", null)
        if (labels.isEmpty()) {
            b.setMessage("No keywords yet.\n\nAdd a word, or a username, and matching chat messages get a coloured row.")
        } else {
            // Tapping an entry is the delete affordance; the list is short enough that a
            // separate edit mode would be more chrome than it's worth.
            b.setItems(labels) { _, which -> confirmDelete(ctx, which, list[which].word) }
        }
        b.show()
    }

    private fun confirmDelete(ctx: Context, index: Int, word: String) {
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Remove \"$word\"?")
            .setPositiveButton("Remove") { _, _ ->
                ChatHighlight.remove(index)
                toast(ctx, "Removed \"$word\"")
                showKeywords(ctx)
            }
            .setNegativeButton("Cancel") { _, _ -> showKeywords(ctx) }
            .show()
    }

    private fun showAdd(ctx: Context) {
        val input = EditText(ctx).apply {
            hint = "Word or username"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val types = RadioGroup(ctx).apply {
            orientation = LinearLayout.VERTICAL
            TYPE_LABELS.forEachIndexed { i, label ->
                addView(RadioButton(ctx).apply { id = 1000 + i; text = label })
            }
            check(1000)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 20f); setPadding(p, dp(ctx, 8f), p, 0)
            addView(input)
            addView(TextView(ctx).apply {
                text = "Match"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(ctx, 12f), 0, 0)
            })
            addView(types)
        }
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Add keyword")
            .setView(box)
            .setPositiveButton("Next") { _, _ ->
                val word = input.text.toString().trim()
                if (word.isEmpty()) { toast(ctx, "Enter a word first"); return@setPositiveButton }
                val type = when (types.checkedRadioButtonId) {
                    1001 -> ChatHighlight.Type.SENSITIVE
                    1002 -> ChatHighlight.Type.USERNAME
                    else -> ChatHighlight.Type.INSENSITIVE
                }
                pickColor(ctx, "Colour for \"$word\"") { color ->
                    ChatHighlight.add(ChatHighlight.Keyword(word, type, color))
                    toast(ctx, "Highlighting \"$word\"")
                    showKeywords(ctx)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> showKeywords(ctx) }
            .show()
    }

    fun showMentionColor(ctx: Context) =
        pickColor(ctx, "@mention colour") { color ->
            Settings.setInt(ChatHighlight.KEY_MENTION_COLOR, color)
            toast(ctx, "Mention colour updated")
        }

    private fun pickColor(ctx: Context, title: String, onPick: (Int) -> Unit) {
        val names = SWATCHES.keys.toTypedArray()
        val colors = SWATCHES.values.toList()
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setAdapter(swatchAdapter(ctx, names, colors)) { _, which -> onPick(colors[which]) }
            .show()
    }

    /** List rows showing the actual tint behind the colour's name, so the choice is visible. */
    private fun swatchAdapter(ctx: Context, names: Array<String>, colors: List<Int>) =
        object : android.widget.BaseAdapter() {
            override fun getCount() = names.size
            override fun getItem(p: Int): Any = names[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: android.view.View?, parent: android.view.ViewGroup?) =
                TextView(ctx).apply {
                    text = names[p]
                    gravity = Gravity.CENTER_VERTICAL
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    val pad = dp(ctx, 16f)
                    setPadding(pad, pad, pad, pad)
                    // Composite the swatch over the menu background so the preview matches what
                    // the semi-transparent tint will actually look like in chat.
                    setBackgroundColor(colors[p])
                }
        }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
