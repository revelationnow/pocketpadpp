package host;

import io.github.revelationnow.pocketpadpp.scintilla.Sci;
import io.github.revelationnow.pocketpadpp.scintilla.SciHost;
import io.github.revelationnow.pocketpadpp.scintilla.SciLex;
import io.github.revelationnow.pocketpadpp.scintilla.ScintillaNative;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Drives the native Scintilla platform layer through the same JNI interface the Android app uses,
 * with Java2D standing in for the Android canvas. Run with hosttest/run.sh.
 */
public final class Harness implements SciHost {
    private long ptr;
    private String clipboard = "";
    private int invalidates;
    private boolean idleWanted;
    private int workQueued;
    private final Map<Integer, Integer> notifications = new HashMap<>();
    private final Set<Integer> timers = new HashSet<>();
    private int failures;
    private int checks;

    // SciHost

    @Override public void invalidate() { invalidates++; }

    @Override public void notify(int code, long position, int ch, int modifiers, int modificationType,
                                 long length, long linesAdded, long line, int margin, int updated) {
        notifications.merge(code, 1, Integer::sum);
    }

    @Override public void startTimer(int reason, int millis) { timers.add(reason); }
    @Override public void cancelTimer(int reason) { timers.remove(reason); }
    @Override public void setIdle(boolean on) { idleWanted = on; }
    @Override public void queueWork() { workQueued++; }
    @Override public void setClipboard(String text) { clipboard = text; }
    @Override public String getClipboard() { return clipboard; }

    // Helpers

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private long send(int msg, long w, long l) { return ScintillaNative.send(ptr, msg, w, l); }
    private long send(int msg) { return send(msg, 0, 0); }
    private long sendText(int msg, long w, String s) { return ScintillaNative.sendString(ptr, msg, w, utf8(s)); }

    private String text() {
        return new String(ScintillaNative.getRange(ptr, 0, send(Sci.SCI_GETLENGTH)), StandardCharsets.UTF_8);
    }

    private void setText(String s) {
        ScintillaNative.imeFinishComposing(ptr);
        sendText(Sci.SCI_SETTEXT, 0, s);
        send(Sci.SCI_EMPTYUNDOBUFFER);
    }

    private int[] ime() {
        int[] state = new int[4];
        ScintillaNative.imeState(ptr, state);
        return state;
    }

    private void drain() {
        for (int i = 0; i < 100000 && idleWanted; i++) {
            ScintillaNative.idle(ptr);
        }
        while (workQueued > 0) {
            workQueued = 0;
            ScintillaNative.idleWork(ptr);
        }
    }

    private void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + "\n     expected: " + show(expected) + "\n     actual:   " + show(actual));
        }
    }

    private void ok(boolean condition, String what) {
        eq(true, condition, what);
    }

    private static String show(Object o) {
        return String.valueOf(o).replace("\r", "\\r").replace("\n", "\\n");
    }

    private HostSurface paint(int width, int height) {
        HostSurface surface = new HostSurface(width, height);
        ScintillaNative.paint(ptr, surface);
        return surface;
    }

    private void tapAtPosition(long position, long timeMs) {
        float x = send(Sci.SCI_POINTXFROMPOSITION, 0, position) + 1;
        float y = send(Sci.SCI_POINTYFROMPOSITION, 0, position) + send(Sci.SCI_TEXTHEIGHT, 0, 0) / 2f;
        ScintillaNative.mouseDown(ptr, x, y, timeMs, 0);
        ScintillaNative.mouseUp(ptr, x, y, timeMs + 20, 0);
    }

    // Tests

    private void setUpStyles() {
        sendText(Sci.SCI_STYLESETFONT, Sci.STYLE_DEFAULT, "monospace");
        send(Sci.SCI_STYLESETSIZE, Sci.STYLE_DEFAULT, 13);
        send(Sci.SCI_STYLESETFORE, Sci.STYLE_DEFAULT, 0x000000);
        send(Sci.SCI_STYLESETBACK, Sci.STYLE_DEFAULT, 0xFFFFFF);
        send(Sci.SCI_STYLECLEARALL);
        ok(ScintillaNative.setLexer(ptr, "cpp"), "cpp lexer exists");
        sendText(Sci.SCI_SETKEYWORDS, 0, "int return if else for while void class struct const static");
        // Scintilla colours are 0xBBGGRR.
        send(Sci.SCI_STYLESETFORE, SciLex.SCE_C_WORD, 0xFF0000);
        send(Sci.SCI_STYLESETBOLD, SciLex.SCE_C_WORD, 1);
        send(Sci.SCI_STYLESETFORE, SciLex.SCE_C_STRING, 0x808080);
        send(Sci.SCI_STYLESETFORE, SciLex.SCE_C_COMMENT, 0x008000);
        send(Sci.SCI_STYLESETFORE, SciLex.SCE_C_COMMENTLINE, 0x008000);
        send(Sci.SCI_STYLESETFORE, SciLex.SCE_C_NUMBER, 0x0080FF);
        send(Sci.SCI_STYLESETFORE, SciLex.SCE_C_PREPROCESSOR, 0x004080);
        send(Sci.SCI_STYLESETFORE, Sci.STYLE_LINENUMBER, 0x808080);
        send(Sci.SCI_STYLESETBACK, Sci.STYLE_LINENUMBER, 0xE4E4E4);
        send(Sci.SCI_SETMARGINTYPEN, 0, Sci.SC_MARGIN_NUMBER);
        send(Sci.SCI_SETMARGINWIDTHN, 0, 90);
        send(Sci.SCI_SETMARGINTYPEN, 2, Sci.SC_MARGIN_SYMBOL);
        send(Sci.SCI_SETMARGINMASKN, 2, Sci.SC_MASK_FOLDERS);
        send(Sci.SCI_SETMARGINWIDTHN, 2, 36);
        send(Sci.SCI_SETMARGINSENSITIVEN, 2, 1);
        ScintillaNative.sendStrings(ptr, Sci.SCI_SETPROPERTY, utf8("fold"), utf8("1"));
        int[][] markers = {
            {Sci.SC_MARKNUM_FOLDEROPEN, Sci.SC_MARK_BOXMINUS}, {Sci.SC_MARKNUM_FOLDER, Sci.SC_MARK_BOXPLUS},
            {Sci.SC_MARKNUM_FOLDERSUB, Sci.SC_MARK_VLINE}, {Sci.SC_MARKNUM_FOLDERTAIL, Sci.SC_MARK_LCORNER},
            {Sci.SC_MARKNUM_FOLDEREND, Sci.SC_MARK_BOXPLUSCONNECTED},
            {Sci.SC_MARKNUM_FOLDEROPENMID, Sci.SC_MARK_BOXMINUSCONNECTED},
            {Sci.SC_MARKNUM_FOLDERMIDTAIL, Sci.SC_MARK_TCORNER}};
        for (int[] marker : markers) {
            send(Sci.SCI_MARKERDEFINE, marker[0], marker[1]);
            send(Sci.SCI_MARKERSETFORE, marker[0], 0xFFFFFF);
            send(Sci.SCI_MARKERSETBACK, marker[0], 0x808080);
        }
        send(Sci.SCI_SETAUTOMATICFOLD, Sci.SC_AUTOMATICFOLD_SHOW | Sci.SC_AUTOMATICFOLD_CLICK | Sci.SC_AUTOMATICFOLD_CHANGE, 0);
        send(Sci.SCI_SETCARETLINEVISIBLE, 1, 0);
        send(Sci.SCI_SETCARETLINEBACK, 0xFFF0E0, 0);
        send(Sci.SCI_SETCARETWIDTH, 3, 0);
        send(Sci.SCI_SETSCROLLWIDTHTRACKING, 1, 0);
        send(Sci.SCI_SETINDENTATIONGUIDES, Sci.SC_IV_LOOKBOTH, 0);
        send(Sci.SCI_SETTABWIDTH, 4, 0);
    }

    private void testRender(File outDir) throws Exception {
        String sample = "#include <stdio.h>\n\n// Greeting: 你好, мир, 😀 ok\nint main(void) {\n"
            + "\tconst int answer = 42;\n\tif (answer > 0) {\n\t\tprintf(\"hello %d\\n\", answer);\n\t}\n"
            + "\t/* block\n\t   comment */\n\treturn 0;\n}\n";
        setText(sample);
        send(Sci.SCI_COLOURISE, 0, -1);
        long keyword = utf8(sample.substring(0, sample.indexOf("int main"))).length;
        eq((long) SciLex.SCE_C_WORD, send(Sci.SCI_GETSTYLEAT, keyword, 0), "lexer styled the int keyword");
        eq((long) SciLex.SCE_C_COMMENTLINE, send(Sci.SCI_GETSTYLEAT, utf8(sample.substring(0, sample.indexOf("// Greeting"))).length, 0), "lexer styled the line comment");
        long answer = utf8(sample.substring(0, sample.indexOf("answer"))).length;
        send(Sci.SCI_SETSEL, answer, answer + 6);
        HostSurface.calls = 0;
        HostSurface.textCalls = 0;
        HostSurface surface = paint(1080, 1400);
        System.out.println("render: " + HostSurface.calls + " drawing calls, " + HostSurface.textCalls + " text runs");
        ok(HostSurface.textCalls > 20, "paint drew text runs");
        int settled = invalidates;
        paint(1080, 1400);
        paint(1080, 1400);
        eq(settled, invalidates, "painting an unchanged editor does not ask for another repaint");
        ImageIO.write(surface.image, "png", new File(outDir, "render.png"));
        int lineHeight = (int) send(Sci.SCI_TEXTHEIGHT, 0, 0);
        ok(lineHeight > 20 && lineHeight < 60, "line height is plausible for a 26px font: " + lineHeight);
        // Dark pixels where the first line's text is, none in the empty area below the document.
        ok(countDark(surface, 130, 0, 500, lineHeight) > 50, "first line has ink");
        eq(0, countDark(surface, 130, lineHeight * 20, 500, lineHeight * 22), "area below the text is blank");
    }

    private static int countDark(HostSurface s, int x0, int y0, int x1, int y1) {
        int dark = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = s.image.getRGB(x, y);
                int lum = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
                if (lum < 300) {
                    dark++;
                }
            }
        }
        return dark;
    }

    private void testTyping() {
        setText("");
        ScintillaNative.insertText(ptr, "a");
        ScintillaNative.insertText(ptr, "b");
        ScintillaNative.insertText(ptr, "c");
        ok(ScintillaNative.keyDown(ptr, Sci.SCK_RETURN, 0), "return key consumed");
        ScintillaNative.insertText(ptr, "é😀");
        eq("abc\né😀", text(), "typed text");
        ok(ScintillaNative.keyDown(ptr, Sci.SCK_BACK, 0), "backspace consumed");
        eq("abc\né", text(), "backspace removes a whole emoji");
        ok(ScintillaNative.keyDown(ptr, 'Z', Sci.SCMOD_CTRL), "ctrl+z consumed");
        ok(text().length() >= "abc\né".length(), "undo restored something");
        setText("\tx");
        send(Sci.SCI_GOTOPOS, 2, 0);
        ScintillaNative.insertText(ptr, "\t");
        eq("\tx\t", text(), "tab key inserts a tab by default");
    }

    private void testComposing() {
        setText("");
        String[] steps = {"h", "he", "hel", "hell", "hello"};
        for (String step : steps) {
            ScintillaNative.imeSetComposingText(ptr, step, 1);
            eq(step, text(), "composing " + step);
            eq("[" + step.length() + ", " + step.length() + ", 0, " + step.length() + "]", Arrays.toString(ime()), "ime state while composing " + step);
        }
        ScintillaNative.imeCommitText(ptr, "hello ", 1);
        eq("hello ", text(), "commit composing text");
        eq("[6, 6, -1, -1]", Arrays.toString(ime()), "ime state after commit");
        int undoSteps = 0;
        while (send(Sci.SCI_CANUNDO) != 0 && undoSteps < 20) {
            send(Sci.SCI_UNDO);
            undoSteps++;
        }
        eq("", text(), "undo returns to empty");
        System.out.println("composing: typing 'hello ' through the keyboard took " + undoSteps + " undo step(s)");
        ok(undoSteps <= 2, "composing coalesces into at most two undo steps");

        // Autocorrect style replacement of the composing word.
        setText("");
        for (String step : new String[] {"t", "te", "teh"}) {
            ScintillaNative.imeSetComposingText(ptr, step, 1);
        }
        ScintillaNative.imeSetComposingText(ptr, "the", 1);
        eq("the", text(), "composing text replaced");
        ScintillaNative.imeCommitText(ptr, "the ", 1);
        eq("the ", text(), "autocorrected commit");

        // Composing text replaces a selection, and deleting it all ends the composition.
        setText("one two three");
        send(Sci.SCI_SETSEL, 4, 7);
        ScintillaNative.imeSetComposingText(ptr, "2", 1);
        eq("one 2 three", text(), "composing replaces the selection");
        ScintillaNative.imeSetComposingText(ptr, "", 1);
        eq("one  three", text(), "empty composing text deletes it");
        eq("[4, 4, -1, -1]", Arrays.toString(ime()), "composition ended");

        // The keyboard adopts an existing word and rewrites it.
        setText("foo bar baz");
        send(Sci.SCI_GOTOPOS, 7, 0);
        ScintillaNative.imeSetComposingRegion(ptr, 4, 7);
        eq("[7, 7, 4, 7]", Arrays.toString(ime()), "composing region set");
        ScintillaNative.imeSetComposingText(ptr, "quux", 1);
        eq("foo quux baz", text(), "composing region rewritten");
        eq("[8, 8, 4, 8]", Arrays.toString(ime()), "composing region follows the new text");
        ScintillaNative.imeFinishComposing(ptr);
        eq("[8, 8, -1, -1]", Arrays.toString(ime()), "finish composing keeps the text");
        eq("foo quux baz", text(), "finish composing keeps the text");

        // An edit elsewhere keeps the composing range attached to its word.
        setText("abc def");
        ScintillaNative.imeSetComposingRegion(ptr, 4, 7);
        sendText(Sci.SCI_INSERTTEXT, 0, ">> ");
        eq("[" + 0 + ", " + 0 + ", 7, 10]", Arrays.toString(ime()), "composing range shifts with earlier insert");
        ScintillaNative.imeCommitText(ptr, "DEF", 1);
        eq(">> abc DEF", text(), "commit lands on the shifted range");

        // A commit that ends in a line break uses the document's end-of-line mode.
        setText("");
        send(Sci.SCI_SETEOLMODE, Sci.SC_EOL_CRLF, 0);
        ScintillaNative.imeSetComposingText(ptr, "lin", 1);
        ScintillaNative.imeCommitText(ptr, "line\n", 1);
        eq("line\r\n", text(), "line break in commit becomes CRLF");
        send(Sci.SCI_SETEOLMODE, Sci.SC_EOL_LF, 0);
    }

    private void testUtf16Offsets() {
        setText("a😀b\r\nc");
        ScintillaNative.imeSetSelection(ptr, 3, 3);
        eq(5L, send(Sci.SCI_GETCURRENTPOS), "utf16 offset 3 is byte 5");
        eq("😀", ScintillaNative.imeTextBefore(ptr, 2), "two units before the caret is the emoji");
        eq("", ScintillaNative.imeTextBefore(ptr, 1), "one unit never returns half an emoji");
        eq("a😀", ScintillaNative.imeTextBefore(ptr, 100), "text before clamps at the start");
        eq("b\r\nc", ScintillaNative.imeTextAfter(ptr, 100), "text after clamps at the end");
        ScintillaNative.imeSetSelection(ptr, 1, 4);
        eq("😀b", ScintillaNative.imeSelectedText(ptr), "selection by utf16 offsets");
        eq("[1, 4, -1, -1]", Arrays.toString(ime()), "ime state for a selection");
        ScintillaNative.imeSetSelection(ptr, 6, 6);
        eq(8L, send(Sci.SCI_GETCURRENTPOS), "utf16 offset 6 is the start of line 2");
        ScintillaNative.imeDeleteSurrounding(ptr, 1, 0);
        eq("a😀bc", text(), "backspace at line start removes the whole CRLF");
        ScintillaNative.imeDeleteSurrounding(ptr, 2, 1);
        eq("a😀", text(), "delete surrounding stops before half an emoji");
        ScintillaNative.imeDeleteSurrounding(ptr, 2, 0);
        eq("a", text(), "delete surrounding removes a whole emoji");
        ScintillaNative.imeSetSelection(ptr, 999, 999);
        eq(1L, send(Sci.SCI_GETCURRENTPOS), "offset past the end clamps");

        // A second document gets the same treatment.
        long doc = send(Sci.SCI_CREATEDOCUMENT, 0, 0);
        send(Sci.SCI_SETDOCPOINTER, 0, doc);
        ScintillaNative.imeCommitText(ptr, "x😀\ny", 1);
        eq("x😀\ny", text(), "text in the second document");
        eq("[5, 5, -1, -1]", Arrays.toString(ime()), "utf16 state in the second document");
        send(Sci.SCI_RELEASEDOCUMENT, 0, doc);
        // Lexers and their settings belong to the document, so the new one needs them again.
        ok(ScintillaNative.setLexer(ptr, "cpp"), "cpp lexer attached to the second document");
        sendText(Sci.SCI_SETKEYWORDS, 0, "int return if else for while void class struct const static");
        ScintillaNative.sendStrings(ptr, Sci.SCI_SETPROPERTY, utf8("fold"), utf8("1"));
    }

    private void testMultiCaret() {
        setText("x\nx\nx");
        send(Sci.SCI_SETMULTIPLESELECTION, 1, 0);
        send(Sci.SCI_SETADDITIONALSELECTIONTYPING, 1, 0);
        send(Sci.SCI_SETSELECTION, 1, 1);
        send(Sci.SCI_ADDSELECTION, 3, 3);
        send(Sci.SCI_ADDSELECTION, 5, 5);
        eq(3L, send(Sci.SCI_GETSELECTIONS), "three carets");
        ScintillaNative.imeCommitText(ptr, "!", 1);
        eq("x!\nx!\nx!", text(), "keyboard text goes to every caret");
        ScintillaNative.imeDeleteSurrounding(ptr, 1, 0);
        eq("x\nx\nx", text(), "backspace applies at every caret");
        send(Sci.SCI_SETSELECTION, 0, 0);
    }

    private void testTouchAndClipboard() {
        setText("alpha beta gamma\nsecond line here\n");
        send(Sci.SCI_SETFIRSTVISIBLELINE, 0, 0);
        tapAtPosition(8, 1000);
        eq(8L, send(Sci.SCI_GETCURRENTPOS), "tap places the caret");
        tapAtPosition(8, 5000);
        tapAtPosition(8, 5150);
        eq("6-10", send(Sci.SCI_GETSELECTIONSTART) + "-" + send(Sci.SCI_GETSELECTIONEND), "double tap selects the word");
        send(Sci.SCI_COPY);
        eq("beta", clipboard, "copy reaches the clipboard");
        clipboard = "Ünïcödé 😀\r\nline";
        send(Sci.SCI_DOCUMENTEND);
        send(Sci.SCI_PASTE);
        ok(text().endsWith("Ünïcödé 😀\nline"), "paste converts line ends and keeps unicode: " + show(text()));
        // Tap on the fold margin toggles a fold.
        setText("void f() {\n\tint a;\n\tint b;\n}\nint tail;\n");
        send(Sci.SCI_COLOURISE, 0, -1);
        float y = send(Sci.SCI_TEXTHEIGHT, 0, 0) / 2f;
        ScintillaNative.mouseDown(ptr, 90 + 18, y, 9000, 0);
        ScintillaNative.mouseUp(ptr, 90 + 18, y, 9020, 0);
        eq(0L, send(Sci.SCI_GETFOLDEXPANDED, 0, 0), "tap on the fold margin collapses the block");
        eq(0L, send(Sci.SCI_GETLINEVISIBLE, 1, 0), "folded line is hidden");
        send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_EXPAND, 0);
    }

    private void testHostCallbacks() {
        ScintillaNative.setFocus(ptr, true);
        ok(timers.contains(0), "caret blink timer started on focus");
        int before = invalidates;
        paint(1080, 1400); // Scintilla holds further invalidations back until the pending one is painted.
        before = invalidates;
        ScintillaNative.tick(ptr, 0);
        ok(invalidates > before, "caret tick invalidates the view");
        ok(notifications.getOrDefault(Sci.SCN_MODIFIED, 0) > 0, "SCN_MODIFIED delivered");
        ok(notifications.getOrDefault(Sci.SCN_UPDATEUI, 0) > 0, "SCN_UPDATEUI delivered");
        ok(notifications.getOrDefault(Sci.SCN_CHARADDED, 0) > 0, "SCN_CHARADDED delivered");
        ok(ScintillaNative.setLexer(ptr, "user"), "Notepad++ user-defined-language lexer is built in");
        ok(!ScintillaNative.setLexer(ptr, "no-such-lexer"), "unknown lexer is reported");
        ok(ScintillaNative.setLexer(ptr, "cpp"), "cpp lexer restored");
    }

    private void testBigDocument(File outDir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            sb.append("\tint value_").append(i).append(" = compute(").append(i).append(", \"текст 😀\"); // line ").append(i).append('\n');
        }
        byte[] bytes = utf8(sb.toString());
        setText("");
        long t0 = System.nanoTime();
        ScintillaNative.sendString(ptr, Sci.SCI_ADDTEXT, bytes.length, bytes);
        long t1 = System.nanoTime();
        eq(200_001L, send(Sci.SCI_GETLINECOUNT), "big document line count");
        send(Sci.SCI_SETFIRSTVISIBLELINE, 100_000, 0);
        eq(100_000L, send(Sci.SCI_GETFIRSTVISIBLELINE), "scrolled to the middle");
        HostSurface.calls = 0;
        long t2 = System.nanoTime();
        HostSurface first = paint(1080, 1400);
        long t3 = System.nanoTime();
        long callsPerFrame = HostSurface.calls;
        int frames = 30;
        for (int i = 1; i <= frames; i++) {
            send(Sci.SCI_SETFIRSTVISIBLELINE, 100_000 + i * 3, 0);
            paint(1080, 1400);
        }
        long t4 = System.nanoTime();
        ImageIO.write(first.image, "png", new File(outDir, "bigdoc.png"));
        long mid = send(Sci.SCI_POSITIONFROMLINE, 150_000, 0);
        send(Sci.SCI_GOTOPOS, mid, 0);
        long t5 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            ScintillaNative.imeSetComposingText(ptr, "w".repeat(1 + i % 8), 1);
            ime();
        }
        ScintillaNative.imeFinishComposing(ptr);
        long t6 = System.nanoTime();
        ok(ime()[0] > 150_000, "utf16 caret offset is far into the document: " + ime()[0]);
        System.out.printf("big document: %.1f MB, load %.0f ms, first paint %.1f ms (%d drawing calls), "
                + "scrolling paint %.1f ms/frame, composing keystroke + state %.3f ms%n",
            bytes.length / 1e6, (t1 - t0) / 1e6, (t3 - t2) / 1e6, callsPerFrame,
            (t4 - t3) / 1e6 / frames, (t6 - t5) / 1e6 / 200);
        drain();
    }

    private void testWrap(File outDir) throws Exception {
        setText("A long line that needs wrapping: " + "word ".repeat(120) + "\nshort\n");
        send(Sci.SCI_SETWRAPMODE, Sci.SC_WRAP_WORD, 0);
        paint(1080, 1400);
        drain();
        HostSurface wrapped = paint(1080, 1400);
        ImageIO.write(wrapped.image, "png", new File(outDir, "wrap.png"));
        ok(send(Sci.SCI_WRAPCOUNT, 0, 0) > 3, "long line wraps onto several rows: " + send(Sci.SCI_WRAPCOUNT, 0, 0));
        send(Sci.SCI_SETWRAPMODE, Sci.SC_WRAP_NONE, 0);
    }

    private int run(File outDir) throws Exception {
        ScintillaNative.init(new HostPlatform(), 2.0f, 48f);
        ptr = ScintillaNative.create(this);
        ok(ptr != 0, "native editor created");
        ScintillaNative.resize(ptr, 1080, 1400);
        setUpStyles();
        testRender(outDir);
        testTyping();
        testComposing();
        testUtf16Offsets();
        testMultiCaret();
        testTouchAndClipboard();
        testHostCallbacks();
        testWrap(outDir);
        testBigDocument(outDir);
        ScintillaNative.destroy(ptr);
        System.out.println((checks - failures) + "/" + checks + " checks passed");
        return failures;
    }

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "out");
        outDir.mkdirs();
        int failures = new Harness().run(outDir);
        System.exit(failures == 0 ? 0 : 1);
    }
}
