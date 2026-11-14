package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

/** What Compare may overlook. */
data class DiffOptions(val ignoreCase: Boolean = false, val ignoreSpaces: Boolean = false)

enum class DiffKind { SAME, ADDED, REMOVED, CHANGED }

/**
 * One row of two texts laid side by side: the line's number in the old and in the new text, -1 where that
 * side has no line for the row.
 */
data class DiffRow(val old: Int, val new: Int, val kind: DiffKind)

/** Line and character differences between two texts, by Myers' shortest-edit algorithm. */
object Diff {
    private const val SAME = 0
    private const val DELETE = 1
    private const val INSERT = 2

    // Above this many differences the texts are treated as unrelated in the part between their common ends.
    private const val MAX_EDITS = 2500
    private const val MAX_LINE = 2000
    private const val PAIRING_REACH = 40

    /** The steps that turn a into b: for each step whether an item is kept, dropped from a, or taken from b. */
    internal fun script(a: IntArray, b: IntArray): ByteArray {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val n = endA - start
        val m = endB - start
        val out = java.io.ByteArrayOutputStream(a.size + b.size)
        repeat(start) { out.write(SAME) }
        when {
            n == 0 -> repeat(m) { out.write(INSERT) }
            m == 0 -> repeat(n) { out.write(DELETE) }
            else -> out.write(middle(a, b, start, n, m))
        }
        repeat(a.size - endA) { out.write(SAME) }
        return out.toByteArray()
    }

    private fun middle(a: IntArray, b: IntArray, offset: Int, n: Int, m: Int): ByteArray {
        val limit = minOf(n + m, MAX_EDITS)
        val trace = ArrayList<IntArray>()
        var v = IntArray(2 * limit + 3)
        val zero = limit + 1
        var found = -1
        search@ for (d in 0..limit) {
            trace += v.copyOfRange(zero - d - 1, zero + d + 2)
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[zero + k - 1] < v[zero + k + 1])) v[zero + k + 1] else v[zero + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[offset + x] == b[offset + y]) {
                    x++
                    y++
                }
                v[zero + k] = x
                if (x >= n && y >= m) {
                    found = d
                    break@search
                }
                k += 2
            }
        }
        if (found < 0) {
            // Too different to be worth lining up: all of the one, then all of the other.
            return ByteArray(n + m) { if (it < n) DELETE.toByte() else INSERT.toByte() }
        }
        // Walk back from the end through the saved rounds.
        val steps = ByteArray(n + m)
        var at = steps.size
        var x = n
        var y = m
        for (d in found downTo 1) {
            val saved = trace[d]
            // saved holds v[zero - d - 1 .. zero + d + 1] as it was before round d.
            fun before(k: Int) = saved[k + d + 1]
            val k = x - y
            val down = k == -d || (k != d && before(k - 1) < before(k + 1))
            val previousK = if (down) k + 1 else k - 1
            val previousX = before(previousK)
            val previousY = previousX - previousK
            while (x > previousX + (if (down) 0 else 1) && y > previousY + (if (down) 1 else 0)) {
                steps[--at] = SAME.toByte()
                x--
                y--
            }
            steps[--at] = if (down) INSERT.toByte() else DELETE.toByte()
            x = previousX
            y = previousY
        }
        while (x > 0 && y > 0) {
            steps[--at] = SAME.toByte()
            x--
            y--
        }
        return steps.copyOfRange(at, steps.size)
    }

    private fun key(line: String, options: DiffOptions): String {
        var text = line
        if (options.ignoreSpaces) text = text.filterNot { it == ' ' || it == '\t' }
        if (options.ignoreCase) text = text.lowercase()
        return text
    }

    /**
     * Lines of the old and the new text side by side. Lines that were replaced by lines much like them are
     * paired as CHANGED; the rest of a replaced block is REMOVED and ADDED.
     */
    fun rows(old: List<String>, new: List<String>, options: DiffOptions = DiffOptions()): List<DiffRow> {
        val ids = HashMap<String, Int>()
        fun id(line: String) = ids.getOrPut(key(line, options)) { ids.size }
        val a = IntArray(old.size) { id(old[it]) }
        val b = IntArray(new.size) { id(new[it]) }
        val steps = script(a, b)
        val rows = ArrayList<DiffRow>(steps.size)
        var i = 0
        var j = 0
        var s = 0
        while (s < steps.size) {
            if (steps[s].toInt() == SAME) {
                rows += DiffRow(i++, j++, DiffKind.SAME)
                s++
                continue
            }
            // A run of removals and additions between two unchanged lines.
            var removed = 0
            var added = 0
            while (s < steps.size && steps[s].toInt() != SAME) {
                if (steps[s].toInt() == DELETE) removed++ else added++
                s++
            }
            // Each removed line looks, in order, for the next added line that is a version of it.
            var next = 0
            for (p in 0 until removed) {
                val oldKey = key(old[i + p], options)
                val match = (next until minOf(added, next + PAIRING_REACH)).firstOrNull { alike(oldKey, key(new[j + it], options)) }
                if (match == null) {
                    rows += DiffRow(i + p, -1, DiffKind.REMOVED)
                } else {
                    for (t in next until match) rows += DiffRow(-1, j + t, DiffKind.ADDED)
                    rows += DiffRow(i + p, j + match, DiffKind.CHANGED)
                    next = match + 1
                }
            }
            for (t in next until added) rows += DiffRow(-1, j + t, DiffKind.ADDED)
            i += removed
            j += added
        }
        return rows
    }

    /**
     * Two lines are versions of one another when a fair share of the longer one is found in the shorter, or
     * when nearly all of the shorter one is still there in the longer.
     */
    private fun alike(a: String, b: String): Boolean {
        val longer = maxOf(a.length, b.length)
        val shorter = minOf(a.length, b.length)
        if (longer == 0) return true
        if (longer > MAX_LINE) return a.take(40) == b.take(40)
        val (da, _) = changedParts(a, b)
        val kept = a.length - da.sumOf { it.last - it.first + 1 }
        return kept * 100 >= longer * 40 || (shorter >= 3 && kept * 100 >= shorter * 80)
    }

    /** The stretches of each line that the other does not have, as character ranges. */
    fun changedParts(old: String, new: String): Pair<List<IntRange>, List<IntRange>> {
        if (old.length > MAX_LINE || new.length > MAX_LINE) {
            return (if (old.isEmpty()) emptyList() else listOf(old.indices)) to (if (new.isEmpty()) emptyList() else listOf(new.indices))
        }
        val steps = script(IntArray(old.length) { old[it].code }, IntArray(new.length) { new[it].code })
        val inOld = ArrayList<IntRange>()
        val inNew = ArrayList<IntRange>()
        fun extend(ranges: MutableList<IntRange>, at: Int) {
            val last = ranges.lastOrNull()
            if (last != null && last.last == at - 1) ranges[ranges.size - 1] = last.first..at else ranges += at..at
        }
        var i = 0
        var j = 0
        for (step in steps) {
            when (step.toInt()) {
                SAME -> { i++; j++ }
                DELETE -> extend(inOld, i++)
                else -> extend(inNew, j++)
            }
        }
        return inOld to inNew
    }

    /** How many separate places differ: runs of rows that are not SAME. */
    fun differences(rows: List<DiffRow>): Int {
        var count = 0
        var inside = false
        for (row in rows) {
            val differs = row.kind != DiffKind.SAME
            if (differs && !inside) count++
            inside = differs
        }
        return count
    }
}

/**
 * Shows the result of a comparison in two Scintilla views, as Notepad++'s Compare plugin does: lines only in
 * one text are tinted, changed lines are tinted with the changed characters marked, and blank rows are put in
 * where the other text has lines this one lacks, so that the two stay level when scrolled together.
 */
object Compare {
    const val ADDED = 10
    const val REMOVED = 11
    const val CHANGED = 12

    /** On a line that has blank rows after it standing in for lines of the other text. It shows nothing itself. */
    const val GAP = 13
    const val INDICATOR = 20
    const val GAP_STYLE = 250
    private const val MASK = ((1 shl ADDED) or (1 shl REMOVED) or (1 shl CHANGED) or (1 shl GAP)).toLong()

    /** Colours and marks belong to a view, so this is for every view that shows a compared document. */
    fun setup(sci: SciApi, dark: Boolean) {
        fun colour(light: Int, darker: Int) = ThemeApplier.sciColour(if (dark) darker else light)
        for ((marker, tint) in listOf(
            ADDED to colour(0xD7F5CF, 0x1F4A22), REMOVED to colour(0xFBD9D9, 0x5A2323), CHANGED to colour(0xFFF1B8, 0x4F4418),
        )) {
            sci.send(Sci.SCI_MARKERDEFINE, marker.toLong(), Sci.SC_MARK_BACKGROUND.toLong())
            sci.send(Sci.SCI_MARKERSETBACK, marker.toLong(), tint)
        }
        sci.send(Sci.SCI_MARKERDEFINE, GAP.toLong(), Sci.SC_MARK_EMPTY.toLong())
        sci.send(Sci.SCI_INDICSETSTYLE, INDICATOR.toLong(), Sci.INDIC_STRAIGHTBOX.toLong())
        sci.send(Sci.SCI_INDICSETFORE, INDICATOR.toLong(), colour(0xE08A00, 0xFFC850))
        sci.send(Sci.SCI_INDICSETALPHA, INDICATOR.toLong(), 90)
        sci.send(Sci.SCI_INDICSETOUTLINEALPHA, INDICATOR.toLong(), 160)
        sci.send(Sci.SCI_INDICSETUNDER, INDICATOR.toLong(), 1)
        sci.send(Sci.SCI_STYLESETBACK, GAP_STYLE.toLong(), colour(0xE9E9E9, 0x3A3A3A))
        sci.send(Sci.SCI_STYLESETFORE, GAP_STYLE.toLong(), colour(0xE9E9E9, 0x3A3A3A))
        sci.send(Sci.SCI_ANNOTATIONSETVISIBLE, Sci.ANNOTATION_STANDARD.toLong())
    }

    /** The lines of a document without their line ends. */
    fun lines(sci: SciApi): List<String> {
        val text = String(sci.range(0, sci.send(Sci.SCI_GETLENGTH)), Charsets.UTF_8)
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        // A last line break does not start a line of its own as far as comparing goes.
        return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    fun clear(sci: SciApi) {
        for (marker in listOf(ADDED, REMOVED, CHANGED, GAP)) sci.send(Sci.SCI_MARKERDELETEALL, marker.toLong())
        sci.send(Sci.SCI_ANNOTATIONCLEARALL)
        sci.send(Sci.SCI_SETINDICATORCURRENT, INDICATOR.toLong())
        sci.send(Sci.SCI_INDICATORCLEARRANGE, 0, sci.send(Sci.SCI_GETLENGTH))
        sci.send(Sci.SCI_SHOWLINES, 0, sci.send(Sci.SCI_GETLINECOUNT) - 1)
    }

    private fun mark(sci: SciApi, line: Int, text: String, parts: List<IntRange>) {
        if (parts.isEmpty()) return
        val start = sci.send(Sci.SCI_POSITIONFROMLINE, line.toLong())
        sci.send(Sci.SCI_SETINDICATORCURRENT, INDICATOR.toLong())
        for (part in parts) {
            val from = text.substring(0, part.first).toByteArray(Charsets.UTF_8).size
            val length = text.substring(part.first, part.last + 1).toByteArray(Charsets.UTF_8).size
            sci.send(Sci.SCI_INDICATORFILLRANGE, start + from, length.toLong())
        }
    }

    private fun gaps(sci: SciApi, owed: Map<Int, Int>) {
        for ((line, rows) in owed) {
            sci.sendText(Sci.SCI_ANNOTATIONSETTEXT, line.toLong(), " \n".repeat(rows - 1) + " ")
            sci.send(Sci.SCI_ANNOTATIONSETSTYLE, line.toLong(), GAP_STYLE.toLong())
            sci.send(Sci.SCI_MARKERADD, line.toLong(), GAP.toLong())
        }
    }

    /** Marks both documents. [oldLines] and [newLines] are what [rows] was worked out from. */
    fun show(old: SciApi, new: SciApi, rows: List<DiffRow>, oldLines: List<String>, newLines: List<String>) {
        clear(old)
        clear(new)
        var lastOld = 0
        var lastNew = 0
        // Blank rows each side is owed, by the line they come after. Scintilla can only put them below a line,
        // so rows owed before the very first line go below it instead.
        val owedOld = HashMap<Int, Int>()
        val owedNew = HashMap<Int, Int>()
        for (row in rows) {
            if (row.old >= 0) lastOld = row.old
            if (row.new >= 0) lastNew = row.new
            when (row.kind) {
                DiffKind.SAME -> {}
                DiffKind.CHANGED -> {
                    old.send(Sci.SCI_MARKERADD, row.old.toLong(), CHANGED.toLong())
                    new.send(Sci.SCI_MARKERADD, row.new.toLong(), CHANGED.toLong())
                    val (inOld, inNew) = Diff.changedParts(oldLines[row.old], newLines[row.new])
                    mark(old, row.old, oldLines[row.old], inOld)
                    mark(new, row.new, newLines[row.new], inNew)
                }
                DiffKind.REMOVED -> {
                    old.send(Sci.SCI_MARKERADD, row.old.toLong(), REMOVED.toLong())
                    owedNew[lastNew] = (owedNew[lastNew] ?: 0) + 1
                }
                DiffKind.ADDED -> {
                    new.send(Sci.SCI_MARKERADD, row.new.toLong(), ADDED.toLong())
                    owedOld[lastOld] = (owedOld[lastOld] ?: 0) + 1
                }
            }
        }
        gaps(old, owedOld)
        gaps(new, owedNew)
    }

    private fun differs(sci: SciApi, line: Long) = line >= 0 && sci.send(Sci.SCI_MARKERGET, line) and MASK != 0L

    /**
     * Where the differences are in the new text: for each run of rows that differ, the line it starts at, or
     * the line before it where the new text has nothing there.
     */
    fun places(rows: List<DiffRow>): List<Int> {
        val places = ArrayList<Int>()
        var lastNew = 0
        var inside = false
        var pending = false
        for (row in rows) {
            val differs = row.kind != DiffKind.SAME
            if (differs && !inside) pending = true
            if (pending && differs) {
                // The first row of the run that has a line on the new side says where; failing that, the line before.
                if (row.new >= 0) {
                    places += row.new
                    pending = false
                }
            } else if (pending) {
                places += lastNew
                pending = false
            }
            if (row.new >= 0) lastNew = row.new
            inside = differs
        }
        if (pending) places += lastNew
        return places.distinct()
    }

    /** Moves the caret to the next or the previous place that differs. Returns false when there is none that way. */
    fun go(sci: SciApi, places: List<Int>, forward: Boolean): Boolean {
        val line = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS)).toInt()
        val target = (if (forward) places.firstOrNull { it > line } else places.lastOrNull { it < line }) ?: return false
        land(sci, target.toLong())
        return true
    }

    fun first(sci: SciApi, places: List<Int>): Boolean = places.firstOrNull()?.let { land(sci, it.toLong()) } != null

    fun last(sci: SciApi, places: List<Int>): Boolean = places.lastOrNull()?.let { land(sci, it.toLong()) } != null

    private fun land(sci: SciApi, line: Long) {
        val target = line.coerceIn(0, sci.send(Sci.SCI_GETLINECOUNT) - 1)
        sci.send(Sci.SCI_ENSUREVISIBLE, target)
        sci.send(Sci.SCI_GOTOLINE, target)
        // A few lines of what comes before stay in view.
        sci.send(Sci.SCI_SETFIRSTVISIBLELINE, (sci.send(Sci.SCI_VISIBLEFROMDOCLINE, target) - 3).coerceAtLeast(0))
    }

    /** Show Only Differences: hides the lines that are the same in both texts, but for one on each side of a difference. */
    fun hideSame(sci: SciApi) {
        val count = sci.send(Sci.SCI_GETLINECOUNT)
        var run = -1L
        fun hide(from: Long, to: Long) {
            // The first line of a document cannot be hidden, and a line of context is left around differences.
            val first = maxOf(from + 1, 1)
            val last = if (to == count - 1) to else to - 1
            if (last >= first) sci.send(Sci.SCI_HIDELINES, first, last)
        }
        for (line in 0 until count) {
            if (differs(sci, line)) {
                if (run >= 0) hide(run, line - 1)
                run = -1
            } else if (run < 0) {
                run = line
            }
        }
        if (run >= 0) hide(run, count - 1)
    }
}
