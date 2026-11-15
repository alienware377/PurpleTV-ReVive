package tv.purple.xp

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Editor for the chat blacklist.
 *
 * Deliberately a near-twin of [HighlightUi] minus the colour step: the two features answer the same
 * question about a message and differ only in what they do with the answer, so making the editors
 * behave differently would be a gratuitous thing to learn twice.
 */
object BlacklistUi {

    private val TYPE_LABELS = listOf(
        "Contains word (any case)",
        "Contains word (exact case)",
        "From username"
    )

    fun show(ctx: Context) {
        val list = Blacklist.terms()
        val labels = list.map {
            val kind = when (it.type) {
                Blacklist.Type.USERNAME -> "user"
                Blacklist.Type.SENSITIVE -> "exact"
                Blacklist.Type.INSENSITIVE -> "word"
            }
            "${it.word}   ($kind)"
        }.toTypedArray()

        val b = TBuilder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Blacklist")
            .setPositiveButton("Add") { _, _ -> showAdd(ctx) }
            .setNegativeButton("Close", null)
        if (labels.isEmpty()) {
            b.setMessage("Nothing blacklisted.\n\nAdd a word, or a username, and matching chat " +
                "messages are hidden completely.")
        } else {
            b.setItems(labels) { _, which -> confirmDelete(ctx, which, list[which].word) }
        }
        b.show()
    }

    private fun confirmDelete(ctx: Context, index: Int, word: String) {
        TBuilder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Stop hiding \"$word\"?")
            .setPositiveButton("Remove") { _, _ ->
                Blacklist.remove(index)
                toast(ctx, "Removed \"$word\"")
                show(ctx)
            }
            .setNegativeButton("Cancel") { _, _ -> show(ctx) }
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
                text = I18n.t("Match")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(ctx, 12f), 0, 0)
            })
            addView(types)
        }
        TBuilder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Blacklist a word")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val word = input.text.toString().trim()
                if (word.isEmpty()) { toast(ctx, "Enter a word first"); return@setPositiveButton }
                val type = when (types.checkedRadioButtonId) {
                    1001 -> Blacklist.Type.SENSITIVE
                    1002 -> Blacklist.Type.USERNAME
                    else -> Blacklist.Type.INSENSITIVE
                }
                Blacklist.add(Blacklist.Term(word, type))
                toast(ctx, "Hiding \"$word\"")
                show(ctx)
            }
            .setNegativeButton("Cancel") { _, _ -> show(ctx) }
            .show()
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
