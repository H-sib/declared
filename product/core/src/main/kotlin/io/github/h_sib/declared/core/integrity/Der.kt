package io.github.h_sib.declared.core.integrity

/** One DER element. */
class Tlv(val tagClass: Int, val constructed: Boolean, val tag: Int, val content: ByteArray) {
    fun children(): List<Tlv> = Der.parseAll(content)
}

/**
 * Minimal DER reader, enough for the Android key attestation extension. Pure Kotlin, so it runs in JVM tests.
 * Every length is checked against the data: malformed input fails with an exception, never with a wrong answer.
 */
object Der {
    fun parseAll(b: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var off = 0
        while (off < b.size) {
            val (tlv, next) = parseOne(b, off)
            out.add(tlv)
            off = next
        }
        return out
    }

    private fun parseOne(b: ByteArray, start: Int): Pair<Tlv, Int> {
        var i = start
        require(i < b.size) { "DER element starts past the end" }
        val first = b[i++].toInt() and 0xFF
        val tagClass = first shr 6
        val constructed = (first and 0x20) != 0
        var tag = first and 0x1F
        if (tag == 0x1F) {
            tag = 0
            var count = 0
            do {
                require(i < b.size) { "DER tag runs past the end" }
                val x = b[i++].toInt() and 0xFF
                tag = (tag shl 7) or (x and 0x7F)
                count += 1
                require(count <= 4) { "DER tag number too long" }
            } while ((x and 0x80) != 0)
        }
        require(i < b.size) { "DER length missing" }
        var len = b[i++].toInt() and 0xFF
        if ((len and 0x80) != 0) {
            val n = len and 0x7F
            require(n in 1..4) { "unsupported DER length" }
            require(i + n <= b.size) { "DER length runs past the end" }
            len = 0
            repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xFF) }
        }
        require(len >= 0 && i + len <= b.size) { "DER length out of range" }
        return Tlv(tagClass, constructed, tag, b.copyOfRange(i, i + len)) to (i + len)
    }

    /** A DER INTEGER as a signed number (up to eight bytes). */
    fun intValue(content: ByteArray): Long {
        var v = if (content.isNotEmpty() && content[0] < 0) -1L else 0L
        for (x in content) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }
}

fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
