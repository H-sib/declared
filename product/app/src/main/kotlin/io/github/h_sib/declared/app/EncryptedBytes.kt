package io.github.h_sib.declared.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.SealedFile
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface SealedRead {
    data object Empty : SealedRead

    class Loaded(val plain: ByteArray) : SealedRead

    data class Corrupt(val reason: String) : SealedRead

    data class Unavailable(val reason: String) : SealedRead
}

/**
 * One file kept encrypted at rest (DESIGN.md section 9): AES-256-GCM with a key that never leaves the Android Keystore, written
 * atomically, with a purpose string bound into the encryption so that one file cannot be swapped for another sealed with the
 * same key. The baseline and the history each use their own file, key and purpose. The layout of the bytes and its errors are in
 * `core` (`SealedFile`); this class only supplies the cipher. [what] is the noun used in reasons shown to the person.
 */
internal class EncryptedBytes(context: Context, name: String, private val alias: String, private val purpose: String, private val what: String) {

    private val file = AtomicFile(File(context.filesDir, name))

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getKey(alias, null) as? SecretKey)

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Keeps a file that cannot be read under another name, so starting over never destroys it. */
    fun moveAside(): Boolean = try {
        val target = File(file.baseFile.parentFile, file.baseFile.name + ".damaged")
        target.delete()
        file.baseFile.renameTo(target)
    } catch (e: SecurityException) {
        false
    }

    fun read(): SealedRead {
        val bytes = try {
            if (!file.baseFile.exists()) return SealedRead.Empty
            file.readFully()
        } catch (e: IOException) {
            return SealedRead.Unavailable("the file could not be read (${e.javaClass.simpleName})")
        }
        val (iv, ciphertext) = try {
            SealedFile.unpack(bytes)
        } catch (e: SealedFile.FormatException) {
            return SealedRead.Corrupt(e.message.orEmpty().replace("saved baseline", what))
        }
        val key = try {
            existingKey()
        } catch (e: GeneralSecurityException) {
            return SealedRead.Unavailable("the key store refused (${e.javaClass.simpleName})")
        } ?: return SealedRead.Corrupt("the key that protected the $what no longer exists, for example after a restore to another phone")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(purpose.toByteArray(Charsets.US_ASCII))
            SealedRead.Loaded(cipher.doFinal(ciphertext))
        } catch (e: AEADBadTagException) {
            SealedRead.Corrupt("the $what failed its integrity check: it was changed or damaged")
        } catch (e: GeneralSecurityException) {
            SealedRead.Unavailable("the key store could not decrypt (${e.javaClass.simpleName})")
        }
    }

    fun write(plain: ByteArray): ReadResult<Unit> {
        return try {
            val key = existingKey() ?: createKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(purpose.toByteArray(Charsets.US_ASCII))
            val sealed = SealedFile.pack(cipher.iv, cipher.doFinal(plain))
            val out = file.startWrite()
            try {
                out.write(sealed)
                file.finishWrite(out)
            } catch (e: IOException) {
                file.failWrite(out)
                throw e
            }
            ReadResult.Ok(Unit)
        } catch (e: GeneralSecurityException) {
            ReadResult.Failed("the key store could not encrypt (${e.javaClass.simpleName})")
        } catch (e: IOException) {
            ReadResult.Failed("the file could not be written (${e.javaClass.simpleName})")
        }
    }
}
