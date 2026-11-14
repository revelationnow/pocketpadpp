package io.github.revelationnow.pocketpadpp.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** A file or folder below a workspace folder. */
class FolderNode(val uri: Uri, val name: String, val isFolder: Boolean)

/**
 * Reads folders for Folder as Workspace. A folder the user picked comes as a "tree" from Android's Storage
 * Access Framework, and everything below it is addressed through that tree, which is what carries the
 * permission. Plain file paths work too, for the app's own storage.
 */
object FolderTree {
    /** The folder itself as a node, from the address the folder picker returned. */
    fun root(context: Context, picked: Uri): FolderNode {
        if (picked.scheme == "file") return FolderNode(picked, File(picked.path ?: "").name.ifEmpty { "/" }, true)
        val document = DocumentsContract.buildDocumentUriUsingTree(picked, DocumentsContract.getTreeDocumentId(picked))
        val name = runCatching { FileIo.displayName(context, document) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: DocumentsContract.getTreeDocumentId(picked).substringAfterLast('/').substringAfterLast(':').ifEmpty { "Folder" }
        return FolderNode(document, name, true)
    }

    /** What a folder holds: folders first, then files, each by name. An unreadable folder is empty. */
    fun children(context: Context, folder: Uri): List<FolderNode> {
        val found = ArrayList<FolderNode>()
        try {
            if (folder.scheme == "file") {
                File(folder.path ?: return found).listFiles()?.forEach { found += FolderNode(Uri.fromFile(it), it.name, it.isDirectory) }
            } else {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        found += FolderNode(
                            DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0)), name,
                            cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Shown as an empty folder.
        }
        return found.sortedWith(compareByDescending<FolderNode> { it.isFolder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * The folder a document sits in, for showing it in the system's file manager. Only Android's own storage
     * provider reveals this, through the form of its document ids ("primary:Notes/todo.txt").
     */
    fun parentOf(document: Uri): Uri? {
        if (document.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getDocumentId(document) }.getOrNull() ?: return null
        val path = id.substringAfter(':', "")
        if (':' !in id) return null
        val parent = id.substringBefore(':') + ":" + path.substringBeforeLast('/', "")
        return DocumentsContract.buildDocumentUri(document.authority, parent)
    }
}
