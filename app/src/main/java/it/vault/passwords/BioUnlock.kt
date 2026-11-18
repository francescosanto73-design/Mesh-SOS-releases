package it.vault.passwords

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
 * Sblocco con impronta: la chiave del caveau è cifrata con una chiave dell'Android Keystore
 * utilizzabile solo dopo autenticazione biometrica (e invalidata se cambiano le impronte).
 */
class BioUnlock(private val ctx: Context) {
    private val file = File(ctx.filesDir, "bio.bin")

    val enabled: Boolean get() = file.exists()

    fun available(): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun disable() {
        file.delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    fun encryptCipher(): Cipher {
        runCatching { keyStore().deleteEntry(ALIAS) }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        gen.generateKey()
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
    }

    fun store(cipher: Cipher, raw: ByteArray) {
        val iv = cipher.iv
        file.writeBytes(byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(raw))
    }

    /** Può lanciare KeyPermanentlyInvalidatedException se le impronte sono cambiate. */
    fun decryptCipher(): Cipher {
        val data = file.readBytes()
        val ivLen = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLen)
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
    }

    fun read(cipher: Cipher): ByteArray {
        val data = file.readBytes()
        return cipher.doFinal(data.copyOfRange(1 + data[0].toInt(), data.size))
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key() = keyStore().getKey(ALIAS, null) as SecretKey

    companion object {
        private const val ALIAS = "custode_bio_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
