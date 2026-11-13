package io.github.revelationnow.pocketpadpp.scintilla;

/**
 * JNI entry points of the Scintilla Android platform layer.
 * All calls must happen on one thread (the UI thread).
 * Positions in the ime* methods are UTF-16 code unit offsets, as used by
 * android.view.inputmethod.InputConnection; everything else uses Scintilla byte positions.
 */
public final class ScintillaNative {
    private ScintillaNative() {}

    static {
        System.loadLibrary("nppscintilla");
    }

    public static native void init(SciPlatform platform, float pixelsPerPoint, float doubleTapSlopPx);

    public static native long create(SciHost host);
    public static native void destroy(long ptr);

    /** Generic SCI_* message with integer parameters. */
    public static native long send(long ptr, int msg, long wParam, long lParam);
    /** SCI_* message whose lParam is a string; a NUL terminator is appended. */
    public static native long sendString(long ptr, int msg, long wParam, byte[] utf8);
    /** SCI_* message whose wParam and lParam are both strings. */
    public static native long sendStrings(long ptr, int msg, byte[] w, byte[] l);
    /** Document bytes in [start, end). */
    public static native byte[] getRange(long ptr, long start, long end);
    /** Attach a Lexilla lexer by name ("cpp", "python", ...). Returns false if unknown. */
    public static native boolean setLexer(long ptr, String name);

    public static native void resize(long ptr, int width, int height);
    public static native void paint(long ptr, SciSurface surface);
    public static native void setFocus(long ptr, boolean focus);

    public static native void mouseDown(long ptr, float x, float y, long timeMs, int modifiers);
    public static native void mouseMove(long ptr, float x, float y, long timeMs, int modifiers);
    public static native void mouseUp(long ptr, float x, float y, long timeMs, int modifiers);

    /** Returns true if the key was consumed. key is an SCK_* code or an ASCII value. */
    public static native boolean keyDown(long ptr, int key, int modifiers);
    /** Text typed directly (hardware keyboard). Inserted at every caret. */
    public static native void insertText(long ptr, String text);

    public static native void tick(long ptr, int reason);
    public static native boolean idle(long ptr);
    public static native void idleWork(long ptr);

    // Soft keyboard support. Offsets are UTF-16 code units from the start of the document.
    public static native void imeSetComposingText(long ptr, String text, int newCursorPosition);
    public static native void imeCommitText(long ptr, String text, int newCursorPosition);
    public static native void imeFinishComposing(long ptr);
    public static native void imeSetComposingRegion(long ptr, int start, int end);
    public static native void imeDeleteSurrounding(long ptr, int before, int after);
    public static native void imeSetSelection(long ptr, int start, int end);
    public static native String imeTextBefore(long ptr, int count);
    public static native String imeTextAfter(long ptr, int count);
    public static native String imeSelectedText(long ptr);
    /** Fills out[0..3] with selection start, selection end, composing start, composing end (-1 if none). */
    public static native void imeState(long ptr, int[] out);
}
