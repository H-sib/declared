package lab.probe

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** K-series: key attestation, StrongBox, digest support and Keystore tripwires. */
object Keystore {
    private const val ATTEST = "lab_attest_key"
    private const val STRONGBOX = "lab_strongbox_key"
    private const val SHA3_PROBE = "lab_sha3_probe"
    private const val TRIP_BIO = "lab_trip_bio"
    private const val TRIP_ANY = "lab_trip_any"

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun levelName(level: Int): String =
        KeyProperties::class.java.fields
            .firstOrNull { it.name.startsWith("SECURITY_LEVEL_") && it.getInt(null) == level }?.name
            ?: "LEVEL_$level"

    private fun generateAttested(alias: String, strongBox: Boolean, challenge: ByteArray): KeyStore {
        val ks = store()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(builder.build())
        generator.generateKeyPair()
        return ks
    }

    @Suppress("DEPRECATION")
    private fun keyInfo(ks: KeyStore, alias: String): JSONObject {
        val key = ks.getKey(alias, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        return jo(
            "insideSecureHardware" to info.isInsideSecureHardware,
            "securityLevel" to if (Build.VERSION.SDK_INT >= 31) levelName(info.securityLevel) else null,
        )
    }

    /** K1, K2 and K3 (chain only; validation against Google roots happens off-device in this phase). */
    fun attestation(): Reading = reading("K1", "Key attestation chain", 1) {
        val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ks = generateAttested(ATTEST, false, challenge)
        val chain = ks.getCertificateChain(ATTEST).map { it as X509Certificate }
        // The attestation extension to trust is the one nearest the root.
        val holder = chain.asReversed().firstOrNull { it.getExtensionValue(AttestationParser.OID) != null }
        var parseError: String? = null
        val parsed = holder?.let {
            try {
                AttestationParser.parse(it.getExtensionValue(AttestationParser.OID))
            } catch (e: Exception) {
                parseError = e.javaClass.simpleName + ": " + e.message
                null
            }
        }
        jo(
            "chainLength" to chain.size,
            "keyInfo" to keyInfo(ks, ATTEST),
            "attestation" to parsed?.toJson(),
            "parseError" to parseError,
            "challengeMatches" to parsed?.let { it.challengeHex == hex(challenge) },
            "certs" to chain.map {
                jo(
                    "subject" to it.subjectX500Principal.name,
                    "issuer" to it.issuerX500Principal.name,
                    "sha256" to sha256Hex(it.encoded),
                    "notAfter" to it.notAfter.time,
                )
            },
            "chainBase64" to chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) },
        )
    }

    /** K5. */
    fun strongBox(ctx: Context): Reading = reading("K5", "StrongBox", 3) {
        val feature = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        var generated = false
        var error: String? = null
        var info: JSONObject? = null
        try {
            val ks = generateAttested(STRONGBOX, true, ByteArray(8))
            generated = true
            info = keyInfo(ks, STRONGBOX)
        } catch (e: Exception) {
            error = e.javaClass.simpleName + ": " + e.message
        }
        jo("systemFeature" to feature, "generated" to generated, "error" to error, "keyInfo" to info)
    }

    /** K8. */
    fun digests(): Reading = reading("K8", "Digest support", 3) {
        val constants = KeyProperties::class.java.fields
            .filter { it.name.startsWith("DIGEST_") }
            .associate { it.name to (it.get(null)?.toString() ?: "") }

        fun sha3(provider: String?): JSONObject = try {
            val md = if (provider == null) MessageDigest.getInstance("SHA3-256") else MessageDigest.getInstance("SHA3-256", provider)
            jo("available" to true, "provider" to md.provider.name, "abcDigest" to hex(md.digest("abc".toByteArray())))
        } catch (e: Exception) {
            jo("available" to false, "error" to e.javaClass.simpleName)
        }

        val keystoreSha3: JSONObject = try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(SHA3_PROBE, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests("SHA3-256")
                    .build(),
            )
            generator.generateKeyPair()
            store().deleteEntry(SHA3_PROBE)
            jo("accepted" to true)
        } catch (e: Exception) {
            jo("accepted" to false, "error" to e.javaClass.simpleName + ": " + e.message)
        }

        jo(
            "keyPropertiesDigests" to constants,
            "platformSha3" to sha3(null),
            "bcSha3" to sha3("BC"),
            "providers" to Security.getProviders().map { it.name },
            "keystoreSha3Key" to keystoreSha3,
        )
    }

    /**
     * Read defensively: asking about biometrics needs USE_BIOMETRIC, and one refused read must not discard the
     * rest of the tripwire result (that is exactly what happened on the first physical-device run).
     */
    @Suppress("DEPRECATION")
    private fun biometricState(ctx: Context): Any? = try {
        val bm = ctx.getSystemService(BiometricManager::class.java)
        if (bm == null) null
        else if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        else bm.canAuthenticate()
    } catch (e: Throwable) {
        "ERR:" + e.javaClass.simpleName + ":" + e.message
    }

    /**
     * K6/K7: creates two authentication-bound AES keys. One accepts only strong biometrics, the other
     * biometrics or device credential. Key creation needs a secure lock screen and an enrolled biometric.
     */
    fun tripwireCreate(ctx: Context): Reading = reading("K6", "Tripwire create", 3) {
        val keys = JSONObject()
        val variants = listOf(
            TRIP_BIO to KeyProperties.AUTH_BIOMETRIC_STRONG,
            TRIP_ANY to (KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL),
        )
        for ((alias, authTypes) in variants) {
            try {
                val ks = store()
                if (ks.containsAlias(alias)) ks.deleteEntry(alias)
                val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, authTypes)
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(builder.build())
                generator.generateKey()
                keys.put(alias, "CREATED")
            } catch (e: Exception) {
                keys.put(alias, "CREATE_FAILED:" + e.javaClass.simpleName + ":" + e.message)
            }
        }
        jo(
            "keys" to keys,
            "deviceSecure" to ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure,
            "biometricStrongState" to biometricState(ctx),
        )
    }

    /** Initializing a cipher is enough to detect invalidation; no user authentication prompt is needed. */
    private fun checkOne(alias: String): String {
        return try {
            val ks = store()
            if (!ks.containsAlias(alias)) return "MISSING"
            val key = ks.getKey(alias, null) as? SecretKey ?: return "NO_KEY"
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
            "VALID"
        } catch (e: KeyPermanentlyInvalidatedException) {
            "INVALIDATED"
        } catch (e: Exception) {
            "OTHER:" + e.javaClass.simpleName + ":" + e.message
        }
    }

    fun tripwireCheck(ctx: Context): Reading = reading("K6", "Tripwire check", 3) {
        val keys = JSONObject()
        for (alias in listOf(TRIP_BIO, TRIP_ANY)) keys.put(alias, checkOne(alias))
        jo(
            "keys" to keys,
            "deviceSecure" to ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure,
            "biometricStrongState" to biometricState(ctx),
        )
    }
}
