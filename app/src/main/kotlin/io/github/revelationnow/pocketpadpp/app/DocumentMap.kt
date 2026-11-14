package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.FrameLayout
import io.github.revelationnow.pocketpadpp.editor.ScintillaView
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import kotlin.math.roundToLong

/**
 * Notepad++'s Document Map: the whole document in tiny print beside the editor, with the part that is on
 * screen marked. It is a second Scintilla view of the same document, so it shows the same colours and
 * follows every edit. A touch moves the editor to that place.
 */
class DocumentMap(context: Context, private val main: ScintillaView, accent: Int) : FrameLayout(context) {
    /** The small view. The application styles it, since styles belong to a view and not to the document. */
    val view = ScintillaView(context).apply { displayOnly = true }

    private val fill = Paint().apply { color = (accent and 0xFFFFFF) or 0x38000000 }
    private val edge = Paint().apply {
        color = (accent and 0xFFFFFF) or 0xC0000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private var markTop = 0f
    private var markBottom = 0f

    init {
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWillNotDraw(false)
        contentDescription = "Document map"
    }

    /** Shows the document the editor shows. Styling comes after this, from the application. */
    fun attach() {
        val document = main.send(Sci.SCI_GETDOCPOINTER)
        if (view.send(Sci.SCI_GETDOCPOINTER) != document) view.send(Sci.SCI_SETDOCPOINTER, 0, document)
    }

    /** Lets go of the document, for when the map is closed. */
    fun detach() {
        view.send(Sci.SCI_SETDOCPOINTER, 0, 0)
    }

    /** What a map needs besides the theme: no margins, no caret, no wrapping, nothing selected. */
    fun plain() {
        for (margin in 0..4) view.send(Sci.SCI_SETMARGINWIDTHN, margin.toLong(), 0)
        view.send(Sci.SCI_SETMARGINLEFT, 0, 2)
        view.send(Sci.SCI_SETWRAPMODE, Sci.SC_WRAP_NONE.toLong())
        view.send(Sci.SCI_SETCARETSTYLE, Sci.CARETSTYLE_INVISIBLE.toLong())
        view.send(Sci.SCI_SETCARETLINEVISIBLE, 0)
        view.send(Sci.SCI_SETVIEWWS, Sci.SCWS_INVISIBLE.toLong())
        view.send(Sci.SCI_SETVIEWEOL, 0)
        view.send(Sci.SCI_SETINDENTATIONGUIDES, Sci.SC_IV_NONE.toLong())
        view.send(Sci.SCI_SETEDGEMODE, Sci.EDGE_NONE.toLong())
        view.send(Sci.SCI_SETXOFFSET, 0)
        // The editor spaces its lines out a little for fingers; the map packs them.
        view.send(Sci.SCI_SETEXTRAASCENT, 0)
        view.send(Sci.SCI_SETEXTRADESCENT, 0)
    }

    private fun lineHeight(): Float = view.send(Sci.SCI_TEXTHEIGHT).toFloat().coerceAtLeast(1f)

    /** The first and one past the last document line the editor shows. */
    private fun shown(): Pair<Long, Long> {
        val first = main.send(Sci.SCI_GETFIRSTVISIBLELINE)
        val total = main.send(Sci.SCI_GETLINECOUNT)
        val top = main.send(Sci.SCI_DOCLINEFROMVISIBLE, first)
        val bottom = main.send(Sci.SCI_DOCLINEFROMVISIBLE, first + main.send(Sci.SCI_LINESONSCREEN))
        // Past the end, Scintilla answers with the last line.
        return top to (if (bottom <= top) total else bottom).coerceAtMost(total)
    }

    /** Scrolls the map so that the editor's part of the document is on it, and moves the mark there. */
    fun sync() {
        val (top, bottom) = shown()
        val total = main.send(Sci.SCI_GETLINECOUNT)
        val mapLines = view.send(Sci.SCI_LINESONSCREEN).coerceAtLeast(1)
        val span = (bottom - top).coerceAtLeast(1)
        val first = if (total <= mapLines) 0L else {
            // The map moves through the document at the pace the editor does: at its end when the editor is.
            (top.toDouble() / (total - span).coerceAtLeast(1) * (total - mapLines)).roundToLong().coerceIn(0, total - mapLines)
        }
        if (view.send(Sci.SCI_GETFIRSTVISIBLELINE) != first) view.send(Sci.SCI_SETFIRSTVISIBLELINE, first)
        val height = lineHeight()
        markTop = (top - first) * height
        markBottom = (markTop + span * height).coerceAtLeast(markTop + 4 * resources.displayMetrics.density)
        invalidate()
    }

    /** The document line under a point of the map. */
    private fun lineAt(y: Float): Long =
        (view.send(Sci.SCI_GETFIRSTVISIBLELINE) + (y / lineHeight()).toLong()).coerceIn(0, main.send(Sci.SCI_GETLINECOUNT) - 1)

    /** Puts a document line in the middle of the editor. */
    internal fun show(line: Long) {
        val onScreen = main.send(Sci.SCI_LINESONSCREEN)
        main.send(Sci.SCI_ENSUREVISIBLE, line)
        main.send(Sci.SCI_SETFIRSTVISIBLELINE, (main.send(Sci.SCI_VISIBLEFROMDOCLINE, line) - onScreen / 2).coerceAtLeast(0))
        sync()
    }

    // Every touch is the map's own: the small view below must not start selecting text.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                show(lineAt(event.y))
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, markTop, width.toFloat(), markBottom, fill)
        canvas.drawRect(0f, markTop, width.toFloat(), markBottom, edge)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { sync() }
    }

    /** For the on-device tests: where the mark is, as top and bottom in pixels. */
    internal val mark: Pair<Float, Float> get() = markTop to markBottom
}
