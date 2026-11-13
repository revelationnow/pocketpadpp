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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** The content of one menu level, described by the code that opens it. */
class MenuSpec {
    class Entry(
        val label: String,
        val enabled: Boolean,
        val checked: Boolean?,
        val action: (() -> Unit)?,
        val children: (MenuSpec.() -> Unit)?,
    )

    val entries = ArrayList<Entry>()

    /** A command. With [checked] set it is a toggle or radio item and the menu stays open after it runs. */
    fun item(label: String, enabled: Boolean = true, checked: Boolean? = null, action: () -> Unit) {
        entries += Entry(label, enabled, checked, action, null)
    }

    /** A submenu, to any depth. Its content is built when it is opened, so it always shows current state. */
    fun sub(label: String, build: MenuSpec.() -> Unit) {
        entries += Entry(label, true, null, null, build)
    }
}

/**
 * A drill-down menu: one panel at the bottom of the screen that replaces its content when a submenu is
 * opened, with a back arrow in the header (and the Back key) to return to the level above.
 */
class MenuSheet(private val context: Context, private val dark: Boolean = false) {

    private class Level(val title: String, val build: MenuSpec.() -> Unit)

    /** Called after any command ran, so the screen around the menu can refresh. */
    var onAction: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val stack = ArrayList<Level>()
    private val textColour = if (dark) 0xFFE6E6E6.toInt() else 0xFF202020.toInt()
    private val dim = if (dark) 0xFF8A8A8A.toInt() else 0xFF9A9A9A.toInt()
    private val back = if (dark) 0xFF2B2B2B.toInt() else 0xFFFFFFFF.toInt()
    private val headerBack = if (dark) 0xFF383838.toInt() else 0xFFEFEFEF.toInt()
    private val accent = 0xFF2A7BDE.toInt()

    private val header = LinearLayout(context)
    private val backButton = TextView(context)
    private val title = TextView(context)
    private val rows = LinearLayout(context)
    private val scroller = ScrollView(context)

    private val dialog = object : Dialog(context) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            if (!up()) dismiss()
        }
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private fun ripple(): android.graphics.drawable.Drawable? {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return if (value.resourceId != 0) context.getDrawable(value.resourceId) else null
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(back)
        }
        header.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(headerBack)
        }
        backButton.apply {
            this.text = "‹"
            contentDescription = "Back"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(accent)
            gravity = Gravity.CENTER
            background = ripple()
            setOnClickListener { up() }
        }
        title.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColour)
            maxLines = 1
            setPadding(dp(4f), 0, dp(12f), 0)
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            this.text = "✕"
            contentDescription = "Close menu"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(dim)
            gravity = Gravity.CENTER
            background = ripple()
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))

        rows.orientation = LinearLayout.VERTICAL
        scroller.addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun dismiss() = dialog.dismiss()

    fun show(menuTitle: String, build: MenuSpec.() -> Unit) {
        stack.clear()
        stack += Level(menuTitle, build)
        render()
        dialog.show()
        dialog.window?.apply {
            // Across the bottom of the screen, never taller than about two thirds of it.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Leave the soft keyboard as it is.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
            setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }

    /** Goes up one level. Returns false at the top. */
    private fun up(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        render()
        return true
    }

    private fun render() {
        val level = stack.last()
        val spec = MenuSpec().apply(level.build)
        title.text = stack.joinToString("  ›  ") { it.title }
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.INVISIBLE
        rows.removeAllViews()
        for (entry in spec.entries) {
            rows.addView(row(entry), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.62f).toInt()
        scroller.layoutParams = (scroller.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (spec.entries.size * dp(46f) > maxHeight) maxHeight else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        scroller.scrollTo(0, 0)
    }

    private fun row(entry: MenuSpec.Entry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46f)
            setPadding(dp(8f), 0, dp(12f), 0)
            if (entry.enabled) {
                background = ripple()
                setOnClickListener { choose(entry) }
            }
        }
        row.addView(TextView(context).apply {
            this.text = if (entry.checked == true) "✓" else ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(accent)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(32f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(context).apply {
            this.text = entry.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (entry.enabled) textColour else dim)
            setPadding(dp(4f), dp(8f), dp(8f), dp(8f))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (entry.children != null) {
            row.addView(TextView(context).apply {
                this.text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(dim)
            })
        }
        return row
    }

    private fun choose(entry: MenuSpec.Entry) {
        val children = entry.children
        when {
            children != null -> {
                stack += Level(entry.label, children)
                render()
            }
            entry.checked != null -> {
                // Toggles keep the menu open so several can be changed in one visit.
                entry.action?.invoke()
                onAction?.invoke()
                render()
            }
            else -> {
                dialog.dismiss()
                entry.action?.invoke()
                onAction?.invoke()
            }
        }
    }
}
