package host

import io.github.revelationnow.pocketpadpp.core.ApiIndex
import io.github.revelationnow.pocketpadpp.core.Compare
import io.github.revelationnow.pocketpadpp.core.Completion
import io.github.revelationnow.pocketpadpp.core.Diff
import io.github.revelationnow.pocketpadpp.core.DiffKind
import io.github.revelationnow.pocketpadpp.core.DiffOptions
import io.github.revelationnow.pocketpadpp.core.DiffRow
import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.EditorOptions
import io.github.revelationnow.pocketpadpp.core.BoostPattern
import io.github.revelationnow.pocketpadpp.core.FunctionEntry
import io.github.revelationnow.pocketpadpp.core.FunctionParser
import io.github.revelationnow.pocketpadpp.core.KeyBinding
import io.github.revelationnow.pocketpadpp.core.ShortcutTable
import io.github.revelationnow.pocketpadpp.core.Shortcuts
import io.github.revelationnow.pocketpadpp.core.StyleChange
import io.github.revelationnow.pocketpadpp.core.StyleOverrides
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.LanguageRegistry
import io.github.revelationnow.pocketpadpp.core.Hashes
import io.github.revelationnow.pocketpadpp.core.HexBuffer
import io.github.revelationnow.pocketpadpp.core.Invisibles
import io.github.revelationnow.pocketpadpp.core.LanguageSetup
import io.github.revelationnow.pocketpadpp.core.LuaEngine
import io.github.revelationnow.pocketpadpp.core.NppCommands
import io.github.revelationnow.pocketpadpp.core.MacroStep
import io.github.revelationnow.pocketpadpp.core.Macros
import io.github.revelationnow.pocketpadpp.core.Marks
import io.github.revelationnow.pocketpadpp.core.NppTheme
import io.github.revelationnow.pocketpadpp.core.SciApi
import io.github.revelationnow.pocketpadpp.core.SearchOptions
import io.github.revelationnow.pocketpadpp.core.Searcher
import io.github.revelationnow.pocketpadpp.core.Session
import io.github.revelationnow.pocketpadpp.core.ProjectNode
import io.github.revelationnow.pocketpadpp.core.ProjectStore
import io.github.revelationnow.pocketpadpp.core.RunCommand
import io.github.revelationnow.pocketpadpp.core.SessionEntry
import io.github.revelationnow.pocketpadpp.core.SessionStore
import io.github.revelationnow.pocketpadpp.core.TextEncodings
import io.github.revelationnow.pocketpadpp.core.UserLanguages
import io.github.revelationnow.pocketpadpp.core.TextTransforms
import io.github.revelationnow.pocketpadpp.core.ThemeApplier
import io.github.revelationnow.pocketpadpp.core.ViewMetrics
import io.github.revelationnow.pocketpadpp.core.sendText
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import io.github.revelationnow.pocketpadpp.scintilla.SciHost
import io.github.revelationnow.pocketpadpp.scintilla.SciLex
import io.github.revelationnow.pocketpadpp.scintilla.ScintillaNative
import java.io.File
import kotlin.system.exitProcess

/** Tests for the application logic that has no Android dependencies, run against the real native editor. */

private var checks = 0
private var failures = 0

private fun eq(expected: Any?, actual: Any?, what: String) {
    checks++
    if (expected != actual) {
        failures++
        println("FAIL $what\n     expected: $expected\n     actual:   $actual")
    }
}

private fun ok(condition: Boolean, what: String) = eq(true, condition, what)

private object QuietHost : SciHost {
    val recorded = ArrayList<MacroStep>()

    override fun macroRecord(message: Int, wParam: Long, lParam: Long, text: ByteArray?) {
        recorded += MacroStep(message, wParam, lParam, text)
    }

    override fun invalidate() {}
    override fun notify(code: Int, position: Long, ch: Int, modifiers: Int, modificationType: Int,
                        length: Long, linesAdded: Long, line: Long, margin: Int, updated: Int) {}
    override fun startTimer(reason: Int, millis: Int) {}
    override fun cancelTimer(reason: Int) {}
    override fun setIdle(on: Boolean) {}
    override fun queueWork() {}
    override fun setClipboard(text: String) {}
    override fun getClipboard(): String = ""
}

private class HostSci : SciApi {
    val ptr: Long = ScintillaNative.create(QuietHost)
    override fun send(msg: Int, wParam: Long, lParam: Long): Long = ScintillaNative.send(ptr, msg, wParam, lParam)
    override fun sendBytes(msg: Int, wParam: Long, bytes: ByteArray): Long = ScintillaNative.sendString(ptr, msg, wParam, bytes)
    override fun sendStrings(msg: Int, first: String, second: String): Long =
        ScintillaNative.sendStrings(ptr, msg, first.toByteArray(), second.toByteArray())
    override fun range(start: Long, end: Long): ByteArray = ScintillaNative.getRange(ptr, start, end)
    override fun setLexer(name: String): Boolean = ScintillaNative.setLexer(ptr, name)

    var text: String
        get() = String(range(0, send(Sci.SCI_GETLENGTH)), Charsets.UTF_8)
        set(value) {
            sendText(Sci.SCI_SETTEXT, 0, value)
            send(Sci.SCI_EMPTYUNDOBUFFER)
            send(Sci.SCI_GOTOPOS, 0)
        }
    val selection: String get() = "${send(Sci.SCI_GETSELECTIONSTART)}-${send(Sci.SCI_GETSELECTIONEND)}"
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun testEncodings() {
    val e = TextEncodings
    eq(e.UTF8, e.detect("plain ascii".toByteArray()), "ascii is UTF-8")
    eq(e.UTF8, e.detect("héllo 日本 😀".toByteArray()), "valid UTF-8")
    eq(e.UTF8_BOM, e.detect(bytes(0xEF, 0xBB, 0xBF) + "x".toByteArray()), "UTF-8 BOM")
    eq(e.UTF16_LE, e.detect(bytes(0xFF, 0xFE, 0x41, 0x00)), "UTF-16 LE BOM")
    eq(e.UTF16_BE, e.detect(bytes(0xFE, 0xFF, 0x00, 0x41)), "UTF-16 BE BOM")
    eq(e.ANSI, e.detect(bytes(0x63, 0x61, 0x66, 0xE9)), "latin-1 bytes fall back to ANSI")
    eq(e.UTF16_LE_NO_BOM, e.detect("hello world, no mark".toByteArray(Charsets.UTF_16LE)), "UTF-16 LE without BOM")
    eq(e.UTF16_BE_NO_BOM, e.detect("hello world, no mark".toByteArray(Charsets.UTF_16BE)), "UTF-16 BE without BOM")
    eq(e.UTF8, e.detect(ByteArray(0)), "empty file is UTF-8")
    ok(!e.isValidUtf8(bytes(0xC3)), "truncated UTF-8 is invalid")
    ok(!e.isValidUtf8(bytes(0xED, 0xA0, 0x80)), "encoded surrogate is invalid")
    ok(!e.isValidUtf8(bytes(0xC0, 0xAF)), "overlong UTF-8 is invalid")

    val sample = "Grüße, мир, 日本語 😀\r\nsecond"
    for (encoding in listOf(e.UTF8, e.UTF8_BOM, e.UTF16_LE, e.UTF16_BE)) {
        val file = e.fromUtf8(sample.toByteArray(), encoding)
        eq(encoding, e.detect(file), "detect what ${encoding.label} writes")
        eq(sample, String(e.toUtf8(file, encoding), Charsets.UTF_8), "round trip through ${encoding.label}")
    }
    val cyrillic = e.byId("windows-1251")!!
    eq("привет", String(e.toUtf8(e.fromUtf8("привет".toByteArray(), cyrillic), cyrillic)), "round trip through Windows-1251")
    eq(6, e.fromUtf8("привет".toByteArray(), cyrillic).size, "Windows-1251 is one byte per letter")
    ok(!e.canEncode("日本".toByteArray(), cyrillic), "Windows-1251 cannot hold Japanese")
    ok(e.canEncode("日本".toByteArray(), e.UTF16_LE), "UTF-16 holds anything")
    eq("caf�", String(e.toUtf8(bytes(0x63, 0x61, 0x66, 0xE9), e.UTF8)), "bad bytes read as UTF-8 become replacement characters")
    val unsupported = e.characterSets.filter { !it.isSupported }.map { it.label }
    println("encodings: ${e.characterSets.size} character sets, not available in this JVM: $unsupported")

    eq(Eol.CRLF, Eol.detect("a\r\nb\r\nc\n".toByteArray()), "mostly CRLF")
    eq(Eol.LF, Eol.detect("a\nb\n".toByteArray()), "LF")
    eq(Eol.CR, Eol.detect("a\rb\r".toByteArray()), "CR")
    eq(null, Eol.detect("no line break".toByteArray()), "no line ending")
}

private fun testLanguages(assets: File): Pair<LanguageRegistry, NppTheme> {
    val registry = File(assets, "languages.tsv").inputStream().use { table ->
        File(assets, "langs.model.xml").inputStream().use { xml -> LanguageRegistry.load(table, xml) }
    }
    val theme = File(assets, "stylers.model.xml").inputStream().use { NppTheme.load(it) }
    ok(registry.languages.size >= 90, "languages loaded: ${registry.languages.size}")
    eq("cpp", registry.forFileName("main.cpp").name, "cpp by extension")
    eq("cpp", registry.forFileName("/sdcard/dir.v2/Header.HPP").name, "extension match ignores case and directories")
    eq("python", registry.forFileName("script.py").name, "python by extension")
    eq("javascript.js", registry.forFileName("app.js").name, "javascript by extension")
    eq("makefile", registry.forFileName("Makefile").name, "makefile by name")
    eq("cmake", registry.forFileName("CMakeLists.txt").name, "cmake by name")
    eq("normal", registry.forFileName("README").name, "no extension is normal text")
    eq("normal", registry.forFileName("notes.unknownext").name, "unknown extension is normal text")
    ok(" if " in " ${registry.byName("cpp")!!.keywordsOf(0)} ", "cpp instruction words loaded")
    eq("//", registry.byName("cpp")!!.commentLine, "cpp line comment marker")
    ok(registry.menu.none { it.name == "javascript" }, "embedded JavaScript is not in the menu")
    val word = theme.lexerStyles("cpp").first { it.name == "INSTRUCTION WORD" }
    eq(Triple(5, 0x0000FF, true), Triple(word.styleId, word.fg, word.bold), "cpp keyword style")
    eq(0, word.keywordClass, "cpp keyword style feeds keyword class instre1")
    eq(0xFFFFFF, theme.global("Default Style")?.bg, "default background")
    val withoutStyles = registry.menu.filter { language ->
        val styledAs = when (language.kind) {
            "js" -> "javascript.js"
            "json", "json5" -> "json"
            "html" -> "html"
            else -> language.name
        }
        language.kind != "text" && theme.lexerStyles(styledAs).isEmpty()
    }
    eq(emptyList<String>(), withoutStyles.map { it.name }, "every language has styles in the theme")
    // Every bundled Notepad++ theme must load and say whether it is dark.
    val themes = File(assets, "themes").listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }
    ok(themes.size >= 20, "bundled themes found: ${themes.size}")
    val dark = ArrayList<String>()
    for (file in themes) {
        val loaded = file.inputStream().use { NppTheme.load(it) }
        ok(loaded.global("Default Style")?.bg != null, "${file.name} has a default background")
        ok(loaded.lexerStyles("cpp").isNotEmpty(), "${file.name} styles C++")
        if (loaded.isDark) dark += file.name.removeSuffix(".xml")
    }
    ok(!theme.isDark, "the default theme is light")
    ok("DarkModeDefault" in dark && "Obsidian" in dark && "Solarized-light" !in dark, "dark themes are recognised: $dark")
    return registry to theme
}

private fun testLexers(registry: LanguageRegistry, theme: NppTheme) {
    val sci = HostSci()
    val missing = ArrayList<String>()
    for (language in registry.languages) {
        val plan = LanguageSetup.plan(language, registry, theme)
        if (plan.lexer.isNotEmpty() && !sci.setLexer(plan.lexer)) missing += "${language.name}:${plan.lexer}"
    }
    eq(emptyList<String>(), missing, "every Notepad++ language has its lexer built in")

    fun styleAt(text: String, needle: String): Long = sci.send(Sci.SCI_GETSTYLEAT, text.toByteArray().size.toLong().let {
        text.substring(0, text.indexOf(needle)).toByteArray().size.toLong()
    })

    fun show(languageName: String, text: String) {
        val plan = LanguageSetup.plan(registry.byName(languageName)!!, registry, theme)
        LanguageSetup.applyToDocument(sci, plan)
        ThemeApplier.apply(sci, theme, plan, ViewMetrics())
        sci.text = text
        sci.send(Sci.SCI_COLOURISE, 0, -1)
    }

    val cpp = "// note\nint main() {\n\treturn 42;\n}\n"
    show("cpp", cpp)
    eq(SciLex.SCE_C_COMMENTLINE.toLong(), styleAt(cpp, "// note"), "cpp comment")
    eq(SciLex.SCE_C_WORD2.toLong(), styleAt(cpp, "int"), "cpp type word uses the second keyword set")
    eq(SciLex.SCE_C_WORD.toLong(), styleAt(cpp, "return"), "cpp instruction word")
    eq(SciLex.SCE_C_NUMBER.toLong(), styleAt(cpp, "42"), "cpp number")
    eq(ThemeApplier.sciColour(0x0000FF), sci.send(Sci.SCI_STYLEGETFORE, SciLex.SCE_C_WORD.toLong()), "keyword colour from the Notepad++ theme")
    eq(1L, sci.send(Sci.SCI_STYLEGETBOLD, SciLex.SCE_C_WORD.toLong()), "keyword is bold")
    eq(ThemeApplier.sciColour(0x008000), sci.send(Sci.SCI_STYLEGETFORE, SciLex.SCE_C_COMMENTLINE.toLong()), "comment colour")
    ok(sci.send(Sci.SCI_GETMARGINWIDTHN, 0) > 0, "line number margin has a width")
    ok(sci.send(Sci.SCI_GETFOLDLEVEL, 1) and Sci.SC_FOLDLEVELHEADERFLAG.toLong() != 0L, "cpp folding is on")

    val py = "def f(x):\n    return None  # c\n"
    show("python", py)
    eq(SciLex.SCE_P_WORD.toLong(), styleAt(py, "def"), "python keyword")
    eq(SciLex.SCE_P_COMMENTLINE.toLong(), styleAt(py, "# c"), "python comment")

    val html = "<html><body class=\"a\"><script>var a = 1;</script><?php echo 2; ?></body></html>"
    show("html", html)
    eq(SciLex.SCE_H_TAG.toLong(), styleAt(html, "html"), "html tag")
    eq(SciLex.SCE_HJ_KEYWORD.toLong(), styleAt(html, "var"), "javascript keyword inside html")
    eq(SciLex.SCE_HPHP_WORD.toLong(), styleAt(html, "echo"), "php keyword inside html")

    val json = "{\"k\": true, \"n\": 1}"
    show("json", json)
    eq(SciLex.SCE_JSON_PROPERTYNAME.toLong(), styleAt(json, "\"k\""), "json property name")
    eq(SciLex.SCE_JSON_KEYWORD.toLong(), styleAt(json, "true"), "json keyword")

    val xml = "<?xml version=\"1.0\"?><a b=\"c\">t</a>"
    show("xml", xml)
    eq(SciLex.SCE_H_TAG.toLong(), styleAt(xml, "a b"), "xml tag")

    show("normal", "plain text")
    eq(0L, styleAt("plain text", "plain"), "normal text is unstyled")
    ScintillaNative.destroy(sci.ptr)
}

private fun testSearch() {
    val sci = HostSci()
    val plain = SearchOptions()
    sci.text = "foo bar Foo foobar\nfoo"
    eq(true, Searcher.find(sci, "foo", plain).found, "find first")
    eq("0-3", sci.selection, "first match selected")
    Searcher.find(sci, "foo", plain)
    eq("8-11", sci.selection, "case-insensitive second match")
    Searcher.find(sci, "foo", plain.copy(matchCase = true))
    eq("12-15", sci.selection, "match case skips Foo")
    Searcher.find(sci, "foo", plain.copy(matchCase = true, wholeWord = true))
    eq("19-22", sci.selection, "whole word skips foobar")
    val wrapped = Searcher.find(sci, "foo", plain.copy(matchCase = true, wholeWord = true))
    eq(true to "0-3", wrapped.wrapped to sci.selection, "wraps to the top")
    eq(false, Searcher.find(sci, "bar", plain.copy(wrapAround = false), forward = false).found, "nothing before the first word without wrap")
    Searcher.find(sci, "foo", plain, forward = false)
    eq("19-22", sci.selection, "backward search wraps to the end")
    Searcher.find(sci, "foo", plain, forward = false)
    eq("12-15", sci.selection, "backward search finds the previous match")
    eq(false, Searcher.find(sci, "absent", plain).found, "missing text is not found")
    eq(4, Searcher.count(sci, "foo", plain), "count all")
    eq(2, Searcher.count(sci, "foo", plain.copy(matchCase = true, wholeWord = true)), "count whole words with case")

    val regex = SearchOptions(regex = true)
    sci.send(Sci.SCI_GOTOPOS, 0)
    Searcher.find(sci, "b[a-z]+", regex)
    eq("4-7", sci.selection, "regex match")
    eq(false, Searcher.find(sci, "(unclosed", regex).found, "a broken regex finds nothing")
    eq(2, Searcher.count(sci, "^foo", regex.copy(matchCase = true)), "anchored regex count")

    sci.text = "one two one"
    sci.send(Sci.SCI_GOTOPOS, 0)
    Searcher.find(sci, "one", plain)
    val (replaced, next) = Searcher.replace(sci, "one", "1", plain)
    eq(Triple(true, "1 two one", "6-9"), Triple(replaced, sci.text, sci.selection), "replace then select the next match")
    eq(true, next.found, "replace reports the next match")
    sci.send(Sci.SCI_GOTOPOS, 0)
    eq(false, Searcher.replace(sci, "one", "x", plain).first, "replace without a selected match only finds")
    eq("1 two one", sci.text, "nothing replaced when the selection is not a match")

    sci.text = "a.b.c\nd.e"
    eq(3, Searcher.replaceAll(sci, ".", "::", plain), "replace all, plain text")
    eq("a::b::c\nd::e", sci.text, "replace all result")
    sci.send(Sci.SCI_UNDO)
    eq("a.b.c\nd.e", sci.text, "replace all is one undo step")
    eq(2, Searcher.replaceAll(sci, "^", "> ", regex), "replace all with an empty-match regex")
    eq("> a.b.c\n> d.e", sci.text, "line prefix added once per line")
    sci.text = "john smith, jane doe"
    eq(2, Searcher.replaceAll(sci, "(\\w+) (\\w+)", "$2 \\1", regex), "regex groups, both \$n and \\n forms")
    eq("smith john, doe jane", sci.text, "groups swapped")
    sci.text = "naïve café naïve"
    eq(2, Searcher.replaceAll(sci, "naïve", "😀", plain), "replace all with non-ASCII text")
    eq("😀 café 😀", sci.text, "unicode replace result")
    sci.text = "aaa"
    eq(3, Searcher.replaceAll(sci, "a", "aa", plain), "replacement containing the search text terminates")
    eq("aaaaaa", sci.text, "each original match replaced once")
    sci.send(Sci.SCI_SETREADONLY, 1)
    eq(0, Searcher.replaceAll(sci, "a", "b", plain), "read-only documents are left alone")
    ScintillaNative.destroy(sci.ptr)
}

private fun testEditOps() {
    val sci = HostSci()
    sci.text = "int a;\n\t// b\n\n  c();\n"
    sci.send(Sci.SCI_SETSEL, 0, sci.send(Sci.SCI_GETLENGTH))
    ok(EditOps.toggleLineComment(sci, "//"), "toggle comment runs")
    eq("// int a;\n\tb\n\n  // c();\n", sci.text, "comment toggled per line, blank lines untouched")
    sci.send(Sci.SCI_UNDO)
    eq("int a;\n\t// b\n\n  c();\n", sci.text, "toggle comment is one undo step")
    ok(!EditOps.toggleLineComment(sci, ""), "languages without a line comment are left alone")

    sci.text = "a  \nb\t \nc\n  \n"
    eq(3, EditOps.trimTrailingSpace(sci), "trim trailing space")
    eq("a\nb\nc\n\n", sci.text, "trailing space removed")

    sci.text = "pear\napple\nfig"
    EditOps.sortLines(sci, descending = false)
    eq("apple\nfig\npear", sci.text, "sort whole document ascending")
    sci.text = "z\npear\napple\nfig\na\n"
    sci.send(Sci.SCI_SETSEL, 2, sci.send(Sci.SCI_POSITIONFROMLINE, 4))
    EditOps.sortLines(sci, descending = true)
    eq("z\npear\nfig\napple\na\n", sci.text, "sort only the selected lines, descending")

    sci.send(Sci.SCI_SETEOLMODE, Sci.SC_EOL_CRLF.toLong())
    sci.text = "a\r\n\r\n \t\r\nb"
    EditOps.removeEmptyLines(sci, includeBlank = false)
    eq("a\r\n \t\r\nb", sci.text, "remove empty lines keeps CRLF and blank-only lines")
    EditOps.removeEmptyLines(sci, includeBlank = true)
    eq("a\r\nb", sci.text, "remove lines holding only blanks")
    sci.send(Sci.SCI_SETEOLMODE, Sci.SC_EOL_LF.toLong())
    sci.text = "x\ny\nx\nx\ny"
    EditOps.removeDuplicateLines(sci)
    eq("x\ny", sci.text, "remove duplicate lines")
    sci.text = "1\n2\n3"
    EditOps.reverseLines(sci)
    eq("3\n2\n1", sci.text, "reverse line order")
    sci.text = "\n\n\nkeep"
    sci.send(Sci.SCI_SETSEL, 0, 2)
    EditOps.removeEmptyLines(sci, includeBlank = false)
    eq("\nkeep", sci.text, "removing every selected line takes their line breaks too")
    sci.text = "one\ntwo\nthree"
    sci.send(Sci.SCI_SETSEL, 0, 6)
    EditOps.joinLines(sci)
    eq("one two\nthree", sci.text, "join selected lines")

    sci.text = "f(a[1]) }"
    sci.send(Sci.SCI_GOTOPOS, 2)
    eq(6L, EditOps.highlightBraces(sci), "brace before the caret matches")
    ok(EditOps.goToMatchingBrace(sci), "go to matching brace")
    eq(6L, sci.send(Sci.SCI_GETCURRENTPOS), "caret moved to the partner")
    sci.send(Sci.SCI_GOTOPOS, 8)
    eq(-1L, EditOps.highlightBraces(sci), "unbalanced brace has no partner")
    sci.send(Sci.SCI_GOTOPOS, 0)
    eq(-1L, EditOps.highlightBraces(sci), "no brace at the caret")

    sci.send(Sci.SCI_SETUSETABS, 1)
    sci.send(Sci.SCI_SETTABWIDTH, 4)
    sci.text = "\tindented"
    sci.send(Sci.SCI_DOCUMENTEND)
    ScintillaNative.insertText(sci.ptr, "\n")
    EditOps.autoIndent(sci, '\n'.code)
    ScintillaNative.insertText(sci.ptr, "x")
    eq("\tindented\n\tx", sci.text, "new line keeps the indentation of the line above")

    sci.text = "two words\nthree more words 😀"
    eq(Triple(28L, 5L, 2L), EditOps.summary(sci), "summary counts characters, words and lines")
    ScintillaNative.destroy(sci.ptr)
}

private fun testTransforms() {
    val t = TextTransforms
    eq("Hello World, Don't Stop 3rd", t.properCase("hELLO wORLD, don't stop 3rd", blend = false), "proper case")
    eq("HELLO WORLD McDonald", t.properCase("hELLO wORLD mcDonald", blend = true), "proper case blend only raises first letters")
    eq("One two. Three! Four?\nFive", t.sentenceCase("one TWO. three! FOUR?\nfive", blend = false), "sentence case")
    eq("One TWO. Three", t.sentenceCase("one TWO. three", blend = true), "sentence case blend")
    eq("3.14 is pi. Yes", t.sentenceCase("3.14 is pi. yes", blend = false), "a decimal point does not end a sentence")
    eq("hELLO wORLD 1é", t.invertCase("Hello World 1É"), "invert case")
    eq("abc def", t.randomCase("ABC def", java.util.Random(1)).lowercase(), "random case only changes case")

    eq("a   b", t.tabsToSpaces("a\tb", 4), "tab to space reaches the next stop")
    eq("        x", t.tabsToSpaces("\t\tx", 4), "leading tabs to spaces")
    eq("\t\tx", t.spacesToTabs("        x", 4, leadingOnly = true), "leading spaces to tabs")
    eq("\t  x  y", t.spacesToTabs("      x  y", 4, leadingOnly = true), "leading only leaves inner blanks, keeps the remainder")
    eq("a\tb c", t.spacesToTabs("a   b c", 4, leadingOnly = false), "inner run reaching a tab stop becomes a tab; single spaces stay")
    eq("\tx", t.spacesToTabs("  \tx", 4, leadingOnly = true), "mixed indentation is normalised")
    eq("x", t.trimBoth(" \t x\t "), "trim both")

    val lines = listOf("b", "A", "c", "B", "a")
    eq(listOf("A", "B", "a", "b", "c"), t.sort(lines, TextTransforms.Sort.LEXICOGRAPHIC, false), "lexicographic sort")
    eq(listOf("A", "a", "b", "B", "c"), t.sort(lines, TextTransforms.Sort.IGNORE_CASE, false), "case-insensitive sort is stable")
    eq(listOf("c", "B", "b", "a", "A"), t.sort(lines, TextTransforms.Sort.IGNORE_CASE, true), "case-insensitive descending")
    eq(listOf("x", "-5", "2 apples", "10", "33"), t.sort(listOf("10", "2 apples", "x", "33", "-5"), TextTransforms.Sort.INTEGER, false), "integer sort, non-numbers first")
    eq(listOf("1,5", "2", "10,25"), t.sort(listOf("10,25", "2", "1,5"), TextTransforms.Sort.DECIMAL_COMMA, false), "decimal comma sort")
    eq(listOf("0.5", "1.25", "3"), t.sort(listOf("3", "0.5", "1.25"), TextTransforms.Sort.DECIMAL_DOT, false), "decimal dot sort")
    eq(listOf("3", "1.25", "0.5"), t.sort(listOf("3", "0.5", "1.25"), TextTransforms.Sort.DECIMAL_DOT, true), "decimal dot descending")
    eq(listOf("é", "aa", "bbb"), t.sort(listOf("bbb", "é", "aa"), TextTransforms.Sort.LENGTH, false), "length sort counts characters, not bytes")
    eq(listOf("a", "b", "a"), t.removeConsecutiveDuplicates(listOf("a", "a", "b", "b", "a")), "consecutive duplicates")
}

private fun testMoreEditOps() {
    val sci = HostSci()
    sci.text = "hello WORLD"
    sci.send(Sci.SCI_SETSEL, 0, 11)
    ok(EditOps.transformSelection(sci) { TextTransforms.invertCase(it) }, "transform selection runs")
    eq("HELLO world" to "0-11", sci.text to sci.selection, "case converted and still selected")
    sci.send(Sci.SCI_GOTOPOS, 0)
    ok(!EditOps.transformSelection(sci) { it.uppercase() }, "nothing selected, nothing converted")

    sci.text = "b 2\nA 1\na 3"
    EditOps.sortLines(sci, descending = false, kind = TextTransforms.Sort.IGNORE_CASE)
    eq("A 1\na 3\nb 2", sci.text, "sort ignoring case through the editor")
    sci.text = "x\nx\ny\nx"
    EditOps.removeConsecutiveDuplicateLines(sci)
    eq("x\ny\nx", sci.text, "remove consecutive duplicate lines")
    sci.text = "1\n2\n3\n4"
    EditOps.shuffleLines(sci, java.util.Random(7))
    eq(listOf("1", "2", "3", "4"), sci.text.split("\n").sorted(), "shuffle keeps every line")

    sci.send(Sci.SCI_SETTABWIDTH, 4)
    sci.text = "\ta\n        b"
    EditOps.tabsToSpaces(sci)
    eq("    a\n        b", sci.text, "tab to space")
    EditOps.spacesToTabs(sci, leadingOnly = true)
    eq("\ta\n\t\tb", sci.text, "space to tab, leading")
    sci.text = "  a  \n b \n\tc"
    EditOps.mapLines(sci) { TextTransforms.trimLeading(it) }
    eq("a  \nb \nc", sci.text, "trim leading space")
    sci.text = " one \n two \nthree"
    EditOps.eolToSpace(sci, trim = true)
    eq("one two three", sci.text, "trim both and EOL to space")
    sci.text = "a\nb\nc"
    sci.send(Sci.SCI_SETSEL, 0, 3)
    EditOps.eolToSpace(sci, trim = false)
    eq("a b\nc", sci.text, "EOL to space on the selection only")

    sci.text = "int a;\n  // b\n"
    sci.send(Sci.SCI_SETSEL, 0, sci.send(Sci.SCI_GETLENGTH))
    EditOps.setLineComment(sci, "//", comment = true)
    eq("// int a;\n  // // b\n", sci.text, "single line comment always adds")
    sci.send(Sci.SCI_SETSEL, 0, sci.send(Sci.SCI_GETLENGTH))
    EditOps.setLineComment(sci, "//", comment = false)
    eq("int a;\n  // b\n", sci.text, "single line uncomment removes one level")
    sci.text = "a = b + c;"
    sci.send(Sci.SCI_SETSEL, 4, 9)
    ok(EditOps.streamComment(sci, "/*", "*/"), "block comment")
    eq("a = /* b + c */;" to "4-15", sci.text to sci.selection, "selection wrapped and still selected")
    ok(EditOps.streamUncomment(sci, "/*", "*/"), "block uncomment")
    eq("a = b + c;" to "4-9", sci.text to sci.selection, "block comment removed")
    ok(!EditOps.streamUncomment(sci, "/*", "*/"), "nothing to uncomment")

    sci.text = "first\nsecond"
    sci.send(Sci.SCI_GOTOPOS, 8)
    EditOps.insertBlankLine(sci, above = true)
    eq("first\n\nsecond" to 6L, sci.text to sci.send(Sci.SCI_GETCURRENTPOS), "blank line above, caret on it")
    sci.send(Sci.SCI_GOTOPOS, 9)
    EditOps.insertBlankLine(sci, above = false)
    eq("first\n\nsecond\n" to 14L, sci.text to sci.send(Sci.SCI_GETCURRENTPOS), "blank line below, caret on it")

    sci.send(Sci.SCI_SETMULTIPLESELECTION, 1)
    sci.send(Sci.SCI_SETADDITIONALSELECTIONTYPING, 1)
    sci.text = "cat Cat cats cat"
    sci.send(Sci.SCI_SETSEL, 0, 3)
    EditOps.multiSelectAll(sci, matchCase = false, wholeWord = false)
    eq(4L, sci.send(Sci.SCI_GETSELECTIONS), "multi-select all, ignoring case and word boundaries")
    sci.send(Sci.SCI_SETSEL, 0, 3)
    EditOps.multiSelectAll(sci, matchCase = true, wholeWord = true)
    eq(2L, sci.send(Sci.SCI_GETSELECTIONS), "multi-select all, match case and whole word")
    ScintillaNative.insertText(sci.ptr, "dog")
    eq("dog Cat cats dog", sci.text, "typing replaces every selected occurrence")
    sci.text = "cat Cat cats cat"
    sci.send(Sci.SCI_GOTOPOS, 1)
    EditOps.multiSelectAll(sci, matchCase = true, wholeWord = true)
    eq(2L, sci.send(Sci.SCI_GETSELECTIONS), "multi-select all with only a caret uses the word under it")
    sci.send(Sci.SCI_GOTOPOS, 1)
    EditOps.multiSelectNext(sci, matchCase = true, wholeWord = true)
    eq(1L to "0-3", sci.send(Sci.SCI_GETSELECTIONS) to sci.selection, "multi-select next with only a caret first selects the word")
    EditOps.multiSelectNext(sci, matchCase = true, wholeWord = true)
    eq(2L, sci.send(Sci.SCI_GETSELECTIONS), "and then adds the next occurrence")
    sci.send(Sci.SCI_SETSEL, 0, 3)
    EditOps.multiSelectNext(sci, matchCase = false, wholeWord = false)
    eq(2L, sci.send(Sci.SCI_GETSELECTIONS), "multi-select next adds one")
    EditOps.multiSelectUndo(sci)
    eq(1L to "0-3", sci.send(Sci.SCI_GETSELECTIONS) to sci.selection, "undo the latest multi-select")
    sci.text = "ab ab ab"
    sci.send(Sci.SCI_SETSEL, 0, 2)
    EditOps.multiSelectNext(sci, matchCase = true, wholeWord = false)
    EditOps.multiSelectSkip(sci, matchCase = true, wholeWord = false)
    val picked = (0 until sci.send(Sci.SCI_GETSELECTIONS)).map { sci.send(Sci.SCI_GETSELECTIONNSTART, it) }.sorted()
    eq(listOf(0L, 6L), picked, "skip moves the latest selection to the following occurrence")
    sci.send(Sci.SCI_SETSEL, 0, 0)

    sci.setLexer("cpp")
    sci.sendStrings(Sci.SCI_SETPROPERTY, "fold", "1")
    sci.text = "void a() {\n\tif (x) {\n\t\ty();\n\t}\n}\nvoid b() {\n\tz();\n}\n"
    EditOps.foldLevel(sci, 2, collapse = true)
    eq(listOf(1L), EditOps.foldedLines(sci), "fold level 2 collapses only the inner block")
    EditOps.foldLevel(sci, 1, collapse = true)
    eq(listOf(0L, 1L, 5L), EditOps.foldedLines(sci), "fold level 1 collapses the functions")
    val folded = EditOps.foldedLines(sci)
    sci.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_EXPAND.toLong())
    eq(emptyList<Long>(), EditOps.foldedLines(sci), "everything unfolded")
    EditOps.restoreFolds(sci, folded)
    eq(listOf(0L, 1L, 5L), EditOps.foldedLines(sci), "fold state restored")
    EditOps.foldLevel(sci, 1, collapse = false)
    sci.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_EXPAND.toLong())
    sci.send(Sci.SCI_GOTOPOS, sci.send(Sci.SCI_POSITIONFROMLINE, 6))
    EditOps.foldCurrent(sci, collapse = true)
    eq(listOf(5L), EditOps.foldedLines(sci), "fold current level folds the enclosing block")
    EditOps.foldCurrent(sci, collapse = false)
    eq(emptyList<Long>(), EditOps.foldedLines(sci), "unfold current level")

    // Folds belong to the view, so a tab has to carry them across a document switch.
    sci.send(Sci.SCI_COLOURISE, 0, -1)
    sci.send(Sci.SCI_SETAUTOMATICFOLD, (Sci.SC_AUTOMATICFOLD_SHOW or Sci.SC_AUTOMATICFOLD_CLICK or Sci.SC_AUTOMATICFOLD_CHANGE).toLong())
    sci.send(Sci.SCI_FOLDLINE, 1, Sci.SC_FOLDACTION_CONTRACT.toLong())
    val tabFolds = EditOps.foldedLines(sci)
    val firstDoc = sci.send(Sci.SCI_GETDOCPOINTER)
    sci.send(Sci.SCI_ADDREFDOCUMENT, 0, firstDoc)
    val secondDoc = sci.send(Sci.SCI_CREATEDOCUMENT)
    eq(emptyList<Long>(), EditOps.foldedLines(sci), "creating a document already resets the view's folds, so they are noted first")
    sci.send(Sci.SCI_SETDOCPOINTER, 0, secondDoc)
    sci.send(Sci.SCI_SETDOCPOINTER, 0, firstDoc)
    eq(emptyList<Long>(), EditOps.foldedLines(sci), "switching documents forgets the folds")
    EditOps.restoreFolds(sci, tabFolds)
    eq(listOf(1L) to 0L, EditOps.foldedLines(sci) to sci.send(Sci.SCI_GETLINEVISIBLE, 2), "and they can be put back")
    sci.send(Sci.SCI_COLOURISE, 0, -1)
    eq(listOf(1L), EditOps.foldedLines(sci), "and they survive the rest of the document being styled again")
    sci.send(Sci.SCI_SETANCHOR, 0)
    sci.send(Sci.SCI_SETCURRENTPOS, 0)
    sci.send(Sci.SCI_SETFIRSTVISIBLELINE, 0)
    eq(listOf(1L), EditOps.foldedLines(sci), "and restoring the caret")
    sci.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_EXPAND.toLong())
    sci.send(Sci.SCI_RELEASEDOCUMENT, 0, firstDoc)
    sci.send(Sci.SCI_RELEASEDOCUMENT, 0, secondDoc)

    // Marks and bookmarked lines
    val marker = ThemeApplier.BOOKMARK_MARKER.toLong()
    sci.setLexer("")
    sci.text = "cat dog cat\nbird\ncat\n"
    eq(3, Marks.markAll(sci, 1, "cat", SearchOptions()), "mark all occurrences")
    eq(listOf(0L to 3L, 8L to 11L, 17L to 20L), Marks.ranges(sci, 1), "marked ranges")
    eq(3, Searcher.count(sci, "cat", SearchOptions()), "counting still works after marking")
    sci.send(Sci.SCI_GOTOPOS, 0)
    ok(Marks.jump(sci, 1, forward = true), "jump down")
    eq(8L, sci.send(Sci.SCI_GETCURRENTPOS), "to the next mark")
    Marks.jump(sci, 1, forward = true)
    Marks.jump(sci, 1, forward = true)
    eq(0L, sci.send(Sci.SCI_GETCURRENTPOS), "jump down wraps to the first mark")
    Marks.jump(sci, 1, forward = false)
    eq(17L, sci.send(Sci.SCI_GETCURRENTPOS), "jump up wraps to the last mark")
    Marks.jump(sci, 1, forward = false)
    eq(8L, sci.send(Sci.SCI_GETCURRENTPOS), "jump up goes to the mark before")
    ok(!Marks.jump(sci, 4, forward = true), "no marks in another style")
    eq("cat\ncat\ncat", Marks.text(sci, listOf(1)), "copy styled text")
    sci.send(Sci.SCI_SETSEL, 4, 7)
    ok(Marks.markRange(sci, 2), "mark one")
    eq("cat\ndog\ncat\ncat", Marks.text(sci, listOf(1, 2)), "all styles, in document order")
    Marks.clear(sci, 1)
    eq(0 to 1, Marks.ranges(sci, 1).size to Marks.ranges(sci, 2).size, "clear one style")
    Marks.clearAllStyles(sci)
    eq(0, Marks.ranges(sci, 2).size, "clear all styles")
    sci.send(Sci.SCI_GOTOPOS, 5)
    eq(1 to "dog", Marks.markSelection(sci, 3, matchCase = true, wholeWord = true) to Marks.text(sci, listOf(3)), "mark the word at the caret")
    sci.send(Sci.SCI_GOTOPOS, 12)
    eq(0, Marks.markSelection(sci, 3, matchCase = false, wholeWord = false).coerceAtMost(0), "marking again is harmless")
    eq(1, Marks.markAll(sci, Marks.FOUND, "b.rd", SearchOptions(regex = true), bookmarkLines = true), "mark with a regular expression")
    ok(sci.send(Sci.SCI_MARKERGET, 1) and (1L shl ThemeApplier.BOOKMARK_MARKER) != 0L, "and bookmark its line")
    sci.send(Sci.SCI_MARKERADD, 2, marker)
    eq("bird\ncat\n", Marks.bookmarkedText(sci), "copy bookmarked lines")
    Marks.invertBookmarks(sci)
    eq("cat dog cat\n\n", Marks.bookmarkedText(sci), "inverse bookmark")
    Marks.invertBookmarks(sci)
    eq(2, Marks.replaceBookmarked(sci, "X"), "paste to bookmarked lines")
    eq("cat dog cat\nX\nX\n", sci.text, "bookmarked lines replaced")
    eq(2, Marks.removeLines(sci, bookmarkedOnes = true), "remove bookmarked lines")
    eq("cat dog cat\n", sci.text, "only the other line is left")
    sci.text = "a\nb\nc\n"
    sci.send(Sci.SCI_MARKERADD, 1, marker)
    eq(2, Marks.removeLines(sci, bookmarkedOnes = false), "remove non-bookmarked lines")
    eq("b\n", sci.text, "only the bookmarked line is left")
    sci.send(Sci.SCI_MARKERDELETEALL, marker)

    // Tools: digests
    eq("900150983cd24fb0d6963f7d28e17f72", Hashes.of("MD5", "abc".toByteArray()), "MD5")
    eq("a9993e364706816aba3e25717850c26c9cd0d89d", Hashes.of("SHA-1", "abc".toByteArray()), "SHA-1")
    eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashes.of("SHA-256", "abc".toByteArray()), "SHA-256")
    eq(128, Hashes.of("SHA-512", "abc".toByteArray()).length, "SHA-512 length")
    eq(Hashes.of("MD5", "a".toByteArray()) + "\n" + Hashes.of("MD5", "b".toByteArray()), Hashes.ofText("MD5", "a\n\nb\n", eachLine = true), "one digest per non-empty line")
    eq("", Hashes.ofText("MD5", "", eachLine = false), "nothing to hash")

    // Find characters in range, redact, hide lines
    sci.text = "plain caf\u00e9 and na\u00efve\n"
    sci.send(Sci.SCI_GOTOPOS, 0)
    ok(EditOps.findCharInRange(sci, 128, 255, forward = true, wrap = false), "find a non-ASCII character")
    eq("\u00e9", String(sci.range(sci.send(Sci.SCI_GETSELECTIONSTART), sci.send(Sci.SCI_GETSELECTIONEND)), Charsets.UTF_8), "the whole character is selected")
    ok(EditOps.findCharInRange(sci, 128, 255, forward = true, wrap = false), "and the next one")
    eq("\u00ef", String(sci.range(sci.send(Sci.SCI_GETSELECTIONSTART), sci.send(Sci.SCI_GETSELECTIONEND)), Charsets.UTF_8), "second non-ASCII character")
    ok(!EditOps.findCharInRange(sci, 128, 255, forward = true, wrap = false), "no more without wrapping")
    ok(EditOps.findCharInRange(sci, 128, 255, forward = true, wrap = true), "wrapping starts again from the top")
    ok(EditOps.findCharInRange(sci, 128, 255, forward = false, wrap = true), "searching upwards wraps to the last one")
    eq("\u00ef", String(sci.range(sci.send(Sci.SCI_GETSELECTIONSTART), sci.send(Sci.SCI_GETSELECTIONEND)), Charsets.UTF_8), "upwards from the first finds the last")
    sci.text = "secret word\nnext\n"
    sci.send(Sci.SCI_SETSEL, 0, 14)
    ok(EditOps.redactSelection(sci), "redact")
    eq("\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\n\u2588\u2588xt\n", sci.text, "selected characters are blocked out, line ends kept")
    sci.text = "a\nb\nc\nd\n"
    sci.send(Sci.SCI_SETSEL, 2, 5)
    ok(EditOps.hideLines(sci), "hide lines")
    eq(listOf(1L, 0L, 0L, 1L), (0L..3L).map { sci.send(Sci.SCI_GETLINEVISIBLE, it) }, "the selected lines are hidden")
    sci.send(Sci.SCI_SHOWLINES, 0, 4)

    // Change history
    val historyMask = Sci.SC_MASK_HISTORY.toLong()
    sci.send(Sci.SCI_SETCHANGEHISTORY, 0)
    sci.text = "one\ntwo\nthree\nfour\n"
    EditOps.enableChangeHistory(sci)
    eq(0L, (0L..3L).sumOf { sci.send(Sci.SCI_MARKERGET, it) and historyMask }, "a freshly loaded text has no changed lines")
    ok(!EditOps.goToChange(sci, forward = true), "so there is no change to go to")
    sci.send(Sci.SCI_GOTOPOS, 4)
    ScintillaNative.insertText(sci.ptr, "X")
    sci.send(Sci.SCI_GOTOPOS, 15)
    ScintillaNative.insertText(sci.ptr, "Y")
    ok(sci.send(Sci.SCI_MARKERGET, 1) and historyMask != 0L && sci.send(Sci.SCI_MARKERGET, 3) and historyMask != 0L, "edited lines are marked")
    eq(0L, sci.send(Sci.SCI_MARKERGET, 2) and historyMask, "other lines are not")
    sci.send(Sci.SCI_GOTOPOS, 0)
    ok(EditOps.goToChange(sci, forward = true), "go to next change")
    eq(1L, sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS)), "first changed line")
    EditOps.goToChange(sci, forward = true)
    eq(3L, sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS)), "second changed line")
    EditOps.goToChange(sci, forward = true)
    eq(1L, sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS)), "next change wraps around")
    EditOps.goToChange(sci, forward = false)
    eq(3L, sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS)), "previous change wraps too")
    EditOps.clearChangeHistory(sci)
    eq(0L to 0L, (0L..3L).sumOf { sci.send(Sci.SCI_MARKERGET, it) and historyMask } to sci.send(Sci.SCI_CANUNDO), "clearing removes the marks and the undo history")
    eq("one\nXtwo\nthree\nYfour\n", sci.text, "and keeps the text")
    sci.send(Sci.SCI_SETCHANGEHISTORY, 0)
    Invisibles.apply(sci, nonPrinting = true, control = false)
    Invisibles.apply(sci, nonPrinting = false, control = true)

    // Find All and the Find in Files filters
    sci.text = "alpha\nb\u00e9ta alpha\nALPHA\n"
    val all = Searcher.findAll(sci, "alpha", SearchOptions())
    eq(listOf(0L, 1L, 2L), all.map { it.line }, "find all lists every match with its line")
    eq(Triple("b\u00e9ta alpha", 5, 5), Triple(all[1].text, all[1].column, all[1].length), "with the line's text and where the match is in it, in characters")
    eq(12L to 17L, all[1].start to all[1].end, "and its place in the document, in bytes")
    eq(2, Searcher.findAll(sci, "alpha", SearchOptions(matchCase = true)).size, "find all goes by the search options")
    eq(1, Searcher.findAll(sci, "alpha", SearchOptions(), limit = 1).size, "and stops listing at the limit")
    ok(Searcher.matchesFilters("notes.TXT", "*.txt *.md") && Searcher.matchesFilters("a.md", "*.txt;*.md"), "filters match by pattern, whatever the case")
    ok(!Searcher.matchesFilters("a.png", "*.txt *.md"), "files outside the patterns are left out")
    ok(Searcher.matchesFilters("anything.bin", "*.*") && Searcher.matchesFilters("Makefile", ""), "*.* or nothing means every file")
    ok(!Searcher.matchesFilters("app.min.js", "*.js !*.min.js") && Searcher.matchesFilters("app.js", "*.js !*.min.js"), "a pattern with ! excludes")

    // Macros
    sci.text = ""
    QuietHost.recorded.clear()
    sci.send(Sci.SCI_STARTRECORD)
    ScintillaNative.insertText(sci.ptr, "x=")
    ScintillaNative.insertText(sci.ptr, ";")
    ScintillaNative.keyDown(sci.ptr, Sci.SCK_RETURN, 0)
    sci.send(Sci.SCI_STOPRECORD)
    ScintillaNative.insertText(sci.ptr, "not recorded")
    sci.send(Sci.SCI_UNDO)
    val macro = QuietHost.recorded.toList()
    ok(macro.size >= 2 && macro.any { it.text != null }, "typing and keys are recorded as steps, text included")
    eq("x=;\n", sci.text, "what was typed while recording")
    Macros.play(sci, macro)
    eq("x=;\nx=;\n", sci.text, "playback repeats it")
    sci.send(Sci.SCI_UNDO)
    eq("x=;\n", sci.text, "playback is one undo step")
    Macros.playTimes(sci, Macros.decode(Macros.encode(macro)), 3)
    eq("x=;\nx=;\nx=;\nx=;\n", sci.text, "a macro survives being stored, and can run several times")
    sci.text = "a\nb\nc"
    QuietHost.recorded.clear()
    sci.send(Sci.SCI_STARTRECORD)
    ScintillaNative.insertText(sci.ptr, "- ")
    ScintillaNative.keyDown(sci.ptr, Sci.SCK_DOWN, 0)
    ScintillaNative.keyDown(sci.ptr, Sci.SCK_HOME, 0)
    sci.send(Sci.SCI_STOPRECORD)
    eq(2, Macros.playToEnd(sci, QuietHost.recorded.toList()), "run until the end of file")
    eq("- a\n- b\n- c", sci.text, "every line got its turn, the last one too")

    // Column Editor
    eq(listOf("1", "1", "3", "3", "5"), TextTransforms.columnNumbers(5, 1, 2, 2, 10, TextTransforms.Leading.NONE), "numbers with increase and repeat")
    eq(listOf("08", "09", "10"), TextTransforms.columnNumbers(3, 8, 1, 1, 10, TextTransforms.Leading.ZEROS), "leading zeros to the widest number")
    eq(listOf(" e", " f", "10"), TextTransforms.columnNumbers(3, 14, 1, 1, 16, TextTransforms.Leading.SPACES), "hex with leading spaces")
    eq(listOf("-1", "00", "01"), TextTransforms.columnNumbers(3, -1, 1, 1, 10, TextTransforms.Leading.ZEROS), "negative numbers keep their sign in front")
    eq(listOf("111", "1000"), TextTransforms.columnNumbers(2, 7, 1, 1, 2, TextTransforms.Leading.NONE), "binary")
    sci.setLexer("")
    sci.send(Sci.SCI_SETVIRTUALSPACEOPTIONS, Sci.SCVS_RECTANGULARSELECTION.toLong())
    sci.text = "abcdef\nab\nabcdef\n"
    sci.send(Sci.SCI_SETRECTANGULARSELECTIONANCHOR, 2)
    sci.send(Sci.SCI_SETRECTANGULARSELECTIONCARET, 14)
    eq(3, EditOps.columnInsert(sci) { n -> List(n) { "X" } }, "column editor works on each line of the column")
    eq("abXef\nabX\nabXef\n", sci.text, "selected parts replaced; short line just gets the text")
    sci.send(Sci.SCI_UNDO)
    eq("abcdef\nab\nabcdef\n", sci.text, "column editor is one undo step")
    sci.send(Sci.SCI_SETRECTANGULARSELECTIONANCHOR, 4)
    sci.send(Sci.SCI_SETRECTANGULARSELECTIONCARET, 14)
    EditOps.columnInsert(sci) { n -> TextTransforms.columnNumbers(n, 1, 1, 1, 10, TextTransforms.Leading.NONE) }
    eq("abcd1ef\nab  2\nabcd3ef\n", sci.text, "a zero-width column inserts, padding the short line with spaces")
    sci.text = "one\n\nthree"
    sci.send(Sci.SCI_GOTOPOS, 1)
    eq(3, EditOps.columnInsert(sci) { n -> List(n) { "|" } }, "without a column selection: from the caret's line to the last")
    eq("o|ne\n |\nt|hree", sci.text, "at the caret's column on every line")

    sci.text = "f(a, [b])"
    sci.send(Sci.SCI_GOTOPOS, 2)
    ok(EditOps.selectBetweenBraces(sci), "select between braces")
    eq("2-8", sci.selection, "inside of the parentheses selected")
    sci.text = "alpha beta"
    sci.send(Sci.SCI_GOTOPOS, 7)
    eq("beta" to "6-10", EditOps.selectionOrWord(sci) to sci.selection, "word at the caret is selected and returned")
    ScintillaNative.destroy(sci.ptr)
}

private fun testSession() {
    val session = Session(
        1,
        listOf(
            SessionEntry("content://docs/a%20b?x=1&y=\"2\"", "a <b>.txt", "cpp", "utf-8-bom", 0, 12, 5, 3, 40, null, 0),
            SessionEntry(null, "new 2", "normal", "utf-8", 2, 0, 0, 0, 0, "new 2@7", 2, pinned = true),
        ),
    )
    eq(session, SessionStore.read(SessionStore.write(session)), "session round trip")
    eq(Session(0, emptyList()), SessionStore.read("not xml at all"), "unreadable session is empty")
    eq(
        "https://example.org/?q=two%20words&f=a%26b.txt&n=a%26b&e=.txt&p=content://x/1",
        RunCommand.expand("https://example.org/?q=\$(CURRENT_WORD)&f=\$(FILE_NAME)&n=\$(NAME_PART)&e=\$(EXT_PART)&p=\$(FULL_CURRENT_PATH)", "two words", "a&b.txt", "content://x/1"),
        "run command variables",
    )
    eq("x--", RunCommand.expand("x-\$(EXT_PART)-", "", "Makefile", ""), "run command: no extension")
    eq(Session(0, emptyList()), SessionStore.read(""), "empty session text is empty")
}

private fun testSettings(registry: LanguageRegistry, theme: NppTheme) {
    // Shortcut mapper.
    val ctrl = Sci.SCMOD_CTRL
    val shift = Sci.SCMOD_SHIFT
    eq("Ctrl+Shift+S", Shortcuts.label(KeyBinding(ctrl or shift, 'S'.code)), "shortcut label")
    eq("Ctrl+Alt+Shift+F12", Shortcuts.label(KeyBinding(ctrl or shift or Sci.SCMOD_ALT, Shortcuts.FUNCTION + 12)), "shortcut label with every modifier")
    eq("F3", Shortcuts.label(KeyBinding(0, Shortcuts.FUNCTION + 3)), "function key label")
    eq("Ctrl+Page Down", Shortcuts.label(KeyBinding(ctrl, Sci.SCK_NEXT)), "named key label")
    eq("", Shortcuts.label(null), "no shortcut has no label")
    ok(Shortcuts.usable(KeyBinding(0, Shortcuts.FUNCTION + 5)), "a function key alone is a usable shortcut")
    ok(!Shortcuts.usable(KeyBinding(shift, 'A'.code)), "a letter needs Ctrl or Alt")
    ok(Shortcuts.usable(KeyBinding(Sci.SCMOD_ALT, 'A'.code)), "Alt and a letter is usable")

    val defaults = mapOf(
        "save" to KeyBinding(ctrl, 'S'.code), "find" to KeyBinding(ctrl, 'F'.code),
        "record" to KeyBinding(ctrl or shift, 'R'.code), "stop" to KeyBinding(ctrl or shift, 'R'.code),
    )
    val table = ShortcutTable(defaults)
    eq(listOf("save"), table.commands(ctrl, 'S'.code), "default shortcut found")
    eq(listOf("record", "stop"), table.commands(ctrl or shift, 'R'.code), "two commands may share a default shortcut")
    eq(emptyList<String>(), table.commands(ctrl, 'X'.code), "unused shortcut")
    ok(!table.hasChanges, "a fresh table has no changes")
    eq(listOf("save"), table.assign("upper", KeyBinding(ctrl, 'S'.code)), "assigning a taken shortcut names the loser")
    eq(listOf("upper"), table.commands(ctrl, 'S'.code), "the shortcut now runs the new command")
    eq(null, table.binding("save"), "the old command lost it")
    ok(table.isCustom("save") && table.isCustom("upper") && !table.isCustom("find"), "changed commands are marked")
    val saved = table.encode()
    val restored = ShortcutTable(defaults).apply { decode(saved) }
    eq(listOf("upper"), restored.commands(ctrl, 'S'.code), "changes survive saving")
    eq(null, restored.binding("save"), "a removed shortcut survives saving")
    eq(KeyBinding(ctrl, 'F'.code), restored.binding("find"), "untouched defaults stay")
    restored.reset("save")
    eq(KeyBinding(ctrl, 'S'.code), restored.binding("save"), "reset restores the default")
    table.assign("save", KeyBinding(ctrl, 'S'.code))
    ok(!table.isCustom("save"), "assigning the default again is no change")
    eq(null, table.binding("upper"), "and takes the shortcut back")
    table.assign("find", null)
    eq(null, table.binding("find"), "a shortcut can be removed")
    table.resetAll()
    ok(!table.hasChanges && table.binding("find") == KeyBinding(ctrl, 'F'.code), "reset all")
    ShortcutTable(defaults).apply { decode("garbage\n\n1\t2\n") }.let { ok(!it.hasChanges, "unreadable shortcut lines are skipped") }

    // Preferences.
    val sci = HostSci()
    EditorOptions(tabSize = 8, useSpaces = true, highlightCurrentLine = false, caret = 3, caretBlinks = false, scrollBeyondLastLine = true, edgeColumn = 100)
        .apply(sci, 2f)
    eq(8L, sci.send(Sci.SCI_GETTABWIDTH), "tab size")
    eq(0L, sci.send(Sci.SCI_GETUSETABS), "tabs replaced by spaces")
    eq(0L, sci.send(Sci.SCI_GETCARETLINEVISIBLE), "current line not highlighted")
    eq(6L, sci.send(Sci.SCI_GETCARETWIDTH), "thick caret, in device pixels")
    eq(0L, sci.send(Sci.SCI_GETCARETPERIOD), "caret does not blink")
    eq(0L, sci.send(Sci.SCI_GETENDATLASTLINE), "scrolling beyond the last line")
    eq(Sci.EDGE_LINE.toLong(), sci.send(Sci.SCI_GETEDGEMODE), "edge line shown")
    eq(100L, sci.send(Sci.SCI_GETEDGECOLUMN), "edge column")
    sci.text = "ab"
    sci.send(Sci.SCI_SETSEL, 1, 1)
    sci.send(Sci.SCI_TAB)
    eq("a       b", sci.text, "the Tab key fills up to the tab stop with spaces")
    EditorOptions(caret = 4).apply(sci, 2f)
    eq(Sci.CARETSTYLE_BLOCK.toLong(), sci.send(Sci.SCI_GETCARETSTYLE), "block caret")
    eq(4L, sci.send(Sci.SCI_GETTABWIDTH), "default tab size")
    eq(1L, sci.send(Sci.SCI_GETUSETABS), "default keeps tabs")
    eq(Sci.EDGE_NONE.toLong(), sci.send(Sci.SCI_GETEDGEMODE), "no edge line by default")

    // Style Configurator.
    val overrides = StyleOverrides()
    ok(theme.with(overrides) === theme, "no changes leaves the theme alone")
    val keyword = theme.lexerStyles("cpp").first { it.name == "INSTRUCTION WORD" }
    overrides.set("cpp", "INSTRUCTION WORD", StyleChange(fg = 0x123456, fontStyle = 2))
    overrides.set("", "Default Style", StyleChange(bg = 0x101010))
    val changed = theme.with(overrides)
    val changedKeyword = changed.lexerStyles("cpp").first { it.name == "INSTRUCTION WORD" }
    eq(0x123456, changedKeyword.fg, "changed foreground")
    eq(keyword.bg, changedKeyword.bg, "background left as the theme has it")
    ok(changedKeyword.italic && !changedKeyword.bold, "changed font style")
    eq(keyword.styleId, changedKeyword.styleId, "the style keeps its number")
    eq(0x101010, changed.background, "changed default background")
    ok(changed.isDark && !theme.isDark, "a dark background makes the theme count as dark")
    eq(theme.lexerStyles("python"), changed.lexerStyles("python"), "other languages are untouched")
    val again = StyleOverrides.decode(overrides.encode())
    eq(StyleChange(fg = 0x123456, fontStyle = 2), again.get("cpp", "INSTRUCTION WORD"), "style changes survive saving")
    eq(StyleChange(bg = 0x101010), again.get("", "Default Style"), "global style changes survive saving")
    again.set("cpp", "INSTRUCTION WORD", null)
    again.set("", "Default Style", StyleChange())
    ok(again.isEmpty, "resetting every style leaves nothing")
    ok(StyleOverrides.decode("nonsense").isEmpty, "unreadable style changes are skipped")
    val plan = LanguageSetup.plan(registry.byName("cpp")!!, registry, changed)
    ThemeApplier.apply(sci, changed, plan, ViewMetrics())
    eq(ThemeApplier.sciColour(0x123456), sci.send(Sci.SCI_STYLEGETFORE, keyword.styleId.toLong()), "the editor shows the changed colour")
    eq(1L, sci.send(Sci.SCI_STYLEGETITALIC, keyword.styleId.toLong()), "and the changed font style")
}

/** Notepad++'s own function list tests: its source files must give the lists it expects for them. */
private fun testFunctionList(assets: File, data: File) {
    fun escape(text: String) = text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    fun canonical(entries: List<FunctionEntry>): String {
        val lines = ArrayList<String>()
        for (entry in entries) if (entry.owner.isEmpty()) lines += "L\t" + escape(entry.name)
        for (owner in entries.map { it.owner }.filter { it.isNotEmpty() }.distinct()) {
            for (entry in entries) if (entry.owner == owner) lines += "N\t" + escape(owner) + "\t" + escape(entry.name)
        }
        return lines.joinToString("\n") + "\n"
    }

    val rules = File(assets, "functionList").listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }
    ok(rules.size >= 45, "function list rules are bundled (${rules.size})")
    val unusable = rules.filter { file -> file.inputStream().use { FunctionParser.load(it) }.let { it == null || it.skipped > 0 } }.map { it.name }
    // fortran77.xml has an unbalanced bracket in its comment expression, which Notepad++ cannot read either.
    eq(listOf("fortran77.xml"), unusable, "every function list expression of Notepad++ can be used, but the one that is broken there too")

    val sources = data.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
    ok(sources.size >= 35, "Notepad++'s function list tests are present (${sources.size})")
    for (source in sources) {
        val language = source.name.removeSuffix(".txt")
        val parser = File(assets, "functionList/$language.xml").inputStream().use { FunctionParser.load(it) }!!
        // Notepad++ runs these tests on Windows, where the files have CR LF line endings.
        val text = source.readText(Charsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n")
        val expected = File(data, "$language.expected").readText(Charsets.UTF_8)
        eq(expected, canonical(parser.parse(text, 30000)), "function list of the $language test file")
    }

    // The same rules on text with Unix line endings, and where a function is on screen.
    val python = File(assets, "functionList/python.xml").inputStream().use { FunctionParser.load(it) }!!
    val code = "import os\n\ndef first(a, b):\n    return a\n\nclass Shop:\n    def open(self):\n        pass\n\n    def close(self):\n        pass\n\ndef last():\n    pass\n"
    val entries = python.parse(code)
    eq(listOf("first(a, b)" to "", "open(self)" to "Shop", "close(self)" to "Shop", "last()" to ""), entries.map { it.name to it.owner }.sortedBy { e -> entries.first { it.name == e.first }.position }, "python functions and methods")
    eq(code.indexOf("first"), entries.first { it.name == "first(a, b)" }.position, "an entry knows where its function is")
    eq("Python", python.displayName, "the rules carry a display name")

    // Pieces of Boost syntax that have to be rewritten.
    fun find(expression: String, text: String): String? = BoostPattern.compile(expression)?.find(text, 0, text.length)?.let { text.substring(it[0], it[1]) }
    eq("bar", find("foo\\Kbar", "xfoobar"), "\\K drops what was matched before it")
    eq("b]c", find("[]b]+c", "a b]c"), "a ] first in a class is a literal")
    eq("a[b", find("a[[]b", "xa[b"), "a [ inside a class is a literal")
    eq("x{y}", find("x{y}", "ax{y}"), "braces that are no quantifier are literals")
    eq("aaa", find("a{2,3}", "baaaa".substring(0, 4)), "quantifier braces still count")
    eq("ab12", find("[[:alpha:]]+[[:digit:]]+", "- ab12 -"), "POSIX class names")
    eq("fooBAR", find("(?'word'foo)(?&word)?bar", "x fooBAR"), "named groups, and case is ignored")
    eq("abab", find("(?'P'ab)(?&P)", "xabab"), "a group can be called as a subroutine")
    eq("(a(b)c)", find("\\((?:[^()]++|(?R))*\\)", "x(a(b)c)y"), "recursion, a few levels deep")
    eq("1:x", find("(\\d:)?(?(1)x|y)", "1:x"), "condition on a group that matched")
    eq("y", find("(\\d:)?(?(1)x|y)", "zy"), "condition on a group that did not match")
    eq("ab", find("(?(?=a)ab|cd)", "ab"), "condition on a lookahead, first branch")
    eq("cd", find("(?(?=a)ab|cd)", "cd"), "condition on a lookahead, second branch")
    eq("ab", find("(?x) a  # the first letter\n b", "xab"), "free-spacing mode with a comment")
    eq("a b", find("(?x) a [ ] b", "a b"), "a blank inside a class counts in free-spacing mode")
    eq(null, find("x*", "abc"), "a match of nothing is no match")
    eq(null, BoostPattern.compile("(unbalanced"), "an expression that cannot be read is turned down")
    eq(
        "<a b=\"1&#10;2&#9;3\"><!-- \"\n --></a>", FunctionParser.keepLineBreaks("<a b=\"1\n2\t3\"><!-- \"\n --></a>"),
        "line breaks inside attribute values are kept for the XML reader",
    )
}

private fun testCompletion(assets: File) {
    val files = File(assets, "autoCompletion").listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }
    ok(files.size >= 30, "auto-completion files are bundled (${files.size})")
    for (file in files) {
        val size = runCatching { file.inputStream().use { ApiIndex.load(it) }.size }.getOrDefault(0)
        ok(size > 0, "auto-completion file ${file.name} can be read")
    }
    val python = File(assets, "autoCompletion/python.xml").inputStream().use { ApiIndex.load(it) }
    ok(python.complete("pri").contains("print"), "API completion of a prefix")
    ok(!python.complete("print").contains("print"), "a word that is already complete is not offered")
    eq(emptyList<String>(), python.complete(""), "nothing is offered for no prefix")
    eq(emptyList<String>(), python.complete("PRI"), "python's names are matched with their case")
    val sql = File(assets, "autoCompletion/sql.xml").inputStream().use { ApiIndex.load(it) }
    ok(sql.ignoreCase && sql.complete("sel").any { it.equals("SELECT", ignoreCase = true) }, "SQL's names are matched without case")

    val text = "total = 0\ntotals = []\nfor item in items:\n    total += item.price\nto"
    eq(listOf("total", "totals"), Completion.words(text, "to", ignoreCase = false), "words of the document, sorted, once each")
    eq(emptyList<String>(), Completion.words(text, "x", ignoreCase = false), "no word starts like this")
    eq(listOf("item.price", "items"), Completion.words(text, "item", ignoreCase = false, extraWordChars = "."), "extra word characters extend words")
    val both = Completion.candidates(text, "to", python, fromApi = true, fromWords = true)
    ok(both.containsAll(listOf("total", "totals")) && both == both.sorted(), "API names and document words come as one sorted list")
    ok("to" !in both, "the prefix itself is not offered")
    eq(listOf("total", "totals"), Completion.candidates(text, "to", python, fromApi = false, fromWords = true), "words only")

    val sci = HostSci()
    sci.text = "x = pri"
    sci.send(Sci.SCI_GOTOPOS, 7)
    val (start, prefix) = Completion.prefix(sci)
    eq(4L to "pri", start to prefix, "the prefix before the caret and where it starts")
    Completion.accept(sci, start, "print")
    eq("x = print", sci.text, "accepting a word replaces the prefix")
    eq(9L, sci.send(Sci.SCI_GETCURRENTPOS), "and leaves the caret after it")
    sci.text = "naïve_wo"
    sci.send(Sci.SCI_GOTOPOS, sci.send(Sci.SCI_GETLENGTH))
    eq(0L to "naïve_wo", Completion.prefix(sci), "prefixes count in bytes for Scintilla")
    sci.text = "a.b"
    sci.send(Sci.SCI_GOTOPOS, 3)
    eq(2L to "b", Completion.prefix(sci), "a dot ends a word by default")
    eq(0L to "a.b", Completion.prefix(sci, "."), "unless the language counts it in")

    val c = File(assets, "autoCompletion/c.xml").inputStream().use { ApiIndex.load(it) }
    val site = Completion.callSite("    n = fopen(name, ", c)!!
    eq("fopen" to 1, site.word.name to site.parameter, "the call being typed and the parameter the caret is in")
    val (hint, from, to) = Completion.hint(site, 0, c)
    ok(hint.contains("fopen (") && from > 0 && hint.substring(from, to).isNotEmpty(), "the hint marks the current parameter: $hint")
    eq("strlen" to 0, Completion.callSite("x = fopen(a, b) + strlen(", c)!!.let { it.word.name to it.parameter }, "a finished call is stepped over")
    eq("fopen" to 1, Completion.callSite("fopen(strlen(a), ", c)!!.let { it.word.name to it.parameter }, "a nested finished call does not count as a separator")
    eq(null, Completion.callSite("x = unknown_function(", c), "an unknown function gives no hint")
    eq(null, Completion.callSite("no call here", c), "no call, no hint")

    val listing = mapOf("/data/" to listOf("notes.txt", "Notebook", "music"), "/data/Notebook/" to listOf("a.md"))
    eq(2 to listOf("Notebook", "notes.txt"), Completion.paths("open \"/data/no", { listing[it] }), "path completion lists what fits")
    eq(0 to listOf("a.md"), Completion.paths("/data/Notebook/", { listing[it] }), "path completion right after a slash")
    eq(0 to emptyList<String>(), Completion.paths("plain words", { listing[it] }), "no path, nothing offered")
    eq(0 to emptyList<String>(), Completion.paths("/missing/x", { listing[it] }), "an unreadable folder offers nothing")
}

private fun testProjects() {
    // A workspace file as Notepad++ writes it.
    val npp = """<NotepadPlus>
    <Project name="Web &amp; App">
        <Folder name="src">
            <Folder name="deep" />
            <File name="src\main.c" />
            <File name="C:\work\util.h" />
        </Folder>
        <File name="readme.txt" />
    </Project>
    <Project name="Notes" />
</NotepadPlus>"""
    val projects = ProjectStore.read(npp)
    eq(listOf("Web & App", "Notes"), projects.map { it.name }, "projects of a Notepad++ workspace file")
    val src = projects[0].children[0]
    eq(listOf("deep", "main.c", "util.h"), src.children.map { it.name }, "folders and files, files named by the last part of their path")
    eq("src\\main.c", src.children[1].path, "a file keeps its path")
    eq(listOf("main.c", "util.h", "readme.txt"), projects[0].files().map { it.name }, "all files of a project")
    ok(src.children[1].parent === src && src.parent === projects[0], "nodes know their parent")
    eq(npp.replace(Regex("\\s+"), ""), ProjectStore.write(projects).substringAfter("?>").replace(Regex("\\s+"), ""), "writing gives the same file back")

    // Files added here are document addresses, which carry no readable name.
    val project = ProjectNode(ProjectNode.Kind.PROJECT, "Phone")
    val folder = project.add(ProjectNode(ProjectNode.Kind.FOLDER, "docs"))
    folder.add(ProjectNode(ProjectNode.Kind.FILE, "todo \"now\".txt", "content://provider/document/123"))
    folder.add(ProjectNode(ProjectNode.Kind.FILE, "b.txt", "content://provider/document/b.txt"))
    val again = ProjectStore.read(ProjectStore.write(listOf(project)))
    eq("todo \"now\".txt" to "content://provider/document/123", again[0].children[0].children[0].let { it.name to it.path }, "a name is kept beside an address")
    ok(!ProjectStore.write(listOf(project)).contains("label=\"b.txt\""), "no label where the address ends in the name")

    ok(folder.children[1].move(folder.children, -1), "move up")
    eq(listOf("b.txt", "todo \"now\".txt"), folder.children.map { it.name }, "moved one place up")
    ok(!folder.children[0].move(folder.children, -1), "the first cannot move up")
    folder.remove(folder.children[0])
    eq(1, folder.children.size, "removed")
    eq(emptyList<String>(), ProjectStore.read("this is not xml").map { it.name }, "an unreadable workspace file is empty")
    eq(emptyList<String>(), ProjectStore.read("").map { it.name }, "an empty workspace file is empty")
}

private fun testUserLanguages(assets: File, registry: LanguageRegistry, theme: NppTheme) {
    val tiny = """<NotepadPlus>
    <UserLang name="Tiny" ext="tiny tn" udlVersion="2.1">
        <Settings>
            <Global caseIgnored="no" allowFoldOfComments="no" foldCompact="no" forcePureLC="0" decimalSeparator="0" />
            <Prefix Keywords1="no" Keywords2="yes" Keywords3="no" Keywords4="no" Keywords5="no" Keywords6="no" Keywords7="no" Keywords8="no" />
        </Settings>
        <KeywordLists>
            <Keywords name="Comments">00// 01 02 03/* 04*/</Keywords>
            <Keywords name="Operators1">+ - =</Keywords>
            <Keywords name="Folders in code1, open">begin</Keywords>
            <Keywords name="Folders in code1, close">end</Keywords>
            <Keywords name="Keywords1">let print &quot;go to&quot;</Keywords>
            <Keywords name="Keywords2">${'$'}</Keywords>
            <Keywords name="Delimiters">00&quot; 01 02&quot; 03 04 05 06 07 08 09 10 11 12 13 14 15 16 17 18 19 20 21 22 23</Keywords>
        </KeywordLists>
        <Styles>
            <WordsStyle name="DEFAULT" fgColor="000000" bgColor="FFFFFF" fontStyle="0" nesting="0" />
            <WordsStyle name="LINE COMMENTS" fgColor="008000" bgColor="FFFFFF" colorStyle="1" fontStyle="2" nesting="0" />
            <WordsStyle name="KEYWORDS1" fgColor="0000FF" bgColor="FFFFFF" fontStyle="1" nesting="0" />
            <WordsStyle name="DELIMITERS1" fgColor="808080" bgColor="FFFFFF" fontStyle="0" nesting="4" />
            <WordsStyle name="NOT A STYLE" fgColor="FF0000" />
        </Styles>
    </UserLang>
    <UserLang ext="x"><Settings /><KeywordLists /><Styles /></UserLang>
    <UserLang name="Broken" ext="x"><Settings /></UserLang>
</NotepadPlus>"""
    val languages = UserLanguages.read(tiny.byteInputStream())
    eq(listOf("Tiny"), languages.map { it.name }, "a language needs a name and all three sections")
    val language = languages[0]
    eq(listOf("tiny", "tn"), language.extensions, "extensions of a user-defined language")
    eq(Triple("//", "/*", "*/"), Triple(language.commentLine, language.commentStart, language.commentEnd), "comment marks for the comment commands")
    ok(language.isPrefix[1] && !language.isPrefix[0], "prefix mode per keyword group")

    val plan = UserLanguages.plan(language)
    eq("user", plan.lexer, "user-defined languages use Notepad++'s own lexer")
    eq("00// 01 02 03/* 04*/", plan.properties.first { it.first == "userDefine.comments" }.second, "comment list goes as a property")
    eq("4", plan.properties.first { it.first == "userDefine.nesting.16" }.second, "nesting of a delimiter style")
    eq("let print go\u000Bto", plan.keywordSets[7], "keyword groups go as keyword sets, phrases joined by a marker")
    eq(15, plan.keywordSets.size, "the fifteen lists that are not properties")
    val comment = plan.styles.first { it.styleId == 2 }
    ok(comment.fg == 0x008000 && comment.bg == null && comment.italic, "a style may use only its foreground colour")
    eq(24, plan.styles.size, "every style of the lexer is there, defined or not")
    eq("a\bb c", UserLanguages.phrases("'a b' c"), "single-quoted phrases get their own marker")
    eq("it's", UserLanguages.phrases("it\\'s"), "an escaped quote is a plain character")

    val sci = HostSci()
    ok(sci.setLexer("user"), "the user-defined-language lexer is built in")
    LanguageSetup.applyToDocument(sci, plan)
    sci.sendStrings(Sci.SCI_SETPROPERTY, "userDefine.currentBufferID", "7")
    val code = "let x = 12 // note\nprint \"hi\" ${'$'}var\nbegin\n  y\nend\n/* block */\n"
    sci.text = code
    sci.send(Sci.SCI_COLOURISE, 0, -1)
    fun style(needle: String) = sci.send(Sci.SCI_GETSTYLEAT, code.indexOf(needle).toLong())
    eq(4L, style("let"), "keyword of group 1")
    eq(4L, style("print"), "second keyword of group 1")
    eq(3L, style("12"), "number")
    eq(12L, style("="), "operator")
    eq(2L, style("// note"), "line comment")
    eq(16L, style("\"hi"), "delimiter 1")
    eq(5L, style("${'$'}var"), "prefix keyword of group 2")
    eq(13L, style("begin"), "fold opener")
    eq(1L, style("/* block"), "block comment")
    eq(0L, style("x ="), "anything else is default")
    ok(sci.send(Sci.SCI_GETFOLDLEVEL, 3) and Sci.SC_FOLDLEVELNUMBERMASK.toLong() > sci.send(Sci.SCI_GETFOLDLEVEL, 2) and Sci.SC_FOLDLEVELNUMBERMASK.toLong(), "begin and end fold what is between them")
    ThemeApplier.apply(sci, theme, plan, ViewMetrics())
    eq(ThemeApplier.sciColour(0x0000FF), sci.send(Sci.SCI_STYLEGETFORE, 4), "the definition's colours are used")
    eq(1L, sci.send(Sci.SCI_STYLEGETBOLD, 4), "and its font styles")

    // Notepad++'s own Markdown definitions, one for light themes and one for dark ones.
    val markdown = File(assets, "userDefineLangs").listFiles { f -> f.name.endsWith(".xml") }!!.sortedBy { it.name }
        .flatMap { file -> file.inputStream().use { UserLanguages.read(it) } }
    eq(listOf(false, true), markdown.map { it.darkModeTheme }, "the bundled Markdown definitions: light and dark")
    registry.setUserLanguages(markdown + languages)
    registry.darkTheme = false
    eq(markdown[0].name, registry.forFileName("notes.md").name, "a file extension picks the definition for the theme in use")
    registry.darkTheme = true
    eq(markdown[1].name, registry.forFileName("NOTES.MD").name, "and the dark one in a dark theme")
    eq("Tiny", registry.forFileName("a.tn").name, "a definition with only one variant is used either way")
    eq("cpp", registry.forFileName("a.cpp").name, "built-in languages still answer for their extensions")
    ok(registry.byName("Tiny")?.user === languages[0], "user-defined languages are found by name")
    eq("//", registry.byName("Tiny")!!.commentLine, "and carry their comment marks")
    val md = LanguageSetup.plan(registry.byName(markdown[0].name)!!, registry, theme)
    val page = "# Title\nplain words\n"
    val view = HostSci()
    LanguageSetup.applyToDocument(view, md)
    view.text = page
    view.send(Sci.SCI_COLOURISE, 0, -1)
    ok(view.send(Sci.SCI_GETSTYLEAT, 0) != view.send(Sci.SCI_GETSTYLEAT, page.indexOf("plain").toLong()), "a Markdown heading is styled apart from plain text")
    registry.setUserLanguages(emptyList())
    registry.darkTheme = false
    eq("normal", registry.forFileName("notes.md").name, "without definitions the extension means nothing")
    eq(emptyList<String>(), UserLanguages.read("nonsense".byteInputStream()).map { it.name }, "an unreadable definition file is empty")
    val fresh = UserLanguages.read(UserLanguages.template("My <new> language", "abc xyz").byteInputStream())
    eq(listOf("My <new> language"), fresh.map { it.name }, "the template for a new language is a definition file")
    eq(listOf("abc", "xyz"), fresh[0].extensions, "with the extensions asked for")
    eq(24, UserLanguages.plan(fresh[0]).styles.size, "and every style")
    ok(UserLanguages.template("x", "").split("<Keywords ").size - 1 == 28, "the template names all 28 lists")
}

private fun testCompare() {
    fun show(rows: List<DiffRow>) = rows.joinToString(" ") { row ->
        when (row.kind) {
            DiffKind.SAME -> "=${row.old}"
            DiffKind.CHANGED -> "~${row.old}/${row.new}"
            DiffKind.REMOVED -> "-${row.old}"
            DiffKind.ADDED -> "+${row.new}"
        }
    }
    val old = listOf("alpha", "beta", "gamma", "delta", "epsilon")
    eq("=0 =1 =2 =3 =4", show(Diff.rows(old, old)), "the same text has no differences")
    eq("=0 =1 +2 =2 =3 =4", show(Diff.rows(old, listOf("alpha", "beta", "NEW", "gamma", "delta", "epsilon"))), "an added line")
    eq("=0 -1 =2 =3 =4", show(Diff.rows(old, listOf("alpha", "gamma", "delta", "epsilon"))), "a removed line")
    eq("=0 =1 ~2/2 =3 =4", show(Diff.rows(old, listOf("alpha", "beta", "gamma ray", "delta", "epsilon"))), "a changed line is paired with what it became")
    eq("=0 =1 -2 +2 =3 =4", show(Diff.rows(old, listOf("alpha", "beta", "XYZ 123", "delta", "epsilon"))), "a line replaced by an unrelated one is a removal and an addition")
    eq("+0 +1", show(Diff.rows(emptyList(), listOf("a", "b"))), "everything added to an empty text")
    eq("-0 -1", show(Diff.rows(listOf("a", "b"), emptyList())), "everything removed")
    eq("=0 =1", show(Diff.rows(listOf("Hello World", "x  y"), listOf("hello world", "xy"), DiffOptions(ignoreCase = true, ignoreSpaces = true))), "case and spaces can be overlooked")
    eq("~0/0 ~1/1", show(Diff.rows(listOf("Hello World", "x  y"), listOf("hello world", "xy"))), "and count otherwise")
    eq(2, Diff.differences(Diff.rows(old, listOf("alpha", "B", "gamma", "delta", "E", "F"))), "separate places that differ are counted")

    // Whatever the texts, following the steps turns the one into the other, and no shorter way was missed for small cases.
    val random = java.util.Random(7)
    var sound = true
    repeat(300) {
        val a = IntArray(random.nextInt(30)) { random.nextInt(4) }
        val b = IntArray(random.nextInt(30)) { random.nextInt(4) }
        val steps = Diff.script(a, b)
        val made = ArrayList<Int>()
        var i = 0
        var j = 0
        var kept = 0
        for (step in steps) {
            when (step.toInt()) {
                0 -> { if (a[i] != b[j]) sound = false; made += a[i]; i++; j++; kept++ }
                1 -> i++
                else -> { made += b[j]; j++ }
            }
        }
        if (i != a.size || j != b.size || made != b.toList()) sound = false
        // The longest common subsequence, the slow and sure way.
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (x in a.indices) for (y in b.indices) {
            table[x + 1][y + 1] = if (a[x] == b[y]) table[x][y] + 1 else maxOf(table[x][y + 1], table[x + 1][y])
        }
        if (kept != table[a.size][b.size]) sound = false
    }
    ok(sound, "random texts: the steps rebuild the new text and keep as much as can be kept")
    val big = IntArray(6000) { it }
    val bigger = IntArray(6000) { it + 1_000_000 }
    eq(12000, Diff.script(big, bigger).size, "texts with nothing in common are given up on quickly")

    eq(listOf(8..8) to listOf(8..10), Diff.changedParts("total = 100", "total = 2,500"), "the characters that changed in a line")
    eq(emptyList<IntRange>() to listOf(5..10), Diff.changedParts("hello", "hello world"), "characters only added")

    // Shown in two views.
    val left = HostSci()
    val right = HostSci()
    left.text = "one\ntwo\nthree\nfour\nfive\n"
    right.text = "one\n2\ntwo and a half\nthree\nfive\nsix\nseven\n"
    val oldLines = Compare.lines(left)
    val newLines = Compare.lines(right)
    eq(listOf("one", "two", "three", "four", "five"), oldLines, "lines of a document, without the empty one after the last line break")
    val rows = Diff.rows(oldLines, newLines)
    Compare.setup(left, dark = false)
    Compare.setup(right, dark = true)
    Compare.show(left, right, rows, oldLines, newLines)
    fun marks(sci: HostSci, marker: Int) = (0 until sci.send(Sci.SCI_GETLINECOUNT)).filter { sci.send(Sci.SCI_MARKERGET, it) and (1L shl marker) != 0L }
    eq(listOf(1L), marks(left, Compare.CHANGED), "changed line on the old side")
    eq(listOf(2L), marks(right, Compare.CHANGED), "changed line on the new side")
    eq(listOf(3L), marks(left, Compare.REMOVED), "removed line on the old side")
    eq(listOf(1L, 5L, 6L), marks(right, Compare.ADDED), "added lines on the new side")
    fun height(sci: HostSci) = (0 until sci.send(Sci.SCI_GETLINECOUNT)).sumOf { 1 + sci.send(Sci.SCI_ANNOTATIONGETLINES, it) }
    eq(height(right), height(left), "blank rows make both sides the same height")
    eq(1L, left.send(Sci.SCI_ANNOTATIONGETLINES, 0), "a blank row on the old side where the new one has a line more")
    eq(1L, right.send(Sci.SCI_ANNOTATIONGETLINES, 3), "a blank row on the new side where a line was removed")
    eq(2L, left.send(Sci.SCI_ANNOTATIONGETLINES, 4), "two blank rows for two lines added at the end")
    ok(right.send(Sci.SCI_INDICATORVALUEAT, Compare.INDICATOR.toLong(), right.send(Sci.SCI_POSITIONFROMLINE, 2) + 4) != 0L, "the changed characters are marked")
    eq(0L, right.send(Sci.SCI_INDICATORVALUEAT, Compare.INDICATOR.toLong(), right.send(Sci.SCI_POSITIONFROMLINE, 2) + 1), "the unchanged ones are not")

    // As drawn: the tint is on the line, and still there when the caret's own line colour is under it.
    ScintillaNative.resize(right.ptr, 600, 400)
    fun pixel(line: Long): Int {
        val surface = HostSurface(600, 400)
        ScintillaNative.paint(right.ptr, surface)
        val y = right.send(Sci.SCI_POINTYFROMPOSITION, 0, right.send(Sci.SCI_POSITIONFROMLINE, line)).toInt() + 3
        return surface.image.getRGB(590, y) and 0xFFFFFF
    }
    right.send(Sci.SCI_GOTOLINE, 0)
    val plain = pixel(3)
    ok(pixel(5) != plain, "an added line is tinted where it is drawn")
    ok(pixel(2) != plain && pixel(2) != pixel(5), "a changed line has a tint of its own")
    right.send(Sci.SCI_SETELEMENTCOLOUR, Sci.SC_ELEMENT_CARET_LINE_BACK.toLong(), 0xFFFFE8E8)
    right.send(Sci.SCI_SETCARETLINEVISIBLEALWAYS, 1)
    right.send(Sci.SCI_GOTOLINE, 3)
    val caretLine = pixel(3)
    ok(caretLine != plain, "the line the caret is on has its own colour")
    right.send(Sci.SCI_GOTOLINE, 5)
    ok(pixel(5) != caretLine && pixel(5) != plain, "an added line still shows as one when the caret is on it")

    // Going from one difference to the next.
    val places = Compare.places(rows)
    eq(listOf(1, 3, 5), places, "where the differences are in the new text; a removal counts at the line it follows")
    fun line(sci: HostSci) = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS))
    right.send(Sci.SCI_GOTOLINE, 0)
    ok(Compare.go(right, places, forward = true) && line(right) == 1L, "next difference")
    ok(Compare.go(right, places, forward = true) && line(right) == 3L, "the one after")
    ok(Compare.go(right, places, forward = true) && line(right) == 5L, "the last one")
    ok(!Compare.go(right, places, forward = true), "no difference after the last")
    ok(Compare.go(right, places, forward = false) && line(right) == 3L, "previous difference")
    ok(Compare.first(right, places) && line(right) == 1L, "first difference")
    ok(Compare.last(right, places) && line(right) == 5L, "last difference")
    eq(listOf(0), Compare.places(Diff.rows(listOf("gone", "kept"), listOf("kept"))), "a removal at the very top counts at the first line")
    eq(emptyList<Int>(), Compare.places(Diff.rows(oldLines, oldLines)), "no differences, no places")

    // Show only differences, and clearing.
    val longOld = (1..40).map { "line $it" }
    val longNew = longOld.toMutableList().also { it[20] = "line twenty-one" }
    val a = HostSci()
    val b = HostSci()
    a.text = longOld.joinToString("\n")
    b.text = longNew.joinToString("\n")
    Compare.show(a, b, Diff.rows(longOld, longNew), longOld, longNew)
    Compare.hideSame(b)
    eq(1L, b.send(Sci.SCI_GETLINEVISIBLE, 20), "the changed line stays in view")
    eq(1L, b.send(Sci.SCI_GETLINEVISIBLE, 19), "with a line of what is around it")
    eq(0L, b.send(Sci.SCI_GETLINEVISIBLE, 10), "lines that are the same are hidden")
    eq(0L, b.send(Sci.SCI_GETLINEVISIBLE, 39), "to the end")
    Compare.clear(b)
    Compare.clear(right)
    eq(1L, b.send(Sci.SCI_GETLINEVISIBLE, 10), "clearing shows every line again")
    eq(emptyList<Long>(), marks(right, Compare.ADDED), "clearing removes the marks")
    eq(0L, right.send(Sci.SCI_ANNOTATIONGETLINES, 3), "and the blank rows")
}

private fun testHexBuffer() {
    fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    fun show(buffer: HexBuffer) = buffer.toByteArray().joinToString(" ") { HexBuffer.hex(it.toInt() and 0xFF) }
    val buffer = HexBuffer(bytes(0x48, 0x65, 0x00, 0xFF))
    eq("48 65 00 FF", show(buffer), "bytes as hex")
    ok(!buffer.modified && !buffer.canUndo, "untouched at first")
    buffer.overwrite(1, 0x45)
    eq("48 45 00 FF", show(buffer), "a byte overwritten")
    buffer.typeDigit(0, high = true, digit = 0xA)
    buffer.typeDigit(0, high = false, digit = 0x7)
    eq("A7 45 00 FF", show(buffer), "a byte typed as two hex digits")
    buffer.insert(2, bytes(0x01, 0x02))
    eq("A7 45 01 02 00 FF", show(buffer), "bytes inserted")
    buffer.delete(4)
    eq("A7 45 01 02 FF", show(buffer), "a byte deleted")
    buffer.overwrite(5, 0x0A)
    eq("A7 45 01 02 FF 0A", show(buffer), "overwriting at the end adds a byte")
    ok(buffer.modified, "changed since it was read")
    eq(5, buffer.undo(), "undo says where the change was")
    eq(4, buffer.undo(), "and the one before")
    eq("A7 45 01 02 00 FF", show(buffer), "two changes undone")
    eq(4, buffer.redo(), "redo")
    eq("A7 45 01 02 FF", show(buffer), "one change redone")
    while (buffer.undo() >= 0) { /* back to the start */ }
    eq("48 65 00 FF", show(buffer), "every change undone gives the bytes that were read")
    ok(!buffer.modified, "and that counts as unchanged")
    buffer.overwrite(0, 0x00)
    buffer.markSaved()
    ok(!buffer.modified, "saved")
    buffer.undo()
    ok(buffer.modified, "undoing past the save is a change")
    buffer.overwrite(0, 0x11)
    ok(buffer.modified && !buffer.canRedo, "a new change after an undo leaves the saved state out of reach")
    buffer.delete(9)
    buffer.insert(9, bytes(1))
    buffer.overwrite(9, 1)
    eq("11 65 00 FF", show(buffer), "changes outside the bytes are ignored")
    val grown = HexBuffer(ByteArray(0))
    grown.insert(0, ByteArray(1000) { it.toByte() })
    eq(1000 to 231, grown.size to grown[999], "a buffer grows as needed")

    val hay = HexBuffer(bytes(0xDE, 0xAD, 0x00, 0xDE, 0xAD, 0xBE, 0xEF))
    eq(0, hay.find(bytes(0xDE, 0xAD), 0), "find from the start")
    eq(3, hay.find(bytes(0xDE, 0xAD), 1), "find the next one")
    eq(0, hay.find(bytes(0xDE, 0xAD), 4), "find goes round at the end")
    eq(3, hay.find(bytes(0xDE, 0xAD), 5, forward = false), "find backwards")
    eq(-1, hay.find(bytes(0x12), 0), "not found")
    eq(-1, hay.find(ByteArray(8), 0), "a pattern longer than the bytes is not found")

    eq("DE AD BE EF", HexBuffer.pattern("de ad BE ef")!!.joinToString(" ") { HexBuffer.hex(it.toInt() and 0xFF) }, "a pattern typed as hex pairs")
    eq("DE AD", HexBuffer.pattern("0xDE,0xAD")!!.joinToString(" ") { HexBuffer.hex(it.toInt() and 0xFF) }, "with 0x and commas")
    eq("48 69 C3 A9", HexBuffer.pattern("\"Hi\u00E9\"")!!.joinToString(" ") { HexBuffer.hex(it.toInt() and 0xFF) }, "a quoted text is its UTF-8 bytes")
    eq(null, HexBuffer.pattern("ABC"), "an odd number of digits is no pattern")
    eq(null, HexBuffer.pattern("zz"), "nor are other letters")
    eq(listOf(26, 26, 26, null, null), listOf("26", "0x1A", "1ah", "-1", "x").map { HexBuffer.offset(it) }, "offsets in decimal and hex")
    eq("A.~.", listOf(0x41, 0x00, 0x7E, 0xFF).map { HexBuffer.shown(it) }.joinToString(""), "the text column shows printable ASCII and dots")
}

private fun testLua(assets: File) {
    val files = File(assets.parentFile, "lua")
    val printed = ArrayList<String>()
    val asked = ArrayList<String>()
    val main = HostSci()
    val other = HostSci()
    var split = true
    var engine: LuaEngine? = null
    engine = LuaEngine(
        File(files, "prelude.lua").readText(),
        mapOf("iface" to File(files, "iface.lua").readText(), "constants" to File(files, "constants.lua").readText()),
    ) { name, args ->
        when (name) {
            "print" -> {
                printed += (if (args[1] == true) "! " else "") + args[0]
                null
            }
            "scripts" -> "/scripts"
            "info" -> when (args[0]) {
                "name" -> "notes.txt"
                "count" -> 3L + (args.getOrNull(1) as? Long ?: 0L)
                "version" -> 65538L
                "langType" -> 23L
                else -> null
            }
            "open" -> if (args[0] == "/bad") throw IllegalStateException("cannot open /bad") else true
            "toast" -> {
                // The application calling back into Lua while Lua is calling it.
                engine!!.event("OnSave", "nested.txt", 9L)
                null
            }
            else -> {
                asked += name + args.joinToString(", ", "(", ")")
                if (name == "command") true else null
            }
        }
    }
    engine.editors = { main.ptr to (if (split) other.ptr else 0L) }
    eq(null, engine.start(), "the scripting layer loads")
    fun run(code: String) = engine.run("test.lua", code)
    // What a line typed in the console shows: its value, or the error.
    fun value(code: String): String {
        printed.clear()
        return engine.eval(code) ?: printed.joinToString("\n")
    }

    // The console.
    eq("3", value("1 + 2"), "an expression typed in the console shows its value")
    eq("Lua 5.4", value("=_VERSION"), "with or without the old = in front")
    eq("", value("x = 5"), "a statement shows nothing")
    eq("10", value("x * 2"), "and what it set stays")
    eq("1\t2\tnil", value("1, 2, nil"), "several values are separated by tabs")
    ok(value("1 +").contains("console:1:"), "a line that is not Lua says so")
    eq("nil", value("os.exit"), "a script cannot end the app")
    eq("/scripts/?.lua;/scripts/?/init.lua", value("package.path"), "require looks in the scripts folder")

    // Functions: text in, text out.
    eq(null, run("editor:AddText('hello world')"), "a function with a text")
    eq("hello world", main.text, "the text is in the editor")
    eq("11", value("editor.Length"), "a property")
    eq("hello world\t11", value("editor:GetText()"), "a function that returns text")
    eq("0", value("#editor:GetSelText()"), "an empty text is an empty string, not nothing")
    eq(null, run("editor:SetSel(0, 5)"), "two numbers")
    eq("hello", value("(editor:GetSelText())"), "the selected text")
    eq("hello world\t5", value("editor:GetCurLine()"), "the line of the caret, and where the caret is in it")
    eq("hello world\t11", value("editor:GetLine(0)"), "a line by number")
    eq("true", value("editor:CanUndo()"), "a function that answers yes or no")
    eq(null, run("editor:AddText('a\\0b')"), "a text with a zero byte in it")
    eq(14L, main.send(Sci.SCI_GETLENGTH), "goes in whole, all three bytes")
    eq("true", value("editor:textrange(5, 8) == 'a\\0b'"), "and comes back whole")
    main.text = "hello world"

    // Properties.
    eq("8", value("editor.TabWidth"), "a number property")
    eq(null, run("editor.TabWidth = 3"), "setting it")
    eq(3L, main.send(Sci.SCI_GETTABWIDTH), "reaches the editor")
    eq(null, run("editor.ViewEOL = true"), "a yes/no property")
    eq(1L, main.send(Sci.SCI_GETVIEWEOL), "is on")
    eq(null, run("editor.ViewEOL = 0"), "0 turns it off, though Lua itself counts 0 as true")
    eq(0L, main.send(Sci.SCI_GETVIEWEOL), "is off")
    eq("false", value("editor.ReadOnly"), "reads as true or false")
    eq(null, run("editor.StyleBold[5] = true"), "a property with an index")
    eq(1L, main.send(Sci.SCI_STYLEGETBOLD, 5), "is set for that index")
    eq("true\tfalse", value("editor.StyleBold[5], editor.StyleBold[6]"), "and read by index")
    eq("11", value("editor.LineEndPosition[0]"), "a read-only one with an index")
    eq(null, run("editor.StyleFont[7] = 'Mono'"), "a text property with an index")
    eq("Mono", value("editor.StyleFont[7]"), "comes back as text")
    main.setLexer("python")
    eq(null, run("editor.Property['fold.compact'] = '1'"), "an index that is itself a text")
    eq("1", value("editor.Property['fold.compact']"), "reads back")
    eq("python", value("editor.LexerLanguage"), "a text property without an index")
    eq(null, run("editor.MarginLeft = 9"), "a property whose value travels second")
    eq(9L, main.send(Sci.SCI_GETMARGINLEFT), "arrives")
    ok(run("editor.Length = 3")!!.contains("can only be read"), "a read-only property says so when set")
    ok(run("editor.TabWidth[1] = 3") != null, "an index on a property that has none is an error")
    eq(null, run("editor:SetTabWidth(5)"), "getters and setters are also there under their own names")
    eq("5", value("editor:GetTabWidth()"), "as functions")
    eq(null, run("editor:AssignCmdKey(string.byte('Q'), SCMOD_CTRL, SCI_SELECTALL)"), "a key with its modifiers")

    // The helpers.
    eq("hello", value("editor:textrange(0, 5)"), "textrange")
    eq("6\t11", value("editor:findtext('world')"), "findtext")
    eq("", value("editor:findtext('nope')"), "findtext finding nothing returns nothing")
    eq("", value("editor:findtext('hello', 0, 3)"), "findtext from a position on")
    eq(null, run("editor:insert(0, '> ') editor:append('!') editor:remove(2, 8)"), "insert, append and remove")
    eq("> world!", main.text, "did what they say")
    main.text = "a1 b22 c333"
    eq(null, run("for m in editor:match('[0-9]+', SCFIND_REGEXP) do m:replace('#' .. m.len) end"), "match with replace")
    eq("a#1 b#2 c#3", main.text, "every match was replaced, each seen once")
    eq("1:#1 5:#2 9:#3 ", value("(function() local s = '' for m in editor:match('#%d', 0) do s = s end for m in editor:match('#[0-9]', SCFIND_REGEXP) do s = s .. m.pos .. ':' .. m.text .. ' ' end return s end)()"), "match gives positions and texts")

    // Constants.
    eq("2001\t7\t1\t42013", value("SCI_ADDTEXT, INDIC_ROUNDBOX, SCE_P_COMMENTLINE, IDM_EDIT_JOIN_LINES"), "constants by name")
    eq("nil", value("NO_SUCH_CONSTANT"), "a name that is nothing is nil as always")
    eq("SCI_ADDTEXT", value("npp.ConstantName(2001, 'SCI_')"), "and back from the number")
    eq("11\t5", value("npp.SendEditor(SCI_GETLENGTH), npp:SendEditor(SCI_GETTABWIDTH)"), "SendEditor, with a dot or a colon")

    // The second view.
    eq(null, run("editor2:AddText('below') editor1:AppendText('.')"), "editor1 and editor2")
    eq("below", other.text, "the second view's text")
    split = false
    ok(run("editor2:AddText('x')")!!.contains("no second view"), "without a split, editor2 says there is none")
    split = true

    // Mistakes are named, with the line.
    val unknown = run("\neditor:NoSuchThing()")!!
    ok(unknown.startsWith("test.lua:2:") && unknown.contains("'NoSuchThing'"), "an unknown function: $unknown")
    ok(run("editor:GetDocPointer()")!!.contains("not available to scripts"), "pointers stay out of reach")
    ok(run("editor:AddText({})")!!.contains("argument 1 must be a string"), "a wrong kind of argument")
    ok(run("editor:GotoLine(1.5)")!!.contains("whole number"), "a fraction where a position goes")
    ok(run("x = = 1")!!.startsWith("test.lua:1:"), "a syntax error")
    eq("test.lua:1: boom", run("error('boom')"), "an error raised by the script")
    ok(run("error({})") != null, "an error that is not a text")
    ok(value("npp.HideMenu").contains("npp.HideMenu is not available"), "what the app does not have says so")
    ok(engine.run("bytes", "\u001bLua")!!.contains("binary"), "compiled Lua is not accepted")

    // The application.
    eq("notes.txt\t3\t4", value("npp:GetFileName(), npp:GetNbOpenFiles(), npp.GetNbOpenFiles(1)"), "questions to the app")
    eq("1.2", value("(npp.Version >> 16) .. '.' .. (npp.Version & 0xff)"), "a property of the app")
    eq(null, run("npp:MenuCommand(IDM_EDIT_JOIN_LINES) npp.MenuCommand('View > Word wrap')"), "menu commands")
    ok(asked.containsAll(listOf("command(42013)", "command(View > Word wrap)")), "by Notepad++'s id or by menu path: $asked")
    eq("true\t22", value("npp.BufferLangType[7] == L_LUA, L_PYTHON"), "the language of a document, by Notepad++'s numbers")
    eq(null, run("npp.BufferLangType[7] = L_PYTHON"), "and setting it")
    ok(asked.contains("langType(7, 22)"), "asks the app: $asked")
    ok(run("npp.LanguageName[1] = 'x'")!!.contains("can only be read"), "what can only be read says so")
    ok(run("npp:DoOpen('/bad')")!!.contains("cannot open /bad"), "what the app refuses becomes an error in the script")
    eq("false", value("(pcall(npp.DoOpen, '/bad'))"), "which the script can catch")
    eq("true", value("npp:DoOpen('/good')"), "and what it answers is returned")

    // Events.
    asked.clear()
    eq(null, run("function onSave(name, id) print('saved', name, id) end npp.AddEventHandler('OnSave', onSave)"), "an event handler")
    eq(listOf("watch(OnSave, true)"), asked, "the app is told which events are wanted")
    printed.clear()
    eq(null, engine.event("OnSave", "a.txt", 7L), "the event")
    eq(listOf("saved\ta.txt\t7"), printed, "reaches the handler with its arguments")
    run("npp.AddEventHandler('OnSave', function() error('oops') end) npp.AddEventHandler('OnSave', function() print('third') end)")
    printed.clear()
    engine.event("OnSave", "b.txt", 8L)
    ok(printed.size == 3 && printed[1].startsWith("! OnSave: ") && printed[1].endsWith("oops") && printed[2] == "third", "a handler that fails is reported and the others still run: $printed")
    printed.clear()
    eq(null, run("npp:Toast('x')"), "the app calling back while a script is running")
    eq("saved\tnested.txt\t9", printed.firstOrNull(), "is served")
    asked.clear()
    eq("true\tfalse", value("npp.RemoveEventHandler('OnSave', onSave), npp.RemoveEventHandler('OnSave', onSave)"), "removing a handler")
    run("npp.RemoveAllEventHandlers('OnSave')")
    eq("watch(OnSave, false)", asked.lastOrNull(), "with the last one gone the app is told")
    printed.clear()
    engine.event("OnSave", "c.txt", 1L)
    eq(emptyList<String>(), printed, "and nothing is called")
    ok(run("npp.AddEventHandler('OnNothing', print)")!!.contains("unrecognized event name"), "an event that does not exist")
    printed.clear()
    eq(null, run("npp.AddEventHandler({'OnStyle', 'OnOpen'}, print) npp.AddEventHandler('OnStyle', print)"), "several events at once")
    eq(1, printed.count { it.contains("does not send OnStyle") }, "an event this app never sends is accepted, with one note")

    // Shortcuts and timers.
    asked.clear()
    main.text = "shout"
    eq(null, run("npp.AddShortcut('Shout', 'Ctrl+Alt+U', function() editor:SelectAll() editor:UpperCase() end)"), "a command with a shortcut")
    eq(listOf("shortcut(Shout, Ctrl+Alt+U)"), asked, "is announced to the app")
    eq(null, engine.dispatch("shortcut", "Shout"), "and when the app runs it")
    eq("SHOUT", main.text, "the function is called")
    run("npp.AddShortcut('Broken', '', function() local x = nil; x.y = 1 end)")
    ok(engine.dispatch("shortcut", "Broken")!!.contains("test.lua:1:"), "a command that fails gives the error back")
    asked.clear()
    eq(null, run("ticks = 0 timer = npp.StartTimer(100, function(t) ticks = ticks + 1 if ticks == 2 then npp.StopTimer(t) end end)"), "a timer")
    eq(listOf("timer(1, 100)"), asked, "is asked of the app")
    repeat(3) { engine.dispatch("timer", 1L) }
    eq("2", value("ticks"), "it runs each time the app says so, until stopped")
    ok(asked.contains("stopTimer(1)"), "and stopping it tells the app")

    // Scripts that would never end, or would take all memory.
    engine.setTimeLimit(300)
    val started = System.nanoTime()
    ok(run("while true do end")!!.contains("was stopped"), "an endless loop is stopped")
    ok(run("while true do pcall(function() while true do end end) end")!!.contains("was stopped"), "also one that catches the error and carries on")
    ok((System.nanoTime() - started) / 1_000_000 < 3000, "without much delay")
    eq("2", value("1 + 1"), "and Lua works on afterwards")
    engine.setTimeLimit(20000)
    ok(run("local t = {} for i = 1, 1e9 do t[i] = string.rep('x', 4096) .. i end")!!.contains("not enough memory"), "a script that takes all the memory it can get is stopped")
    eq("true", value("collectgarbage() == 0"), "and Lua works on after that too")

    // With LUA_SCRIPTS set to a folder, each script in it is loaded too, to see how scripts from elsewhere fare.
    System.getenv("LUA_SCRIPTS")?.let { folder ->
        for (script in File(folder).listFiles { f -> f.name.endsWith(".lua") }!!.sortedBy { it.name }) {
            engine.start()
            asked.clear()
            val problem = run(script.readText())
            println("${script.name}: ${problem ?: "loads"} ${asked.filter { it.startsWith("shortcut") || it.startsWith("watch") }}")
            for (name in asked.filter { it.startsWith("shortcut(") }.map { it.substringAfter('(').substringBefore(',') }) {
                main.text = "one two\nthree four\nfive\n"
                println("   $name: ${engine.dispatch("shortcut", name) ?: "runs"}")
            }
            for (event in asked.filter { it.startsWith("watch(") }.map { it.substringAfter('(').substringBefore(',') }) {
                println("   $event: ${engine.event(event, "x") ?: "runs"} ${printed.takeLast(1)}")
            }
        }
        engine.start()
    }

    // Starting afresh forgets everything scripts set up.
    eq(null, engine.start(), "a fresh interpreter")
    eq("nil", value("ticks"), "has none of the old values")
    eq(null, engine.dispatch("shortcut", "Shout"), "nor the old commands")
    engine.stop()
    ok(engine.eval("1") != null && !engine.running, "after stopping, nothing runs")

    // Shortcuts as scripts write them.
    eq(KeyBinding(Sci.SCMOD_CTRL or Sci.SCMOD_ALT or Sci.SCMOD_SHIFT, 'D'.code), Shortcuts.parse("Ctrl+Alt+Shift+D"), "a shortcut in words")
    eq(KeyBinding(Sci.SCMOD_CTRL, Shortcuts.FUNCTION + 5), Shortcuts.parse("ctrl+f5"), "in any case")
    eq(KeyBinding(Sci.SCMOD_ALT, Sci.SCK_PRIOR), Shortcuts.parse("Alt+PageUp"), "a named key")
    eq(KeyBinding(Sci.SCMOD_CTRL, Sci.SCK_ADD), Shortcuts.parse("Ctrl++"), "the plus key")
    eq(null, Shortcuts.parse(""), "no shortcut")
    eq(null, Shortcuts.parse("D"), "a key that types is not a shortcut")
    eq(null, Shortcuts.parse("Ctrl+;"), "nor is a key this app has no shortcut for")
    val table = ShortcutTable(mapOf("Edit > Copy" to KeyBinding(Sci.SCMOD_CTRL, 'C'.code)))
    table.added = mapOf("Plugins > Lua Script > Copy Line" to KeyBinding(Sci.SCMOD_CTRL, 'C'.code))
    eq(listOf("Plugins > Lua Script > Copy Line", "Edit > Copy"), table.commands(Sci.SCMOD_CTRL, 'C'.code), "a script's shortcut is asked before a built-in one")
    table.assign("Plugins > Lua Script > Copy Line", KeyBinding(Sci.SCMOD_ALT, 'L'.code))
    eq(listOf("Edit > Copy"), table.commands(Sci.SCMOD_CTRL, 'C'.code), "and can be changed like any other")

    // Notepad++'s command ids.
    val commands = NppCommands(File(files, "menu.tsv").readText())
    eq("Edit > Line Operations > Join Lines", commands.path(42013), "a Notepad++ command id leads to its place in the menus")
    ok(commands.size > 400 && commands.path(1) == null, "for every command of its menu")
}

fun main(args: Array<String>) {
    val assets = File(args.firstOrNull() ?: "app/src/main/assets/npp")
    ScintillaNative.init(HostPlatform(), 2.0f, 48f)
    if (System.getenv("POCKETPAD_ONLY") == "lua") {
        testLua(assets)
        println("core: ${checks - failures}/$checks checks passed")
        exitProcess(if (failures == 0) 0 else 1)
    }
    testEncodings()
    val (registry, theme) = testLanguages(assets)
    testLexers(registry, theme)
    testSearch()
    testEditOps()
    testTransforms()
    testMoreEditOps()
    testSession()
    testSettings(registry, theme)
    testFunctionList(assets, File(args.getOrNull(1) ?: "hosttest/data/functionList"))
    testCompletion(assets)
    testProjects()
    testUserLanguages(assets, registry, theme)
    testCompare()
    testHexBuffer()
    testLua(assets)
    println("core: ${checks - failures}/$checks checks passed")
    exitProcess(if (failures == 0) 0 else 1)
}
