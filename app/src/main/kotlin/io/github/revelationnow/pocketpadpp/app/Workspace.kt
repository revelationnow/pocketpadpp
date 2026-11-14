package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.revelationnow.pocketpadpp.core.EditOps
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.Language
import io.github.revelationnow.pocketpadpp.core.LanguageRegistry
import io.github.revelationnow.pocketpadpp.core.LanguageSetup
import io.github.revelationnow.pocketpadpp.core.NppTheme
import io.github.revelationnow.pocketpadpp.core.Session
import io.github.revelationnow.pocketpadpp.core.SessionEntry
import io.github.revelationnow.pocketpadpp.core.SessionStore
import io.github.revelationnow.pocketpadpp.core.TextEncoding
import io.github.revelationnow.pocketpadpp.core.TextEncodings
import io.github.revelationnow.pocketpadpp.core.ThemeApplier
import io.github.revelationnow.pocketpadpp.core.ViewMetrics
import io.github.revelationnow.pocketpadpp.editor.ScintillaView
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import java.io.File
import java.io.IOException

/** One open tab. The text itself lives in a Scintilla document. */
class Doc(val id: Long, internal val sciDoc: Long, var language: Language) {
    var uri: Uri? = null
    var name: String = ""
    /** N for a never-saved "new N" tab, otherwise 0. */
    var untitledNumber = 0
    var encoding: TextEncoding = TextEncodings.UTF8

    /** Modified since the last save. Kept up to date for the active tab; frozen while a tab is in the background. */
    var dirty = false

    /** Set when content was restored from a backup or its encoding changed: unsaved even though Scintilla has no edits. */
    internal var forcedDirty = false
    internal var lexerApplied = false
    internal var changedSinceBackup = false
    internal var backupName: String? = null

    // View state while the tab is in the background.
    internal var caret = 0L
    internal var anchor = 0L
    internal var firstDocLine = 0L
    internal var folds: List<Long> = emptyList()

    /** 0 for none, or 1 to 5: the colour given to the tab with View > Tab > Apply Colour. */
    var colour = 0

    /** Pinned tabs stay at the start of the tab bar and survive Close All but Pinned. */
    var pinned = false

    /** View > Monitoring (tail -f): the file is watched and shown again whenever it changes. */
    var monitoring = false
    internal var monitorStamp = 0L

    /** Size as UTF-8 and the file's modification time when it was opened or saved, for Window > Sort By. */
    var length = 0L
    var modified = 0L

    /** Counts up each time the tab is shown, for most-recently-used order. */
    internal var lastShown = 0L

    /** A read-only state set while the tab was in the background, applied when it is next shown. */
    internal var pendingReadOnly: Boolean? = null
    internal var xOffset = 0L
    internal var eolMode = Eol.LF.sciMode

    /** Saved while in the background; Scintilla is told once the tab is shown again. */
    internal var savePointPending = false
}

/** What a file on disk turned into when read. */
class LoadedFile(val name: String, val utf8: ByteArray, val encoding: TextEncoding, val eol: Eol?)

object FileIo {
    /** Refuse files that would not fit comfortably in memory several times over. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** A number that changes when the file does: its modification time and size together. */
    fun stamp(context: Context, uri: Uri): Long {
        try {
            if (uri.scheme == "file") {
                val file = java.io.File(uri.path ?: return 0)
                return file.lastModified() * 31 + file.length()
            }
            val columns = arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE)
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return (if (cursor.isNull(0)) 0 else cursor.getLong(0)) * 31 + (if (cursor.isNull(1)) 0 else cursor.getLong(1))
            }
        } catch (e: Exception) {
            // Treated as unchanged.
        }
        return 0
    }

    /** When the file was last changed, in milliseconds, or 0 when its provider does not say. */
    fun lastModified(context: Context, uri: Uri): Long {
        try {
            if (uri.scheme == "file") return java.io.File(uri.path ?: return 0).lastModified()
            context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
            }
        } catch (e: Exception) {
            // Not every provider has the column.
        }
        return 0
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        } catch (e: Exception) {
            // Some providers do not answer queries; fall back to the path.
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "file"
    }

    @Throws(IOException::class)
    fun read(context: Context, uri: Uri, encoding: TextEncoding? = null): LoadedFile {
        val bytes = (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_FILE_BYTES) throw IOException("The file is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB")
            }
            out.toByteArray()
        }
        val used = encoding ?: TextEncodings.detect(bytes)
        val utf8 = TextEncodings.toUtf8(bytes, used)
        return LoadedFile(displayName(context, uri), utf8, used, Eol.detect(utf8))
    }

    @Throws(IOException::class)
    fun write(context: Context, uri: Uri, bytes: ByteArray) {
        val resolver = context.contentResolver
        // "wt" truncates; a few providers only understand "w".
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            resolver.openOutputStream(uri, "w")
        } ?: throw IOException("Cannot open the file for writing")
        stream.use { it.write(bytes) }
    }

    /** Keeps access to a picked file across restarts, where the provider allows it. */
    fun persistAccess(context: Context, uri: Uri) {
        for (flags in intArrayOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                return
            } catch (e: Exception) {
                // Not offered for this URI; try the weaker grant, then give up quietly.
            }
        }
    }
}

/**
 * The set of open tabs over a single editor view.
 * Each tab owns a Scintilla document; switching tabs swaps the view's document pointer, as Notepad++ does.
 */
class Workspace(
    private val context: Context,
    private val editor: ScintillaView,
    val languages: LanguageRegistry,
    theme: NppTheme,
    private val metrics: () -> ViewMetrics,
) {
    /** The Notepad++ theme in use. Setting it restyles the editor straight away. */
    var theme: NppTheme = theme
        set(value) {
            field = value
            restyle()
        }

    val docs = ArrayList<Doc>()
    lateinit var active: Doc
        private set

    /** Called when tabs are added, removed, renamed, switched or change their modified state. */
    var onTabsChanged: (() -> Unit)? = null

    /** Called whenever a tab has been given its language and theme, which is when it is shown. */
    var afterStyle: (() -> Unit)? = null

    /** Settings > Preferences > New Document. */
    var newDocumentEol: Eol = Eol.LF
    var newDocumentEncoding: TextEncoding = TextEncodings.UTF8

    private var nextId = 1L
    private val backupDir = File(context.filesDir, "backup").apply { mkdirs() }
    private val sessionFile = File(context.filesDir, "session.xml")
    private var lineNumberDigits = 0

    /** View > Show Symbol > Show Indent Guide. */
    var showIndentGuides = true
        set(value) {
            field = value
            restyle()
        }

    init {
        // Adopt the document the view was created with as the first tab.
        val initial = editor.send(Sci.SCI_GETDOCPOINTER)
        editor.send(Sci.SCI_ADDREFDOCUMENT, 0, initial)
        val doc = Doc(nextId++, initial, languages.normal).apply {
            untitledNumber = 1
            name = "new 1"
        }
        docs += doc
        active = doc
        applyLanguage(doc)
        EditOps.enableChangeHistory(editor)
    }

    // region Tabs

    private fun nextUntitledNumber(): Int {
        var n = 1
        while (docs.any { it.untitledNumber == n }) n++
        return n
    }

    // Folds of the tab on screen, noted just before a document is created (see createDoc).
    private var foldsBeforeCreate: List<Long>? = null

    private fun createDoc(): Doc {
        // SCI_CREATEDOCUMENT has a side effect: it resets the fold state of the view. Every new document is
        // switched to straight away, so the tab being left only needs its folds noted before that happens.
        foldsBeforeCreate = EditOps.foldedLines(editor)
        return Doc(nextId++, editor.send(Sci.SCI_CREATEDOCUMENT), languages.normal).also { docs += it }
    }

    fun newDocument(): Doc {
        val number = nextUntitledNumber()
        val doc = createDoc().apply {
            untitledNumber = number
            name = "new $number"
        }
        doc.encoding = newDocumentEncoding
        activate(doc)
        editor.send(Sci.SCI_SETEOLMODE, newDocumentEol.sciMode.toLong())
        EditOps.enableChangeHistory(editor)
        onTabsChanged?.invoke()
        return doc
    }

    fun isDirty(doc: Doc): Boolean =
        if (doc === active) doc.forcedDirty || editor.send(Sci.SCI_GETMODIFY) != 0L else doc.dirty

    /** True for the untouched empty tab the app starts with, which a newly opened file may take over. */
    private fun isPristine(doc: Doc): Boolean =
        doc === active && doc.uri == null && !isDirty(doc) && editor.send(Sci.SCI_GETLENGTH) == 0L

    private fun saveViewState() {
        val doc = active
        doc.caret = editor.send(Sci.SCI_GETCURRENTPOS)
        doc.anchor = editor.send(Sci.SCI_GETANCHOR)
        doc.firstDocLine = editor.send(Sci.SCI_DOCLINEFROMVISIBLE, editor.send(Sci.SCI_GETFIRSTVISIBLELINE))
        doc.xOffset = editor.send(Sci.SCI_GETXOFFSET)
        // Which blocks are folded belongs to the view, not the document, so it is carried per tab by hand.
        doc.folds = foldsBeforeCreate ?: EditOps.foldedLines(editor)
        doc.length = editor.send(Sci.SCI_GETLENGTH)
        foldsBeforeCreate = null
        doc.eolMode = editor.send(Sci.SCI_GETEOLMODE).toInt()
        doc.dirty = isDirty(doc)
        writeBackupIfNeeded(doc)
    }

    private fun restoreViewState(doc: Doc) {
        editor.send(Sci.SCI_SETANCHOR, doc.anchor)
        editor.send(Sci.SCI_SETCURRENTPOS, doc.caret)
        editor.send(Sci.SCI_SETFIRSTVISIBLELINE, editor.send(Sci.SCI_VISIBLEFROMDOCLINE, doc.firstDocLine))
        editor.send(Sci.SCI_SETXOFFSET, doc.xOffset)
    }

    fun activate(doc: Doc) {
        if (doc !== active) {
            saveViewState()
            editor.send(Sci.SCI_SETDOCPOINTER, 0, doc.sciDoc)
            active = doc
            if (doc.savePointPending) {
                editor.send(Sci.SCI_SETSAVEPOINT)
                doc.savePointPending = false
            }
            doc.pendingReadOnly?.let { editor.send(Sci.SCI_SETREADONLY, if (it) 1 else 0) }
            doc.pendingReadOnly = null
            doc.lastShown = ++shownCounter
            applyLanguage(doc)
            EditOps.restoreFolds(editor, doc.folds)
            restoreViewState(doc)
            editor.documentSwitched()
        }
        onTabsChanged?.invoke()
    }

    private var shownCounter = 0L

    /** Window > Sort By: puts the tabs in a new order. The tab on screen stays the same. */
    fun sortTabs(order: Comparator<Doc>) {
        active.length = editor.send(Sci.SCI_GETLENGTH)
        docs.sortWith(order)
        onTabsChanged?.invoke()
    }

    /** View > Tab > Pin Tab: pinned tabs gather at the start of the tab bar, in the order they had. */
    fun setPinned(doc: Doc, pinned: Boolean) {
        doc.pinned = pinned
        docs.sortWith(compareByDescending { it.pinned })
        onTabsChanged?.invoke()
    }

    /** Edit > Set / Clear Read-Only for All Documents. */
    fun setReadOnlyAll(readOnly: Boolean) {
        for (doc in docs) doc.pendingReadOnly = if (doc === active) null else readOnly
        editor.send(Sci.SCI_SETREADONLY, if (readOnly) 1 else 0)
        onTabsChanged?.invoke()
    }

    /** Closes a tab without asking; the caller deals with unsaved changes first. */
    fun close(doc: Doc) {
        val index = docs.indexOf(doc)
        if (index < 0) return
        if (docs.size == 1) {
            // Like Notepad++, the last tab is replaced by a fresh empty one.
            newDocument()
        } else if (doc === active) {
            activate(docs[if (index == docs.size - 1) index - 1 else index + 1])
        }
        docs.remove(doc)
        deleteBackup(doc)
        editor.send(Sci.SCI_RELEASEDOCUMENT, 0, doc.sciDoc)
        onTabsChanged?.invoke()
    }

    fun move(doc: Doc, toIndex: Int) {
        if (docs.remove(doc)) {
            docs.add(toIndex.coerceIn(0, docs.size), doc)
            onTabsChanged?.invoke()
        }
    }

    // endregion

    // region Language and styles

    private fun applyLanguage(doc: Doc) {
        val plan = LanguageSetup.plan(doc.language, languages, theme)
        if (!doc.lexerApplied) {
            LanguageSetup.applyToDocument(editor, plan)
            // The user-defined-language lexer keeps what it knows of a document under a number of the document's own.
            if (doc.language.user != null) editor.sendStrings(Sci.SCI_SETPROPERTY, "userDefine.currentBufferID", doc.id.toString())
            doc.lexerApplied = true
        }
        ThemeApplier.apply(editor, theme, plan, metrics())
        lineNumberDigits = editor.send(Sci.SCI_GETLINECOUNT).toString().length
        // Python-like languages fold by indentation, which Notepad++ pairs with forward-looking guides.
        val pythonStyle = doc.language.name in setOf("python", "nim", "coffeescript", "yaml", "gdscript")
        val guides = when {
            !showIndentGuides -> Sci.SC_IV_NONE
            pythonStyle -> Sci.SC_IV_LOOKFORWARD
            else -> Sci.SC_IV_LOOKBOTH
        }
        editor.send(Sci.SCI_SETINDENTATIONGUIDES, guides.toLong())
        afterStyle?.invoke()
    }

    fun setLanguage(language: Language) {
        active.language = language
        active.lexerApplied = false
        applyLanguage(active)
        editor.send(Sci.SCI_COLOURISE, 0, -1)
        onTabsChanged?.invoke()
    }

    /** Gives another view of the active tab's document, such as the Document Map, the tab's colours. */
    fun styleOther(other: io.github.revelationnow.pocketpadpp.core.SciApi, otherMetrics: ViewMetrics) {
        ThemeApplier.apply(other, theme, LanguageSetup.plan(active.language, languages, theme), otherMetrics)
    }

    /** After the user-defined languages were read again: tabs that use one get its new definition, or lose it. */
    fun userLanguagesChanged() {
        for (doc in docs) {
            if (doc.language.user == null) continue
            doc.language = languages.byName(doc.language.name)?.takeIf { it.user != null } ?: languages.normal
            doc.lexerApplied = false
        }
        applyLanguage(active)
        editor.send(Sci.SCI_COLOURISE, 0, -1)
        onTabsChanged?.invoke()
    }

    /** Re-applies styles to the active tab, for example after the font size changed. */
    fun restyle() = applyLanguage(active)

    /** Widens the line number margin when the line count gains a digit. */
    fun lineCountMayHaveChanged() {
        val digits = editor.send(Sci.SCI_GETLINECOUNT).toString().length
        if (digits != lineNumberDigits) {
            lineNumberDigits = digits
            ThemeApplier.updateLineNumberWidth(editor, metrics().showLineNumbers)
        }
    }

    // endregion

    // region Content

    private fun setContent(doc: Doc, utf8: ByteArray, eol: Eol?) {
        // Loading is not a change: history is off while the text goes in and starts afresh afterwards.
        editor.send(Sci.SCI_SETCHANGEHISTORY, 0)
        editor.send(Sci.SCI_SETUNDOCOLLECTION, 0)
        editor.send(Sci.SCI_CLEARALL)
        editor.sendBytes(Sci.SCI_ADDTEXT, utf8.size.toLong(), utf8)
        editor.send(Sci.SCI_SETUNDOCOLLECTION, 1)
        editor.send(Sci.SCI_EMPTYUNDOBUFFER)
        editor.send(Sci.SCI_SETSAVEPOINT)
        if (eol != null) editor.send(Sci.SCI_SETEOLMODE, eol.sciMode.toLong())
        editor.send(Sci.SCI_GOTOPOS, 0)
        EditOps.enableChangeHistory(editor)
        doc.changedSinceBackup = false
    }

    /** Shows loaded content in a new tab, or in the untouched start-up tab. */
    fun open(uri: Uri?, file: LoadedFile, language: Language? = null): Doc {
        val doc = if (isPristine(active)) active else createDoc()
        if (doc !== active) {
            saveViewState()
            editor.send(Sci.SCI_SETDOCPOINTER, 0, doc.sciDoc)
            active = doc
        }
        doc.uri = uri
        doc.name = file.name
        doc.untitledNumber = 0
        doc.length = file.utf8.size.toLong()
        doc.modified = if (uri != null) FileIo.lastModified(context, uri) else 0
        doc.encoding = file.encoding
        doc.forcedDirty = false
        doc.language = language ?: languages.forFileName(file.name)
        doc.lexerApplied = false
        setContent(doc, file.utf8, file.eol)
        applyLanguage(doc)
        editor.documentSwitched()
        onTabsChanged?.invoke()
        return doc
    }

    /** Replaces the active tab's text with a fresh read of its file. */
    fun reloaded(file: LoadedFile) {
        val doc = active
        doc.encoding = file.encoding
        doc.forcedDirty = false
        setContent(doc, file.utf8, file.eol)
        deleteBackup(doc)
        editor.documentSwitched()
        onTabsChanged?.invoke()
    }

    fun findByUri(uri: Uri): Doc? = docs.firstOrNull { it.uri == uri }

    /** The active tab's text as it should be written to a file. */
    fun bytesForSaving(): ByteArray = editor.range(0, editor.send(Sci.SCI_GETLENGTH))

    /** Records that the active tab now matches what is on disk. */
    fun markSaved(doc: Doc, uri: Uri, name: String) {
        val renamed = doc.uri != uri || doc.name != name
        doc.modified = System.currentTimeMillis()
        doc.uri = uri
        doc.name = name
        doc.untitledNumber = 0
        doc.forcedDirty = false
        doc.dirty = false
        if (doc === active) {
            editor.send(Sci.SCI_SETSAVEPOINT)
            if (renamed && doc.language === languages.normal) {
                val detected = languages.forFileName(name)
                if (detected !== languages.normal) setLanguage(detected)
            }
        } else {
            doc.savePointPending = true
        }
        deleteBackup(doc)
        onTabsChanged?.invoke()
    }

    fun setEncoding(encoding: TextEncoding) {
        if (active.encoding != encoding) {
            active.encoding = encoding
            active.forcedDirty = true
            active.changedSinceBackup = true
            onTabsChanged?.invoke()
        }
    }

    fun textChanged() {
        active.changedSinceBackup = true
    }

    // endregion

    // region Session: open tabs and unsaved text survive the app being closed

    private fun writeBackupIfNeeded(doc: Doc) {
        if (doc !== active) return
        if (!isDirty(doc)) {
            deleteBackup(doc)
            return
        }
        if (doc.backupName != null && !doc.changedSinceBackup) return
        val name = doc.backupName ?: "doc-${doc.id}-${System.currentTimeMillis()}.bak"
        try {
            File(backupDir, name).writeBytes(bytesForSaving())
            doc.backupName = name
            doc.changedSinceBackup = false
        } catch (e: IOException) {
            // Out of space or similar: the session simply will not carry this tab's unsaved text.
        }
    }

    private fun deleteBackup(doc: Doc) {
        doc.backupName?.let { File(backupDir, it).delete() }
        doc.backupName = null
    }

    private fun sessionNow(withBackups: Boolean): Session {
        saveViewState()
        val entries = docs.map { doc ->
            SessionEntry(
                location = doc.uri?.toString(),
                name = doc.name,
                language = doc.language.name,
                encoding = doc.encoding.id,
                eolMode = doc.eolMode,
                caret = doc.caret,
                anchor = doc.anchor,
                firstVisibleLine = doc.firstDocLine,
                xOffset = doc.xOffset.toInt(),
                tabColour = doc.colour,
                pinned = doc.pinned,
                backup = if (withBackups) doc.backupName else null,
                untitledNumber = doc.untitledNumber,
            )
        }
        return Session(docs.indexOf(active), entries)
    }

    /** File > Save Session: the open files that exist in storage, as a Notepad++-style session file. */
    fun exportSession(): String {
        val files = sessionNow(withBackups = false).entries.filter { it.location != null }
        return SessionStore.write(Session(0, files))
    }

    /** File > Rename: a new name, and for a stored file its new address. */
    fun renamed(doc: Doc, uri: Uri?, name: String) {
        if (uri != null) doc.uri = uri
        doc.name = name
        if (doc.language === languages.normal) {
            val detected = languages.forFileName(name)
            if (detected !== languages.normal) {
                doc.language = detected
                doc.lexerApplied = false
                if (doc === active) {
                    applyLanguage(doc)
                    editor.send(Sci.SCI_COLOURISE, 0, -1)
                }
            }
        }
        onTabsChanged?.invoke()
    }

    fun saveSession() {
        try {
            sessionFile.writeText(SessionStore.write(sessionNow(withBackups = true)))
        } catch (e: IOException) {
            // Nothing useful to do; the next start begins with an empty tab.
        }
    }

    fun readSession(): Session = try {
        if (sessionFile.exists()) SessionStore.read(sessionFile.readText()) else Session(0, emptyList())
    } catch (e: IOException) {
        Session(0, emptyList())
    }

    /** Unsaved text kept for a session entry, or null if it had none or the file has gone. */
    fun readBackup(entry: SessionEntry): ByteArray? {
        val name = entry.backup ?: return null
        val file = File(backupDir, name)
        return try {
            if (file.exists()) file.readBytes() else null
        } catch (e: IOException) {
            null
        }
    }

    /** Adds a tab from a session entry whose content has already been read. */
    fun restore(entry: SessionEntry, file: LoadedFile, fromBackup: Boolean): Doc {
        val uri = entry.location?.let { Uri.parse(it) }
        val doc = open(uri, file, languages.byName(entry.language))
        doc.untitledNumber = if (uri == null) entry.untitledNumber.coerceAtLeast(1) else 0
        if (fromBackup) {
            doc.forcedDirty = true
            doc.backupName = entry.backup
            doc.changedSinceBackup = false
        }
        val length = editor.send(Sci.SCI_GETLENGTH)
        doc.anchor = entry.anchor.coerceIn(0, length)
        doc.caret = entry.caret.coerceIn(0, length)
        doc.firstDocLine = entry.firstVisibleLine
        doc.xOffset = entry.xOffset.toLong()
        doc.colour = entry.tabColour
        doc.pinned = entry.pinned
        restoreViewState(doc)
        onTabsChanged?.invoke()
        return doc
    }

    /** Removes backup files no tab refers to any more. */
    fun pruneBackups() {
        val used = docs.mapNotNull { it.backupName }.toSet()
        backupDir.listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    // endregion
}
