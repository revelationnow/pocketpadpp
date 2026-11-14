package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

/** One recorded step: a Scintilla message with its arguments, the last of which may be text. */
class MacroStep(val message: Int, val wParam: Long, val lParam: Long, val text: ByteArray?)

/** Notepad++'s macros: what Scintilla records between Start Recording and Stop Recording, played back as is. */
object Macros {
    private fun step(sci: SciApi, step: MacroStep) {
        val text = step.text
        when {
            text == null -> sci.send(step.message, step.wParam, step.lParam)
            // These two take the length of the text as their first argument.
            step.message == Sci.SCI_ADDTEXT || step.message == Sci.SCI_APPENDTEXT -> sci.sendBytes(step.message, text.size.toLong(), text)
            else -> sci.sendBytes(step.message, step.wParam, text)
        }
    }

    /** Plays the macro once, as a single undo step. */
    fun play(sci: SciApi, steps: List<MacroStep>) {
        sci.send(Sci.SCI_BEGINUNDOACTION)
        for (step in steps) step(sci, step)
        sci.send(Sci.SCI_ENDUNDOACTION)
    }

    /** Run a Macro Multiple Times > Run N times. */
    fun playTimes(sci: SciApi, steps: List<MacroStep>, times: Int) {
        sci.send(Sci.SCI_BEGINUNDOACTION)
        repeat(times.coerceIn(0, MAX_RUNS)) { for (step in steps) step(sci, step) }
        sci.send(Sci.SCI_ENDUNDOACTION)
    }

    /**
     * Run a Macro Multiple Times > Run until the end of file: plays line after line for as long as the macro
     * moves the caret further down, so the last line gets its turn too. Returns the number of runs.
     */
    fun playToEnd(sci: SciApi, steps: List<MacroStep>): Int {
        var runs = 0
        sci.send(Sci.SCI_BEGINUNDOACTION)
        while (runs < MAX_RUNS) {
            val before = sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS))
            for (step in steps) step(sci, step)
            runs++
            if (sci.send(Sci.SCI_LINEFROMPOSITION, sci.send(Sci.SCI_GETCURRENTPOS)) <= before) break
        }
        sci.send(Sci.SCI_ENDUNDOACTION)
        return runs
    }

    /** A macro as one line of text, for keeping it in the settings. */
    fun encode(steps: List<MacroStep>): String = steps.joinToString(";") { step ->
        val text = step.text?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: "-"
        "${step.message},${step.wParam},${step.lParam},$text"
    }

    fun decode(line: String): List<MacroStep> = line.split(';').mapNotNull { part ->
        val fields = part.split(',')
        if (fields.size != 4) return@mapNotNull null
        val text = if (fields[3] == "-") null else runCatching { java.util.Base64.getDecoder().decode(fields[3]) }.getOrNull()
        MacroStep(fields[0].toIntOrNull() ?: return@mapNotNull null, fields[1].toLongOrNull() ?: 0, fields[2].toLongOrNull() ?: 0, text)
    }

    private const val MAX_RUNS = 1_000_000
}
