package io.github.h_sib.declared.sensors

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.h_sib.declared.core.integrity.ReferenceBundle
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.core.sensor.AttestationEvidence
import io.github.h_sib.declared.core.sensor.AttestationSource
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.ReferenceSource
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/**
 * Asks the Android Keystore for a fresh attested key and returns its certificate chain (lab K1, which parsed the
 * attestation on Android 10 and 12 to 17). A new random challenge goes in every time, so a chain cannot be replayed.
 * The key is deleted again straight away: this app has no use for it.
 */
class AndroidAttestationSource : AttestationSource {

    override fun read(): ReadResult<AttestationEvidence> = guarded {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build()
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            val chain = store.getCertificateChain(ALIAS) ?: error("the key store returned no certificate chain")
            AttestationEvidence(chain.map { it.encoded }, challenge)
        } finally {
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        }
    }

    private companion object {
        const val ALIAS = "declared_attestation_probe"
    }
}

/** Reads the bundled roots, status list and manifest from the app's assets and lets `core` check their checksums. */
class AndroidReferenceSource(private val context: Context) : ReferenceSource {

    override fun read(): ReadResult<ReferenceData> {
        val assets = context.assets
        return try {
            val roots = assets.open("attestation/${ReferenceBundle.ROOTS}").use { it.readBytes() }
            val status = assets.open("attestation/${ReferenceBundle.STATUS}").use { it.readBytes() }
            val manifest = assets.open("attestation/MANIFEST.txt").use { it.readBytes() }.toString(Charsets.UTF_8)
            ReferenceBundle.load(roots, status, manifest)
        } catch (e: java.io.IOException) {
            ReadResult.Failed("a bundled reference file could not be opened: ${e.javaClass.simpleName}")
        }
    }
}
