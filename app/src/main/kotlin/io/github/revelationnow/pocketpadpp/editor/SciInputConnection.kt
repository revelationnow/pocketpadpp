package io.github.revelationnow.pocketpadpp.editor

import android.text.Editable
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import io.github.revelationnow.pocketpadpp.scintilla.ScintillaNative

/**
 * The soft keyboard's view of the editor.
 *
 * Android keyboards edit through this interface using UTF-16 offsets. Every request is forwarded to the
 * native layer, which applies it to the Scintilla document directly; there is no shadow copy of the text.
 */
internal class SciInputConnection(private val view: ScintillaView) : BaseInputConnection(view, true) {

    // BaseInputConnection wants an Editable for the methods not overridden here; it stays empty.
    private val unused: Editable = Editable.Factory.getInstance().newEditable("")

    private val ptr: Long get() = view.nativePtr()

    private fun log(message: String) {
        view.imeLog?.invoke(message)
    }

    private fun quote(text: CharSequence?): String =
        if (text == null) "null" else "\"" + text.toString().replace("\n", "\\n").take(40) + "\""

    /** Runs an edit, then reports the new selection to the keyboard unless a batch is open. */
    private inline fun edit(description: String, action: (Long) -> Unit): Boolean {
        log(description)
        val p = ptr
        if (p == 0L) return false
        action(p)
        view.syncIme()
        return true
    }

    // Edits held back while Ctrl or Alt is held on screen (see hold).
    private val heldEdits = ArrayList<() -> Unit>()
    private val applyHeld = Runnable { flushHeld("no key followed") }

    /**
     * A keyboard typing over a selection may first move the caret and delete the selected text, and only then
     * send the letter. With Ctrl or Alt held on screen that letter is a shortcut which needs the selection
     * (copy, cut), so such preparations wait a moment: a key that follows cancels them, otherwise they go ahead.
     * Returns true when the edit was held.
     */
    private fun hold(description: String, action: () -> Unit): Boolean {
        if (!view.softShortcutHeld) return false
        log("held: $description")
        heldEdits += action
        view.removeCallbacks(applyHeld)
        view.postDelayed(applyHeld, HOLD_MS)
        return true
    }

    private fun flushHeld(why: String) {
        view.removeCallbacks(applyHeld)
        if (heldEdits.isEmpty()) return
        val edits = heldEdits.toList()
        heldEdits.clear()
        log("applying ${edits.size} held ($why)")
        for (action in edits) action()
    }

    /** Text from the keyboard: a shortcut key when Ctrl or Alt is held on screen (true), otherwise an edit (false). */
    private fun usedAsShortcut(text: CharSequence, composing: Boolean): Boolean {
        if (!view.softShortcutHeld && heldEdits.isEmpty()) return false
        if (view.typedWithSoftModifiers(text, composing)) {
            view.removeCallbacks(applyHeld)
            if (heldEdits.isNotEmpty()) log("dropped ${heldEdits.size} held (a shortcut key followed)")
            heldEdits.clear()
            // The keyboard's idea of the text may now differ from the document; make it read it again.
            view.restartIme()
            return true
        }
        flushHeld("ordinary typing")
        return false
    }

    override fun getEditable(): Editable = unused

    override fun beginBatchEdit(): Boolean {
        view.imeBatchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (view.imeBatchDepth > 0) view.imeBatchDepth--
        if (view.imeBatchDepth == 0) view.syncIme()
        return view.imeBatchDepth > 0
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
        if (ptr != 0L) ScintillaNative.imeTextBefore(ptr, n) else ""

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
        if (ptr != 0L) ScintillaNative.imeTextAfter(ptr, n) else ""

    override fun getSelectedText(flags: Int): CharSequence? =
        if (ptr != 0L) ScintillaNative.imeSelectedText(ptr).ifEmpty { null } else null

    override fun getCursorCapsMode(reqModes: Int): Int {
        if (ptr == 0L) return 0
        val before = ScintillaNative.imeTextBefore(ptr, 64)
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        val p = ptr
        if (p == 0L) return null
        // A window around the caret rather than the whole document, which may be many megabytes.
        val before = ScintillaNative.imeTextBefore(p, EXTRACT_WINDOW)
        val selected = ScintillaNative.imeSelectedText(p)
        val after = ScintillaNative.imeTextAfter(p, EXTRACT_WINDOW)
        val state = IntArray(4)
        ScintillaNative.imeState(p, state)
        return ExtractedText().apply {
            text = before + selected + after
            startOffset = state[0] - before.length
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = before.length
            selectionEnd = before.length + selected.length
            this.flags = 0
        }
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
        if (usedAsShortcut(text, composing = true)) true else edit("setComposingText(${quote(text)}, $newCursorPosition)") {
            ScintillaNative.imeSetComposingText(it, text.toString(), newCursorPosition)
        }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        edit("setComposingRegion($start, $end)") { ScintillaNative.imeSetComposingRegion(it, start, end) }

    override fun finishComposingText(): Boolean =
        edit("finishComposingText()") { ScintillaNative.imeFinishComposing(it) }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
        if (usedAsShortcut(text, composing = false)) true else edit("commitText(${quote(text)}, $newCursorPosition)") {
            ScintillaNative.imeCommitText(it, text.toString(), newCursorPosition)
        }

    override fun commitCompletion(text: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val description = "deleteSurroundingText($beforeLength, $afterLength)"
        val delete = { edit(description) { ScintillaNative.imeDeleteSurrounding(it, beforeLength, afterLength) } }
        return hold(description) { delete() } || delete()
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val description = "deleteSurroundingTextInCodePoints($beforeLength, $afterLength)"
        val delete = {
            edit(description) {
                // Convert code points to UTF-16 units using the text actually around the caret.
                val before = ScintillaNative.imeTextBefore(it, beforeLength * 2)
                val after = ScintillaNative.imeTextAfter(it, afterLength * 2)
                val beforeUnits = before.length - before.offsetByCodePoints(before.length, -minOf(beforeLength, before.codePointCount(0, before.length)))
                val afterUnits = after.offsetByCodePoints(0, minOf(afterLength, after.codePointCount(0, after.length)))
                ScintillaNative.imeDeleteSurrounding(it, beforeUnits, afterUnits)
            }
        }
        return hold(description) { delete() } || delete()
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val description = "setSelection($start, $end)"
        val select = { edit(description) { ScintillaNative.imeSetSelection(it, start, end) } }
        return hold(description) { select() } || select()
    }

    override fun performContextMenuAction(id: Int): Boolean {
        log("performContextMenuAction($id)")
        when (id) {
            android.R.id.selectAll -> view.send(Sci.SCI_SELECTALL)
            android.R.id.cut -> view.send(Sci.SCI_CUT)
            android.R.id.copy -> view.send(Sci.SCI_COPY)
            android.R.id.paste, android.R.id.pasteAsPlainText -> view.send(Sci.SCI_PASTE)
            android.R.id.undo -> view.send(Sci.SCI_UNDO)
            android.R.id.redo -> view.send(Sci.SCI_REDO)
            else -> return false
        }
        view.syncIme()
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean =
        edit("performEditorAction($actionCode)") { ScintillaNative.keyDown(it, Sci.SCK_RETURN, 0) }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Handled at once rather than queued, so key events stay in order with the text edits around them.
        if (event.action == KeyEvent.ACTION_DOWN) {
            val description = "sendKeyEvent(${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")})"
            if (event.keyCode == KeyEvent.KEYCODE_DEL && hold(description) {
                    log(description)
                    view.handleKey(event)
                    view.syncIme()
                }
            ) return true
            if (event.keyCode != KeyEvent.KEYCODE_DEL) flushHeld("another key")
            log(description)
            if (!view.handleKey(event)) return super.sendKeyEvent(event)
            view.syncIme()
        }
        return true
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun closeConnection() {
        super.closeConnection()
        view.imeBatchDepth = 0
    }

    private companion object {
        const val EXTRACT_WINDOW = 2048
        const val HOLD_MS = 150L
    }
}
