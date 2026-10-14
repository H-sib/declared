package io.github.h_sib.declared.core.state

/**
 * The bytes on disk around an encrypted state: a magic byte, a format version, the length-prefixed IV, then the AES-GCM
 * ciphertext (which ends in its authentication tag). Kept in `core` and free of any crypto so that every way a file can be
 * wrong (empty, truncated, someone else's file, a newer format) is tested without a device. The Android side supplies
 * the IV and ciphertext and checks the tag; a changed byte anywhere in the ciphertext fails that check.
 */
object SealedFile {
    private const val MAGIC = 0xCA
    const val FORMAT = 1

    /** Bound into the encryption as associated data, so this file cannot be swapped for another sealed with the same key. */
    const val PURPOSE = "declared-state-v1"

    /** The same for the history file, which is sealed under a key of its own. */
    const val PURPOSE_LEDGER = "declared-ledger-v1"

    class FormatException(message: String) : Exception(message)

    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size in 1..255) { "iv length" }
        return byteArrayOf(MAGIC.toByte(), FORMAT.toByte(), iv.size.toByte()) + iv + ciphertext
    }

    /** @return the IV and the ciphertext. Throws [FormatException] with a plain reason if the bytes are not a sealed state. */
    fun unpack(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        if (bytes.isEmpty()) throw FormatException("the file is empty")
        if (bytes.size < 3) throw FormatException("the file is too short to be a saved baseline")
        if ((bytes[0].toInt() and 0xFF) != MAGIC) throw FormatException("the file is not a saved baseline of this app")
        val format = bytes[1].toInt() and 0xFF
        if (format != FORMAT) throw FormatException("the file uses format $format, which this version does not know")
        val ivLength = bytes[2].toInt() and 0xFF
        if (ivLength == 0 || bytes.size < 3 + ivLength + 16) throw FormatException("the file is truncated")
        return bytes.copyOfRange(3, 3 + ivLength) to bytes.copyOfRange(3 + ivLength, bytes.size)
    }
}
