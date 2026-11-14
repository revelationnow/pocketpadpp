package io.github.revelationnow.pocketpadpp.scintilla;

/** Per-editor callbacks from the native Scintilla instance to its view. */
public interface SciHost {
    void invalidate();
    /** A Scintilla notification (SCN_*). Only the commonly used fields are forwarded. */
    void notify(int code, long position, int ch, int modifiers, int modificationType,
                long length, long linesAdded, long line, int margin, int updated);
    void startTimer(int reason, int millis);
    void cancelTimer(int reason);
    void setIdle(boolean on);
    void queueWork();

    /**
     * A step of a macro being recorded (between SCI_STARTRECORD and SCI_STOPRECORD): a message to send again
     * on playback. For messages whose last argument is text, {@code text} holds it and {@code lParam} is 0.
     */
    default void macroRecord(int message, long wParam, long lParam, byte[] text) {
    }
    void setClipboard(String text);
    String getClipboard();
}
