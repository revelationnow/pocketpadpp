package io.github.revelationnow.pocketpadpp.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/** The rows of one page of the side panel, described by the code that shows it. */
class PanelSpec {
    class Row(
        val label: String,
        val depth: Int,
        /** Shown before the label: an arrow for folders, a dot for unsaved documents. */
        val mark: String,
        val strong: Boolean,
        val dimmed: Boolean,
        val onClick: (() -> Unit)?,
        val onLongClick: (() -> Unit)?,
    )

    val rows = ArrayList<Row>()
    val actions = ArrayList<Pair<String, () -> Unit>>()

    fun row(
        label: String, depth: Int = 0, mark: String = "", strong: Boolean = false, dimmed: Boolean = false,
        onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null,
    ) {
        rows += Row(label, depth, mark, strong, dimmed, onClick, onLongClick)
    }

    /** A button under the page's tabs, such as Add Folder. */
    fun action(label: String, run: () -> Unit) {
        actions += label to run
    }
}

/**
 * Notepad++ docks its panels (Document List, Folder as Workspace, Function List) beside the editor. A phone has
 * no room for that, so they share one panel that slides over the editor from the left, with a tab for each.
 */
class SidePanel(private val context: Context, private val dark: Boolean) {
    class Page(val title: String, val build: PanelSpec.() -> Unit)

    private val density = context.resources.displayMetrics.density
    private val textColour = if (dark) 0xFFE6E6E6.toInt() else 0xFF202020.toInt()
    private val dim = if (dark) 0xFF8A8A8A.toInt() else 0xFF8A8A8A.toInt()
    private val back = if (dark) 0xFF2B2B2B.toInt() else 0xFFFFFFFF.toInt()
    private val headerBack = if (dark) 0xFF383838.toInt() else 0xFFEFEFEF.toInt()
    private val accent = if (dark) 0xFF5AA2F0.toInt() else 0xFF2A7BDE.toInt()

    private var pages: List<Page> = emptyList()
    private var shown = 0
    private val tabs = LinearLayout(context)
    private val actions = LinearLayout(context)
    private val rows = LinearLayout(context)
    private val scroller = ScrollView(context)
    private val root = LinearLayout(context)
    private val dialog = Dialog(context)

    /** Called with the title of the page that was switched to. */
    var onPage: ((String) -> Unit)? = null

    val isShowing: Boolean get() = dialog.isShowing
    val page: String get() = pages.getOrNull(shown)?.title ?: ""

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun ripple(): android.graphics.drawable.Drawable? {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return if (value.resourceId != 0) context.getDrawable(value.resourceId) else null
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(back)
        tabs.orientation = LinearLayout.HORIZONTAL
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBack)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "✕"
                contentDescription = "Close panel"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(dim)
                gravity = Gravity.CENTER
                background = ripple()
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        }
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
        actions.orientation = LinearLayout.HORIZONTAL
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rows.orientation = LinearLayout.VERTICAL
        scroller.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
    }

    fun show(pages: List<Page>, open: String) {
        this.pages = pages
        shown = pages.indexOfFirst { it.title == open }.coerceAtLeast(0)
        render(keepScroll = false)
        dialog.show()
        dialog.window?.apply {
            val metrics = context.resources.displayMetrics
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.START or Gravity.TOP)
            setLayout(min((metrics.widthPixels * 0.84f).toInt(), dp(420f)), ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.25f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
            setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }

    fun dismiss() = dialog.dismiss()

    /** Draws the page again after what it lists has changed, staying where it was scrolled to. */
    fun refresh() {
        if (dialog.isShowing) render(keepScroll = true)
    }

    private fun render(keepScroll: Boolean) {
        val scrollY = if (keepScroll) scroller.scrollY else 0
        tabs.removeAllViews()
        pages.forEachIndexed { index, page ->
            tabs.addView(TextView(context).apply {
                text = page.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, if (index == shown) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (index == shown) accent else textColour)
                gravity = Gravity.CENTER
                setPadding(dp(12f), 0, dp(12f), 0)
                background = ripple()
                setOnClickListener {
                    if (index != shown) {
                        shown = index
                        onPage?.invoke(page.title)
                        render(keepScroll = false)
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)))
        }
        val spec = PanelSpec().apply(pages[shown].build)
        actions.removeAllViews()
        for ((label, run) in spec.actions) {
            actions.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(accent)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = ripple()
                setOnClickListener { run() }
            })
        }
        rows.removeAllViews()
        for (row in spec.rows) rows.addView(rowView(row), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroller.scrollTo(0, scrollY)
        scroller.post { scroller.scrollTo(0, scrollY) }
    }

    private fun rowView(row: PanelSpec.Row): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(42f)
        setPadding(dp(8f) + dp(16f) * row.depth, 0, dp(10f), 0)
        if (row.onClick != null) {
            background = ripple()
            setOnClickListener { row.onClick.invoke() }
        }
        if (row.onLongClick != null) {
            setOnLongClickListener {
                row.onLongClick.invoke()
                true
            }
        }
        addView(TextView(context).apply {
            text = row.mark
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (row.strong) accent else dim)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24f), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = row.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, if (row.strong) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (row.dimmed) dim else textColour)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(2f), dp(8f), dp(4f), dp(8f))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
}
