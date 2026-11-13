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
        edit("setComposingText(${quote(text)}, $newCursorPosition)") {
            ScintillaNative.imeSetComposingText(it, text.toString(), newCursorPosition)
        }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        edit("setComposingRegion($start, $end)") { ScintillaNative.imeSetComposingRegion(it, start, end) }

    override fun finishComposingText(): Boolean =
        edit("finishComposingText()") { ScintillaNative.imeFinishComposing(it) }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
        edit("commitText(${quote(text)}, $newCursorPosition)") {
            ScintillaNative.imeCommitText(it, text.toString(), newCursorPosition)
        }

    override fun commitCompletion(text: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        edit("deleteSurroundingText($beforeLength, $afterLength)") {
            ScintillaNative.imeDeleteSurrounding(it, beforeLength, afterLength)
        }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        edit("deleteSurroundingTextInCodePoints($beforeLength, $afterLength)") {
            // Convert code points to UTF-16 units using the text actually around the caret.
            val before = ScintillaNative.imeTextBefore(it, beforeLength * 2)
            val after = ScintillaNative.imeTextAfter(it, afterLength * 2)
            val beforeUnits = before.length - before.offsetByCodePoints(before.length, -minOf(beforeLength, before.codePointCount(0, before.length)))
            val afterUnits = after.offsetByCodePoints(0, minOf(afterLength, after.codePointCount(0, after.length)))
            ScintillaNative.imeDeleteSurrounding(it, beforeUnits, afterUnits)
        }

    override fun setSelection(start: Int, end: Int): Boolean =
        edit("setSelection($start, $end)") { ScintillaNative.imeSetSelection(it, start, end) }

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
            log("sendKeyEvent(${KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")})")
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
    }
}
