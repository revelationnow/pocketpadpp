package host

import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.LanguageRegistry
import io.github.revelationnow.pocketpadpp.core.Hashes
import io.github.revelationnow.pocketpadpp.core.LanguageSetup
import io.github.revelationnow.pocketpadpp.core.Marks
import io.github.revelationnow.pocketpadpp.core.NppTheme
import io.github.revelationnow.pocketpadpp.core.SciApi
import io.github.revelationnow.pocketpadpp.core.SearchOptions
import io.github.revelationnow.pocketpadpp.core.Searcher
import io.github.revelationnow.pocketpadpp.core.Session
import io.github.revelationnow.pocketpadpp.core.SessionEntry
import io.github.revelationnow.pocketpadpp.core.SessionStore
import io.github.revelationnow.pocketpadpp.core.TextEncodings
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
            SessionEntry(null, "new 2", "normal", "utf-8", 2, 0, 0, 0, 0, "new 2@7", 2),
        ),
    )
    eq(session, SessionStore.read(SessionStore.write(session)), "session round trip")
    eq(Session(0, emptyList()), SessionStore.read("not xml at all"), "unreadable session is empty")
    eq(Session(0, emptyList()), SessionStore.read(""), "empty session text is empty")
}

fun main(args: Array<String>) {
    val assets = File(args.firstOrNull() ?: "app/src/main/assets/npp")
    ScintillaNative.init(HostPlatform(), 2.0f, 48f)
    testEncodings()
    val (registry, theme) = testLanguages(assets)
    testLexers(registry, theme)
    testSearch()
    testEditOps()
    testTransforms()
    testMoreEditOps()
    testSession()
    println("core: ${checks - failures}/$checks checks passed")
    exitProcess(if (failures == 0) 0 else 1)
}
