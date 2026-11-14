package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The Lua console, a panel under the editor: what scripts print, their errors in red, and a line to type
 * Lua into. An expression shows its value; anything else is run.
 */
class LuaConsole(context: Context, private val palette: Palette) : LinearLayout(context) {
    /** Called with a line typed into the console. */
    var onRun: ((String) -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val output = TextView(context)
    private val scroll = ScrollView(context)
    private val input = EditText(context)

    private fun dp(value: Float): Int = (value * density).roundToInt()

    init {
        orientation = VERTICAL
        visibility = View.GONE
        setBackgroundColor(palette.findBack)
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.chrome)
        }
        header.addView(TextView(context).apply {
            text = "Lua console"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.chromeText)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            setPadding(dp(10f), 0, dp(4f), 0)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun button(label: String, description: String, width: Float, action: () -> Unit) = TextView(context).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            setTextColor(palette.chromeDimText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length == 1) 15f else 13f)
            setOnClickListener { action() }
            header.addView(this, LayoutParams(dp(width), dp(36f)))
        }
        button("Clear", "Clear console", 56f) { onClear?.invoke() }
        button("✕", "Close console", 44f) { onClose?.invoke() }
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)))

        output.apply {
            contentDescription = "Lua output"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(palette.chromeText)
            setTextIsSelectable(true)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        }
        scroll.addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The text is only as tall as its lines once it has been laid out again.
        output.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) toEnd()
        }
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.keysBack)
        }
        input.apply {
            contentDescription = "Lua input"
            hint = "Lua, such as editor.Length"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.chromeText)
            setHintTextColor(palette.chromeDimText)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_GO || enter) {
                    run()
                    true
                } else {
                    false
                }
            }
        }
        row.addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6f) })
        row.addView(TextView(context).apply {
            text = "Run"
            contentDescription = "Run Lua"
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { run() }
        }, LayoutParams(dp(56f), dp(40f)))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun run() {
        val code = input.text.toString().trim()
        if (code.isEmpty()) return
        input.setText("")
        onRun?.invoke(code)
    }

    /** Shows the text kept by the owner, and goes to its end. */
    fun show(text: CharSequence) {
        output.text = text
        toEnd()
    }

    // Scrolled by position: ScrollView's own "go to the end" also takes the keyboard focus, away from the editor.
    private fun toEnd() {
        scroll.post { scroll.scrollTo(0, (output.height - scroll.height).coerceAtLeast(0)) }
    }

    val text: String get() = output.text.toString()

    companion object {
        /** No more than this is kept; older lines go first. */
        private const val KEPT = 30_000

        /** Adds a line to the text a console shows, errors in their own colour. */
        fun append(to: SpannableStringBuilder, line: String, error: Boolean, palette: Palette) {
            val from = to.length
            to.append(line).append('\n')
            if (error) to.setSpan(ForegroundColorSpan(palette.tabDirty), from, to.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (to.length > KEPT) {
                val cut = to.indexOf("\n", to.length - KEPT)
                to.delete(0, if (cut < 0) to.length - KEPT else cut + 1)
            }
        }
    }
}
