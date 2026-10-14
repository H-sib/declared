package io.github.h_sib.declared.core.integrity

import java.time.LocalDate

/** Android's `KeyDescription` (the key attestation extension), reduced to what the integrity panel uses. */
data class KeyAttestation(
    val attestationVersion: Long,
    /** 0 software, 1 trusted environment, 2 StrongBox. */
    val attestationSecurityLevel: Long,
    val keyMintVersion: Long,
    val keyMintSecurityLevel: Long,
    val challengeHex: String,
    /** 0 verified, 1 self-signed, 2 unverified, 3 failed. Null when the record has no root of trust. */
    val verifiedBootState: Long?,
    val deviceLocked: Boolean?,
    val verifiedBootKeyHex: String?,
    val verifiedBootHashHex: String?,
    val osVersion: Long?,
    /** `yyyyMM`. */
    val osPatchLevel: Long?,
    /** `yyyyMMdd` (or `yyyyMM` on older records). */
    val vendorPatchLevel: Long?,
    val bootPatchLevel: Long?,
) {
    val hardwareBacked: Boolean get() = attestationSecurityLevel >= 1

    val securityLevelName: String
        get() = when (attestationSecurityLevel) {
            0L -> "software"
            1L -> "a trusted environment"
            2L -> "StrongBox hardware"
            else -> "an unknown level ($attestationSecurityLevel)"
        }

    val bootStateName: String?
        get() = when (verifiedBootState) {
            0L -> "verified"
            1L -> "verified with a key the owner set (custom software)"
            2L -> "not verified"
            3L -> "failed verification"
            null -> null
            else -> "unknown ($verifiedBootState)"
        }
}

object AttestationParser {
    const val OID = "1.3.6.1.4.1.11129.2.1.17"

    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OS_VERSION = 705
    private const val TAG_OS_PATCH_LEVEL = 706
    private const val TAG_VENDOR_PATCH_LEVEL = 718
    private const val TAG_BOOT_PATCH_LEVEL = 719

    /**
     * @param extensionValue what `X509Certificate.getExtensionValue(OID)` returns: a DER OCTET STRING wrapping the
     * KeyDescription. Throws `IllegalArgumentException` on anything that is not one.
     */
    fun parse(extensionValue: ByteArray): KeyAttestation {
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
        return KeyAttestation(
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

/** A patch level the attestation record gives as `yyyyMM` or `yyyyMMdd`, as the first day of that month or the exact day. */
fun attestedPatchDate(level: Long?): LocalDate? {
    if (level == null) return null
    return try {
        when (level) {
            in 190_001..999_912 -> LocalDate.of((level / 100).toInt(), (level % 100).toInt(), 1)
            in 19_000_101..99_991_231 -> LocalDate.of((level / 10_000).toInt(), ((level / 100) % 100).toInt(), (level % 100).toInt())
            else -> null
        }
    } catch (e: java.time.DateTimeException) {
        null
    }
}
