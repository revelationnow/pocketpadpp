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
    }

    // region Tabs

    private fun nextUntitledNumber(): Int {
        var n = 1
        while (docs.any { it.untitledNumber == n }) n++
        return n
    }

    private fun createDoc(): Doc = Doc(nextId++, editor.send(Sci.SCI_CREATEDOCUMENT), languages.normal).also { docs += it }

    fun newDocument(): Doc {
        val number = nextUntitledNumber()
        val doc = createDoc().apply {
            untitledNumber = number
            name = "new $number"
        }
        activate(doc)
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
        doc.folds = EditOps.foldedLines(editor)
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
            applyLanguage(doc)
            EditOps.restoreFolds(editor, doc.folds)
            restoreViewState(doc)
            editor.documentSwitched()
        }
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
    }

    fun setLanguage(language: Language) {
        active.language = language
        active.lexerApplied = false
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
        editor.send(Sci.SCI_SETUNDOCOLLECTION, 0)
        editor.send(Sci.SCI_CLEARALL)
        editor.sendBytes(Sci.SCI_ADDTEXT, utf8.size.toLong(), utf8)
        editor.send(Sci.SCI_SETUNDOCOLLECTION, 1)
        editor.send(Sci.SCI_EMPTYUNDOBUFFER)
        editor.send(Sci.SCI_SETSAVEPOINT)
        if (eol != null) editor.send(Sci.SCI_SETEOLMODE, eol.sciMode.toLong())
        editor.send(Sci.SCI_GOTOPOS, 0)
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

    fun saveSession() {
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
                backup = doc.backupName,
                untitledNumber = doc.untitledNumber,
            )
        }
        try {
            sessionFile.writeText(SessionStore.write(Session(docs.indexOf(active), entries)))
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
