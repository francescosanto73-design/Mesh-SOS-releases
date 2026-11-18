package it.vault.custode.kdbx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WrongCredentialsException : Exception("Password errata o file danneggiato")
class UnsupportedFormatException(msg: String) : Exception(msg)

internal object Crypto {
    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").also { d -> parts.forEach { d.update(it) } }.digest()

    fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").also { d -> parts.forEach { d.update(it) } }.digest()

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").also { m ->
            m.init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { m.update(it) }
        }.doFinal()

    fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun le4(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun le8(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

    /** Chiave per il blocco `index` (−1 = intestazione) del flusso HMAC. */
    fun blockKey(hmacBase: ByteArray, index: Long) = sha512(le8(index), hmacBase)

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
