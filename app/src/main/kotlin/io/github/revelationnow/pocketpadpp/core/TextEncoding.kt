package io.github.revelationnow.pocketpadpp.core

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A file encoding as shown in the Encoding menu.
 * Documents are always UTF-8 in memory; this says how the bytes on disk are read and written.
 */
data class TextEncoding(
    val id: String,
    val label: String,
    val charsetName: String,
    val bom: Boolean = false,
    /** Submenu under "Character sets", or null for the top-level Unicode entries. */
    val group: String? = null,
) {
    val isSupported: Boolean
        get() = try {
            Charset.isSupported(charsetName)
        } catch (e: IllegalArgumentException) {
            false
        }

    val isUtf8: Boolean get() = charsetName == "UTF-8"
}

object TextEncodings {
    val UTF8 = TextEncoding("utf-8", "UTF-8", "UTF-8")
    val UTF8_BOM = TextEncoding("utf-8-bom", "UTF-8-BOM", "UTF-8", bom = true)
    val UTF16_BE = TextEncoding("utf-16be-bom", "UTF-16 BE BOM", "UTF-16BE", bom = true)
    val UTF16_LE = TextEncoding("utf-16le-bom", "UTF-16 LE BOM", "UTF-16LE", bom = true)
    val UTF16_BE_NO_BOM = TextEncoding("utf-16be", "UTF-16 BE", "UTF-16BE")
    val UTF16_LE_NO_BOM = TextEncoding("utf-16le", "UTF-16 LE", "UTF-16LE")

    /** Notepad++ calls the system code page "ANSI"; Android has none, so Western European stands in. */
    val ANSI = TextEncoding("ansi", "ANSI", "windows-1252")

    val unicode: List<TextEncoding> = listOf(ANSI, UTF8, UTF8_BOM, UTF16_BE, UTF16_LE)

    private fun set(group: String, label: String, charset: String) =
        TextEncoding(charset.lowercase(), label, charset, group = group)

    /** Notepad++'s "Character sets" menu, in its order. */
    val characterSets: List<TextEncoding> = listOf(
        set("Arabic", "ISO 8859-6", "ISO-8859-6"),
        set("Arabic", "OEM 720", "IBM720"),
        set("Arabic", "Windows-1256", "windows-1256"),
        set("Baltic", "ISO 8859-4", "ISO-8859-4"),
        set("Baltic", "ISO 8859-13", "ISO-8859-13"),
        set("Baltic", "OEM 775", "IBM775"),
        set("Baltic", "Windows-1257", "windows-1257"),
        set("Celtic", "ISO 8859-14", "ISO-8859-14"),
        set("Cyrillic", "ISO 8859-5", "ISO-8859-5"),
        set("Cyrillic", "KOI8-R", "KOI8-R"),
        set("Cyrillic", "KOI8-U", "KOI8-U"),
        set("Cyrillic", "Macintosh", "x-MacCyrillic"),
        set("Cyrillic", "OEM 855", "IBM855"),
        set("Cyrillic", "OEM 866", "IBM866"),
        set("Cyrillic", "Windows-1251", "windows-1251"),
        set("Central European", "OEM 852", "IBM852"),
        set("Central European", "Windows-1250", "windows-1250"),
        set("Chinese", "Big5 (Traditional)", "Big5"),
        set("Chinese", "GB2312 (Simplified)", "GB2312"),
        set("Eastern European", "ISO 8859-2", "ISO-8859-2"),
        set("Greek", "ISO 8859-7", "ISO-8859-7"),
        set("Greek", "OEM 737", "x-IBM737"),
        set("Greek", "OEM 869", "IBM869"),
        set("Greek", "Windows-1253", "windows-1253"),
        set("Hebrew", "ISO 8859-8", "ISO-8859-8"),
        set("Hebrew", "OEM 862", "IBM862"),
        set("Hebrew", "Windows-1255", "windows-1255"),
        set("Japanese", "Shift-JIS", "Shift_JIS"),
        set("Korean", "Windows 949", "x-windows-949"),
        set("Korean", "EUC-KR", "EUC-KR"),
        set("North European", "OEM 861 : Icelandic", "IBM861"),
        set("North European", "OEM 865 : Nordic", "IBM865"),
        set("Thai", "TIS-620", "TIS-620"),
        set("Turkish", "ISO 8859-3", "ISO-8859-3"),
        set("Turkish", "ISO 8859-9", "ISO-8859-9"),
        set("Turkish", "OEM 857", "IBM857"),
        set("Turkish", "Windows-1254", "windows-1254"),
        set("Western European", "ISO 8859-1", "ISO-8859-1"),
        set("Western European", "ISO 8859-15", "ISO-8859-15"),
        set("Western European", "OEM 850", "IBM850"),
        set("Western European", "OEM 858", "IBM00858"),
        set("Western European", "OEM 860 : Portuguese", "IBM860"),
        set("Western European", "OEM 863 : French", "IBM863"),
        set("Western European", "OEM-US : CP437", "IBM437"),
        set("Western European", "Windows-1252", "windows-1252"),
        set("Vietnamese", "Windows-1258", "windows-1258"),
    )

    private val all = unicode + listOf(UTF16_BE_NO_BOM, UTF16_LE_NO_BOM) + characterSets

    fun byId(id: String?): TextEncoding? = all.firstOrNull { it.id == id }

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val BOM_UTF16_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun bomOf(encoding: TextEncoding): ByteArray = when {
        !encoding.bom -> ByteArray(0)
        encoding.charsetName == "UTF-8" -> BOM_UTF8
        encoding.charsetName == "UTF-16BE" -> BOM_UTF16_BE
        encoding.charsetName == "UTF-16LE" -> BOM_UTF16_LE
        else -> ByteArray(0)
    }

    /**
     * Guesses how a file is encoded: byte order marks first, then valid UTF-8 (which includes plain ASCII),
     * then UTF-16 without a mark, and otherwise the ANSI fallback.
     */
    fun detect(bytes: ByteArray): TextEncoding = when {
        bytes.startsWith(BOM_UTF8) -> UTF8_BOM
        bytes.startsWith(BOM_UTF16_LE) -> UTF16_LE
        bytes.startsWith(BOM_UTF16_BE) -> UTF16_BE
        else -> {
            val utf16 = looksLikeUtf16(bytes)
            when {
                utf16 != null -> utf16
                isValidUtf8(bytes) -> UTF8
                else -> ANSI
            }
        }
    }

    /** UTF-16 text in a Latin script has a NUL in every other byte. */
    private fun looksLikeUtf16(bytes: ByteArray): TextEncoding? {
        val sample = minOf(bytes.size, 4096) and 1.inv()
        if (sample < 4) return null
        var evenZero = 0
        var oddZero = 0
        for (i in 0 until sample) {
            if (bytes[i].toInt() == 0) {
                if (i and 1 == 0) evenZero++ else oddZero++
            }
        }
        val half = sample / 2
        return when {
            oddZero > half * 0.6 && evenZero < half * 0.1 -> UTF16_LE_NO_BOM
            evenZero > half * 0.6 && oddZero < half * 0.1 -> UTF16_BE_NO_BOM
            else -> null
        }
    }

    fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b < 0x80 -> 0
                b in 0xC2..0xDF -> 1
                b in 0xE0..0xEF -> 2
                b in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + extra >= n) return false
            if (extra >= 1) {
                val b1 = bytes[i + 1].toInt() and 0xFF
                val low = when (b) { 0xE0 -> 0xA0; 0xF0 -> 0x90; else -> 0x80 }
                val high = when (b) { 0xED -> 0x9F; 0xF4 -> 0x8F; else -> 0xBF }
                if (b1 < low || b1 > high) return false
                for (k in 2..extra) {
                    if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
                }
            }
            i += extra + 1
        }
        return true
    }

    /** File bytes to the UTF-8 bytes Scintilla holds. Undecodable input becomes U+FFFD. */
    fun toUtf8(fileBytes: ByteArray, encoding: TextEncoding): ByteArray {
        val bom = bomOf(encoding)
        val skip = if (bom.isNotEmpty() && fileBytes.startsWith(bom)) bom.size else 0
        if (encoding.isUtf8 && isValidUtf8(fileBytes)) {
            return if (skip == 0) fileBytes else fileBytes.copyOfRange(skip, fileBytes.size)
        }
        val decoder = Charset.forName(encoding.charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = decoder.decode(ByteBuffer.wrap(fileBytes, skip, fileBytes.size - skip))
        return chars.toString().toByteArray(Charsets.UTF_8)
    }

    /** Scintilla's UTF-8 bytes to file bytes. Characters the encoding lacks become '?'. */
    fun fromUtf8(utf8: ByteArray, encoding: TextEncoding): ByteArray {
        val bom = bomOf(encoding)
        if (encoding.isUtf8) {
            return if (bom.isEmpty()) utf8 else bom + utf8
        }
        val encoder = Charset.forName(encoding.charsetName).newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val encoded = encoder.encode(CharBuffer.wrap(String(utf8, Charsets.UTF_8)))
        val body = ByteArray(encoded.remaining()).also { encoded.get(it) }
        return if (bom.isEmpty()) body else bom + body
    }

    /** True when saving in this encoding would lose characters. */
    fun canEncode(utf8: ByteArray, encoding: TextEncoding): Boolean {
        if (encoding.charsetName.startsWith("UTF-")) return true
        return Charset.forName(encoding.charsetName).newEncoder().canEncode(String(utf8, Charsets.UTF_8))
    }
}

/** Line ending styles, with Scintilla's SC_EOL_* values. */
enum class Eol(val sciMode: Int, val label: String, val longLabel: String) {
    CRLF(0, "CRLF", "Windows (CR LF)"),
    CR(1, "CR", "Macintosh (CR)"),
    LF(2, "LF", "Unix (LF)");

    companion object {
        fun fromSciMode(mode: Int): Eol = entries.firstOrNull { it.sciMode == mode } ?: LF

        /** The line ending a UTF-8 text mostly uses, or null if it has no line breaks. */
        fun detect(utf8: ByteArray, limit: Int = 1 shl 20): Eol? {
            var crlf = 0
            var cr = 0
            var lf = 0
            val n = minOf(utf8.size, limit)
            var i = 0
            while (i < n) {
                when (utf8[i].toInt()) {
                    0x0D -> if (i + 1 < utf8.size && utf8[i + 1].toInt() == 0x0A) { crlf++; i++ } else cr++
                    0x0A -> lf++
                }
                i++
            }
            return when {
                crlf == 0 && cr == 0 && lf == 0 -> null
                crlf >= lf && crlf >= cr -> CRLF
                lf >= cr -> LF
                else -> CR
            }
        }
    }
}
