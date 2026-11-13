package io.github.revelationnow.pocketpadpp.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import io.github.revelationnow.pocketpadpp.core.SciApi
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import io.github.revelationnow.pocketpadpp.scintilla.SciHost
import io.github.revelationnow.pocketpadpp.scintilla.ScintillaNative
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A Scintilla editor as an Android view.
 *
 * Scintilla does the editing, layout and painting. This class supplies what a phone needs on top:
 * pixel-smooth touch scrolling and flinging, tap / double-tap / long-press selection with draggable
 * handles, a floating cut-copy-paste toolbar, and the soft keyboard connection.
 */
class ScintillaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), SciHost, SciApi {

    /** How the soft keyboard is asked to behave. */
    enum class KeyboardMode {
        /** No suggestions, autocorrect or swipe typing: every key press arrives as typed. Best for code. */
        CODE,

        /** Suggestions, autocorrect and swipe typing, using composing text. Best for prose. */
        TEXT,
    }

    private var ptr: Long = 0
    private val surface = AndroidSurface.forView()
    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Called after the caret, selection, text or scroll position changed. Safe to query the editor from here. */
    var onStateChanged: (() -> Unit)? = null

    /** Called after the document text changed. */
    var onTextChanged: (() -> Unit)? = null

    /**
     * Called for every typed character (SCN_CHARADDED), while the editor is still handling the key.
     * The handler may edit the document, as auto-indent does.
     */
    var onCharAdded: ((Int) -> Unit)? = null

    /**
     * Modifiers held by the on-screen Ctrl, Alt and Shift keys, as SCMOD_* bits. They apply to whatever key
     * comes next, from the soft keyboard, the extra keys row or a hardware keyboard.
     */
    var softModifiers = 0

    /** Called after a key used [softModifiers], so that one-shot modifiers can be released. */
    var onSoftModifiersUsed: (() -> Unit)? = null

    /**
     * Application shortcuts. Receives the key (an upper-case letter, a digit, an SCK_* code or
     * [KEY_FUNCTION] + n for F-keys) and SCMOD_* bits; returns true when it handled the key.
     */
    var onShortcut: ((Int, Int) -> Boolean)? = null

    /** Called when a pinch changed the zoom level, so that margins sized to the text can follow. */
    var onZoomChanged: (() -> Unit)? = null

    /** Receives one line per soft keyboard request, for diagnosing keyboard behaviour. */
    var imeLog: ((String) -> Unit)? = null

    var keyboardMode: KeyboardMode = KeyboardMode.CODE
        set(value) {
            if (field != value) {
                field = value
                if (ptr != 0L) ScintillaNative.imeFinishComposing(ptr)
                imm.restartInput(this)
            }
        }

    // Scrolling. Scintilla scrolls vertically by whole lines, so the view shifts the painted content up by
    // [fracY] pixels within the first visible line and gives Scintilla a client area one line taller than the view.
    private var lineHeight = 1
    private var fracY = 0
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val viewConfig = ViewConfiguration.get(context)
    private val touchSlop = viewConfig.scaledTouchSlop
    private var lastScrollTime = 0L

    // Touch state
    private enum class Touch { NONE, PENDING, SCROLL, HANDLE, SELECT_DRAG, THUMB, ZOOM }

    private var touch = Touch.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var scrollHorizontal = false
    private var dragFixed = 0L          // The selection end that stays put while a handle is dragged.
    private var dragOffsetX = 0f        // From the finger to the text point the handle stands for.
    private var dragOffsetY = 0f
    private var dragWordStart = 0L      // Word picked by the long press that started a selection drag.
    private var dragWordEnd = 0L
    private var caretHandleUntil = 0L   // Uptime until which the insertion handle stays visible.

    private val handleRadius = 11f * density
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A7BDE.toInt() }
    private val handlePath = Path()
    // A mid grey shows on light and dark themes alike.
    private val scrollBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99808080.toInt() }
    private val thumbRect = RectF()
    private var thumbGrabOffset = 0f
    private var pinchStartSpan = 0f
    private var pinchStartSize = 0L
    private val edgeZone = 48f * density

    private var actionMode: ActionMode? = null

    // Notifications arrive in the middle of native work (often while painting), so reactions are deferred.
    private var pendingUpdate = 0
    private var updatePosted = false
    private val lastImeState = IntArray(4) { -2 }
    private val imeState = IntArray(4)
    internal var imeBatchDepth = 0

    private val timers = arrayOfNulls<Runnable>(8)
    private var idleOn = false

    // region Public API

    override fun send(msg: Int, wParam: Long, lParam: Long): Long =
        if (ptr != 0L) ScintillaNative.send(ptr, msg, wParam, lParam) else 0

    override fun sendBytes(msg: Int, wParam: Long, bytes: ByteArray): Long =
        if (ptr != 0L) ScintillaNative.sendString(ptr, msg, wParam, bytes) else 0

    override fun sendStrings(msg: Int, first: String, second: String): Long =
        if (ptr != 0L) {
            ScintillaNative.sendStrings(ptr, msg, first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
        } else {
            0
        }

    override fun range(start: Long, end: Long): ByteArray =
        if (ptr != 0L) ScintillaNative.getRange(ptr, start, end) else ByteArray(0)

    /** Attaches a Lexilla lexer ("cpp", "python", "xml", ...) to the current document. */
    override fun setLexer(name: String): Boolean = ptr != 0L && ScintillaNative.setLexer(ptr, name)

    /**
     * Call after SCI_SETDOCPOINTER or after replacing the whole text: the soft keyboard and the
     * scroll state both refer to the previous content.
     */
    fun documentSwitched() {
        if (ptr == 0L) return
        ScintillaNative.imeFinishComposing(ptr)
        scroller.forceFinished(true)
        fracY = 0
        actionMode?.finish()
        lastImeState.fill(-2)
        imm.restartInput(this)
        invalidate()
    }

    var text: String
        get() = if (ptr != 0L) String(ScintillaNative.getRange(ptr, 0, send(Sci.SCI_GETLENGTH)), Charsets.UTF_8) else ""
        set(value) {
            if (ptr == 0L) return
            ScintillaNative.imeFinishComposing(ptr)
            val bytes = value.toByteArray(Charsets.UTF_8)
            send(Sci.SCI_CLEARALL)
            ScintillaNative.sendString(ptr, Sci.SCI_ADDTEXT, bytes.size.toLong(), bytes)
            send(Sci.SCI_GOTOPOS, 0)
            send(Sci.SCI_EMPTYUNDOBUFFER)
            documentSwitched()
        }

    /** Types text at every caret, as a hardware keyboard would. */
    fun typeText(text: String) {
        if (ptr != 0L) ScintillaNative.insertText(ptr, text)
    }

    /** A key with modifiers: the application's shortcuts get it first, then Scintilla's own key map. */
    fun shortcutKey(key: Int, modifiers: Int): Boolean {
        if (ptr == 0L) return false
        if (onShortcut?.invoke(key, modifiers) == true) return true
        return key < KEY_FUNCTION && ScintillaNative.keyDown(ptr, key, modifiers)
    }

    /**
     * A character from the soft keyboard while Ctrl or Alt is held on screen is a shortcut, not text.
     * Returns true when the text was used up that way.
     */
    internal fun typedWithSoftModifiers(text: CharSequence, composing: Boolean): Boolean {
        val modifiers = softModifiers
        if (modifiers == 0 || ptr == 0L) return false
        var typed: Char? = null
        if (modifiers and (Sci.SCMOD_CTRL or Sci.SCMOD_ALT) != 0) {
            if (text.isEmpty() && hasSelection()) {
                // Some keyboards clear the selection before sending the key typed over it. With Ctrl or Alt
                // held the key is a shortcut that needs that selection (copy, cut), so it is left alone.
                return true
            }
            if (!composing) {
                if (text.length == 1) typed = text[0]
            } else {
                // With suggestions on, the keyboard sends the whole word it is composing on every key press,
                // and after a paste or a tap it adopts the word around the caret. The key just pressed is the
                // one character added to that word at the caret, in place of whatever part of it is selected.
                ScintillaNative.imeState(ptr, imeState)
                val (selStart, selEnd, wordStart, wordEnd) = imeState
                val composed = if (wordStart >= 0) wordEnd - wordStart else 0
                val inside = wordStart >= 0 && selStart >= wordStart && selEnd <= wordEnd
                val selected = if (inside) selEnd - selStart else 0
                val at = if (inside) selStart - wordStart else composed
                typed = when {
                    text.length == composed - selected + 1 && at in text.indices -> text[at]
                    text.length == 1 -> text[0]
                    else -> null
                }
            }
        }
        if (typed == null) {
            // Shift alone, or text that is not a single key press: ordinary typing, which still uses up a one-shot key.
            if (!composing) onSoftModifiersUsed?.invoke()
            return false
        }
        // The word being composed stays as it is; the shortcut must not act on a half-finished composition.
        if (composing) ScintillaNative.imeFinishComposing(ptr)
        val ch: Char = typed
        val key = when {
            ch.isLetter() -> ch.uppercaseChar().code
            ch == '+' || ch == '=' -> Sci.SCK_ADD
            ch == '-' -> Sci.SCK_SUBTRACT
            ch == '/' -> Sci.SCK_DIVIDE
            else -> ch.code
        }
        shortcutKey(key, modifiers)
        onSoftModifiersUsed?.invoke()
        // The keyboard believes it has started a word; make it start afresh.
        if (composing) imm.restartInput(this)
        return true
    }

    /** Presses a Scintilla key (SCK_*) with SCMOD_* modifiers. */
    fun pressKey(key: Int, modifiers: Int = 0): Boolean = ptr != 0L && ScintillaNative.keyDown(ptr, key, modifiers)

    fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, 0)
    }

    /** Frees the native editor. The view must not be used afterwards. */
    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        actionMode?.finish()
        val p = ptr
        ptr = 0
        if (p != 0L) ScintillaNative.destroy(p)
    }

    // endregion

    // region Drawing and layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ptr == 0L) return
        lineHeight = send(Sci.SCI_TEXTHEIGHT).toInt().coerceAtLeast(1)
        ScintillaNative.resize(ptr, w, h + lineHeight)
        if (h < oldh && hasFocus()) {
            // Usually the keyboard opening: keep the caret in view.
            handler.post {
                if (ptr != 0L) {
                    send(Sci.SCI_SCROLLCARET)
                    revealCaret()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (ptr == 0L) return
        val textHeight = send(Sci.SCI_TEXTHEIGHT).toInt().coerceAtLeast(1)
        if (textHeight != lineHeight) {
            // Font or zoom changed.
            lineHeight = textHeight
            ScintillaNative.resize(ptr, width, height + lineHeight)
            fracY = fracY.coerceIn(0, lineHeight)
        }
        canvas.save()
        canvas.translate(0f, -fracY.toFloat())
        surface.canvas = canvas
        ScintillaNative.paint(ptr, surface)
        surface.canvas = null
        canvas.restore()
        drawHandles(canvas)
        drawScrollBar(canvas)
    }

    private fun totalDisplayLines(): Long = send(Sci.SCI_VISIBLEFROMDOCLINE, send(Sci.SCI_GETLINECOUNT)).coerceAtLeast(1)

    private fun maxTopLine(): Long = (totalDisplayLines() - send(Sci.SCI_LINESONSCREEN)).coerceAtLeast(0)

    private fun maxScrollY(): Long = (totalDisplayLines() * lineHeight - height).coerceAtLeast(0)

    private fun scrollYPixels(): Long = send(Sci.SCI_GETFIRSTVISIBLELINE) * lineHeight + fracY

    private fun scrollTextYTo(target: Long) {
        val y = target.coerceIn(0, maxScrollY())
        val top = min(y / lineHeight, maxTopLine())
        fracY = (y - top * lineHeight).toInt()
        if (top != send(Sci.SCI_GETFIRSTVISIBLELINE)) {
            send(Sci.SCI_SETFIRSTVISIBLELINE, top)
        }
        lastScrollTime = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    private fun marginsWidth(): Int {
        var total = send(Sci.SCI_GETMARGINLEFT).toInt()
        for (margin in 0 until send(Sci.SCI_GETMARGINS).toInt()) {
            total += send(Sci.SCI_GETMARGINWIDTHN, margin.toLong()).toInt()
        }
        return total
    }

    private fun maxScrollX(): Int {
        if (send(Sci.SCI_GETWRAPMODE).toInt() != Sci.SC_WRAP_NONE) return 0
        val textArea = width - marginsWidth() - send(Sci.SCI_GETMARGINRIGHT).toInt()
        return (send(Sci.SCI_GETSCROLLWIDTH).toInt() - textArea).coerceAtLeast(0)
    }

    private fun scrollTextXTo(target: Int) {
        val x = target.coerceIn(0, maxScrollX())
        if (x.toLong() != send(Sci.SCI_GETXOFFSET)) {
            send(Sci.SCI_SETXOFFSET, x.toLong())
            invalidate()
        }
    }

    /** Scintilla keeps the caret inside its (taller) client area; this finishes the job for the visible part. */
    private fun revealCaret() {
        val top = send(Sci.SCI_GETFIRSTVISIBLELINE)
        if (fracY >= lineHeight && top < maxTopLine()) {
            fracY = 0
            send(Sci.SCI_SETFIRSTVISIBLELINE, top + 1)
        }
        val caretY = send(Sci.SCI_POINTYFROMPOSITION, 0, send(Sci.SCI_GETCURRENTPOS)).toInt()
        if (caretY < fracY) {
            scrollTextYTo(scrollYPixels() - (fracY - caretY))
        } else if (caretY + lineHeight > fracY + height) {
            scrollTextYTo(scrollYPixels() + (caretY + lineHeight - fracY - height))
        }
    }

    override fun computeScroll() {
        if (ptr != 0L && scroller.computeScrollOffset()) {
            scrollTextXTo(scroller.currX)
            scrollTextYTo(scroller.currY.toLong())
            actionMode?.invalidateContentRect()
            postInvalidateOnAnimation()
        }
    }

    /**
     * Works out where the scroll indicator is. In long documents it is a thumb wide enough to drag,
     * which is the quick way to get around a big file on a touch screen.
     * Returns false when there is nothing to scroll or the indicator has faded.
     */
    private fun layoutThumb(): Boolean {
        if (touch != Touch.THUMB && android.os.SystemClock.uptimeMillis() - lastScrollTime > SCROLL_BAR_MS) return false
        val maxY = maxScrollY()
        if (maxY <= 0) return false
        val draggable = maxY > height * 2L
        val barWidth = (if (draggable) 14f else 4f) * density
        val barHeight = max((if (draggable) 52f else 32f) * density, height.toFloat() * height / (maxY + height))
        val top = (height - barHeight) * scrollYPixels() / maxY
        thumbRect.set(width - barWidth, top, width.toFloat(), top + barHeight)
        return true
    }

    private fun thumbIsDraggable() = maxScrollY() > height * 2L

    private fun drawScrollBar(canvas: Canvas) {
        if (!layoutThumb()) return
        val radius = if (thumbIsDraggable()) 5f * density else 0f
        canvas.drawRoundRect(thumbRect, radius, radius, scrollBarPaint)
        postInvalidateDelayed(SCROLL_BAR_MS + 100)
    }

    private fun tryGrabThumb(x: Float, y: Float): Boolean {
        if (!thumbIsDraggable() || !layoutThumb()) return false
        val reach = 14f * density
        if (x < thumbRect.left - reach * 1.5f || y < thumbRect.top - reach || y > thumbRect.bottom + reach) return false
        thumbGrabOffset = (y - thumbRect.top).coerceIn(0f, thumbRect.height())
        return true
    }

    private fun dragThumbTo(y: Float) {
        val track = height - thumbRect.height()
        if (track <= 0f) return
        val fraction = ((y - thumbGrabOffset) / track).coerceIn(0f, 1f)
        scrollTextYTo((fraction.toDouble() * maxScrollY()).toLong())
    }

    // endregion

    // region Pinch zoom

    private fun pinchSpan(event: MotionEvent): Float =
        kotlin.math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun startPinch(event: MotionEvent) {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(edgeScroll)
        scroller.forceFinished(true)
        touch = Touch.ZOOM
        pinchStartSpan = pinchSpan(event).coerceAtLeast(1f)
        pinchStartSize = send(Sci.SCI_STYLEGETSIZE, Sci.STYLE_DEFAULT.toLong()) + send(Sci.SCI_GETZOOM)
    }

    /** Text size follows the distance between the two fingers, within Notepad++'s zoom range. */
    private fun pinchTo(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val base = send(Sci.SCI_STYLEGETSIZE, Sci.STYLE_DEFAULT.toLong())
        val size = (pinchStartSize * pinchSpan(event) / pinchStartSpan).roundToInt().toLong()
        val zoom = (size - base).coerceIn(max(-10L, 4L - base), 20L)
        if (zoom != send(Sci.SCI_GETZOOM)) {
            send(Sci.SCI_SETZOOM, zoom)
            onZoomChanged?.invoke()
            invalidate()
        }
    }

    // endregion

    // region Selection handles

    private fun selectionStart() = send(Sci.SCI_GETSELECTIONSTART)
    private fun selectionEnd() = send(Sci.SCI_GETSELECTIONEND)
    private fun hasSelection() = selectionStart() != selectionEnd()

    /** View x of a document position. */
    private fun xOf(position: Long) = send(Sci.SCI_POINTXFROMPOSITION, 0, position).toFloat()

    /** View y of the bottom of the line holding a document position. */
    private fun bottomOf(position: Long) = (send(Sci.SCI_POINTYFROMPOSITION, 0, position) - fracY + lineHeight).toFloat()

    private fun positionAt(x: Float, y: Float): Long =
        send(Sci.SCI_POSITIONFROMPOINT, x.roundToInt().toLong(), (y + fracY).roundToInt().toLong())

    private fun caretHandleVisible() =
        hasFocus() && !hasSelection() && android.os.SystemClock.uptimeMillis() < caretHandleUntil

    /** kind: -1 selection start (drawn to the left), 0 caret (centred), 1 selection end (to the right). */
    private fun handleCentreX(x: Float, kind: Int) = x + kind * handleRadius

    private fun drawHandle(canvas: Canvas, x: Float, bottom: Float, kind: Int) {
        if (bottom < 0 || bottom > height + handleRadius || x < marginsWidth() - 1) return
        val cx = handleCentreX(x, kind)
        val cy = bottom + handleRadius
        handlePath.rewind()
        handlePath.addCircle(cx, cy, handleRadius, Path.Direction.CW)
        when (kind) {
            -1 -> handlePath.addRect(cx, bottom, cx + handleRadius, cy, Path.Direction.CW)
            1 -> handlePath.addRect(cx - handleRadius, bottom, cx, cy, Path.Direction.CW)
            else -> {
                // A small point so the handle visibly hangs from the caret.
                handlePath.moveTo(cx, bottom - handleRadius * 0.4f)
                handlePath.lineTo(cx + handleRadius * 0.7f, cy - handleRadius * 0.7f)
                handlePath.lineTo(cx - handleRadius * 0.7f, cy - handleRadius * 0.7f)
                handlePath.close()
            }
        }
        canvas.drawPath(handlePath, handlePaint)
    }

    private fun drawHandles(canvas: Canvas) {
        if (!hasFocus()) return
        if (hasSelection()) {
            val start = selectionStart()
            val end = selectionEnd()
            drawHandle(canvas, xOf(start), bottomOf(start), -1)
            drawHandle(canvas, xOf(end), bottomOf(end), 1)
        } else if (caretHandleVisible() || (touch == Touch.HANDLE)) {
            val caret = send(Sci.SCI_GETCURRENTPOS)
            drawHandle(canvas, xOf(caret), bottomOf(caret), 0)
        }
    }

    private fun hitsHandle(x: Float, y: Float, position: Long, kind: Int): Boolean {
        val cx = handleCentreX(xOf(position), kind)
        val cy = bottomOf(position) + handleRadius
        val reach = handleRadius * 2f
        // Only the part that hangs below the text counts. A touch on the line itself is a tap on the text;
        // otherwise the second tap of a double tap would pick up the handle the first tap brought out.
        return abs(x - cx) <= reach && y >= bottomOf(position) - handleRadius * 0.25f && y - cy <= reach
    }

    /** Starts dragging a handle if the touch landed on one. */
    private fun tryGrabHandle(x: Float, y: Float): Boolean {
        val moving: Long
        if (hasSelection()) {
            val start = selectionStart()
            val end = selectionEnd()
            when {
                hitsHandle(x, y, end, 1) -> { moving = end; dragFixed = start }
                hitsHandle(x, y, start, -1) -> { moving = start; dragFixed = end }
                else -> return false
            }
        } else if (caretHandleVisible() && hitsHandle(x, y, send(Sci.SCI_GETCURRENTPOS), 0)) {
            moving = send(Sci.SCI_GETCURRENTPOS)
            dragFixed = -1
        } else {
            return false
        }
        // Remember where the finger is relative to the middle of the handle's text line.
        dragOffsetX = xOf(moving) - x
        dragOffsetY = (bottomOf(moving) - lineHeight / 2f) - y
        return true
    }

    private fun dragHandleTo(x: Float, y: Float) {
        val position = positionAt(x + dragOffsetX, (y + dragOffsetY).coerceIn(1f, height - 1f))
        if (dragFixed < 0) {
            send(Sci.SCI_SETEMPTYSELECTION, position)
            caretHandleUntil = android.os.SystemClock.uptimeMillis() + CARET_HANDLE_MS
        } else {
            send(Sci.SCI_SETANCHOR, dragFixed)
            send(Sci.SCI_SETCURRENTPOS, position)
        }
        invalidate()
    }

    private fun dragSelectionTo(x: Float, y: Float) {
        val position = positionAt(x, y.coerceIn(1f, height - 1f))
        if (position < dragWordStart) {
            send(Sci.SCI_SETANCHOR, dragWordEnd)
            send(Sci.SCI_SETCURRENTPOS, position)
        } else {
            send(Sci.SCI_SETANCHOR, dragWordStart)
            send(Sci.SCI_SETCURRENTPOS, max(position, dragWordEnd))
        }
        invalidate()
    }

    /** While a drag is held near the top or bottom edge, keep scrolling and extending the selection. */
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (ptr == 0L || (touch != Touch.HANDLE && touch != Touch.SELECT_DRAG)) return
            val step = when {
                lastY < edgeZone -> -((edgeZone - lastY) / 3f).roundToInt().coerceAtLeast(1)
                lastY > height - edgeZone -> ((lastY - (height - edgeZone)) / 3f).roundToInt().coerceAtLeast(1)
                else -> 0
            }
            if (step != 0) {
                scrollTextYTo(scrollYPixels() + step)
                if (touch == Touch.HANDLE) dragHandleTo(lastX, lastY) else dragSelectionTo(lastX, lastY)
                handler.postDelayed(this, 16)
            }
        }
    }

    private fun updateEdgeScroll() {
        handler.removeCallbacks(edgeScroll)
        if (lastY < edgeZone || lastY > height - edgeZone) handler.postDelayed(edgeScroll, 16)
    }

    // endregion

    // region Touch

    private val longPress = Runnable {
        if (ptr == 0L || touch != Touch.PENDING) return@Runnable
        val position = positionAt(downX, downY)
        val wordStart = send(Sci.SCI_WORDSTARTPOSITION, position, 1)
        val wordEnd = send(Sci.SCI_WORDENDPOSITION, position, 1)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (wordStart == wordEnd) {
            // Nothing to select here: place the caret and offer paste.
            send(Sci.SCI_SETEMPTYSELECTION, position)
            touch = Touch.NONE
            showActionMode()
        } else {
            send(Sci.SCI_SETSEL, wordStart, wordEnd)
            dragWordStart = wordStart
            dragWordEnd = wordEnd
            touch = Touch.SELECT_DRAG
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ptr == 0L) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = x; downY = y; lastX = x; lastY = y
                downTime = event.eventTime
                if (tryGrabThumb(x, y)) {
                    touch = Touch.THUMB
                    actionMode?.finish()
                } else if (tryGrabHandle(x, y)) {
                    touch = Touch.HANDLE
                    actionMode?.finish()
                } else {
                    touch = Touch.PENDING
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger turns whatever was starting into a pinch, unless a handle is being dragged.
                if (event.pointerCount == 2 && (touch == Touch.PENDING || touch == Touch.SCROLL)) startPinch(event)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                when (touch) {
                    Touch.THUMB -> dragThumbTo(y)
                    Touch.ZOOM -> pinchTo(event)
                    Touch.PENDING -> if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) {
                        handler.removeCallbacks(longPress)
                        touch = Touch.SCROLL
                        // Lock to one axis; sideways scrolling only exists when lines are not wrapped.
                        scrollHorizontal = abs(x - downX) > abs(y - downY) * 1.5f && maxScrollX() > 0
                        actionMode?.finish()
                    }

                    Touch.SCROLL -> if (scrollHorizontal) {
                        scrollTextXTo(send(Sci.SCI_GETXOFFSET).toInt() + (lastX - x).roundToInt())
                    } else {
                        scrollTextYTo(scrollYPixels() + (lastY - y).roundToInt())
                    }

                    Touch.HANDLE -> { dragHandleTo(x, y); lastX = x; lastY = y; updateEdgeScroll() }
                    Touch.SELECT_DRAG -> { dragSelectionTo(x, y); lastX = x; lastY = y; updateEdgeScroll() }
                    Touch.NONE -> {}
                }
                lastX = x; lastY = y
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(edgeScroll)
                when (touch) {
                    Touch.PENDING -> tap(x, y, event.eventTime)
                    Touch.SCROLL -> fling()
                    Touch.HANDLE, Touch.SELECT_DRAG -> if (hasSelection()) showActionMode()
                    Touch.THUMB -> lastScrollTime = android.os.SystemClock.uptimeMillis()
                    Touch.ZOOM, Touch.NONE -> {}
                }
                touch = Touch.NONE
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(edgeScroll)
                touch = Touch.NONE
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float, upTime: Long) {
        // Scintilla sees a mouse click, which also gives double tap = word and triple tap = line.
        ScintillaNative.mouseDown(ptr, x, y + fracY, downTime, 0)
        ScintillaNative.mouseUp(ptr, x, y + fracY, upTime, 0)
        if (x < marginsWidth()) return  // Margin taps fold or select lines; they should not raise the keyboard.
        if (hasSelection()) {
            showActionMode()
        } else {
            actionMode?.finish()
            caretHandleUntil = android.os.SystemClock.uptimeMillis() + CARET_HANDLE_MS
            postInvalidateDelayed(CARET_HANDLE_MS + 50)
        }
        showKeyboard()
    }

    private fun fling() {
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, viewConfig.scaledMaximumFlingVelocity.toFloat())
        val vx = if (scrollHorizontal) -tracker.xVelocity.toInt() else 0
        val vy = if (scrollHorizontal) 0 else -tracker.yVelocity.toInt()
        if (abs(vx) < viewConfig.scaledMinimumFlingVelocity && abs(vy) < viewConfig.scaledMinimumFlingVelocity) return
        val maxY = maxScrollY().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        scroller.fling(
            send(Sci.SCI_GETXOFFSET).toInt(), scrollYPixels().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            vx, vy, 0, maxScrollX(), 0, maxY,
        )
        postInvalidateOnAnimation()
    }

    // endregion

    // region Cut / copy / paste toolbar

    private fun showActionMode() {
        val current = actionMode
        if (current != null) {
            current.invalidate()
            current.invalidateContentRect()
        } else {
            actionMode = startActionMode(actionCallback, ActionMode.TYPE_FLOATING)
        }
    }

    private val actionCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            populate(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            populate(menu)
            return true
        }

        private fun populate(menu: Menu) {
            menu.clear()
            if (hasSelection()) {
                menu.add(Menu.NONE, android.R.id.cut, 0, android.R.string.cut)
                menu.add(Menu.NONE, android.R.id.copy, 1, android.R.string.copy)
            }
            if (clipboard.hasPrimaryClip()) {
                menu.add(Menu.NONE, android.R.id.paste, 2, android.R.string.paste)
            }
            menu.add(Menu.NONE, android.R.id.selectAll, 3, android.R.string.selectAll)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (ptr == 0L) return false
            when (item.itemId) {
                android.R.id.cut -> send(Sci.SCI_CUT)
                android.R.id.copy -> { send(Sci.SCI_COPY); send(Sci.SCI_SETEMPTYSELECTION, send(Sci.SCI_GETCURRENTPOS)) }
                android.R.id.paste -> send(Sci.SCI_PASTE)
                android.R.id.selectAll -> { send(Sci.SCI_SELECTALL); mode.invalidate(); invalidate(); return true }
                else -> return false
            }
            mode.finish()
            invalidate()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val start = selectionStart()
            val end = selectionEnd()
            val top = (bottomOf(start) - lineHeight).toInt().coerceIn(0, height)
            val bottom = (bottomOf(end) + if (hasSelection()) handleRadius * 2 else 0f).toInt().coerceIn(top, height)
            val left = min(xOf(start), xOf(end)).toInt().coerceIn(0, width)
            val right = max(xOf(start), xOf(end)).toInt().coerceIn(left, width)
            if (bottomOf(start) == bottomOf(end)) outRect.set(left, top, right, bottom) else outRect.set(0, top, width, bottom)
        }
    }

    // endregion

    // region Keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

    /** Handles a key press from a hardware keyboard or one the soft keyboard sent as a key event. */
    internal fun handleKey(event: KeyEvent): Boolean {
        if (ptr == 0L || event.action != KeyEvent.ACTION_DOWN) return false
        val hardware = (if (event.isShiftPressed) Sci.SCMOD_SHIFT else 0) or
            (if (event.isCtrlPressed) Sci.SCMOD_CTRL else 0) or
            (if (event.isAltPressed) Sci.SCMOD_ALT else 0)
        val modifiers = hardware or softModifiers
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Sci.SCK_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Sci.SCK_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Sci.SCK_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Sci.SCK_DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> Sci.SCK_HOME
            KeyEvent.KEYCODE_MOVE_END -> Sci.SCK_END
            KeyEvent.KEYCODE_PAGE_UP -> Sci.SCK_PRIOR
            KeyEvent.KEYCODE_PAGE_DOWN -> Sci.SCK_NEXT
            KeyEvent.KEYCODE_DEL -> Sci.SCK_BACK
            KeyEvent.KEYCODE_FORWARD_DEL -> Sci.SCK_DELETE
            KeyEvent.KEYCODE_INSERT -> Sci.SCK_INSERT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Sci.SCK_RETURN
            KeyEvent.KEYCODE_TAB -> Sci.SCK_TAB
            KeyEvent.KEYCODE_ESCAPE -> Sci.SCK_ESCAPE
            else -> 0
        }
        if (key != 0) {
            if (modifiers == 0) ScintillaNative.keyDown(ptr, key, 0) else shortcutKey(key, modifiers)
            if (softModifiers != 0) onSoftModifiersUsed?.invoke()
            return true
        }
        if (event.keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            val handled = onShortcut?.invoke(KEY_FUNCTION + 1 + (event.keyCode - KeyEvent.KEYCODE_F1), modifiers) == true
            if (softModifiers != 0) onSoftModifiersUsed?.invoke()
            return handled
        }
        val shortcut = when (event.keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'A'.code + (event.keyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (event.keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> Sci.SCK_ADD
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Sci.SCK_SUBTRACT
            KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> Sci.SCK_DIVIDE
            else -> 0
        }
        if (shortcut != 0 && modifiers and Sci.SCMOD_CTRL != 0) {
            // The application's shortcuts first; Scintilla's own key map provides Ctrl+Z, Y, X, C, V, A, D, L, T, U and friends.
            val handled = shortcutKey(shortcut, modifiers)
            if (softModifiers != 0) onSoftModifiersUsed?.invoke()
            return handled
        }
        if (shortcut != 0 && modifiers and Sci.SCMOD_ALT != 0 && onShortcut?.invoke(shortcut, modifiers) == true) {
            // Only when the application has a use for it: on many keyboards the right Alt key types characters.
            if (softModifiers != 0) onSoftModifiersUsed?.invoke()
            return true
        }
        val ch = event.unicodeChar
        if (ch != 0 && (ch and KeyCharacterMap.COMBINING_ACCENT) == 0 && !Character.isISOControl(ch)) {
            ScintillaNative.insertText(ptr, String(Character.toChars(ch)))
            if (softModifiers != 0) onSoftModifiersUsed?.invoke()
            return true
        }
        return false
    }

    // endregion

    // region Soft keyboard

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (ptr == 0L) return null
        ScintillaNative.imeFinishComposing(ptr)
        outAttrs.inputType = when (keyboardMode) {
            KeyboardMode.CODE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            KeyboardMode.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
        ScintillaNative.imeState(ptr, imeState)
        outAttrs.initialSelStart = imeState[0]
        outAttrs.initialSelEnd = imeState[1]
        imeState.copyInto(lastImeState)
        imeBatchDepth = 0
        return SciInputConnection(this)
    }

    internal fun nativePtr(): Long = ptr

    /** Tells the soft keyboard where the selection and composing text are, if that changed. */
    internal fun syncIme() {
        if (ptr == 0L || imeBatchDepth > 0) return
        ScintillaNative.imeState(ptr, imeState)
        if (!imeState.contentEquals(lastImeState)) {
            imeState.copyInto(lastImeState)
            imm.updateSelection(this, imeState[0], imeState[1], imeState[2], imeState[3])
        }
    }

    // endregion

    // region SciHost: calls from the native editor

    // SciHost.invalidate() is View.invalidate().

    override fun notify(
        code: Int, position: Long, ch: Int, modifiers: Int, modificationType: Int,
        length: Long, linesAdded: Long, line: Long, margin: Int, updated: Int,
    ) {
        if (code == Sci.SCN_CHARADDED) {
            onCharAdded?.invoke(ch)
        } else if (code == Sci.SCN_UPDATEUI) {
            pendingUpdate = pendingUpdate or updated
            if (!updatePosted) {
                updatePosted = true
                handler.post(updateRunnable)
            }
        }
    }

    private val updateRunnable = Runnable {
        updatePosted = false
        val what = pendingUpdate
        pendingUpdate = 0
        if (ptr == 0L) return@Runnable
        // SC_UPDATE_CONTENT also fires when lines are merely styled as they scroll into view, so only a moved
        // caret or changed text counts as a reason to bring the caret back on screen.
        val caretMoved = what and Sci.SC_UPDATE_SELECTION != 0
        val textChanged = what and Sci.SC_UPDATE_TEXT != 0
        if (caretMoved || textChanged) {
            if (touch == Touch.NONE && scroller.isFinished) {
                revealCaret()
            }
            syncIme()
        }
        if (textChanged) {
            if (!hasSelection() && touch == Touch.NONE) actionMode?.finish()
            onTextChanged?.invoke()
        }
        onStateChanged?.invoke()
        invalidate()
    }

    override fun startTimer(reason: Int, millis: Int) {
        if (reason !in timers.indices) return
        cancelTimer(reason)
        val period = millis.coerceAtLeast(1).toLong()
        val timer = object : Runnable {
            override fun run() {
                if (ptr == 0L || timers[reason] !== this) return
                ScintillaNative.tick(ptr, reason)
                // The tick may have cancelled or replaced this timer.
                if (ptr != 0L && timers[reason] === this) handler.postDelayed(this, period)
            }
        }
        timers[reason] = timer
        handler.postDelayed(timer, period)
    }

    override fun cancelTimer(reason: Int) {
        if (reason !in timers.indices) return
        timers[reason]?.let { handler.removeCallbacks(it) }
        timers[reason] = null
    }

    private val idleRunnable = object : Runnable {
        override fun run() {
            if (ptr == 0L || !idleOn) return
            if (ScintillaNative.idle(ptr) && idleOn) handler.post(this)
        }
    }

    override fun setIdle(on: Boolean) {
        idleOn = on
        handler.removeCallbacks(idleRunnable)
        if (on) handler.post(idleRunnable)
    }

    override fun queueWork() {
        handler.post { if (ptr != 0L) ScintillaNative.idleWork(ptr) }
    }

    override fun setClipboard(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Pocketpad++", text))
    }

    override fun getClipboard(): String {
        val clip = clipboard.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
    }

    // endregion

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (ptr == 0L) return
        ScintillaNative.setFocus(ptr, gainFocus && hasWindowFocus())
        if (!gainFocus) actionMode?.finish()
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (ptr != 0L) ScintillaNative.setFocus(ptr, hasWindowFocus && hasFocus())
    }

    // Kept after every property: creating the native editor can call back into this object straight away,
    // and Kotlin initialises properties and init blocks in the order they appear.
    init {
        ensurePlatform(context)
        ptr = ScintillaNative.create(this)
        isFocusable = true
        isFocusableInTouchMode = true
        send(Sci.SCI_SETCARETWIDTH, (2 * density).roundToInt().coerceIn(1, 20).toLong())
        send(Sci.SCI_SETSCROLLWIDTHTRACKING, 1)
        send(Sci.SCI_SETSCROLLWIDTH, 1)
        send(Sci.SCI_SETMULTIPLESELECTION, 1)
        send(Sci.SCI_SETADDITIONALSELECTIONTYPING, 1)
        send(Sci.SCI_SETMULTIPASTE, Sci.SC_MULTIPASTE_EACH.toLong())
        send(Sci.SCI_SETEXTRAASCENT, density.roundToInt().toLong())
        send(Sci.SCI_SETEXTRADESCENT, density.roundToInt().toLong())
        // Scintilla draws visible spaces as dots one pixel wide by default, which a phone screen swallows.
        send(Sci.SCI_SETWHITESPACESIZE, (2 * density).roundToInt().coerceAtLeast(1).toLong())
    }

    companion object {
        /** Added to n for the F-keys in [onShortcut]: F3 is KEY_FUNCTION + 3. */
        const val KEY_FUNCTION = 0x1000

        private const val CARET_HANDLE_MS = 4000L
        private const val SCROLL_BAR_MS = 1500L
        private var platformReady = false

        private fun ensurePlatform(context: Context) {
            if (platformReady) return
            val metrics = context.resources.displayMetrics
            // Style sizes are treated as sp, so they follow the system font size setting.
            val pixelsPerPoint = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, metrics)
            val doubleTapSlop = ViewConfiguration.get(context).scaledTouchSlop * 3f
            ScintillaNative.init(AndroidPlatform, pixelsPerPoint, doubleTapSlop)
            platformReady = true
        }
    }
}
