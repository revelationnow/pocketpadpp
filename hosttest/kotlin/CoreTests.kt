package host

import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.LanguageRegistry
import io.github.revelationnow.pocketpadpp.core.LanguageSetup
import io.github.revelationnow.pocketpadpp.core.NppTheme
import io.github.revelationnow.pocketpadpp.core.SciApi
import io.github.revelationnow.pocketpadpp.core.SearchOptions
import io.github.revelationnow.pocketpadpp.core.Searcher
import io.github.revelationnow.pocketpadpp.core.Session
import io.github.revelationnow.pocketpadpp.core.SessionEntry
import io.github.revelationnow.pocketpadpp.core.SessionStore
import io.github.revelationnow.pocketpadpp.core.TextEncodings
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
    testSession()
    println("core: ${checks - failures}/$checks checks passed")
    exitProcess(if (failures == 0) 0 else 1)
}
