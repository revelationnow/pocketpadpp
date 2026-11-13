package io.github.revelationnow.pocketpadpp.core

/**
 * The slice of a Scintilla editor that application logic needs.
 * Implemented by the Android view and, in tests, directly on top of the native layer.
 */
interface SciApi {
    fun send(msg: Int, wParam: Long = 0, lParam: Long = 0): Long

    /** Sends a message whose lParam is a byte string; a NUL terminator is added. */
    fun sendBytes(msg: Int, wParam: Long, bytes: ByteArray): Long

    /** Sends a message whose wParam and lParam are both strings. */
    fun sendStrings(msg: Int, first: String, second: String): Long

    /** Document bytes in [start, end). */
    fun range(start: Long, end: Long): ByteArray

    /** Attaches a Lexilla lexer to the current document; an empty name removes it. */
    fun setLexer(name: String): Boolean
}

fun SciApi.sendText(msg: Int, wParam: Long, text: String): Long = sendBytes(msg, wParam, text.toByteArray(Charsets.UTF_8))
