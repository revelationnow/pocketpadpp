package io.github.revelationnow.pocketpadpp.core

import io.github.revelationnow.pocketpadpp.scintilla.Sci

/**
 * View > Show Symbol > Show Non-Printing Characters and Show Control Characters & Unicode EOL:
 * characters that take no space or look like a plain space are drawn as small labelled boxes.
 */
object Invisibles {
    /** Spaces and joiners that cannot be told from nothing or from an ordinary space, with Notepad++'s labels. */
    val NON_PRINTING = listOf(
        0x00A0 to "NBSP", 0x00AD to "SHY", 0x1680 to "OSPM", 0x2000 to "NQSP", 0x2001 to "MQSP", 0x2002 to "ENSP",
        0x2003 to "EMSP", 0x2004 to "3/MSP", 0x2005 to "4/MSP", 0x2006 to "6/MSP", 0x2007 to "FSP", 0x2008 to "PSP",
        0x2009 to "THSP", 0x200A to "HSP", 0x200B to "ZWSP", 0x200C to "ZWNJ", 0x200D to "ZWJ", 0x202F to "NNBSP",
        0x205F to "MMSP", 0x2060 to "WJ", 0x3000 to "IDSP", 0xFEFF to "ZWNBSP",
    )

    /** The C1 control characters and the Unicode line ends, which Scintilla labels by default. */
    private val CONTROL = (0x80..0x9F) + listOf(0x2028, 0x2029)

    private fun encoded(codePoint: Int) = String(Character.toChars(codePoint))

    fun apply(sci: SciApi, nonPrinting: Boolean, control: Boolean) {
        // Back to Scintilla's own labels first, then take away or add as chosen.
        sci.send(Sci.SCI_CLEARALLREPRESENTATIONS)
        if (!control) for (codePoint in CONTROL) sci.sendStrings(Sci.SCI_CLEARREPRESENTATION, encoded(codePoint), "")
        if (nonPrinting) for ((codePoint, label) in NON_PRINTING) sci.sendStrings(Sci.SCI_SETREPRESENTATION, encoded(codePoint), label)
    }
}
