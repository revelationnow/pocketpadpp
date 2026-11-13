package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci
import io.github.revelationnow.pocketpadpp.scintilla.SciLex

/** Everything needed to make Scintilla highlight one language the way Notepad++ does. */
class LexerPlan(
    val lexer: String,
    /** Keyword set number (SCI_SETKEYWORDS) to its words. */
    val keywordSets: Map<Int, String>,
    val properties: List<Pair<String, String>>,
    val styles: List<WordStyle>,
    /** Styles whose background runs to the edge of the window. */
    val eolFilled: List<Int> = emptyList(),
)

/**
 * Mirrors Notepad++'s ScintillaEditView::defineDocType and its setXxxLexer helpers: which lexer a language
 * uses, which keyword class goes to which keyword set, and which lexer properties are switched on.
 */
object LanguageSetup {
    private const val INSTR = 0
    private const val INSTR2 = 1
    private const val TYPE = 2
    private const val TYPE2 = 3

    private val FOLD = listOf("fold" to "1", "fold.compact" to "0", "fold.comment" to "1")

    fun plan(language: Language, registry: LanguageRegistry, theme: NppTheme): LexerPlan {
        /** Notepad++'s concatToBuildKeywordList: words the user added in the style file, then the language's own. */
        fun words(source: Language?, classIndex: Int): String {
            if (source == null) return ""
            val user = theme.lexerStyles(source.name).filter { it.keywordClass == classIndex }.joinToString(" ") { it.userKeywords }
            return (user + " " + source.keywordsOf(classIndex)).trim()
        }

        val doxygen = registry.byName("cpp")?.keywordsOf(TYPE2) ?: ""
        val styles = theme.lexerStyles(language.name)

        return when (language.kind) {
            "cpp" -> LexerPlan(
                lexer = "cpp",
                keywordSets = buildMap {
                    put(0, words(language, INSTR))
                    put(1, words(language, TYPE))
                    if (language.name != "rc") put(2, doxygen)
                    put(3, words(language, INSTR2))
                },
                properties = FOLD + listOfNotNull(
                    "fold.cpp.comment.explicit" to "0",
                    "fold.preprocessor" to "1",
                    "lexer.cpp.track.preprocessor" to "0",
                    ("lexer.cpp.backquoted.strings" to "1").takeIf { language.name == "go" },
                ),
                styles = styles,
            )

            "js" -> {
                val js = registry.byName("javascript.js") ?: language
                LexerPlan(
                    lexer = "cpp",
                    keywordSets = mapOf(0 to words(js, INSTR), 1 to words(js, TYPE), 2 to doxygen, 3 to words(js, INSTR2)),
                    properties = FOLD + listOf("lexer.cpp.track.preprocessor" to "0", "lexer.cpp.backquoted.strings" to "2"),
                    styles = theme.lexerStyles(js.name),
                )
            }

            "typescript" -> LexerPlan(
                lexer = language.lexer,
                keywordSets = mapOf(0 to words(language, INSTR), 1 to words(language, TYPE), 2 to doxygen),
                properties = FOLD + listOf("lexer.cpp.track.preprocessor" to "0", "lexer.cpp.backquoted.strings" to "1"),
                styles = styles,
            )

            "objc" -> LexerPlan(
                lexer = "objc",
                keywordSets = mapOf(
                    0 to words(language, INSTR), 1 to words(language, TYPE), 2 to doxygen,
                    3 to words(language, INSTR2), 4 to words(language, TYPE2),
                ),
                properties = FOLD,
                styles = styles,
            )

            "tcl" -> LexerPlan(
                lexer = "tcl",
                // Notepad++ swaps the second and third lists for this lexer.
                keywordSets = mapOf(
                    0 to words(language, 0), 1 to words(language, 2), 2 to words(language, 1), 3 to words(language, 3),
                    4 to words(language, 4), 5 to words(language, 5), 6 to words(language, 6), 7 to words(language, 7),
                    8 to words(language, 8),
                ),
                properties = FOLD,
                styles = styles,
            )

            "xml" -> LexerPlan(
                lexer = "xml",
                keywordSets = mapOf(5 to words(language, INSTR)),
                properties = listOf(
                    "lexer.xml.allow.scripts" to "0", "fold" to "1", "fold.compact" to "0",
                    "fold.html" to "1", "fold.hypertext.comment" to "1",
                ),
                styles = styles,
            )

            // HTML, PHP, ASP and JSP share one lexer that also styles the embedded script languages.
            "html" -> {
                val html = registry.byName("html")
                val embeddedJs = registry.byName("javascript")
                val php = registry.byName("php")
                val asp = registry.byName("asp")
                val aspUser = theme.lexerStyles("asp").filter { it.keywordClass == INSTR }.joinToString(" ") { it.userKeywords }
                LexerPlan(
                    lexer = "hypertext",
                    keywordSets = mapOf(
                        0 to words(html, INSTR),
                        1 to words(embeddedJs, INSTR),
                        2 to (aspUser + " " + (registry.byName("vb")?.keywordsOf(INSTR) ?: "")).trim(),
                        4 to words(php, INSTR),
                        5 to words(html, INSTR2),
                    ),
                    properties = listOf(
                        "asp.default.language" to "2", "fold" to "1", "fold.compact" to "0",
                        "fold.html" to "1", "fold.hypertext.comment" to "1",
                    ),
                    styles = theme.lexerStyles("html") + theme.lexerStyles("javascript") +
                        theme.lexerStyles("php") + (asp?.let { theme.lexerStyles(it.name) } ?: emptyList()),
                    eolFilled = listOf(
                        SciLex.SCE_HJ_DEFAULT, SciLex.SCE_HJ_COMMENT, SciLex.SCE_HJ_COMMENTDOC,
                        SciLex.SCE_HPHP_DEFAULT, SciLex.SCE_HPHP_COMMENT, SciLex.SCE_HBA_DEFAULT,
                    ),
                )
            }

            "json", "json5" -> {
                val json = registry.byName("json") ?: language
                LexerPlan(
                    lexer = "json",
                    keywordSets = mapOf(0 to words(json, INSTR), 1 to words(json, INSTR2)),
                    properties = listOfNotNull(
                        "fold" to "1", "fold.compact" to "0", "lexer.json.escape.sequence" to "1",
                        ("lexer.json.allow.comments" to "1").takeIf { language.kind == "json5" },
                    ),
                    styles = theme.lexerStyles("json"),
                )
            }

            "bash" -> LexerPlan(language.lexer, mapOf(0 to words(language, INSTR)), FOLD, styles)

            "text" -> LexerPlan("", emptyMap(), emptyList(), emptyList())

            else -> LexerPlan(
                lexer = language.lexer,
                keywordSets = (0..8).filter { language.mask and (1 shl it) != 0 }.associateWith { words(language, it) },
                properties = FOLD + language.properties,
                styles = styles,
                eolFilled = if (language.lexer == "props") listOf(SciLex.SCE_PROPS_SECTION) else emptyList(),
            )
        }
    }

    /** Attaches the lexer, keywords and lexer properties to the current document. Needed once per document. */
    fun applyToDocument(sci: SciApi, plan: LexerPlan) {
        sci.setLexer(plan.lexer)
        if (plan.lexer.isEmpty()) return
        for ((key, value) in plan.properties) {
            sci.sendStrings(Sci.SCI_SETPROPERTY, key, value)
        }
        for ((set, words) in plan.keywordSets) {
            sci.sendText(Sci.SCI_SETKEYWORDS, set.toLong(), words)
        }
    }
}

/** Sizes that depend on the device, passed in so the theme code stays free of Android classes. */
class ViewMetrics(
    val fontName: String = "monospace",
    val fontSize: Int = 13,
    val foldMarginWidth: Int = 24,
    val leftPadding: Int = 4,
    val showLineNumbers: Boolean = true,
    /** Device pixels per density-independent pixel, for line widths Scintilla defines in pixels. */
    val pixelScale: Float = 1f,
)

/** Applies a Notepad++ style file to a Scintilla view. Styles belong to the view, so this runs on every tab switch. */
object ThemeApplier {
    /** Notepad++ files hold 0xRRGGBB; Scintilla wants 0xBBGGRR. */
    fun sciColour(rgb: Int): Long =
        (((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)).toLong()

    private fun setStyle(sci: SciApi, style: WordStyle) {
        val id = style.styleId.toLong()
        style.fg?.let { sci.send(Sci.SCI_STYLESETFORE, id, sciColour(it)) }
        style.bg?.let { sci.send(Sci.SCI_STYLESETBACK, id, sciColour(it)) }
        sci.send(Sci.SCI_STYLESETBOLD, id, if (style.bold) 1 else 0)
        sci.send(Sci.SCI_STYLESETITALIC, id, if (style.italic) 1 else 0)
        sci.send(Sci.SCI_STYLESETUNDERLINE, id, if (style.underline) 1 else 0)
    }

    fun apply(sci: SciApi, theme: NppTheme, plan: LexerPlan, metrics: ViewMetrics) {
        // The default style first: STYLECLEARALL then copies it to every other style.
        // Font names and sizes in Notepad++ themes are Windows fonts, so the device font settings are used instead.
        sci.sendText(Sci.SCI_STYLESETFONT, Sci.STYLE_DEFAULT.toLong(), metrics.fontName)
        sci.send(Sci.SCI_STYLESETSIZE, Sci.STYLE_DEFAULT.toLong(), metrics.fontSize.toLong())
        val default = theme.global("Default Style")
        sci.send(Sci.SCI_STYLESETFORE, Sci.STYLE_DEFAULT.toLong(), sciColour(default?.fg ?: 0x000000))
        sci.send(Sci.SCI_STYLESETBACK, Sci.STYLE_DEFAULT.toLong(), sciColour(default?.bg ?: 0xFFFFFF))
        sci.send(Sci.SCI_STYLESETBOLD, Sci.STYLE_DEFAULT.toLong(), 0)
        sci.send(Sci.SCI_STYLESETITALIC, Sci.STYLE_DEFAULT.toLong(), 0)
        sci.send(Sci.SCI_STYLECLEARALL)

        for (style in plan.styles) {
            if (style.styleId in 0..255 && style.styleId != Sci.STYLE_DEFAULT) setStyle(sci, style)
        }
        for (id in plan.eolFilled) {
            sci.send(Sci.SCI_STYLESETEOLFILLED, id.toLong(), 1)
        }

        for (name in listOf("Indent guideline style", "Brace highlight style", "Bad brace colour", "Line number margin")) {
            theme.global(name)?.let { setStyle(sci, it) }
        }
        theme.global("Current line background colour")?.bg?.let {
            sci.send(Sci.SCI_SETCARETLINEVISIBLE, 1)
            sci.send(Sci.SCI_SETCARETLINEBACK, sciColour(it))
        }
        theme.global("Selected text colour")?.bg?.let { sci.send(Sci.SCI_SETSELBACK, 1, sciColour(it)) }
        theme.global("Multi-selected text color")?.bg?.let { sci.send(Sci.SCI_SETADDITIONALSELBACK, sciColour(it)) }
        theme.global("Caret colour")?.fg?.let { sci.send(Sci.SCI_SETCARETFORE, sciColour(it)) }
        theme.global("Multi-edit carets color")?.fg?.let { sci.send(Sci.SCI_SETADDITIONALCARETFORE, sciColour(it)) }
        theme.global("Edge colour")?.fg?.let { sci.send(Sci.SCI_SETEDGECOLOUR, sciColour(it)) }
        theme.global("White space symbol")?.fg?.let { sci.send(Sci.SCI_SETWHITESPACEFORE, 1, sciColour(it)) }
        theme.global("Fold margin")?.let { style ->
            style.bg?.let { sci.send(Sci.SCI_SETFOLDMARGINCOLOUR, 1, sciColour(it)) }
            style.fg?.let { sci.send(Sci.SCI_SETFOLDMARGINHICOLOUR, 1, sciColour(it)) }
        }

        // Margins: line numbers, bookmarks, fold markers.
        sci.send(Sci.SCI_SETMARGINTYPEN, 0, Sci.SC_MARGIN_NUMBER.toLong())
        sci.send(Sci.SCI_SETMARGINTYPEN, 1, Sci.SC_MARGIN_SYMBOL.toLong())
        sci.send(Sci.SCI_SETMARGINMASKN, 1, (1L shl BOOKMARK_MARKER))
        sci.send(Sci.SCI_SETMARGINWIDTHN, 1, 0)
        sci.send(Sci.SCI_SETMARGINTYPEN, 2, Sci.SC_MARGIN_SYMBOL.toLong())
        sci.send(Sci.SCI_SETMARGINMASKN, 2, Sci.SC_MASK_FOLDERS.toLong() and 0xFFFFFFFFL)
        sci.send(Sci.SCI_SETMARGINWIDTHN, 2, metrics.foldMarginWidth.toLong())
        sci.send(Sci.SCI_SETMARGINSENSITIVEN, 2, 1)
        sci.send(Sci.SCI_SETMARGINLEFT, 0, metrics.leftPadding.toLong())
        updateLineNumberWidth(sci, metrics.showLineNumbers)

        // Notepad++ swaps the two colours of the "Fold" style: its background outlines the box.
        val fold = theme.global("Fold")
        val boxFill = sciColour(fold?.bg ?: 0xF3F3F3)
        val boxLine = sciColour(fold?.fg ?: 0x808080)
        val active = sciColour(theme.global("Fold active")?.fg ?: 0xFF0000)
        for ((marker, symbol) in FOLD_MARKERS) {
            sci.send(Sci.SCI_MARKERDEFINE, marker.toLong(), symbol.toLong())
            sci.send(Sci.SCI_MARKERSETFORE, marker.toLong(), boxFill)
            sci.send(Sci.SCI_MARKERSETBACK, marker.toLong(), boxLine)
            sci.send(Sci.SCI_MARKERSETBACKSELECTED, marker.toLong(), active)
            // Stroke width is in hundredths of a pixel; one pixel is too faint on dense screens.
            sci.send(Sci.SCI_MARKERSETSTROKEWIDTH, marker.toLong(), (metrics.pixelScale * 100).toLong().coerceAtLeast(100))
        }
        sci.send(
            Sci.SCI_SETAUTOMATICFOLD,
            (Sci.SC_AUTOMATICFOLD_SHOW or Sci.SC_AUTOMATICFOLD_CLICK or Sci.SC_AUTOMATICFOLD_CHANGE).toLong(),
        )
        Marks.setup(sci, theme)
        // The bookmark margin is hidden, so a bookmark shows as a tint on its line that works on any theme.
        sci.send(Sci.SCI_MARKERDEFINE, BOOKMARK_MARKER.toLong(), Sci.SC_MARK_BOOKMARK.toLong())
        sci.send(Sci.SCI_MARKERSETFORE, BOOKMARK_MARKER.toLong(), sciColour(0x2020A0))
        sci.send(Sci.SCI_MARKERSETBACK, BOOKMARK_MARKER.toLong(), sciColour(0x3A7BD5))
        sci.send(Sci.SCI_MARKERSETALPHA, BOOKMARK_MARKER.toLong(), 70)
    }

    /** Sizes the line number margin for the document's line count, as Notepad++ does. */
    fun updateLineNumberWidth(sci: SciApi, show: Boolean) {
        if (!show) {
            sci.send(Sci.SCI_SETMARGINWIDTHN, 0, 0)
            return
        }
        val lines = sci.send(Sci.SCI_GETLINECOUNT).coerceAtLeast(1)
        val digits = maxOf(3, lines.toString().length)
        val width = sci.sendText(Sci.SCI_TEXTWIDTH, Sci.STYLE_LINENUMBER.toLong(), "8".repeat(digits) + "8")
        if (width != sci.send(Sci.SCI_GETMARGINWIDTHN, 0)) {
            sci.send(Sci.SCI_SETMARGINWIDTHN, 0, width)
        }
    }

    /** Notepad++ uses marker 20 for bookmarks. */
    const val BOOKMARK_MARKER = 20

    private val FOLD_MARKERS = listOf(
        Sci.SC_MARKNUM_FOLDEROPEN to Sci.SC_MARK_BOXMINUS,
        Sci.SC_MARKNUM_FOLDER to Sci.SC_MARK_BOXPLUS,
        Sci.SC_MARKNUM_FOLDERSUB to Sci.SC_MARK_VLINE,
        Sci.SC_MARKNUM_FOLDERTAIL to Sci.SC_MARK_LCORNER,
        Sci.SC_MARKNUM_FOLDEREND to Sci.SC_MARK_BOXPLUSCONNECTED,
        Sci.SC_MARKNUM_FOLDEROPENMID to Sci.SC_MARK_BOXMINUSCONNECTED,
        Sci.SC_MARKNUM_FOLDERMIDTAIL to Sci.SC_MARK_TCORNER,
    )
}
