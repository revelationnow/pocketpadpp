package io.github.revelationnow.pocketpadpp.core

/**
 * The bytes of a file as the Hex Editor works on them: overwritten, inserted and deleted one at a time or in
 * runs, with every change undoable. Nothing here knows about text or encodings.
 */
class HexBuffer(initial: ByteArray) {
    private var data = initial.copyOf(maxOf(16, initial.size))
    var size: Int = initial.size
        private set

    private class Change(val offset: Int, val removed: ByteArray, val inserted: ByteArray)

    private val done = ArrayList<Change>()
    private val undone = ArrayList<Change>()

    // The number of changes that were in effect when the bytes were last saved; -1 once that state cannot be reached.
    private var savedAt = 0

    val modified: Boolean get() = done.size != savedAt
    val canUndo: Boolean get() = done.isNotEmpty()
    val canRedo: Boolean get() = undone.isNotEmpty()

    operator fun get(offset: Int): Int = data[offset].toInt() and 0xFF

    fun toByteArray(): ByteArray = data.copyOf(size)

    fun markSaved() {
        savedAt = done.size
    }

    private fun splice(offset: Int, remove: Int, insert: ByteArray) {
        val newSize = size - remove + insert.size
        if (newSize > data.size) data = data.copyOf(maxOf(newSize, data.size * 2))
        System.arraycopy(data, offset + remove, data, offset + insert.size, size - offset - remove)
        System.arraycopy(insert, 0, data, offset, insert.size)
        size = newSize
    }

    private fun apply(offset: Int, remove: Int, insert: ByteArray) {
        val removed = data.copyOfRange(offset, offset + remove)
        splice(offset, remove, insert)
        if (savedAt > done.size) savedAt = -1
        done += Change(offset, removed, insert)
        undone.clear()
    }

    /** Replaces the byte at an offset; at the end of the buffer it adds one. */
    fun overwrite(offset: Int, value: Int) {
        if (offset < 0 || offset > size) return
        apply(offset, if (offset < size) 1 else 0, byteArrayOf(value.toByte()))
    }

    /**
     * A hex digit typed over one half of a byte: the high half first, then the low. Typing at the end of the
     * buffer starts a new byte.
     */
    fun typeDigit(offset: Int, high: Boolean, digit: Int) {
        val now = if (offset < size) get(offset) else 0
        overwrite(offset, if (high) (digit shl 4) or (now and 0x0F) else (now and 0xF0) or digit)
    }

    fun insert(offset: Int, bytes: ByteArray) {
        if (offset < 0 || offset > size || bytes.isEmpty()) return
        apply(offset, 0, bytes)
    }

    fun delete(offset: Int, count: Int = 1) {
        if (offset < 0 || count <= 0 || offset + count > size) return
        apply(offset, count, ByteArray(0))
    }

    /** Takes the last change back. Returns the offset it was at, or -1 if there was none. */
    fun undo(): Int {
        val change = done.removeLastOrNull() ?: return -1
        splice(change.offset, change.inserted.size, change.removed)
        undone += change
        return change.offset
    }

    fun redo(): Int {
        val change = undone.removeLastOrNull() ?: return -1
        splice(change.offset, change.removed.size, change.inserted)
        done += change
        return change.offset
    }

    /** The offset of the next (or previous) place the pattern is found, going round at the end; -1 if nowhere. */
    fun find(pattern: ByteArray, from: Int, forward: Boolean = true): Int {
        if (pattern.isEmpty() || pattern.size > size) return -1
        val last = size - pattern.size
        fun at(offset: Int): Boolean {
            for (i in pattern.indices) if (data[offset + i] != pattern[i]) return false
            return true
        }
        val count = last + 1
        var offset = ((if (forward) from else from) % count + count) % count
        repeat(count) {
            if (at(offset)) return offset
            offset = if (forward) (offset + 1) % count else (offset - 1 + count) % count
        }
        return -1
    }

    companion object {
        private const val DIGITS = "0123456789ABCDEF"

        fun hex(value: Int): String = "${DIGITS[(value shr 4) and 0xF]}${DIGITS[value and 0xF]}"

        fun digit(c: Char): Int = DIGITS.indexOf(c.uppercaseChar())

        /** How a byte shows in the text column: itself if it is a printable ASCII character, a dot otherwise. */
        fun shown(value: Int): Char = if (value in 0x20..0x7E) value.toChar() else '.'

        /**
         * What to search for: hex digits in pairs, spaces between them or not ("DE AD", "dead", "0xDE 0xAD"),
         * or a text in double quotes, taken as its UTF-8 bytes. Null when it is neither.
         */
        fun pattern(text: String): ByteArray? {
            val trimmed = text.trim()
            if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
                return trimmed.substring(1, trimmed.length - 1).toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
            }
            val digits = trimmed.replace("0x", "", ignoreCase = true).filterNot { it.isWhitespace() || it == ',' }
            if (digits.isEmpty() || digits.length % 2 != 0 || digits.any { digit(it) < 0 }) return null
            return ByteArray(digits.length / 2) { ((digit(digits[2 * it]) shl 4) or digit(digits[2 * it + 1])).toByte() }
        }

        /** An offset typed as decimal, or as hex with 0x or a trailing h. Null if it is neither. */
        fun offset(text: String): Int? {
            val trimmed = text.trim()
            return when {
                trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2).toIntOrNull(16)
                trimmed.endsWith("h", ignoreCase = true) -> trimmed.dropLast(1).toIntOrNull(16)
                else -> trimmed.toIntOrNull()
            }?.takeIf { it >= 0 }
        }
    }
}
