package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

data class SearchOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    val wrapAround: Boolean = true,
) {
    val flags: Int
        get() = (if (matchCase) Sci.SCFIND_MATCHCASE else 0) or
            (if (wholeWord && !regex) Sci.SCFIND_WHOLEWORD else 0) or
            (if (regex) Sci.SCFIND_REGEXP or Sci.SCFIND_CXX11REGEX else 0)
}

data class FindResult(val found: Boolean, val wrapped: Boolean = false, val start: Long = -1, val end: Long = -1)

/** Find and replace on top of Scintilla's target search. */
object Searcher {
    private fun searchIn(sci: SciApi, needle: ByteArray, from: Long, to: Long): Long {
        sci.send(Sci.SCI_SETTARGETRANGE, from, to)
        return sci.sendBytes(Sci.SCI_SEARCHINTARGET, needle.size.toLong(), needle)
    }

    private fun select(sci: SciApi, start: Long, end: Long) {
        // Unfold if the match is hidden, then select it, which also scrolls it into view.
        sci.send(Sci.SCI_ENSUREVISIBLE, sci.send(Sci.SCI_LINEFROMPOSITION, start))
        sci.send(Sci.SCI_SETSEL, start, end)
    }

    /**
     * Selects the next (or previous) match relative to the current selection.
     * A regular expression that fails to compile simply finds nothing.
     */
    fun find(sci: SciApi, text: String, options: SearchOptions, forward: Boolean = true): FindResult {
        if (text.isEmpty()) return FindResult(false)
        val needle = text.toByteArray(Charsets.UTF_8)
        val length = sci.send(Sci.SCI_GETLENGTH)
        val selStart = sci.send(Sci.SCI_GETSELECTIONSTART)
        val selEnd = sci.send(Sci.SCI_GETSELECTIONEND)
        sci.send(Sci.SCI_SETSEARCHFLAGS, options.flags.toLong())

        var wrapped = false
        var position = if (forward) searchIn(sci, needle, selEnd, length) else searchIn(sci, needle, selStart, 0)
        if (position >= 0 && selStart == selEnd &&
            sci.send(Sci.SCI_GETTARGETSTART) == selStart && sci.send(Sci.SCI_GETTARGETEND) == selStart
        ) {
            // An empty match right at the caret (possible with patterns such as ^ or \b): move on, or the search never advances.
            val next = if (forward) sci.send(Sci.SCI_POSITIONAFTER, selStart) else sci.send(Sci.SCI_POSITIONBEFORE, selStart)
            position = if (next == selStart) -1 else if (forward) searchIn(sci, needle, next, length) else searchIn(sci, needle, next, 0)
        }
        if (position < 0 && options.wrapAround) {
            wrapped = true
            position = if (forward) searchIn(sci, needle, 0, length) else searchIn(sci, needle, length, 0)
        }
        if (position < 0) return FindResult(false)
        val start = sci.send(Sci.SCI_GETTARGETSTART)
        val end = sci.send(Sci.SCI_GETTARGETEND)
        select(sci, start, end)
        return FindResult(true, wrapped, start, end)
    }

    /** Notepad++ accepts $1 as well as \1 for groups; Scintilla only knows the backslash form. */
    private fun replacementBytes(replacement: String, options: SearchOptions): ByteArray =
        (if (options.regex) replacement.replace(Regex("""\$(\d)"""), "\\\\$1") else replacement).toByteArray(Charsets.UTF_8)

    private fun replaceTarget(sci: SciApi, replacement: ByteArray, options: SearchOptions): Long =
        sci.sendBytes(if (options.regex) Sci.SCI_REPLACETARGETRE else Sci.SCI_REPLACETARGET, replacement.size.toLong(), replacement)

    /**
     * If the selection is a match, replaces it; then selects the next match.
     * Returns whether a replacement happened and the result of the following find.
     */
    fun replace(sci: SciApi, text: String, replacement: String, options: SearchOptions): Pair<Boolean, FindResult> {
        if (text.isEmpty()) return false to FindResult(false)
        val needle = text.toByteArray(Charsets.UTF_8)
        val selStart = sci.send(Sci.SCI_GETSELECTIONSTART)
        val selEnd = sci.send(Sci.SCI_GETSELECTIONEND)
        sci.send(Sci.SCI_SETSEARCHFLAGS, options.flags.toLong())
        var replaced = false
        if (searchIn(sci, needle, selStart, selEnd) == selStart && sci.send(Sci.SCI_GETTARGETEND) == selEnd &&
            sci.send(Sci.SCI_GETREADONLY) == 0L
        ) {
            val newLength = replaceTarget(sci, replacementBytes(replacement, options), options)
            sci.send(Sci.SCI_SETEMPTYSELECTION, selStart + newLength)
            replaced = true
        }
        return replaced to find(sci, text, options, forward = true)
    }

    /** Replaces every match in the document as a single undo step. Returns the number of replacements. */
    fun replaceAll(sci: SciApi, text: String, replacement: String, options: SearchOptions): Int {
        if (text.isEmpty() || sci.send(Sci.SCI_GETREADONLY) != 0L) return 0
        val needle = text.toByteArray(Charsets.UTF_8)
        val with = replacementBytes(replacement, options)
        sci.send(Sci.SCI_SETSEARCHFLAGS, options.flags.toLong())
        var count = 0
        var position = 0L
        sci.send(Sci.SCI_BEGINUNDOACTION)
        try {
            while (count < MAX_MATCHES) {
                val length = sci.send(Sci.SCI_GETLENGTH)
                if (position > length || searchIn(sci, needle, position, length) < 0) break
                val start = sci.send(Sci.SCI_GETTARGETSTART)
                val empty = sci.send(Sci.SCI_GETTARGETEND) == start
                val newLength = replaceTarget(sci, with, options)
                count++
                position = start + newLength
                if (empty) {
                    // Step over one character so that an empty match is not found again at the same place.
                    val next = sci.send(Sci.SCI_POSITIONAFTER, position)
                    if (next == position) break
                    position = next
                }
            }
        } finally {
            sci.send(Sci.SCI_ENDUNDOACTION)
        }
        return count
    }

    fun count(sci: SciApi, text: String, options: SearchOptions): Int {
        if (text.isEmpty()) return 0
        val needle = text.toByteArray(Charsets.UTF_8)
        val length = sci.send(Sci.SCI_GETLENGTH)
        sci.send(Sci.SCI_SETSEARCHFLAGS, options.flags.toLong())
        var count = 0
        var position = 0L
        while (count < MAX_MATCHES && position <= length && searchIn(sci, needle, position, length) >= 0) {
            count++
            val start = sci.send(Sci.SCI_GETTARGETSTART)
            val end = sci.send(Sci.SCI_GETTARGETEND)
            position = if (end > start) end else sci.send(Sci.SCI_POSITIONAFTER, end).also { if (it == end) return count }
        }
        return count
    }

    private const val MAX_MATCHES = 10_000_000
}
