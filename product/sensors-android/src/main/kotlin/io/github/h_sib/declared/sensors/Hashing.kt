package io.github.h_sib.declared.sensors

import java.security.MessageDigest

internal object Hashing {
    /** SHA-256 as lowercase hex. Used for signing-certificate identity (CONCEPT.md, SHA-256 use 3). */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
