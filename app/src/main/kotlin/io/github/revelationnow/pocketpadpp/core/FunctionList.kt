package io.github.revelationnow.pocketpadpp.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.xml.parsers.SAXParserFactory

/** One line of the Function List: a function, and the class or section it belongs to (empty for none). */
data class FunctionEntry(val name: String, val owner: String, val position: Int)

/** Thrown when a file takes too long to go through, which a pattern can do on text it was not written for. */
class FunctionListTimeout : RuntimeException()

/**
 * Notepad++ writes its function list rules for Boost.Regex. This turns such an expression into one that Java
 * and Android understand, for the parts where the two differ. What cannot be expressed (recursion,
 * conditionals) fails to compile, and the rule it belongs to is left out.
 */
class BoostPattern private constructor(private val pattern: Pattern, private val keeps: List<String>) {

    /** Start and end of the first match within [begin, end), or null. */
    fun find(text: CharSequence, begin: Int, end: Int): IntArray? {
        if (begin > end) return null
        // The matcher sees nothing after `end`, as Boost does with an end iterator, but can look back before
        // `begin`, so that ^ and lookbehind judge the real surroundings.
        val matcher: Matcher = pattern.matcher(Slice(text, end))
        matcher.useAnchoringBounds(false)
        matcher.useTransparentBounds(true)
        var from = begin
        while (from <= end) {
            matcher.region(from, end)
            if (!matcher.find()) return null
            var start = matcher.start()
            // \K: what was matched before it is not part of the result.
            for (keep in keeps) {
                val at = matcher.end(keep)
                if (at > start) start = at
            }
            if (matcher.end() > start) return intArrayOf(start, matcher.end())
            // Like Notepad++'s search, a match of nothing does not count; the search goes on one character later.
            from = start + 1
        }
        return null
    }

    private class Slice(private val text: CharSequence, override val length: Int) : CharSequence {
        override fun get(index: Int): Char = text[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
        override fun toString(): String = text.subSequence(0, length).toString()
    }

    companion object {
        private val POSIX = mapOf(
            "alpha" to "\\p{Alpha}", "digit" to "\\d", "alnum" to "\\p{Alnum}", "upper" to "\\p{Upper}", "lower" to "\\p{Lower}",
            "space" to "\\s", "blank" to "\\h", "punct" to "\\p{Punct}", "xdigit" to "\\p{XDigit}", "word" to "\\w",
            "cntrl" to "\\p{Cntrl}", "print" to "\\p{Print}", "graph" to "\\p{Graph}",
        )

        fun compile(expression: String): BoostPattern? {
            val keeps = ArrayList<String>()
            val translated = try {
                translate(expression, keeps)
            } catch (e: Exception) {
                return null
            }
            return try {
                // Boost's ^ and $ work per line; Notepad++ asks for a dot that matches line breaks and ignores case.
                BoostPattern(Pattern.compile(translated, Pattern.MULTILINE or Pattern.DOTALL or Pattern.CASE_INSENSITIVE), keeps)
            } catch (e: Exception) {
                null
            }
        }

        internal fun translate(expression: String, keeps: MutableList<String>): String = Rewriter(expression, keeps).rewrite()
    }

    /**
     * Rewrites one expression. Beyond spelling differences this covers three things Java has no word for:
     * \K (an empty group marks the spot), calls of a group as a subroutine (the group's text is copied in,
     * a few levels deep where it calls itself) and conditions on whether a group matched (an empty group
     * placed after it is referred back to).
     */
    private class Rewriter(source: String, private val keeps: MutableList<String>) {
        private enum class Kind { CAPTURE, PLAIN, FLAGS, COMMENT, DEFINE, CALL, IF_LOOK, IF_GROUP }

        private class Group(val open: Int, val kind: Kind, val bodyStart: Int, val name: String?, val number: Int, val header: String, val ref: String) {
            var close = -1
        }

        private val e: String = if (FREE_SPACING.containsMatchIn(source)) strip(source) else source
        private val groups = HashMap<Int, Group>()
        private val byName = HashMap<String, Group>()
        private val byNumber = HashMap<Int, Group>()
        private val needMarker = HashSet<Group>()
        private var count = 0

        private fun classEnd(start: Int): Int {
            var j = start + 1
            if (j < e.length && e[j] == '^') j++
            if (j < e.length && e[j] == ']') j++
            while (j < e.length) {
                when {
                    e[j] == '\\' -> j += 2
                    e.startsWith("[:", j) && e.indexOf(":]", j + 2) > 0 -> j = e.indexOf(":]", j + 2) + 2
                    e[j] == ']' -> return j
                    else -> j++
                }
            }
            throw IllegalArgumentException("unfinished character class")
        }

        /** Free-spacing mode: blanks and # comments outside character classes are not part of the expression. */
        private fun strip(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < text.length -> {
                        out.append(c).append(text[i + 1])
                        i += 2
                    }
                    c == '[' -> {
                        // The class is copied whole; its end is found the same way as later on.
                        var j = i + 1
                        if (j < text.length && text[j] == '^') j++
                        if (j < text.length && text[j] == ']') j++
                        while (j < text.length && text[j] != ']') {
                            j = when {
                                text[j] == '\\' -> j + 2
                                text.startsWith("[:", j) && text.indexOf(":]", j + 2) > 0 -> text.indexOf(":]", j + 2) + 2
                                else -> j + 1
                            }
                        }
                        out.append(text, i, minOf(j + 1, text.length))
                        i = j + 1
                    }
                    c == '#' -> {
                        val end = text.indexOf('\n', i)
                        i = if (end < 0) text.length else end + 1
                    }
                    c.isWhitespace() -> i++
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }

        private fun scan() {
            val stack = ArrayList<Group>()
            var i = 0
            while (i < e.length) {
                val c = e[i]
                when {
                    c == '\\' -> i += 2
                    c == '[' -> i = classEnd(i) + 1
                    c == '(' -> {
                        val group = open(i)
                        groups[i] = group
                        if (group.kind == Kind.CAPTURE) {
                            byNumber[group.number] = group
                            group.name?.let { byName[it] = group }
                        }
                        when (group.kind) {
                            Kind.CALL, Kind.FLAGS -> {
                                group.close = group.bodyStart - 1
                                i = group.bodyStart
                            }
                            Kind.COMMENT -> {
                                group.close = e.indexOf(')', i).also { if (it < 0) throw IllegalArgumentException("unfinished comment") }
                                i = group.close + 1
                            }
                            else -> {
                                stack += group
                                i = group.bodyStart
                            }
                        }
                    }
                    c == ')' -> {
                        if (stack.isEmpty()) throw IllegalArgumentException("unbalanced )")
                        stack.removeAt(stack.size - 1).close = i
                        i++
                    }
                    else -> i++
                }
            }
            if (stack.isNotEmpty()) throw IllegalArgumentException("unbalanced (")
        }

        private fun open(i: Int): Group {
            fun named(start: Int, close: Char): Group {
                val end = e.indexOf(close, start)
                if (end < 0) throw IllegalArgumentException("unfinished group name")
                return Group(i, Kind.CAPTURE, end + 1, e.substring(start, end), ++count, "", "")
            }
            if (!e.startsWith("(?", i)) return Group(i, Kind.CAPTURE, i + 1, null, ++count, "", "")
            val rest = e.substring(i + 2, minOf(e.length, i + 40))
            return when {
                rest.startsWith("#") -> Group(i, Kind.COMMENT, i, null, 0, "", "")
                rest.startsWith("'") -> named(i + 3, '\'')
                rest.startsWith("P<") -> named(i + 4, '>')
                rest.startsWith("<") && !rest.startsWith("<=") && !rest.startsWith("<!") -> named(i + 3, '>')
                rest.startsWith("(DEFINE)") -> Group(i, Kind.DEFINE, i + 10, null, 0, "", "")
                rest.startsWith("(?") -> Group(i, Kind.IF_LOOK, i + 2, null, 0, "", "")
                rest.startsWith("(") -> {
                    val end = e.indexOf(')', i + 3)
                    if (end < 0) throw IllegalArgumentException("unfinished condition")
                    Group(i, Kind.IF_GROUP, end + 1, null, 0, "", e.substring(i + 3, end).trim('\'', '<', '>'))
                }
                else -> {
                    CALL.find(rest)?.takeIf { it.range.first == 0 }?.let { call ->
                        return Group(i, Kind.CALL, i + 2 + call.value.length, null, 0, "", call.groupValues[1].removePrefix("&").removePrefix("P>"))
                    }
                    val flags = FLAGS.find(rest)?.takeIf { it.range.first == 0 }
                    when {
                        flags != null && flags.value.endsWith(")") -> Group(i, Kind.FLAGS, i + 2 + flags.value.length, null, 0, "(?" + flags.value, "")
                        flags != null -> Group(i, Kind.PLAIN, i + 2 + flags.value.length, null, 0, "(?" + flags.value, "")
                        rest.startsWith("<=") || rest.startsWith("<!") -> Group(i, Kind.PLAIN, i + 4, null, 0, "(?" + rest.substring(0, 2), "")
                        rest.startsWith(":") || rest.startsWith("=") || rest.startsWith("!") || rest.startsWith(">") ->
                            Group(i, Kind.PLAIN, i + 3, null, 0, "(?" + rest[0], "")
                        else -> throw IllegalArgumentException("unknown group")
                    }
                }
            }
        }

        private fun target(ref: String): Group? = ref.toIntOrNull()?.let { byNumber[it] } ?: byName[ref]

        private fun id(group: Group): String = group.name?.let { groupName(it) } ?: "n${group.number}"

        private fun marker(group: Group): String = "m${group.number}"

        fun rewrite(): String {
            scan()
            for (group in groups.values) if (group.kind == Kind.IF_GROUP) target(group.ref)?.let { needMarker += it }
            return emit(0, e.length, inlined = false, depth = emptyMap())
        }

        /** Where the | between the two branches of a condition is, or -1. */
        private fun bar(from: Int, to: Int): Int {
            var i = from
            while (i < to) {
                val group = groups[i]
                when {
                    group != null -> i = group.close + 1
                    e[i] == '\\' -> i += 2
                    e[i] == '[' -> i = classEnd(i) + 1
                    e[i] == '|' -> return i
                    else -> i++
                }
            }
            return -1
        }

        private fun emit(from: Int, to: Int, inlined: Boolean, depth: Map<String, Int>): String {
            val out = StringBuilder()
            var i = from
            while (i < to) {
                val c = e[i]
                val group = groups[i]
                if (group != null) {
                    fun body() = emit(group.bodyStart, group.close, inlined, depth)
                    when (group.kind) {
                        Kind.COMMENT, Kind.DEFINE -> {}
                        Kind.FLAGS -> out.append(group.header)
                        Kind.PLAIN -> out.append(group.header).append(body()).append(')')
                        Kind.CAPTURE -> {
                            if (inlined) out.append("(?:").append(body()).append(')')
                            else if (group in needMarker) {
                                // Wrapped, so that a quantifier after the group covers its marker too.
                                out.append("(?:(?<").append(id(group)).append('>').append(body()).append(")(?<").append(marker(group)).append(">))")
                            } else {
                                out.append("(?<").append(id(group)).append('>').append(body()).append(')')
                            }
                        }
                        Kind.CALL -> {
                            val whole = group.ref == "R" || group.ref == "0"
                            val called = if (whole) null else target(group.ref) ?: throw IllegalArgumentException("unknown subroutine ${group.ref}")
                            val level = depth[group.ref] ?: 0
                            if (level >= MAX_DEPTH) out.append("(?!)") else {
                                val deeper = depth + (group.ref to level + 1)
                                out.append("(?:")
                                out.append(if (called == null) emit(0, e.length, true, deeper) else emit(called.bodyStart, called.close, true, deeper))
                                out.append(')')
                            }
                        }
                        Kind.IF_LOOK -> {
                            val test = groups[group.bodyStart] ?: throw IllegalArgumentException("bad condition")
                            val split = bar(test.close + 1, group.close)
                            val yes = emit(test.close + 1, if (split < 0) group.close else split, inlined, depth)
                            val no = if (split < 0) "" else emit(split + 1, group.close, inlined, depth)
                            val inner = emit(test.bodyStart, test.close, inlined, depth)
                            val opposite = when (test.header) {
                                "(?=" -> "(?!"
                                "(?!" -> "(?="
                                "(?<=" -> "(?<!"
                                "(?<!" -> "(?<="
                                else -> throw IllegalArgumentException("bad condition")
                            }
                            out.append("(?:").append(test.header).append(inner).append(')').append(yes)
                                .append('|').append(opposite).append(inner).append(')').append(no).append(')')
                        }
                        Kind.IF_GROUP -> {
                            val split = bar(group.bodyStart, group.close)
                            val yes = emit(group.bodyStart, if (split < 0) group.close else split, inlined, depth)
                            val no = if (split < 0) "" else emit(split + 1, group.close, inlined, depth)
                            val tested = target(group.ref)
                            if (tested == null || inlined) out.append("(?:").append(yes).append('|').append(no).append(')')
                            else out.append("(?:\\k<").append(marker(tested)).append('>').append(yes)
                                .append("|(?!\\k<").append(marker(tested)).append(">)").append(no).append(')')
                        }
                    }
                    i = group.close + 1
                    continue
                }
                when {
                    c == '\\' && i + 1 < e.length -> {
                        val next = e[i + 1]
                        when {
                            next == 'K' -> {
                                val name = "keep${keeps.size}"
                                keeps += name
                                out.append("(?<").append(name).append(">)")
                                i += 2
                            }
                            next == '<' -> { out.append("\\b(?=\\w)"); i += 2 }
                            next == '>' -> { out.append("\\b(?<=\\w)"); i += 2 }
                            next == 'l' -> { out.append("\\p{Lower}"); i += 2 }
                            next == 'u' && !HEX4.matchesAt(e, i + 2) -> { out.append("\\p{Upper}"); i += 2 }
                            next in '1'..'9' -> {
                                var end = i + 2
                                while (end < e.length && e[end].isDigit() && e.substring(i + 1, end + 1).toInt() <= count) end++
                                val referred = byNumber[e.substring(i + 1, end).toInt()]
                                if (referred == null || inlined) out.append("(?!)") else out.append("\\k<").append(id(referred)).append('>')
                                i = end
                            }
                            (next == 'k' || next == 'g') && i + 2 < e.length && e[i + 2] in "<'{" -> {
                                val close = when (e[i + 2]) { '<' -> '>'; '\'' -> '\''; else -> '}' }
                                val end = e.indexOf(close, i + 3)
                                if (end < 0) throw IllegalArgumentException("unfinished group reference")
                                val referred = target(e.substring(i + 3, end))
                                if (referred == null || inlined) out.append("(?!)") else out.append("\\k<").append(id(referred)).append('>')
                                i = end + 1
                            }
                            else -> { out.append(c).append(next); i += 2 }
                        }
                    }
                    c == '[' -> {
                        val end = classEnd(i)
                        charClass(i, end, out)
                        i = end + 1
                    }
                    c == '{' -> {
                        val quantifier = QUANTIFIER.matchAt(e, i)
                        if (quantifier != null && out.isNotEmpty()) {
                            out.append(quantifier.value)
                            i += quantifier.value.length
                        } else {
                            out.append("\\{")
                            i++
                        }
                    }
                    c == '}' -> { out.append("\\}"); i++ }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        /** A character class: nested brackets are plain characters in Boost, and classes have POSIX names. */
        private fun charClass(start: Int, end: Int, out: StringBuilder) {
            out.append('[')
            var i = start + 1
            if (e[i] == '^') { out.append('^'); i++ }
            if (e[i] == ']' && i < end) { out.append("\\]"); i++ }
            while (i < end) {
                val c = e[i]
                when {
                    c == '\\' && i + 1 < end -> {
                        when (e[i + 1]) {
                            'l' -> out.append("\\p{Lower}")
                            'u' -> if (HEX4.matchesAt(e, i + 2)) out.append("\\u") else out.append("\\p{Upper}")
                            else -> out.append(c).append(e[i + 1])
                        }
                        i += 2
                    }
                    c == '[' && e.startsWith("[:", i) && e.indexOf(":]", i + 2) in 0 until end -> {
                        val close = e.indexOf(":]", i + 2)
                        out.append(POSIX[e.substring(i + 2, close)] ?: throw IllegalArgumentException("unknown class"))
                        i = close + 2
                    }
                    c == '[' -> { out.append("\\["); i++ }
                    // Free-spacing mode drops blanks and # inside classes here, unlike Boost.
                    c == ' ' -> { out.append("\\x20"); i++ }
                    c == '\t' -> { out.append("\\t"); i++ }
                    c == '\n' -> { out.append("\\n"); i++ }
                    c == '\r' -> { out.append("\\r"); i++ }
                    c == '#' -> { out.append("\\#"); i++ }
                    c == '&' -> { out.append("\\&"); i++ }
                    // Android's engine reads more into these inside a class than Java's does.
                    c == '{' || c == '}' || c == '$' -> { out.append('\\').append(c); i++ }
                    else -> { out.append(c); i++ }
                }
            }
            out.append(']')
        }

        companion object {
            private const val MAX_DEPTH = 2
            private val FREE_SPACING = Regex("\\(\\?[a-wyz]*x[a-z]*(?:-[a-wyz]*)?[):]")
            private val CALL = Regex("(&\\w+|P>\\w+|R|[+-]?\\d+)\\)")
            private val FLAGS = Regex("[a-zA-Z]*(?:-[a-zA-Z]*)?[):]")
            private val QUANTIFIER = Regex("\\{\\d+(?:,\\d*)?\\}")
            private val HEX4 = Regex("[0-9A-Fa-f]{4}")

            // Group names may only hold letters and digits here.
            private fun groupName(name: String): String {
                val plain = name.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                return "g" + plain + (if (plain.length == name.length) "" else "x" + (name.hashCode() and 0xFFFF).toString(16))
            }
        }
    }
}

/** The rules for one language, read from one of Notepad++'s functionList files. */
class FunctionParser private constructor(
    val displayName: String,
    private val comment: BoostPattern?,
    private val zone: ZoneRule?,
    private val unit: UnitRule?,
    /** Expressions that could not be used here; the rules they belong to were left out. */
    val skipped: Int,
) {
    internal class UnitRule(val main: BoostPattern, val names: List<BoostPattern>, val owners: List<BoostPattern>)

    internal class ZoneRule(
        val main: BoostPattern, val open: BoostPattern?, val close: BoostPattern?, val openOrClose: BoostPattern?,
        val owners: List<BoostPattern>, val function: UnitRule,
    )

    private class Deadline(private val text: CharSequence, private val until: Long) : CharSequence {
        private var calls = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char {
            if (++calls and 0xFFFF == 0 && System.nanoTime() > until) throw FunctionListTimeout()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
        override fun toString(): String = text.toString()
    }

    /**
     * Finds the functions of a text, as Notepad++'s FunctionParser classes do: comments are set aside, class
     * bodies are searched with the class rule, and what lies outside them with the plain function rule.
     */
    fun parse(source: CharSequence, timeoutMs: Long = 4000): List<FunctionEntry> {
        val text: CharSequence = Deadline(source, System.nanoTime() + timeoutMs * 1_000_000)
        val found = ArrayList<FunctionEntry>()
        val end = text.length
        val comments = zones(text, comment, 0, end)
        when {
            zone != null && unit != null -> {
                val scanned = ArrayList<IntArray>()
                classParse(text, found, scanned, comments, 0, end)
                for (z in scanned.toList()) classParse(text, found, ArrayList(), comments, z[0], z[1])
                for (z in invert(scanned, 0, end)) funcParse(text, found, unit, z[0], z[1], "", comments)
            }
            zone != null -> for (z in invert(comments, 0, end)) classParse(text, found, ArrayList(), comments, z[0], z[1])
            unit != null -> for (z in invert(comments, 0, end)) funcParse(text, found, unit, z[0], z[1], "", null)
        }
        return found
    }

    private fun zones(text: CharSequence, pattern: BoostPattern?, from: Int, end: Int): List<IntArray> {
        val result = ArrayList<IntArray>()
        if (pattern == null) return result
        var begin = from
        while (begin < end) {
            val match = pattern.find(text, begin, end) ?: break
            result += match
            if (match[1] == end) break
            begin = if (match[1] > begin) match[1] else begin + 1
        }
        return result
    }

    private fun inZones(position: Int, zones: List<IntArray>): Boolean = position >= 0 && zones.any { position >= it[0] && position < it[1] }

    // The one-off steps at the edges are Notepad++'s own.
    private fun invert(zones: List<IntArray>, begin: Int, end: Int): List<IntArray> {
        if (zones.isEmpty()) return listOf(intArrayOf(begin, end))
        val result = ArrayList<IntArray>()
        if (begin < zones[0][0]) result += intArrayOf(begin, zones[0][0] - 1)
        for (i in 0 until zones.size - 1) {
            val newBegin = zones[i][1] + 1
            val newEnd = zones[i + 1][0] - 1
            if (newBegin < newEnd) result += intArrayOf(newBegin, newEnd)
        }
        val lastBegin = zones.last()[1] + 1
        if (lastBegin < end) result += intArrayOf(lastBegin, end)
        return result
    }

    /** Applies the expressions one inside the other; gives the text of the last one and where it starts. */
    private fun subLevel(text: CharSequence, begin: Int, end: Int, expressions: List<BoostPattern>): Pair<String, Int> {
        if (begin >= end || expressions.isEmpty()) return "" to -1
        var from = begin
        var to = end
        for (expression in expressions) {
            val match = expression.find(text, from, to) ?: return "" to -1
            from = match[0]
            to = match[1]
        }
        return text.subSequence(from, minOf(to, from + 1023)).toString() to from
    }

    private fun funcParse(
        text: CharSequence, found: MutableList<FunctionEntry>, rule: UnitRule, from: Int, end: Int, owner: String, comments: List<IntArray>?,
    ) {
        var begin = from
        while (begin < end) {
            val match = rule.main.find(text, begin, end) ?: break
            if (match[1] == end) break
            var name = ""
            var position = -1
            var ownerName = ""
            var ownerPosition = -1
            if (rule.names.isEmpty() && rule.owners.isEmpty()) {
                name = text.subSequence(match[0], minOf(match[1], match[0] + 1023)).toString()
                position = match[0]
            } else {
                if (rule.names.isNotEmpty()) subLevel(text, match[0], match[1], rule.names).let { name = it.first; position = it.second }
                if (owner.isNotEmpty()) ownerName = owner
                else if (rule.owners.isNotEmpty()) subLevel(text, match[0], match[1], rule.owners).let { ownerName = it.first; ownerPosition = it.second }
            }
            if (position != -1 || ownerPosition != -1) {
                if (comments == null || (!inZones(position, comments) && !inZones(ownerPosition, comments))) {
                    found += FunctionEntry(name, ownerName, if (position != -1) position else ownerPosition)
                }
            }
            begin = if (match[1] > begin) match[1] else begin + 1
        }
    }

    private fun bodyEnd(text: CharSequence, begin: Int, rule: ZoneRule, comments: List<IntArray>): Int {
        val length = text.length
        if (begin >= length) return length
        val either = rule.openOrClose ?: return begin
        var depth = 1
        var end = begin
        var match = either.find(text, begin, length)
        do {
            if (match != null) {
                end = match[1]
                if (!inZones(match[0], comments)) {
                    if (rule.open!!.find(text, match[0], match[1]) != null) depth++ else depth--
                }
            } else {
                depth = 0
                end = begin
            }
            match = if (depth > 0) either.find(text, if (end > (match?.get(0) ?: -1)) end else end + 1, length) else null
        } while (depth > 0)
        return end
    }

    private fun classParse(
        text: CharSequence, found: MutableList<FunctionEntry>, scanned: MutableList<IntArray>, comments: List<IntArray>, from: Int, end: Int,
    ) {
        val rule = zone ?: return
        var begin = from
        while (begin < end) {
            val match = rule.main.find(text, begin, end) ?: break
            val start = match[0]
            var stop = match[1]
            val owner = subLevel(text, start, stop, rule.owners).first
            if (rule.open != null && rule.close != null) stop = bodyEnd(text, stop, rule, comments)
            if (stop > end) break
            scanned += intArrayOf(start, stop)
            if (stop == end) break
            if (!inZones(start, comments)) funcParse(text, found, rule.function, start, stop, owner, comments)
            begin = if (stop > begin) stop else begin + 1
        }
    }

    companion object {
        /** Reads a functionList file. Null when it holds no rule that can be used. */
        /**
         * XML readers turn line breaks inside attribute values into blanks. The expressions in these files span
         * lines and carry # comments that end at the line break, so the breaks are written as character
         * references first, which survive.
         */
        internal fun keepLineBreaks(xml: String): String {
            val out = StringBuilder(xml.length + 256)
            var i = 0
            var quote = 0.toChar()
            var inTag = false
            while (i < xml.length) {
                val c = xml[i]
                when {
                    quote != 0.toChar() -> when (c) {
                        quote -> { quote = 0.toChar(); out.append(c) }
                        '\n' -> out.append("&#10;")
                        '\r' -> {}
                        '\t' -> out.append("&#9;")
                        else -> out.append(c)
                    }
                    inTag -> {
                        if (c == '"' || c == '\'') quote = c
                        if (c == '>') inTag = false
                        out.append(c)
                    }
                    xml.startsWith("<!--", i) -> {
                        val end = xml.indexOf("-->", i + 4).let { if (it < 0) xml.length else it + 3 }
                        out.append(xml, i, end)
                        i = end
                        continue
                    }
                    xml.startsWith("<?", i) -> {
                        val end = xml.indexOf("?>", i + 2).let { if (it < 0) xml.length else it + 2 }
                        out.append(xml, i, end)
                        i = end
                        continue
                    }
                    c == '<' -> { inTag = true; out.append(c) }
                    else -> out.append(c)
                }
                i++
            }
            return out.toString()
        }

        fun load(xml: InputStream): FunctionParser? = load(xml.readBytes().toString(Charsets.UTF_8))

        fun load(xmlText: String): FunctionParser? {
            val xml = org.xml.sax.InputSource(java.io.StringReader(keepLineBreaks(xmlText.removePrefix("\uFEFF"))))
            var displayName = ""
            var commentExpr = ""
            var skipped = 0
            var zoneMain = ""
            var zoneOpen = ""
            var zoneClose = ""
            val zoneOwners = ArrayList<String>()
            var zoneFunction = ""
            val zoneNames = ArrayList<String>()
            var unitMain = ""
            val unitNames = ArrayList<String>()
            val unitOwners = ArrayList<String>()
            var seenParser = false
            val path = ArrayList<String>()
            SAXParserFactory.newInstance().newSAXParser().parse(xml, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    path += qName
                    val inZone = "classRange" in path
                    when (qName) {
                        "parser" -> if (!seenParser) {
                            seenParser = true
                            displayName = a.getValue("displayName") ?: a.getValue("id") ?: ""
                            commentExpr = a.getValue("commentExpr") ?: ""
                        }
                        "classRange" -> if (zoneMain.isEmpty()) {
                            zoneMain = a.getValue("mainExpr") ?: ""
                            zoneOpen = a.getValue("openSymbole") ?: ""
                            zoneClose = a.getValue("closeSymbole") ?: ""
                        }
                        "function" -> if (inZone) { if (zoneFunction.isEmpty()) zoneFunction = a.getValue("mainExpr") ?: "" }
                        else if (unitMain.isEmpty()) unitMain = a.getValue("mainExpr") ?: ""
                        "nameExpr" -> (a.getValue("expr") ?: "").takeIf { it.isNotEmpty() }?.let { expr ->
                            val parent = path.getOrNull(path.size - 2)
                            when {
                                inZone && parent == "className" && "function" !in path -> zoneOwners += expr
                                !inZone && parent == "functionName" -> unitNames += expr
                                !inZone && parent == "className" -> unitOwners += expr
                            }
                        }
                        "funcNameExpr" -> (a.getValue("expr") ?: "").takeIf { it.isNotEmpty() }?.let { if (inZone) zoneNames += it }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    path.removeAt(path.size - 1)
                }
            })

            fun compile(expression: String): BoostPattern? {
                if (expression.isEmpty()) return null
                return BoostPattern.compile(expression).also { if (it == null) skipped++ }
            }
            // A rule is only as good as all of its expressions: with one missing it would list the wrong things.
            fun compileAll(expressions: List<String>): List<BoostPattern>? {
                val compiled = expressions.map { compile(it) }
                return if (compiled.any { it == null }) null else compiled.filterNotNull()
            }
            val unit = compile(unitMain)?.let { main ->
                val names = compileAll(unitNames)
                val owners = compileAll(unitOwners)
                if (names == null || owners == null) null else UnitRule(main, names, owners)
            }
            val zone = compile(zoneMain)?.let { main ->
                val function = compile(zoneFunction)
                val names = compileAll(zoneNames)
                val owners = compileAll(zoneOwners)
                val open = compile(zoneOpen)
                val close = compile(zoneClose)
                val brackets = zoneOpen.isNotEmpty() && zoneClose.isNotEmpty()
                val either = if (brackets) compile("($zoneOpen|$zoneClose)") else null
                if (function == null || names == null || owners == null || (brackets && (open == null || close == null || either == null))) null
                else ZoneRule(main, open.takeIf { brackets }, close.takeIf { brackets }, either, owners, UnitRule(function, names, owners))
            }
            if (unit == null && zone == null) return null
            return FunctionParser(displayName, compile(commentExpr), zone, unit, skipped)
        }
    }
}
