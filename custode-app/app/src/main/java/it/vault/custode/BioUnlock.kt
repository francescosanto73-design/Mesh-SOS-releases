package it.vault.custode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Sblocco con impronta: la chiave composta del database è cifrata con una chiave dell'Android Keystore
 * utilizzabile solo dopo l'autenticazione biometrica (e invalidata se cambiano le impronte).
 */
class BioUnlock(private val ctx: Context) {
    private val file = File(ctx.filesDir, "bio.bin")
    val enabled: Boolean get() = file.exists()

    fun available(): Boolean = BiometricManager.from(ctx)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun disable() { file.delete(); runCatching { keyStore().deleteEntry(ALIAS) } }

    fun encryptCipher(): Cipher {
        runCatching { keyStore().deleteEntry(ALIAS) }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
        }.generateKey()
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
    }

    fun store(cipher: Cipher, secret: ByteArray) {
        val iv = cipher.iv
        file.writeBytes(byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(secret))
    }

    /** Può lanciare KeyPermanentlyInvalidatedException se le impronte sono cambiate. */
    fun decryptCipher(): Cipher {
        val d = file.readBytes()
        val iv = d.copyOfRange(1, 1 + d[0].toInt())
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
    }

    fun read(cipher: Cipher): ByteArray {
        val d = file.readBytes()
        return cipher.doFinal(d.copyOfRange(1 + d[0].toInt(), d.size))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key() = keyStore().getKey(ALIAS, null) as SecretKey

    companion object {
        private const val ALIAS = "custode_bio_key_v2"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
