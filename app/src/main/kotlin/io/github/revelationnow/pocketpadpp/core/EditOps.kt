package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

/** Editing commands Notepad++ implements itself on top of Scintilla. */
object EditOps {
    private fun lineText(sci: SciApi, line: Long): String =
        String(sci.range(sci.send(Sci.SCI_POSITIONFROMLINE, line), sci.send(Sci.SCI_GETLINEENDPOSITION, line)), Charsets.UTF_8)

    /** First and last line touched by the selection; a selection ending at the start of a line does not include it. */
    private fun selectedLines(sci: SciApi): LongRange {
        val start = sci.send(Sci.SCI_GETSELECTIONSTART)
        val end = sci.send(Sci.SCI_GETSELECTIONEND)
        val first = sci.send(Sci.SCI_LINEFROMPOSITION, start)
        var last = sci.send(Sci.SCI_LINEFROMPOSITION, end)
        if (last > first && sci.send(Sci.SCI_POSITIONFROMLINE, last) == end) last--
        return first..last
    }

    /** The whole document when nothing is selected, otherwise the selected lines. */
    private fun targetLines(sci: SciApi): LongRange =
        if (sci.send(Sci.SCI_GETSELECTIONEMPTY) != 0L) 0L..(sci.send(Sci.SCI_GETLINECOUNT) - 1) else selectedLines(sci)

    private fun replaceRange(sci: SciApi, start: Long, end: Long, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sci.send(Sci.SCI_SETTARGETRANGE, start, end)
        sci.sendBytes(Sci.SCI_REPLACETARGET, bytes.size.toLong(), bytes)
    }

    private inline fun undoable(sci: SciApi, action: () -> Unit) {
        sci.send(Sci.SCI_BEGINUNDOACTION)
        try {
            action()
        } finally {
            sci.send(Sci.SCI_ENDUNDOACTION)
        }
    }

    /**
     * Toggle Single Line Comment: lines that already start with the marker lose it, the others gain it.
     * Returns false when the language has no line comment.
     */
    fun toggleLineComment(sci: SciApi, marker: String): Boolean {
        if (marker.isEmpty() || sci.send(Sci.SCI_GETREADONLY) != 0L) return false
        val lines = selectedLines(sci)
        undoable(sci) {
            for (line in lines) {
                val text = lineText(sci, line)
                val indent = text.length - text.trimStart(' ', '\t').length
                val body = text.substring(indent)
                if (body.isEmpty()) continue
                val lineStart = sci.send(Sci.SCI_POSITIONFROMLINE, line)
                val at = lineStart + text.substring(0, indent).toByteArray(Charsets.UTF_8).size
                if (body.startsWith(marker)) {
                    val remove = if (body.startsWith("$marker ")) marker.length + 1 else marker.length
                    replaceRange(sci, at, at + body.substring(0, remove).toByteArray(Charsets.UTF_8).size, "")
                } else {
                    replaceRange(sci, at, at, "$marker ")
                }
            }
        }
        return true
    }

    /** Trim Trailing Space, over the whole document. Returns the number of lines changed. */
    fun trimTrailingSpace(sci: SciApi): Int {
        var changed = 0
        undoable(sci) {
            for (line in 0 until sci.send(Sci.SCI_GETLINECOUNT)) {
                val text = lineText(sci, line)
                val trimmed = text.trimEnd(' ', '\t')
                if (trimmed.length != text.length) {
                    val end = sci.send(Sci.SCI_GETLINEENDPOSITION, line)
                    replaceRange(sci, end - (text.length - trimmed.length), end, "")
                    changed++
                }
            }
        }
        return changed
    }

    private fun rewriteLines(sci: SciApi, lines: LongRange, transform: (List<String>) -> List<String>) {
        if (lines.isEmpty()) return
        val original = lines.map { lineText(sci, it) }
        val result = transform(original)
        if (result == original) return
        val eol = when (sci.send(Sci.SCI_GETEOLMODE).toInt()) {
            Sci.SC_EOL_CRLF -> "\r\n"
            Sci.SC_EOL_CR -> "\r"
            else -> "\n"
        }
        val start = sci.send(Sci.SCI_POSITIONFROMLINE, lines.first)
        var end = sci.send(Sci.SCI_GETLINEENDPOSITION, lines.last)
        var text = result.joinToString(eol)
        if (result.isEmpty() && lines.last + 1 < sci.send(Sci.SCI_GETLINECOUNT)) {
            // Every line went away: take the final line break with them.
            end = sci.send(Sci.SCI_POSITIONFROMLINE, lines.last + 1)
            text = ""
        }
        undoable(sci) { replaceRange(sci, start, end, text) }
    }

    /** Sort Lines Lexicographically, ascending or descending, over the selection or the whole document. */
    fun sortLines(sci: SciApi, descending: Boolean) =
        rewriteLines(sci, targetLines(sci)) { if (descending) it.sortedDescending() else it.sorted() }

    /** Remove Empty Lines; optionally also lines holding only spaces and tabs. */
    fun removeEmptyLines(sci: SciApi, includeBlank: Boolean) =
        rewriteLines(sci, targetLines(sci)) { lines ->
            lines.filter { if (includeBlank) it.isNotBlank() else it.isNotEmpty() }
        }

    /** Remove Duplicate Lines, keeping the first of each. */
    fun removeDuplicateLines(sci: SciApi) = rewriteLines(sci, targetLines(sci)) { it.distinct() }

    /** Reverse Line Order. */
    fun reverseLines(sci: SciApi) = rewriteLines(sci, targetLines(sci)) { it.reversed() }

    /** Join Lines: the selected lines become one, separated by single spaces. */
    fun joinLines(sci: SciApi) {
        val lines = selectedLines(sci)
        if (lines.first == lines.last) return
        sci.send(Sci.SCI_SETTARGETRANGE, sci.send(Sci.SCI_POSITIONFROMLINE, lines.first), sci.send(Sci.SCI_GETLINEENDPOSITION, lines.last))
        sci.send(Sci.SCI_LINESJOIN)
    }

    /**
     * Highlights the brace next to the caret and its partner, as Notepad++ does on every caret move.
     * Returns the position of the matching brace, or -1.
     */
    fun highlightBraces(sci: SciApi): Long {
        val caret = sci.send(Sci.SCI_GETCURRENTPOS)
        fun isBrace(position: Long): Boolean =
            position >= 0 && sci.send(Sci.SCI_GETCHARAT, position).toInt().toChar() in "()[]{}"
        val brace = when {
            isBrace(caret - 1) -> caret - 1
            isBrace(caret) -> caret
            else -> -1L
        }
        if (brace < 0) {
            sci.send(Sci.SCI_BRACEHIGHLIGHT, -1, -1)
            return -1
        }
        val match = sci.send(Sci.SCI_BRACEMATCH, brace, 0)
        if (match < 0) sci.send(Sci.SCI_BRACEBADLIGHT, brace) else sci.send(Sci.SCI_BRACEHIGHLIGHT, brace, match)
        return match
    }

    /** Go to Matching Brace. */
    fun goToMatchingBrace(sci: SciApi): Boolean {
        val match = highlightBraces(sci)
        if (match < 0) return false
        sci.send(Sci.SCI_GOTOPOS, match)
        return true
    }

    /** Basic auto-indent: a new line starts with the indentation of the line above. Call for every SCN_CHARADDED. */
    fun autoIndent(sci: SciApi, ch: Int) {
        val eolMode = sci.send(Sci.SCI_GETEOLMODE).toInt()
        val lineEnd = if (eolMode == Sci.SC_EOL_CR) '\r'.code else '\n'.code
        if (ch != lineEnd) return
        val line = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS))
        if (line <= 0) return
        val indentation = sci.send(Sci.SCI_GETLINEINDENTATION, line - 1)
        if (indentation <= 0 || sci.send(Sci.SCI_GETLINEINDENTATION, line) != 0L) return
        sci.send(Sci.SCI_SETLINEINDENTATION, line, indentation)
        sci.send(Sci.SCI_GOTOPOS, sci.send(Sci.SCI_GETLINEINDENTPOSITION, line))
    }

    /** Character, word and line counts for View > Summary. */
    fun summary(sci: SciApi): Triple<Long, Long, Long> {
        val length = sci.send(Sci.SCI_GETLENGTH)
        val characters = sci.send(Sci.SCI_COUNTCHARACTERS, 0, length)
        var words = 0L
        var position = 0L
        while (position < length) {
            val end = sci.send(Sci.SCI_WORDENDPOSITION, position, 1)
            if (end > position) {
                words++
                position = end
            } else {
                position = sci.send(Sci.SCI_POSITIONAFTER, position).also { if (it == position) return Triple(characters, words, sci.send(Sci.SCI_GETLINECOUNT)) }
            }
        }
        return Triple(characters, words, sci.send(Sci.SCI_GETLINECOUNT))
    }
}
