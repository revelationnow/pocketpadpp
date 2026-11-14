package io.github.revelationnow.pocketpadpp.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/** A project, a folder in it, or a file: one node of a Project Panel. Folders here are groups, not directories. */
class ProjectNode(
    val kind: Kind,
    var name: String,
    /** Where a file is: a path in Notepad++'s own workspace files, a document address in ones made here. */
    var path: String = "",
) {
    enum class Kind { PROJECT, FOLDER, FILE }

    val children = ArrayList<ProjectNode>()
    var parent: ProjectNode? = null
        private set

    fun add(child: ProjectNode, index: Int = children.size): ProjectNode {
        child.parent = this
        children.add(index.coerceIn(0, children.size), child)
        return child
    }

    fun remove(child: ProjectNode) {
        if (children.remove(child)) child.parent = null
    }

    /** Moves the node one place among its siblings. Returns false at either end. */
    fun move(siblings: MutableList<ProjectNode>, by: Int): Boolean {
        val from = siblings.indexOf(this)
        val to = from + by
        if (from < 0 || to !in siblings.indices) return false
        siblings.removeAt(from)
        siblings.add(to, this)
        return true
    }

    /** Every file below this node. */
    fun files(): List<ProjectNode> = if (kind == Kind.FILE) listOf(this) else children.flatMap { it.files() }
}

/** Reads and writes Notepad++'s workspace files, the content of a Project Panel. */
object ProjectStore {
    /** The projects of a workspace file. A file that is not one gives an empty list. */
    fun read(xml: String): MutableList<ProjectNode> {
        val projects = ArrayList<ProjectNode>()
        if (xml.isBlank()) return projects
        try {
            val stack = ArrayList<ProjectNode>()
            SAXParserFactory.newInstance().newSAXParser().parse(xml.byteInputStream(Charsets.UTF_8), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    val name = a.getValue("name") ?: ""
                    val node = when (qName) {
                        "Project" -> ProjectNode(ProjectNode.Kind.PROJECT, name)
                        "Folder" -> ProjectNode(ProjectNode.Kind.FOLDER, name)
                        // Notepad++ shows a file under the last part of its path; an address has no such part, so the name is kept beside it.
                        "File" -> ProjectNode(ProjectNode.Kind.FILE, a.getValue("label") ?: name.substringAfterLast('\\').substringAfterLast('/'), name)
                        else -> return
                    }
                    when {
                        node.kind == ProjectNode.Kind.PROJECT -> projects += node
                        stack.isEmpty() -> return
                        else -> stack.last().add(node)
                    }
                    stack += node
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if ((qName == "Project" || qName == "Folder" || qName == "File") && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                }
            })
        } catch (e: Exception) {
            return ArrayList()
        }
        return projects
    }

    fun write(projects: List<ProjectNode>): String {
        fun escape(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<NotepadPlus>\n")
        fun node(n: ProjectNode, indent: String) {
            when (n.kind) {
                ProjectNode.Kind.FILE -> {
                    out.append(indent).append("<File name=\"").append(escape(n.path)).append('"')
                    if (n.name != n.path.substringAfterLast('\\').substringAfterLast('/')) out.append(" label=\"").append(escape(n.name)).append('"')
                    out.append(" />\n")
                }
                else -> {
                    val tag = if (n.kind == ProjectNode.Kind.PROJECT) "Project" else "Folder"
                    out.append(indent).append('<').append(tag).append(" name=\"").append(escape(n.name)).append('"')
                    if (n.children.isEmpty()) out.append(" />\n") else {
                        out.append(">\n")
                        for (child in n.children) node(child, "$indent    ")
                        out.append(indent).append("</").append(tag).append(">\n")
                    }
                }
            }
        }
        for (project in projects) node(project, "    ")
        return out.append("</NotepadPlus>\n").toString()
    }
}
