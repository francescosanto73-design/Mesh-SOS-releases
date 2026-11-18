package it.vault.passwords

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
)

class WrongPasswordException : Exception()

/**
 * Caveau cifrato su file.
 * Formato: "CPV1" | salt(16) | iv(12) | AES-256-GCM(JSON) 
 * Chiave: PBKDF2-HMAC-SHA256 dalla password principale.
 */
class Vault(private val file: File) {
    private var key: SecretKeySpec? = null
    private var salt: ByteArray? = null
    var entries: List<Entry> = emptyList()
        private set

    val exists: Boolean get() = file.exists()
    val isUnlocked: Boolean get() = key != null

    fun create(master: CharArray) {
        val s = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        salt = s
        key = derive(master, s)
        entries = emptyList()
        persist()
    }

    fun unlock(master: CharArray) = load { s -> derive(master, s) }

    /** Sblocco con la chiave grezza (usata dallo sblocco biometrico). */
    fun unlockWithKey(raw: ByteArray) = load { SecretKeySpec(raw, "AES") }

    private fun load(keyFor: (ByteArray) -> SecretKeySpec) {
        val data = file.readBytes()
        require(data.size > MAGIC.size + SALT_LEN + IV_LEN && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "File non valido"
        }
        val s = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = data.copyOfRange(MAGIC.size + SALT_LEN, MAGIC.size + SALT_LEN + IV_LEN)
        val ct = data.copyOfRange(MAGIC.size + SALT_LEN + IV_LEN, data.size)
        val k = keyFor(s)
        val plain = try {
            Cipher.getInstance(TRANSFORM).run {
                init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
                doFinal(ct)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPasswordException()
        }
        val arr = JSONArray(String(plain, Charsets.UTF_8))
        entries = (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                Entry(
                    it.getString("id"), it.getString("title"), it.optString("username"),
                    it.optString("password"), it.optString("url"), it.optString("notes"),
                )
            }
        }
        salt = s
        key = k
    }

    fun lock() {
        key = null
        salt = null
        entries = emptyList()
    }

    fun keyBytes(): ByteArray = checkNotNull(key).encoded

    /** Il file è già cifrato: l'export è una copia byte per byte. */
    fun exportTo(out: java.io.OutputStream) { file.inputStream().use { it.copyTo(out) } }

    /** Sostituisce il caveau con un backup, dopo aver verificato che la password lo apra. */
    fun replaceFrom(bytes: ByteArray, master: CharArray) {
        val tmp = File(file.parentFile, file.name + ".import")
        tmp.writeBytes(bytes)
        try {
            Vault(tmp).unlock(master)
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
        file.delete()
        check(tmp.renameTo(file)) { "Ripristino fallito" }
        lock(); unlock(master)
    }

    fun upsert(e: Entry) {
        entries = if (entries.any { it.id == e.id }) entries.map { if (it.id == e.id) e else it } else entries + e
        persist()
    }

    fun delete(id: String) {
        entries = entries.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        val k = checkNotNull(key); val s = checkNotNull(salt)
        val json = JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("id", it.id).put("title", it.title).put("username", it.username)
                    .put("password", it.password).put("url", it.url).put("notes", it.notes))
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val ct = Cipher.getInstance(TRANSFORM).run {
            init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
            doFinal(json)
        }
        // Scrittura atomica: file temporaneo + rinomina.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(MAGIC + s + iv + ct)
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "Salvataggio fallito" } }
    }

    private fun derive(master: CharArray, s: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(master, s, ITERATIONS, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        private val MAGIC = "CPV1".toByteArray()
        private const val SALT_LEN = 16
        private const val IV_LEN = 12
        private const val ITERATIONS = 210_000
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private val random = SecureRandom()

        fun generatePassword(length: Int = 20, symbols: Boolean = true): String {
            val pools = mutableListOf("abcdefghijkmnopqrstuvwxyz", "ABCDEFGHJKLMNPQRSTUVWXYZ", "23456789")
            if (symbols) pools += "!@#$%^&*-_=+?"
            val all = pools.joinToString("")
            val chars = pools.map { it[random.nextInt(it.length)] }.toMutableList()
            while (chars.size < length) chars += all[random.nextInt(all.length)]
            chars.shuffle(random)
            return chars.joinToString("")
        }
    }
}
