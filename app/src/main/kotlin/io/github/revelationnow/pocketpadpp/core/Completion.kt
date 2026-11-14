package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** One way of calling a function, as Notepad++'s auto-completion files describe it. */
class Overload(val returns: String, val description: String, val parameters: List<String>)

class ApiWord(val name: String, val isFunction: Boolean, val overloads: List<Overload>)

/** The keywords and functions of one language: one of Notepad++'s autoCompletion files. */
class ApiIndex private constructor(
    private val words: List<ApiWord>,
    val ignoreCase: Boolean,
    val startFunction: Char,
    val stopFunction: Char,
    val separator: Char,
    /** Characters that count as part of a word here besides letters, digits and the underscore. */
    val extraWordChars: String,
) {
    private val byName = words.associateBy { if (ignoreCase) it.name.lowercase() else it.name }

    val size: Int get() = words.size

    fun word(name: String): ApiWord? = byName[if (ignoreCase) name.lowercase() else name]

    /** The names that start with the prefix, in the file's (alphabetical) order. */
    fun complete(prefix: String, limit: Int = 50): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val found = ArrayList<String>()
        for (word in words) {
            if (word.name.length > prefix.length && word.name.startsWith(prefix, ignoreCase)) {
                found += word.name
                if (found.size >= limit) break
            }
        }
        return found
    }

    companion object {
        fun load(xml: InputStream): ApiIndex {
            val words = ArrayList<ApiWord>()
            var ignoreCase = false
            var start = '('
            var stop = ')'
            var separator = ','
            var extra = ""
            SAXParserFactory.newInstance().newSAXParser().parse(xml, object : DefaultHandler() {
                var name = ""
                var isFunction = false
                var overloads = ArrayList<Overload>()
                var returns = ""
                var description = ""
                var parameters = ArrayList<String>()

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "Environment" -> {
                            ignoreCase = a.getValue("ignoreCase") == "yes"
                            a.getValue("startFunc")?.firstOrNull()?.let { start = it }
                            a.getValue("stopFunc")?.firstOrNull()?.let { stop = it }
                            a.getValue("paramSeparator")?.firstOrNull()?.let { separator = it }
                            extra = a.getValue("additionalWordChar") ?: ""
                        }
                        "KeyWord" -> {
                            name = a.getValue("name") ?: ""
                            isFunction = a.getValue("func") == "yes"
                            overloads = ArrayList()
                        }
                        "Overload" -> {
                            returns = a.getValue("retVal") ?: ""
                            description = a.getValue("descr") ?: ""
                            parameters = ArrayList()
                        }
                        "Param" -> parameters.add(a.getValue("name") ?: "")
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "Overload" -> overloads.add(Overload(returns, description, parameters))
                        "KeyWord" -> if (name.isNotEmpty()) words.add(ApiWord(name, isFunction, overloads))
                    }
                }
            })
            return ApiIndex(words, ignoreCase, start, stop, separator, extra)
        }
    }
}

/** A function call the caret is inside of: which function, and which of its parameters is being typed. */
class CallSite(val word: ApiWord, val parameter: Int)

/** Auto-completion as Notepad++ does it: from the words of the document and from the language's API file. */
object Completion {
    private fun isWordChar(c: Char, extra: String) = c.isLetterOrDigit() || c == '_' || c in extra

    /** The part of a word that stands before the caret, and where it starts (in bytes). */
    fun prefix(sci: SciApi, extraWordChars: String = ""): Pair<Long, String> {
        val caret = sci.send(Sci.SCI_GETCURRENTPOS)
        val lineStart = sci.send(Sci.SCI_POSITIONFROMLINE, sci.send(Sci.SCI_LINEFROMPOSITION, caret))
        val before = String(sci.range(lineStart, caret), Charsets.UTF_8)
        var start = before.length
        while (start > 0 && isWordChar(before[start - 1], extraWordChars)) start--
        val word = before.substring(start)
        return caret - word.toByteArray(Charsets.UTF_8).size to word
    }

    /** Words of the text that start with the prefix, most used first is not Notepad++'s way: they come sorted. */
    fun words(text: String, prefix: String, ignoreCase: Boolean, extraWordChars: String = "", limit: Int = 50): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val found = sortedSetOf(if (ignoreCase) String.CASE_INSENSITIVE_ORDER else naturalOrder())
        var i = 0
        while (i < text.length) {
            if (!isWordChar(text[i], extraWordChars)) {
                i++
                continue
            }
            var end = i
            while (end < text.length && isWordChar(text[end], extraWordChars)) end++
            if (end - i > prefix.length && text.startsWith(prefix, i, ignoreCase)) found += text.substring(i, end)
            i = end
        }
        return found.take(limit)
    }

    /** What to offer at the caret: API names and document words together, as in Notepad++'s default setting. */
    fun candidates(text: String, prefix: String, api: ApiIndex?, fromApi: Boolean, fromWords: Boolean, limit: Int = 40): List<String> {
        val ignoreCase = api?.ignoreCase ?: false
        val merged = sortedSetOf(if (ignoreCase) String.CASE_INSENSITIVE_ORDER else naturalOrder())
        if (fromApi && api != null) merged += api.complete(prefix, limit)
        if (fromWords) merged += words(text, prefix, ignoreCase, api?.extraWordChars ?: "", limit)
        // The word being typed is in the document too, but only as itself.
        merged.remove(prefix)
        return merged.take(limit)
    }

    /** Replaces the typed prefix by the chosen word. */
    fun accept(sci: SciApi, start: Long, word: String) {
        sci.send(Sci.SCI_SETSEL, start, sci.send(Sci.SCI_GETCURRENTPOS))
        sci.sendText(Sci.SCI_REPLACESEL, 0, word)
    }

    /**
     * Function Parameters Hint: looks back from the caret, on the same line, for the call that is being typed.
     * Calls that are already closed are stepped over, so f(g(x), | finds f and its second parameter.
     */
    fun callSite(lineBeforeCaret: String, api: ApiIndex): CallSite? {
        var depth = 0
        var parameter = 0
        var i = lineBeforeCaret.length - 1
        while (i >= 0) {
            val c = lineBeforeCaret[i]
            when {
                c == api.stopFunction -> depth++
                c == api.startFunction && depth > 0 -> depth--
                c == api.startFunction -> {
                    var end = i
                    while (end > 0 && lineBeforeCaret[end - 1] == ' ') end--
                    var start = end
                    while (start > 0 && isWordChar(lineBeforeCaret[start - 1], api.extraWordChars)) start--
                    val word = api.word(lineBeforeCaret.substring(start, end))
                    if (word != null && word.isFunction && word.overloads.isNotEmpty()) return CallSite(word, parameter)
                    // Not a known function: perhaps a bracket inside a call further out.
                    parameter = 0
                }
                c == api.separator && depth == 0 -> parameter++
            }
            i--
        }
        return null
    }

    /** The hint as text: return type, name and parameters, with the current parameter marked by [start, end). */
    fun hint(site: CallSite, overloadIndex: Int, api: ApiIndex): Triple<String, Int, Int> {
        val overload = site.word.overloads[overloadIndex.coerceIn(0, site.word.overloads.size - 1)]
        val text = StringBuilder()
        if (overload.returns.isNotEmpty()) text.append(overload.returns).append(' ')
        text.append(site.word.name).append(' ').append(api.startFunction)
        var markStart = -1
        var markEnd = -1
        overload.parameters.forEachIndexed { index, parameter ->
            if (index > 0) text.append(api.separator).append(' ')
            if (index == site.parameter) markStart = text.length
            text.append(parameter)
            if (index == site.parameter) markEnd = text.length
        }
        text.append(api.stopFunction)
        return Triple(text.toString(), markStart, markEnd)
    }

    /**
     * Path Completion: the names in a folder that continue the path typed before the caret. Gives the start
     * of the last path part (as a count of characters back from the caret) and the names that fit.
     */
    fun paths(lineBeforeCaret: String, list: (String) -> List<String>?): Pair<Int, List<String>> {
        var start = lineBeforeCaret.length
        while (start > 0 && !lineBeforeCaret[start - 1].isWhitespace() && lineBeforeCaret[start - 1] !in "\"'<>|") start--
        val path = lineBeforeCaret.substring(start)
        val slash = path.lastIndexOf('/')
        if (slash < 0) return 0 to emptyList()
        val folder = path.substring(0, slash + 1)
        val typed = path.substring(slash + 1)
        val names = list(folder) ?: return 0 to emptyList()
        return typed.length to names.filter { it.startsWith(typed, ignoreCase = true) && it.length > typed.length }.sortedBy { it.lowercase() }
    }
}
