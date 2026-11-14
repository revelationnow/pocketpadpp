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
import android.widget.CheckBox
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
import io.github.revelationnow.pocketpadpp.app.DocumentMap
import io.github.revelationnow.pocketpadpp.app.FileIo
import io.github.revelationnow.pocketpadpp.app.FindBar
import io.github.revelationnow.pocketpadpp.app.FolderNode
import io.github.revelationnow.pocketpadpp.app.FolderTree
import io.github.revelationnow.pocketpadpp.app.PanelSpec
import io.github.revelationnow.pocketpadpp.app.SidePanel
import io.github.revelationnow.pocketpadpp.app.LoadedFile
import io.github.revelationnow.pocketpadpp.app.MenuSheet
import io.github.revelationnow.pocketpadpp.app.Palette
import io.github.revelationnow.pocketpadpp.app.MenuSpec
import io.github.revelationnow.pocketpadpp.app.TabStrip
import io.github.revelationnow.pocketpadpp.app.Workspace
import io.github.revelationnow.pocketpadpp.core.ApiIndex
import io.github.revelationnow.pocketpadpp.core.CallSite
import io.github.revelationnow.pocketpadpp.core.Completion
import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.EditorOptions
import io.github.revelationnow.pocketpadpp.core.FunctionEntry
import io.github.revelationnow.pocketpadpp.core.FunctionParser
import io.github.revelationnow.pocketpadpp.core.KeyBinding
import io.github.revelationnow.pocketpadpp.core.ShortcutTable
import io.github.revelationnow.pocketpadpp.core.Shortcuts
import io.github.revelationnow.pocketpadpp.core.StyleChange
import io.github.revelationnow.pocketpadpp.core.StyleOverrides
import io.github.revelationnow.pocketpadpp.core.WordStyle
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.LanguageRegistry
import io.github.revelationnow.pocketpadpp.core.Hashes
import io.github.revelationnow.pocketpadpp.core.Invisibles
import io.github.revelationnow.pocketpadpp.core.SessionStore
import io.github.revelationnow.pocketpadpp.app.ResultsPanel
import io.github.revelationnow.pocketpadpp.core.Hit
import io.github.revelationnow.pocketpadpp.core.SearchOptions
import io.github.revelationnow.pocketpadpp.core.Searcher
import io.github.revelationnow.pocketpadpp.core.MacroStep
import io.github.revelationnow.pocketpadpp.core.Macros
import io.github.revelationnow.pocketpadpp.core.Marks
import io.github.revelationnow.pocketpadpp.core.NppTheme
import io.github.revelationnow.pocketpadpp.core.ProjectNode
import io.github.revelationnow.pocketpadpp.core.ProjectStore
import io.github.revelationnow.pocketpadpp.core.RunCommand
import io.github.revelationnow.pocketpadpp.core.TextEncoding
import io.github.revelationnow.pocketpadpp.core.TextEncodings
import io.github.revelationnow.pocketpadpp.core.UserLanguage
import io.github.revelationnow.pocketpadpp.core.UserLanguages
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
open class MainActivity : Activity() {

    /** True for a window opened with Open in New Instance: it shows what it was given and keeps no session. */
    protected open val secondWindow: Boolean = false

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
    private lateinit var results: ResultsPanel

    // A second editor that is never shown: it runs searches over other documents and over files on disk
    // with exactly the rules the Find bar has, without disturbing what is on screen.
    private var searchEditor: ScintillaView? = null
    private var searchRun = 0
    private val clipHistory = ArrayDeque<String>()

    // The macro being recorded, or the last one recorded.
    private val currentMacro = ArrayList<MacroStep>()
    private var recording = false
    private var macroPaused = false
    private var keyboardBeforeRecording: ScintillaView.KeyboardMode? = null

    // The on-screen Ctrl, Alt and Shift keys: SCMOD_* bits held for the next key only, and those locked on.
    private var armedModifiers = 0
    private var lockedModifiers = 0
    private val modifierKeys = LinkedHashMap<Int, Button>()
    private lateinit var root: LinearLayout
    private lateinit var editorRow: LinearLayout
    private lateinit var splitArea: LinearLayout
    internal var documentMap: DocumentMap? = null
    private var themeName = ""

    // The theme as its file has it, and what the Style Configurator changed about it.
    private lateinit var baseTheme: NppTheme
    private var styleOverrides = StyleOverrides()
    private var fontName = "monospace"
    internal var options = EditorOptions()
    internal val shortcutTable = ShortcutTable(DEFAULT_SHORTCUTS)
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
        baseTheme = loadTheme(themeName)
        styleOverrides = StyleOverrides.decode(prefs.getString("styles:$themeName", "") ?: "")
        val theme = baseTheme.with(styleOverrides)
        palette = Palette(theme.isDark)
        setTheme(if (palette.dark) R.style.AppTheme_Dark else R.style.AppTheme)
        super.onCreate(savedInstanceState)
        fontSize = prefs.getInt("fontSize", 13)
        fontName = prefs.getString("fontName", "monospace") ?: "monospace"
        options = EditorOptions(
            tabSize = prefs.getInt("tabSize", 4),
            useSpaces = prefs.getBoolean("useSpaces", false),
            autoIndent = prefs.getBoolean("autoIndent", true),
            highlightCurrentLine = prefs.getBoolean("currentLine", true),
            caret = prefs.getInt("caret", 2),
            caretBlinks = prefs.getBoolean("caretBlinks", true),
            scrollBeyondLastLine = prefs.getBoolean("scrollBeyond", false),
            edgeColumn = prefs.getInt("edgeColumn", 0),
        )
        shortcutTable.decode(prefs.getString("shortcuts", "") ?: "")
        showLineNumbers = prefs.getBoolean("lineNumbers", true)

        editor = ScintillaView(this)
        buildScreen(theme.background)

        // Notepad++'s own language and style definitions, shipped as assets.
        val languages = assets.open("npp/languages.tsv").use { table ->
            assets.open("npp/langs.model.xml").use { xml -> LanguageRegistry.load(table, xml) }
        }

        loadUserLanguages(languages)
        languages.darkTheme = palette.dark

        editor.send(Sci.SCI_SETWRAPMODE, (if (prefs.getBoolean("wrap", false)) Sci.SC_WRAP_WORD else Sci.SC_WRAP_NONE).toLong())
        editor.keyboardMode = if (prefs.getBoolean("textKeyboard", false)) ScintillaView.KeyboardMode.TEXT else ScintillaView.KeyboardMode.CODE

        workspace = Workspace(this, editor, languages, theme) {
            ViewMetrics(
                fontName = fontName, fontSize = fontSize, foldMarginWidth = dp(22f), leftPadding = dp(4f),
                showLineNumbers = showLineNumbers, pixelScale = resources.displayMetrics.density,
            )
        }
        workspace.onTabsChanged = { refreshTabs() }
        workspace.keepsSession = !secondWindow
        workspace.afterStyle = {
            options.apply(editor, resources.displayMetrics.density)
            styleDocumentMap()
            styleOtherView()
        }
        syncVertical = prefs.getBoolean("syncVertical", false)
        syncHorizontal = prefs.getBoolean("syncHorizontal", false)
        workspace.newDocumentEol = Eol.fromSciMode(prefs.getInt("newEol", Eol.LF.sciMode))
        workspace.newDocumentEncoding = TextEncodings.byId(prefs.getString("newEncoding", null)) ?: TextEncodings.UTF8
        // The tab the app starts with was made before these settings were read.
        workspace.active.encoding = workspace.newDocumentEncoding
        editor.send(Sci.SCI_SETEOLMODE, workspace.newDocumentEol.sciMode.toLong())
        options.apply(editor, resources.displayMetrics.density)
        applySelectionMenu()
        if (prefs.getBoolean("documentMap", false)) setDocumentMap(true)
        editor.rightToLeft = prefs.getBoolean("rightToLeft", false)

        editor.onStateChanged = { onEditorStateChanged() }
        editor.onTextChanged = { workspace.textChanged() }
        editor.onCharAdded = { ch ->
            // Auto-indent happens again on playback, so what it does is not part of a macro.
            macroPaused = true
            if (options.autoIndent) EditOps.autoIndent(editor, ch)
            macroPaused = false
            completeWhileTyping(ch)
        }
        editor.onMacroStep = { message, wParam, lParam, text ->
            if (recording && !macroPaused) currentMacro += MacroStep(message, wParam, lParam, text)
        }
        editor.onZoomChanged = {
            ThemeApplier.updateLineNumberWidth(editor, showLineNumbers)
            zoomOtherView()
        }
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

        if (secondWindow) handleIntent(intent) else restoreSession { handleIntent(intent) }
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

        // The editor, with room beside it for the Document Map.
        editorRow = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        editorRow.addView(editor, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        documentMap?.let { map ->
            (map.parent as? ViewGroup)?.removeView(map)
            editorRow.addView(map, LinearLayout.LayoutParams(dp(MAP_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // The editor row, and beside or below it the second view when the screen is split.
        splitArea = LinearLayout(themed)
        splitArea.addView(editorRow)
        root.addView(splitArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        otherPane = null
        if (otherDoc != null) addOtherPane()
        layoutSplit()

        results = ResultsPanel(themed, palette)
        results.onOpen = { group, hit -> goToHit(group, hit) }
        results.onClose = {
            searchRun++
            editor.requestFocus()
        }
        root.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.3f).toInt()))

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

        root.addView(completionBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        searchEditor?.destroy()
        otherView?.destroy()
        editor.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            findBar.isOpen -> findBar.close()
            results.isOpen -> results.close()
            fullScreen -> setScreenMode(full = false, bare = false)
            // A second window keeps no session, so what is unsaved in it would be gone.
            secondWindow && workspace.docs.any { workspace.isDirty(it) } -> AlertDialog.Builder(themed)
                .setTitle("Close this window?")
                .setMessage("It has unsaved changes, and a second window does not keep them for next time.")
                .setPositiveButton("Close without saving") { _, _ -> finish() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutSplit()
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
        // The shortcut mapper's table first. Commands that share a shortcut take turns by which one is available,
        // which is how one key both starts and stops recording.
        val bound = shortcutTable.commands(modifiers, key)
        if (bound.isNotEmpty()) {
            bound.firstOrNull { runCommand(it) }
            updateStatus()
            return true
        }
        val docs = workspace.docs
        val index = docs.indexOf(workspace.active)
        when (modifiers) {
            Sci.SCMOD_CTRL -> when (key) {
                Sci.SCK_TAB -> workspace.activate(docs[(index + 1) % docs.size])
                else -> return false
            }
            Sci.SCMOD_CTRL or Sci.SCMOD_SHIFT -> when (key) {
                Sci.SCK_TAB -> workspace.activate(docs[(index + docs.size - 1) % docs.size])
                else -> return false
            }
            // Alt and a letter opens that menu, as on Windows.
            Sci.SCMOD_ALT -> when (key) {
                'F'.code -> showMainMenu("File")
                'E'.code -> showMainMenu("Edit")
                'S'.code -> showMainMenu("Search")
                'V'.code -> showMainMenu("View")
                'N'.code -> showMainMenu("Encoding")
                'L'.code -> showMainMenu("Language")
                'T'.code -> showMainMenu("Settings")
                'O'.code -> showMainMenu("Tools")
                'M'.code -> showMainMenu("Macro")
                'R'.code -> showMainMenu("Run")
                'W'.code -> showMainMenu("Window")
                else -> return false
            }
            else -> return false
        }
        updateStatus()
        return true
    }

    // region Commands by name: what the shortcut mapper and the selection menu work with

    /** The entries of one menu level, found by the labels leading to it. Entries that come and go are left out. */
    private fun entriesAt(path: List<String>): List<MenuSpec.Entry> {
        var spec = MenuSpec().apply { cataloguing = true; mainMenu() }
        for (segment in path) {
            val children = spec.entries.firstOrNull { it.id == segment && it.children != null }?.children ?: return emptyList()
            spec = MenuSpec().apply { cataloguing = true; children() }
        }
        return spec.entries
    }

    private fun commandEntry(id: String): MenuSpec.Entry? {
        val path = id.split(COMMAND_SEPARATOR)
        return entriesAt(path.dropLast(1)).firstOrNull { it.id == path.last() && it.action != null }
    }

    /** Runs a menu command by its place in the menus, such as "Edit > Line Operations > Join Lines". */
    internal fun runCommand(id: String): Boolean {
        val entry = commandEntry(id) ?: return false
        if (!entry.enabled) return false
        entry.action?.invoke()
        return true
    }

    /** Shortcuts that point at a command the menus do not have. Empty unless a menu was renamed without them. */
    internal fun unknownShortcutCommands(): List<String> = DEFAULT_SHORTCUTS.keys.filter { commandEntry(it) == null }

    private fun showShortcutMapper() = showMenu("Shortcut Mapper") { mapperLevel(emptyList()) }

    private fun MenuSpec.mapperLevel(path: List<String>) {
        if (path.isEmpty()) {
            item("Reset All Shortcuts", enabled = shortcutTable.hasChanges, checked = false) {
                shortcutTable.resetAll()
                saveShortcuts()
            }
        }
        for (entry in entriesAt(path)) {
            val full = path + entry.id
            if (entry.children != null) {
                sub(entry.label) { mapperLevel(full) }
            } else {
                val id = full.joinToString(COMMAND_SEPARATOR)
                // A tick marks the shortcuts that were changed.
                item(entry.label, checked = shortcutTable.isCustom(id), hint = Shortcuts.label(shortcutTable.binding(id))) {
                    shortcutDialog(id, entry.label)
                }
            }
        }
    }

    private fun saveShortcuts() {
        prefs.edit().putString("shortcuts", shortcutTable.encode()).apply()
    }

    private fun shortcutDialog(id: String, name: String) {
        val current = shortcutTable.binding(id)
        fun box(label: String, bit: Int) = CheckBox(themed).apply {
            text = label
            isChecked = current != null && current.modifiers and bit != 0
        }
        val ctrl = box("Ctrl", Sci.SCMOD_CTRL)
        val alt = box("Alt", Sci.SCMOD_ALT)
        val shift = box("Shift", Sci.SCMOD_SHIFT)
        val names = listOf("None") + Shortcuts.KEYS.map { it.first }
        val keys = Spinner(themed).apply {
            contentDescription = "Key"
            adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(1 + Shortcuts.KEYS.indexOfFirst { it.second == current?.key })
        }
        val container = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ctrl)
                addView(alt)
                addView(shift)
            })
            addView(LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(themed).apply { text = "Key  " })
                addView(keys)
            })
            addView(TextView(themed).apply {
                text = "With a keyboard attached, simply press the shortcut."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8f), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(themed)
            .setTitle(name)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("Default") { _, _ ->
                shortcutTable.reset(id)
                saveShortcuts()
                menuSheet?.refresh()
            }
            .create()
        dialog.setOnKeyListener { _, _, event ->
            val key = ScintillaView.shortcutKeyOf(event)
            // Plain keys are left to the dialog, which needs Tab, Enter and the arrows for itself.
            val capture = key != 0 && (event.isCtrlPressed || event.isAltPressed || key > Shortcuts.FUNCTION)
            if (capture && event.action == android.view.KeyEvent.ACTION_DOWN) {
                ctrl.isChecked = event.isCtrlPressed
                alt.isChecked = event.isAltPressed
                shift.isChecked = event.isShiftPressed
                keys.setSelection(1 + Shortcuts.KEYS.indexOfFirst { it.second == key })
            }
            capture
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val modifiers = (if (ctrl.isChecked) Sci.SCMOD_CTRL else 0) or (if (alt.isChecked) Sci.SCMOD_ALT else 0) or
                (if (shift.isChecked) Sci.SCMOD_SHIFT else 0)
            val chosen = Shortcuts.KEYS.getOrNull(keys.selectedItemPosition - 1)?.let { KeyBinding(modifiers, it.second) }
            if (chosen != null && !Shortcuts.usable(chosen)) {
                toast("Add Ctrl or Alt, or pick a function key")
                return@setOnClickListener
            }
            val assign = {
                shortcutTable.assign(id, chosen)
                saveShortcuts()
                dialog.dismiss()
                menuSheet?.refresh()
            }
            val others = if (chosen == null) emptyList() else shortcutTable.commands(chosen.modifiers, chosen.key).filter { it != id }
            if (others.isEmpty()) assign() else {
                AlertDialog.Builder(themed)
                    .setTitle("${Shortcuts.label(chosen)} is taken")
                    .setMessage("It belongs to:\n" + others.joinToString("\n") + "\n\nUse it for $name instead?")
                    .setPositiveButton("Reassign") { _, _ -> assign() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Notepad++'s Edit Popup ContextMenu: which commands follow cut, copy and paste in the selection toolbar. */
    private fun selectionMenuDialog() {
        val chosen = selectionMenuIds().toMutableSet()
        val choices = SELECTION_MENU_CHOICES.filter { commandEntry(it.first) != null }
        AlertDialog.Builder(themed)
            .setTitle("Selection Menu Items")
            .setMultiChoiceItems(
                choices.map { it.second }.toTypedArray(), choices.map { it.first in chosen }.toBooleanArray(),
            ) { _, which, checked -> if (checked) chosen += choices[which].first else chosen -= choices[which].first }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putString("selectionMenu", choices.map { it.first }.filter { it in chosen }.joinToString("\n")).apply()
                applySelectionMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectionMenuIds(): List<String> =
        (prefs.getString("selectionMenu", null) ?: DEFAULT_SELECTION_MENU).split('\n').filter { it.isNotEmpty() }

    private fun applySelectionMenu() {
        val names = SELECTION_MENU_CHOICES.toMap()
        editor.extraActions = selectionMenuIds().mapNotNull { id ->
            names[id]?.let { name -> name to { runCommand(id); updateStatus() } }
        }
    }

    // endregion

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
        // The second view follows its document: gone with it, renamed with it.
        otherDoc?.let { if (it !in workspace.docs) closeOtherView() else otherTitle?.text = it.name }
        shownDirty = workspace.isDirty(workspace.active)
        tabs.setTabs(workspace.docs.map { TabStrip.Tab(it.name, workspace.isDirty(it), it === workspace.active, it.colour, it.pinned) })
        updateStatus()
    }

    private fun onEditorStateChanged() {
        documentMap?.sync()
        otherView?.let { followScroll(editor, it, mainScroll, otherScroll) }
        completionFollowsCaret()
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
        if (recording) parts += "REC"
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

    internal fun openUris(uris: List<Uri>, then: (() -> Unit)? = null) {
        for (uri in uris) {
            val existing = workspace.findByUri(uri)
            if (existing != null) {
                workspace.activate(existing)
                then?.invoke()
                continue
            }
            FileIo.persistAccess(this, uri)
            io.execute {
                val result = runCatching { FileIo.read(this, uri) }
                ui.post {
                    if (destroyed) return@post
                    result.onSuccess {
                        workspace.open(uri, it)
                        then?.invoke()
                    }.onFailure { toast("Cannot open the file: ${it.message ?: it.javaClass.simpleName}") }
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
                    // A language definition takes effect when it is saved.
                    if (uri.scheme == "file" && uri.path?.startsWith(udlFolder().path) == true) reloadUserLanguages()
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

            REQUEST_IMPORT_THEME -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
                if (uris.isEmpty()) data.data?.let { uris += it }
                io.execute {
                    val imported = uris.mapNotNull { uri ->
                        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                        bytes?.let { importTheme(FileIo.displayName(this, uri), it) }
                    }
                    ui.post {
                        if (destroyed) return@post
                        toast(
                            when {
                                imported.isEmpty() -> "That is not a Notepad++ theme file"
                                imported.size == 1 -> "Imported ${imported[0].removeSuffix(".xml")}. It is now listed under Theme."
                                else -> "Imported ${imported.size} themes. They are now listed under Theme."
                            },
                        )
                    }
                }
            }

            REQUEST_IMPORT_UDL -> data.data?.let { uri ->
                io.execute {
                    val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    val name = FileIo.displayName(this, uri)
                    ui.post {
                        if (destroyed) return@post
                        val names = bytes?.let { importUserLanguages(name, it) } ?: emptyList()
                        toast(if (names.isEmpty()) "That is not a user-defined language file" else "Added ${names.joinToString()}")
                    }
                }
            }

            REQUEST_EXPORT_UDL -> data.data?.let { uri ->
                val file = udlToExport ?: return
                udlToExport = null
                io.execute {
                    val ok = runCatching { FileIo.write(this, uri, file.readBytes()) }.isSuccess
                    ui.post { if (!destroyed) toast(if (ok) "Definition saved" else "Cannot write the file") }
                }
            }

            REQUEST_PROJECT_FILES -> {
                val (index, node) = projectTarget ?: return
                projectTarget = null
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
                if (uris.isEmpty()) data.data?.let { uris += it }
                for (uri in uris) {
                    FileIo.persistAccess(this, uri)
                    node.add(ProjectNode(ProjectNode.Kind.FILE, FileIo.displayName(this, uri), uri.toString()))
                }
                saveProjects(index)
            }

            REQUEST_OPEN_WORKSPACE -> data.data?.let { uri ->
                val index = workspacePanel
                io.execute {
                    val text = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                    ui.post {
                        if (destroyed) return@post
                        val read = ProjectStore.read(text ?: "")
                        if (read.isEmpty()) toast("That is not a Notepad++ workspace file") else {
                            projectPanels[index] = read
                            saveProjects(index)
                        }
                    }
                }
            }

            REQUEST_SAVE_WORKSPACE -> data.data?.let { uri ->
                val xml = ProjectStore.write(projectPanel(workspacePanel))
                io.execute {
                    val ok = runCatching { FileIo.write(this, uri, xml.toByteArray(Charsets.UTF_8)) }.isSuccess
                    ui.post { if (!destroyed) toast(if (ok) "Workspace saved" else "Cannot write the workspace file") }
                }
            }

            REQUEST_WORKSPACE_FOLDER -> data.data?.let { tree ->
                try {
                    contentResolver.takePersistableUriPermission(
                        tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (e: Exception) {
                    // The folder stays usable until the app is closed.
                }
                addWorkspaceFolder(tree)
            }

            REQUEST_FIND_FOLDER -> data.data?.let { tree ->
                try {
                    contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // The folder stays usable until the app is closed.
                }
                prefs.edit().putString("findFolder", tree.toString()).apply()
                findFolderThen?.invoke()
                findFolderThen = null
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

    private fun showMenu(title: String, shortcuts: Boolean = false, build: MenuSpec.() -> Unit) {
        menuSheet?.dismiss()
        menuSheet = MenuSheet(themed, palette.dark).also { sheet ->
            sheet.onAction = { updateStatus() }
            if (shortcuts) sheet.hints = { path -> Shortcuts.label(shortcutTable.binding(path.joinToString(COMMAND_SEPARATOR))) }
            sheet.show(title, build)
        }
    }

    /** For the on-device tests: brings a row of the open menu on screen without swiping to it. */
    internal fun revealMenuRow(label: String): Boolean = menuSheet?.reveal(label) ?: false

    private fun MenuSpec.mainMenu() {
        sub("File") { fileMenu() }
        sub("Edit") { editMenu() }
        sub("Search") { searchMenu() }
        sub("View") { viewMenu() }
        sub("Encoding") { encodingMenu() }
        sub("Language") { languageMenu() }
        sub("Settings") { settingsMenu() }
        sub("Tools") { toolsMenu() }
        sub("Macro") { macroMenu() }
        sub("Run") { item("Run...") { runDialog() } }
        sub("Window") { windowMenu() }
        sub("?") { helpMenu() }
    }

    private fun showMainMenu(open: String? = null) {
        showMenu("Menu", shortcuts = true) { mainMenu() }
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
        sub("Open Containing Folder") {
            item("Explorer", enabled = doc.uri != null) { showInFileManager(doc.uri?.let { FolderTree.parentOf(it) }) }
            item("Folder as Workspace", enabled = doc.uri != null) { pickWorkspaceFolder(doc.uri) }
        }
        item("Open Folder as Workspace...") { pickWorkspaceFolder(null) }
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
            item("Close All but Pinned Documents") { closeMany(workspace.docs.filter { !it.pinned }) }
        }
        sub("Recent Files") {
            val recent = recentFiles()
            if (!cataloguing) {
                for ((uri, name) in recent) {
                    item(name) { openUris(listOf(uri)) }
                }
            }
            item("Restore Recent Closed File", enabled = recent.isNotEmpty()) { openUris(listOf(recent.first().first)) }
            item("Open All Recent Files", enabled = recent.isNotEmpty()) { openUris(recent.map { it.first }) }
            item("Empty Recent Files List", enabled = recent.isNotEmpty()) { prefs.edit().remove("recent").apply() }
        }
        item("Print...") { printDocument() }
        item("Print Now") { printDocument() }
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
        item(if (beginSelect == null) "Begin/End Select" else "Begin/End Select (end here)", id = "Begin/End Select") { beginEndSelect(column = false) }
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
        sub("Auto-Completion") {
            item("Function Completion") { completeNow(fromApi = true, fromWords = false) }
            item("Word Completion") { completeNow(fromApi = false, fromWords = true) }
            item("Function Parameters Hint") { if (!showCallHint()) toast("The caret is not inside a call of a known function") }
            item("Function Parameters Previous Hint", enabled = callSite != null) { stepCallHint(-1) }
            item("Function Parameters Next Hint", enabled = callSite != null) { stepCallHint(1) }
            item("Path Completion") { completePath() }
        }
        sub("Paste Special") {
            item("Copy Binary Content") { copyBinary(cut = false) }
            item("Cut Binary Content") { copyBinary(cut = true) }
            item("Paste Binary Content") {
                val bytes = binaryClip
                if (bytes == null) toast("Nothing was copied with Copy Binary Content") else {
                    editor.send(Sci.SCI_BEGINUNDOACTION)
                    editor.send(Sci.SCI_CLEAR)
                    editor.sendBytes(Sci.SCI_ADDTEXT, bytes.size.toLong(), bytes)
                    editor.send(Sci.SCI_ENDUNDOACTION)
                }
            }
            item("Paste HTML Content") {
                val html = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.htmlText
                if (html.isNullOrEmpty()) toast("The clipboard holds no HTML") else editor.typeText(html)
            }
        }
        sub("On Selection") {
            item("Open File") { openSelectedFile() }
            item("Open Containing Folder in Explorer") { showInFileManager(folderOfSelectedPath()) }
            item("Search on Internet") { searchOnInternet() }
            item("Redact Selection") { if (!EditOps.redactSelection(editor)) toast("Select some text first") }
            sub("Change Search Engine...") { searchEngineItems() }
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
        item("Find All in Current Document") { findAllInDocuments(all = false) }
        item("Find All in All Opened Documents") { findAllInDocuments(all = true) }
        item("Find in Files...") { findInFilesDialog() }
        item("Search Results Window", checked = results.isOpen) { if (results.isOpen) results.close() else results.show() }
        item("Next Search Result") { if (!results.step(forward = true)) toast("There are no search results") }
        item("Previous Search Result") { if (!results.step(forward = false)) toast("There are no search results") }
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
            val together = prefs.getBoolean("zoomSync", true)
            item("Synchronize Across Views", checked = together) {
                prefs.edit().putBoolean("zoomSync", !together).apply()
                zoomOtherView()
            }
        }
        sub("Move/Clone Current Document") {
            val stored = workspace.active.uri != null
            item("Move to Other View") { moveToOtherView() }
            item("Clone to Other View") { showOtherView(workspace.active) }
            item("Move to New Instance", enabled = stored) { openInNewWindow(move = true) }
            item("Open in New Instance", enabled = stored) { openInNewWindow(move = false) }
        }
        item("Focus on Another View", enabled = otherDoc != null) { swapViews() }
        item("Text Direction RTL", checked = editor.rightToLeft) { setTextDirection(rightToLeft = true) }
        item("Text Direction LTR", checked = !editor.rightToLeft) { setTextDirection(rightToLeft = false) }
        item("Synchronize Vertical Scrolling", checked = syncVertical) {
            syncVertical = !syncVertical
            prefs.edit().putBoolean("syncVertical", syncVertical).apply()
        }
        item("Synchronize Horizontal Scrolling", checked = syncHorizontal) {
            syncHorizontal = !syncHorizontal
            prefs.edit().putBoolean("syncHorizontal", syncHorizontal).apply()
        }
        item("Toggle Full Screen Mode", checked = fullScreen && !distractionFree) { setScreenMode(full = !fullScreen || distractionFree, bare = false) }
        item("Distraction Free Mode", checked = distractionFree) { setScreenMode(full = !distractionFree, bare = !distractionFree) }
        item("View Current File in Browser", enabled = workspace.active.uri != null) { viewInAnotherApp(workspace.active, "text/html") }
        item("Monitoring (tail -f)", checked = workspace.active.monitoring, enabled = workspace.active.uri != null) {
            setMonitoring(workspace.active, !workspace.active.monitoring)
        }
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
            item("Pin Tab", checked = workspace.active.pinned) { workspace.setPinned(workspace.active, !workspace.active.pinned) }
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
        item("Folder as Workspace") { showSidePanel(PANEL_FOLDERS) }
        sub("Project Panels") {
            for (index in 0 until PROJECT_PANELS) item("Project Panel ${index + 1}") { showProjectPanel(index) }
        }
        item("Document Map", checked = documentMap != null) { setDocumentMap(documentMap == null) }
        item("Document List") { showSidePanel(PANEL_DOCUMENTS) }
        item("Function List") { showSidePanel(PANEL_FUNCTIONS) }
        item("Summary...") { showSummary() }
    }

    private fun zoom(change: () -> Unit) {
        change()
        ThemeApplier.updateLineNumberWidth(editor, showLineNumbers)
        zoomOtherView()
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
        sub("User Defined Language") {
            item("Define your language...") { showUserLanguages() }
            item("Open User Defined Language folder...") { showUserLanguages() }
            item("Notepad++ User Defined Languages Collection") { openLink("https://github.com/notepad-plus-plus/userDefinedLanguages") }
        }
        // Notepad++'s "User-Defined" entry is the language being worked on in its definition dialog.
        val defining = workspace.languages.byName(prefs.getString("udlCurrent", null))?.takeIf { it.user != null }
        item("User-Defined", checked = defining != null && defining === current, id = "User-Defined") {
            if (defining != null) workspace.setLanguage(defining) else showUserLanguages()
        }
        if (!cataloguing) {
            for (language in workspace.languages.userLanguages) {
                item(language.display, checked = language === current) { workspace.setLanguage(language) }
            }
        }
    }

    // region User-defined languages

    private fun udlFolder() = java.io.File(filesDir, "userDefineLangs").apply { mkdirs() }

    // Which file each user-defined language comes from.
    private val udlFiles = HashMap<String, java.io.File>()
    private var udlToExport: java.io.File? = null

    /** Reads the definition files kept with the app. The first time, Notepad++'s own Markdown definitions are put there. */
    private fun loadUserLanguages(registry: LanguageRegistry) {
        val folder = udlFolder()
        if (!prefs.getBoolean("udlInstalled", false)) {
            for (name in assets.list("npp/userDefineLangs") ?: emptyArray()) {
                runCatching { assets.open("npp/userDefineLangs/$name").use { input -> java.io.File(folder, name).writeBytes(input.readBytes()) } }
            }
            prefs.edit().putBoolean("udlInstalled", true).apply()
        }
        udlFiles.clear()
        val all = ArrayList<UserLanguage>()
        for (file in (folder.listFiles() ?: emptyArray()).sortedBy { it.name }) {
            val found = runCatching { file.inputStream().use { UserLanguages.read(it) } }.getOrDefault(emptyList())
            for (language in found) {
                if (language.name in udlFiles) continue
                udlFiles[language.name] = file
                all += language
            }
        }
        registry.setUserLanguages(all)
    }

    private fun reloadUserLanguages() {
        loadUserLanguages(workspace.languages)
        workspace.userLanguagesChanged()
        menuSheet?.refresh()
    }

    /** Keeps a UDL file with the app and makes its languages available. Returns their names; none if it is not a UDL file. */
    internal fun importUserLanguages(fileName: String, content: ByteArray): List<String> {
        val names = UserLanguages.read(content.inputStream()).map { it.name }
        if (names.isEmpty()) return names
        val safe = fileName.substringAfterLast('/').removeSuffix(".xml").replace(Regex("[^A-Za-z0-9 ._()+-]"), "_").ifEmpty { "language" }
        java.io.File(udlFolder(), "$safe.xml").writeBytes(content)
        prefs.edit().putString("udlCurrent", names.first()).apply()
        reloadUserLanguages()
        return names
    }

    /**
     * Notepad++'s "Define your language" dialog and its folder of definitions in one list: the languages there
     * are, and for each what can be done with it. A definition is edited as the XML file it is.
     */
    private fun showUserLanguages() = showMenu("User Defined Languages") {
        item("Import...") { pickFile(REQUEST_IMPORT_UDL) }
        item("New Language...") {
            askText("New Language", "Language name", "My language") { name ->
                if (workspace.languages.byName(name) != null) toast("There is a language called $name already") else {
                    val file = java.io.File(udlFolder(), name.replace(Regex("[^A-Za-z0-9 ._()+-]"), "_") + ".xml")
                    file.writeText(UserLanguages.template(name, ""), Charsets.UTF_8)
                    prefs.edit().putString("udlCurrent", name).apply()
                    reloadUserLanguages()
                    menuSheet?.dismiss()
                    openUris(listOf(Uri.fromFile(file)))
                    toast("Fill in the lists and save: the language updates each time")
                }
            }
        }
        for (language in workspace.languages.userLanguages) {
            val file = udlFiles[language.name] ?: continue
            sub(language.display) {
                item("Use for This Document", checked = workspace.active.language === language) { workspace.setLanguage(language) }
                item("Edit Definition") {
                    prefs.edit().putString("udlCurrent", language.name).apply()
                    openUris(listOf(Uri.fromFile(file)))
                }
                item("Export...") {
                    udlToExport = file
                    createFile(REQUEST_EXPORT_UDL, file.name)
                }
                item("Remove") {
                    file.delete()
                    reloadUserLanguages()
                }
            }
        }
    }

    // endregion

    private fun MenuSpec.settingsMenu() {
        item("Preferences...") { showPreferences() }
        item("Style Configurator...") { showStyleConfigurator() }
        item("Shortcut Mapper...") { showShortcutMapper() }
        sub("Import") {
            item("Import Style Theme(s)...") { pickThemes() }
        }
        item("Selection Menu Items...") { selectionMenuDialog() }
        appearanceItems()
        keyboardItems()
        item("Keyboard log", checked = imeLogView.visibility == View.VISIBLE) {
            imeLogView.visibility = if (imeLogView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            showImeLog()
        }
    }

    private fun MenuSpec.appearanceItems() {
        // Notepad++'s dark mode is its DarkModeDefault theme; the other themes are under Theme.
        item("Dark mode", checked = palette.dark) { chooseTheme(if (palette.dark) "" else DARK_THEME) }
        sub("Theme") {
            item("Default (stylers.xml)", checked = themeName.isEmpty()) { chooseTheme("") }
            for (file in themeFiles()) {
                item(file.removeSuffix(".xml"), checked = file == themeName) { chooseTheme(file) }
            }
        }
        item("Show line numbers", checked = showLineNumbers) {
            showLineNumbers = !showLineNumbers
            prefs.edit().putBoolean("lineNumbers", showLineNumbers).apply()
            ThemeApplier.updateLineNumberWidth(editor, showLineNumbers)
        }
        item("Bigger text") { setFontSize(fontSize + 1) }
        item("Smaller text") { setFontSize(fontSize - 1) }
    }

    private fun MenuSpec.keyboardItems() {
        val textMode = editor.keyboardMode == ScintillaView.KeyboardMode.TEXT
        item("Keyboard suggestions and autocorrect", checked = textMode) {
            editor.keyboardMode = if (textMode) ScintillaView.KeyboardMode.CODE else ScintillaView.KeyboardMode.TEXT
            prefs.edit().putBoolean("textKeyboard", !textMode).apply()
        }
    }

    private fun MenuSpec.searchEngineItems() {
        val current = prefs.getString("searchEngine", "") ?: ""
        for ((name, template) in SEARCH_ENGINES) {
            item(name, checked = template == current) { prefs.edit().putString("searchEngine", template).apply() }
        }
    }

    // region Preferences

    private fun changeOptions(changed: EditorOptions) {
        options = changed
        prefs.edit()
            .putInt("tabSize", changed.tabSize).putBoolean("useSpaces", changed.useSpaces)
            .putBoolean("autoIndent", changed.autoIndent).putBoolean("currentLine", changed.highlightCurrentLine)
            .putInt("caret", changed.caret).putBoolean("caretBlinks", changed.caretBlinks)
            .putBoolean("scrollBeyond", changed.scrollBeyondLastLine).putInt("edgeColumn", changed.edgeColumn)
            .apply()
        // Styling again brings back what the theme decides, and the options are laid over that.
        workspace.restyle()
    }

    /** Notepad++'s Preferences dialog, as far as its pages have a meaning here, as one menu to walk through. */
    private fun showPreferences() = showMenu("Preferences") {
        sub("Editing") {
            sub("Tab Size") {
                for (size in listOf(2, 3, 4, 6, 8)) {
                    item(size.toString(), checked = options.tabSize == size) { changeOptions(options.copy(tabSize = size)) }
                }
            }
            item("Replace Tabs by Spaces", checked = options.useSpaces) { changeOptions(options.copy(useSpaces = !options.useSpaces)) }
            item("Auto-indent", checked = options.autoIndent) { changeOptions(options.copy(autoIndent = !options.autoIndent)) }
            item("Highlight Current Line", checked = options.highlightCurrentLine) {
                changeOptions(options.copy(highlightCurrentLine = !options.highlightCurrentLine))
            }
            sub("Caret") {
                for ((label, width) in listOf("Thin" to 1, "Normal" to 2, "Thick" to 3, "Block" to 4)) {
                    item(label, checked = options.caret == width) { changeOptions(options.copy(caret = width)) }
                }
                item("Blinking", checked = options.caretBlinks) { changeOptions(options.copy(caretBlinks = !options.caretBlinks)) }
            }
            item("Scroll Beyond Last Line", checked = options.scrollBeyondLastLine) {
                changeOptions(options.copy(scrollBeyondLastLine = !options.scrollBeyondLastLine))
            }
            sub("Vertical Edge") {
                item("None", checked = options.edgeColumn == 0) { changeOptions(options.copy(edgeColumn = 0)) }
                for (column in listOf(80, 100, 120)) {
                    item("Column $column", checked = options.edgeColumn == column) { changeOptions(options.copy(edgeColumn = column)) }
                }
            }
        }
        sub("New Document") {
            sub("Line Ending") {
                for (eol in Eol.entries) {
                    item(eol.longLabel, checked = workspace.newDocumentEol == eol) {
                        workspace.newDocumentEol = eol
                        prefs.edit().putInt("newEol", eol.sciMode).apply()
                    }
                }
            }
            sub("Encoding") {
                for (encoding in listOf(TextEncodings.UTF8, TextEncodings.UTF8_BOM, TextEncodings.UTF16_BE, TextEncodings.UTF16_LE)) {
                    item(encoding.label, checked = workspace.newDocumentEncoding == encoding) {
                        workspace.newDocumentEncoding = encoding
                        prefs.edit().putString("newEncoding", encoding.id).apply()
                    }
                }
            }
        }
        sub("Appearance") {
            appearanceItems()
            item("Style Configurator...") { showStyleConfigurator() }
        }
        sub("Keyboard") {
            keyboardItems()
            item("Shortcut Mapper...") { showShortcutMapper() }
            item("Selection Menu Items...") { selectionMenuDialog() }
        }
        sub("Auto-Completion") {
            val enabled = prefs.getBoolean("autoComplete", true)
            val from = prefs.getInt("autoCompleteFrom", 2)
            val source = prefs.getInt("autoCompleteSource", 0)
            item("Enable Auto-Completion on Each Input", checked = enabled) {
                prefs.edit().putBoolean("autoComplete", !enabled).apply()
                hideCompletion()
            }
            sub("From") {
                for (count in 1..4) {
                    val label = when (count) { 1 -> "1st Character"; 2 -> "2nd Character"; 3 -> "3rd Character"; else -> "4th Character" }
                    item(label, checked = from == count) { prefs.edit().putInt("autoCompleteFrom", count).apply() }
                }
            }
            sub("Offer") {
                for ((index, label) in listOf("Function and Word Completion", "Function Completion", "Word Completion").withIndex()) {
                    item(label, checked = source == index) { prefs.edit().putInt("autoCompleteSource", index).apply() }
                }
            }
            val hints = prefs.getBoolean("parameterHints", true)
            item("Function Parameters Hint on Input", checked = hints) { prefs.edit().putBoolean("parameterHints", !hints).apply() }
        }
        sub("Search") {
            sub("Search Engine") { searchEngineItems() }
        }
    }

    // endregion

    // region Style Configurator

    private fun showStyleConfigurator() = showMenu("Style Configurator") {
        sub("Font") {
            for ((label, family) in FONTS) {
                item(label, checked = fontName == family) {
                    fontName = family
                    prefs.edit().putString("fontName", family).apply()
                    workspace.restyle()
                }
            }
        }
        item("Bigger Text ($fontSize)", checked = false, id = "Bigger Text") { setFontSize(fontSize + 1) }
        item("Smaller Text", checked = false) { setFontSize(fontSize - 1) }
        sub("Global Styles") { styleItems("", baseTheme.globalStyles) }
        val language = workspace.active.language
        if (baseTheme.lexerStyles(language.name).isNotEmpty()) {
            sub(language.display) { styleItems(language.name, baseTheme.lexerStyles(language.name)) }
        }
        sub("All Languages") {
            val named = baseTheme.lexerNames.map { name -> (workspace.languages.byName(name)?.display ?: name) to name }
            for ((display, name) in named.sortedBy { it.first.lowercase() }) {
                sub(display) { styleItems(name, baseTheme.lexerStyles(name)) }
            }
        }
        item("Reset All Styles of This Theme", enabled = !styleOverrides.isEmpty, checked = false) {
            styleOverrides.clear()
            stylesChanged()
        }
    }

    private fun MenuSpec.styleItems(lexer: String, styles: List<WordStyle>) {
        for (style in styles.distinctBy { it.name }) {
            if (style.name.isEmpty()) continue
            // A tick marks the styles that were changed.
            item(style.name, checked = styleOverrides.get(lexer, style.name) != null) { styleDialog(lexer, style) }
        }
    }

    private fun stylesChanged() {
        prefs.edit().putString("styles:$themeName", styleOverrides.encode()).apply()
        showTheme(baseTheme.with(styleOverrides))
        menuSheet?.refresh()
    }

    /** One style of the theme: its two colours and whether it is bold, italic or underlined. */
    private fun styleDialog(lexer: String, base: WordStyle) {
        val theme = workspace.theme
        val now = (if (lexer.isEmpty()) theme.global(base.name) else theme.lexerStyles(lexer).firstOrNull { it.name == base.name }) ?: base
        val default = theme.global("Default Style")
        fun hex(colour: Int?) = colour?.let { "%06X".format(it and 0xFFFFFF) } ?: ""
        fun parse(text: String): Int? = text.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)

        val preview = TextView(themed).apply {
            contentDescription = "Style preview"
            text = "AaBbCc 0123 { }"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        }
        val bold = CheckBox(themed).apply { text = "Bold"; isChecked = now.bold }
        val italic = CheckBox(themed).apply { text = "Italic"; isChecked = now.italic }
        val underline = CheckBox(themed).apply { text = "Underline"; isChecked = now.underline }
        fun colourField(label: String, initial: Int?) = EditText(themed).apply {
            contentDescription = "$label colour"
            hint = "RRGGBB, empty for the theme's own"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(hex(initial))
        }
        val fore = colourField("Foreground", now.fg)
        val back = colourField("Background", now.bg)
        fun refresh() {
            preview.setTextColor((parse(fore.text.toString()) ?: base.fg ?: default?.fg ?: 0) or 0xFF000000.toInt())
            preview.setBackgroundColor((parse(back.text.toString()) ?: base.bg ?: default?.bg ?: 0xFFFFFF) or 0xFF000000.toInt())
            preview.setTypeface(Typeface.MONOSPACE, (if (bold.isChecked) Typeface.BOLD else 0) or (if (italic.isChecked) Typeface.ITALIC else 0))
            preview.paintFlags = if (underline.isChecked) preview.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            else preview.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
        }
        fun swatches(target: EditText, label: String) = HorizontalScrollView(themed).apply {
            addView(LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                for (colour in SWATCHES) {
                    addView(View(themed).apply {
                        contentDescription = "$label ${hex(colour)}"
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(colour or 0xFF000000.toInt())
                            setStroke(dp(1f), palette.chromeDimText)
                        }
                        setOnClickListener { target.setText(hex(colour)) }
                    }, LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { setMargins(0, dp(4f), dp(6f), dp(8f)) })
                }
            })
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(text: android.text.Editable?) = refresh()
        }
        fore.addTextChangedListener(watcher)
        back.addTextChangedListener(watcher)
        for (boxView in listOf(bold, italic, underline)) boxView.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()

        val container = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(themed).apply { text = "Foreground"; setPadding(0, dp(8f), 0, 0) })
            addView(fore, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(swatches(fore, "Foreground"))
            addView(TextView(themed).apply { text = "Background" })
            addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(swatches(back, "Background"))
            addView(LinearLayout(themed).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(bold)
                addView(italic)
                addView(underline)
            })
        }
        val scroll = ScrollView(themed).apply { addView(container) }
        val title = if (lexer.isEmpty()) base.name else "${workspace.languages.byName(lexer)?.display ?: lexer}: ${base.name}"
        AlertDialog.Builder(themed)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val fontStyle = (if (bold.isChecked) 1 else 0) or (if (italic.isChecked) 2 else 0) or (if (underline.isChecked) 4 else 0)
                val fg = parse(fore.text.toString())
                val bg = parse(back.text.toString())
                // Only what differs from the theme is kept, so a later theme update still shows through.
                styleOverrides.set(
                    lexer, base.name,
                    StyleChange(fg.takeIf { it != base.fg }, bg.takeIf { it != base.bg }, fontStyle.takeIf { it != base.fontStyle }),
                )
                stylesChanged()
            }
            .setNeutralButton("Reset") { _, _ ->
                styleOverrides.set(lexer, base.name, null)
                stylesChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    private fun importedThemes() = java.io.File(filesDir, "themes")

    private fun pickThemes() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_IMPORT_THEME)
        } catch (e: Exception) {
            toast("No file picker is available")
        }
    }

    /** Settings > Import > Import Style Theme(s): keeps a Notepad++ theme file with the app. Returns its name, or null if it is not one. */
    internal fun importTheme(fileName: String, content: ByteArray): String? {
        val theme = runCatching { NppTheme.load(content.inputStream()) }.getOrNull() ?: return null
        if (theme.global("Default Style") == null) return null
        val name = fileName.substringAfterLast('/').removeSuffix(".xml").replace(Regex("[^A-Za-z0-9 ._()+-]"), "_").ifEmpty { "Imported" } + ".xml"
        importedThemes().mkdirs()
        java.io.File(importedThemes(), name).writeBytes(content)
        return name
    }

    private fun themeFiles(): List<String> =
        ((assets.list("npp/themes") ?: emptyArray()).toList() + (importedThemes().list() ?: emptyArray()).toList())
            .filter { it.endsWith(".xml") }.distinct().sortedBy { it.lowercase() }

    /** Reads a Notepad++ theme shipped with the app; an empty or unreadable name gives the default one. */
    private fun loadTheme(name: String): NppTheme {
        if (name.isNotEmpty()) {
            try {
                val imported = java.io.File(importedThemes(), name)
                if (imported.isFile) return imported.inputStream().use { NppTheme.load(it) }
                return assets.open("npp/themes/$name").use { NppTheme.load(it) }
            } catch (e: Exception) {
                // Fall through to the default theme.
            }
        }
        return assets.open("npp/stylers.model.xml").use { NppTheme.load(it) }
    }

    private fun chooseTheme(name: String) {
        if (name == themeName) return
        baseTheme = loadTheme(name)
        themeName = name
        prefs.edit().putString("theme", name).apply()
        styleOverrides = StyleOverrides.decode(prefs.getString("styles:$name", "") ?: "")
        showTheme(baseTheme.with(styleOverrides))
    }

    /** Puts a theme on screen: the editor's styles, and the bars around it when it goes between light and dark. */
    private fun showTheme(theme: NppTheme) {
        if (theme.isDark != palette.dark) {
            // Only the bars around the editor are rebuilt in the other colours. The editor view stays, so the
            // open documents keep their undo history, and a menu that is open stays open.
            palette = Palette(theme.isDark)
            buildScreen(theme.background)
            menuSheet?.dark = palette.dark
            refreshTabs()
            editor.requestFocus()
        }
        workspace.languages.darkTheme = theme.isDark
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
        item("Pin Tab", checked = doc.pinned) { workspace.setPinned(doc, !doc.pinned) }
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
        if (!cataloguing) {
            for (doc in workspace.docs) {
                item(doc.name, checked = doc === workspace.active) { workspace.activate(doc) }
            }
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

    // region Macros

    private fun MenuSpec.macroMenu() {
        val saved = savedMacros()
        item("Start Recording", enabled = !recording) { startRecording() }
        item("Stop Recording", enabled = recording) { stopRecording() }
        item("Playback", enabled = !recording && currentMacro.isNotEmpty()) { Macros.play(editor, currentMacro.toList()) }
        item("Save Current Recorded Macro...", enabled = !recording && currentMacro.isNotEmpty()) { saveMacro() }
        item("Run a Macro Multiple Times...", enabled = !recording && (currentMacro.isNotEmpty() || saved.isNotEmpty())) { runMacroDialog() }
        for ((name, steps) in saved) item(name, enabled = !recording) { Macros.play(editor, steps) }
        if (saved.isNotEmpty() && !cataloguing) {
            sub("Delete Macro") {
                for ((name, _) in saved) item(name) { storeMacros(savedMacros().filter { it.first != name }) }
            }
        }
    }

    private fun startRecording() {
        currentMacro.clear()
        recording = true
        // Typing has to arrive as plain keys to be recorded, which is how the keyboard works with suggestions off.
        keyboardBeforeRecording = editor.keyboardMode
        editor.keyboardMode = ScintillaView.KeyboardMode.CODE
        editor.send(Sci.SCI_STARTRECORD)
        updateStatus()
        toast("Recording. Choose Macro > Stop Recording when done.")
    }

    private fun stopRecording() {
        editor.send(Sci.SCI_STOPRECORD)
        recording = false
        keyboardBeforeRecording?.let { editor.keyboardMode = it }
        keyboardBeforeRecording = null
        updateStatus()
    }

    /** Saved macros as (name, steps), kept in the settings one per line. */
    private fun savedMacros(): List<Pair<String, List<MacroStep>>> =
        (prefs.getString("macros", "") ?: "").split('\n').filter { '\t' in it }
            .map { it.substringBefore('\t') to Macros.decode(it.substringAfter('\t')) }

    private fun storeMacros(macros: List<Pair<String, List<MacroStep>>>) {
        prefs.edit().putString("macros", macros.joinToString("\n") { "${it.first}\t${Macros.encode(it.second)}" }).apply()
    }

    private fun saveMacro() {
        val input = EditText(themed).apply {
            contentDescription = "Macro name"
            hint = "Name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
        }
        val container = LinearLayout(themed).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(themed)
            .setTitle("Save Current Recorded Macro")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().replace('\t', ' ').replace('\n', ' ')
                if (name.isEmpty()) return@setPositiveButton toast("The macro needs a name")
                storeMacros(savedMacros().filter { it.first != name } + (name to currentMacro.toList()))
                toast("Macro \"$name\" saved")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runMacroDialog() {
        val choices = ArrayList<Pair<String, List<MacroStep>>>()
        if (currentMacro.isNotEmpty()) choices += "Current recorded macro" to currentMacro.toList()
        choices += savedMacros()
        val which = Spinner(themed).apply {
            contentDescription = "Macro to run"
            adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_dropdown_item, choices.map { it.first })
        }
        val times = RadioButton(themed).apply { text = "Run this many times:" }
        val toEnd = RadioButton(themed).apply { text = "Run until the end of file" }
        val count = EditText(themed).apply {
            contentDescription = "Times to run"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            minEms = 4
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(which, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(RadioGroup(themed).apply {
                addView(times)
                addView(toEnd)
            })
            addView(count)
        }
        times.isChecked = true
        AlertDialog.Builder(themed)
            .setTitle("Run a Macro Multiple Times")
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton("Run") { _, _ ->
                val steps = choices.getOrNull(which.selectedItemPosition)?.second ?: return@setPositiveButton
                if (toEnd.isChecked) Macros.playToEnd(editor, steps) else Macros.playTimes(editor, steps, count.text.toString().toIntOrNull() ?: 1)
                editor.requestFocus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region Find All and Find in Files

    private fun searcher(): ScintillaView = searchEditor ?: ScintillaView(this).also { searchEditor = it }

    private fun hitsLabel(hits: Int) = if (hits == 1) "1 hit" else "$hits hits"

    /** The results need the room the keyboard takes, and reading them needs no keyboard. */
    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(editor.windowToken, 0)
    }

    /** Find All in Current Document / in All Opened Documents, with the Find bar's text and switches. */
    private fun findAllInDocuments(all: Boolean) {
        val text = findBar.searchText
        if (text.isEmpty()) {
            findBar.open(withReplace = false)
            return toast("Type what to find first")
        }
        val options = findBar.searchOptions
        val docs = if (all) workspace.docs.toList() else listOf(workspace.active)
        val engine = searcher()
        searchRun++
        hideKeyboard()
        results.begin("Search \"$text\"")
        var hits = 0
        var withHits = 0
        for (doc in docs) {
            // A Scintilla document can be attached to a second view, which leaves the one on screen alone.
            engine.send(Sci.SCI_SETDOCPOINTER, 0, doc.sciDoc)
            val found = Searcher.findAll(engine, text, options)
            if (found.isNotEmpty()) {
                results.add(ResultsPanel.Group(doc, doc.name, found))
                hits += found.size
                withHits++
            }
        }
        engine.send(Sci.SCI_SETDOCPOINTER, 0, 0)
        results.setHeading("Search \"$text\" (${hitsLabel(hits)} in $withHits of ${docs.size} ${if (docs.size == 1) "document" else "documents"})")
    }

    private fun goToHit(group: ResultsPanel.Group, hit: Hit) {
        fun select() {
            editor.send(Sci.SCI_ENSUREVISIBLEENFORCEPOLICY, hit.line)
            val length = editor.send(Sci.SCI_GETLENGTH)
            editor.send(Sci.SCI_SETSEL, hit.start.coerceAtMost(length), hit.end.coerceAtMost(length))
            editor.requestFocus()
        }
        when (val key = group.key) {
            is Doc -> if (key in workspace.docs) {
                workspace.activate(key)
                select()
            } else {
                toast("${group.name} has been closed")
            }
            is Uri -> openUris(listOf(key)) { select() }
        }
    }

    private var findFolderThen: (() -> Unit)? = null

    private fun chooseFindFolder(then: () -> Unit) {
        findFolderThen = then
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FIND_FOLDER)
        } catch (e: Exception) {
            toast("No folder picker is available on this device")
        }
    }

    /** Search > Find in Files: what to find, which files, and in which folder. */
    private fun findInFilesDialog(keepText: String? = null) {
        val folder = prefs.getString("findFolder", null)?.let { Uri.parse(it) }
        if (folder == null) return chooseFindFolder { findInFilesDialog(keepText) }
        fun field(name: String, value: String) = EditText(themed).apply {
            contentDescription = name
            hint = name
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
        }
        fun check(label: String, on: Boolean) = android.widget.CheckBox(themed).apply {
            text = label
            isChecked = on
        }
        val selection = EditOps.selectionOrWord(editor).takeIf { '\n' !in it && it.length <= 200 } ?: ""
        val what = field("Find what", keepText ?: findBar.searchText.ifEmpty { selection })
        val filters = field("Filters", prefs.getString("findFilters", "*.*") ?: "*.*")
        val folderName = TextView(themed).apply {
            text = "Folder: ${android.provider.DocumentsContract.getTreeDocumentId(folder).substringAfterLast(':').ifEmpty { "storage" }}"
            setPadding(0, dp(10f), 0, dp(4f))
        }
        val matchCase = check("Match case", findBar.matchCaseOn)
        val wholeWord = check("Match whole word only", findBar.wholeWordOn)
        val regex = check("Regular expression", false)
        val subFolders = check("In all sub-folders", true)
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            for (view in listOf(what, filters, folderName, matchCase, wholeWord, regex, subFolders)) {
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        AlertDialog.Builder(themed)
            .setTitle("Find in Files")
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton("Find All") { _, _ ->
                val text = what.text.toString()
                if (text.isEmpty()) return@setPositiveButton toast("Type what to find first")
                prefs.edit().putString("findFilters", filters.text.toString()).apply()
                val options = SearchOptions(matchCase.isChecked, wholeWord.isChecked, regex.isChecked, wrapAround = false)
                val pattern = filters.text.toString()
                val deep = subFolders.isChecked
                results.begin("Listing files...")
                val run = ++searchRun
                io.execute {
                    val files = runCatching { listTree(folder, deep, pattern, run) }.getOrElse { emptyList() }
                    ui.post { if (!destroyed && run == searchRun) findInFiles(files, text, options) }
                }
            }
            .setNeutralButton("Change folder...") { _, _ ->
                val typed = what.text.toString()
                chooseFindFolder { findInFilesDialog(typed) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The files under a folder picked with the system's folder picker, as (address, name) pairs. Runs off the main thread. */
    private fun listTree(tree: Uri, deep: Boolean, filters: String, run: Int): List<Pair<Uri, String>> {
        val contract = android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
        val found = ArrayList<Pair<Uri, String>>()
        val pending = ArrayDeque<String>()
        pending += android.provider.DocumentsContract.getTreeDocumentId(tree)
        while (pending.isNotEmpty() && found.size < MAX_FIND_FILES && run == searchRun) {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            val columns = arrayOf(contract, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_SIZE)
            contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (deep) pending += id
                    } else if (cursor.getLong(3) <= MAX_FIND_FILE_BYTES && Searcher.matchesFilters(name, filters)) {
                        found += android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                    }
                }
            }
        }
        return found
    }

    /**
     * Searches files one after another: each is read in the background and searched in the unseen editor,
     * and the panel fills as they come in. Closing the panel or starting another search stops it.
     */
    internal fun findInFiles(files: List<Pair<Uri, String>>, text: String, options: SearchOptions) {
        val run = ++searchRun
        val engine = searcher()
        hideKeyboard()
        results.begin("Search \"$text\" (0 of ${files.size} files)")
        var hits = 0
        var withHits = 0
        io.execute {
            for ((index, file) in files.withIndex()) {
                if (run != searchRun || destroyed) return@execute
                val (uri, name) = file
                // Files that cannot be read, are too big or are not text are passed over.
                val loaded = runCatching { FileIo.read(this, uri) }.getOrNull()
                val utf8 = loaded?.utf8?.takeIf { bytes -> bytes.size <= MAX_FIND_FILE_BYTES && (0 until minOf(bytes.size, 4096)).none { bytes[it].toInt() == 0 } }
                val done = java.util.concurrent.CountDownLatch(1)
                ui.post {
                    try {
                        if (run == searchRun && !destroyed) {
                            if (utf8 != null) {
                                engine.send(Sci.SCI_SETUNDOCOLLECTION, 0)
                                engine.send(Sci.SCI_CLEARALL)
                                engine.sendBytes(Sci.SCI_ADDTEXT, utf8.size.toLong(), utf8)
                                val found = Searcher.findAll(engine, text, options)
                                if (found.isNotEmpty()) {
                                    results.add(ResultsPanel.Group(uri, name, found))
                                    hits += found.size
                                    withHits++
                                }
                            }
                            val state = if (index == files.size - 1) "${files.size} ${if (files.size == 1) "file" else "files"}" else "${index + 1} of ${files.size} files so far"
                            results.setHeading("Search \"$text\" (${hitsLabel(hits)} in $withHits of $state)")
                        }
                    } finally {
                        done.countDown()
                    }
                }
                done.await()
            }
            ui.post {
                if (run == searchRun && !destroyed) {
                    engine.send(Sci.SCI_CLEARALL)
                    if (files.isEmpty()) results.setHeading("Search \"$text\" (no files to search)")
                }
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

    // Copy Binary Content keeps the exact bytes, NUL characters included, which Android's clipboard would cut short.
    private var binaryClip: ByteArray? = null

    private fun copyBinary(cut: Boolean) {
        val start = editor.send(Sci.SCI_GETSELECTIONSTART)
        val end = editor.send(Sci.SCI_GETSELECTIONEND)
        if (end <= start) return toast("Select some text first")
        val bytes = editor.range(start, end)
        binaryClip = bytes
        editor.setClipboard(String(bytes, Charsets.UTF_8))
        if (cut) editor.send(Sci.SCI_CLEAR)
    }

    /** View > Monitoring (tail -f): the file is checked every two seconds and shown again, at its end, when it has changed. */
    private fun setMonitoring(doc: Doc, on: Boolean) {
        val uri = doc.uri ?: return
        doc.monitoring = on
        if (doc === workspace.active) editor.send(Sci.SCI_SETREADONLY, if (on) 1 else 0)
        if (on) {
            doc.monitorStamp = FileIo.stamp(this, uri)
            ui.removeCallbacks(monitor)
            ui.postDelayed(monitor, MONITOR_MS)
        }
        updateStatus()
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (workspace.docs.none { it.monitoring }) return
            ui.postDelayed(this, MONITOR_MS)
            val doc = workspace.active
            val uri = doc.uri
            if (!doc.monitoring || uri == null) return
            io.execute {
                val stamp = FileIo.stamp(this@MainActivity, uri)
                if (stamp == doc.monitorStamp) return@execute
                val file = runCatching { FileIo.read(this@MainActivity, uri, doc.encoding) }.getOrNull() ?: return@execute
                ui.post {
                    if (destroyed || doc !== workspace.active || !doc.monitoring) return@post
                    doc.monitorStamp = stamp
                    editor.send(Sci.SCI_SETREADONLY, 0)
                    workspace.reloaded(file)
                    editor.send(Sci.SCI_SETREADONLY, 1)
                    editor.send(Sci.SCI_DOCUMENTEND)
                }
            }
        }
    }

    /** Notepad++'s Run dialog, which starts programs. Here it opens an address or link in whichever app handles it. */
    private fun runDialog() {
        val doc = workspace.active
        val input = EditText(themed).apply {
            contentDescription = "Address to open"
            hint = "https://... or another app's link"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("runCommand", "") ?: "")
        }
        val help = TextView(themed).apply {
            text = "\$(CURRENT_WORD), \$(FILE_NAME) and \$(FULL_CURRENT_PATH) are replaced, for example\nhttps://en.wikipedia.org/wiki/Special:Search?search=\$(CURRENT_WORD)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8f), 0, 0)
        }
        val container = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(help)
        }
        AlertDialog.Builder(themed)
            .setTitle("Run")
            .setView(container)
            .setPositiveButton("Run") { _, _ ->
                val command = input.text.toString().trim()
                if (command.isEmpty()) return@setPositiveButton
                prefs.edit().putString("runCommand", command).apply()
                openLink(RunCommand.expand(command, EditOps.selectionOrWord(editor), doc.name, doc.uri?.toString() ?: ""))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Kept until the system has taken the page over for printing.
    private var printView: android.webkit.WebView? = null

    /** File > Print: hands the text to Android's print service, which also offers Save as PDF. */
    private fun printDocument() {
        val doc = workspace.active
        if (editor.send(Sci.SCI_GETLENGTH) > MAX_PRINT_BYTES) return toast("This document is too large to print from here")
        val escaped = android.text.TextUtils.htmlEncode(editor.text)
        val page = "<html><body><pre style=\"font-family:monospace;font-size:10pt;white-space:pre-wrap;word-wrap:break-word\">$escaped</pre></body></html>"
        val web = android.webkit.WebView(this)
        web.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                try {
                    (getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager).print(
                        "Pocketpad++ - ${doc.name}", view.createPrintDocumentAdapter(doc.name), android.print.PrintAttributes.Builder().build(),
                    )
                } catch (e: Exception) {
                    toast("Printing is not available on this device")
                }
                printView = null
            }
        }
        printView = web
        web.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null)
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
    // region Side panel: Document List and Folder as Workspace

    private var sidePanel: SidePanel? = null
    private val expandedFolders = HashSet<String>()
    private val folderContents = HashMap<String, List<FolderNode>>()
    private val foldersLoading = HashSet<String>()
    private val folderRoots = HashMap<String, FolderNode>()

    internal fun showSidePanel(page: String) {
        sidePanel?.dismiss()
        // Read again each time the panel opens: the text may have changed since.
        functionListing = null
        sidePanel = SidePanel(themed, palette.dark).also { panel ->
            val pages = arrayListOf(
                SidePanel.Page(PANEL_DOCUMENTS) { documentRows() },
                SidePanel.Page(PANEL_FOLDERS) { folderRows() },
                SidePanel.Page(PANEL_FUNCTIONS) { functionRows() },
            )
            // A Project Panel has its tab once it was asked for or holds something.
            for (index in 0 until PROJECT_PANELS) {
                if (index in openedProjectPanels || projectPanel(index).isNotEmpty()) {
                    pages += SidePanel.Page(projectPanelTitle(index)) { projectRows(index) }
                }
            }
            panel.show(pages, page)
        }
    }

    /** Notepad++'s Document List: every open document, the one on screen in bold. A long press gives the tab's menu. */
    private fun PanelSpec.documentRows() {
        for (doc in workspace.docs.toList()) {
            val mark = when {
                workspace.isDirty(doc) -> "\u25CF"
                doc.pinned -> "\uD83D\uDCCC"
                else -> ""
            }
            row(doc.name, mark = mark, strong = doc === workspace.active, onLongClick = {
                sidePanel?.dismiss()
                showTabMenu(doc)
            }) {
                sidePanel?.dismiss()
                workspace.activate(doc)
            }
        }
    }

    // region Auto-completion

    // A phone has no room for a list floating at the caret, and the keyboard would cover it. The words on offer
    // sit in a row above the extra keys instead, like the suggestions of a keyboard, and so does the hint
    // for the parameters of the function being called.
    private lateinit var completionStrip: LinearLayout
    private lateinit var completionScroller: HorizontalScrollView
    private lateinit var callHintView: TextView
    private val apiIndexes = HashMap<String, ApiIndex?>()
    private var completionStart = -1L
    private var completionCaret = -1L
    private var completionKind = 0
    internal var completionWords: List<String> = emptyList()
    private var callSite: CallSite? = null
    private var callOverload = 0
    private var callLine = -1L

    private fun completionBar(): View {
        completionStrip = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        completionScroller = HorizontalScrollView(themed).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(palette.chrome)
            visibility = View.GONE
            addView(completionStrip)
        }
        callHintView = TextView(themed).apply {
            contentDescription = "Function parameters hint"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.chromeText)
            setBackgroundColor(palette.chrome)
            typeface = Typeface.MONOSPACE
            maxLines = 4
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            visibility = View.GONE
            // A tap shows the next way of calling the function.
            setOnClickListener { stepCallHint(1) }
        }
        return LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(callHintView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(completionScroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)))
        }.also {
            // The screen is built again when the theme goes between light and dark.
            completionWords = emptyList()
            callSite = null
        }
    }

    /** Notepad++'s auto-completion file for the language of the document on screen, if it has one. */
    private fun apiIndex(): ApiIndex? {
        val name = workspace.active.language.name
        return apiIndexes.getOrPut(name) {
            val file = API_FILES[name] ?: name
            try {
                assets.open("npp/autoCompletion/$file.xml").use { ApiIndex.load(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun documentText(): String =
        if (editor.send(Sci.SCI_GETLENGTH) <= MAX_COMPLETION_BYTES) editor.text else ""

    private fun showCompletion(words: List<String>, start: Long, kind: Int) {
        completionWords = words
        completionStart = start
        completionKind = kind
        completionCaret = editor.send(Sci.SCI_GETCURRENTPOS)
        completionStrip.removeAllViews()
        for (word in words) {
            completionStrip.addView(TextView(themed).apply {
                text = word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.chromeText)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(dp(12f), 0, dp(12f), 0)
                setOnClickListener {
                    Completion.accept(editor, completionStart, word)
                    hideCompletion()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        completionScroller.scrollTo(0, 0)
        completionScroller.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun hideCompletion() {
        if (completionWords.isNotEmpty()) showCompletion(emptyList(), -1, 0)
    }

    /** What to offer for the word before the caret. Kind 0 is what the settings say, 1 functions, 2 words. */
    private fun wordCandidates(kind: Int): Pair<Long, List<String>> {
        val api = apiIndex()
        val (start, prefix) = Completion.prefix(editor, api?.extraWordChars ?: "")
        val source = if (kind == 0) prefs.getInt("autoCompleteSource", 0) else kind
        val words = Completion.candidates(documentText(), prefix, api, fromApi = source != 2, fromWords = source != 1)
        return start to (if (prefix.isEmpty()) emptyList() else words)
    }

    private fun completeWhileTyping(ch: Int) {
        if (recording) return
        val api = apiIndex()
        if (prefs.getBoolean("parameterHints", true) && api != null) {
            if (ch == api.startFunction.code || ch == api.separator.code) showCallHint()
            else if (ch == api.stopFunction.code) showCallHint() || hideCallHint()
        }
        if (!prefs.getBoolean("autoComplete", true)) return
        val (start, words) = wordCandidates(0)
        val typed = editor.send(Sci.SCI_GETCURRENTPOS) - start
        if (typed >= prefs.getInt("autoCompleteFrom", 2)) showCompletion(words, start, 0) else hideCompletion()
    }

    /** After the caret moved: the list follows a word that is still being typed or shortened, and goes otherwise. */
    private fun completionFollowsCaret() {
        val caret = editor.send(Sci.SCI_GETCURRENTPOS)
        if (completionWords.isNotEmpty() && caret != completionCaret) {
            val (start, words) = if (completionKind == 3) -1L to emptyList() else wordCandidates(completionKind)
            if (start == completionStart && caret > start) showCompletion(words, start, completionKind) else hideCompletion()
        }
        if (callSite != null && editor.send(Sci.SCI_LINEFROMPOSITION, caret) != callLine) hideCallHint()
    }

    /** Edit > Auto-Completion > Function Completion and Word Completion. */
    private fun completeNow(fromApi: Boolean, fromWords: Boolean) {
        val kind = if (fromApi) 1 else 2
        val (start, words) = wordCandidates(kind)
        when {
            start == editor.send(Sci.SCI_GETCURRENTPOS) -> toast("Type the start of a word first")
            words.isEmpty() -> toast(if (fromApi && apiIndex() == null) "Notepad++ has no function list for ${workspace.active.language.display}" else "Nothing to complete this with")
            words.size == 1 && fromWords -> Completion.accept(editor, start, words[0])
            else -> showCompletion(words, start, kind)
        }
    }

    /** Edit > Auto-Completion > Path Completion: the names in the folder whose path is being typed. */
    private fun completePath() {
        val caret = editor.send(Sci.SCI_GETCURRENTPOS)
        val lineStart = editor.send(Sci.SCI_POSITIONFROMLINE, editor.send(Sci.SCI_LINEFROMPOSITION, caret))
        val before = String(editor.range(lineStart, caret), Charsets.UTF_8)
        val (typed, names) = Completion.paths(before) { folder -> java.io.File(folder).list()?.toList() }
        if (names.isEmpty()) return toast("No folder this app can read matches the path before the caret")
        showCompletion(names, caret - before.takeLast(typed).toByteArray(Charsets.UTF_8).size, 3)
    }

    /** Shows how to call the function the caret is in. Returns false when it is in none that is known. */
    private fun showCallHint(): Boolean {
        val api = apiIndex() ?: return false
        val caret = editor.send(Sci.SCI_GETCURRENTPOS)
        val line = editor.send(Sci.SCI_LINEFROMPOSITION, caret)
        val before = String(editor.range(editor.send(Sci.SCI_POSITIONFROMLINE, line), caret), Charsets.UTF_8)
        val site = Completion.callSite(before, api) ?: return false
        if (site.word !== callSite?.word) callOverload = 0
        callSite = site
        callLine = line
        drawCallHint()
        return true
    }

    private fun hideCallHint(): Boolean {
        callSite = null
        callHintView.visibility = View.GONE
        return true
    }

    private fun stepCallHint(step: Int) {
        val site = callSite ?: return
        val count = site.word.overloads.size
        callOverload = (callOverload + step + count) % count
        drawCallHint()
    }

    private fun drawCallHint() {
        val site = callSite ?: return
        val api = apiIndex() ?: return
        val (text, from, to) = Completion.hint(site, callOverload, api)
        val overload = site.word.overloads[callOverload]
        val shown = android.text.SpannableStringBuilder(text)
        if (from >= 0) {
            shown.setSpan(android.text.style.StyleSpan(Typeface.BOLD), from, to, 0)
            shown.setSpan(android.text.style.ForegroundColorSpan(palette.accent), from, to, 0)
        }
        if (site.word.overloads.size > 1) shown.append("   \u25B2 ${callOverload + 1} of ${site.word.overloads.size} \u25BC")
        if (overload.description.isNotEmpty()) shown.append("\n").append(overload.description.trim())
        callHintView.text = shown
        callHintView.visibility = View.VISIBLE
    }

    internal val callHint: String get() = if (callHintView.visibility == View.VISIBLE) callHintView.text.toString() else ""

    // endregion

    // region Split screen: the second view

    // Notepad++ can show two documents, or one document twice, side by side. Here the second view is for
    // reading: it scrolls, selects and copies, while typing and every command go to the editor proper.
    // Swapping the two (Focus on Another View) is how the other document comes under the keyboard.
    internal var otherView: ScintillaView? = null
    internal var otherDoc: Doc? = null
    private var otherPane: LinearLayout? = null
    private var otherTitle: TextView? = null
    private var syncVertical = false
    private var syncHorizontal = false
    private val mainScroll = longArrayOf(0, 0)
    private val otherScroll = longArrayOf(0, 0)

    /** View > Text Direction: lays the text out from the right, for languages written that way, or from the left. */
    internal fun setTextDirection(rightToLeft: Boolean) {
        prefs.edit().putBoolean("rightToLeft", rightToLeft).apply()
        editor.rightToLeft = rightToLeft
        otherView?.rightToLeft = rightToLeft
    }

    /** Side by side on a wide screen, one above the other on a tall one. */
    private fun layoutSplit() {
        val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        splitArea.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (index in 0 until splitArea.childCount) {
            splitArea.getChildAt(index).layoutParams =
                if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        splitArea.requestLayout()
    }

    private fun addOtherPane() {
        val view = otherView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        val title = TextView(themed).apply {
            contentDescription = "Other view"
            text = otherDoc?.name ?: ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(palette.chromeText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(10f), 0, dp(6f), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        otherTitle = title
        val header = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.chrome)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(barButton("\u21C5", "Focus on Another View") { swapViews() }, LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(barButton("\u2715", "Close other view") { closeOtherView() }, LinearLayout.LayoutParams(dp(44f), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val pane = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(themed).apply { setBackgroundColor(palette.accent) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2f)))
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        otherPane = pane
        splitArea.addView(pane)
        layoutSplit()
    }

    /** Shows a document in the second view, opening that view if need be. */
    internal fun showOtherView(doc: Doc) {
        val view = otherView ?: ScintillaView(this).apply {
            viewOnly = true
            onStateChanged = { otherView?.let { followScroll(it, editor, otherScroll, mainScroll) } }
        }.also { otherView = it }
        otherDoc = doc
        view.send(Sci.SCI_SETDOCPOINTER, 0, doc.sciDoc)
        view.documentSwitched()
        if (otherPane == null) addOtherPane() else otherTitle?.text = doc.name
        styleOtherView()
        rememberScroll(view, otherScroll)
        rememberScroll(editor, mainScroll)
    }

    internal fun closeOtherView() {
        val view = otherView ?: return
        otherPane?.let { splitArea.removeView(it) }
        otherPane = null
        otherTitle = null
        otherDoc = null
        otherView = null
        view.destroy()
        layoutSplit()
        editor.requestFocus()
    }

    /** The second view takes the looks of the first: theme, font, options, wrapping and, if asked, zoom. */
    private fun styleOtherView() {
        val view = otherView ?: return
        val doc = otherDoc ?: return
        workspace.styleDoc(
            doc, view,
            ViewMetrics(
                fontName = fontName, fontSize = fontSize, foldMarginWidth = dp(22f), leftPadding = dp(4f),
                showLineNumbers = showLineNumbers, pixelScale = resources.displayMetrics.density,
            ),
        )
        options.apply(view, resources.displayMetrics.density)
        view.send(Sci.SCI_SETWRAPMODE, editor.send(Sci.SCI_GETWRAPMODE))
        view.rightToLeft = editor.rightToLeft
        zoomOtherView()
    }

    private fun zoomOtherView() {
        val view = otherView ?: return
        if (prefs.getBoolean("zoomSync", true)) view.send(Sci.SCI_SETZOOM, editor.send(Sci.SCI_GETZOOM))
        ThemeApplier.updateLineNumberWidth(view, showLineNumbers)
    }

    /** View > Move to Other View: the document goes below, and the editor moves on to the tab next to it. */
    private fun moveToOtherView() {
        val doc = workspace.active
        val docs = workspace.docs
        val index = docs.indexOf(doc)
        if (docs.size > 1) workspace.activate(docs[if (index == docs.size - 1) index - 1 else index + 1])
        showOtherView(doc)
    }

    private class ViewPlace(val caret: Long, val anchor: Long, val line: Long, val x: Long)

    private fun place(view: ScintillaView) = ViewPlace(
        view.send(Sci.SCI_GETCURRENTPOS), view.send(Sci.SCI_GETANCHOR),
        view.send(Sci.SCI_DOCLINEFROMVISIBLE, view.send(Sci.SCI_GETFIRSTVISIBLELINE)), view.send(Sci.SCI_GETXOFFSET),
    )

    private fun goTo(view: ScintillaView, place: ViewPlace) {
        view.send(Sci.SCI_SETANCHOR, place.anchor)
        view.send(Sci.SCI_SETCURRENTPOS, place.caret)
        view.send(Sci.SCI_SETFIRSTVISIBLELINE, view.send(Sci.SCI_VISIBLEFROMDOCLINE, place.line))
        view.send(Sci.SCI_SETXOFFSET, place.x)
    }

    /** View > Focus on Another View: the two views trade documents and places, so the other one can be edited. */
    internal fun swapViews() {
        val view = otherView ?: return
        val below = otherDoc ?: return
        val above = workspace.active
        val placeBelow = place(view)
        val placeAbove = place(editor)
        if (below !== above) {
            workspace.activate(below)
            showOtherView(above)
        }
        goTo(editor, placeBelow)
        goTo(view, placeAbove)
        rememberScroll(editor, mainScroll)
        rememberScroll(view, otherScroll)
        editor.requestFocus()
        updateStatus()
    }

    private fun rememberScroll(view: ScintillaView, at: LongArray) {
        at[0] = view.send(Sci.SCI_GETFIRSTVISIBLELINE)
        at[1] = view.send(Sci.SCI_GETXOFFSET)
    }

    /** Synchronized scrolling: what one view moved by, the other moves by too. */
    private fun followScroll(moved: ScintillaView, follower: ScintillaView, was: LongArray, followerWas: LongArray) {
        val line = moved.send(Sci.SCI_GETFIRSTVISIBLELINE)
        val x = moved.send(Sci.SCI_GETXOFFSET)
        if (syncVertical && line != was[0]) follower.send(Sci.SCI_LINESCROLL, 0, line - was[0])
        if (syncHorizontal && x != was[1]) follower.send(Sci.SCI_SETXOFFSET, (follower.send(Sci.SCI_GETXOFFSET) + x - was[1]).coerceAtLeast(0))
        was[0] = line
        was[1] = x
        // The follower will report its own move; it is not to be passed back.
        rememberScroll(follower, followerWas)
    }

    /** View > Open in New Instance and Move to New Instance: the file in a window of its own. */
    private fun openInNewWindow(move: Boolean) {
        val doc = workspace.active
        val uri = doc.uri ?: return
        if (move && workspace.isDirty(doc)) return toast("Save the document first")
        try {
            startActivity(
                Intent(this, SecondWindowActivity::class.java).setData(uri).setAction(Intent.ACTION_VIEW).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ),
            )
            if (move) workspace.close(doc)
        } catch (e: Exception) {
            toast("This device cannot open a second window")
        }
    }

    // endregion

    // region Project Panels

    // Notepad++'s three Project Panels, each a workspace file of projects, folders and files kept with the app.
    private val projectPanels = arrayOfNulls<MutableList<ProjectNode>>(PROJECT_PANELS)
    private val openedProjectPanels = HashSet<Int>()
    private val closedProjectNodes: MutableSet<ProjectNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private var projectTarget: Pair<Int, ProjectNode>? = null
    private var workspacePanel = 0

    private fun projectPanelTitle(index: Int) = "Project ${index + 1}"

    private fun projectFile(index: Int) = java.io.File(java.io.File(filesDir, "projects").apply { mkdirs() }, "workspace${index + 1}.xml")

    internal fun projectPanel(index: Int): MutableList<ProjectNode> = projectPanels[index] ?: run {
        val text = runCatching { projectFile(index).readText(Charsets.UTF_8) }.getOrDefault("")
        ProjectStore.read(text).also { projectPanels[index] = it }
    }

    private fun saveProjects(index: Int) {
        runCatching { projectFile(index).writeText(ProjectStore.write(projectPanel(index)), Charsets.UTF_8) }
        sidePanel?.refresh()
    }

    internal fun showProjectPanel(index: Int) {
        openedProjectPanels += index
        showSidePanel(projectPanelTitle(index))
    }

    private fun askText(title: String, description: String, initial: String, done: (String) -> Unit) {
        val input = EditText(themed).apply {
            contentDescription = description
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(initial)
            selectAll()
        }
        val container = LinearLayout(themed).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(themed)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun PanelSpec.projectRows(index: Int) {
        val projects = projectPanel(index)
        action("New Project") {
            askText("New Project", "Project name", "Project ${projects.size + 1}") { name ->
                projects += ProjectNode(ProjectNode.Kind.PROJECT, name)
                saveProjects(index)
            }
        }
        action("Open...") {
            workspacePanel = index
            pickFile(REQUEST_OPEN_WORKSPACE)
        }
        action("Save As...") {
            workspacePanel = index
            createFile(REQUEST_SAVE_WORKSPACE, "workspace${index + 1}.xml")
        }
        if (projects.isEmpty()) row("Make a project to keep a set of files together.", dimmed = true)
        for (project in projects) projectRow(index, project, 0)
    }

    private fun PanelSpec.projectRow(index: Int, node: ProjectNode, depth: Int) {
        if (node.kind == ProjectNode.Kind.FILE) {
            row(node.name, depth, onLongClick = { projectNodeMenu(index, node) }) {
                sidePanel?.dismiss()
                openProjectFile(node)
            }
            return
        }
        val open = node !in closedProjectNodes
        row(
            node.name, depth, mark = if (open) "\u25BE" else "\u25B8", strong = node.kind == ProjectNode.Kind.PROJECT,
            onLongClick = { projectNodeMenu(index, node) },
        ) {
            if (open) closedProjectNodes += node else closedProjectNodes -= node
            sidePanel?.refresh()
        }
        if (open) for (child in node.children) projectRow(index, child, depth + 1)
    }

    private fun openProjectFile(node: ProjectNode) {
        val path = node.path
        val uri = when {
            path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
            path.startsWith("/") -> Uri.fromFile(java.io.File(path))
            // Workspace files made by Notepad++ on Windows list paths this device does not have.
            else -> return toast("This phone has no file at ${path.take(80)}")
        }
        openUris(listOf(uri))
    }

    private fun projectNodeMenu(index: Int, node: ProjectNode) = showMenu(node.name) {
        val siblings = node.parent?.children ?: projectPanel(index)
        val place = siblings.indexOf(node)
        if (node.kind != ProjectNode.Kind.FILE) {
            val doc = workspace.active
            item("Add Current File", enabled = doc.uri != null) {
                node.add(ProjectNode(ProjectNode.Kind.FILE, doc.name, doc.uri.toString()))
                saveProjects(index)
            }
            item("Add All Open Files", enabled = workspace.docs.any { it.uri != null }) {
                val listed = node.children.map { it.path }.toSet()
                for (open in workspace.docs) {
                    val uri = open.uri?.toString() ?: continue
                    if (uri !in listed) node.add(ProjectNode(ProjectNode.Kind.FILE, open.name, uri))
                }
                saveProjects(index)
            }
            item("Add Files...") {
                projectTarget = index to node
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_PROJECT_FILES)
                } catch (e: Exception) {
                    toast("No file picker is available on this device")
                }
            }
            item("Add Folder...") {
                askText("Add Folder", "Folder name", "Folder") { name ->
                    node.add(ProjectNode(ProjectNode.Kind.FOLDER, name))
                    closedProjectNodes -= node
                    saveProjects(index)
                }
            }
        }
        item("Rename...") {
            askText("Rename", "New name", node.name) { name ->
                node.name = name
                saveProjects(index)
            }
        }
        item("Move Up", enabled = place > 0) {
            node.move(siblings, -1)
            saveProjects(index)
        }
        item("Move Down", enabled = place in 0 until siblings.size - 1) {
            node.move(siblings, 1)
            saveProjects(index)
        }
        item("Remove") {
            val parent = node.parent
            if (parent != null) parent.remove(node) else siblings.remove(node)
            saveProjects(index)
        }
    }

    // endregion

    // region Document Map

    /** View > Document Map: shows or hides the small view of the whole document beside the editor. */
    internal fun setDocumentMap(on: Boolean) {
        prefs.edit().putBoolean("documentMap", on).apply()
        val current = documentMap
        if (on && current == null) {
            val map = DocumentMap(this, editor, palette.accent)
            documentMap = map
            editorRow.addView(map, LinearLayout.LayoutParams(dp(MAP_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
            styleDocumentMap()
        } else if (!on && current != null) {
            documentMap = null
            editorRow.removeView(current)
            current.detach()
        }
    }

    /** The map shows the document of the tab on screen, in that tab's colours and in very small print. */
    private fun styleDocumentMap() {
        val map = documentMap ?: return
        map.attach()
        workspace.styleOther(
            map.view,
            ViewMetrics(
                fontName = fontName, fontSize = MAP_FONT_SIZE, foldMarginWidth = 0, leftPadding = 0,
                showLineNumbers = false, pixelScale = resources.displayMetrics.density,
            ),
        )
        map.plain()
        map.sync()
    }

    // endregion

    // region Function List

    // Notepad++'s function list rules by language name; null for a language that has none.
    private val functionParsers = HashMap<String, FunctionParser?>()

    private class FunctionListing(val doc: Doc, val entries: List<Pair<FunctionEntry, Long>>, val note: String)

    private var functionListing: FunctionListing? = null
    private var functionRun = 0
    private var functionsSorted = false

    private fun functionParser(language: String): FunctionParser? = functionParsers.getOrPut(language) {
        try {
            assets.open("npp/functionList/$language.xml").use { FunctionParser.load(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** For the on-device tests: the rule files this device's regular expression engine cannot use in full. */
    internal fun functionListProblems(): List<String> =
        (assets.list("npp/functionList") ?: emptyArray()).sorted().mapNotNull { file ->
            val parser = functionParser(file.removeSuffix(".xml"))
            when {
                parser == null -> "$file: unusable"
                parser.skipped > 0 -> "$file: ${parser.skipped} skipped"
                else -> null
            }
        }

    /** Reads the functions of the document on screen, in the background, and shows them when done. */
    private fun loadFunctions() {
        val doc = workspace.active
        val run = ++functionRun
        val language = doc.language
        val length = editor.send(Sci.SCI_GETLENGTH)
        if (length > MAX_FUNCTION_LIST_BYTES) {
            functionListing = FunctionListing(doc, emptyList(), "This document is too large for the function list.")
            return
        }
        val text = editor.text
        functionListing = FunctionListing(doc, emptyList(), "Reading\u2026")
        io.execute {
            val parser = functionParser(language.name)
            var note = ""
            val entries = ArrayList<Pair<FunctionEntry, Long>>()
            if (parser == null) {
                note = "Notepad++ has no function list rules for ${language.display}."
            } else {
                try {
                    val found = parser.parse(text).filter { it.name.isNotBlank() }
                    // Scintilla counts in bytes of UTF-8, the parser in characters.
                    var at = 0
                    var bytes = 0L
                    for (entry in found.sortedBy { it.position }) {
                        bytes += text.substring(at, entry.position).toByteArray(Charsets.UTF_8).size
                        at = entry.position
                        entries += entry to bytes
                    }
                    val order = found.withIndex().associate { it.value to it.index }
                    entries.sortBy { order[it.first] }
                    if (entries.isEmpty()) note = "No functions found."
                } catch (e: Throwable) {
                    note = "The function list rules took too long on this document."
                }
            }
            ui.post {
                if (destroyed || run != functionRun) return@post
                functionListing = FunctionListing(doc, entries, note)
                sidePanel?.refresh()
            }
        }
    }

    /** Notepad++'s Function List: the functions of the document, grouped by class, each a tap away. */
    private fun PanelSpec.functionRows() {
        val doc = workspace.active
        var listing = functionListing
        if (listing == null || listing.doc !== doc) {
            loadFunctions()
            listing = functionListing
        }
        action("Reload") { loadFunctions(); sidePanel?.refresh() }
        action(if (functionsSorted) "Sort: A\u2013Z" else "Sort: as written") {
            functionsSorted = !functionsSorted
            sidePanel?.refresh()
        }
        row(doc.name, mark = "\u25BE", strong = true)
        if (listing == null) return
        if (listing.note.isNotEmpty()) row(listing.note, depth = 1, dimmed = true)
        fun tidy(name: String) = name.replace(Regex("\\s+"), " ").trim()
        fun sorted(entries: List<Pair<FunctionEntry, Long>>) =
            if (functionsSorted) entries.sortedBy { tidy(it.first.name).lowercase() } else entries
        fun leaf(entry: Pair<FunctionEntry, Long>, depth: Int) = row(tidy(entry.first.name), depth) {
            sidePanel?.dismiss()
            goToFunction(doc, entry.second)
        }
        // Classes first appear where their first function does, as in Notepad++'s tree.
        val shown = HashSet<String>()
        for (entry in sorted(listing.entries)) {
            val owner = entry.first.owner
            if (owner.isEmpty()) leaf(entry, 1) else if (shown.add(owner)) {
                row(tidy(owner), depth = 1, mark = "\u25BE", strong = true)
                for (member in sorted(listing.entries.filter { it.first.owner == owner })) leaf(member, 2)
            }
        }
    }

    private fun goToFunction(doc: Doc, position: Long) {
        if (doc !== workspace.active) return
        val line = editor.send(Sci.SCI_LINEFROMPOSITION, position.coerceAtMost(editor.send(Sci.SCI_GETLENGTH)))
        editor.send(Sci.SCI_ENSUREVISIBLE, line)
        editor.send(Sci.SCI_GOTOPOS, position)
        // The function goes near the top of the screen, with a line or two of what comes before it.
        editor.send(Sci.SCI_SETFIRSTVISIBLELINE, (editor.send(Sci.SCI_VISIBLEFROMDOCLINE, line) - 2).coerceAtLeast(0))
        updateStatus()
    }

    // endregion

    internal fun workspaceFolders(): List<Uri> =
        (prefs.getString("workspaceFolders", "") ?: "").split('\n').filter { it.isNotEmpty() }.map { Uri.parse(it) }

    /** Notepad++'s Folder as Workspace: the folders that were added, as a tree whose files open with a tap. */
    private fun PanelSpec.folderRows() {
        action("Add Folder...") { pickWorkspaceFolder(null) }
        val roots = workspaceFolders()
        if (roots.isEmpty()) row("Add a folder to browse its files here.", dimmed = true)
        for (picked in roots) {
            val root = folderRoots.getOrPut(picked.toString()) { FolderTree.root(this@MainActivity, picked) }
            folderRow(root, 0, picked)
        }
    }

    private fun PanelSpec.folderRow(node: FolderNode, depth: Int, picked: Uri?) {
        val key = node.uri.toString()
        if (!node.isFolder) {
            row(node.name, depth) {
                sidePanel?.dismiss()
                openUris(listOf(node.uri))
            }
            return
        }
        val open = key in expandedFolders
        row(node.name, depth, mark = if (open) "\u25BE" else "\u25B8", onLongClick = picked?.let { { folderMenu(it, node) } }) {
            if (open) expandedFolders -= key else {
                expandedFolders += key
                loadFolder(node.uri, again = false)
            }
            sidePanel?.refresh()
        }
        if (!open) return
        val children = folderContents[key]
        when {
            children == null -> row("Loading\u2026", depth + 1, dimmed = true)
            children.isEmpty() -> row("(empty)", depth + 1, dimmed = true)
            else -> for (child in children) folderRow(child, depth + 1, null)
        }
    }

    private fun loadFolder(folder: Uri, again: Boolean) {
        val key = folder.toString()
        if ((!again && key in folderContents) || !foldersLoading.add(key)) return
        io.execute {
            val children = FolderTree.children(this, folder)
            ui.post {
                foldersLoading -= key
                folderContents[key] = children
                if (!destroyed) sidePanel?.refresh()
            }
        }
    }

    private fun folderMenu(picked: Uri, root: FolderNode) = showMenu(root.name) {
        item("Refresh") {
            for (key in expandedFolders.toList()) loadFolder(Uri.parse(key), again = true)
        }
        if (picked.scheme == "content") {
            item("Find in Files...") {
                sidePanel?.dismiss()
                prefs.edit().putString("findFolder", picked.toString()).apply()
                findInFilesDialog()
            }
        }
        item("Remove from Workspace") {
            prefs.edit().putString("workspaceFolders", workspaceFolders().filter { it != picked }.joinToString("\n")).apply()
            folderRoots.remove(picked.toString())
            sidePanel?.refresh()
        }
    }

    /** Asks for a folder to add. With a document given, the picker starts where that document is. */
    private fun pickWorkspaceFolder(near: Uri?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (near != null && near.scheme == "content") intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, near)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_WORKSPACE_FOLDER)
        } catch (e: Exception) {
            toast("No folder picker is available")
        }
    }

    internal fun addWorkspaceFolder(picked: Uri) {
        val folders = workspaceFolders()
        if (picked !in folders) prefs.edit().putString("workspaceFolders", (folders + picked).joinToString("\n")).apply()
        val root = folderRoots.getOrPut(picked.toString()) { FolderTree.root(this, picked) }
        expandedFolders += root.uri.toString()
        loadFolder(root.uri, again = true)
        showSidePanel(PANEL_FOLDERS)
    }

    /** Shows a folder in the system's file manager. */
    private fun showInFileManager(folder: Uri?) {
        if (folder == null) return toast("Android only tells where a file is for files on the device's own storage")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(folder, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (e: Exception) {
            toast("No file manager can show this folder")
        }
    }

    /** The folder of the path that is selected, when it is on the device's storage; otherwise that of the current file. */
    private fun folderOfSelectedPath(): Uri? {
        val text = EditOps.selectionOrWord(editor).trim()
        for (prefix in listOf("/storage/emulated/0/", "/sdcard/")) {
            if (text.startsWith(prefix)) {
                val parent = text.removePrefix(prefix).trimEnd('/').substringBeforeLast('/', "")
                return android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$parent")
            }
        }
        return workspace.active.uri?.let { FolderTree.parentOf(it) }
    }

    // endregion

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
        const val REQUEST_FIND_FOLDER = 6
        const val REQUEST_IMPORT_THEME = 7
        const val REQUEST_WORKSPACE_FOLDER = 8
        const val REQUEST_PROJECT_FILES = 9
        const val REQUEST_OPEN_WORKSPACE = 10
        const val REQUEST_SAVE_WORKSPACE = 11
        const val REQUEST_IMPORT_UDL = 12
        const val REQUEST_EXPORT_UDL = 13
        const val PROJECT_PANELS = 3
        const val PANEL_DOCUMENTS = "Documents"
        const val MAP_WIDTH_DP = 72f
        const val MAP_FONT_SIZE = 3
        const val PANEL_FOLDERS = "Folders"
        const val PANEL_FUNCTIONS = "Functions"
        const val MAX_FUNCTION_LIST_BYTES = 3L * 1024 * 1024
        const val MAX_COMPLETION_BYTES = 512L * 1024

        /** Auto-completion files whose names differ from the language's. */
        val API_FILES = mapOf("javascript.js" to "javascript", "coffeescript" to "coffee", "baanc" to "BaanC")
        const val COMMAND_SEPARATOR = " > "

        /** Font families every Android device has. */
        val FONTS = listOf(
            "Monospace" to "monospace", "Serif Monospace" to "serif-monospace", "Sans Serif" to "sans-serif", "Serif" to "serif",
        )

        val SWATCHES = listOf(
            0x000000, 0x404040, 0x808080, 0xC0C0C0, 0xFFFFFF, 0x800000, 0xFF0000, 0xFF8000, 0xFFFF00, 0x808000,
            0x008000, 0x00C000, 0x008080, 0x00FFFF, 0x000080, 0x0000FF, 0x8000FF, 0x800080, 0xFF00FF, 0x804000,
        )

        /** What can be added to the selection toolbar, by command and by the short name shown there. */
        val SELECTION_MENU_CHOICES = listOf(
            "Edit > Delete" to "Delete",
            "Edit > Convert Case to > UPPERCASE" to "UPPERCASE",
            "Edit > Convert Case to > lowercase" to "lowercase",
            "Edit > Comment/Uncomment > Toggle Single Line Comment" to "Comment",
            "Edit > Comment/Uncomment > Block Comment" to "Block comment",
            "Edit > Indent > Increase Line Indent" to "Indent",
            "Edit > Indent > Decrease Line Indent" to "Outdent",
            "Edit > Line Operations > Duplicate Current Line" to "Duplicate",
            "Edit > On Selection > Search on Internet" to "Search web",
            "Edit > On Selection > Open File" to "Open file",
            "Edit > On Selection > Redact Selection" to "Redact",
            "Search > Select and Find Next" to "Find next",
            "Search > Find All in Current Document" to "Find all",
            "Search > Mark All > Using 1st Style" to "Mark",
            "Search > Unmark All > Clear all Styles" to "Unmark",
            "Search > Bookmark > Toggle Bookmark" to "Bookmark",
            "View > Hide Lines" to "Hide lines",
        )
        const val DEFAULT_SELECTION_MENU = ""

        private fun key(modifiers: Int, key: Int) = KeyBinding(modifiers, key)
        private fun f(n: Int) = Shortcuts.FUNCTION + n
        private const val CTRL = Sci.SCMOD_CTRL
        private const val ALT = Sci.SCMOD_ALT
        private const val SHIFT = Sci.SCMOD_SHIFT

        /** The shortcuts Notepad++ comes with, for the commands found here, by their place in the menus. */
        val DEFAULT_SHORTCUTS: Map<String, KeyBinding> = LinkedHashMap<String, KeyBinding>().apply {
            put("File > New", key(CTRL, 'N'.code))
            put("File > Open...", key(CTRL, 'O'.code))
            put("File > Save", key(CTRL, 'S'.code))
            put("File > Save As...", key(CTRL or ALT, 'S'.code))
            put("File > Save All", key(CTRL or SHIFT, 'S'.code))
            put("File > Close", key(CTRL, 'W'.code))
            put("File > Close All", key(CTRL or SHIFT, 'W'.code))
            put("File > Recent Files > Restore Recent Closed File", key(CTRL or SHIFT, 'T'.code))
            put("File > Print...", key(CTRL, 'P'.code))
            put("Edit > Line Operations > Join Lines", key(CTRL, 'J'.code))
            put("Edit > Line Operations > Split Lines", key(CTRL, 'I'.code))
            put("Edit > Line Operations > Move Up Current Line", key(CTRL or SHIFT, Sci.SCK_UP))
            put("Edit > Line Operations > Move Down Current Line", key(CTRL or SHIFT, Sci.SCK_DOWN))
            put("Edit > Comment/Uncomment > Toggle Single Line Comment", key(CTRL, 'Q'.code))
            put("Edit > Comment/Uncomment > Single Line Comment", key(CTRL, 'K'.code))
            put("Edit > Comment/Uncomment > Single Line Uncomment", key(CTRL or SHIFT, 'K'.code))
            put("Edit > Comment/Uncomment > Block Comment", key(CTRL or SHIFT, 'Q'.code))
            put("Edit > Column Editor...", key(ALT, 'C'.code))
            put("Search > Find...", key(CTRL, 'F'.code))
            put("Search > Find Next", key(0, f(3)))
            put("Search > Find Previous", key(SHIFT, f(3)))
            put("Search > Select and Find Next", key(CTRL, f(3)))
            put("Search > Select and Find Previous", key(CTRL or SHIFT, f(3)))
            put("Search > Replace...", key(CTRL, 'H'.code))
            put("Search > Find in Files...", key(CTRL or SHIFT, 'F'.code))
            put("Search > Search Results Window", key(0, f(7)))
            put("Search > Next Search Result", key(0, f(4)))
            put("Search > Previous Search Result", key(SHIFT, f(4)))
            put("Search > Incremental Search", key(CTRL or ALT, 'I'.code))
            put("Search > Mark...", key(CTRL, 'M'.code))
            put("Search > Go to...", key(CTRL, 'G'.code))
            put("Search > Go to Matching Brace", key(CTRL, 'B'.code))
            put("Search > Select All In-between {} [] or ()", key(CTRL or ALT or SHIFT, 'B'.code))
            put("Search > Bookmark > Toggle Bookmark", key(CTRL, f(2)))
            put("Search > Bookmark > Next Bookmark", key(0, f(2)))
            put("Search > Bookmark > Previous Bookmark", key(SHIFT, f(2)))
            put("View > Zoom > Zoom In", key(CTRL, Sci.SCK_ADD))
            put("View > Zoom > Zoom Out", key(CTRL, Sci.SCK_SUBTRACT))
            put("View > Zoom > Restore Default Zoom", key(CTRL, Sci.SCK_DIVIDE))
            put("View > Toggle Full Screen Mode", key(0, f(11)))
            put("View > Hide Lines", key(ALT, 'H'.code))
            put("View > Tab > Next Tab", key(CTRL, Sci.SCK_NEXT))
            put("View > Tab > Previous Tab", key(CTRL, Sci.SCK_PRIOR))
            put("View > Fold All", key(ALT, '0'.code))
            put("View > Unfold All", key(ALT or SHIFT, '0'.code))
            put("View > Fold Current Level", key(CTRL or ALT, 'F'.code))
            put("View > Unfold Current Level", key(CTRL or ALT or SHIFT, 'F'.code))
            for (level in 1..8) {
                put("View > Fold Level > $level", key(ALT, '0'.code + level))
                put("View > Unfold Level > $level", key(ALT or SHIFT, '0'.code + level))
            }
            put("Macro > Start Recording", key(CTRL or SHIFT, 'R'.code))
            put("Macro > Stop Recording", key(CTRL or SHIFT, 'R'.code))
            put("Macro > Playback", key(CTRL or SHIFT, 'P'.code))
            put("Run > Run...", key(0, f(5)))
            put("View > Focus on Another View", key(0, f(8)))
            put("Edit > Auto-Completion > Function Completion", key(CTRL, ' '.code))
            put("Edit > Auto-Completion > Word Completion", key(CTRL, Sci.SCK_RETURN))
            put("Edit > Auto-Completion > Function Parameters Hint", key(CTRL or SHIFT, ' '.code))
            put("Edit > Auto-Completion > Function Parameters Previous Hint", key(ALT, Sci.SCK_UP))
            put("Edit > Auto-Completion > Function Parameters Next Hint", key(ALT, Sci.SCK_DOWN))
            put("Edit > Auto-Completion > Path Completion", key(CTRL or ALT, ' '.code))
        }
        const val MAX_FIND_FILES = 5000
        const val MONITOR_MS = 2000L
        const val MAX_PRINT_BYTES = 4L * 1024 * 1024
        const val MAX_FIND_FILE_BYTES = 8L * 1024 * 1024

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
