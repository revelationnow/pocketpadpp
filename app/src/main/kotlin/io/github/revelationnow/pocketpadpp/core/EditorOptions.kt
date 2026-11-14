package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

/** The editing choices of Settings > Preferences. */
data class EditorOptions(
    val tabSize: Int = 4,
    val useSpaces: Boolean = false,
    val autoIndent: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    /** 1 thin, 2 normal, 3 thick, 4 a block over the character. */
    val caret: Int = 2,
    val caretBlinks: Boolean = true,
    val scrollBeyondLastLine: Boolean = false,
    /** Column of the vertical edge line, or 0 for none. */
    val edgeColumn: Int = 0,
) {
    /**
     * Tab settings belong to the document and the rest to the view, so this runs for every tab that is shown,
     * after the theme has been applied (which decides whether the current line can be highlighted at all).
     */
    fun apply(sci: SciApi, pixelScale: Float) {
        sci.send(Sci.SCI_SETTABWIDTH, tabSize.coerceIn(1, 32).toLong())
        sci.send(Sci.SCI_SETINDENT, 0)
        sci.send(Sci.SCI_SETUSETABS, if (useSpaces) 0 else 1)
        if (!highlightCurrentLine) sci.send(Sci.SCI_SETCARETLINEVISIBLE, 0)
        if (caret >= 4) {
            sci.send(Sci.SCI_SETCARETSTYLE, Sci.CARETSTYLE_BLOCK.toLong())
        } else {
            sci.send(Sci.SCI_SETCARETSTYLE, Sci.CARETSTYLE_LINE.toLong())
            sci.send(Sci.SCI_SETCARETWIDTH, Math.round(caret.coerceIn(1, 3) * pixelScale).coerceIn(1, 20).toLong())
        }
        sci.send(Sci.SCI_SETCARETPERIOD, if (caretBlinks) 500 else 0)
        sci.send(Sci.SCI_SETENDATLASTLINE, if (scrollBeyondLastLine) 0 else 1)
        sci.send(Sci.SCI_SETEDGEMODE, (if (edgeColumn > 0) Sci.EDGE_LINE else Sci.EDGE_NONE).toLong())
        sci.send(Sci.SCI_SETEDGECOLUMN, edgeColumn.toLong())
    }
}
