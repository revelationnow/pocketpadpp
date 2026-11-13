package io.github.revelationnow.pocketpadpp.core

import java.security.MessageDigest

/** Tools > MD5 / SHA-1 / SHA-256 / SHA-512. */
object Hashes {
    /** Menu names with the names the platform knows the algorithms by. */
    val ALGORITHMS = listOf("MD5" to "MD5", "SHA-1" to "SHA-1", "SHA-256" to "SHA-256", "SHA-512" to "SHA-512")

    fun of(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    /** The digest of a text as UTF-8, or one digest per non-empty line when [eachLine] is set. */
    fun ofText(algorithm: String, text: String, eachLine: Boolean): String {
        if (!eachLine) return if (text.isEmpty()) "" else of(algorithm, text.toByteArray(Charsets.UTF_8))
        return text.lineSequence().filter { it.isNotEmpty() }
            .joinToString("\n") { of(algorithm, it.toByteArray(Charsets.UTF_8)) }
    }
}
