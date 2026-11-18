package it.vault.custode

import it.vault.custode.kdbx.KdbxDatabase
import it.vault.custode.kdbx.UnsupportedFormatException
import it.vault.custode.kdbx.WrongCredentialsException
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Il database è un normale file KDBX 4 (`vault.kdbx`) nella memoria privata dell'app.
 * Le operazioni lente (Argon2) vanno chiamate fuori dal thread UI; [save] è rapido.
 */
class VaultStore(dir: File) {
    private val dirPath = dir.absolutePath
    private val file = File(dir, "vault.kdbx")
    var db: KdbxDatabase? = null
        private set

    val exists: Boolean get() = file.exists()

    fun create(name: String, password: CharArray?, keyFile: ByteArray?) {
        val d = KdbxDatabase.create(name, KdbxDatabase.compositeKeyOf(password, keyFile))
        d.prepare()
        db = d
        save()
    }

    fun open(password: CharArray?, keyFile: ByteArray?) = openWith(KdbxDatabase.compositeKeyOf(password, keyFile))

    fun openWith(composite: ByteArray) {
        val d = KdbxDatabase.openWithKey(file.readBytes(), composite)
        d.prepare()
        db?.close()
        db = d
        if (d.upgradedFromV3) save() // l'app scrive solo KDBX 4
    }

    /** Sostituisce il database con uno esistente (es. creato con KeePassXC), dopo averne verificato l'apertura. */
    fun replaceWith(bytes: ByteArray, password: CharArray?, keyFile: ByteArray?): Boolean {
        val d = KdbxDatabase.open(bytes, password, keyFile)
        d.prepare()
        db?.close()
        db = d
        save()
        return d.upgradedFromV3
    }

    fun changeKey(password: CharArray?, keyFile: ByteArray?) {
        val d = checkNotNull(db)
        d.changeKey(KdbxDatabase.compositeKeyOf(password, keyFile))
        d.prepare()
        save()
    }

    fun save() {
        val bytes = checkNotNull(db).toBytes()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun exportTo(out: OutputStream) { file.inputStream().use { it.copyTo(out) } }

    fun lock() { db?.close(); db = null }

    companion object {
        @Volatile private var instance: VaultStore? = null

        /** Un solo archivio per processo: lo condividono l'app e il servizio di compilazione automatica. */
        fun get(ctx: android.content.Context): VaultStore {
            val dir = ctx.applicationContext.filesDir
            instance?.takeIf { it.dirPath == dir.absolutePath }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.dirPath == dir.absolutePath } ?: VaultStore(dir).also { instance = it }
            }
        }

        fun describe(e: Throwable): String = when (e) {
            is WrongCredentialsException -> "Password o file chiave errati"
            is UnsupportedFormatException -> e.message ?: "File non supportato"
            is OutOfMemoryError -> "Memoria insufficiente per aprire questo database"
            else -> "Errore: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
