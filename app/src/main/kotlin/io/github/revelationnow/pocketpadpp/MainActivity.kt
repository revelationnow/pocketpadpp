package io.github.revelationnow.pocketpadpp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import io.github.revelationnow.pocketpadpp.app.Doc
import io.github.revelationnow.pocketpadpp.app.FileIo
import io.github.revelationnow.pocketpadpp.app.FindBar
import io.github.revelationnow.pocketpadpp.app.LoadedFile
import io.github.revelationnow.pocketpadpp.app.TabStrip
import io.github.revelationnow.pocketpadpp.app.Workspace
import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.LanguageRegistry
import io.github.revelationnow.pocketpadpp.core.NppTheme
import io.github.revelationnow.pocketpadpp.core.TextEncoding
import io.github.revelationnow.pocketpadpp.core.TextEncodings
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

    private lateinit var editor: ScintillaView
    private lateinit var workspace: Workspace
    private lateinit var tabs: TabStrip
    private lateinit var findBar: FindBar
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

    private var fontSize = 13
    private var showLineNumbers = true
    private var shownDirty = false
    private var destroyed = false

    // A Save As in progress: which tab, whether it is only a copy, and what to do afterwards.
    private var saveAsDoc: Doc? = null
    private var saveAsCopy = false
    private var afterSave: (() -> Unit)? = null

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // region Set-up

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontSize = prefs.getInt("fontSize", 13)
        showLineNumbers = prefs.getBoolean("lineNumbers", true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CHROME)
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

        tabs = TabStrip(this)
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38f)))

        editor = ScintillaView(this)
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        imeLogView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(0xFF004400.toInt())
            setBackgroundColor(0xFFF4FFF4.toInt())
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            visibility = View.GONE
        }
        root.addView(imeLogView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        findBar = FindBar(this, editor)
        findBar.onClose = { editor.requestFocus() }
        root.addView(findBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(extraKeys(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42f)))
        root.addView(statusBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        // Notepad++'s own language and style definitions, shipped as assets.
        val languages = assets.open("npp/languages.tsv").use { table ->
            assets.open("npp/langs.model.xml").use { xml -> LanguageRegistry.load(table, xml) }
        }
        val theme = assets.open("npp/stylers.model.xml").use { NppTheme.load(it) }

        editor.send(Sci.SCI_SETTABWIDTH, 4)
        editor.send(Sci.SCI_SETWRAPMODE, (if (prefs.getBoolean("wrap", false)) Sci.SC_WRAP_WORD else Sci.SC_WRAP_NONE).toLong())
        editor.keyboardMode = if (prefs.getBoolean("textKeyboard", false)) ScintillaView.KeyboardMode.TEXT else ScintillaView.KeyboardMode.CODE

        workspace = Workspace(this, editor, languages, theme) {
            ViewMetrics(fontSize = fontSize, foldMarginWidth = dp(22f), leftPadding = dp(4f), showLineNumbers = showLineNumbers)
        }
        workspace.onTabsChanged = { refreshTabs() }
        tabs.onSelect = { index -> workspace.docs.getOrNull(index)?.let { workspace.activate(it) } }
        tabs.onClose = { index -> workspace.docs.getOrNull(index)?.let { requestClose(it) } }
        tabs.onLongPress = { index, view -> workspace.docs.getOrNull(index)?.let { showTabMenu(it, view) } }

        editor.onStateChanged = { onEditorStateChanged() }
        editor.onTextChanged = { workspace.textChanged() }
        editor.onCharAdded = { ch -> EditOps.autoIndent(editor, ch) }
        editor.imeLog = { line -> onImeLog(line) }
        editor.requestFocus()
        refreshTabs()

        restoreSession { handleIntent(intent) }
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
        if (findBar.isOpen) findBar.close() else super.onBackPressed()
    }

    private fun barButton(label: String, description: String, onClick: (View) -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
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
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(label: String, action: () -> Unit) {
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minWidth = 0
                minimumWidth = dp(40f)
                setPadding(dp(8f), 0, dp(8f), 0)
                isFocusable = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        key("Tab") { editor.pressKey(Sci.SCK_TAB) }
        key("←") { editor.pressKey(Sci.SCK_LEFT) }
        key("↑") { editor.pressKey(Sci.SCK_UP) }
        key("↓") { editor.pressKey(Sci.SCK_DOWN) }
        key("→") { editor.pressKey(Sci.SCK_RIGHT) }
        key("Home") { editor.pressKey(Sci.SCK_HOME) }
        key("End") { editor.pressKey(Sci.SCK_END) }
        for (symbol in listOf("{", "}", "(", ")", "[", "]", ";", ":", "\"", "'", "<", ">", "=", "/", "\\", "|", "&", "!", "_", "#")) {
            key(symbol) { editor.typeText(symbol) }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFFF7F7F7.toInt())
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Notepad++'s status bar: position on the left; line ending, encoding and language on the right, each tappable. */
    private fun statusBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CHROME)
        }
        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF404040.toInt())
            setPadding(dp(8f), dp(4f), dp(4f), dp(4f))
            maxLines = 1
            // Notepad++ opens Go to from a double click on this part of the status bar.
            setOnClickListener { goToLine() }
        }
        bar.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun chip(action: (View) -> Unit) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF202020.toInt())
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            maxLines = 1
            setOnClickListener(action)
        }.also { bar.addView(it) }
        languageChip = chip { chooseLanguage() }
        eolChip = chip { showEolMenu(it) }
        encodingChip = chip { showEncodingMenu(it) }
        return bar
    }

    // endregion

    // region Keeping the chrome in step with the editor

    private fun refreshTabs() {
        shownDirty = workspace.isDirty(workspace.active)
        tabs.setTabs(workspace.docs.map { TabStrip.Tab(it.name, workspace.isDirty(it), it === workspace.active) })
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
        if (end > start) parts += "Sel ${editor.send(Sci.SCI_COUNTCHARACTERS, start, end)}"
        if (carets > 1) parts += "$carets carets"
        parts += "${editor.send(Sci.SCI_GETLINECOUNT)} lines"
        if (editor.send(Sci.SCI_GETREADONLY) != 0L) parts += "Read-only"
        statusText.text = parts.joinToString("  ·  ")
        languageChip.text = workspace.active.language.display
        eolChip.text = Eol.fromSciMode(editor.send(Sci.SCI_GETEOLMODE).toInt()).label
        encodingChip.text = workspace.active.encoding.label
    }

    private fun onImeLog(line: String) {
        imeLines.addLast(line)
        while (imeLines.size > 6) imeLines.removeFirst()
        if (imeLogView.visibility == View.VISIBLE) imeLogView.text = imeLines.joinToString("\n")
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

    private fun openUris(uris: List<Uri>) {
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
            AlertDialog.Builder(this)
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
                    AlertDialog.Builder(this)
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
            workspace.close(doc)
            then?.invoke()
            return
        }
        workspace.activate(doc)
        AlertDialog.Builder(this)
            .setTitle("Save")
            .setMessage("Save file \"${doc.name}\"?")
            .setPositiveButton("Yes") { _, _ -> save(doc) { workspace.close(doc); then?.invoke() } }
            .setNegativeButton("No") { _, _ -> workspace.close(doc); then?.invoke() }
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

    private class MenuSpec {
        class Entry(val label: String, val enabled: Boolean, val checked: Boolean?, val action: (() -> Unit)?, val children: MenuSpec?)

        val entries = ArrayList<Entry>()

        fun item(label: String, enabled: Boolean = true, checked: Boolean? = null, action: () -> Unit) {
            entries += Entry(label, enabled, checked, action, null)
        }

        /** One level of nesting is all Android menus allow; deeper levels open as a fresh popup. */
        fun sub(label: String, build: MenuSpec.() -> Unit) {
            entries += Entry(label, true, null, null, MenuSpec().apply(build))
        }
    }

    private fun showMenu(anchor: View, build: MenuSpec.() -> Unit) {
        val popup = PopupMenu(this, anchor)
        val actions = HashMap<Int, () -> Unit>()
        var nextId = 1
        fun add(menu: Menu, spec: MenuSpec, nested: Boolean) {
            for (entry in spec.entries) {
                val children = entry.children
                if (children != null && !nested) {
                    add(menu.addSubMenu(entry.label), children, nested = true)
                } else {
                    val id = nextId++
                    val item = menu.add(Menu.NONE, id, Menu.NONE, if (children != null) "${entry.label}  ▸" else entry.label)
                    item.isEnabled = entry.enabled
                    if (entry.checked != null) {
                        item.isCheckable = true
                        item.isChecked = entry.checked
                    }
                    actions[id] = if (children != null) {
                        { showMenu(anchor) { entries += children.entries } }
                    } else {
                        entry.action ?: {}
                    }
                }
            }
        }
        add(popup.menu, MenuSpec().apply(build), nested = false)
        popup.setOnMenuItemClickListener { item ->
            actions[item.itemId]?.let { action ->
                action()
                updateStatus()
            }
            true
        }
        popup.show()
    }

    private fun showMainMenu() = showMenu(moreButton) {
        sub("File") { fileMenu() }
        sub("Edit") { editMenu() }
        sub("Search") { searchMenu() }
        sub("View") { viewMenu() }
        sub("Encoding") { encodingMenu() }
        item("Language...") { chooseLanguage() }
        sub("Settings") { settingsMenu() }
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
        sub("Close Multiple Documents") {
            val index = workspace.docs.indexOf(doc)
            item("Close All but Active Document") { closeMany(workspace.docs.filter { it !== doc }) }
            item("Close All to the Left") { closeMany(workspace.docs.take(index)) }
            item("Close All to the Right") { closeMany(workspace.docs.drop(index + 1)) }
            item("Close All Unchanged") { closeMany(workspace.docs.filter { !workspace.isDirty(it) }) }
        }
    }

    private fun MenuSpec.editMenu() {
        val readOnly = editor.send(Sci.SCI_GETREADONLY) != 0L
        item("Undo", enabled = editor.send(Sci.SCI_CANUNDO) != 0L) { editor.send(Sci.SCI_UNDO) }
        item("Redo", enabled = editor.send(Sci.SCI_CANREDO) != 0L) { editor.send(Sci.SCI_REDO) }
        item("Cut") { editor.send(Sci.SCI_CUT) }
        item("Copy") { editor.send(Sci.SCI_COPY) }
        item("Paste") { editor.send(Sci.SCI_PASTE) }
        item("Delete") { editor.send(Sci.SCI_CLEAR) }
        item("Select All") { editor.send(Sci.SCI_SELECTALL) }
        sub("Indent") {
            item("Increase Line Indent") { editor.send(Sci.SCI_TAB) }
            item("Decrease Line Indent") { editor.send(Sci.SCI_BACKTAB) }
        }
        sub("Convert Case to") {
            item("UPPERCASE") { editor.send(Sci.SCI_UPPERCASE) }
            item("lowercase") { editor.send(Sci.SCI_LOWERCASE) }
        }
        sub("Line Operations") {
            item("Duplicate Current Line") { editor.send(Sci.SCI_SELECTIONDUPLICATE) }
            item("Remove Duplicate Lines") { EditOps.removeDuplicateLines(editor) }
            item("Join Lines") { EditOps.joinLines(editor) }
            item("Move Up Current Line") { editor.send(Sci.SCI_MOVESELECTEDLINESUP) }
            item("Move Down Current Line") { editor.send(Sci.SCI_MOVESELECTEDLINESDOWN) }
            item("Remove Empty Lines") { EditOps.removeEmptyLines(editor, includeBlank = false) }
            item("Remove Empty Lines (Containing Blank characters)") { EditOps.removeEmptyLines(editor, includeBlank = true) }
            item("Reverse Line Order") { EditOps.reverseLines(editor) }
            item("Sort Lines Lexicographically Ascending") { EditOps.sortLines(editor, descending = false) }
            item("Sort Lines Lexicographically Descending") { EditOps.sortLines(editor, descending = true) }
        }
        sub("Comment/Uncomment") {
            item("Toggle Single Line Comment") {
                if (!EditOps.toggleLineComment(editor, workspace.active.language.commentLine)) {
                    toast("${workspace.active.language.display} has no single line comment")
                }
            }
        }
        sub("EOL Conversion") { eolItems() }
        sub("Blank Operations") {
            item("Trim Trailing Space") { EditOps.trimTrailingSpace(editor) }
        }
        item("Read-Only", checked = readOnly) { editor.send(Sci.SCI_SETREADONLY, if (readOnly) 0 else 1) }
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
        item("Replace...") { findBar.open(withReplace = true) }
        item("Go to...") { goToLine() }
        item("Go to Matching Brace") { EditOps.goToMatchingBrace(editor) }
        sub("Bookmark") {
            item("Toggle Bookmark") { toggleBookmark() }
            item("Next Bookmark") { goToBookmark(forward = true) }
            item("Previous Bookmark") { goToBookmark(forward = false) }
            item("Clear All Bookmarks") { editor.send(Sci.SCI_MARKERDELETEALL, ThemeApplier.BOOKMARK_MARKER.toLong()) }
        }
    }

    private fun MenuSpec.viewMenu() {
        val wrapped = editor.send(Sci.SCI_GETWRAPMODE).toInt() != Sci.SC_WRAP_NONE
        val whitespace = editor.send(Sci.SCI_GETVIEWWS).toInt() != Sci.SCWS_INVISIBLE
        val eol = editor.send(Sci.SCI_GETVIEWEOL) != 0L
        val guides = editor.send(Sci.SCI_GETINDENTATIONGUIDES).toInt() != Sci.SC_IV_NONE
        val wrapSymbol = editor.send(Sci.SCI_GETWRAPVISUALFLAGS).toInt() != Sci.SC_WRAPVISUALFLAG_NONE
        item("Word wrap", checked = wrapped) {
            editor.send(Sci.SCI_SETWRAPMODE, (if (wrapped) Sci.SC_WRAP_NONE else Sci.SC_WRAP_WORD).toLong())
            prefs.edit().putBoolean("wrap", !wrapped).apply()
        }
        sub("Show Symbol") {
            fun showWhitespace(on: Boolean) = editor.send(Sci.SCI_SETVIEWWS, (if (on) Sci.SCWS_VISIBLEALWAYS else Sci.SCWS_INVISIBLE).toLong())
            item("Show Space and Tab", checked = whitespace) { showWhitespace(!whitespace) }
            item("Show End of Line", checked = eol) { editor.send(Sci.SCI_SETVIEWEOL, if (eol) 0 else 1) }
            item("Show All Characters", checked = whitespace && eol) {
                val on = !(whitespace && eol)
                showWhitespace(on)
                editor.send(Sci.SCI_SETVIEWEOL, if (on) 1 else 0)
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
        item("Fold All") { editor.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_CONTRACT.toLong()) }
        item("Unfold All") { editor.send(Sci.SCI_FOLDALL, Sci.SC_FOLDACTION_EXPAND.toLong()) }
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
        item("Character sets...") { chooseCharacterSet() }
    }

    private fun MenuSpec.settingsMenu() {
        val textMode = editor.keyboardMode == ScintillaView.KeyboardMode.TEXT
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
        }
    }

    private fun setFontSize(size: Int) {
        fontSize = size.coerceIn(6, 40)
        prefs.edit().putInt("fontSize", fontSize).apply()
        workspace.restyle()
    }

    private fun showEolMenu(anchor: View) = showMenu(anchor) { eolItems() }

    private fun showEncodingMenu(anchor: View) = showMenu(anchor) { encodingMenu() }

    private fun showTabMenu(doc: Doc, anchor: View) = showMenu(anchor) {
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

    private fun chooseLanguage() {
        val languages = listOf(workspace.languages.normal) + workspace.languages.menu
        val labels = languages.map { if (it === workspace.languages.normal) "None (Normal Text)" else it.display }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Language")
            .setSingleChoiceItems(labels, languages.indexOf(workspace.active.language)) { dialog, which ->
                workspace.setLanguage(languages[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Notepad++'s Encoding > Character sets: re-read the file as that character set. */
    private fun chooseCharacterSet() {
        val sets = TextEncodings.characterSets.filter { it.isSupported }
        val labels = sets.map { "${it.group}: ${it.label}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Character sets")
            .setSingleChoiceItems(labels, sets.indexOf(workspace.active.encoding)) { dialog, which ->
                dialog.dismiss()
                if (workspace.active.uri != null) reloadFromDisk(sets[which]) else workspace.setEncoding(sets[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun goToLine() {
        val lines = editor.send(Sci.SCI_GETLINECOUNT)
        val current = editor.send(Sci.SCI_LINEFROMPOSITION, editor.send(Sci.SCI_GETCURRENTPOS)) + 1
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 - $lines"
            setText(current.toString())
            selectAll()
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this).setTitle("Summary").setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    // endregion

    private companion object {
        const val REQUEST_OPEN = 1
        const val REQUEST_SAVE_AS = 2
        const val CHROME = 0xFFEFEFEF.toInt()
    }
}
