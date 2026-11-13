package io.github.revelationnow.pocketpadpp.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** One open tab as remembered between runs. */
data class SessionEntry(
    /** Where the file lives (a content URI on Android), or null for a document that was never saved. */
    val location: String?,
    val name: String,
    val language: String,
    val encoding: String,
    val eolMode: Int,
    val caret: Long = 0,
    val anchor: Long = 0,
    val firstVisibleLine: Long = 0,
    val xOffset: Int = 0,
    /** File holding unsaved content, relative to the backup directory, or null if the tab had no changes. */
    val backup: String? = null,
    val untitledNumber: Int = 0,
    /** 0 for none, or 1 to 5 for one of the tab colours. */
    val tabColour: Int = 0,
)

data class Session(val activeIndex: Int, val entries: List<SessionEntry>)

/**
 * Reads and writes the list of open tabs. The layout follows Notepad++'s session.xml
 * (NotepadPlus / Session / mainView / File) so that its session files can be supported later.
 */
object SessionStore {
    private fun escape(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\n' -> append("&#10;")
                '\r' -> append("&#13;")
                '\t' -> append("&#9;")
                else -> append(ch)
            }
        }
    }

    fun write(session: Session): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<NotepadPlus>\n  <Session activeView=\"0\">\n")
        append("    <mainView activeIndex=\"").append(session.activeIndex).append("\">\n")
        for (e in session.entries) {
            append("      <File")
            fun attribute(name: String, value: Any?) {
                if (value != null) append(' ').append(name).append("=\"").append(escape(value.toString())).append('"')
            }
            attribute("filename", e.name)
            attribute("location", e.location)
            attribute("lang", e.language)
            attribute("encoding", e.encoding)
            attribute("eol", e.eolMode)
            attribute("startPos", e.anchor)
            attribute("endPos", e.caret)
            attribute("firstVisibleLine", e.firstVisibleLine)
            attribute("xOffset", e.xOffset)
            attribute("tabColourId", e.tabColour)
            attribute("backupFilePath", e.backup)
            attribute("untitled", e.untitledNumber)
            append(" />\n")
        }
        append("    </mainView>\n  </Session>\n</NotepadPlus>\n")
    }

    /** Returns an empty session if the text is not a readable session file. */
    fun read(xml: String): Session {
        val entries = ArrayList<SessionEntry>()
        var active = 0
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(InputSource(StringReader(xml)), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    when (qName) {
                        "mainView" -> active = a.getValue("activeIndex")?.toIntOrNull() ?: 0
                        "File" -> entries += SessionEntry(
                            location = a.getValue("location"),
                            name = a.getValue("filename") ?: "",
                            language = a.getValue("lang") ?: "normal",
                            encoding = a.getValue("encoding") ?: TextEncodings.UTF8.id,
                            eolMode = a.getValue("eol")?.toIntOrNull() ?: Eol.LF.sciMode,
                            caret = a.getValue("endPos")?.toLongOrNull() ?: 0,
                            anchor = a.getValue("startPos")?.toLongOrNull() ?: 0,
                            firstVisibleLine = a.getValue("firstVisibleLine")?.toLongOrNull() ?: 0,
                            xOffset = a.getValue("xOffset")?.toIntOrNull() ?: 0,
                            tabColour = a.getValue("tabColourId")?.toIntOrNull()?.coerceIn(0, 5) ?: 0,
                            backup = a.getValue("backupFilePath"),
                            untitledNumber = a.getValue("untitled")?.toIntOrNull() ?: 0,
                        )
                    }
                }
            })
        } catch (e: Exception) {
            return Session(0, emptyList())
        }
        return Session(active.coerceIn(0, maxOf(0, entries.size - 1)), entries)
    }
}
