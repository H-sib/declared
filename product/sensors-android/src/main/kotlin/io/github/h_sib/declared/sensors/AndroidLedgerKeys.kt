package io.github.h_sib.declared.sensors

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import io.github.h_sib.declared.core.ledger.ArmOutcome
import io.github.h_sib.declared.core.ledger.EntrySigner
import io.github.h_sib.declared.core.ledger.KeyHeld
import io.github.h_sib.declared.core.ledger.KeyReading
import io.github.h_sib.declared.core.ledger.KeyState
import io.github.h_sib.declared.core.ledger.TripwireKeys
import io.github.h_sib.declared.core.ledger.TripwireKind
import io.github.h_sib.declared.core.ledger.TripwireReading
import io.github.h_sib.declared.core.sensor.ReadResult
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

/**
 * The key that signs the history (STAGE3_DESIGN.md section 3): ECDSA over P-256 with SHA-256, created on first use inside the
 * Keystore (in StrongBox when the phone has it), needing no user authentication so the history can be written without a prompt.
 * Whether the key is held in hardware is read from the key itself and recorded in the history's header, never assumed: on an
 * emulator or a phone without secure hardware it says "software".
 */
class AndroidLedgerSigner(private val alias: String = "declared_history_sign_key") : EntrySigner {

    private class Loaded(val spki: ByteArray?, val held: KeyHeld, val key: PrivateKey?)

    private val loaded: Loaded by lazy { load() }

    override val publicKeySpki: ByteArray? get() = loaded.spki

    override val keyHeld: KeyHeld get() = loaded.held

    private fun generate(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(builder.build()) }.generateKeyPair()
    }

    @Suppress("DEPRECATION")
    private fun load(): Loaded = try {
        val ks = keyStore()
        if (!ks.containsAlias(alias)) {
            try {
                generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                generate(strongBox = false)
            } catch (e: java.security.ProviderException) {
                generate(strongBox = false)
            }
        }
        val key = ks.getKey(alias, null) as PrivateKey
        val spki = ks.getCertificate(alias).publicKey.encoded
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        val inHardware = if (Build.VERSION.SDK_INT >= 31) info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE else info.isInsideSecureHardware
        Loaded(spki, if (inHardware) KeyHeld.HARDWARE else KeyHeld.SOFTWARE, key)
    } catch (e: Exception) {
        Loaded(null, KeyHeld.NONE, null)
    }

    override fun sign(message: ByteArray): ReadResult<ByteArray> {
        val key = loaded.key ?: return ReadResult.Failed("there is no signing key")
        return try {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(key)
            s.update(message)
            ReadResult.Ok(s.sign())
        } catch (e: Exception) {
            ReadResult.Failed("the key store would not sign (${e.javaClass.simpleName})")
        }
    }
}

/**
 * The two tripwire keys (STAGE3_DESIGN.md section 5, `FINDINGS.md` Q3): AES keys that require user authentication for every use
 * and are invalidated by biometric enrollment. One accepts a strong biometric only (it dies when a fingerprint is added and when
 * the lock is removed); the other also accepts the device credential (it dies only when the lock is removed). Reading a key is
 * initializing a cipher with it, which needs no prompt. No permission is needed: whether a fingerprint is enrolled is learned from
 * the Keystore refusing to make the key, not by asking the biometric service.
 */
class AndroidTripwireKeys(private val context: Context) : TripwireKeys {

    private fun alias(kind: TripwireKind) = if (kind == TripwireKind.BIOMETRIC) "declared_tripwire_biometric" else "declared_tripwire_credential"

    private fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    @Suppress("DEPRECATION") // setUserAuthenticationValidityDurationSeconds: the only way to bind a key to the credential on Android 10
    override fun arm(kind: TripwireKind): ArmOutcome {
        // Measured on the Android 10 emulator (lab tripwire experiment): a key that requires a fingerprint stayed usable after another one was
        // added, so this tripwire could never fire there. Saying "nothing happened" would be false; it says it cannot work.
        if (kind == TripwireKind.BIOMETRIC && Build.VERSION.SDK_INT < 31) {
            return ArmOutcome.NotPossible("on Android 10 a key is not destroyed when a fingerprint is added (measured on an emulator), so this tripwire cannot work")
        }
        if (!deviceSecure()) return ArmOutcome.NotPossible("there is no screen lock")
        return try {
            val ks = keyStore()
            if (ks.containsAlias(alias(kind))) ks.deleteEntry(alias(kind))
            val builder = KeyGenParameterSpec.Builder(alias(kind), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                val types = if (kind == TripwireKind.BIOMETRIC) KeyProperties.AUTH_BIOMETRIC_STRONG else KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                builder.setInvalidatedByBiometricEnrollment(true)
                builder.setUserAuthenticationParameters(0, types)
            } else {
                // Android 10 binds a key to the device credential only through a time window (the per-use form needs a fingerprint).
                builder.setUserAuthenticationValidityDurationSeconds(1)
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
            ArmOutcome.Armed
        } catch (e: Exception) {
            // A biometric key cannot be made without an enrolled fingerprint; the lock screen was checked above.
            ArmOutcome.NotPossible(
                if (kind == TripwireKind.BIOMETRIC) "no fingerprint is enrolled on this phone" else "the key store refused to make the key (${e.javaClass.simpleName})",
            )
        }
    }

    override fun read(): TripwireReading = TripwireReading(readOne(alias(TripwireKind.BIOMETRIC)), readOne(alias(TripwireKind.CREDENTIAL)))

    private fun readOne(alias: String): KeyReading = try {
        val ks = keyStore()
        if (!ks.containsAlias(alias)) {
            KeyReading(KeyState.MISSING)
        } else {
            val key = ks.getKey(alias, null) as? SecretKey
            if (key == null) {
                KeyReading(KeyState.MISSING)
            } else {
                Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
                KeyReading(KeyState.VALID)
            }
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        KeyReading(KeyState.INVALIDATED)
    } catch (e: UserNotAuthenticatedException) {
        KeyReading(KeyState.VALID) // the key exists and wants a person to authenticate, which is exactly what it is for
    } catch (e: UnrecoverableKeyException) {
        KeyReading(KeyState.INVALIDATED) // Android 10 reports a key whose lock was removed this way instead of as missing
    } catch (e: Exception) {
        KeyReading(KeyState.UNREADABLE, e.javaClass.simpleName)
    }
}
