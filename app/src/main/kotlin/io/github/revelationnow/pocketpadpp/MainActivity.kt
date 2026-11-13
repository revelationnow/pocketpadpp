package io.github.revelationnow.pocketpadpp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import io.github.revelationnow.pocketpadpp.editor.ScintillaView
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import io.github.revelationnow.pocketpadpp.scintilla.SciLex
import kotlin.math.roundToInt

/**
 * Spike screen: one Scintilla editor with just enough around it to judge typing, selection and
 * scrolling on a phone. The real application shell (tabs, files, menus) comes later.
 */
class MainActivity : Activity() {

    private lateinit var editor: ScintillaView
    private lateinit var status: TextView
    private lateinit var imeLogView: TextView
    private lateinit var keyboardButton: Button
    private val imeLines = ArrayDeque<String>()
    private var fontSize = 13

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFEFEFEF.toInt())
            setPadding(dp(8f), 0, dp(4f), 0)
        }
        toolbar.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF202020.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(barButton("Undo") { editor.send(Sci.SCI_UNDO) })
        toolbar.addView(barButton("Redo") { editor.send(Sci.SCI_REDO) })
        keyboardButton = barButton("Kbd: code") { toggleKeyboardMode() }
        toolbar.addView(keyboardButton)
        toolbar.addView(barButton("More") { showMenu(it) })
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))

        editor = ScintillaView(this)
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        imeLogView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFF004400.toInt())
            setBackgroundColor(0xFFF4FFF4.toInt())
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            visibility = View.GONE
        }
        root.addView(imeLogView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(extraKeys(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)))

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF404040.toInt())
            setBackgroundColor(0xFFEFEFEF.toInt())
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            maxLines = 1
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        applyTheme()
        editor.text = SAMPLE
        editor.onStateChanged = { updateStatus() }
        editor.imeLog = { line -> onImeLog(line) }
        editor.requestFocus()
        updateStatus()
    }

    override fun onDestroy() {
        editor.destroy()
        super.onDestroy()
    }

    private fun barButton(label: String, onClick: (View) -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10f), 0, dp(10f), 0)
            // Toolbar taps must not take focus (and with it the keyboard) away from the editor.
            isFocusable = false
            setOnClickListener(onClick)
        }

    /** A row of keys phone keyboards make hard to reach. */
    private fun extraKeys(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(label: String, action: () -> Unit) {
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minWidth = 0
                minimumWidth = dp(40f)
                setPadding(dp(8f), 0, dp(8f), 0)
                isFocusable = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        key("Tab") { editor.pressKey(Sci.SCK_TAB) }
        key("←") { editor.pressKey(Sci.SCK_LEFT) }
        key("↑") { editor.pressKey(Sci.SCK_UP) }
        key("↓") { editor.pressKey(Sci.SCK_DOWN) }
        key("→") { editor.pressKey(Sci.SCK_RIGHT) }
        key("Home") { editor.pressKey(Sci.SCK_HOME) }
        key("End") { editor.pressKey(Sci.SCK_END) }
        for (symbol in listOf("{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "<", ">", "=", "/", "\\", "|", "&", "!", "_", "#")) {
            key(symbol) { editor.typeText(symbol) }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFFF7F7F7.toInt())
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun toggleKeyboardMode() {
        val text = editor.keyboardMode == ScintillaView.KeyboardMode.CODE
        editor.keyboardMode = if (text) ScintillaView.KeyboardMode.TEXT else ScintillaView.KeyboardMode.CODE
        keyboardButton.text = if (text) "Kbd: text" else "Kbd: code"
    }

    private fun showMenu(anchor: View) {
        val wrapped = editor.send(Sci.SCI_GETWRAPMODE).toInt() != Sci.SC_WRAP_NONE
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (wrapped) "Word wrap: on" else "Word wrap: off")
            menu.add(0, 2, 1, "Bigger text")
            menu.add(0, 3, 2, "Smaller text")
            menu.add(0, 4, 3, "Load sample")
            menu.add(0, 5, 4, "Load 100,000 lines")
            menu.add(0, 6, 5, if (imeLogView.visibility == View.VISIBLE) "Hide keyboard log" else "Show keyboard log")
            menu.add(0, 7, 6, "Add caret below (multi-edit)")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> editor.send(Sci.SCI_SETWRAPMODE, (if (wrapped) Sci.SC_WRAP_NONE else Sci.SC_WRAP_WORD).toLong())
                    2 -> setFontSize(fontSize + 1)
                    3 -> setFontSize(fontSize - 1)
                    4 -> editor.text = SAMPLE
                    5 -> editor.text = bigDocument()
                    6 -> imeLogView.visibility = if (imeLogView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    7 -> editor.send(Sci.SCI_LINEDOWNRECTEXTEND)
                }
                updateStatus()
                true
            }
            show()
        }
    }

    private fun onImeLog(line: String) {
        imeLines.addLast(line)
        while (imeLines.size > 6) imeLines.removeFirst()
        if (imeLogView.visibility == View.VISIBLE) imeLogView.text = imeLines.joinToString("\n")
    }

    private fun updateStatus() {
        val position = editor.send(Sci.SCI_GETCURRENTPOS)
        val line = editor.send(Sci.SCI_LINEFROMPOSITION, position) + 1
        val column = editor.send(Sci.SCI_GETCOLUMN, position) + 1
        val selected = editor.send(Sci.SCI_GETSELECTIONEND) - editor.send(Sci.SCI_GETSELECTIONSTART)
        val carets = editor.send(Sci.SCI_GETSELECTIONS)
        val parts = mutableListOf("Ln $line, Col $column")
        if (selected > 0) parts += "Sel $selected"
        if (carets > 1) parts += "$carets carets"
        parts += "${editor.send(Sci.SCI_GETLINECOUNT)} lines"
        parts += "UTF-8"
        parts += when (editor.send(Sci.SCI_GETEOLMODE).toInt()) {
            Sci.SC_EOL_CRLF -> "CRLF"
            Sci.SC_EOL_CR -> "CR"
            else -> "LF"
        }
        status.text = parts.joinToString("  ·  ")
    }

    private fun setFontSize(size: Int) {
        fontSize = size.coerceIn(6, 40)
        applyTheme()
    }

    /** Colours follow Notepad++'s default light theme. Scintilla colours are 0xBBGGRR. */
    private fun applyTheme() {
        fun rgb(value: Int): Long =
            (((value and 0xFF) shl 16) or (value and 0xFF00) or ((value shr 16) and 0xFF)).toLong()

        fun style(id: Int, fore: Int, bold: Boolean = false, italic: Boolean = false) {
            editor.send(Sci.SCI_STYLESETFORE, id.toLong(), rgb(fore))
            editor.send(Sci.SCI_STYLESETBOLD, id.toLong(), if (bold) 1 else 0)
            editor.send(Sci.SCI_STYLESETITALIC, id.toLong(), if (italic) 1 else 0)
        }

        editor.sendText(Sci.SCI_STYLESETFONT, Sci.STYLE_DEFAULT.toLong(), "monospace")
        editor.send(Sci.SCI_STYLESETSIZE, Sci.STYLE_DEFAULT.toLong(), fontSize.toLong())
        editor.send(Sci.SCI_STYLESETFORE, Sci.STYLE_DEFAULT.toLong(), rgb(0x000000))
        editor.send(Sci.SCI_STYLESETBACK, Sci.STYLE_DEFAULT.toLong(), rgb(0xFFFFFF))
        editor.send(Sci.SCI_STYLECLEARALL)

        editor.setLexer("cpp")
        editor.sendText(Sci.SCI_SETKEYWORDS, 0, CPP_KEYWORDS)
        editor.sendText(Sci.SCI_SETKEYWORDS, 1, CPP_TYPES)
        editor.setProperty("fold", "1")
        editor.setProperty("fold.compact", "0")

        style(SciLex.SCE_C_WORD, 0x0000FF, bold = true)
        style(SciLex.SCE_C_WORD2, 0x8000FF)
        style(SciLex.SCE_C_COMMENT, 0x008000)
        style(SciLex.SCE_C_COMMENTLINE, 0x008000)
        style(SciLex.SCE_C_COMMENTDOC, 0x008080)
        style(SciLex.SCE_C_NUMBER, 0xFF8000)
        style(SciLex.SCE_C_STRING, 0x808080)
        style(SciLex.SCE_C_CHARACTER, 0x808080)
        style(SciLex.SCE_C_PREPROCESSOR, 0x804000)
        style(SciLex.SCE_C_OPERATOR, 0x000080, bold = true)

        // Margins: line numbers, then fold markers sized for a fingertip.
        editor.send(Sci.SCI_STYLESETFORE, Sci.STYLE_LINENUMBER.toLong(), rgb(0x808080))
        editor.send(Sci.SCI_STYLESETBACK, Sci.STYLE_LINENUMBER.toLong(), rgb(0xE4E4E4))
        editor.send(Sci.SCI_SETMARGINTYPEN, 0, Sci.SC_MARGIN_NUMBER.toLong())
        editor.send(Sci.SCI_SETMARGINWIDTHN, 0, editor.sendText(Sci.SCI_TEXTWIDTH, Sci.STYLE_LINENUMBER.toLong(), "_999999"))
        editor.send(Sci.SCI_SETMARGINWIDTHN, 1, 0)
        editor.send(Sci.SCI_SETMARGINTYPEN, 2, Sci.SC_MARGIN_SYMBOL.toLong())
        editor.send(Sci.SCI_SETMARGINMASKN, 2, Sci.SC_MASK_FOLDERS.toLong())
        editor.send(Sci.SCI_SETMARGINWIDTHN, 2, dp(22f).toLong())
        editor.send(Sci.SCI_SETMARGINSENSITIVEN, 2, 1)
        editor.send(Sci.SCI_SETMARGINLEFT, 0, dp(4f).toLong())
        editor.send(Sci.SCI_SETFOLDMARGINCOLOUR, 1, rgb(0xF2F2F2))
        editor.send(Sci.SCI_SETFOLDMARGINHICOLOUR, 1, rgb(0xF2F2F2))
        val markers = listOf(
            Sci.SC_MARKNUM_FOLDEROPEN to Sci.SC_MARK_BOXMINUS,
            Sci.SC_MARKNUM_FOLDER to Sci.SC_MARK_BOXPLUS,
            Sci.SC_MARKNUM_FOLDERSUB to Sci.SC_MARK_VLINE,
            Sci.SC_MARKNUM_FOLDERTAIL to Sci.SC_MARK_LCORNER,
            Sci.SC_MARKNUM_FOLDEREND to Sci.SC_MARK_BOXPLUSCONNECTED,
            Sci.SC_MARKNUM_FOLDEROPENMID to Sci.SC_MARK_BOXMINUSCONNECTED,
            Sci.SC_MARKNUM_FOLDERMIDTAIL to Sci.SC_MARK_TCORNER,
        )
        for ((number, symbol) in markers) {
            editor.send(Sci.SCI_MARKERDEFINE, number.toLong(), symbol.toLong())
            editor.send(Sci.SCI_MARKERSETFORE, number.toLong(), rgb(0xFFFFFF))
            editor.send(Sci.SCI_MARKERSETBACK, number.toLong(), rgb(0x808080))
        }
        editor.send(
            Sci.SCI_SETAUTOMATICFOLD,
            (Sci.SC_AUTOMATICFOLD_SHOW or Sci.SC_AUTOMATICFOLD_CLICK or Sci.SC_AUTOMATICFOLD_CHANGE).toLong(),
        )

        editor.send(Sci.SCI_SETCARETLINEVISIBLE, 1)
        editor.send(Sci.SCI_SETCARETLINEBACK, rgb(0xE8E8FF))
        editor.send(Sci.SCI_SETSELBACK, 1, rgb(0xC0C0C0))
        editor.send(Sci.SCI_SETTABWIDTH, 4)
        editor.send(Sci.SCI_SETINDENTATIONGUIDES, Sci.SC_IV_LOOKBOTH.toLong())
        editor.send(Sci.SCI_BRACEBADLIGHT, -1)
    }

    private fun bigDocument(): String = buildString {
        for (i in 1..100_000) {
            append("\tint value_").append(i).append(" = compute(").append(i).append(", \"line ").append(i).append("\"); // generated\n")
        }
    }

    private companion object {
        const val CPP_KEYWORDS = "alignas alignof and auto break case catch class const constexpr continue default delete do " +
            "else enum explicit export extern false for friend goto if inline namespace new noexcept nullptr operator " +
            "private protected public return sizeof static struct switch template this throw true try typedef typename " +
            "union using virtual volatile while"
        const val CPP_TYPES = "bool char double float int long short signed unsigned void size_t string vector"

        val SAMPLE = """
            // Pocketpad++ spike: Scintilla running natively on Android.
            // Things to try:
            //   - type with your normal keyboard (switch "Kbd: code" / "Kbd: text" above)
            //   - double tap a word, long press and drag, pull the selection handles
            //   - fling to scroll, tap the fold boxes in the left margin
            //   - More > Load 100,000 lines, then fling again
            #include <string>
            #include <vector>

            /* Unicode check: Ünïcödé, Ελληνικά, 日本語, emoji 😀 👍 */
            namespace demo {

            struct Item {
            	std::string name;
            	int count = 0;
            };

            int total(const std::vector<Item> &items) {
            	int sum = 0;
            	for (const Item &item : items) {
            		if (item.count > 0) {
            			sum += item.count;
            		}
            	}
            	return sum;
            }

            }

            int main() {
            	std::vector<demo::Item> items = {{"apples", 3}, {"pears", 0}, {"plums", 12}};
            	return demo::total(items) == 15 ? 0 : 1;
            }
        """.trimIndent() + "\n"
    }
}
