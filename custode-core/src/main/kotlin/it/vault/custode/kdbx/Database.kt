package it.vault.custode.kdbx

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.StreamCipher
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Database KDBX 4 (formato di KeePass/KeePassXC/KeePassDX), scritto dalla specifica pubblica.
 * Il documento XML viene conservato così com'è: campi sconosciuti, storico, allegati e
 * dati personalizzati creati da altre app non vanno persi quando si salva.
 */
class KdbxDatabase internal constructor(
    internal val doc: Document,
    private var composite: ByteArray,
    private val headerFields: LinkedHashMap<Int, ByteArray>,
    private val kdf: VarDict,
    private val binaries: List<ByteArray>,
    private val innerStreamId: Int,
    /** true se il file originale era KDBX 3: al salvataggio viene convertito in KDBX 4. */
    val upgradedFromV3: Boolean = false,
    transformedInit: ByteArray? = null,
) {
    /** Risultato della KDF (Argon2): riusato per i salvataggi nella stessa sessione, così salvare è rapido. */
    private var transformed: ByteArray? = transformedInit
    /** Esegue in anticipo la KDF (lenta): i salvataggi successivi diventano rapidi. Chiamare fuori dal thread UI. */
    fun prepare() { transformedKey() }

    /** Elimina una voce o un gruppo da qualunque punto dell'albero (lascia traccia in DeletedObjects). */
    fun delete(entry: KdbxEntry) { KdbxGroup(entry.el.parentNode as Element, this).remove(entry) }
    fun delete(group: KdbxGroup) { KdbxGroup(group.el.parentNode as Element, this).remove(group) }

    private fun transformedKey(): ByteArray = transformed ?: Kdf.transform(kdf, composite).also { transformed = it }

    private val random = SecureRandom()

    val root: KdbxGroup get() = KdbxGroup(doc.documentElement.child("Root")!!.child("Group")!!, this)
    var name: String
        get() = doc.documentElement.child("Meta")?.child("DatabaseName")?.textContent ?: ""
        set(v) { doc.documentElement.child("Meta")?.child("DatabaseName")?.textContent = v }

    /** Chiave composta (hash della password): serve allo sblocco biometrico. Va azzerata dal chiamante. */
    fun compositeKey(): ByteArray = composite.copyOf()

    fun close() { composite.fill(0); transformed?.fill(0); transformed = null }

    /** Cambia la chiave principale (nuovo salt KDF; il prossimo salvataggio ricalcola Argon2). */
    fun changeKey(newComposite: ByteArray) {
        composite.fill(0); transformed?.fill(0); transformed = null
        composite = newComposite.copyOf()
        kdf.items["S"]?.let { it.value = ByteArray((it.value as ByteArray).size).also(random::nextBytes) }
        doc.documentElement.child("Meta")?.child("MasterKeyChanged")?.textContent = nowIso()
    }

    fun allEntries(): List<KdbxEntry> = root.entriesRecursive()

    /** Serializza e cifra il database (nuovi salt/IV/chiave interna a ogni salvataggio). */
    fun toBytes(): ByteArray {
        val masterSeed = ByteArray(32).also(random::nextBytes)
        val cipherId = headerFields[2] ?: CIPHER_AES
        val iv = ByteArray(if (cipherId.contentEquals(CIPHER_CHACHA20)) 12 else 16).also(random::nextBytes)
        val compression = ByteBuffer.wrap(headerFields[3] ?: Crypto.le4(1)).order(ByteOrder.LITTLE_ENDIAN).getInt()

        val header = ByteArrayOutputStream()
        header.write(byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()))
        header.write(byteArrayOf(0x01, 0x00, 0x04, 0x00)) // versione 4.1
        fun field(id: Int, data: ByteArray) { header.write(id); header.write(Crypto.le4(data.size)); header.write(data) }
        field(2, cipherId); field(3, Crypto.le4(compression)); field(4, masterSeed); field(7, iv); field(11, kdf.toBytes())
        headerFields.filterKeys { it !in setOf(0, 2, 3, 4, 7, 11) }.forEach { (id, d) -> field(id, d) }
        field(0, byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A))
        val headerBytes = header.toByteArray()

        val tk = transformedKey()
        val cipherKey = Crypto.sha256(masterSeed, tk)
        val hmacBase = Crypto.sha512(masterSeed, tk, byteArrayOf(1))

        // Intestazione interna + XML (valori protetti ricifrati con una nuova chiave).
        val innerKey = ByteArray(64).also(random::nextBytes)
        val inner = ByteArrayOutputStream()
        fun ifield(id: Int, data: ByteArray) { inner.write(id); inner.write(Crypto.le4(data.size)); inner.write(data) }
        ifield(1, Crypto.le4(innerStreamId)); ifield(2, innerKey)
        binaries.forEach { ifield(3, it) }
        ifield(0, ByteArray(0))
        val xml = withProtected(innerKey) { serializeXml() }
        inner.write(xml)

        var plain = inner.toByteArray()
        if (compression == 1) {
            plain = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()
        }
        val encrypted = encrypt(cipherId, cipherKey, iv, plain)

        val out = ByteArrayOutputStream()
        out.write(headerBytes)
        out.write(Crypto.sha256(headerBytes))
        out.write(Crypto.hmacSha256(Crypto.blockKey(hmacBase, -1L), headerBytes))
        var index = 0L; var pos = 0
        while (pos < encrypted.size) {
            val n = minOf(1 shl 20, encrypted.size - pos)
            val chunk = encrypted.copyOfRange(pos, pos + n)
            out.write(Crypto.hmacSha256(Crypto.blockKey(hmacBase, index), Crypto.le8(index), Crypto.le4(n), chunk))
            out.write(Crypto.le4(n)); out.write(chunk)
            index++; pos += n
        }
        out.write(Crypto.hmacSha256(Crypto.blockKey(hmacBase, index), Crypto.le8(index), Crypto.le4(0)))
        out.write(Crypto.le4(0))
        return out.toByteArray()
    }

    // ---- XML: valori protetti ----

    /** Cifra in place i valori protetti, esegue [block], poi li ripristina in chiaro. */
    private fun <T> withProtected(innerKey: ByteArray, block: () -> T): T {
        xorProtected(innerStream(innerStreamId, innerKey), encrypt = true)
        try { return block() } finally { xorProtected(innerStream(innerStreamId, innerKey), encrypt = false) }
    }

    private fun xorProtected(stream: StreamCipher, encrypt: Boolean) = Companion.xorProtected(doc, stream, encrypt)

    private fun serializeXml(): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.STANDALONE, "yes")
        return ByteArrayOutputStream().also { t.transform(DOMSource(doc), StreamResult(it)) }.toByteArray()
    }

    companion object {
        internal val CIPHER_AES = Crypto.hex("31c1f2e6bf714350be5805216afc5aff")
        internal val CIPHER_CHACHA20 = Crypto.hex("d6038a2b8b6f4cb5a524339a31dbb59a")
        private val SALSA_IV = Crypto.hex("E830094B97205D2A")
        private val b64 = Base64.getEncoder(); private val b64d = Base64.getDecoder()

        fun compositeKeyOf(password: CharArray): ByteArray = compositeKeyOf(password, null)

        /** Chiave composta KeePass: SHA-256 di [SHA-256(password)] + [hash del file chiave]. Almeno uno dei due. */
        fun compositeKeyOf(password: CharArray?, keyFile: ByteArray?): ByteArray {
            require(password != null || keyFile != null) { "Serve almeno una password o un file chiave" }
            val parts = ArrayList<ByteArray>()
            if (password != null) {
                val bytes = String(password).toByteArray(Charsets.UTF_8)
                parts += Crypto.sha256(bytes); bytes.fill(0)
            }
            if (keyFile != null) parts += keyFileHash(keyFile)
            return Crypto.sha256(*parts.toTypedArray())
        }

        /** Formati di file chiave: XML v1/v2, 32 byte grezzi, 64 caratteri esadecimali, o SHA-256 del contenuto. */
        internal fun keyFileHash(c: ByteArray): ByteArray {
            if (c.size > 1 shl 20) return Crypto.sha256(c)
            val text = String(c, Charsets.UTF_8)
            if (text.contains("<KeyFile>")) {
                try {
                    val d = secureFactory().newDocumentBuilder().parse(ByteArrayInputStream(c))
                    val data = d.getElementsByTagName("Data").item(0) as Element
                    val version = d.getElementsByTagName("Version").item(0)?.textContent?.trim() ?: "1.0"
                    val raw = if (version.startsWith("2")) hexOf(data.textContent.filter { !it.isWhitespace() })
                              else b64d.decode(data.textContent.trim())
                    if (raw.size == 32) return raw
                } catch (e: Exception) { /* non è un XML valido: si usa l'hash del contenuto */ }
            }
            if (c.size == 32) return c.copyOf()
            if (c.size == 64 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return hexOf(text)
            return Crypto.sha256(c)
        }
        private fun hexOf(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun open(data: ByteArray, password: CharArray): KdbxDatabase = openWithKey(data, compositeKeyOf(password))
        fun open(data: ByteArray, password: CharArray?, keyFile: ByteArray?): KdbxDatabase =
            openWithKey(data, compositeKeyOf(password, keyFile))

        fun openWithKey(data: ByteArray, composite: ByteArray): KdbxDatabase {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (data.size < 12 || bb.getInt() != 0x9AA2D903.toInt() || bb.getInt() != 0xB54BFB67.toInt())
                throw UnsupportedFormatException("Non è un file KeePass")
            bb.getShort(); val major = bb.getShort().toInt()
            if (major == 3) return openV3(data, composite)
            if (major != 4) throw UnsupportedFormatException("Versione del formato KDBX non supportata (${major})")
            val fields = LinkedHashMap<Int, ByteArray>()
            try {
                while (true) {
                    val id = bb.get().toInt() and 0xFF
                    val size = bb.getInt()
                    require(size in 0..bb.remaining())
                    val d = ByteArray(size).also { bb.get(it) }
                    if (id == 0) break
                    fields[id] = d
                }
            } catch (e: RuntimeException) { throw UnsupportedFormatException("Intestazione non valida") }
            val headerEnd = bb.position()
            if (data.size < headerEnd + 64) throw UnsupportedFormatException("File troncato")
            val headerBytes = data.copyOfRange(0, headerEnd)
            if (!Crypto.equal(data.copyOfRange(headerEnd, headerEnd + 32), Crypto.sha256(headerBytes)))
                throw UnsupportedFormatException("File danneggiato")

            val seed = fields[4]; val iv = fields[7]; val cipherId = fields[2]; val kdfRaw = fields[11]
            if (seed == null || iv == null || cipherId == null || kdfRaw == null) throw UnsupportedFormatException("Intestazione incompleta")
            val compression = ByteBuffer.wrap(fields[3] ?: Crypto.le4(0)).order(ByteOrder.LITTLE_ENDIAN).getInt()
            val kdf = VarDict.parse(kdfRaw)

            val transformed = Kdf.transform(kdf, composite)
            val tcopy = transformed.copyOf()
            val hmacBase = Crypto.sha512(seed, transformed, byteArrayOf(1))
            val cipherKey = Crypto.sha256(seed, transformed)
            transformed.fill(0)
            // L'HMAC dell'intestazione dipende dalla chiave: se non torna, la password è sbagliata.
            if (!Crypto.equal(data.copyOfRange(headerEnd + 32, headerEnd + 64),
                    Crypto.hmacSha256(Crypto.blockKey(hmacBase, -1L), headerBytes))) throw WrongCredentialsException()

            val bb2 = ByteBuffer.wrap(data, headerEnd + 64, data.size - headerEnd - 64).order(ByteOrder.LITTLE_ENDIAN)
            val ct = ByteArrayOutputStream()
            var index = 0L
            while (true) {
                if (bb2.remaining() < 36) throw UnsupportedFormatException("File troncato")
                val mac = ByteArray(32).also { bb2.get(it) }
                val size = bb2.getInt()
                if (size < 0 || size > bb2.remaining()) throw UnsupportedFormatException("File troncato")
                val chunk = ByteArray(size).also { bb2.get(it) }
                if (!Crypto.equal(mac, Crypto.hmacSha256(Crypto.blockKey(hmacBase, index), Crypto.le8(index), Crypto.le4(size), chunk)))
                    throw UnsupportedFormatException("File danneggiato o manomesso")
                if (size == 0) break
                ct.write(chunk); index++
            }
            var plain = decrypt(cipherId, cipherKey, iv, ct.toByteArray())
            if (compression == 1) {
                plain = try { GZIPInputStream(ByteArrayInputStream(plain)).readBytes() }
                catch (e: java.io.IOException) { throw UnsupportedFormatException("Dati compressi non validi") }
            }

            // Intestazione interna
            val pb = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
            var streamId = 0; var innerKey = ByteArray(0); val bins = ArrayList<ByteArray>()
            try {
                while (true) {
                    val id = pb.get().toInt() and 0xFF
                    val size = pb.getInt(); require(size in 0..pb.remaining())
                    val d = ByteArray(size).also { pb.get(it) }
                    when (id) { 0 -> break; 1 -> streamId = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN).getInt(); 2 -> innerKey = d; 3 -> bins += d }
                }
            } catch (e: RuntimeException) { throw UnsupportedFormatException("Intestazione interna non valida") }

            val doc = parseXml(plain.copyOfRange(pb.position(), plain.size))
            xorProtected(doc, innerStream(streamId, innerKey), encrypt = false)
            return KdbxDatabase(doc, composite.copyOf(), fields, kdf, bins, streamId, transformedInit = tcopy)
        }


        // ---- KDBX 3 (sola lettura: al salvataggio diventa KDBX 4) ----
        private fun openV3(data: ByteArray, composite: ByteArray): KdbxDatabase {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); bb.position(12)
            val f = HashMap<Int, ByteArray>()
            try {
                while (true) {
                    val id = bb.get().toInt() and 0xFF
                    val size = bb.getShort().toInt() and 0xFFFF
                    require(size <= bb.remaining())
                    val d = ByteArray(size).also { bb.get(it) }
                    if (id == 0) break
                    f[id] = d
                }
            } catch (e: RuntimeException) { throw UnsupportedFormatException("Intestazione non valida") }
            val cipherId = f[2]; val seed = f[4]; val tseed = f[5]; val rounds = f[6]; val iv = f[7]
            val skey = f[8]; val start = f[9]; val sid = f[10]
            if (cipherId == null || seed == null || tseed == null || rounds == null || iv == null || skey == null || start == null || sid == null)
                throw UnsupportedFormatException("Intestazione incompleta")
            val compression = ByteBuffer.wrap(f[3] ?: Crypto.le4(0)).order(ByteOrder.LITTLE_ENDIAN).getInt()
            val kdf = VarDict().apply {
                items["\$UUID"] = VarDict.Item(0x42, Kdf.AES_KDF); items["S"] = VarDict.Item(0x42, tseed)
                items["R"] = VarDict.Item(0x05, ByteBuffer.wrap(rounds).order(ByteOrder.LITTLE_ENDIAN).getLong())
            }
            val transformed = Kdf.transform(kdf, composite)
            val key = Crypto.sha256(seed, transformed); transformed.fill(0)
            val plain = try {
                decrypt(cipherId, key, iv, data.copyOfRange(bb.position(), data.size))
            } catch (e: Exception) { throw WrongCredentialsException() } // con chiave errata il padding non torna
            if (plain.size < 32 || !Crypto.equal(plain.copyOfRange(0, 32), start)) throw WrongCredentialsException()

            // Flusso di blocchi con hash SHA-256
            val pb = ByteBuffer.wrap(plain, 32, plain.size - 32).order(ByteOrder.LITTLE_ENDIAN)
            val joined = ByteArrayOutputStream()
            var expected = 0
            while (true) {
                if (pb.remaining() < 40) throw UnsupportedFormatException("File troncato")
                val idx = pb.getInt(); val hash = ByteArray(32).also { pb.get(it) }; val size = pb.getInt()
                if (idx != expected++ || size < 0 || size > pb.remaining()) throw UnsupportedFormatException("File danneggiato")
                if (size == 0) break
                val chunk = ByteArray(size).also { pb.get(it) }
                if (!Crypto.equal(hash, Crypto.sha256(chunk))) throw UnsupportedFormatException("File danneggiato o manomesso")
                joined.write(chunk)
            }
            var xml = joined.toByteArray()
            if (compression == 1) {
                xml = try { GZIPInputStream(ByteArrayInputStream(xml)).readBytes() }
                catch (e: java.io.IOException) { throw UnsupportedFormatException("Dati compressi non validi") }
            }
            val streamId = ByteBuffer.wrap(sid).order(ByteOrder.LITTLE_ENDIAN).getInt()
            val doc = parseXml(xml)
            xorProtected(doc, innerStream(streamId, skey), encrypt = false)
            val bins = convertBinaries(doc)
            // Conversione: stesso cifrario e compressione, KDF nuova (Argon2d), cifrario interno ChaCha20.
            val fields = linkedMapOf(2 to cipherId, 3 to Crypto.le4(compression))
            val newKdf = Kdf.newArgon2d(salt = ByteArray(32).also(SecureRandom()::nextBytes))
            return KdbxDatabase(doc, composite.copyOf(), fields, newKdf, bins, 3, upgradedFromV3 = true)
        }

        /** In KDBX 3 gli allegati stanno in Meta/Binaries; in KDBX 4 nell'intestazione interna. */
        private fun convertBinaries(doc: Document): List<ByteArray> {
            val meta = doc.documentElement.child("Meta") ?: return emptyList()
            val holder = meta.child("Binaries") ?: return emptyList()
            val map = HashMap<String, Int>(); val out = ArrayList<ByteArray>()
            for (b in holder.children("Binary")) {
                var raw = try { Base64.getMimeDecoder().decode(b.textContent.trim()) } catch (e: IllegalArgumentException) { throw UnsupportedFormatException("Allegato non valido") }
                if (b.getAttribute("Compressed").equals("True", ignoreCase = true)) {
                    raw = try { GZIPInputStream(ByteArrayInputStream(raw)).readBytes() } catch (e: java.io.IOException) { throw UnsupportedFormatException("Allegato non valido") }
                }
                map[b.getAttribute("ID")] = out.size
                out += byteArrayOf(0) + raw
            }
            meta.removeChild(holder)
            val values = doc.getElementsByTagName("Value")
            for (i in 0 until values.length) {
                val e = values.item(i) as Element
                if (e.hasAttribute("Ref")) map[e.getAttribute("Ref")]?.let { e.setAttribute("Ref", it.toString()) }
            }
            return out
        }

        /** Nuovo database vuoto (AES-256, Argon2d 64 MiB, gzip). */
        fun create(name: String, password: CharArray): KdbxDatabase = create(name, compositeKeyOf(password))

        fun create(name: String, composite: ByteArray): KdbxDatabase {
            val r = SecureRandom()
            val now = nowIso()
            val xml = """<?xml version="1.0" encoding="utf-8" standalone="yes"?>
<KeePassFile><Meta><Generator>Custode</Generator><DatabaseName>${esc(name)}</DatabaseName><DatabaseNameChanged>$now</DatabaseNameChanged><DatabaseDescription></DatabaseDescription><DatabaseDescriptionChanged>$now</DatabaseDescriptionChanged><DefaultUserName></DefaultUserName><DefaultUserNameChanged>$now</DefaultUserNameChanged><MaintenanceHistoryDays>365</MaintenanceHistoryDays><Color></Color><MasterKeyChanged>$now</MasterKeyChanged><MasterKeyChangeRec>-1</MasterKeyChangeRec><MasterKeyChangeForce>-1</MasterKeyChangeForce><MemoryProtection><ProtectTitle>False</ProtectTitle><ProtectUserName>False</ProtectUserName><ProtectPassword>True</ProtectPassword><ProtectURL>False</ProtectURL><ProtectNotes>False</ProtectNotes></MemoryProtection><RecycleBinEnabled>False</RecycleBinEnabled><HistoryMaxItems>10</HistoryMaxItems><HistoryMaxSize>6291456</HistoryMaxSize><Binaries/><CustomData/></Meta><Root><Group><UUID>${newUuid()}</UUID><Name>${esc(name)}</Name><Notes></Notes><IconID>48</IconID>${times(now)}<IsExpanded>True</IsExpanded><DefaultAutoTypeSequence></DefaultAutoTypeSequence><EnableAutoType>null</EnableAutoType><EnableSearching>null</EnableSearching></Group><DeletedObjects/></Root></KeePassFile>"""
            val fields = linkedMapOf(2 to CIPHER_AES, 3 to Crypto.le4(1))
            val kdf = Kdf.newArgon2d(salt = ByteArray(32).also(r::nextBytes))
            return KdbxDatabase(parseXml(xml.toByteArray(Charsets.UTF_8)), composite.copyOf(), fields, kdf, emptyList(), 3)
        }

        internal fun nowIso(): String = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        internal fun newUuid(): String {
            val u = UUID.randomUUID()
            return b64.encodeToString(ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array())
        }
        internal fun times(now: String) =
            "<Times><CreationTime>$now</CreationTime><LastModificationTime>$now</LastModificationTime><LastAccessTime>$now</LastAccessTime><ExpiryTime>$now</ExpiryTime><Expires>False</Expires><UsageCount>0</UsageCount><LocationChanged>$now</LocationChanged></Times>"
        internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun secureFactory(): DocumentBuilderFactory {
            val f = DocumentBuilderFactory.newInstance()
            // Il file può essere ostile: niente DTD né entità esterne.
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { f.isXIncludeAware = false; f.isExpandEntityReferences = false }
            return f
        }

        private fun parseXml(b: ByteArray): Document {
            val f = secureFactory()
            try {
                val d = f.newDocumentBuilder().parse(ByteArrayInputStream(b))
                require(d.documentElement.tagName == "KeePassFile" && d.documentElement.child("Root")?.child("Group") != null)
                return d
            } catch (e: Exception) { throw UnsupportedFormatException("Contenuto del database non valido") }
        }

        internal fun xorProtected(doc: Document, stream: StreamCipher, encrypt: Boolean) {
            val values = doc.getElementsByTagName("Value")
            for (i in 0 until values.length) {
                val e = values.item(i) as Element
                if (!e.getAttribute("Protected").equals("True", ignoreCase = true)) continue
                if (encrypt) {
                    val p = e.textContent.toByteArray(Charsets.UTF_8)
                    val o = ByteArray(p.size); stream.processBytes(p, 0, p.size, o, 0)
                    e.textContent = b64.encodeToString(o)
                } else {
                    val c = try { b64d.decode(e.textContent.trim()) } catch (ex: IllegalArgumentException) { throw UnsupportedFormatException("Valore protetto non valido") }
                    val o = ByteArray(c.size); stream.processBytes(c, 0, c.size, o, 0)
                    e.textContent = String(o, Charsets.UTF_8)
                }
            }
        }

        internal fun innerStream(id: Int, key: ByteArray): StreamCipher = when (id) {
            3 -> { val h = Crypto.sha512(key); ChaCha7539Engine().apply { init(true, ParametersWithIV(KeyParameter(h, 0, 32), h.copyOfRange(32, 44))) } }
            2 -> Salsa20Engine().apply { init(true, ParametersWithIV(KeyParameter(Crypto.sha256(key)), SALSA_IV)) }
            else -> throw UnsupportedFormatException("Cifrario interno non supportato")
        }

        private fun decrypt(id: ByteArray, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = when {
            id.contentEquals(CIPHER_AES) -> try {
                Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data) }
            } catch (e: java.security.GeneralSecurityException) { throw UnsupportedFormatException("File danneggiato") }
            id.contentEquals(CIPHER_CHACHA20) -> ChaCha7539Engine().run {
                init(false, ParametersWithIV(KeyParameter(key), iv)); ByteArray(data.size).also { processBytes(data, 0, data.size, it, 0) }
            }
            else -> throw UnsupportedFormatException("Cifrario del database non supportato")
        }

        private fun encrypt(id: ByteArray, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = when {
            id.contentEquals(CIPHER_AES) ->
                Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data) }
            else -> ChaCha7539Engine().run {
                init(true, ParametersWithIV(KeyParameter(key), iv)); ByteArray(data.size).also { processBytes(data, 0, data.size, it, 0) }
            }
        }
    }
}

// ---- Modello: gruppi e voci come "viste" sul DOM ----

internal fun Element.children(name: String): List<Element> {
    val out = ArrayList<Element>(); var n = firstChild
    while (n != null) { if (n is Element && n.tagName == name) out += n; n = n.nextSibling }
    return out
}
internal fun Element.child(name: String): Element? = children(name).firstOrNull()

class KdbxGroup internal constructor(internal val el: Element, private val db: KdbxDatabase) {
    val id: String get() = el.child("UUID")?.textContent ?: ""
    var name: String
        get() = el.child("Name")?.textContent ?: ""
        set(v) { el.child("Name")?.textContent = v; KdbxEntry.touch(el) }
    val groups: List<KdbxGroup> get() = el.children("Group").map { KdbxGroup(it, db) }
    val entries: List<KdbxEntry> get() = el.children("Entry").map { KdbxEntry(it, db) }

    fun entriesRecursive(): List<KdbxEntry> = entries + groups.flatMap { it.entriesRecursive() }

    fun addGroup(name: String): KdbxGroup {
        val now = KdbxDatabase.nowIso()
        val g = el.ownerDocument.createElement("Group")
        listOf("UUID" to KdbxDatabase.newUuid(), "Name" to name, "Notes" to "", "IconID" to "48").forEach { (k, v) ->
            g.appendChild(el.ownerDocument.createElement(k).also { it.textContent = v })
        }
        g.appendChild(timesElement(el.ownerDocument, now))
        // Prima degli eventuali elementi non-Group/Entry? Basta accodare: l'ordine tra Group ed Entry è libero.
        el.appendChild(g)
        return KdbxGroup(g, db)
    }

    fun addEntry(title: String, username: String = "", password: String = "", url: String = "", notes: String = ""): KdbxEntry {
        val doc = el.ownerDocument; val now = KdbxDatabase.nowIso()
        val e = doc.createElement("Entry")
        e.appendChild(doc.createElement("UUID").also { it.textContent = KdbxDatabase.newUuid() })
        e.appendChild(doc.createElement("IconID").also { it.textContent = "0" })
        e.appendChild(timesElement(doc, now))
        el.appendChild(e)
        return KdbxEntry(e, db).also {
            it.title = title; it.username = username; it.password = password; it.url = url; it.notes = notes
        }
    }

    /** Elimina la voce e lascia traccia in DeletedObjects (per la sincronizzazione con altre app). */
    fun remove(entry: KdbxEntry) { el.removeChild(entry.el); tombstone(entry.id) }
    fun remove(group: KdbxGroup) { el.removeChild(group.el); tombstone(group.id) }

    private fun tombstone(uuid: String) {
        val doc = el.ownerDocument
        val del = doc.documentElement.child("Root")!!.child("DeletedObjects") ?: return
        del.appendChild(doc.createElement("DeletedObject").also { d ->
            d.appendChild(doc.createElement("UUID").also { it.textContent = uuid })
            d.appendChild(doc.createElement("DeletionTime").also { it.textContent = KdbxDatabase.nowIso() })
        })
    }

    private fun timesElement(doc: Document, now: String): Element {
        val t = doc.createElement("Times")
        listOf("CreationTime" to now, "LastModificationTime" to now, "LastAccessTime" to now, "ExpiryTime" to now,
            "Expires" to "False", "UsageCount" to "0", "LocationChanged" to now)
            .forEach { (k, v) -> t.appendChild(doc.createElement(k).also { it.textContent = v }) }
        return t
    }
}

class KdbxEntry internal constructor(internal val el: Element, private val db: KdbxDatabase) {
    val id: String get() = el.child("UUID")?.textContent ?: ""
    var title: String get() = field("Title"); set(v) = setField("Title", v)
    var username: String get() = field("UserName"); set(v) = setField("UserName", v)
    var password: String get() = field("Password"); set(v) = setField("Password", v, protect = true)
    var url: String get() = field("URL"); set(v) = setField("URL", v)
    var notes: String get() = field("Notes"); set(v) = setField("Notes", v)

    fun field(key: String): String = stringEl(key)?.child("Value")?.textContent ?: ""

    fun setField(key: String, value: String, protect: Boolean = false) {
        val doc = el.ownerDocument
        val existing = stringEl(key)
        if (existing != null) {
            existing.child("Value")?.textContent = value
        } else {
            val s = doc.createElement("String")
            s.appendChild(doc.createElement("Key").also { it.textContent = key })
            s.appendChild(doc.createElement("Value").also {
                it.textContent = value
                if (protect) it.setAttribute("Protected", "True")
            })
            val last = el.children("String").lastOrNull()
            val anchor = last?.nextSibling ?: el.child("Times")?.nextSibling
            if (anchor != null) el.insertBefore(s, anchor) else el.appendChild(s)
        }
        touch(el)
    }

    /** Campi personalizzati (tutto tranne i cinque standard). */
    val customFields: Map<String, String>
        get() = el.children("String").associate { (it.child("Key")?.textContent ?: "") to (it.child("Value")?.textContent ?: "") }
            .filterKeys { it !in setOf("Title", "UserName", "Password", "URL", "Notes") }

    private fun stringEl(key: String) = el.children("String").firstOrNull { it.child("Key")?.textContent == key }

    companion object {
        internal fun touch(e: Element) {
            e.child("Times")?.child("LastModificationTime")?.textContent = KdbxDatabase.nowIso()
        }
    }
}
