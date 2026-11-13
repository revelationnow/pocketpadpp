package io.github.revelationnow.pocketpadpp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.revelationnow.pocketpadpp.app.Doc
import io.github.revelationnow.pocketpadpp.app.FileIo
import io.github.revelationnow.pocketpadpp.app.FindBar
import io.github.revelationnow.pocketpadpp.app.LoadedFile
import io.github.revelationnow.pocketpadpp.app.MenuSheet
import io.github.revelationnow.pocketpadpp.app.Palette
import io.github.revelationnow.pocketpadpp.app.MenuSpec
import io.github.revelationnow.pocketpadpp.app.TabStrip
import io.github.revelationnow.pocketpadpp.app.Workspace
import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.LanguageRegistry
import io.github.revelationnow.pocketpadpp.core.Hashes
import io.github.revelationnow.pocketpadpp.core.Invisibles
import io.github.revelationnow.pocketpadpp.core.SessionStore
import io.github.revelationnow.pocketpadpp.core.Marks
import io.github.revelationnow.pocketpadpp.core.NppTheme
import io.github.revelationnow.pocketpadpp.core.TextEncoding
import io.github.revelationnow.pocketpadpp.core.TextEncodings
import io.github.revelationnow.pocketpadpp.core.TextTransforms
import io.github.revelationnow.pocketpadpp.core.ThemeApplier
import io.github.revelationnow.pocketpadpp.core.ViewMetrics
import io.github.revelationnow.pocketpadpp.editor.ScintillaView
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The editor screen: toolbar, document tabs, the Scintilla view, find bar, extra keys and status bar.
 * Menus follow Notepad++'s layout and wording so that they stay familiar.
 */
class MainActivity : Activity() {

    // Internal rather than private so that the on-device tests can inspect them.
    internal lateinit var editor: ScintillaView
    internal lateinit var workspace: Workspace
    private lateinit var tabs: TabStrip
    internal lateinit var findBar: FindBar
    private lateinit var statusText: TextView
    private lateinit var eolChip: TextView
    private lateinit var encodingChip: TextView
    private lateinit var languageChip: TextView
    private lateinit var imeLogView: TextView
    private lateinit var moreButton: View

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val imeLines = ArrayDeque<String>()

    private lateinit var palette: Palette
    private lateinit var themed: Context
    private val chromeBars = ArrayList<View>()
    private val clipHistory = ArrayDeque<String>()

    // The on-screen Ctrl, Alt and Shift keys: SCMOD_* bits held for the next key only, and those locked on.
    private var armedModifiers = 0
    private var lockedModifiers = 0
    private val modifierKeys = LinkedHashMap<Int, Button>()
    private lateinit var root: LinearLayout
    private var themeName = ""
    private var fontSize = 13
    private var showLineNumbers = true
    private var shownDirty = false
    private var destroyed = false

    // A Save As in progress: which tab, whether it is only a copy, and what to do afterwards.
    private var saveAsDoc: Doc? = null
    private var saveAsCopy = false
    private var afterSave: (() -> Unit)? = null
    private var menuSheet: MenuSheet? = null

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // region Set-up

    override fun onCreate(savedInstanceState: Bundle?) {
        // The bars and dialogs around the editor are light or dark to match the Notepad++ theme in use,
        // which has to be settled before the window is created.
        themeName = prefs.getString("theme", "") ?: ""
        val theme = loadTheme(themeName)
        palette = Palette(theme.isDark)
        setTheme(if (palette.dark) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        fontSize = prefs.getInt("fontSize", 13)
        showLineNumbers = prefs.getBoolean("lineNumbers", true)

        editor = ScintillaView(this)
        buildScreen(theme.background)

        // Notepad++'s own language and style definitions, shipped as assets.
        val languages = assets.open("npp/languages.tsv").use { table ->
            assets.open("npp/langs.model.xml").use { xml -> LanguageRegistry.load(table, xml) }
        }

        editor.send(Sci.SCI_SETTABWIDTH, 4)
        editor.send(Sci.SCI_SETWRAPMODE, (if (prefs.getBoolean("wrap", false)) Sci.SC_WRAP_WORD else Sci.SC_WRAP_NONE).toLong())
        editor.keyboardMode = if (prefs.getBoolean("textKeyboard", false)) ScintillaView.KeyboardMode.TEXT else ScintillaView.KeyboardMode.CODE

        workspace = Workspace(this, editor, languages, theme) {
            ViewMetrics(
                fontSize = fontSize, foldMarginWidth = dp(22f), leftPadding = dp(4f),
                showLineNumbers = showLineNumbers, pixelScale = resources.displayMetrics.density,
            )
        }
        workspace.onTabsChanged = { refreshTabs() }

        editor.onStateChanged = { onEditorStateChanged() }
        editor.onTextChanged = { workspace.textChanged() }
        editor.onCharAdded = { ch -> EditOps.autoIndent(editor, ch) }
        editor.onZoomChanged = { ThemeApplier.updateLineNumberWidth(editor, showLineNumbers) }
        editor.onShortcut = { key, modifiers -> shortcut(key, modifiers) }
        editor.onCopied = { text ->
            clipHistory.remove(text)
            clipHistory.addFirst(text)
            while (clipHistory.size > 20) clipHistory.removeLast()
        }
        Invisibles.apply(editor, prefs.getBoolean("showNpc", false), prefs.getBoolean("showControl", true))
        editor.onSoftModifiersUsed = { releaseModifiers() }
        editor.imeLog = { line -> onImeLog(line) }
        editor.requestFocus()
        refreshTabs()

        restoreSession { handleIntent(intent) }
    }

    /**
     * Builds everything around the editor: toolbar, tabs, find bar, extra keys and status bar.
     * Called again when the theme goes between light and dark; the editor view itself is kept, and with it
     * the open documents and their undo history.
     */
    private fun buildScreen(background: Int) {
        // Widgets and dialogs take their look from this context rather than from the window's fixed theme.
        themed = ContextThemeWrapper(this, if (palette.dark) R.style.AppTheme_Dark else R.style.AppTheme)
        (editor.parent as? ViewGroup)?.removeView(editor)

        root = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(background or 0xFF000000.toInt())
        }

        val toolbar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.chrome)
        }
        fun tool(label: String, description: String, action: (View) -> Unit): Button = barButton(label, description, action).also {
            toolbar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        tool("New", "New") { workspace.newDocument() }
        tool("Open", "Open") { openFiles() }
        tool("Save", "Save") { save() }
        tool("Undo", "Undo") { editor.send(Sci.SCI_UNDO) }
        tool("Redo", "Redo") { editor.send(Sci.SCI_REDO) }
        tool("Find", "Find") { findBar.open(withReplace = false) }
        moreButton = tool("⋮", "Menu") { showMainMenu() }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f)))
        chromeBars.clear()
        chromeBars += toolbar

        tabs = TabStrip(themed, palette)
        // The tabs, and at their end Notepad++'s button that lists every open document.
        val tabRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.tabBar)
            addView(tabs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(barButton("\u25BC", "Tab list") { showWindowsList() }.apply { setTextColor(palette.tabInactiveText) },
                LinearLayout.LayoutParams(dp(40f), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38f)))
        chromeBars += tabRow

        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        imeLogView = TextView(themed).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFF004400.toInt())
            setBackgroundColor(0xFFF4FFF4.toInt())
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            visibility = View.GONE
            setOnClickListener { copyImeLog() }
        }
        root.addView(imeLogView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        findBar = FindBar(themed, editor, palette)
        findBar.onClose = { editor.requestFocus() }
        root.addView(findBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(extraKeys(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f)))
        val status = statusBar()
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chromeBars += status
        for (bar in chromeBars) bar.visibility = if (distractionFree) View.GONE else View.VISIBLE
        setContentView(root)
        tabs.onSelect = { index -> workspace.docs.getOrNull(index)?.let { workspace.activate(it) } }
        tabs.onClose = { index -> workspace.docs.getOrNull(index)?.let { requestClose(it) } }
        tabs.onLongPress = { index, _ -> workspace.docs.getOrNull(index)?.let { showTabMenu(it) } }

        window.statusBarColor = palette.chrome
        window.navigationBarColor = palette.chrome
        applySystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT) {
            openUris(listOf(uri))
            intent.data = null
        }
    }

    override fun onStop() {
        super.onStop()
        // Like Notepad++'s session snapshot: tabs and unsaved text come back on the next start.
        workspace.saveSession()
    }

    override fun onDestroy() {
        destroyed = true
        io.shutdown()
        editor.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            findBar.isOpen -> findBar.close()
            fullScreen -> setScreenMode(full = false, bare = false)
            else -> super.onBackPressed()
        }
    }

    private fun barButton(label: String, description: String, onClick: (View) -> Unit): Button =
        Button(themed, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            contentDescription = description
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            // Toolbar taps must not take focus (and with it the keyboard) away from the editor.
            isFocusable = false
            setOnClickListener(onClick)
        }

    /** A row of keys phone keyboards make hard to reach. */
    private fun extraKeys(): View {
        val row = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(label: String, action: () -> Unit): Button {
            val button = Button(themed, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minWidth = 0
                minimumWidth = dp(40f)
                setPadding(dp(8f), 0, dp(8f), 0)
                isFocusable = false
                setOnClickListener { action() }
            }
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return button
        }
        // Sticky modifiers: one tap holds the key for the next key press, a second tap locks it, a third releases it.
        modifierKeys.clear()
        for ((label, bit) in listOf("Ctrl" to Sci.SCMOD_CTRL, "Alt" to Sci.SCMOD_ALT, "Shift" to Sci.SCMOD_SHIFT)) {
            modifierKeys[bit] = key(label) { cycleModifier(bit) }
        }
        showModifiers()
        fun press(label: String, code: Int) = key(label) {
            val modifiers = editor.softModifiers
            if (modifiers == 0) editor.pressKey(code) else editor.shortcutKey(code, modifiers)
            releaseModifiers()
        }
        press("Tab", Sci.SCK_TAB)
        press("←", Sci.SCK_LEFT)
        press("↑", Sci.SCK_UP)
        press("↓", Sci.SCK_DOWN)
        press("→", Sci.SCK_RIGHT)
        press("Home", Sci.SCK_HOME)
        press("End", Sci.SCK_END)
        for (symbol in listOf("{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "<", ">", "=", "/", "\\", "|", "&", "!", "_", "#")) {
            key(symbol) {
                editor.typeText(symbol)
                releaseModifiers()
            }
        }
        return HorizontalScrollView(themed).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(palette.keysBack)
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun cycleModifier(bit: Int) {
        when {
            lockedModifiers and bit != 0 -> lockedModifiers = lockedModifiers and bit.inv()
            armedModifiers and bit != 0 -> {
                armedModifiers = armedModifiers and bit.inv()
                lockedModifiers = lockedModifiers or bit
            }
            else -> armedModifiers = armedModifiers or bit
        }
        showModifiers()
    }

    /** One-shot modifiers let go once a key has used them; locked ones stay. */
    private fun releaseModifiers() {
        if (armedModifiers != 0) {
            armedModifiers = 0
            showModifiers()
        }
    }

    private fun showModifiers() {
        editor.softModifiers = armedModifiers or lockedModifiers
        for ((bit, button) in modifierKeys) {
            val locked = lockedModifiers and bit != 0
            val armed = armedModifiers and bit != 0
            button.setBackgroundColor(if (locked) palette.accent else Color.TRANSPARENT)
            button.setTextColor(if (locked) Color.WHITE else if (armed) palette.accent else palette.chromeText)
            button.setTypeface(Typeface.MONOSPACE, if (armed || locked) Typeface.BOLD else Typeface.NORMAL)
            button.contentDescription = button.text.toString() + (if (locked) ", locked" else if (armed) ", on for the next key" else "")
        }
    }

    /**
     * Notepad++'s default shortcuts, for the on-screen modifier keys and for hardware keyboards.
     * Keys are upper-case letters, digits, SCK_* codes or F-keys. Returning false passes the key on to
     * Scintilla's own key map, which has the editing shortcuts (Ctrl+Z, Y, X, C, V, A, D, L, T, U...).
     */
    private fun shortcut(key: Int, modifiers: Int): Boolean {
        val ctrl = Sci.SCMOD_CTRL
        val alt = Sci.SCMOD_ALT
        val shift = Sci.SCMOD_SHIFT
        val language = workspace.active.language
        val docs = workspace.docs
        val index = docs.indexOf(workspace.active)
        fun f(n: Int) = ScintillaView.KEY_FUNCTION + n
        when (modifiers) {
            0 -> when (key) {
                f(2) -> goToBookmark(forward = true)
                f(3) -> findBar.find(forward = true)
                else -> return false
            }
            shift -> when (key) {
                f(2) -> goToBookmark(forward = false)
                f(3) -> findBar.find(forward = false)
                else -> return false
            }
            ctrl -> when (key) {
                'N'.code -> workspace.newDocument()
                'O'.code -> openFiles()
                'S'.code -> save()
                'W'.code -> requestClose(workspace.active)
                'F'.code -> findBar.open(withReplace = false)
                'H'.code -> findBar.open(withReplace = true)
                'G'.code -> goToLine()
                'B'.code -> EditOps.goToMatchingBrace(editor)
                'Q'.code -> EditOps.toggleLineComment(editor, language.commentLine)
                'K'.code -> EditOps.setLineComment(editor, language.commentLine, comment = true)
                'J'.code -> EditOps.joinLines(editor)
                'I'.code -> EditOps.splitLines(editor)
                Sci.SCK_TAB, Sci.SCK_NEXT -> workspace.activate(docs[(index + 1) % docs.size])
                Sci.SCK_PRIOR -> workspace.activate(docs[(index + docs.size - 1) % docs.size])
                Sci.SCK_ADD -> zoom { editor.send(Sci.SCI_ZOOMIN) }
                Sci.SCK_SUBTRACT -> zoom { editor.send(Sci.SCI_ZOOMOUT) }
                Sci.SCK_DIVIDE -> zoom { editor.send(Sci.SCI_SETZOOM, 0) }
                f(2) -> toggleBookmark()
                f(3) -> findBar.findSelection(forward = true, remember = true)
                else -> return false
            }
            ctrl or shift -> when (key) {
                'S'.code -> saveAll()
                'W'.code -> closeMany(docs.toList())
                'Q'.code -> EditOps.streamComment(editor, language.commentStart, language.commentEnd)
                'K'.code -> EditOps.setLineComment(editor, language.commentLine, comment = false)
                Sci.SCK_TAB -> workspace.activate(docs[(index + docs.size - 1) % docs.size])
                Sci.SCK_UP -> editor.send(Sci.SCI_MOVESELECTEDLINESUP)
                Sci.SCK_DOWN -> editor.send(Sci.SCI_MOVESELECTEDLINESDOWN)
                f(3) -> findBar.findSelection(forward = false, remember = true)
                else -> return false
            }
            ctrl or alt -> when (key) {
                'S'.code -> saveAs(copy = false)
                'F'.code -> EditOps.foldCurrent(editor, collapse = true)
                else -> return false
            }
            ctrl or alt or shift -> when (key) {
                'F'.code -> EditOps.foldCurrent(editor, collapse = false)
                'B'.code -> EditOps.selectBetweenBraces(editor)
                else -> return false
            }
            // Alt and a letter opens that menu, as on Windows.
            alt -> when (key) {
                'F'.code -> showMainMenu("File")
                'E'.code -> showMainMenu("Edit")
                'S'.code -> showMainMenu("Search")
                'V'.code -> showMainMenu("View")
                'N'.code -> showMainMenu("Encoding")
                'L'.code -> showMainMenu("Language")
                'T'.code -> showMainMenu("Settings")
                'O'.code -> showMainMenu("Tools")
                'W'.code -> showMainMenu("Window")
                'C'.code -> columnEditor()
                '0'.code -> editor.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_CONTRACT.toLong())
                in '1'.code..'8'.code -> EditOps.foldLevel(editor, key - '0'.code, collapse = true)
                else -> return false
            }
            alt or shift -> when (key) {
                '0'.code -> editor.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_EXPAND.toLong())
                in '1'.code..'8'.code -> EditOps.foldLevel(editor, key - '0'.code, collapse = false)
                else -> return false
            }
            else -> return false
        }
        updateStatus()
        return true
    }

    /** Notepad++'s status bar: position on the left; line ending, encoding and language on the right, each tappable. */
    private fun statusBar(): View {
        val bar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.chrome)
        }
        statusText = TextView(themed).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(palette.chromeDimText)
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            maxLines = 1
            // Notepad++ opens Go to from a double click on this part of the status bar.
            setOnClickListener { goToLine() }
        }
        bar.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun chip(action: (View) -> Unit) = TextView(themed).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(palette.chromeText)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            maxLines = 1
            setOnClickListener(action)
        }.also { bar.addView(it) }
        languageChip = chip { showMenu("Language") { languageMenu() } }
        eolChip = chip { showMenu("EOL Conversion") { eolItems() } }
        encodingChip = chip { showMenu("Encoding") { encodingMenu() } }
        return bar
    }

    // endregion

    // region Keeping the chrome in step with the editor

    private fun refreshTabs() {
        shownDirty = workspace.isDirty(workspace.active)
        tabs.setTabs(workspace.docs.map { TabStrip.Tab(it.name, workspace.isDirty(it), it === workspace.active, it.colour) })
        updateStatus()
    }

    private fun onEditorStateChanged() {
        if (workspace.isDirty(workspace.active) != shownDirty) refreshTabs() else updateStatus()
        workspace.lineCountMayHaveChanged()
        EditOps.highlightBraces(editor)
    }

    private fun updateStatus() {
        val position = editor.send(Sci.SCI_GETCURRENTPOS)
        val line = editor.send(Sci.SCI_LINEFROMPOSITION, position) + 1
        val column = editor.send(Sci.SCI_GETCOLUMN, position) + 1
        val start = editor.send(Sci.SCI_GETSELECTIONSTART)
        val end = editor.send(Sci.SCI_GETSELECTIONEND)
        val carets = editor.send(Sci.SCI_GETSELECTIONS)
        val parts = mutableListOf("Ln $line, Col $column")
        // With several selections (a column, or multi-select) the count is what is selected in each, added up.
        val selected = if (carets > 1) {
            (0 until carets).sumOf { n ->
                editor.send(Sci.SCI_COUNTCHARACTERS, editor.send(Sci.SCI_GETSELECTIONNSTART, n), editor.send(Sci.SCI_GETSELECTIONNEND, n))
            }
        } else {
            editor.send(Sci.SCI_COUNTCHARACTERS, start, end)
        }
        if (selected > 0) parts += "Sel $selected"
        if (carets > 1) parts += "$carets carets"
        parts += "${editor.send(Sci.SCI_GETLINECOUNT)} lines"
        if (editor.send(Sci.SCI_GETREADONLY) != 0L) parts += "Read-only"
        statusText.text = parts.joinToString("  ·  ")
        languageChip.text = workspace.active.language.display
        eolChip.text = Eol.fromSciMode(editor.send(Sci.SCI_GETEOLMODE).toInt()).label
        encodingChip.text = workspace.active.encoding.label
    }

    private fun onImeLog(line: String) {
        // Milliseconds within the last minute and a half are enough to see what belongs together.
        imeLines.addLast("%05d %s".format(android.os.SystemClock.uptimeMillis() % 100000, line))
        while (imeLines.size > IME_LOG_LINES) imeLines.removeFirst()
        if (imeLogView.visibility == View.VISIBLE) showImeLog()
    }

    /** The log shows its newest lines; tapping it copies all that were kept. */
    private fun showImeLog() {
        imeLogView.text = (listOf("Keyboard log (tap to copy ${imeLines.size} lines)") + imeLines.toList().takeLast(6)).joinToString("\n")
    }

    private fun copyImeLog() {
        editor.setClipboard(imeLines.joinToString("\n"))
        toast("Keyboard log copied: ${imeLines.size} lines")
    }

    // endregion

    // region Files

    private fun openFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OPEN)
        } catch (e: Exception) {
            toast("No file picker is available on this device")
        }
    }

    internal fun openUris(uris: List<Uri>) {
        for (uri in uris) {
            val existing = workspace.findByUri(uri)
            if (existing != null) {
                workspace.activate(existing)
                continue
            }
            FileIo.persistAccess(this, uri)
            io.execute {
                val result = runCatching { FileIo.read(this, uri) }
                ui.post {
                    if (destroyed) return@post
                    result.onSuccess { workspace.open(uri, it) }
                        .onFailure { toast("Cannot open the file: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        }
    }

    private fun reloadFromDisk(encoding: TextEncoding? = null) {
        val doc = workspace.active
        val uri = doc.uri ?: return toast("This document has not been saved yet")
        val reload = {
            io.execute {
                val result = runCatching { FileIo.read(this, uri, encoding) }
                ui.post {
                    if (destroyed) return@post
                    result.onSuccess { if (doc === workspace.active) workspace.reloaded(it) }
                        .onFailure { toast("Cannot reload the file: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        }
        if (workspace.isDirty(doc)) {
            AlertDialog.Builder(themed)
                .setMessage("Are you sure you want to reload the current file and lose the changes made in Pocketpad++?")
                .setPositiveButton(android.R.string.ok) { _, _ -> reload() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            reload()
        }
    }

    /** Saves a tab to its file, or asks where to put it if it has none yet. */
    private fun save(doc: Doc = workspace.active, then: (() -> Unit)? = null) {
        workspace.activate(doc)
        val uri = doc.uri
        if (uri == null) saveAs(doc, copy = false, then = then) else write(doc, uri, doc.name, markSaved = true, then = then)
    }

    private fun saveAs(doc: Doc = workspace.active, copy: Boolean, then: (() -> Unit)? = null) {
        workspace.activate(doc)
        saveAsDoc = doc
        saveAsCopy = copy
        afterSave = then
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, if (doc.uri == null && '.' !in doc.name) "${doc.name}.txt" else doc.name)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_SAVE_AS)
        } catch (e: Exception) {
            toast("No file picker is available on this device")
        }
    }

    private fun write(doc: Doc, uri: Uri, name: String, markSaved: Boolean, then: (() -> Unit)?) {
        // The text is copied on the UI thread; encoding and writing happen in the background.
        val utf8 = workspace.bytesForSaving()
        val encoding = doc.encoding
        io.execute {
            val result = runCatching {
                val complete = TextEncodings.canEncode(utf8, encoding)
                FileIo.write(this, uri, TextEncodings.fromUtf8(utf8, encoding))
                complete
            }
            ui.post {
                if (destroyed) return@post
                result.onSuccess { complete ->
                    if (markSaved) workspace.markSaved(doc, uri, name)
                    toast(if (complete) "Saved $name" else "Saved, but ${encoding.label} cannot hold every character; those were written as ?")
                    then?.invoke()
                }.onFailure {
                    AlertDialog.Builder(themed)
                        .setTitle("Save failed")
                        .setMessage("${it.message ?: it.javaClass.simpleName}\n\nThe file may be read-only for this app. Use Save As to write it somewhere else.")
                        .setPositiveButton("Save As...") { _, _ -> saveAs(doc, copy = false, then = then) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun saveAll(remaining: List<Doc> = workspace.docs.filter { workspace.isDirty(it) }) {
        val doc = remaining.firstOrNull() ?: return
        save(doc) { saveAll(remaining.drop(1)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQUEST_SAVE_AS) {
                saveAsDoc = null
                afterSave = null
            }
            return
        }
        when (requestCode) {
            REQUEST_LOAD_SESSION -> data.data?.let { uri ->
                io.execute {
                    val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                    ui.post { if (!destroyed) if (text == null) toast("Cannot read the session file") else loadSessionText(text) }
                }
            }

            REQUEST_SAVE_SESSION -> data.data?.let { uri ->
                val xml = workspace.exportSession()
                io.execute {
                    val ok = runCatching { FileIo.write(this, uri, xml.toByteArray(Charsets.UTF_8)) }.isSuccess
                    ui.post { if (!destroyed) toast(if (ok) "Session saved" else "Cannot write the session file") }
                }
            }

            REQUEST_HASH -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
                if (uris.isEmpty()) data.data?.let { uris += it }
                hashRequest?.let { (name, algorithm) -> if (uris.isNotEmpty()) showFileHashes(name, algorithm, uris) }
                hashRequest = null
            }

            REQUEST_OPEN -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
                if (uris.isEmpty()) data.data?.let { uris += it }
                openUris(uris)
            }

            REQUEST_SAVE_AS -> {
                val doc = saveAsDoc
                val uri = data.data
                val then = afterSave
                saveAsDoc = null
                afterSave = null
                if (doc != null && uri != null && doc in workspace.docs) {
                    FileIo.persistAccess(this, uri)
                    workspace.activate(doc)
                    write(doc, uri, FileIo.displayName(this, uri), markSaved = !saveAsCopy, then = then)
                }
            }
        }
    }

    /** Closes a tab, first asking about unsaved changes as Notepad++ does. */
    private fun requestClose(doc: Doc, then: (() -> Unit)? = null) {
        if (!workspace.isDirty(doc)) {
            rememberRecent(doc)
            workspace.close(doc)
            then?.invoke()
            return
        }
        workspace.activate(doc)
        AlertDialog.Builder(themed)
            .setTitle("Save")
            .setMessage("Save file \"${doc.name}\"?")
            .setPositiveButton("Yes") { _, _ -> save(doc) { rememberRecent(doc); workspace.close(doc); then?.invoke() } }
            .setNegativeButton("No") { _, _ -> rememberRecent(doc); workspace.close(doc); then?.invoke() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun closeMany(docs: List<Doc>) {
        val doc = docs.firstOrNull { it in workspace.docs } ?: return
        requestClose(doc) { closeMany(docs.drop(docs.indexOf(doc) + 1)) }
    }

    private fun restoreSession(then: () -> Unit) {
        val session = workspace.readSession()
        if (session.entries.isEmpty()) return then()
        io.execute {
            val loaded = session.entries.map { entry ->
                val encoding = TextEncodings.byId(entry.encoding) ?: TextEncodings.UTF8
                val backup = workspace.readBackup(entry)
                when {
                    backup != null ->
                        Triple(entry, LoadedFile(entry.name, backup, encoding, Eol.detect(backup) ?: Eol.fromSciMode(entry.eolMode)), true)
                    entry.location != null -> runCatching { FileIo.read(this, Uri.parse(entry.location), encoding) }.getOrNull()
                        ?.let { Triple(entry, LoadedFile(entry.name.ifEmpty { it.name }, it.utf8, it.encoding, it.eol), false) }
                    else -> null  // An untitled tab with nothing in it.
                }
            }
            ui.post {
                if (destroyed) return@post
                var activeDoc: Doc? = null
                loaded.forEachIndexed { index, item ->
                    if (item != null) {
                        val doc = workspace.restore(item.first, item.second, item.third)
                        if (index == session.activeIndex) activeDoc = doc
                    }
                }
                activeDoc?.let { workspace.activate(it) }
                workspace.pruneBackups()
                val missing = loaded.count { it == null } - session.entries.count { it.location == null && it.backup == null }
                if (missing > 0) toast("$missing file(s) from the last session could not be reopened")
                then()
            }
        }
    }

    // endregion

    // region Menus

    private fun showMenu(title: String, build: MenuSpec.() -> Unit) {
        menuSheet?.dismiss()
        menuSheet = MenuSheet(themed, palette.dark).also { sheet ->
            sheet.onAction = { updateStatus() }
            sheet.show(title, build)
        }
    }

    private fun showMainMenu(open: String? = null) {
        showMenu("Menu") {
            sub("File") { fileMenu() }
            sub("Edit") { editMenu() }
            sub("Search") { searchMenu() }
            sub("View") { viewMenu() }
            sub("Encoding") { encodingMenu() }
            sub("Language") { languageMenu() }
            sub("Settings") { settingsMenu() }
            sub("Tools") { toolsMenu() }
            sub("Window") { windowMenu() }
            sub("?") { helpMenu() }
        }
        if (open != null) menuSheet?.open(open)
    }

    private fun MenuSpec.fileMenu() {
        val doc = workspace.active
        item("New") { workspace.newDocument() }
        item("Open...") { openFiles() }
        item("Reload from Disk", enabled = doc.uri != null) { reloadFromDisk() }
        item("Save") { save() }
        item("Save As...") { saveAs(copy = false) }
        item("Save a Copy As...") { saveAs(copy = true) }
        item("Save All") { saveAll() }
        item("Close") { requestClose(workspace.active) }
        item("Close All") { closeMany(workspace.docs.toList()) }
        item("Open in Default Viewer", enabled = doc.uri != null) { viewInAnotherApp(doc, null) }
        item("Rename...") { renameFile() }
        item("Delete from Storage", enabled = doc.uri != null) { deleteFile() }
        item("Load Session...") { pickFile(REQUEST_LOAD_SESSION) }
        item("Save Session...") { createFile(REQUEST_SAVE_SESSION, "session.xml") }
        sub("Close Multiple Documents") {
            val index = workspace.docs.indexOf(doc)
            item("Close All but Active Document") { closeMany(workspace.docs.filter { it !== doc }) }
            item("Close All to the Left") { closeMany(workspace.docs.take(index)) }
            item("Close All to the Right") { closeMany(workspace.docs.drop(index + 1)) }
            item("Close All Unchanged") { closeMany(workspace.docs.filter { !workspace.isDirty(it) }) }
        }
        sub("Recent Files") {
            val recent = recentFiles()
            for ((uri, name) in recent) {
                item(name) { openUris(listOf(uri)) }
            }
            item("Restore Recent Closed File", enabled = recent.isNotEmpty()) { openUris(listOf(recent.first().first)) }
            item("Open All Recent Files", enabled = recent.isNotEmpty()) { openUris(recent.map { it.first }) }
            item("Empty Recent Files List", enabled = recent.isNotEmpty()) { prefs.edit().remove("recent").apply() }
        }
        item("Exit") {
            // Like Notepad++ with its session snapshot: nothing is asked, everything comes back next time.
            workspace.saveSession()
            finishAndRemoveTask()
        }
    }

    private fun MenuSpec.editMenu() {
        val readOnly = editor.send(Sci.SCI_GETREADONLY) != 0L
        val language = workspace.active.language
        item("Undo", enabled = editor.send(Sci.SCI_CANUNDO) != 0L) { editor.send(Sci.SCI_UNDO) }
        item("Redo", enabled = editor.send(Sci.SCI_CANREDO) != 0L) { editor.send(Sci.SCI_REDO) }
        item("Cut") { editor.send(Sci.SCI_CUT) }
        item("Copy") { editor.send(Sci.SCI_COPY) }
        item("Paste") { editor.send(Sci.SCI_PASTE) }
        item("Delete") { editor.send(Sci.SCI_CLEAR) }
        item("Select All") { editor.send(Sci.SCI_SELECTALL) }
        item(if (beginSelect == null) "Begin/End Select" else "Begin/End Select (end here)") { beginEndSelect(column = false) }
        item(if (beginSelect == null) "Begin/End Select in Column Mode" else "Begin/End Select in Column Mode (end here)") {
            beginEndSelect(column = true)
        }
        sub("Insert") {
            item("Date Time (short)") { insertDateTime(java.text.DateFormat.SHORT) }
            item("Date Time (long)") { insertDateTime(java.text.DateFormat.LONG) }
            item("Date Time (customized)") {
                val format = prefs.getString("dateTimeFormat", null) ?: "yyyy-MM-dd HH:mm:ss"
                editor.typeText(java.text.SimpleDateFormat(format, java.util.Locale.getDefault()).format(java.util.Date()))
            }
        }
        sub("Copy to Clipboard") {
            val uri = workspace.active.uri
            item("Copy Current Full File path", enabled = uri != null) { editor.setClipboard(uri.toString()) }
            item("Copy Current Filename") { editor.setClipboard(workspace.active.name) }
            item("Copy Current Dir. Path", enabled = uri != null) { editor.setClipboard(uri.toString().substringBeforeLast('/')) }
            item("Copy All Filenames") { editor.setClipboard(workspace.docs.joinToString("\n") { it.name }) }
            item("Copy All File Paths") { editor.setClipboard(workspace.docs.mapNotNull { it.uri?.toString() }.joinToString("\n")) }
        }
        sub("Indent") {
            item("Increase Line Indent") { editor.send(Sci.SCI_TAB) }
            item("Decrease Line Indent") { editor.send(Sci.SCI_BACKTAB) }
        }
        sub("Convert Case to") {
            fun convert(label: String, transform: (String) -> String) = item(label) {
                if (!EditOps.transformSelection(editor, transform)) toast("Select some text first")
            }
            item("UPPERCASE") { editor.send(Sci.SCI_UPPERCASE) }
            item("lowercase") { editor.send(Sci.SCI_LOWERCASE) }
            convert("Proper Case") { TextTransforms.properCase(it, blend = false) }
            convert("Proper Case (blend)") { TextTransforms.properCase(it, blend = true) }
            convert("Sentence case") { TextTransforms.sentenceCase(it, blend = false) }
            convert("Sentence case (blend)") { TextTransforms.sentenceCase(it, blend = true) }
            convert("iNVERT cASE") { TextTransforms.invertCase(it) }
            convert("ranDOm CasE") { TextTransforms.randomCase(it) }
        }
        sub("Line Operations") {
            item("Duplicate Current Line") { editor.send(Sci.SCI_SELECTIONDUPLICATE) }
            item("Remove Duplicate Lines") { EditOps.removeDuplicateLines(editor) }
            item("Remove Consecutive Duplicate Lines") { EditOps.removeConsecutiveDuplicateLines(editor) }
            item("Split Lines") { EditOps.splitLines(editor) }
            item("Join Lines") { EditOps.joinLines(editor) }
            item("Move Up Current Line") { editor.send(Sci.SCI_MOVESELECTEDLINESUP) }
            item("Move Down Current Line") { editor.send(Sci.SCI_MOVESELECTEDLINESDOWN) }
            item("Remove Empty Lines") { EditOps.removeEmptyLines(editor, includeBlank = false) }
            item("Remove Empty Lines (Containing Blank characters)") { EditOps.removeEmptyLines(editor, includeBlank = true) }
            item("Insert Blank Line Above Current") { EditOps.insertBlankLine(editor, above = true) }
            item("Insert Blank Line Below Current") { EditOps.insertBlankLine(editor, above = false) }
            item("Reverse Line Order") { EditOps.reverseLines(editor) }
            item("Randomize Line Order") { EditOps.shuffleLines(editor) }
            val sorts = listOf(
                "Lexicographically" to TextTransforms.Sort.LEXICOGRAPHIC,
                "Lex. %s Ignoring Case" to TextTransforms.Sort.IGNORE_CASE,
                "In Locale Order" to TextTransforms.Sort.LOCALE,
                "As Integers" to TextTransforms.Sort.INTEGER,
                "As Decimals (Comma)" to TextTransforms.Sort.DECIMAL_COMMA,
                "As Decimals (Dot)" to TextTransforms.Sort.DECIMAL_DOT,
                "By Length" to TextTransforms.Sort.LENGTH,
            )
            for (descending in listOf(false, true)) {
                val direction = if (descending) "Descending" else "Ascending"
                for ((label, kind) in sorts) {
                    val name = if ("%s" in label) "Sort Lines ${label.format(direction)}" else "Sort Lines $label $direction"
                    item(name) { EditOps.sortLines(editor, descending, kind) }
                }
            }
        }
        sub("Comment/Uncomment") {
            fun needsMarker(done: Boolean, what: String) {
                if (!done) toast("${language.display} has no $what")
            }
            item("Toggle Single Line Comment") { needsMarker(EditOps.toggleLineComment(editor, language.commentLine), "single line comment") }
            item("Single Line Comment") { needsMarker(EditOps.setLineComment(editor, language.commentLine, comment = true), "single line comment") }
            item("Single Line Uncomment") { needsMarker(EditOps.setLineComment(editor, language.commentLine, comment = false), "single line comment") }
            item("Block Comment") {
                if (language.commentStart.isEmpty()) toast("${language.display} has no block comment")
                else if (!EditOps.streamComment(editor, language.commentStart, language.commentEnd)) toast("Select some text first")
            }
            item("Block Uncomment") {
                if (!EditOps.streamUncomment(editor, language.commentStart, language.commentEnd)) toast("Select a whole block comment first")
            }
        }
        sub("EOL Conversion") { eolItems() }
        sub("Blank Operations") {
            item("Trim Trailing Space") { EditOps.trimTrailingSpace(editor) }
            item("Trim Leading Space") { EditOps.mapLines(editor) { TextTransforms.trimLeading(it) } }
            item("Trim Leading and Trailing Space") { EditOps.mapLines(editor) { TextTransforms.trimBoth(it) } }
            item("EOL to Space") { EditOps.eolToSpace(editor, trim = false) }
            item("Trim both and EOL to Space") { EditOps.eolToSpace(editor, trim = true) }
            item("TAB to Space") { EditOps.tabsToSpaces(editor) }
            item("Space to TAB (All)") { EditOps.spacesToTabs(editor, leadingOnly = false) }
            item("Space to TAB (Leading)") { EditOps.spacesToTabs(editor, leadingOnly = true) }
        }
        sub("Paste Special") {
            item("Paste HTML Content") {
                val html = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.htmlText
                if (html.isNullOrEmpty()) toast("The clipboard holds no HTML") else editor.typeText(html)
            }
        }
        sub("On Selection") {
            item("Open File") { openSelectedFile() }
            item("Search on Internet") { searchOnInternet() }
            item("Redact Selection") { if (!EditOps.redactSelection(editor)) toast("Select some text first") }
            sub("Change Search Engine...") {
                val current = prefs.getString("searchEngine", "") ?: ""
                for ((name, template) in SEARCH_ENGINES) {
                    item(name, checked = template == current) { prefs.edit().putString("searchEngine", template).apply() }
                }
            }
        }
        item("Character Panel") { characterPanel() }
        item("Clipboard History") { clipboardHistory() }
        item("Column Mode...") { columnModeTip() }
        item("Column Editor...") { columnEditor() }
        sub("Multi-select All") {
            item("Ignore Case & Whole Word") { EditOps.multiSelectAll(editor, matchCase = false, wholeWord = false) }
            item("Match Case Only") { EditOps.multiSelectAll(editor, matchCase = true, wholeWord = false) }
            item("Match Whole Word Only") { EditOps.multiSelectAll(editor, matchCase = false, wholeWord = true) }
            item("Match Case & Whole Word") { EditOps.multiSelectAll(editor, matchCase = true, wholeWord = true) }
        }
        sub("Multi-select Next") {
            item("Ignore Case & Whole Word") { multiSelectNext(matchCase = false, wholeWord = false) }
            item("Match Case Only") { multiSelectNext(matchCase = true, wholeWord = false) }
            item("Match Whole Word Only") { multiSelectNext(matchCase = false, wholeWord = true) }
            item("Match Case & Whole Word") { multiSelectNext(matchCase = true, wholeWord = true) }
        }
        item("Undo the Latest Added Multi-Select") { EditOps.multiSelectUndo(editor) }
        item("Skip Current & Go to Next Multi-select") { EditOps.multiSelectSkip(editor, lastMultiSelect.first, lastMultiSelect.second) }
        item("Read-Only", checked = readOnly) { editor.send(Sci.SCI_SETREADONLY, if (readOnly) 0 else 1) }
        item("Set Read-Only for All Documents") { workspace.setReadOnlyAll(true) }
        item("Clear Read-Only for All Documents") { workspace.setReadOnlyAll(false) }
    }

    private fun MenuSpec.eolItems() {
        val current = Eol.fromSciMode(editor.send(Sci.SCI_GETEOLMODE).toInt())
        for (eol in listOf(Eol.CRLF, Eol.LF, Eol.CR)) {
            item(eol.longLabel, checked = eol == current) {
                editor.send(Sci.SCI_CONVERTEOLS, eol.sciMode.toLong())
                editor.send(Sci.SCI_SETEOLMODE, eol.sciMode.toLong())
            }
        }
    }

    private fun MenuSpec.searchMenu() {
        item("Find...") { findBar.open(withReplace = false) }
        item("Find Next") { findBar.find(forward = true) }
        item("Find Previous") { findBar.find(forward = false) }
        item("Select and Find Next") { findBar.findSelection(forward = true, remember = true) }
        item("Select and Find Previous") { findBar.findSelection(forward = false, remember = true) }
        item("Find (Volatile) Next") { findBar.findSelection(forward = true, remember = false) }
        item("Find (Volatile) Previous") { findBar.findSelection(forward = false, remember = false) }
        item("Replace...") { findBar.open(withReplace = true) }
        item("Incremental Search") { findBar.openIncremental() }
        item("Mark...") { findBar.openMark() }
        item("Go to...") { goToLine() }
        item("Go to Matching Brace") { EditOps.goToMatchingBrace(editor) }
        item("Find characters in range...") { findCharsInRange() }
        sub("Changed Lines") {
            item("Go to Next Change") { if (!EditOps.goToChange(editor, forward = true)) toast("No changed lines") }
            item("Go to Previous Change") { if (!EditOps.goToChange(editor, forward = false)) toast("No changed lines") }
            item("Clear Change History") { EditOps.clearChangeHistory(editor) }
        }
        item("Select All In-between {} [] or ()") {
            if (!EditOps.selectBetweenBraces(editor)) toast("Put the caret next to a brace first")
        }
        sub("Bookmark") {
            item("Toggle Bookmark") { toggleBookmark() }
            item("Next Bookmark") { goToBookmark(forward = true) }
            item("Previous Bookmark") { goToBookmark(forward = false) }
            item("Clear All Bookmarks") { editor.send(Sci.SCI_MARKERDELETEALL, ThemeApplier.BOOKMARK_MARKER.toLong()) }
            item("Cut Bookmarked Lines") {
                editor.setClipboard(Marks.bookmarkedText(editor))
                Marks.removeLines(editor, bookmarkedOnes = true)
            }
            item("Copy Bookmarked Lines") { editor.setClipboard(Marks.bookmarkedText(editor)) }
            item("Paste to (Replace) Bookmarked Lines") {
                Marks.replaceBookmarked(editor, editor.getClipboard().removeSuffix("\n").removeSuffix("\r"))
            }
            item("Remove Bookmarked Lines") { Marks.removeLines(editor, bookmarkedOnes = true) }
            item("Remove Non-Bookmarked Lines") { Marks.removeLines(editor, bookmarkedOnes = false) }
            item("Inverse Bookmark") { Marks.invertBookmarks(editor) }
        }
        // Notepad++'s five highlight styles, plus the one Search > Mark uses.
        val ordinals = listOf("1st", "2nd", "3rd", "4th", "5th")
        sub("Mark All") {
            for (style in Marks.STYLES) {
                item("Using ${ordinals[style - 1]} Style") {
                    if (Marks.markSelection(editor, style, findBar.matchCaseOn, findBar.wholeWordOn) == 0) {
                        toast("Select some text or put the caret in a word first")
                    }
                }
            }
        }
        sub("Mark One") {
            for (style in Marks.STYLES) {
                item("Using ${ordinals[style - 1]} Style") {
                    if (!Marks.markRange(editor, style)) toast("Select some text first")
                }
            }
        }
        sub("Unmark All") {
            for (style in Marks.STYLES) item("Clear ${ordinals[style - 1]} Style") { Marks.clear(editor, style) }
            item("Clear all Styles") { Marks.clearAllStyles(editor) }
        }
        for (forward in listOf(false, true)) {
            sub(if (forward) "Jump Down" else "Jump Up") {
                for (style in Marks.STYLES) {
                    item("${ordinals[style - 1]} Style") { if (!Marks.jump(editor, style, forward)) toast("Nothing is marked in this style") }
                }
                item("Find Mark Style") { if (!Marks.jump(editor, Marks.FOUND, forward)) toast("Nothing is marked in this style") }
            }
        }
        sub("Copy Styled Text") {
            for (style in Marks.STYLES) item("${ordinals[style - 1]} Style") { editor.setClipboard(Marks.text(editor, listOf(style))) }
            item("All Styles") { editor.setClipboard(Marks.text(editor, Marks.STYLES.toList())) }
            item("Find Mark Style") { editor.setClipboard(Marks.text(editor, listOf(Marks.FOUND))) }
        }
    }

    private fun MenuSpec.viewMenu() {
        val wrapped = editor.send(Sci.SCI_GETWRAPMODE).toInt() != Sci.SC_WRAP_NONE
        item("Word wrap", checked = wrapped) {
            editor.send(Sci.SCI_SETWRAPMODE, (if (wrapped) Sci.SC_WRAP_NONE else Sci.SC_WRAP_WORD).toLong())
            prefs.edit().putBoolean("wrap", !wrapped).apply()
        }
        sub("Show Symbol") {
            val whitespace = editor.send(Sci.SCI_GETVIEWWS).toInt() != Sci.SCWS_INVISIBLE
            val eol = editor.send(Sci.SCI_GETVIEWEOL) != 0L
            val guides = editor.send(Sci.SCI_GETINDENTATIONGUIDES).toInt() != Sci.SC_IV_NONE
            val wrapSymbol = editor.send(Sci.SCI_GETWRAPVISUALFLAGS).toInt() != Sci.SC_WRAPVISUALFLAG_NONE
            fun showWhitespace(on: Boolean) = editor.send(Sci.SCI_SETVIEWWS, (if (on) Sci.SCWS_VISIBLEALWAYS else Sci.SCWS_INVISIBLE).toLong())
            item("Show Space and Tab", checked = whitespace) { showWhitespace(!whitespace) }
            item("Show End of Line", checked = eol) { editor.send(Sci.SCI_SETVIEWEOL, if (eol) 0 else 1) }
            item("Show All Characters", checked = whitespace && eol) {
                val on = !(whitespace && eol)
                showWhitespace(on)
                editor.send(Sci.SCI_SETVIEWEOL, if (on) 1 else 0)
            }
            val npc = prefs.getBoolean("showNpc", false)
            val control = prefs.getBoolean("showControl", true)
            item("Show Non-Printing Characters", checked = npc) {
                prefs.edit().putBoolean("showNpc", !npc).apply()
                Invisibles.apply(editor, !npc, control)
            }
            item("Show Control Characters & Unicode EOL", checked = control) {
                prefs.edit().putBoolean("showControl", !control).apply()
                Invisibles.apply(editor, npc, !control)
            }
            item("Show Indent Guide", checked = guides) { workspace.showIndentGuides = !guides }
            item("Show Wrap Symbol", checked = wrapSymbol) {
                editor.send(Sci.SCI_SETWRAPVISUALFLAGS, (if (wrapSymbol) Sci.SC_WRAPVISUALFLAG_NONE else Sci.SC_WRAPVISUALFLAG_END).toLong())
            }
        }
        sub("Zoom") {
            item("Zoom In") { zoom { editor.send(Sci.SCI_ZOOMIN) } }
            item("Zoom Out") { zoom { editor.send(Sci.SCI_ZOOMOUT) } }
            item("Restore Default Zoom") { zoom { editor.send(Sci.SCI_SETZOOM, 0) } }
        }
        item("Toggle Full Screen Mode", checked = fullScreen && !distractionFree) { setScreenMode(full = !fullScreen || distractionFree, bare = false) }
        item("Distraction Free Mode", checked = distractionFree) { setScreenMode(full = !distractionFree, bare = !distractionFree) }
        item("View Current File in Browser", enabled = workspace.active.uri != null) { viewInAnotherApp(workspace.active, "text/html") }
        item("Hide Lines") { if (!EditOps.hideLines(editor)) toast("The first and last line cannot be hidden") }
        item("Show Hidden Lines") { editor.send(Sci.SCI_SHOWLINES, 0, editor.send(Sci.SCI_GETLINECOUNT) - 1) }
        sub("Tab") {
            val docs = workspace.docs
            val index = docs.indexOf(workspace.active)
            val places = listOf("1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th", "9th")
            for ((n, place) in places.withIndex()) {
                item("$place Tab", enabled = n < docs.size) { workspace.activate(docs[n]) }
            }
            for (colour in 1..5) item("Apply Colour $colour", checked = workspace.active.colour == colour) { setTabColour(workspace.active, colour) }
            item("Remove Colour") { setTabColour(workspace.active, 0) }
            item("First Tab") { workspace.activate(docs.first()) }
            item("Last Tab") { workspace.activate(docs.last()) }
            item("Next Tab") { workspace.activate(docs[(index + 1) % docs.size]) }
            item("Previous Tab") { workspace.activate(docs[(index + docs.size - 1) % docs.size]) }
            item("Move to Start") { workspace.move(workspace.active, 0) }
            item("Move to End") { workspace.move(workspace.active, docs.size - 1) }
            item("Move Tab Forward", enabled = index < docs.size - 1) { workspace.move(workspace.active, index + 1) }
            item("Move Tab Backward", enabled = index > 0) { workspace.move(workspace.active, index - 1) }
        }
        item("Fold All") { editor.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_CONTRACT.toLong()) }
        item("Unfold All") { editor.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_EXPAND.toLong()) }
        item("Fold Current Level") { EditOps.foldCurrent(editor, collapse = true) }
        item("Unfold Current Level") { EditOps.foldCurrent(editor, collapse = false) }
        sub("Fold Level") {
            for (level in 1..8) item(level.toString()) { EditOps.foldLevel(editor, level, collapse = true) }
        }
        sub("Unfold Level") {
            for (level in 1..8) item(level.toString()) { EditOps.foldLevel(editor, level, collapse = false) }
        }
        item("Summary...") { showSummary() }
    }

    private fun zoom(change: () -> Unit) {
        change()
        ThemeApplier.updateLineNumberWidth(editor, showLineNumbers)
    }

    private fun MenuSpec.encodingMenu() {
        val current = workspace.active.encoding
        for (encoding in TextEncodings.unicode) {
            item(encoding.label, checked = encoding == current) { workspace.setEncoding(encoding) }
        }
        // Notepad++'s Character sets: re-read the file as that character set.
        sub("Character sets") {
            for ((group, sets) in TextEncodings.characterSets.filter { it.isSupported }.groupBy { it.group ?: "" }) {
                sub(group) {
                    for (set in sets) {
                        item(set.label, checked = set == current) {
                            if (workspace.active.uri != null) reloadFromDisk(set) else workspace.setEncoding(set)
                        }
                    }
                }
            }
        }
    }

    private fun MenuSpec.languageMenu() {
        val current = workspace.active.language
        val normal = workspace.languages.normal
        item("None (Normal Text)", checked = current === normal) { workspace.setLanguage(normal) }
        for (language in workspace.languages.menu) {
            item(language.display, checked = language === current) { workspace.setLanguage(language) }
        }
    }

    private fun MenuSpec.settingsMenu() {
        val textMode = editor.keyboardMode == ScintillaView.KeyboardMode.TEXT
        // Notepad++'s dark mode is its DarkModeDefault theme; the other themes are under Theme.
        item("Dark mode", checked = palette.dark) { chooseTheme(if (palette.dark) "" else DARK_THEME) }
        sub("Theme") {
            item("Default (stylers.xml)", checked = themeName.isEmpty()) { chooseTheme("") }
            for (file in themeFiles()) {
                item(file.removeSuffix(".xml"), checked = file == themeName) { chooseTheme(file) }
            }
        }
        item("Keyboard suggestions and autocorrect", checked = textMode) {
            editor.keyboardMode = if (textMode) ScintillaView.KeyboardMode.CODE else ScintillaView.KeyboardMode.TEXT
            prefs.edit().putBoolean("textKeyboard", !textMode).apply()
        }
        item("Show line numbers", checked = showLineNumbers) {
            showLineNumbers = !showLineNumbers
            prefs.edit().putBoolean("lineNumbers", showLineNumbers).apply()
            ThemeApplier.updateLineNumberWidth(editor, showLineNumbers)
        }
        item("Bigger text") { setFontSize(fontSize + 1) }
        item("Smaller text") { setFontSize(fontSize - 1) }
        item("Keyboard log", checked = imeLogView.visibility == View.VISIBLE) {
            imeLogView.visibility = if (imeLogView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            showImeLog()
        }
    }

    private fun themeFiles(): List<String> =
        (assets.list("npp/themes") ?: emptyArray()).filter { it.endsWith(".xml") }.sortedBy { it.lowercase() }

    /** Reads a Notepad++ theme shipped with the app; an empty or unreadable name gives the default one. */
    private fun loadTheme(name: String): NppTheme {
        if (name.isNotEmpty()) {
            try {
                return assets.open("npp/themes/$name").use { NppTheme.load(it) }
            } catch (e: Exception) {
                // Fall through to the default theme.
            }
        }
        return assets.open("npp/stylers.model.xml").use { NppTheme.load(it) }
    }

    private fun chooseTheme(name: String) {
        if (name == themeName) return
        val theme = loadTheme(name)
        themeName = name
        prefs.edit().putString("theme", name).apply()
        if (theme.isDark != palette.dark) {
            // Only the bars around the editor are rebuilt in the other colours. The editor view stays, so the
            // open documents keep their undo history, and a menu that is open stays open.
            palette = Palette(theme.isDark)
            buildScreen(theme.background)
            menuSheet?.dark = palette.dark
            refreshTabs()
            editor.requestFocus()
        }
        workspace.theme = theme
        root.setBackgroundColor(theme.background or 0xFF000000.toInt())
    }

    private fun setFontSize(size: Int) {
        fontSize = size.coerceIn(6, 40)
        prefs.edit().putInt("fontSize", fontSize).apply()
        workspace.restyle()
    }

    private fun showTabMenu(doc: Doc) = showMenu(doc.name) {
        val index = workspace.docs.indexOf(doc)
        item("Close") { requestClose(doc) }
        item("Close All but This") { closeMany(workspace.docs.filter { it !== doc }) }
        item("Close All to the Left") { closeMany(workspace.docs.take(index)) }
        item("Close All to the Right") { closeMany(workspace.docs.drop(index + 1)) }
        item("Save") { save(doc) }
        item("Save As...") { saveAs(doc, copy = false) }
        item("Move Tab Forward", enabled = index < workspace.docs.size - 1) { workspace.move(doc, index + 1) }
        item("Move Tab Backward", enabled = index > 0) { workspace.move(doc, index - 1) }
    }

    // endregion

    // region Dialogs and commands

    private var beginSelect: Long? = null
    private var lastMultiSelect = false to false

    /** Begin/End Select: the first use marks a position, the second selects from there to the caret. */
    private fun beginEndSelect(column: Boolean) {
        val start = beginSelect
        val caret = editor.send(Sci.SCI_GETCURRENTPOS)
        if (start == null) {
            beginSelect = caret
            toast("Selection start marked. Move the caret and choose this command again.")
            return
        }
        beginSelect = null
        val anchor = start.coerceIn(0, editor.send(Sci.SCI_GETLENGTH))
        if (column) {
            editor.send(Sci.SCI_SETRECTANGULARSELECTIONANCHOR, anchor)
            editor.send(Sci.SCI_SETRECTANGULARSELECTIONCARET, caret)
        } else {
            editor.send(Sci.SCI_SETSEL, anchor, caret)
        }
    }

    private fun multiSelectNext(matchCase: Boolean, wholeWord: Boolean) {
        lastMultiSelect = matchCase to wholeWord
        EditOps.multiSelectNext(editor, matchCase, wholeWord)
    }

    private fun insertDateTime(dateStyle: Int) {
        val now = java.util.Date()
        val text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(now) + " " +
            java.text.DateFormat.getDateInstance(dateStyle).format(now)
        editor.typeText(text)
    }

    private fun searchOnInternet() {
        val query = EditOps.selectionOrWord(editor).trim()
        if (query.isEmpty()) return toast("Select some text first")
        val template = prefs.getString("searchEngine", "") ?: ""
        if (template.isNotEmpty()) return openLink(template.replace("%s", Uri.encode(query)))
        try {
            startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query))
        } catch (e: Exception) {
            toast("No app can search the web")
        }
    }

    /** Recently closed files, newest first, as kept in the settings. */
    private fun recentFiles(): List<Pair<Uri, String>> =
        (prefs.getString("recent", "") ?: "").split('\n').filter { '\t' in it }
            .map { Uri.parse(it.substringBefore('\t')) to it.substringAfter('\t') }

    private fun rememberRecent(doc: Doc) {
        val uri = doc.uri ?: return
        val entries = listOf(uri to doc.name) + recentFiles().filter { it.first != uri }
        prefs.edit().putString("recent", entries.take(MAX_RECENT).joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }

    private fun goToLine() {
        val lines = editor.send(Sci.SCI_GETLINECOUNT)
        val current = editor.send(Sci.SCI_LINEFROMPOSITION, editor.send(Sci.SCI_GETCURRENTPOS)) + 1
        val input = EditText(themed).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - $lines"
            setText(current.toString())
            selectAll()
        }
        val container = LinearLayout(themed).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(themed)
            .setTitle("Go to line")
            .setView(container)
            .setPositiveButton("Go") { _, _ ->
                val line = (input.text.toString().toLongOrNull() ?: current).coerceIn(1, lines) - 1
                editor.send(Sci.SCI_ENSUREVISIBLEENFORCEPOLICY, line)
                editor.send(Sci.SCI_GOTOLINE, line)
                editor.requestFocus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun columnModeTip() {
        AlertDialog.Builder(themed)
            .setTitle("Column Mode Tip")
            .setMessage(
                "There are three ways to select a column of text:\n\n" +
                    "1. Tap Alt in the keys row, then drag a finger over the text. Tap Alt twice to keep it on.\n\n" +
                    "2. Lock Alt and Shift (tap each twice), then use the arrow keys.\n\n" +
                    "3. Put the caret at one corner, choose Edit > Begin/End Select in Column Mode, " +
                    "move the caret to the opposite corner and choose it again.\n\n" +
                    "Typing then goes to every line of the column, and Edit > Column Editor fills it with text or numbers.",
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Notepad++'s Column Editor: the same text, or a series of numbers, on every line of a column. */
    private fun columnEditor() {
        fun label(text: String) = TextView(themed).apply {
            this.text = text
            setPadding(0, dp(10f), 0, 0)
        }
        fun field(name: String, value: String, number: Boolean) = EditText(themed).apply {
            contentDescription = name
            setText(value)
            maxLines = 1
            inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        fun choice(name: String, options: List<String>) = Spinner(themed).apply {
            contentDescription = name
            adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_dropdown_item, options)
        }
        val textMode = RadioButton(themed).apply { text = "Text to Insert" }
        val numberMode = RadioButton(themed).apply { text = "Number to Insert" }
        val text = field("Text to insert", "", number = false)
        val initial = field("Initial number", "1", number = true)
        val increase = field("Increase by", "1", number = true)
        val repeat = field("Repeat", "1", number = true)
        val format = choice("Format", listOf("Dec", "Hex", "Oct", "Bin"))
        val leading = choice("Leading", listOf("None", "Zeros", "Spaces"))
        val numbers = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            for ((name, view) in listOf("Initial number" to initial, "Increase by" to increase, "Repeat" to repeat, "Format" to format, "Leading" to leading)) {
                addView(label(name))
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val modes = RadioGroup(themed).apply {
            addView(textMode)
            addView(numberMode)
            setOnCheckedChangeListener { _, _ ->
                text.visibility = if (textMode.isChecked) View.VISIBLE else View.GONE
                numbers.visibility = if (numberMode.isChecked) View.VISIBLE else View.GONE
            }
        }
        textMode.isChecked = true
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(modes)
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(numbers)
        }
        AlertDialog.Builder(themed)
            .setTitle("Column Editor")
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val lines = if (textMode.isChecked) {
                    val value = text.text.toString()
                    EditOps.columnInsert(editor) { count -> List(count) { value } }
                } else {
                    val base = listOf(10, 16, 8, 2)[format.selectedItemPosition.coerceIn(0, 3)]
                    val pad = TextTransforms.Leading.entries[leading.selectedItemPosition.coerceIn(0, 2)]
                    EditOps.columnInsert(editor) { count ->
                        TextTransforms.columnNumbers(
                            count, initial.text.toString().toLongOrNull() ?: 0, increase.text.toString().toLongOrNull() ?: 0,
                            repeat.text.toString().toIntOrNull() ?: 1, base, pad,
                        )
                    }
                }
                toast("Column Editor: $lines lines")
                editor.requestFocus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // region Tools, Window and ? menus

    private fun MenuSpec.toolsMenu() {
        for ((name, algorithm) in Hashes.ALGORITHMS) {
            sub(name) {
                item("Generate...") { hashDialog(name, algorithm) }
                item("Generate from files...") { hashFiles(name, algorithm) }
                item("Generate from selection into clipboard") {
                    val start = editor.send(Sci.SCI_GETSELECTIONSTART)
                    val end = editor.send(Sci.SCI_GETSELECTIONEND)
                    if (end <= start) toast("Select some text first") else {
                        editor.setClipboard(Hashes.of(algorithm, editor.range(start, end)))
                        toast("$name digest copied")
                    }
                }
            }
        }
    }

    private fun MenuSpec.windowMenu() {
        sub("Sort By") {
            fun type(doc: Doc) = doc.name.substringAfterLast('.', "").lowercase()
            val orders = listOf<Pair<String, Comparator<Doc>>>(
                "Name A to Z" to compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                "Name Z to A" to compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name },
                "Path A to Z" to compareBy(String.CASE_INSENSITIVE_ORDER) { it.uri?.toString() ?: it.name },
                "Path Z to A" to compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.uri?.toString() ?: it.name },
                "Type A to Z" to compareBy<Doc> { type(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                "Type Z to A" to compareByDescending<Doc> { type(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                "Content Length Ascending" to compareBy { it.length },
                "Content Length Descending" to compareByDescending { it.length },
                "Modified Time Ascending" to compareBy { it.modified },
                "Modified Time Descending" to compareByDescending { it.modified },
                "Most Recently Used First" to compareByDescending { it.lastShown },
            )
            for ((label, order) in orders) item(label) { workspace.sortTabs(order) }
        }
        item("Windows...") { showWindowsList() }
        for (doc in workspace.docs) {
            item(doc.name, checked = doc === workspace.active) { workspace.activate(doc) }
        }
    }

    /** Notepad++'s Windows dialog and the tab bar's list button: every open document, with what can be done to it. */
    private fun showWindowsList() = showMenu("Windows") {
        for (doc in workspace.docs.toList()) {
            sub((if (workspace.isDirty(doc)) "\u25CF " else "") + doc.name) {
                item("Activate") { workspace.activate(doc) }
                item("Save") { save(doc) }
                item("Close") { requestClose(doc) }
            }
        }
    }

    private fun MenuSpec.helpMenu() {
        item("Pocketpad++ Home") { openLink("https://github.com/revelationnow/pocketpadpp") }
        item("Pocketpad++ Project Page") { openLink("https://github.com/revelationnow/pocketpadpp") }
        item("Notepad++ Online User Manual") { openLink("https://npp-user-manual.org/") }
        item("Report a Problem") { openLink("https://github.com/revelationnow/pocketpadpp/issues") }
        item("Debug Info...") { debugInfo() }
        item("About Pocketpad++...") { about() }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No app can open $url")
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun debugInfo() {
        val keyboard = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: "?"
        val info = listOf(
            "Pocketpad++ ${versionName()}",
            "Scintilla 5.6.9, Lexilla 5.5.5",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
            "Screen: ${resources.displayMetrics.widthPixels} x ${resources.displayMetrics.heightPixels}, density ${resources.displayMetrics.density}",
            "Keyboard: $keyboard",
            "Keyboard suggestions: ${if (editor.keyboardMode == ScintillaView.KeyboardMode.TEXT) "on" else "off"}",
            "Theme: ${themeName.ifEmpty { "default" }}",
            "Open documents: ${workspace.docs.size}",
        ).joinToString("\n")
        AlertDialog.Builder(themed)
            .setTitle("Debug Info")
            .setMessage(info)
            .setPositiveButton("Copy to Clipboard") { _, _ -> editor.setClipboard(info) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun about() {
        AlertDialog.Builder(themed)
            .setTitle("Pocketpad++ ${versionName()}")
            .setMessage(
                "A free, open-source text and code editor for Android in the spirit of Notepad++.\n\n" +
                    "Built on Scintilla and Lexilla by Neil Hodgson and contributors, and on the language definitions, " +
                    "themes and user-defined-language lexer of Notepad++ by Don Ho and contributors.\n\n" +
                    "Pocketpad++ is an independent project, not affiliated with or endorsed by Notepad++.\n\n" +
                    "Licence: GNU Affero General Public License 3.0",
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Tools > Generate: the digest of a text, updated while it is typed. */
    private fun hashDialog(name: String, algorithm: String) {
        val input = EditText(themed).apply {
            contentDescription = "Text to hash"
            hint = "Text"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 5
            setText(EditOps.selectionOrWord(editor).takeIf { editor.send(Sci.SCI_GETSELECTIONEND) > editor.send(Sci.SCI_GETSELECTIONSTART) } ?: "")
        }
        val eachLine = android.widget.CheckBox(themed).apply { text = "Treat each line as a separate string" }
        val result = TextView(themed).apply {
            contentDescription = "Digest"
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8f), 0, 0)
        }
        fun update() {
            result.text = Hashes.ofText(algorithm, input.text.toString(), eachLine.isChecked)
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = update()
        })
        eachLine.setOnCheckedChangeListener { _, _ -> update() }
        update()
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(eachLine)
            addView(result)
        }
        AlertDialog.Builder(themed)
            .setTitle("Generate $name digest")
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton("Copy to Clipboard") { _, _ -> editor.setClipboard(result.text.toString()) }
            .setNegativeButton("Close", null)
            .show()
    }

    private var hashRequest: Pair<String, String>? = null

    private fun hashFiles(name: String, algorithm: String) {
        hashRequest = name to algorithm
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_HASH)
        } catch (e: Exception) {
            toast("No file picker is available on this device")
        }
    }

    /** Digests of files as "digest  name" lines, the layout of md5sum and friends. */
    internal fun showFileHashes(name: String, algorithm: String, uris: List<Uri>) {
        io.execute {
            val lines = uris.map { uri ->
                val digest = runCatching {
                    (contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file")).use { input ->
                        val md = java.security.MessageDigest.getInstance(algorithm)
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            md.update(buffer, 0, n)
                        }
                        md.digest().joinToString("") { "%02x".format(it) }
                    }
                }.getOrElse { "cannot read: ${it.message ?: it.javaClass.simpleName}" }
                "$digest  ${FileIo.displayName(this, uri)}"
            }.joinToString("\n")
            ui.post {
                if (destroyed) return@post
                AlertDialog.Builder(themed)
                    .setTitle("$name digest of ${uris.size} file(s)")
                    .setMessage(lines)
                    .setPositiveButton("Copy to Clipboard") { _, _ -> editor.setClipboard(lines) }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    // endregion

    /** Edit > Character Panel: the 256 single-byte characters with their codes; a tap inserts one. */
    private fun characterPanel() = showMenu("Character Panel") {
        val names = listOf(
            "NUL", "SOH", "STX", "ETX", "EOT", "ENQ", "ACK", "BEL", "BS", "TAB", "LF", "VT", "FF", "CR", "SO", "SI", "DLE",
            "DC1", "DC2", "DC3", "DC4", "NAK", "SYN", "ETB", "CAN", "EM", "SUB", "ESC", "FS", "GS", "RS", "US", "Space",
        )
        val latin = java.nio.charset.Charset.forName("windows-1252")
        for (code in 0..255) {
            val character = if (code < 128) code.toChar().toString() else String(byteArrayOf(code.toByte()), latin)
            val shown = names.getOrNull(code) ?: if (code == 127) "DEL" else character
            item("%3d   %02X   %s".format(code, code, shown)) { editor.typeText(character) }
        }
    }

    /** Edit > Clipboard History: what was copied in this app lately; a tap pastes it. */
    private fun clipboardHistory() = showMenu("Clipboard History") {
        if (clipHistory.isEmpty()) item("Nothing has been copied here yet", enabled = false) {}
        for (text in clipHistory.toList()) {
            item(text.replace("\r\n", "\u23CE").replace('\n', '\u23CE').replace('\r', '\u23CE').take(60)) { editor.typeText(text) }
        }
    }

    private fun pickFile(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (e: Exception) {
            toast("No file picker is available on this device")
        }
    }

    private fun createFile(request: Int, name: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (e: Exception) {
            toast("No file picker is available on this device")
        }
    }

    /** File > Load Session: opens the files a Notepad++-style session file lists. */
    internal fun loadSessionText(xml: String) {
        val uris = SessionStore.read(xml).entries.mapNotNull { entry -> entry.location?.let { Uri.parse(it) } }
        if (uris.isEmpty()) toast("The session file lists no files") else openUris(uris)
    }

    /** File > Rename: a new name for the tab, and for the file in storage if there is one. */
    private fun renameFile() {
        val doc = workspace.active
        val input = EditText(themed).apply {
            contentDescription = "New name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(doc.name)
            selectAll()
        }
        val container = LinearLayout(themed).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(themed)
            .setTitle("Rename")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                val uri = doc.uri
                if (name.isEmpty() || name == doc.name || doc !in workspace.docs) return@setPositiveButton
                if (uri == null) return@setPositiveButton workspace.renamed(doc, null, name)
                try {
                    val renamed = if (uri.scheme == "file") {
                        val from = java.io.File(uri.path ?: throw java.io.IOException("No path"))
                        val to = java.io.File(from.parentFile, name)
                        if (to.exists() || !from.renameTo(to)) throw java.io.IOException("the file could not be renamed")
                        Uri.fromFile(to)
                    } else {
                        android.provider.DocumentsContract.renameDocument(contentResolver, uri, name)
                            ?: throw java.io.IOException("the storage provider refused")
                    }
                    FileIo.persistAccess(this, renamed)
                    workspace.renamed(doc, renamed, FileIo.displayName(this, renamed))
                } catch (e: Exception) {
                    toast("Cannot rename: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Notepad++'s Move to Recycle Bin. Android has no recycle bin, so this asks first and then deletes. */
    private fun deleteFile() {
        val doc = workspace.active
        val uri = doc.uri ?: return
        AlertDialog.Builder(themed)
            .setTitle("Delete")
            .setMessage("Delete the file \"${doc.name}\" from storage? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = if (uri.scheme == "file") java.io.File(uri.path ?: "").delete()
                    else android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    if (!deleted) throw java.io.IOException("the storage provider refused")
                    if (doc in workspace.docs) workspace.close(doc)
                } catch (e: Exception) {
                    toast("Cannot delete: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setTabColour(doc: Doc, colour: Int) {
        doc.colour = colour
        refreshTabs()
    }

    /** File > Open in Default Viewer and View > View Current File in Browser: hands the saved file to another app. */
    private fun viewInAnotherApp(doc: Doc, forcedType: String?) {
        val uri = doc.uri ?: return toast("Save the document first")
        if (workspace.isDirty(doc)) toast("The other app sees the file as last saved")
        val type = forcedType ?: contentResolver.getType(uri) ?: "text/plain"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, doc.name))
        } catch (e: Exception) {
            toast("No app can show this file")
        }
    }

    /** On Selection > Open File: the selection as a file path, a content or file address, or a web link. */
    private fun openSelectedFile() {
        val text = EditOps.selectionOrWord(editor).trim()
        when {
            text.isEmpty() -> toast("Select a path or link first")
            text.startsWith("content://") || text.startsWith("file://") -> openUris(listOf(Uri.parse(text)))
            text.startsWith("http://") || text.startsWith("https://") -> openLink(text)
            text.startsWith("/") && java.io.File(text).canRead() -> openUris(listOf(Uri.fromFile(java.io.File(text))))
            else -> toast("This app cannot reach \"${text.take(60)}\"")
        }
    }

    // Full screen hides the system bars; distraction free also hides the app's own bars. Back leaves either.
    private var fullScreen = false
    private var distractionFree = false

    private fun setScreenMode(full: Boolean, bare: Boolean) {
        fullScreen = full
        distractionFree = full && bare
        for (bar in chromeBars) bar.visibility = if (distractionFree) View.GONE else View.VISIBLE
        applySystemBars()
        if (full) toast("Press Back to leave ${if (distractionFree) "distraction free" else "full screen"} mode")
    }

    private fun applySystemBars() {
        val light = if (palette.dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 27) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        val immersive = if (fullScreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            0
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = light or immersive
    }

    /** Search > Find characters in range: non-ASCII, ASCII, or any range of byte values. */
    private fun findCharsInRange() {
        fun number(name: String, value: String) = EditText(themed).apply {
            contentDescription = name
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value)
            minEms = 3
        }
        val nonAscii = RadioButton(themed).apply { text = "Non-ASCII Characters (128-255)" }
        val ascii = RadioButton(themed).apply { text = "ASCII Characters (0-127)" }
        val custom = RadioButton(themed).apply { text = "My Range (0-255):" }
        val from = number("Range from", "128")
        val to = number("Range to", "255")
        val up = android.widget.CheckBox(themed).apply { text = "Search upwards" }
        val wrap = android.widget.CheckBox(themed).apply {
            text = "Wrap around"
            isChecked = true
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(RadioGroup(themed).apply {
                addView(nonAscii)
                addView(ascii)
                addView(custom)
            })
            addView(LinearLayout(themed).apply {
                addView(from)
                addView(TextView(themed).apply { text = "  to  " })
                addView(to)
            })
            addView(up)
            addView(wrap)
        }
        nonAscii.isChecked = true
        AlertDialog.Builder(themed)
            .setTitle("Find Characters in Range")
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton("Find") { _, _ ->
                val (low, high) = when {
                    nonAscii.isChecked -> 128 to 255
                    ascii.isChecked -> 0 to 127
                    else -> (from.text.toString().toIntOrNull() ?: 0).coerceIn(0, 255) to (to.text.toString().toIntOrNull() ?: 255).coerceIn(0, 255)
                }
                if (!EditOps.findCharInRange(editor, low, high, forward = !up.isChecked, wrap = wrap.isChecked)) {
                    toast("No character in the range $low to $high was found")
                }
                editor.requestFocus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleBookmark() {
        val line = editor.send(Sci.SCI_LINEFROMPOSITION, editor.send(Sci.SCI_GETCURRENTPOS))
        val marker = ThemeApplier.BOOKMARK_MARKER
        if (editor.send(Sci.SCI_MARKERGET, line) and (1L shl marker) != 0L) {
            editor.send(Sci.SCI_MARKERDELETE, line, marker.toLong())
        } else {
            editor.send(Sci.SCI_MARKERADD, line, marker.toLong())
        }
    }

    private fun goToBookmark(forward: Boolean) {
        val mask = 1L shl ThemeApplier.BOOKMARK_MARKER
        val line = editor.send(Sci.SCI_LINEFROMPOSITION, editor.send(Sci.SCI_GETCURRENTPOS))
        var target = if (forward) editor.send(Sci.SCI_MARKERNEXT, line + 1, mask) else editor.send(Sci.SCI_MARKERPREVIOUS, line - 1, mask)
        if (target < 0) {
            // Wrap around, as Notepad++ does.
            target = if (forward) editor.send(Sci.SCI_MARKERNEXT, 0, mask) else editor.send(Sci.SCI_MARKERPREVIOUS, editor.send(Sci.SCI_GETLINECOUNT), mask)
        }
        if (target < 0) return toast("No bookmarks")
        editor.send(Sci.SCI_ENSUREVISIBLEENFORCEPOLICY, target)
        editor.send(Sci.SCI_GOTOLINE, target)
    }

    private fun showSummary() {
        val doc = workspace.active
        val (characters, words, lines) = EditOps.summary(editor)
        val bytes = editor.send(Sci.SCI_GETLENGTH)
        val start = editor.send(Sci.SCI_GETSELECTIONSTART)
        val end = editor.send(Sci.SCI_GETSELECTIONEND)
        val message = buildString {
            append(doc.name).append('\n')
            doc.uri?.let { append(it).append('\n') }
            append('\n')
            append("Characters: ").append(characters).append('\n')
            append("Words: ").append(words).append('\n')
            append("Lines: ").append(lines).append('\n')
            append("Document length: ").append(bytes).append(" bytes as UTF-8\n")
            append(editor.send(Sci.SCI_COUNTCHARACTERS, start, end)).append(" selected characters (").append(end - start).append(" bytes)")
        }
        AlertDialog.Builder(themed).setTitle("Summary").setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    // endregion

    private companion object {
        const val REQUEST_OPEN = 1
        const val REQUEST_SAVE_AS = 2
        const val REQUEST_HASH = 3
        const val REQUEST_LOAD_SESSION = 4
        const val REQUEST_SAVE_SESSION = 5

        /** Edit > On Selection > Change Search Engine: an empty address means the device's own web search. */
        val SEARCH_ENGINES = listOf(
            "Device default" to "",
            "DuckDuckGo" to "https://duckduckgo.com/?q=%s",
            "Google" to "https://www.google.com/search?q=%s",
            "Bing" to "https://www.bing.com/search?q=%s",
            "Yahoo!" to "https://search.yahoo.com/search?q=%s",
            "Stack Overflow" to "https://stackoverflow.com/search?q=%s",
        )
        const val MAX_RECENT = 10
        const val DARK_THEME = "DarkModeDefault.xml"
        const val IME_LOG_LINES = 200
    }
}
