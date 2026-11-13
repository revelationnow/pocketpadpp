package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.SciApi
import io.github.revelationnow.pocketpadpp.core.SearchOptions
import io.github.revelationnow.pocketpadpp.core.Searcher
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import kotlin.math.roundToInt

/** Find and replace, as a bar that sits above the keyboard instead of Notepad++'s floating dialog. */
class FindBar(context: Context, private val editor: SciApi) : LinearLayout(context) {

    /** Called when the bar closes, so that focus can go back to the editor. */
    var onClose: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val findField = field("Find")
    private val replaceField = field("Replace with")
    private val replaceRow = LinearLayout(context)
    private val status = TextView(context)
    private val matchCase = toggle("Aa", "Match case")
    private val wholeWord = toggle("W", "Match whole word only")
    private val regex = toggle(".*", "Regular expression")
    private val wrap = toggle("Wrap", "Wrap around").apply { isChecked = true }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun field(hintText: String) = EditText(context).apply {
        hint = hintText
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.MONOSPACE
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    private fun button(label: String, description: String, action: () -> Unit) =
        Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            contentDescription = description
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minWidth = 0
            minimumWidth = dp(44f)
            setPadding(dp(10f), 0, dp(10f), 0)
            setOnClickListener { action() }
        }

    private fun toggle(label: String, description: String) = ToggleButton(context).apply {
        textOn = label
        textOff = label
        text = label
        contentDescription = description
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minWidth = 0
        minimumWidth = dp(44f)
        minHeight = 0
        minimumHeight = dp(36f)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF0F0F0.toInt())
        setPadding(dp(6f), dp(2f), dp(2f), dp(2f))
        visibility = View.GONE

        val findRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        findRow.addView(findField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        findRow.addView(button("▲", "Find previous") { find(forward = false) })
        findRow.addView(button("▼", "Find next") { find(forward = true) })
        findRow.addView(button("✕", "Close") { close() })
        addView(findRow)

        replaceRow.gravity = Gravity.CENTER_VERTICAL
        replaceRow.addView(replaceField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        replaceRow.addView(button("Replace", "Replace") { replace() })
        replaceRow.addView(button("All", "Replace all") { replaceAll() })
        addView(replaceRow)

        val optionsRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        for (option in listOf(matchCase, wholeWord, regex, wrap)) {
            optionsRow.addView(option, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        optionsRow.addView(button("Count", "Count matches") { count() })
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        status.setPadding(dp(8f), 0, dp(4f), 0)
        status.maxLines = 2
        optionsRow.addView(status, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(optionsRow)

        findField.setOnEditorActionListener { _, _, _ -> find(forward = true); true }
        replaceField.setOnEditorActionListener { _, _, _ -> replace(); true }
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE

    private fun options() = SearchOptions(matchCase.isChecked, wholeWord.isChecked, regex.isChecked, wrap.isChecked)

    private fun say(message: String, colour: Int = MESSAGE) {
        status.text = message
        status.setTextColor(colour)
    }

    /** Opens the bar, seeding the find field with the selection when it is a short single line. */
    fun open(withReplace: Boolean) {
        replaceRow.visibility = if (withReplace) View.VISIBLE else View.GONE
        val start = editor.send(Sci.SCI_GETSELECTIONSTART)
        val end = editor.send(Sci.SCI_GETSELECTIONEND)
        if (end > start && end - start <= 200) {
            val selected = String(editor.range(start, end), Charsets.UTF_8)
            if ('\n' !in selected && '\r' !in selected) findField.setText(selected)
        }
        say("")
        visibility = View.VISIBLE
        findField.requestFocus()
        findField.selectAll()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findField, 0)
    }

    fun close() {
        if (!isOpen) return
        visibility = View.GONE
        onClose?.invoke()
    }

    /** Find Next / Find Previous, also usable from the menu while the bar is closed. */
    fun find(forward: Boolean) {
        val text = findField.text.toString()
        if (text.isEmpty()) {
            if (!isOpen) open(withReplace = false)
            return
        }
        val result = Searcher.find(editor, text, options(), forward)
        when {
            !result.found -> say("Can't find the text \"$text\"", NOT_FOUND)
            result.wrapped && forward -> say("Found the 1st occurrence from the top. The end of the document has been reached.", END_REACHED)
            result.wrapped -> say("Found the 1st occurrence from the bottom. The beginning of the document has been reached.", END_REACHED)
            else -> say("")
        }
    }

    /**
     * Select and Find Next / Find (Volatile) Next: searches for the selection or the word at the caret.
     * With [remember] the text also becomes the find field's content.
     */
    fun findSelection(forward: Boolean, remember: Boolean) {
        val text = EditOps.selectionOrWord(editor)
        if (text.isEmpty() || '\n' in text || '\r' in text) return
        if (remember) findField.setText(text)
        val result = Searcher.find(editor, text, SearchOptions(matchCase.isChecked, wholeWord.isChecked, regex = false, wrapAround = true), forward)
        if (!result.found && isOpen) say("Can't find the text \"$text\"", NOT_FOUND)
    }

    private fun replace() {
        val text = findField.text.toString()
        if (text.isEmpty()) return
        val (replaced, next) = Searcher.replace(editor, text, replaceField.text.toString(), options())
        when {
            replaced && next.found -> say("1 occurrence was replaced. The next occurrence found.")
            replaced -> say("1 occurrence was replaced. No more occurrences were found.", END_REACHED)
            next.found -> say("")
            else -> say("Can't find the text \"$text\"", NOT_FOUND)
        }
    }

    private fun replaceAll() {
        val text = findField.text.toString()
        if (text.isEmpty()) return
        val count = Searcher.replaceAll(editor, text, replaceField.text.toString(), options())
        say(if (count == 1) "Replace All: 1 occurrence was replaced." else "Replace All: $count occurrences were replaced.")
    }

    private fun count() {
        val text = findField.text.toString()
        if (text.isEmpty()) return
        val count = Searcher.count(editor, text, options())
        say(if (count == 1) "Count: 1 match." else "Count: $count matches.")
    }

    private companion object {
        // Notepad++'s find status colours.
        const val MESSAGE = 0xFF0000FF.toInt()
        const val NOT_FOUND = 0xFFFF0000.toInt()
        const val END_REACHED = 0xFF008000.toInt()
    }
}
