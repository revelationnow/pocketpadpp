package io.github.revelationnow.pocketpadpp.scintilla;

/**
 * The Lua interpreter, which lives in the same native library as Scintilla so that scripts reach the
 * editor without a detour through Java. Everything here is for the UI thread only.
 */
public final class LuaNative {
    private LuaNative() {}

    static {
        System.loadLibrary("nppscintilla");
    }

    /** A new interpreter with Lua's standard libraries and a table of native functions called "host". */
    public static native long create(LuaHost host);

    /** Frees the interpreter. If called from inside a running script, it is freed when that script returns. */
    public static native void destroy(long state);

    /** The editors scripts work on, as pointers from {@link ScintillaNative#create}: the main one and the second view, or 0. */
    public static native void setEditors(long state, long main, long other);

    /** How long one call into Lua may run before it is stopped with an error. */
    public static native void setTimeLimit(long state, int millis);

    /** Runs a piece of Lua source. Returns null, or the error message. */
    public static native String run(long state, String chunkName, byte[] source);

    /** Calls the global Lua function __dispatch(what, ...). Returns null, or the error message. */
    public static native String dispatch(long state, String what, Object[] args);
}
