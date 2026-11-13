package io.github.revelationnow.pocketpadpp.core

import java.text.Collator

/** Pure text transformations behind the Edit menu's case, blank and sort commands. */
object TextTransforms {

    // Convert Case to

    /** Proper Case: the first letter of every word in upper case; the rest lower-cased unless [blend] keeps them. */
    fun properCase(text: String, blend: Boolean): String = buildString(text.length) {
        var startOfWord = true
        for (ch in text) {
            if (ch.isLetter()) {
                append(if (startOfWord) ch.uppercaseChar() else if (blend) ch else ch.lowercaseChar())
                startOfWord = false
            } else {
                append(ch)
                // Digits are part of a word ("3rd") and apostrophes do not end one ("don't").
                if (ch.isDigit()) startOfWord = false else if (ch != '\'') startOfWord = true
            }
        }
    }

    /** Sentence case: the first letter of every sentence in upper case; the rest lower-cased unless [blend] keeps them. */
    fun sentenceCase(text: String, blend: Boolean): String = buildString(text.length) {
        var startOfSentence = true
        var afterStop = false
        for (ch in text) {
            when {
                ch.isLetter() -> {
                    append(if (startOfSentence) ch.uppercaseChar() else if (blend) ch else ch.lowercaseChar())
                    startOfSentence = false
                    afterStop = false
                }
                ch == '.' || ch == '!' || ch == '?' -> {
                    append(ch)
                    afterStop = true
                }
                ch == '\n' || ch == '\r' -> {
                    append(ch)
                    startOfSentence = true
                    afterStop = false
                }
                ch.isWhitespace() -> {
                    append(ch)
                    if (afterStop) startOfSentence = true
                }
                else -> {
                    append(ch)
                    afterStop = false
                    // A sentence that opens with a number has no letter to capitalise.
                    if (ch.isDigit()) startOfSentence = false
                }
            }
        }
    }

    fun invertCase(text: String): String = buildString(text.length) {
        for (ch in text) append(if (ch.isUpperCase()) ch.lowercaseChar() else if (ch.isLowerCase()) ch.uppercaseChar() else ch)
    }

    fun randomCase(text: String, random: java.util.Random = java.util.Random()): String = buildString(text.length) {
        for (ch in text) append(if (!ch.isLetter()) ch else if (random.nextBoolean()) ch.uppercaseChar() else ch.lowercaseChar())
    }

    // Blank Operations (one line at a time, without its line ending)

    fun trimLeading(line: String): String = line.trimStart(' ', '\t')
    fun trimTrailing(line: String): String = line.trimEnd(' ', '\t')
    fun trimBoth(line: String): String = line.trim(' ', '\t')

    /** TAB to Space: each tab becomes the spaces needed to reach the next tab stop. */
    fun tabsToSpaces(line: String, tabWidth: Int): String {
        if ('\t' !in line) return line
        val width = tabWidth.coerceAtLeast(1)
        return buildString(line.length + 8) {
            var column = 0
            for (ch in line) {
                if (ch == '\t') {
                    val spaces = width - column % width
                    repeat(spaces) { append(' ') }
                    column += spaces
                } else {
                    append(ch)
                    column++
                }
            }
        }
    }

    /**
     * Space to TAB: runs of blanks that reach a tab stop are rewritten with tabs.
     * With [leadingOnly], only the indentation at the start of the line is touched.
     */
    fun spacesToTabs(line: String, tabWidth: Int, leadingOnly: Boolean): String {
        val width = tabWidth.coerceAtLeast(1)
        val out = StringBuilder(line.length)
        var column = 0          // Column of the next character to place.
        var runStart = -1       // Column where the current run of blanks began, or -1.
        var inIndent = true

        fun flushRun() {
            if (runStart < 0) return
            var at = runStart
            // A single space is left alone unless it is indentation: "a b" should not turn into "a\tb".
            val single = column - runStart == 1 && !inIndent
            while (!single && (at / width + 1) * width <= column) {
                out.append('\t')
                at = (at / width + 1) * width
            }
            repeat(column - at) { out.append(' ') }
            runStart = -1
        }

        for (ch in line) {
            if ((ch == ' ' || ch == '\t') && (inIndent || !leadingOnly)) {
                if (runStart < 0) runStart = column
                column = if (ch == '\t') (column / width + 1) * width else column + 1
            } else {
                flushRun()
                inIndent = false
                out.append(ch)
                column = if (ch == '\t') (column / width + 1) * width else column + 1
            }
        }
        flushRun()
        return out.toString()
    }

    // Line Operations

    enum class Sort {
        LEXICOGRAPHIC, IGNORE_CASE, LOCALE, INTEGER, DECIMAL_COMMA, DECIMAL_DOT, LENGTH
    }

    private val NUMBER_PREFIX = Regex("""^\s*([+-]?\d+)""")
    private val DECIMAL_PREFIX = Regex("""^\s*([+-]?(?:\d+[.,]?\d*|[.,]\d+))""")

    /** Sorts lines the way the Edit > Line Operations > Sort Lines commands do. Sorting is stable. */
    fun sort(lines: List<String>, kind: Sort, descending: Boolean): List<String> {
        val sorted: List<String> = when (kind) {
            Sort.LEXICOGRAPHIC -> lines.sorted()
            Sort.IGNORE_CASE -> lines.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            Sort.LOCALE -> lines.sortedWith(Collator.getInstance())
            Sort.LENGTH -> lines.sortedBy { it.codePointCount(0, it.length) }
            Sort.INTEGER -> sortNumeric(lines) { line ->
                NUMBER_PREFIX.find(line)?.groupValues?.get(1)?.toBigIntegerOrNull()?.toBigDecimal()
            }
            Sort.DECIMAL_COMMA -> sortNumeric(lines) { line ->
                DECIMAL_PREFIX.find(line)?.groupValues?.get(1)?.replace(".", "")?.replace(',', '.')?.toBigDecimalOrNull()
            }
            Sort.DECIMAL_DOT -> sortNumeric(lines) { line ->
                DECIMAL_PREFIX.find(line)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()
            }
        }
        return if (descending) sorted.reversed() else sorted
    }

    /** Lines that do not start with a number come first, in their original order. */
    private fun sortNumeric(lines: List<String>, value: (String) -> java.math.BigDecimal?): List<String> {
        val keyed = lines.map { it to value(it) }
        return keyed.filter { it.second == null }.map { it.first } +
            keyed.filter { it.second != null }.sortedBy { it.second }.map { it.first }
    }

    enum class Leading { NONE, ZEROS, SPACES }

    /**
     * The Column Editor's number series: [count] numbers starting at [initial], growing by [increase] after
     * every [repeat] lines, written in [base] (10, 16, 8 or 2) and padded to the width of the widest one.
     */
    fun columnNumbers(count: Int, initial: Long, increase: Long, repeat: Int, base: Int, leading: Leading): List<String> {
        val step = repeat.coerceAtLeast(1)
        val digits = List(count) { index ->
            val value = initial + (index / step) * increase
            (value < 0) to java.lang.Long.toString(kotlin.math.abs(value), base)
        }
        val width = digits.maxOfOrNull { (negative, text) -> text.length + if (negative) 1 else 0 } ?: 0
        return digits.map { (negative, text) ->
            val sign = if (negative) "-" else ""
            when (leading) {
                Leading.NONE -> sign + text
                Leading.ZEROS -> sign + text.padStart(width - sign.length, '0')
                Leading.SPACES -> (sign + text).padStart(width)
            }
        }
    }

    fun removeConsecutiveDuplicates(lines: List<String>): List<String> =
        lines.filterIndexed { index, line -> index == 0 || line != lines[index - 1] }
}
