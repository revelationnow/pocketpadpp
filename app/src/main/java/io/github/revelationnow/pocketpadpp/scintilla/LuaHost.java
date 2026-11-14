package io.github.revelationnow.pocketpadpp.scintilla;

/** What Lua scripts ask of the application. */
public interface LuaHost {
    /**
     * A request by name. Arguments and the result are null, Boolean, Long, Double, String, or an Object[] of
     * those; an exception thrown here becomes an error in the script.
     */
    Object call(String name, Object[] args);
}
