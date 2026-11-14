package io.github.revelationnow.pocketpadpp.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/** Notepad++ keyword classes are named in its XML files and numbered in its code (see getKwClassFromName). */
internal fun keywordClassIndex(name: String?): Int = when (name) {
    null, "" -> -1
    "instre1" -> 0
    "instre2" -> 1
    "type1" -> 2
    "type2" -> 3
    "type3" -> 4
    "type4" -> 5
    "type5" -> 6
    "type6" -> 7
    "type7" -> 8
    else -> when {
        name.startsWith("substyle") -> (name.removePrefix("substyle").toIntOrNull() ?: 0).let { if (it in 1..8) 8 + it else -1 }
        name.length == 1 && name[0] in '0'..'8' -> name[0] - '0'
        else -> -1
    }
}

/** A language as Notepad++ defines it: the table in its source code plus its entry in langs.model.xml. */
class Language(
    /** Notepad++'s internal name, used in langs.xml, stylers.xml and session files. */
    val name: String,
    val display: String,
    /** Lexilla lexer name. */
    val lexer: String,
    /** Which of Notepad++'s lexer setup routines applies (see tools/gen_npp_tables.py). */
    val kind: String,
    val mask: Int,
    val properties: List<Pair<String, String>>,
) {
    var extensions: List<String> = emptyList()
        internal set
    var commentLine: String = ""
        internal set
    var commentStart: String = ""
        internal set
    var commentEnd: String = ""
        internal set

    /** Keyword lists by class index. */
    internal val keywords = arrayOfNulls<String>(17)

    /** Set for a user-defined language: its definition. */
    var user: UserLanguage? = null
        internal set

    fun keywordsOf(classIndex: Int): String = keywords.getOrNull(classIndex) ?: ""

    override fun toString(): String = name
}

class LanguageRegistry private constructor(val languages: List<Language>) {
    private val byName = languages.associateBy { it.name }
    private val byExtension = HashMap<String, Language>()

    val normal: Language = byName["normal"] ?: Language("normal", "Normal text", "null", "text", 0, emptyList())

    /** Languages as listed in the Language menu, sorted by name. */
    val menu: List<Language> = languages
        .filter { it.name != "normal" && it.name != "javascript" && it.name != "searchResult" && it.name != "ext" }
        .sortedBy { it.display.lowercase() }

    init {
        for (language in languages) {
            for (extension in language.extensions) {
                byExtension.putIfAbsent(extension.lowercase(), language)
            }
        }
    }

    /** The user-defined languages, in the order they were given. */
    var userLanguages: List<Language> = emptyList()
        private set

    /** True while a dark theme is in use, which decides between two definitions for the same file extension. */
    var darkTheme = false

    /** Replaces the user-defined languages. Each becomes a language of its own name. */
    fun setUserLanguages(definitions: List<UserLanguage>) {
        userLanguages = definitions.distinctBy { it.name }.map { definition ->
            Language(definition.name, definition.name, "user", "udl", 0, emptyList()).apply {
                extensions = definition.extensions
                commentLine = definition.commentLine
                commentStart = definition.commentStart
                commentEnd = definition.commentEnd
                user = definition
            }
        }
    }

    fun byName(name: String?): Language? = byName[name] ?: userLanguages.firstOrNull { it.name == name }

    /** Picks a language from a file name, by well-known names first and then by extension. */
    fun forFileName(fileName: String): Language {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val lower = base.lowercase()
        // As in Notepad++, a user-defined language that names the extension comes before the built-in ones.
        val ending = lower.substringAfterLast('.', "")
        if (ending.isNotEmpty() && lower.contains('.')) {
            val claiming = userLanguages.filter { language -> language.extensions.any { it.equals(ending, ignoreCase = true) } }
            (claiming.firstOrNull { it.user?.darkModeTheme == darkTheme } ?: claiming.firstOrNull())?.let { return it }
        }
        WELL_KNOWN[lower]?.let { known -> byName[known]?.let { return it } }
        val extension = lower.substringAfterLast('.', "")
        if (extension.isEmpty() || !lower.contains('.')) return normal
        return byExtension[extension] ?: normal
    }

    companion object {
        private val WELL_KNOWN = mapOf(
            "makefile" to "makefile", "gnumakefile" to "makefile",
            "cmakelists.txt" to "cmake",
            "sconstruct" to "python", "sconscript" to "python", "wscript" to "python",
            "rakefile" to "ruby", "vagrantfile" to "ruby", "gemfile" to "ruby",
            "crontab" to "bash", "pkgbuild" to "bash", "apkbuild" to "bash",
            ".bashrc" to "bash", ".bash_profile" to "bash", ".profile" to "bash", ".zshrc" to "bash",
            "dockerfile" to "bash",
        )

        /**
         * @param table languages.tsv, generated from Notepad++'s source code
         * @param langsXml Notepad++'s langs.model.xml
         */
        fun load(table: InputStream, langsXml: InputStream): LanguageRegistry {
            val languages = ArrayList<Language>()
            table.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachLine
                val cells = line.split('\t')
                if (cells.size < 5) return@forEachLine
                val properties = cells.getOrElse(5) { "" }.split(';').filter { it.contains('=') }
                    .map { it.substringBefore('=') to it.substringAfter('=') }
                languages += Language(cells[0], cells[1], cells[2], cells[3], cells[4].toIntOrNull() ?: 0, properties)
            }
            val byName = languages.associateBy { it.name }
            val parser = SAXParserFactory.newInstance().newSAXParser()
            parser.parse(langsXml, object : DefaultHandler() {
                var current: Language? = null
                var classIndex = -1
                val text = StringBuilder()

                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    when (qName) {
                        "Language" -> current = byName[attributes.getValue("name")]?.also { language ->
                            language.extensions = (attributes.getValue("ext") ?: "").split(' ').filter { it.isNotEmpty() }
                            language.commentLine = attributes.getValue("commentLine") ?: ""
                            language.commentStart = attributes.getValue("commentStart") ?: ""
                            language.commentEnd = attributes.getValue("commentEnd") ?: ""
                        }
                        "Keywords" -> {
                            classIndex = keywordClassIndex(attributes.getValue("name"))
                            text.setLength(0)
                        }
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (classIndex >= 0) text.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "Keywords" -> {
                            val language = current
                            if (language != null && classIndex in language.keywords.indices) {
                                language.keywords[classIndex] = text.toString().trim()
                            }
                            classIndex = -1
                        }
                        "Language" -> current = null
                    }
                }
            })
            return LanguageRegistry(languages)
        }
    }
}

/** One entry of a Notepad++ style file: a WordsStyle of a lexer or a global WidgetStyle. */
data class WordStyle(
    val name: String,
    val styleId: Int,
    /** 0xRRGGBB, or null when the file gives none or the entry opts out through colorStyle. */
    val fg: Int?,
    val bg: Int?,
    /** Bit 1 bold, bit 2 italic, bit 4 underline. */
    val fontStyle: Int,
    val keywordClass: Int,
    /** Extra keywords the user added to this style. */
    val userKeywords: String,
) {
    val bold: Boolean get() = fontStyle and 1 != 0
    val italic: Boolean get() = fontStyle and 2 != 0
    val underline: Boolean get() = fontStyle and 4 != 0
}

/** What the Style Configurator changed about one style. A null field leaves the theme's own value. */
data class StyleChange(val fg: Int? = null, val bg: Int? = null, val fontStyle: Int? = null)

/** The user's changes to a theme, by lexer name (empty for the global styles) and style name. */
class StyleOverrides {
    private val changes = LinkedHashMap<Pair<String, String>, StyleChange>()

    val isEmpty: Boolean get() = changes.isEmpty()

    fun get(lexer: String, style: String): StyleChange? = changes[lexer to style]

    /** Records a change; null or a change of nothing puts the style back to what the theme says. */
    fun set(lexer: String, style: String, change: StyleChange?) {
        if (change == null || change == StyleChange()) changes.remove(lexer to style) else changes[lexer to style] = change
    }

    fun clear() = changes.clear()

    /** One line per style: lexer, style, foreground, background, font style, separated by tabs; "-" for unchanged. */
    fun encode(): String = changes.entries.joinToString("\n") { (key, change) ->
        fun hex(colour: Int?) = colour?.let { "%06X".format(it and 0xFFFFFF) } ?: "-"
        listOf(key.first, key.second, hex(change.fg), hex(change.bg), change.fontStyle?.toString() ?: "-").joinToString("\t")
    }

    companion object {
        fun decode(text: String): StyleOverrides {
            val result = StyleOverrides()
            for (line in text.split('\n')) {
                val cells = line.split('\t')
                if (cells.size < 5) continue
                result.set(cells[0], cells[1], StyleChange(cells[2].toIntOrNull(16), cells[3].toIntOrNull(16), cells[4].toIntOrNull()))
            }
            return result
        }
    }
}

/** A Notepad++ style file (stylers.model.xml or a theme). */
class NppTheme private constructor(
    private val lexers: Map<String, List<WordStyle>>,
    private val globals: List<WordStyle>,
) {
    fun lexerStyles(languageName: String): List<WordStyle> = lexers[languageName] ?: emptyList()
    fun global(name: String): WordStyle? = globals.firstOrNull { it.name == name }
    val globalStyles: List<WordStyle> get() = globals

    /** This theme with the user's changes laid over it. */
    fun with(overrides: StyleOverrides): NppTheme {
        if (overrides.isEmpty) return this
        fun changed(lexer: String, style: WordStyle): WordStyle {
            val change = overrides.get(lexer, style.name) ?: return style
            return style.copy(fg = change.fg ?: style.fg, bg = change.bg ?: style.bg, fontStyle = change.fontStyle ?: style.fontStyle)
        }
        return NppTheme(
            lexers.mapValues { (lexer, styles) -> styles.map { changed(lexer, it) } },
            globals.map { changed("", it) },
        )
    }
    val lexerNames: Set<String> get() = lexers.keys

    /** Editor background and text colours, for tinting the application around the editor. */
    val background: Int get() = global("Default Style")?.bg ?: 0xFFFFFF
    val foreground: Int get() = global("Default Style")?.fg ?: 0x000000

    /** True when the editor background is dark, judged by its perceived brightness. */
    val isDark: Boolean
        get() {
            val bg = background
            val luminance = 0.299 * ((bg shr 16) and 0xFF) + 0.587 * ((bg shr 8) and 0xFF) + 0.114 * (bg and 0xFF)
            return luminance < 128
        }

    companion object {
        fun load(stylersXml: InputStream): NppTheme {
            val lexers = LinkedHashMap<String, MutableList<WordStyle>>()
            val globals = ArrayList<WordStyle>()
            val parser = SAXParserFactory.newInstance().newSAXParser()
            parser.parse(stylersXml, object : DefaultHandler() {
                var currentLexer: MutableList<WordStyle>? = null
                var pending: WordStyle? = null
                val text = StringBuilder()

                fun colour(value: String?): Int? = value?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull(16)

                fun style(attributes: Attributes): WordStyle {
                    // colorStyle: bit 1 foreground, bit 2 background; absent means both.
                    val colorStyle = attributes.getValue("colorStyle")?.toIntOrNull() ?: 3
                    return WordStyle(
                        name = attributes.getValue("name") ?: "",
                        styleId = attributes.getValue("styleID")?.toIntOrNull() ?: 0,
                        fg = colour(attributes.getValue("fgColor")).takeIf { colorStyle and 1 != 0 },
                        bg = colour(attributes.getValue("bgColor")).takeIf { colorStyle and 2 != 0 },
                        fontStyle = attributes.getValue("fontStyle")?.toIntOrNull() ?: 0,
                        keywordClass = keywordClassIndex(attributes.getValue("keywordClass")),
                        userKeywords = "",
                    )
                }

                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    when (qName) {
                        "LexerType" -> currentLexer = lexers.getOrPut(attributes.getValue("name") ?: "") { ArrayList() }
                        "WordsStyle" -> {
                            pending = style(attributes)
                            text.setLength(0)
                        }
                        "WidgetStyle" -> globals += style(attributes)
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (pending != null) text.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "WordsStyle" -> {
                            pending?.let { currentLexer?.add(it.copy(userKeywords = text.toString().trim())) }
                            pending = null
                        }
                        "LexerType" -> currentLexer = null
                    }
                }
            })
            return NppTheme(lexers, globals)
        }
    }
}
