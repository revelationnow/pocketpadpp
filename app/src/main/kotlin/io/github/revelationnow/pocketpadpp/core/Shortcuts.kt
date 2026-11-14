package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

/** A key with its modifiers: [modifiers] are SCMOD_* bits, [key] a capital letter, a digit, an SCK_* code or a function key. */
data class KeyBinding(val modifiers: Int, val key: Int)

object Shortcuts {
    /** Added to n for the function keys: F3 is FUNCTION + 3. */
    const val FUNCTION = 0x1000

    /** Every key that can be part of a shortcut, by the name shown for it. */
    val KEYS: List<Pair<String, Int>> = buildList {
        for (c in 'A'..'Z') add(c.toString() to c.code)
        for (c in '0'..'9') add(c.toString() to c.code)
        for (n in 1..12) add("F$n" to FUNCTION + n)
        add("Tab" to Sci.SCK_TAB)
        add("Enter" to Sci.SCK_RETURN)
        add("Backspace" to Sci.SCK_BACK)
        add("Delete" to Sci.SCK_DELETE)
        add("Insert" to Sci.SCK_INSERT)
        add("Up" to Sci.SCK_UP)
        add("Down" to Sci.SCK_DOWN)
        add("Left" to Sci.SCK_LEFT)
        add("Right" to Sci.SCK_RIGHT)
        add("Home" to Sci.SCK_HOME)
        add("End" to Sci.SCK_END)
        add("Page Up" to Sci.SCK_PRIOR)
        add("Page Down" to Sci.SCK_NEXT)
        add("+" to Sci.SCK_ADD)
        add("-" to Sci.SCK_SUBTRACT)
        add("/" to Sci.SCK_DIVIDE)
    }

    private val names = KEYS.associate { it.second to it.first }

    fun keyName(key: Int): String = names[key] ?: "?"

    /** "Ctrl+Shift+S", or an empty text for no shortcut. */
    fun label(binding: KeyBinding?): String {
        if (binding == null) return ""
        val text = StringBuilder()
        if (binding.modifiers and Sci.SCMOD_CTRL != 0) text.append("Ctrl+")
        if (binding.modifiers and Sci.SCMOD_ALT != 0) text.append("Alt+")
        if (binding.modifiers and Sci.SCMOD_SHIFT != 0) text.append("Shift+")
        return text.append(keyName(binding.key)).toString()
    }

    /** A shortcut must not take over a key that types: it needs Ctrl or Alt unless it is a function key. */
    fun usable(binding: KeyBinding): Boolean =
        binding.key in names && (binding.key > FUNCTION || binding.modifiers and (Sci.SCMOD_CTRL or Sci.SCMOD_ALT) != 0)
}

/**
 * Which shortcut runs which command. Commands are known by an id; [defaults] are the shortcuts they come with
 * and the user's changes are kept apart from them, so they can be saved on their own and undone one by one.
 */
class ShortcutTable(private val defaults: Map<String, KeyBinding>) {
    // A null value means the user removed the command's shortcut.
    private val custom = LinkedHashMap<String, KeyBinding?>()

    fun binding(id: String): KeyBinding? = if (id in custom) custom[id] else defaults[id]

    fun isCustom(id: String): Boolean = id in custom

    val hasChanges: Boolean get() = custom.isNotEmpty()

    /** The commands this shortcut runs. Several can share one, such as start and stop recording. */
    fun commands(modifiers: Int, key: Int): List<String> {
        val wanted = KeyBinding(modifiers, key)
        return (defaults.keys + custom.keys).distinct().filter { binding(it) == wanted }
    }

    /** Gives the command this shortcut (or none), taking it away from the commands that had it; returns those. */
    fun assign(id: String, binding: KeyBinding?): List<String> {
        val losers = if (binding == null) emptyList() else commands(binding.modifiers, binding.key).filter { it != id }
        for (loser in losers) set(loser, null)
        set(id, binding)
        return losers
    }

    /** Back to the shortcut the command came with. Other commands keep what they have. */
    fun reset(id: String) {
        custom.remove(id)
    }

    fun resetAll() = custom.clear()

    private fun set(id: String, binding: KeyBinding?) {
        if (binding == defaults[id]) custom.remove(id) else custom[id] = binding
    }

    /** One line per changed command: modifiers, key and id, separated by tabs. */
    fun encode(): String = custom.entries.joinToString("\n") { (id, binding) ->
        "${binding?.modifiers ?: 0}\t${binding?.key ?: 0}\t$id"
    }

    fun decode(text: String) {
        custom.clear()
        for (line in text.split('\n')) {
            val cells = line.split('\t', limit = 3)
            if (cells.size < 3 || cells[2].isEmpty()) continue
            val modifiers = cells[0].toIntOrNull() ?: continue
            val key = cells[1].toIntOrNull() ?: continue
            set(cells[2], if (key == 0) null else KeyBinding(modifiers, key))
        }
    }
}
