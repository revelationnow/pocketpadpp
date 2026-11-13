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

    /**
     * Column Editor: puts one text on every line of the column selection, in place of what is selected there.
     * Without a column selection the texts go on every line from the caret's down to the last one, at the
     * caret's column, as in Notepad++; lines that are too short are padded with spaces.
     * [texts] is asked once for as many texts as there are lines. Returns the number of lines changed.
     */
    fun columnInsert(sci: SciApi, texts: (Int) -> List<String>): Int {
        class Slot(val start: Long, val end: Long, val padding: Long)
        val slots = ArrayList<Slot>()
        if (sci.send(Sci.SCI_SELECTIONISRECTANGLE) != 0L) {
            val anchor = sci.send(Sci.SCI_GETRECTANGULARSELECTIONANCHOR)
            val caret = sci.send(Sci.SCI_GETRECTANGULARSELECTIONCARET)
            // The left edge of the rectangle, which may lie beyond the end of some of its lines.
            val column = minOf(
                sci.send(Sci.SCI_GETCOLUMN, anchor) + sci.send(Sci.SCI_GETRECTANGULARSELECTIONANCHORVIRTUALSPACE),
                sci.send(Sci.SCI_GETCOLUMN, caret) + sci.send(Sci.SCI_GETRECTANGULARSELECTIONCARETVIRTUALSPACE),
            )
            val anchorLine = sci.send(Sci.SCI_LINEFROMPOSITION, anchor)
            val caretLine = sci.send(Sci.SCI_LINEFROMPOSITION, caret)
            for (line in minOf(anchorLine, caretLine)..maxOf(anchorLine, caretLine)) {
                var start = sci.send(Sci.SCI_GETLINESELSTARTPOSITION, line)
                var end = sci.send(Sci.SCI_GETLINESELENDPOSITION, line)
                if (start < 0) {
                    start = sci.send(Sci.SCI_GETLINEENDPOSITION, line)
                    end = start
                }
                slots += Slot(start, end, (column - sci.send(Sci.SCI_GETCOLUMN, start)).coerceAtLeast(0))
            }
        } else {
            val caret = sci.send(Sci.SCI_GETCURRENTPOS)
            val column = sci.send(Sci.SCI_GETCOLUMN, caret)
            for (line in sci.send(Sci.SCI_LINEFROMPOSITION, caret) until sci.send(Sci.SCI_GETLINECOUNT)) {
                val position = sci.send(Sci.SCI_FINDCOLUMN, line, column)
                slots += Slot(position, position, (column - sci.send(Sci.SCI_GETCOLUMN, position)).coerceAtLeast(0))
            }
        }
        val all = texts(slots.size)
        sci.send(Sci.SCI_BEGINUNDOACTION)
        // From the bottom up, so that the positions noted above stay valid.
        for (i in slots.indices.reversed()) {
            val slot = slots[i]
            val bytes = (" ".repeat(slot.padding.toInt()) + all.getOrElse(i) { "" }).toByteArray(Charsets.UTF_8)
            sci.send(Sci.SCI_SETTARGETRANGE, slot.start, slot.end)
            sci.sendBytes(Sci.SCI_REPLACETARGET, bytes.size.toLong(), bytes)
        }
        sci.send(Sci.SCI_ENDUNDOACTION)
        slots.firstOrNull()?.let { sci.send(Sci.SCI_SETEMPTYSELECTION, it.start) }
        return slots.size
    }

    /**
     * Search > Find characters in range: selects the next character whose bytes fall in [low]..[high]
     * (0 to 255, as Notepad++ counts them), searching from the selection. Returns false when there is none.
     */
    fun findCharInRange(sci: SciApi, low: Int, high: Int, forward: Boolean, wrap: Boolean): Boolean {
        val length = sci.send(Sci.SCI_GETLENGTH)
        fun scan(from: Long, to: Long): Long {
            // In chunks, so that a large document is not copied whole or asked for byte by byte.
            val chunk = 1L shl 16
            var start = from
            while (start < to) {
                val end = minOf(start + chunk, to)
                val bytes = sci.range(start, end)
                for (i in bytes.indices) {
                    if ((bytes[i].toInt() and 0xFF) in low..high) return start + i
                }
                start = end
            }
            return -1
        }
        val selStart = sci.send(Sci.SCI_GETSELECTIONSTART)
        val selEnd = sci.send(Sci.SCI_GETSELECTIONEND)
        var found = if (forward) scan(selEnd, length) else scanBackward(sci, low, high, 0, selStart)
        if (found < 0 && wrap) found = if (forward) scan(0, selEnd) else scanBackward(sci, low, high, selStart, length)
        if (found < 0) return false
        // Whole characters: a byte in the middle of a UTF-8 sequence selects the character it belongs to.
        val start = sci.send(Sci.SCI_POSITIONBEFORE, sci.send(Sci.SCI_POSITIONAFTER, found))
        sci.send(Sci.SCI_ENSUREVISIBLE, sci.send(Sci.SCI_LINEFROMPOSITION, start))
        sci.send(Sci.SCI_SETSEL, start, sci.send(Sci.SCI_POSITIONAFTER, start))
        return true
    }

    private fun scanBackward(sci: SciApi, low: Int, high: Int, from: Long, to: Long): Long {
        val chunk = 1L shl 16
        var end = to
        while (end > from) {
            val start = maxOf(from, end - chunk)
            val bytes = sci.range(start, end)
            for (i in bytes.indices.reversed()) {
                if ((bytes[i].toInt() and 0xFF) in low..high) return start + i
            }
            end = start
        }
        return -1
    }

    /** On Selection > Redact Selection: every selected character except line ends becomes a block. */
    fun redactSelection(sci: SciApi): Boolean =
        transformSelection(sci) { text -> text.map { if (it == '\n' || it == '\r') it else '\u2588' }.joinToString("") }

    /** View > Hide Lines: hides the lines the selection touches, except the first and last line of the document. */
    fun hideLines(sci: SciApi): Boolean {
        val last = sci.send(Sci.SCI_GETLINECOUNT) - 1
        val from = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETSELECTIONSTART)).coerceAtLeast(1)
        val to = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETSELECTIONEND)).coerceAtMost(last - 1)
        if (to < from) return false
        sci.send(Sci.SCI_HIDELINES, from, to)
        sci.send(Sci.SCI_GOTOLINE, from - 1)
        return true
    }

    private const val HISTORY = Sci.SC_CHANGE_HISTORY_ENABLED or Sci.SC_CHANGE_HISTORY_MARKERS

    /**
     * Turns on change history for the document on screen: changed lines get a mark in the margin, orange
     * until saved and green afterwards. Scintilla only allows this while there is nothing to undo.
     */
    fun enableChangeHistory(sci: SciApi) {
        sci.send(Sci.SCI_SETCHANGEHISTORY, HISTORY.toLong())
    }

    private fun changed(sci: SciApi, line: Long): Boolean =
        line >= 0 && sci.send(Sci.SCI_MARKERGET, line) and Sci.SC_MASK_HISTORY.toLong() != 0L

    /** Search > Changed Lines > Go to Next / Previous Change: to the start of the next run of changed lines, wrapping. */
    fun goToChange(sci: SciApi, forward: Boolean): Boolean {
        val mask = Sci.SC_MASK_HISTORY.toLong()
        val count = sci.send(Sci.SCI_GETLINECOUNT)
        var line = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS))
        var target: Long
        if (forward) {
            while (line < count && changed(sci, line)) line++
            // SCI_MARKERNEXT does not see change history marks (SCI_MARKERGET and SCI_MARKERPREVIOUS do),
            // so the lines are asked one by one.
            target = (line until count).firstOrNull { changed(sci, it) } ?: (0L until line).firstOrNull { changed(sci, it) } ?: -1L
        } else {
            while (line >= 0 && changed(sci, line)) line--
            target = if (line >= 0) sci.send(Sci.SCI_MARKERPREVIOUS, line, mask) else -1
            if (target < 0) target = sci.send(Sci.SCI_MARKERPREVIOUS, count - 1, mask)
            while (target > 0 && changed(sci, target - 1)) target--
        }
        if (target < 0) return false
        sci.send(Sci.SCI_ENSUREVISIBLEENFORCEPOLICY, target)
        sci.send(Sci.SCI_GOTOLINE, target)
        return true
    }

    /** Search > Changed Lines > Clear Change History. As in Notepad++ this also empties the undo history. */
    fun clearChangeHistory(sci: SciApi) {
        sci.send(Sci.SCI_SETCHANGEHISTORY, 0)
        sci.send(Sci.SCI_EMPTYUNDOBUFFER)
        sci.send(Sci.SCI_SETCHANGEHISTORY, HISTORY.toLong())
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
