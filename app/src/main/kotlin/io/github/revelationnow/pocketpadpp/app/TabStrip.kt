package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The row of document tabs. */
class TabStrip(context: Context) : HorizontalScrollView(context) {

    class Tab(val title: String, val dirty: Boolean, val active: Boolean)

    var onSelect: ((Int) -> Unit)? = null
    var onClose: ((Int) -> Unit)? = null
    var onLongPress: ((Int, View) -> Unit)? = null

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val density = resources.displayMetrics.density

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        setBackgroundColor(BAR)
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    fun setTabs(tabs: List<Tab>) {
        row.removeAllViews()
        var activeView: View? = null
        tabs.forEachIndexed { index, tab ->
            val item = TabView(context, tab.active).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), 0, dp(2f), 0)
                setOnClickListener { onSelect?.invoke(index) }
                setOnLongClickListener { onLongPress?.invoke(index, it); true }
            }
            item.addView(TextView(context).apply {
                // Notepad++ marks unsaved tabs with a red icon; a red dot does the same job here.
                text = if (tab.dirty) "● ${tab.title}" else tab.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (tab.dirty) DIRTY else if (tab.active) ACTIVE_TEXT else INACTIVE_TEXT)
                setTypeface(typeface, if (tab.active) Typeface.BOLD else Typeface.NORMAL)
                maxLines = 1
            })
            item.addView(TextView(context).apply {
                text = "×"
                contentDescription = "Close ${tab.title}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(INACTIVE_TEXT)
                gravity = Gravity.CENTER
                setOnClickListener { onClose?.invoke(index) }
            }, LinearLayout.LayoutParams(dp(36f), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                rightMargin = dp(1f)
            })
            if (tab.active) activeView = item
        }
        activeView?.let { view ->
            post {
                // Keep the active tab on screen.
                val left = view.left
                val right = view.right
                if (left < scrollX) smoothScrollTo(left, 0) else if (right > scrollX + width) smoothScrollTo(right - width, 0)
            }
        }
    }

    /** A tab with Notepad++'s orange bar along the top of the active one. */
    private class TabView(context: Context, private val active: Boolean) : LinearLayout(context) {
        private val indicator = Paint().apply { color = ACTIVE_INDICATOR }

        init {
            setWillNotDraw(false)
            setBackgroundColor(if (active) ACTIVE_BACK else INACTIVE_BACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (active) canvas.drawRect(0f, 0f, width.toFloat(), 3f * resources.displayMetrics.density, indicator)
        }
    }

    private companion object {
        // From Notepad++'s default theme: active tab indicator, inactive tabs.
        const val ACTIVE_INDICATOR = 0xFFFAAA3C.toInt()
        const val ACTIVE_BACK = 0xFFFFFFFF.toInt()
        const val INACTIVE_BACK = 0xFFD8D8D8.toInt()
        const val BAR = 0xFFC0C0C0.toInt()
        const val ACTIVE_TEXT = 0xFF000000.toInt()
        const val INACTIVE_TEXT = 0xFF606060.toInt()
        const val DIRTY = 0xFFC02020.toInt()
    }
}
