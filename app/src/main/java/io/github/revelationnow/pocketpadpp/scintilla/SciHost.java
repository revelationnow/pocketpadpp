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
    void setClipboard(String text);
    String getClipboard();
}
