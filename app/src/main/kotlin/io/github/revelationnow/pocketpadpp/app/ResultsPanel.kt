package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.revelationnow.pocketpadpp.core.Hit
import kotlin.math.roundToInt

/**
 * Notepad++'s search results window as a panel under the editor: the hits of a Find All or Find in Files,
 * grouped by document, each a line that can be tapped to go there.
 */
class ResultsPanel(context: Context, private val palette: Palette) : LinearLayout(context) {

    /** The hits in one document. [key] is whatever the owner needs to find the document again. */
    class Group(val key: Any, val name: String, val hits: List<Hit>)

    private class Row(val group: Group, val hit: Hit?)

    /** Called when a hit is chosen, by a tap or by [step]. */
    var onOpen: ((Group, Hit) -> Unit)? = null

    /** Called when the panel is closed, which also stops a search that is still running. */
    var onClose: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val rows = ArrayList<Row>()
    private var selected = -1
    private val title = TextView(context)
    private val list = ListView(context)

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].hit == null) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val view = (convertView as? TextView) ?: TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            }
            val hit = row.hit
            if (hit == null) {
                view.typeface = Typeface.DEFAULT_BOLD
                view.setTextColor(palette.chromeText)
                view.setBackgroundColor(palette.keysBack)
                view.text = "${row.group.name} (${row.group.hits.size} ${if (row.group.hits.size == 1) "hit" else "hits"})"
            } else {
                view.typeface = Typeface.MONOSPACE
                view.setTextColor(palette.chromeText)
                view.setBackgroundColor(if (position == selected) palette.tabInactiveBack else 0)
                val prefix = "Line ${hit.line + 1}: "
                val shown = SpannableString(prefix + hit.text)
                val from = (prefix.length + hit.column).coerceAtMost(shown.length)
                val to = (from + hit.length).coerceAtMost(shown.length)
                if (to > from) shown.setSpan(BackgroundColorSpan(palette.resultMatch), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                view.text = shown
            }
            return view
        }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        setBackgroundColor(palette.findBack)
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.chrome)
        }
        title.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.chromeText)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            setPadding(dp(10f), 0, dp(4f), 0)
        }
        header.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "✕"
            contentDescription = "Close search results"
            gravity = Gravity.CENTER
            setTextColor(palette.chromeDimText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener { close() }
        }, LayoutParams(dp(44f), dp(36f)))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> choose(position) }
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE
    val hasResults: Boolean get() = rows.isNotEmpty()

    /** Empties the panel and shows it, ready for [add]. */
    fun begin(heading: String) {
        rows.clear()
        selected = -1
        title.text = heading
        adapter.notifyDataSetChanged()
        visibility = View.VISIBLE
    }

    fun add(group: Group) {
        rows += Row(group, null)
        for (hit in group.hits) rows += Row(group, hit)
        adapter.notifyDataSetChanged()
    }

    fun setHeading(heading: String) {
        title.text = heading
    }

    fun show() {
        visibility = View.VISIBLE
    }

    fun close() {
        if (!isOpen) return
        visibility = View.GONE
        onClose?.invoke()
    }

    private fun choose(position: Int) {
        val row = rows.getOrNull(position) ?: return
        val hit = row.hit ?: return
        selected = position
        adapter.notifyDataSetChanged()
        onOpen?.invoke(row.group, hit)
    }

    /** Search > Next / Previous Search Result: moves to the neighbouring hit, wrapping around. False when there are none. */
    fun step(forward: Boolean): Boolean {
        val hits = rows.indices.filter { rows[it].hit != null }
        if (hits.isEmpty()) return false
        val next = if (forward) hits.firstOrNull { it > selected } ?: hits.first() else hits.lastOrNull { it < selected } ?: hits.last()
        list.setSelection((next - 1).coerceAtLeast(0))
        choose(next)
        return true
    }
}
