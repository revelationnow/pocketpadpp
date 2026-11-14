package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.LuaHost
import io.github.revelationnow.pocketpadpp.scintilla.LuaNative

/**
 * The Lua interpreter with the scripting layer loaded: Notepad++'s LuaScript plugin, as far as it carries
 * over. Scripts get `editor` (every Scintilla function and property), `npp` (the application) and `print`.
 *
 * [prelude] is assets/lua/prelude.lua, which builds those objects; [sources] are the generated tables it
 * reads ("iface" and "constants"). Whatever a script asks of the application arrives at [app] as a name and
 * arguments (null, Boolean, Long, Double or String); what [app] returns goes back to the script, and what
 * it throws becomes an error there.
 */
class LuaEngine(
    private val prelude: String,
    private val sources: Map<String, String>,
    private val app: (name: String, args: Array<Any?>) -> Any?,
) : LuaHost {
    private var state = 0L

    /** The native editors scripts work on, asked for whenever Lua is about to run: the main one, and the second view or 0. */
    var editors: () -> Pair<Long, Long> = { 0L to 0L }

    val running: Boolean get() = state != 0L

    /** Starts a fresh interpreter, dropping the one before with everything scripts had set up. Returns null, or what went wrong. */
    fun start(): String? {
        stop()
        state = LuaNative.create(this)
        if (state == 0L) return "Lua could not be started"
        sync()
        return LuaNative.run(state, "=prelude", prelude.toByteArray())
    }

    fun stop() {
        if (state != 0L) LuaNative.destroy(state)
        state = 0L
    }

    /** How long one call into Lua may take before it is stopped. */
    fun setTimeLimit(millis: Int) {
        if (state != 0L) LuaNative.setTimeLimit(state, millis)
    }

    /** Runs a script. Errors name it by [name]. Returns null, or the error. */
    fun run(name: String, code: String): String? {
        if (state == 0L) return NOT_RUNNING
        sync()
        return LuaNative.run(state, "@$name", code.toByteArray())
    }

    /** A line from the console: an expression whose value is printed, or statements. */
    fun eval(code: String): String? = dispatch("eval", code)

    /** Calls the handlers scripts registered for an event with npp.AddEventHandler. */
    fun event(name: String, vararg args: Any?): String? = dispatch("event", name, *args)

    fun dispatch(what: String, vararg args: Any?): String? {
        if (state == 0L) return NOT_RUNNING
        sync()
        return LuaNative.dispatch(state, what, args)
    }

    override fun call(name: String, args: Array<Any?>): Any? {
        if (name == "source") return sources[args.getOrNull(0)]
        try {
            return app(name, args)
        } finally {
            // What the script asked for may have closed the second view; Lua must not keep a pointer to it.
            if (state != 0L) sync()
        }
    }

    private fun sync() {
        val (main, other) = editors()
        LuaNative.setEditors(state, main, other)
    }

    companion object {
        private const val NOT_RUNNING = "Lua is not running"

        /** What Edit Startup Script starts a new startup.lua with. */
        val STARTUP_TEMPLATE = """
            -- Startup script: runs when Pocketpad++ starts, and again each time this file is saved.
            -- The objects are those of Notepad++'s LuaScript plugin: editor, npp, print.
            -- Remove the "--" in front of an example to try it.

            -- A command in Plugins > Lua Script, with a shortcut:
            -- npp.AddShortcut("Insert Date", "Ctrl+Alt+D", function()
            --     editor:ReplaceSel(os.date("%Y-%m-%d"))
            -- end)

            -- Something done every time a file is saved:
            -- npp.AddEventHandler("OnBeforeSave", function(filename)
            --     for m in editor:match("[ \t]+${'$'}", SCFIND_REGEXP) do m:replace("") end
            -- end)
        """.trimIndent() + "\n"
    }
}

/** Notepad++'s menu commands by id, from assets/lua/menu.tsv, for npp:MenuCommand(IDM_...). */
class NppCommands(table: String) {
    private val paths = HashMap<Long, String>()

    init {
        for (line in table.lineSequence()) {
            val cells = line.split('\t')
            if (cells.size >= 3) cells[1].toLongOrNull()?.let { paths[it] = cells[2] }
        }
    }

    val size: Int get() = paths.size

    /** The command's place in Notepad++'s menus, "Edit > Line Operations > Join Lines". */
    fun path(id: Long): String? = paths[id]

    val all: Collection<String> get() = paths.values
}
