package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import io.github.revelationnow.pocketpadpp.core.HexBuffer
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import kotlin.math.max
import kotlin.math.min

/**
 * The Hex Editor's view: a column of offsets, the bytes as hex, and the same bytes as text. A tap picks a
 * byte, in either column. Typed hex digits (or, in the text column, characters) overwrite from there on.
 */
class HexView(context: Context) : View(context) {
    var buffer = HexBuffer(ByteArray(0))
        set(value) {
            field = value
            cursor = cursor.coerceIn(0, value.size)
            invalidate()
        }

    /** The byte typing goes to; may be one past the last byte, where typing adds bytes. */
    var cursor = 0
        private set

    /** True while the next digit is the first half of the byte. */
    var highHalf = true
        private set

    /** True when the text column was picked: typed characters go in as they are. */
    var inText = false
        private set

    /** 0 to fit as many as the width takes (8 or 16), or a fixed number. */
    var bytesPerRowWanted = 0
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var bytesPerRow = 8
        private set

    /** Called after the cursor moved or the bytes changed. */
    var onChanged: (() -> Unit)? = null

    /** A letter or digit typed with Ctrl or Alt held: the key as an upper-case character and SCMOD_* bits. */
    var onShortcut: ((Int, Int) -> Boolean)? = null

    private val density = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val mark = Paint()
    private val soft = Paint()
    private var background = 0xFFFFFFFF.toInt()
    private var charWidth = 1f
    private var rowHeight = 1f
    private var baseline = 0f
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private val pad = 6 * density

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Hex editor"
        setTextSize(13f)
        setColours(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF2A7BDE.toInt())
    }

    fun setTextSize(sp: Float) {
        text.textSize = sp * resources.displayMetrics.scaledDensity
        dim.textSize = text.textSize
        charWidth = text.measureText("0")
        val metrics = text.fontMetrics
        rowHeight = (metrics.descent - metrics.ascent) + 4 * density
        baseline = -metrics.ascent + 2 * density
        requestLayout()
        invalidate()
    }

    fun setColours(foreground: Int, back: Int, accent: Int) {
        text.color = foreground
        dim.color = (foreground and 0xFFFFFF) or 0x99000000.toInt()
        mark.color = (accent and 0xFFFFFF) or 0x66000000
        soft.color = (accent and 0xFFFFFF) or 0x2A000000
        background = back
        invalidate()
    }

    // Columns, in characters: "00000000  " then "XX " per byte, a blank, then one character per byte.
    private fun hexStart() = pad + 10 * charWidth
    private fun textStart() = hexStart() + (bytesPerRow * 3 + 1) * charWidth
    private fun widthFor(perRow: Int) = 2 * pad + (10 + perRow * 3 + 1 + perRow) * charWidth

    private val rows: Int get() = buffer.size / bytesPerRow + 1
    private fun maxScroll() = max(0f, rows * rowHeight - height + rowHeight)

    /** How many rows fit on screen, for paging. */
    val rowsOnScreen: Int get() = max(1, (height / rowHeight).toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bytesPerRow = when {
            bytesPerRowWanted > 0 -> bytesPerRowWanted
            widthFor(16) <= w -> 16
            widthFor(8) <= w -> 8
            else -> 4
        }
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    override fun requestLayout() {
        super.requestLayout()
        if (width > 0) onSizeChanged(width, height, width, height)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        val first = (scroll / rowHeight).toInt()
        val last = min(rows - 1, first + (height / rowHeight).toInt() + 1)
        val hexX = hexStart()
        val textX = textStart()
        val line = StringBuilder()
        for (row in first..last) {
            val top = row * rowHeight - scroll
            val offset = row * bytesPerRow
            canvas.drawText("%08X".format(offset), pad, top + baseline, dim)
            // The cursor, strongly in the column typing goes to and softly in the other.
            if (cursor >= offset && cursor < offset + bytesPerRow) {
                val column = cursor - offset
                val hexLeft = hexX + column * 3 * charWidth
                val charLeft = textX + column * charWidth
                canvas.drawRect(hexLeft - charWidth / 4, top, hexLeft + 2 * charWidth + charWidth / 4, top + rowHeight, if (inText) soft else mark)
                canvas.drawRect(charLeft, top, charLeft + charWidth, top + rowHeight, if (inText) mark else soft)
                if (!inText) {
                    // A line under the half of the byte the next digit goes to.
                    val half = hexLeft + (if (highHalf) 0f else charWidth)
                    canvas.drawRect(half, top + rowHeight - 2 * density, half + charWidth, top + rowHeight, text)
                }
            }
            val count = min(bytesPerRow, buffer.size - offset)
            if (count <= 0) continue
            line.setLength(0)
            for (i in 0 until count) line.append(HexBuffer.hex(buffer[offset + i])).append(' ')
            canvas.drawText(line, 0, line.length, hexX, top + baseline, text)
            line.setLength(0)
            for (i in 0 until count) line.append(HexBuffer.shown(buffer[offset + i]))
            canvas.drawText(line, 0, line.length, textX, top + baseline, text)
        }
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    /** Where a byte is drawn in the hex column, in view coordinates; for the tests. */
    fun pointOf(offset: Int, text: Boolean = false): Pair<Float, Float> {
        val row = offset / bytesPerRow
        val column = offset % bytesPerRow
        val x = if (text) textStart() + (column + 0.5f) * charWidth else hexStart() + (column * 3 + 1) * charWidth
        return x to (row + 0.5f) * rowHeight - scroll
    }

    private fun offsetAt(x: Float, y: Float): Pair<Int, Boolean> {
        val row = ((y + scroll) / rowHeight).toInt().coerceIn(0, rows - 1)
        val text = x >= textStart() - charWidth / 2
        val column = if (text) ((x - textStart()) / charWidth).toInt() else ((x - hexStart() + charWidth / 2) / (3 * charWidth)).toInt()
        return (row * bytesPerRow + column.coerceIn(0, bytesPerRow - 1)).coerceIn(0, buffer.size) to text
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (offset, text) = offsetAt(e.x, e.y)
            setCursor(offset, text)
            requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this@HexView, 0)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scroll = (scroll + distanceY).coerceIn(0f, maxScroll())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(0, scroll.toInt(), 0, -velocityY.toInt(), 0, 0, 0, maxScroll().toInt())
            postInvalidateOnAnimation()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    fun setCursor(offset: Int, text: Boolean = inText) {
        cursor = offset.coerceIn(0, buffer.size)
        inText = text
        highHalf = true
        reveal()
        changed()
    }

    private fun changed() {
        invalidate()
        onChanged?.invoke()
    }

    /** Scrolls just enough to bring the cursor's row on screen. */
    private fun reveal() {
        val top = (cursor / bytesPerRow) * rowHeight
        if (top < scroll) scroll = top
        if (top + rowHeight > scroll + height && height > 0) scroll = top + rowHeight - height
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    /** Typed characters: hex digits in the hex column, any printable ASCII character in the text column. */
    fun type(characters: CharSequence) {
        for (c in characters) {
            if (inText) {
                if (c.code in 0x20..0x7E) {
                    buffer.overwrite(cursor, c.code)
                    cursor++
                }
            } else {
                val digit = HexBuffer.digit(c)
                if (digit < 0) continue
                buffer.typeDigit(cursor, highHalf, digit)
                if (highHalf) highHalf = false else {
                    highHalf = true
                    cursor++
                }
            }
        }
        reveal()
        changed()
    }

    fun insertByte() {
        buffer.insert(cursor, byteArrayOf(0))
        highHalf = true
        changed()
    }

    fun deleteByte() {
        if (cursor < buffer.size) buffer.delete(cursor)
        highHalf = true
        changed()
    }

    fun undo() {
        val at = buffer.undo()
        if (at >= 0) setCursor(at) else changed()
    }

    fun redo() {
        val at = buffer.redo()
        if (at >= 0) setCursor(at) else changed()
    }

    /** A key: arrows, Home, End, the page keys, and Backspace to step back. Returns false for keys with no use here. */
    fun key(keyCode: Int): Boolean {
        val rowStart = cursor - cursor % bytesPerRow
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DEL -> setCursor(cursor - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> setCursor(cursor + 1)
            KeyEvent.KEYCODE_DPAD_UP -> setCursor(if (cursor >= bytesPerRow) cursor - bytesPerRow else cursor)
            KeyEvent.KEYCODE_DPAD_DOWN -> setCursor(min(cursor + bytesPerRow, buffer.size))
            KeyEvent.KEYCODE_MOVE_HOME -> setCursor(rowStart)
            KeyEvent.KEYCODE_MOVE_END -> setCursor(min(rowStart + bytesPerRow - 1, buffer.size))
            KeyEvent.KEYCODE_PAGE_UP -> setCursor(max(cursor - rowsOnScreen * bytesPerRow, cursor % bytesPerRow))
            KeyEvent.KEYCODE_PAGE_DOWN -> setCursor(min(cursor + rowsOnScreen * bytesPerRow, buffer.size))
            KeyEvent.KEYCODE_FORWARD_DEL -> deleteByte()
            KeyEvent.KEYCODE_TAB -> setCursor(cursor, !inText)
            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed || event.isAltPressed) {
            val character = when (keyCode) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'A'.code + (keyCode - KeyEvent.KEYCODE_A)
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (keyCode - KeyEvent.KEYCODE_0)
                else -> 0
            }
            val modifiers = (if (event.isCtrlPressed) Sci.SCMOD_CTRL else 0) or (if (event.isAltPressed) Sci.SCMOD_ALT else 0) or
                (if (event.isShiftPressed) Sci.SCMOD_SHIFT else 0)
            if (modifiers == Sci.SCMOD_CTRL && character == 'Z'.code) return true.also { undo() }
            if (modifiers == Sci.SCMOD_CTRL && character == 'Y'.code) return true.also { redo() }
            if (character != 0 && onShortcut?.invoke(character, modifiers) == true) return true
            return super.onKeyDown(keyCode, event)
        }
        if (key(keyCode)) return true
        val typed = event.unicodeChar
        if (typed != 0 && !event.isCtrlPressed && !event.isAltPressed) {
            type(typed.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                type(text)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { key(KeyEvent.KEYCODE_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }
}
