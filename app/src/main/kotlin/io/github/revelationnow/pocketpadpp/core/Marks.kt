package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

/**
 * Notepad++'s marks: coloured highlights laid over text. Five "styles" are put on by hand
 * (Search > Mark All > Using 1st Style ...), and one more is used by Search > Mark for everything a search finds.
 * They are Scintilla indicators with the same numbers Notepad++ uses.
 */
object Marks {
    /** The style Search > Mark uses ("Find Mark Style" in Notepad++'s themes). */
    const val FOUND = 0

    /** Indicator numbers for [FOUND] and for the 1st to 5th style, as in Notepad++. */
    private val INDICATORS = intArrayOf(31, 25, 24, 23, 22, 21)
    private val THEME_NAMES = listOf("Find Mark Style", "Mark Style 1", "Mark Style 2", "Mark Style 3", "Mark Style 4", "Mark Style 5")
    private val DEFAULT_COLOURS = intArrayOf(0xFF0000, 0x00FFFF, 0xFF8000, 0xFFFF00, 0x8000FF, 0x008000)

    val STYLES = 1..5

    fun indicator(style: Int): Int = INDICATORS[style.coerceIn(0, 5)]

    /** Gives the marks their looks; part of applying a theme. */
    fun setup(sci: SciApi, theme: NppTheme) {
        for (style in 0..5) {
            val indicator = indicator(style).toLong()
            val colour = theme.global(THEME_NAMES[style])?.bg ?: DEFAULT_COLOURS[style]
            sci.send(Sci.SCI_INDICSETSTYLE, indicator, Sci.INDIC_ROUNDBOX.toLong())
            sci.send(Sci.SCI_INDICSETFORE, indicator, ThemeApplier.sciColour(colour))
            sci.send(Sci.SCI_INDICSETALPHA, indicator, 100)
            sci.send(Sci.SCI_INDICSETOUTLINEALPHA, indicator, 160)
            sci.send(Sci.SCI_INDICSETUNDER, indicator, 1)
        }
    }

    private fun fill(sci: SciApi, style: Int, start: Long, end: Long) {
        if (end <= start) return
        sci.send(Sci.SCI_SETINDICATORCURRENT, indicator(style).toLong())
        sci.send(Sci.SCI_INDICATORFILLRANGE, start, end - start)
    }

    /** Marks every match of a search. With [bookmarkLines] the lines of the matches get a bookmark too. Returns the count. */
    fun markAll(sci: SciApi, style: Int, text: String, options: SearchOptions, bookmarkLines: Boolean = false): Int =
        Searcher.forEachMatch(sci, text, options) { start, end ->
            fill(sci, style, start, end)
            if (bookmarkLines) {
                val line = sci.send(Sci.SCI_LINEFROMPOSITION, start)
                if (sci.send(Sci.SCI_MARKERGET, line) and (1L shl ThemeApplier.BOOKMARK_MARKER) == 0L) {
                    sci.send(Sci.SCI_MARKERADD, line, ThemeApplier.BOOKMARK_MARKER.toLong())
                }
            }
        }

    /** Mark All > Using Nth Style: every occurrence of the selection, or of the word at the caret. */
    fun markSelection(sci: SciApi, style: Int, matchCase: Boolean, wholeWord: Boolean): Int {
        val start = sci.send(Sci.SCI_GETSELECTIONSTART)
        val end = sci.send(Sci.SCI_GETSELECTIONEND)
        val caret = sci.send(Sci.SCI_GETCURRENTPOS)
        val from = if (end > start) start else sci.send(Sci.SCI_WORDSTARTPOSITION, caret, 1)
        val to = if (end > start) end else sci.send(Sci.SCI_WORDENDPOSITION, caret, 1)
        if (to <= from) return 0
        val text = String(sci.range(from, to), Charsets.UTF_8)
        if ('\n' in text || '\r' in text) return 0
        return markAll(sci, style, text, SearchOptions(matchCase, wholeWord, regex = false, wrapAround = true))
    }

    /** Mark One > Using Nth Style: just the selection. */
    fun markRange(sci: SciApi, style: Int): Boolean {
        val start = sci.send(Sci.SCI_GETSELECTIONSTART)
        val end = sci.send(Sci.SCI_GETSELECTIONEND)
        fill(sci, style, start, end)
        return end > start
    }

    fun clear(sci: SciApi, style: Int) {
        sci.send(Sci.SCI_SETINDICATORCURRENT, indicator(style).toLong())
        sci.send(Sci.SCI_INDICATORCLEARRANGE, 0, sci.send(Sci.SCI_GETLENGTH))
    }

    fun clearAllStyles(sci: SciApi) {
        for (style in STYLES) clear(sci, style)
    }

    private fun marked(sci: SciApi, style: Int, position: Long): Boolean =
        position >= 0 && position < sci.send(Sci.SCI_GETLENGTH) && sci.send(Sci.SCI_INDICATORVALUEAT, indicator(style).toLong(), position) != 0L

    /** Every marked stretch of text as (start, end), in document order. */
    fun ranges(sci: SciApi, style: Int): List<Pair<Long, Long>> {
        val indicator = indicator(style).toLong()
        val length = sci.send(Sci.SCI_GETLENGTH)
        val result = ArrayList<Pair<Long, Long>>()
        var position = 0L
        while (position < length) {
            val end = sci.send(Sci.SCI_INDICATOREND, indicator, position)
            if (end <= position) break
            if (marked(sci, style, position)) result += position to end
            position = end
        }
        return result
    }

    /** Jump Down / Jump Up: moves the caret to the next or previous mark, wrapping around. Returns false when there is none. */
    fun jump(sci: SciApi, style: Int, forward: Boolean): Boolean {
        val all = ranges(sci, style)
        if (all.isEmpty()) return false
        val caret = sci.send(Sci.SCI_GETCURRENTPOS)
        val target = if (forward) {
            all.firstOrNull { it.first > caret } ?: all.first()
        } else {
            // The mark the caret is in or at the start of does not count; the one before it does.
            all.lastOrNull { it.second <= caret } ?: all.last()
        }
        sci.send(Sci.SCI_ENSUREVISIBLEENFORCEPOLICY, sci.send(Sci.SCI_LINEFROMPOSITION, target.first))
        sci.send(Sci.SCI_GOTOPOS, target.first)
        return true
    }

    /** Copy Styled Text: the marked stretches, one per line. An empty list of styles gives nothing. */
    fun text(sci: SciApi, styles: List<Int>): String =
        styles.flatMap { ranges(sci, it) }.sortedBy { it.first }
            .joinToString("\n") { String(sci.range(it.first, it.second), Charsets.UTF_8) }

    // Bookmarked lines

    private fun bookmarked(sci: SciApi, line: Long): Boolean =
        sci.send(Sci.SCI_MARKERGET, line) and (1L shl ThemeApplier.BOOKMARK_MARKER) != 0L

    private fun lineRange(sci: SciApi, line: Long): Pair<Long, Long> =
        sci.send(Sci.SCI_POSITIONFROMLINE, line) to sci.send(Sci.SCI_POSITIONFROMLINE, line) + sci.send(Sci.SCI_LINELENGTH, line)

    /** Copy Bookmarked Lines: their text, each with a line ending. */
    fun bookmarkedText(sci: SciApi): String {
        val eol = when (sci.send(Sci.SCI_GETEOLMODE).toInt()) {
            Sci.SC_EOL_CRLF -> "\r\n"
            Sci.SC_EOL_CR -> "\r"
            else -> "\n"
        }
        val out = StringBuilder()
        for (line in 0 until sci.send(Sci.SCI_GETLINECOUNT)) {
            if (!bookmarked(sci, line)) continue
            val start = sci.send(Sci.SCI_POSITIONFROMLINE, line)
            out.append(String(sci.range(start, sci.send(Sci.SCI_GETLINEENDPOSITION, line)), Charsets.UTF_8)).append(eol)
        }
        return out.toString()
    }

    /** Remove Bookmarked Lines ([bookmarkedOnes] true) or Remove Non-Bookmarked Lines (false). Returns the number removed. */
    fun removeLines(sci: SciApi, bookmarkedOnes: Boolean): Int {
        var removed = 0
        sci.send(Sci.SCI_BEGINUNDOACTION)
        for (line in sci.send(Sci.SCI_GETLINECOUNT) - 1 downTo 0) {
            if (bookmarked(sci, line) != bookmarkedOnes) continue
            val (start, end) = lineRange(sci, line)
            // The last line has no line ending of its own; an empty last line is not a line to remove.
            if (end == start && line == sci.send(Sci.SCI_GETLINECOUNT) - 1) continue
            if (bookmarkedOnes) sci.send(Sci.SCI_MARKERDELETE, line, ThemeApplier.BOOKMARK_MARKER.toLong())
            sci.send(Sci.SCI_DELETERANGE, start, end - start)
            removed++
        }
        sci.send(Sci.SCI_ENDUNDOACTION)
        return removed
    }

    /** Paste to (Replace) Bookmarked Lines: every bookmarked line becomes [text]. Returns the number replaced. */
    fun replaceBookmarked(sci: SciApi, text: String): Int {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var replaced = 0
        sci.send(Sci.SCI_BEGINUNDOACTION)
        for (line in sci.send(Sci.SCI_GETLINECOUNT) - 1 downTo 0) {
            if (!bookmarked(sci, line)) continue
            sci.send(Sci.SCI_SETTARGETRANGE, sci.send(Sci.SCI_POSITIONFROMLINE, line), sci.send(Sci.SCI_GETLINEENDPOSITION, line))
            sci.sendBytes(Sci.SCI_REPLACETARGET, bytes.size.toLong(), bytes)
            replaced++
        }
        sci.send(Sci.SCI_ENDUNDOACTION)
        return replaced
    }

    /** Inverse Bookmark: bookmarked lines lose their bookmark and all others get one. */
    fun invertBookmarks(sci: SciApi) {
        val marker = ThemeApplier.BOOKMARK_MARKER.toLong()
        for (line in 0 until sci.send(Sci.SCI_GETLINECOUNT)) {
            if (bookmarked(sci, line)) sci.send(Sci.SCI_MARKERDELETE, line, marker) else sci.send(Sci.SCI_MARKERADD, line, marker)
        }
    }
}
