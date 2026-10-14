package lab.probe

import org.json.JSONObject

/** One DER element. */
class Tlv(val tagClass: Int, val constructed: Boolean, val tag: Int, val content: ByteArray) {
    fun children(): List<Tlv> = Der.parseAll(content)
}

/** Minimal DER reader, enough for the Android key attestation extension. Pure Kotlin so it can be unit tested on the JVM. */
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
        val first = b[i++].toInt() and 0xFF
        val tagClass = first shr 6
        val constructed = (first and 0x20) != 0
        var tag = first and 0x1F
        if (tag == 0x1F) {
            tag = 0
            do {
                val x = b[i++].toInt() and 0xFF
                tag = (tag shl 7) or (x and 0x7F)
            } while ((x and 0x80) != 0)
        }
        var len = b[i++].toInt() and 0xFF
        if ((len and 0x80) != 0) {
            val n = len and 0x7F
            require(n in 1..4) { "unsupported DER length" }
            len = 0
            repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xFF) }
        }
        require(len >= 0 && i + len <= b.size) { "DER length out of range" }
        return Tlv(tagClass, constructed, tag, b.copyOfRange(i, i + len)) to (i + len)
    }

    fun intValue(content: ByteArray): Long {
        var v = if (content.isNotEmpty() && content[0] < 0) -1L else 0L
        for (x in content) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }
}

data class Attestation(
    val attestationVersion: Long,
    val attestationSecurityLevel: Long,
    val keyMintVersion: Long,
    val keyMintSecurityLevel: Long,
    val challengeHex: String,
    val verifiedBootState: Long?,
    val deviceLocked: Boolean?,
    val verifiedBootKeyHex: String?,
    val verifiedBootHashHex: String?,
    val osVersion: Long?,
    val osPatchLevel: Long?,
    val vendorPatchLevel: Long?,
    val bootPatchLevel: Long?,
) {
    fun toJson(): JSONObject = jo(
        "attestationVersion" to attestationVersion,
        "attestationSecurityLevel" to attestationSecurityLevel,
        "keyMintVersion" to keyMintVersion,
        "keyMintSecurityLevel" to keyMintSecurityLevel,
        "challengeHex" to challengeHex,
        "verifiedBootState" to verifiedBootState,
        "deviceLocked" to deviceLocked,
        "verifiedBootKeyHex" to verifiedBootKeyHex,
        "verifiedBootHashHex" to verifiedBootHashHex,
        "osVersion" to osVersion,
        "osPatchLevel" to osPatchLevel,
        "vendorPatchLevel" to vendorPatchLevel,
        "bootPatchLevel" to bootPatchLevel,
    )
}

object AttestationParser {
    const val OID = "1.3.6.1.4.1.11129.2.1.17"

    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OS_VERSION = 705
    private const val TAG_OS_PATCH_LEVEL = 706
    private const val TAG_VENDOR_PATCH_LEVEL = 718
    private const val TAG_BOOT_PATCH_LEVEL = 719

    /** @param extensionValue the bytes returned by X509Certificate.getExtensionValue(OID): a DER OCTET STRING wrapping the KeyDescription. */
    fun parse(extensionValue: ByteArray): Attestation {
        val outer = Der.parseAll(extensionValue).single()
        require(outer.tagClass == 0 && outer.tag == 4) { "expected OCTET STRING" }
        val seq = Der.parseAll(outer.content).single()
        require(seq.tagClass == 0 && seq.tag == 16) { "expected SEQUENCE" }
        val f = seq.children()
        require(f.size >= 8) { "KeyDescription too short: ${f.size}" }

        val softwareEnforced = f[6].children()
        val hardwareEnforced = f[7].children()
        // Hardware-enforced values take precedence over software-enforced ones.
        val all = hardwareEnforced + softwareEnforced

        fun explicit(tag: Int): Tlv? = all.firstOrNull { it.tagClass == 2 && it.tag == tag }?.children()?.singleOrNull()
        fun explicitInt(tag: Int): Long? = explicit(tag)?.let { Der.intValue(it.content) }

        val rot = explicit(TAG_ROOT_OF_TRUST)?.children()
        return Attestation(
            attestationVersion = Der.intValue(f[0].content),
            attestationSecurityLevel = Der.intValue(f[1].content),
            keyMintVersion = Der.intValue(f[2].content),
            keyMintSecurityLevel = Der.intValue(f[3].content),
            challengeHex = hex(f[4].content),
            verifiedBootState = rot?.getOrNull(2)?.let { Der.intValue(it.content) },
            deviceLocked = rot?.getOrNull(1)?.let { it.content.isNotEmpty() && it.content[0].toInt() != 0 },
            verifiedBootKeyHex = rot?.getOrNull(0)?.let { hex(it.content) },
            verifiedBootHashHex = rot?.getOrNull(3)?.let { hex(it.content) },
            osVersion = explicitInt(TAG_OS_VERSION),
            osPatchLevel = explicitInt(TAG_OS_PATCH_LEVEL),
            vendorPatchLevel = explicitInt(TAG_VENDOR_PATCH_LEVEL),
            bootPatchLevel = explicitInt(TAG_BOOT_PATCH_LEVEL),
        )
    }
}
