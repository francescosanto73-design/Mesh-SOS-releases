package it.vault.custode.kdbx

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** VariantDictionary del formato KDBX (parametri della KDF). Conserva l'ordine e i tipi originali. */
internal class VarDict(val items: LinkedHashMap<String, Item> = LinkedHashMap()) {
    class Item(val type: Int, var value: Any)

    fun bytes(n: String) = items[n]?.value as? ByteArray
    fun long(n: String): Long? = when (val v = items[n]?.value) { is Long -> v; is Int -> v.toLong(); else -> null }

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x01)) // versione 0x0100
        for ((name, it) in items) {
            val n = name.toByteArray(Charsets.UTF_8)
            val v: ByteArray = when (it.type) {
                0x04 -> Crypto.le4((it.value as Long).toInt())
                0x05, 0x0D -> Crypto.le8(it.value as Long)
                0x08 -> byteArrayOf(if (it.value as Boolean) 1 else 0)
                0x0C -> Crypto.le4(it.value as Int)
                0x18 -> (it.value as String).toByteArray(Charsets.UTF_8)
                else -> it.value as ByteArray
            }
            out.write(it.type); out.write(Crypto.le4(n.size)); out.write(n); out.write(Crypto.le4(v.size)); out.write(v)
        }
        out.write(0)
        return out.toByteArray()
    }

    companion object {
        fun parse(b: ByteArray): VarDict {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val d = VarDict()
            try {
                bb.getShort()
                while (true) {
                    val type = bb.get().toInt() and 0xFF
                    if (type == 0) break
                    val name = ByteArray(bb.getInt().also { require(it in 0..bb.remaining()) }).also { bb.get(it) }
                    val raw = ByteArray(bb.getInt().also { require(it in 0..bb.remaining()) }).also { bb.get(it) }
                    val rb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    val v: Any = when (type) {
                        0x04 -> rb.getInt().toLong() and 0xFFFFFFFFL
                        0x05, 0x0D -> rb.getLong()
                        0x08 -> raw[0].toInt() != 0
                        0x0C -> rb.getInt()
                        0x18 -> String(raw, Charsets.UTF_8)
                        else -> raw
                    }
                    d.items[String(name, Charsets.UTF_8)] = Item(type, v)
                }
            } catch (e: RuntimeException) {
                throw UnsupportedFormatException("Parametri di derivazione chiave non validi")
            }
            return d
        }
    }
}

internal object Kdf {
    val ARGON2D = Crypto.hex("ef636ddf8c29444b91f7a9a403e30a0c")
    val ARGON2ID = Crypto.hex("9e298b1956db4773b23dfc3ec6f0a1e6")
    val AES_KDF = Crypto.hex("c9d9f39a628a4460bf740d08c18a4fea")

    fun newArgon2d(memoryMiB: Int = 64, iterations: Long = 8, parallelism: Int = 2, salt: ByteArray): VarDict =
        VarDict().apply {
            items["\$UUID"] = VarDict.Item(0x42, ARGON2D)
            items["S"] = VarDict.Item(0x42, salt)
            items["P"] = VarDict.Item(0x04, parallelism.toLong())
            items["I"] = VarDict.Item(0x05, iterations)
            items["M"] = VarDict.Item(0x05, memoryMiB.toLong() * 1024 * 1024)
            items["V"] = VarDict.Item(0x04, 0x13L)
        }

    fun transform(p: VarDict, composite: ByteArray): ByteArray {
        val id = p.bytes("\$UUID") ?: throw UnsupportedFormatException("KDF mancante")
        val salt = p.bytes("S") ?: throw UnsupportedFormatException("Salt KDF mancante")
        return when {
            id.contentEquals(ARGON2D) || id.contentEquals(ARGON2ID) -> {
                val mem = p.long("M") ?: throw UnsupportedFormatException("Parametro M mancante")
                val iter = p.long("I") ?: throw UnsupportedFormatException("Parametro I mancante")
                val par = p.long("P") ?: throw UnsupportedFormatException("Parametro P mancante")
                // Limiti di sicurezza: un file ostile non deve poter chiedere memoria/tempo smisurati.
                if (mem !in 8192L..(2L shl 30) || iter !in 1L..1000L || par !in 1L..64L) {
                    throw UnsupportedFormatException("Parametri Argon2 fuori dai limiti consentiti")
                }
                val type = if (id.contentEquals(ARGON2D)) Argon2Parameters.ARGON2_d else Argon2Parameters.ARGON2_id
                val b = Argon2Parameters.Builder(type)
                    .withVersion((p.long("V") ?: 0x13L).toInt())
                    .withIterations(iter.toInt())
                    .withMemoryAsKB((mem / 1024).toInt())
                    .withParallelism(par.toInt())
                    .withSalt(salt)
                p.bytes("K")?.let { b.withSecret(it) }
                p.bytes("A")?.let { b.withAdditional(it) }
                val out = ByteArray(32)
                Argon2BytesGenerator().apply { init(b.build()) }.generateBytes(composite, out)
                out
            }
            id.contentEquals(AES_KDF) -> {
                val rounds = p.long("R") ?: throw UnsupportedFormatException("Parametro R mancante")
                if (rounds !in 1L..2_000_000_000L) throw UnsupportedFormatException("Round AES-KDF non validi")
                val c = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(salt, "AES")) }
                var k = composite.copyOf()
                var r = 0L
                while (r++ < rounds) k = c.update(k)
                Crypto.sha256(k)
            }
            else -> throw UnsupportedFormatException("Algoritmo di derivazione chiave non supportato")
        }
    }
}
