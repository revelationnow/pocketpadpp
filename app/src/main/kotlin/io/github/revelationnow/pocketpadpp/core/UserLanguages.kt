package io.github.revelationnow.pocketpadpp.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** One style of a user-defined language. Colours are 0xRRGGBB; [colorStyle] says which of the two are in use. */
class UdlStyle(val id: Int, val fg: Int?, val bg: Int?, val colorStyle: Int, val fontStyle: Int, val nesting: Int)

/**
 * A user-defined language (UDL) as one of Notepad++'s UDL files describes it: words, operators, comment and
 * delimiter marks and folding points, for the "user" lexer that Notepad++ adds to Lexilla.
 */
class UserLanguage(
    val name: String,
    val extensions: List<String>,
    val udlVersion: String,
    /** Made for a dark theme. Of two definitions for the same file extension, the one that fits the theme is taken. */
    val darkModeTheme: Boolean,
    val caseIgnored: Boolean,
    val allowFoldOfComments: Boolean,
    val foldCompact: Boolean,
    val forcePureLC: Int,
    val decimalSeparator: Int,
    val isPrefix: BooleanArray,
    /** The lists by their SCE_USER_KWLIST_ number. */
    val keywordLists: Array<String>,
    val styles: List<UdlStyle>,
) {
    /** Tells one definition from another for the lexer, which keeps what it worked out per definition. */
    val id: Int = (name.hashCode() * 31 + keywordLists.contentHashCode()) and 0x7FFFFFFF

    /** A mark from the comment list, which reads like "00// 01 02 03/x 04x/": the text after a two-digit code. */
    private fun symbol(code: String): String {
        val list = keywordLists[UserLanguages.COMMENTS]
        val at = list.indexOf(code)
        if (at < 0) return ""
        val end = list.indexOf(' ', at).let { if (it < 0) list.length else it }
        return list.substring(at + 2, end)
    }

    val commentLine: String get() = symbol("00")
    val commentStart: String get() = symbol("03")
    val commentEnd: String get() = symbol("04")
}

/** Reads Notepad++'s UDL files and turns a definition into what the lexer is to be told, as setUserLexer does. */
object UserLanguages {
    const val COMMENTS = 0
    private const val OPERATORS1 = 8
    private const val KEYWORDS1 = 19
    private const val DELIMITERS = 27
    private const val LISTS = 28
    private const val STYLES = 24
    private const val GROUPS = 8

    // Names of the lists in UDL files of every version, as in Notepad++'s GlobalMappers.
    private val LIST_IDS: Map<String, Int> = HashMap<String, Int>().apply {
        // before 2.0
        put("Operators", OPERATORS1); put("Folder+", 10); put("Folder-", 12)
        for (i in 1..4) put("Words$i", KEYWORDS1 + i - 1)
        // 2.0
        put("Comments", COMMENTS); put("Numbers, additional", 7); put("Numbers, prefixes", 2)
        put("Numbers, extras with prefixes", 4); put("Numbers, suffixes", 6)
        put("Operators1", OPERATORS1); put("Operators2", 9)
        put("Folders in code1, open", 10); put("Folders in code1, middle", 11); put("Folders in code1, close", 12)
        put("Folders in code2, open", 13); put("Folders in code2, middle", 14); put("Folders in code2, close", 15)
        put("Folders in comment, open", 16); put("Folders in comment, middle", 17); put("Folders in comment, close", 18)
        for (i in 1..8) put("Keywords$i", KEYWORDS1 + i - 1)
        put("Delimiters", DELIMITERS)
        // 2.1
        put("Numbers, prefix1", 1); put("Numbers, prefix2", 2); put("Numbers, extras1", 3); put("Numbers, extras2", 4)
        put("Numbers, suffix1", 5); put("Numbers, suffix2", 6); put("Numbers, range", 7)
    }

    private val STYLE_IDS: Map<String, Int> = HashMap<String, Int>().apply {
        // before 2.0
        put("FOLDEROPEN", 13); put("FOLDERCLOSE", 13)
        for (i in 1..4) put("KEYWORD$i", 3 + i)
        put("COMMENT", 1); put("COMMENT LINE", 2); put("NUMBER", 3); put("OPERATOR", 12)
        for (i in 1..3) put("DELIMINER$i", 15 + i)
        // from 2.0 on
        put("DEFAULT", 0); put("COMMENTS", 1); put("LINE COMMENTS", 2); put("NUMBERS", 3)
        for (i in 1..8) put("KEYWORDS$i", 3 + i)
        put("OPERATORS", 12); put("FOLDER IN CODE1", 13); put("FOLDER IN CODE2", 14); put("FOLDER IN COMMENT", 15)
        for (i in 1..8) put("DELIMITERS$i", 15 + i)
    }

    /** The names styles go by in the Style Configurator, by style number. */
    val STYLE_NAMES: List<String> = listOf(
        "DEFAULT", "COMMENTS", "LINE COMMENTS", "NUMBERS", "KEYWORDS1", "KEYWORDS2", "KEYWORDS3", "KEYWORDS4", "KEYWORDS5",
        "KEYWORDS6", "KEYWORDS7", "KEYWORDS8", "OPERATORS", "FOLDER IN CODE1", "FOLDER IN CODE2", "FOLDER IN COMMENT",
        "DELIMITERS1", "DELIMITERS2", "DELIMITERS3", "DELIMITERS4", "DELIMITERS5", "DELIMITERS6", "DELIMITERS7", "DELIMITERS8",
    )

    // Lists the lexer takes as properties; the others are sent as keyword sets, in order.
    private val PROPERTY_LISTS = mapOf(
        COMMENTS to "userDefine.comments", DELIMITERS to "userDefine.delimiters", OPERATORS1 to "userDefine.operators1",
        1 to "userDefine.numberPrefix1", 2 to "userDefine.numberPrefix2", 3 to "userDefine.numberExtras1",
        4 to "userDefine.numberExtras2", 5 to "userDefine.numberSuffix1", 6 to "userDefine.numberSuffix2",
        7 to "userDefine.numberRange", 10 to "userDefine.foldersInCode1Open", 11 to "userDefine.foldersInCode1Middle",
        12 to "userDefine.foldersInCode1Close",
    )

    /** Every language of a UDL file. A file that is not one, or a language without a name, gives nothing. */
    fun read(xml: InputStream): List<UserLanguage> {
        val found = ArrayList<UserLanguage>()
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(xml, object : DefaultHandler() {
                var name = ""
                var extensions = emptyList<String>()
                var version = ""
                var dark = false
                var caseIgnored = false
                var foldComments = false
                var foldCompact = false
                var forcePureLC = 0
                var decimalSeparator = 0
                var prefix = BooleanArray(GROUPS)
                var lists = Array(LISTS) { "" }
                var styles = ArrayList<UdlStyle>()
                var listName: String? = null
                val text = StringBuilder()
                var parts = 0

                fun yes(a: Attributes, key: String) = a.getValue(key) == "yes"

                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "UserLang" -> {
                            name = a.getValue("name") ?: ""
                            extensions = (a.getValue("ext") ?: "").split(' ').filter { it.isNotEmpty() }
                            version = a.getValue("udlVersion") ?: ""
                            dark = yes(a, "darkModeTheme")
                            caseIgnored = false; foldComments = false; foldCompact = false; forcePureLC = 0; decimalSeparator = 0
                            prefix = BooleanArray(GROUPS)
                            lists = Array(LISTS) { "" }
                            styles = ArrayList()
                            parts = 0
                        }
                        "Settings" -> parts = parts or 1
                        "KeywordLists" -> parts = parts or 2
                        "Styles" -> parts = parts or 4
                        "Global" -> {
                            caseIgnored = yes(a, "caseIgnored")
                            foldComments = yes(a, "allowFoldOfComments")
                            foldCompact = yes(a, "foldCompact")
                            forcePureLC = a.getValue("forcePureLC")?.toIntOrNull() ?: 0
                            decimalSeparator = a.getValue("decimalSeparator")?.toIntOrNull() ?: 0
                        }
                        "Prefix" -> if (version == "2.1" || version == "2.0") {
                            for (i in 0 until GROUPS) prefix[i] = yes(a, "Keywords${i + 1}")
                        } else {
                            for (i in 0 until 4) prefix[i] = yes(a, "words${i + 1}")
                        }
                        "Keywords" -> {
                            listName = a.getValue("name") ?: ""
                            text.setLength(0)
                        }
                        "WordsStyle" -> {
                            val id = STYLE_IDS[a.getValue("name") ?: ""] ?: return
                            if (id >= STYLES || styles.any { it.id == id }) return
                            styles += UdlStyle(
                                id, a.getValue("fgColor")?.trim()?.toIntOrNull(16), a.getValue("bgColor")?.trim()?.toIntOrNull(16),
                                a.getValue("colorStyle")?.toIntOrNull() ?: 3, a.getValue("fontStyle")?.toIntOrNull() ?: 0,
                                a.getValue("nesting")?.toIntOrNull() ?: 0,
                            )
                        }
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (listName != null) text.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "Keywords" -> {
                            val list = listName
                            listName = null
                            val value = text.toString()
                            if (list == null || value.isEmpty()) return
                            when {
                                // The two list forms from before version 2.0.
                                version.isEmpty() && list == "Delimiters" && value.length >= 6 -> {
                                    fun mark(i: Int) = if (value[i] != '0') value[i].toString() else ""
                                    lists[DELIMITERS] = "00${mark(0)} 01 02${mark(3)} 03${mark(1)} 04 05${mark(4)} 06${mark(2)} 07 08${mark(5)}" +
                                        " 09 10 11 12 13 14 15 16 17 18 19 20 21 22 23"
                                }
                                list == "Comment" -> lists[COMMENTS] =
                                    (" $value").replace(" 0", " 00").replace(" 1", " 03").replace(" 2", " 04").removePrefix(" ") + " 01 02"
                                else -> LIST_IDS[list]?.let { lists[it] = value }
                            }
                        }
                        "UserLang" -> if (name.isNotEmpty() && parts == 7) {
                            found += UserLanguage(
                                name, extensions, version, dark, caseIgnored, foldComments, foldCompact, forcePureLC, decimalSeparator,
                                prefix, lists, styles,
                            )
                        }
                    }
                }
            })
        } catch (e: Exception) {
            return emptyList()
        }
        return found
    }

    /**
     * Keyword lists may hold phrases in quotes. The lexer wants the quotes gone and the blanks inside them
     * replaced by a marker, which says whether the phrase was in double or in single quotes.
     */
    internal fun phrases(words: String): String {
        val out = StringBuilder(words.length)
        var inDouble = false
        var inSingle = false
        var content = false
        var j = 0
        while (j < words.length) {
            val c = words[j]
            when {
                !inSingle && c == '"' -> inDouble = !inDouble
                !inDouble && c == '\'' -> inSingle = !inSingle
                c == '\\' && j + 1 < words.length && words[j + 1] in "\"'\\" -> {
                    j++
                    out.append(words[j])
                }
                inDouble || inSingle -> {
                    val next = words.getOrElse(j + 1) { ' ' }
                    if (c > ' ') {
                        out.append(c)
                        content = true
                    } else if (content && words[j - 1] != '"' && next != '"' && next > ' ') {
                        out.append(if (inDouble) '\u000B' else '\b')
                    }
                }
                else -> out.append(c)
            }
            j++
        }
        return out.toString()
    }

    /** An empty definition to start a new language from, in the form Notepad++ 8 writes. */
    fun template(name: String, extensions: String): String {
        fun escape(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val lists = LIST_IDS.entries.filter { (key, _) -> key.first().isUpperCase() && !key.startsWith("Words") && key !in OLD_LIST_NAMES }
            .sortedBy { it.value }.distinctBy { it.value }
        val out = StringBuilder("<NotepadPlus>\n")
        out.append("    <UserLang name=\"${escape(name)}\" ext=\"${escape(extensions)}\" udlVersion=\"2.1\">\n")
        out.append("        <Settings>\n")
        out.append("            <Global caseIgnored=\"no\" allowFoldOfComments=\"no\" foldCompact=\"no\" forcePureLC=\"0\" decimalSeparator=\"0\" />\n")
        out.append("            <Prefix").append((1..GROUPS).joinToString("") { " Keywords$it=\"no\"" }).append(" />\n")
        out.append("        </Settings>\n        <KeywordLists>\n")
        for ((list, id) in lists) {
            val value = when (id) {
                COMMENTS -> "00 01 02 03 04"
                DELIMITERS -> (0 until 24).joinToString(" ") { "%02d".format(it) }
                else -> ""
            }
            out.append("            <Keywords name=\"$list\">$value</Keywords>\n")
        }
        out.append("        </KeywordLists>\n        <Styles>\n")
        for (style in STYLE_NAMES) {
            out.append("            <WordsStyle name=\"$style\" fgColor=\"000000\" bgColor=\"FFFFFF\" colorStyle=\"1\" fontStyle=\"0\" nesting=\"0\" />\n")
        }
        return out.append("        </Styles>\n    </UserLang>\n</NotepadPlus>\n").toString()
    }

    private val OLD_LIST_NAMES = setOf(
        "Operators", "Folder+", "Folder-", "Numbers, additional", "Numbers, prefixes", "Numbers, extras with prefixes", "Numbers, suffixes",
    )

    fun plan(language: UserLanguage): LexerPlan {
        fun flag(on: Boolean) = if (on) "1" else "0"
        val properties = ArrayList<Pair<String, String>>()
        properties += "fold" to "1"
        properties += "userDefine.isCaseIgnored" to flag(language.caseIgnored)
        properties += "userDefine.allowFoldOfComments" to flag(language.allowFoldOfComments)
        properties += "userDefine.foldCompact" to flag(language.foldCompact)
        for (i in 0 until GROUPS) properties += "userDefine.prefixKeywords${i + 1}" to flag(language.isPrefix[i])
        val sets = LinkedHashMap<Int, String>()
        for (i in 0 until LISTS) {
            val property = PROPERTY_LISTS[i]
            if (property != null) properties += property to language.keywordLists[i] else sets[sets.size] = phrases(language.keywordLists[i])
        }
        properties += "userDefine.forcePureLC" to language.forcePureLC.toString()
        properties += "userDefine.decimalSeparator" to language.decimalSeparator.toString()
        properties += "userDefine.udlName" to language.id.toString()
        val styles = ArrayList<WordStyle>()
        for (id in 0 until STYLES) {
            val style = language.styles.firstOrNull { it.id == id }
            properties += "userDefine.nesting.%02d".format(id) to (style?.nesting ?: 0).toString()
            styles += WordStyle(
                STYLE_NAMES[id], id, style?.fg.takeIf { style != null && style.colorStyle and 1 != 0 },
                style?.bg.takeIf { style != null && style.colorStyle and 2 != 0 }, style?.fontStyle ?: 0, -1, "",
            )
        }
        return LexerPlan("user", sets, properties, styles)
    }
}
