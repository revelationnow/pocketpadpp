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

    /** The Sort Lines commands, over the selection or the whole document. */
    fun sortLines(sci: SciApi, descending: Boolean, kind: TextTransforms.Sort = TextTransforms.Sort.LEXICOGRAPHIC) =
        rewriteLines(sci, targetLines(sci)) { TextTransforms.sort(it, kind, descending) }

    /** Randomize Line Order. */
    fun shuffleLines(sci: SciApi, random: java.util.Random = java.util.Random()) =
        rewriteLines(sci, targetLines(sci)) { it.shuffled(random) }

    /** Remove Consecutive Duplicate Lines. */
    fun removeConsecutiveDuplicateLines(sci: SciApi) =
        rewriteLines(sci, targetLines(sci)) { TextTransforms.removeConsecutiveDuplicates(it) }

    /** Blank Operations that work line by line, over the selection or the whole document. */
    fun mapLines(sci: SciApi, transform: (String) -> String) =
        rewriteLines(sci, targetLines(sci)) { lines -> lines.map(transform) }

    fun tabsToSpaces(sci: SciApi) {
        val width = sci.send(Sci.SCI_GETTABWIDTH).toInt()
        mapLines(sci) { TextTransforms.tabsToSpaces(it, width) }
    }

    fun spacesToTabs(sci: SciApi, leadingOnly: Boolean) {
        val width = sci.send(Sci.SCI_GETTABWIDTH).toInt()
        mapLines(sci) { TextTransforms.spacesToTabs(it, width, leadingOnly) }
    }

    /** EOL to Space, optionally trimming each line first: the target lines become one line. */
    fun eolToSpace(sci: SciApi, trim: Boolean) {
        val lines = targetLines(sci)
        if (lines.isEmpty()) return
        val joined = lines.map { lineText(sci, it) }.map { if (trim) TextTransforms.trimBoth(it) else it }.joinToString(" ")
        undoable(sci) {
            replaceRange(sci, sci.send(Sci.SCI_POSITIONFROMLINE, lines.first), sci.send(Sci.SCI_GETLINEENDPOSITION, lines.last), joined)
        }
    }

    /** Replaces the selection with a transformed copy and keeps it selected. Used by the Convert Case commands. */
    fun transformSelection(sci: SciApi, transform: (String) -> String): Boolean {
        val start = sci.send(Sci.SCI_GETSELECTIONSTART)
        val end = sci.send(Sci.SCI_GETSELECTIONEND)
        if (start == end || sci.send(Sci.SCI_GETREADONLY) != 0L) return false
        val original = String(sci.range(start, end), Charsets.UTF_8)
        val changed = transform(original)
        if (changed != original) {
            undoable(sci) { replaceRange(sci, start, end, changed) }
            sci.send(Sci.SCI_SETSEL, start, start + changed.toByteArray(Charsets.UTF_8).size)
        }
        return true
    }

    /** Single Line Comment / Single Line Uncomment: add the marker to every selected line, or remove it. */
    fun setLineComment(sci: SciApi, marker: String, comment: Boolean): Boolean {
        if (marker.isEmpty() || sci.send(Sci.SCI_GETREADONLY) != 0L) return false
        rewriteLines(sci, selectedLines(sci)) { lines ->
            lines.map { line ->
                val indent = line.length - line.trimStart(' ', '\t').length
                val body = line.substring(indent)
                when {
                    body.isEmpty() -> line
                    comment -> line.substring(0, indent) + marker + " " + body
                    body.startsWith("$marker ") -> line.substring(0, indent) + body.substring(marker.length + 1)
                    body.startsWith(marker) -> line.substring(0, indent) + body.substring(marker.length)
                    else -> line
                }
            }
        }
        return true
    }

    /** Block Comment: wraps the selection in the language's comment start and end markers. */
    fun streamComment(sci: SciApi, startMarker: String, endMarker: String): Boolean {
        if (startMarker.isEmpty() || endMarker.isEmpty() || sci.send(Sci.SCI_GETREADONLY) != 0L) return false
        val start = sci.send(Sci.SCI_GETSELECTIONSTART)
        val end = sci.send(Sci.SCI_GETSELECTIONEND)
        if (start == end) return false
        val opening = "$startMarker "
        val closing = " $endMarker"
        undoable(sci) {
            replaceRange(sci, end, end, closing)
            replaceRange(sci, start, start, opening)
        }
        sci.send(Sci.SCI_SETSEL, start, end + opening.toByteArray().size + closing.toByteArray().size)
        return true
    }

    /** Block Uncomment: removes the comment markers at the two ends of the selection. */
    fun streamUncomment(sci: SciApi, startMarker: String, endMarker: String): Boolean {
        if (startMarker.isEmpty() || endMarker.isEmpty() || sci.send(Sci.SCI_GETREADONLY) != 0L) return false
        val start = sci.send(Sci.SCI_GETSELECTIONSTART)
        val end = sci.send(Sci.SCI_GETSELECTIONEND)
        val text = String(sci.range(start, end), Charsets.UTF_8)
        val trimmed = text.trim()
        if (!trimmed.startsWith(startMarker) || !trimmed.endsWith(endMarker) || trimmed.length < startMarker.length + endMarker.length) {
            return false
        }
        val leading = text.substring(0, text.indexOf(trimmed))
        val trailing = text.substring(text.indexOf(trimmed) + trimmed.length)
        val inner = trimmed.substring(startMarker.length, trimmed.length - endMarker.length).removePrefix(" ").removeSuffix(" ")
        val result = leading + inner + trailing
        undoable(sci) { replaceRange(sci, start, end, result) }
        sci.send(Sci.SCI_SETSEL, start, start + result.toByteArray(Charsets.UTF_8).size)
        return true
    }

    /** Insert Blank Line Above / Below Current, leaving the caret on the new line. */
    fun insertBlankLine(sci: SciApi, above: Boolean) {
        val eol = when (sci.send(Sci.SCI_GETEOLMODE).toInt()) {
            Sci.SC_EOL_CRLF -> "\r\n"
            Sci.SC_EOL_CR -> "\r"
            else -> "\n"
        }
        val line = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS))
        if (above) {
            val at = sci.send(Sci.SCI_POSITIONFROMLINE, line)
            replaceRange(sci, at, at, eol)
            sci.send(Sci.SCI_GOTOPOS, at)
        } else {
            val at = sci.send(Sci.SCI_GETLINEENDPOSITION, line)
            replaceRange(sci, at, at, eol)
            sci.send(Sci.SCI_GOTOPOS, at + eol.length)
        }
    }

    /** Split Lines: wraps the selected lines at the window edge by inserting line breaks. */
    fun splitLines(sci: SciApi) {
        sci.send(Sci.SCI_TARGETFROMSELECTION)
        sci.send(Sci.SCI_LINESSPLIT, 0)
    }

    // Multi-select

    private fun searchFlags(matchCase: Boolean, wholeWord: Boolean): Long =
        ((if (matchCase) Sci.SCFIND_MATCHCASE else 0) or (if (wholeWord) Sci.SCFIND_WHOLEWORD else 0)).toLong()

    /** Multi-select All: a caret on every occurrence of the selection (or of the word at the caret). */
    fun multiSelectAll(sci: SciApi, matchCase: Boolean, wholeWord: Boolean) {
        sci.send(Sci.SCI_SETSEARCHFLAGS, searchFlags(matchCase, wholeWord))
        sci.send(Sci.SCI_TARGETWHOLEDOCUMENT)
        // With nothing selected Scintilla's first step only selects the word at the caret;
        // Notepad++ selects that word and then carries on.
        if (sci.send(Sci.SCI_GETSELECTIONEMPTY) != 0L) sci.send(Sci.SCI_MULTIPLESELECTADDNEXT)
        if (sci.send(Sci.SCI_GETSELECTIONEMPTY) != 0L) return
        sci.send(Sci.SCI_MULTIPLESELECTADDEACH)
    }

    /** Multi-select Next: adds the next occurrence to the set of selections. */
    fun multiSelectNext(sci: SciApi, matchCase: Boolean, wholeWord: Boolean) {
        sci.send(Sci.SCI_SETSEARCHFLAGS, searchFlags(matchCase, wholeWord))
        sci.send(Sci.SCI_TARGETWHOLEDOCUMENT)
        // As in Notepad++, with nothing selected the first use selects the word at the caret.
        sci.send(Sci.SCI_MULTIPLESELECTADDNEXT)
    }

    /** Undo the Latest Added Multi-Select. */
    fun multiSelectUndo(sci: SciApi) {
        if (sci.send(Sci.SCI_GETSELECTIONS) > 1) {
            sci.send(Sci.SCI_DROPSELECTIONN, sci.send(Sci.SCI_GETMAINSELECTION))
        }
    }

    /** Skip Current & Go to Next Multi-select: the latest selection moves on to the following occurrence. */
    fun multiSelectSkip(sci: SciApi, matchCase: Boolean, wholeWord: Boolean) {
        val count = sci.send(Sci.SCI_GETSELECTIONS)
        val main = sci.send(Sci.SCI_GETMAINSELECTION)
        multiSelectNext(sci, matchCase, wholeWord)
        if (sci.send(Sci.SCI_GETSELECTIONS) > count) sci.send(Sci.SCI_DROPSELECTIONN, main)
    }

    // Folding

    /** Fold Level N / Unfold Level N: collapses or expands every fold header at that depth (1 is the outermost). */
    fun foldLevel(sci: SciApi, level: Int, collapse: Boolean) {
        sci.send(Sci.SCI_COLOURISE, 0, -1)
        val wanted = Sci.SC_FOLDLEVELBASE + level - 1
        for (line in 0 until sci.send(Sci.SCI_GETLINECOUNT)) {
            val fold = sci.send(Sci.SCI_GETFOLDLEVEL, line).toInt()
            if (fold and Sci.SC_FOLDLEVELHEADERFLAG != 0 && fold and Sci.SC_FOLDLEVELNUMBERMASK == wanted) {
                sci.send(Sci.SCI_FOLDLINE, line, (if (collapse) Sci.SC_FOLDACTION_CONTRACT else Sci.SC_FOLDACTION_EXPAND).toLong())
            }
        }
    }

    /** Fold / Unfold Current Level: the block the caret is in. */
    fun foldCurrent(sci: SciApi, collapse: Boolean) {
        val line = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS))
        val header = if (sci.send(Sci.SCI_GETFOLDLEVEL, line).toInt() and Sci.SC_FOLDLEVELHEADERFLAG != 0) {
            line
        } else {
            sci.send(Sci.SCI_GETFOLDPARENT, line)
        }
        if (header >= 0) {
            sci.send(Sci.SCI_FOLDLINE, header, (if (collapse) Sci.SC_FOLDACTION_CONTRACT else Sci.SC_FOLDACTION_EXPAND).toLong())
        }
    }

    /** Lines that are currently folded, for remembering fold state while a tab is in the background. */
    fun foldedLines(sci: SciApi): List<Long> {
        val result = ArrayList<Long>()
        var line = sci.send(Sci.SCI_CONTRACTEDFOLDNEXT, 0)
        while (line >= 0) {
            result += line
            line = sci.send(Sci.SCI_CONTRACTEDFOLDNEXT, line + 1)
        }
        return result
    }

    fun restoreFolds(sci: SciApi, lines: List<Long>) {
        if (lines.isEmpty()) return
        // Fold levels only exist for text the lexer has seen.
        val last = lines.max()
        sci.send(Sci.SCI_COLOURISE, 0, sci.send(Sci.SCI_POSITIONFROMLINE, last + 1).let { if (it < 0) -1 else it })
        for (line in lines) {
            if (sci.send(Sci.SCI_GETFOLDLEVEL, line).toInt() and Sci.SC_FOLDLEVELHEADERFLAG != 0) {
                sci.send(Sci.SCI_FOLDLINE, line, Sci.SC_FOLDACTION_CONTRACT.toLong())
            }
        }
    }

    /** Select All In-between {} [] or (): the text inside the pair of braces next to the caret. */
    fun selectBetweenBraces(sci: SciApi): Boolean {
        val caret = sci.send(Sci.SCI_GETCURRENTPOS)
        fun isBrace(position: Long): Boolean =
            position >= 0 && sci.send(Sci.SCI_GETCHARAT, position).toInt().toChar() in "()[]{}"
        val brace = when {
            isBrace(caret - 1) -> caret - 1
            isBrace(caret) -> caret
            else -> return false
        }
        val match = sci.send(Sci.SCI_BRACEMATCH, brace, 0)
        if (match < 0) return false
        sci.send(Sci.SCI_SETSEL, minOf(brace, match) + 1, maxOf(brace, match))
        return true
    }

    /** The selection, or the word at the caret when nothing is selected. Used by the select-and-find commands. */
    fun selectionOrWord(sci: SciApi): String {
        var start = sci.send(Sci.SCI_GETSELECTIONSTART)
        var end = sci.send(Sci.SCI_GETSELECTIONEND)
        if (start == end) {
            val caret = sci.send(Sci.SCI_GETCURRENTPOS)
            start = sci.send(Sci.SCI_WORDSTARTPOSITION, caret, 1)
            end = sci.send(Sci.SCI_WORDENDPOSITION, caret, 1)
            if (start < end) sci.send(Sci.SCI_SETSEL, start, end)
        }
        return String(sci.range(start, end), Charsets.UTF_8)
    }

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
