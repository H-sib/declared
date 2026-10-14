package io.github.h_sib.declared.core.integrity

import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

data class ChainLink(
    val index: Int,
    val subject: String,
    val serialDecimal: String,
    /** Whether this certificate's signature verifies with the next certificate's key. Null for the last one. */
    val signatureOk: Boolean?,
    val signatureError: String?,
    val validNow: Boolean,
    val validityError: String?,
)

data class ChainReport(
    val links: List<ChainLink>,
    /** Whether the last certificate is a trusted root or is signed by one. */
    val endsAtTrustedRoot: Boolean,
    val rootDetail: String,
    /** The chain hangs off Google's legacy RSA root (phones launched before 2021). */
    val legacyRoot: Boolean,
    /** Indexes and entries of certificates on Google's revocation list. */
    val revoked: List<Pair<Int, RevocationEntry>>,
    val unparsable: String?,
    /** The attestation record of the certificate nearest the root that carries one; null if none could be read. */
    val attestation: KeyAttestation? = null,
    val attestationError: String? = null,
) {
    val brokenLinks: List<Int> get() = links.filter { it.signatureOk == false }.map { it.index }
    val expired: List<Int> get() = links.filter { !it.validNow }.map { it.index }

    /**
     * Trusted means every rule holds: each link is signed by the next, the end is a Google root, nothing is on the
     * revocation list, and every certificate is within its dates, except that a legacy factory chain may carry expired
     * certificates (Google's guidance: still trustworthy unless revoked; lab defect D-008).
     */
    val trusted: Boolean
        get() = unparsable == null && links.isNotEmpty() && brokenLinks.isEmpty() && endsAtTrustedRoot && revoked.isEmpty() &&
            (expired.isEmpty() || legacyRoot)

    /** The reasons a chain is not trusted, in plain words. Empty when it is. */
    fun problems(): List<String> = buildList {
        unparsable?.let { add(it) }
        if (links.isEmpty() && unparsable == null) add("the phone returned no certificate chain")
        if (brokenLinks.isNotEmpty()) add("a certificate is not signed by the next one in the chain (certificate ${brokenLinks.joinToString(", ")})")
        if (!endsAtTrustedRoot && links.isNotEmpty()) add("the chain does not lead back to a certificate that Google publishes for this purpose")
        if (expired.isNotEmpty() && !legacyRoot) add("a certificate is outside its validity dates (certificate ${expired.joinToString(", ")})")
        revoked.forEach { (i, e) -> add("certificate $i is on Google's revocation list (${e.status}${e.reason?.let { r -> ", $r" }.orEmpty()})") }
    }
}

/**
 * Verifies a key attestation chain offline, with only `java.security`: signatures link by link, the root against the
 * bundled Google roots, validity dates (with the legacy-root rule) and the revocation list. It runs on the same phone as
 * the data it judges, so it can say the chain is genuine Google-rooted material, never that the phone is honest.
 */
class ChainVerifier(private val legacyRootSerialHex: String = "f92009e853b6b045") {

    fun verify(chainDer: List<ByteArray>, reference: ReferenceData, now: Instant): ChainReport {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = try {
            chainDer.map { factory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        } catch (e: CertificateException) {
            return ChainReport(emptyList(), false, "", false, emptyList(), "a certificate in the chain could not be read (${e.javaClass.simpleName})")
        } catch (e: ClassCastException) {
            return ChainReport(emptyList(), false, "", false, emptyList(), "an element of the chain is not an X.509 certificate")
        }
        if (certs.isEmpty()) return ChainReport(emptyList(), false, "", false, emptyList(), null)

        val date = Date.from(now)
        val links = certs.mapIndexed { i, cert ->
            var sigOk: Boolean? = null
            var sigError: String? = null
            if (i + 1 < certs.size) {
                try {
                    cert.verify(certs[i + 1].publicKey)
                    sigOk = true
                } catch (e: Exception) {
                    sigOk = false
                    sigError = e.javaClass.simpleName
                }
            }
            var valid = true
            var validityError: String? = null
            try {
                cert.checkValidity(date)
            } catch (e: CertificateException) {
                valid = false
                validityError = e.javaClass.simpleName
            }
            ChainLink(i, cert.subjectX500Principal.name, cert.serialNumber.toString(), sigOk, sigError, valid, validityError)
        }

        val top = certs.last()
        var rootDetail = ""
        var trustedRoot = false
        for ((r, rootDer) in reference.roots.withIndex()) {
            val root = try {
                factory.generateCertificate(ByteArrayInputStream(rootDer)) as X509Certificate
            } catch (e: Exception) {
                continue
            }
            if (top.encoded.contentEquals(root.encoded)) {
                trustedRoot = true
                rootDetail = "the last certificate in the chain is trusted root #$r"
                break
            }
            try {
                top.verify(root.publicKey)
                trustedRoot = true
                rootDetail = "the last certificate in the chain is signed by trusted root #$r"
                break
            } catch (e: Exception) {
                // not this root
            }
        }

        val revoked = certs.mapIndexedNotNull { i, c ->
            reference.revocation.lookup(c.serialNumber.toString(), c.serialNumber.toString(16))?.let { i to it }
        }
        // The attestation record to trust is the one nearest the root (lab K1): a leaf can carry a forged copy.
        var attestation: KeyAttestation? = null
        var attestationError: String? = null
        val holder = certs.asReversed().firstOrNull { it.getExtensionValue(AttestationParser.OID) != null }
        if (holder == null) {
            attestationError = "no certificate in the chain carries a key attestation record"
        } else {
            try {
                attestation = AttestationParser.parse(holder.getExtensionValue(AttestationParser.OID))
            } catch (e: Exception) {
                attestationError = "the attestation record could not be read (${e.javaClass.simpleName})"
            }
        }
        return ChainReport(links, trustedRoot, rootDetail, isLegacy(top), revoked, null, attestation, attestationError)
    }

    /**
     * The JDK writes the X.500 `serialNumber` attribute as OID 2.5.4.5 with a hex-encoded value, so the legacy root's
     * serial is looked for in the subject and issuer both as text and as the hex of its ASCII bytes.
     */
    private fun isLegacy(top: X509Certificate): Boolean {
        val text = (top.subjectX500Principal.name + " " + top.issuerX500Principal.name).lowercase()
        return legacyRootSerialHex in text || legacyRootSerialHex.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) } in text
    }
}
