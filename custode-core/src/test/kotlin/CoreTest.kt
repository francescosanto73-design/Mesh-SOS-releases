import it.vault.custode.kdbx.*
import kotlin.test.*

class TotpTest {
    // Vettori di prova dell'RFC 6238 (appendice B), 8 cifre.
    private val sha1 = Totp.base32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")!!                                   // "12345678901234567890"
    private val sha256 = Totp.base32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA")!!            // 32 byte
    private val sha512 = Totp.base32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA")!! // 64 byte

    @Test fun rfc6238Vectors() {
        val t = listOf(59L to ("94287082" to "46119246"), 1111111109L to ("07081804" to "68084774"), 1234567890L to ("89005924" to "91819424"), 20000000000L to ("65353130" to "77737706"))
        for ((time, exp) in t) {
            assertEquals(exp.first, Totp.code(TotpSpec(sha1, 30, 8, "SHA1"), time), "SHA1 t=$time")
        }
        assertEquals("46119246", Totp.code(TotpSpec(sha256, 30, 8, "SHA256"), 59))
        assertEquals("90693936", Totp.code(TotpSpec(sha512, 30, 8, "SHA512"), 59))
        assertEquals("68084774", Totp.code(TotpSpec(sha256, 30, 8, "SHA256"), 1111111109))
    }

    @Test fun parseUris() {
        val s = Totp.parse("otpauth://totp/Esempio:mario%40x.it?secret=JBSWY3DPEHPK3PXP&issuer=Esempio&digits=8&period=60&algorithm=SHA256")!!
        assertEquals(8, s.digits); assertEquals(60, s.period); assertEquals("SHA256", s.algorithm)
        assertNull(Totp.parse("jbsw y3dp ehpk 3pxp"))                  // segreto nudo: ambiguo, non letto
        assertNull(Totp.parse("JBSWY3DPEHPK3PXP;30;6"))
        assertNotNull(Totp.parse("key=JBSWY3DPEHPK3PXP&size=6&step=30"))
        assertNull(Totp.parse("otpauth://hotp/X?secret=JBSWY3DPEHPK3PXP&counter=1"))
        assertNull(Totp.parse("otpauth://totp/X?secret=JBSWY3DPEHPK3PXP&encoder=steam"))
        assertNull(Totp.parse("non è base32 !!!"))
        assertNull(Totp.parse("otpauth://totp/X?secret=JBSWY3DPEHPK3PXP&digits=99"))
        assertEquals(Totp.code(Totp.parse(Totp.uri("JBSWY3DPEHPK3PXP", "Esempio", "mario@x.it")!!)!!, 59),
                     Totp.code(TotpSpec(Totp.base32("JBSWY3DPEHPK3PXP")!!), 59))
    }
}

class ModelTest {
    private val pw = "pw-di-prova-123".toCharArray()
    private fun reopen(db: KdbxDatabase) = KdbxDatabase.open(db.toBytes(), pw)

    @Test fun historyKeepsOldVersionsOnlyWhenChanged() {
        var db = KdbxDatabase.create("T", pw)
        val e = db.root.addEntry("Sito", "u", "v1", "", "")
        db = reopen(db); val e1 = db.allEntries().single()
        db.edit(e1) { password = "v2" }
        db.edit(e1) { password = "v2" }                 // nessun cambiamento: nessuna nuova versione
        db.edit(e1) { password = "v3"; notes = "n" }
        db = reopen(db); val e3 = db.allEntries().single()
        assertEquals("v3", e3.password)
        assertEquals(listOf("v1", "v2"), e3.history.map { it.password })
        db.restoreVersion(e3, e3.history.first())
        db = reopen(db); val e4 = db.allEntries().single()
        assertEquals("v1", e4.password); assertEquals("", e4.notes)
        assertEquals(listOf("v1", "v2", "v3"), e4.history.map { it.password })
    }

    @Test fun historyLimit() {
        val db = KdbxDatabase.create("T", pw)
        val e = db.root.addEntry("X", "", "0", "", "")
        repeat(15) { i -> db.edit(e) { password = "p${i + 1}" } }
        assertEquals(10, e.history.size); assertEquals("p5", e.history.first().password)
    }

    @Test fun recycleBinFlow() {
        var db = KdbxDatabase.create("T", pw)
        val g = db.root.addGroup("Lavoro"); val e = g.addEntry("Server", "u", "p", "", "")
        db.root.addEntry("Altro", "u", "p", "", "")
        db.trash(e)
        assertNotNull(db.recycleBin); assertTrue(db.isInRecycleBin(e))
        assertEquals(listOf("Altro"), db.activeEntries().map { it.title })
        db = reopen(db)
        val moved = db.allEntries().first { it.title == "Server" }
        assertTrue(db.isInRecycleBin(moved))
        db.restore(moved)
        assertEquals("Lavoro", (db.root.groups.first { it.name == "Lavoro" }).name)
        assertEquals(listOf("Server"), db.root.groups.first { it.name == "Lavoro" }.entries.map { it.title })   // torna al gruppo di origine
        // gruppo intero nel cestino, poi svuota
        db.trash(db.root.groups.first { it.name == "Lavoro" })
        assertEquals(listOf("Altro"), db.activeEntries().map { it.title })
        db.emptyRecycleBin()
        assertEquals(listOf("Altro"), reopen(db).allEntries().map { it.title })
        // eliminare dal cestino è definitivo
        db.trash(db.activeEntries().single()); db.trash(db.allEntries().single())
        assertTrue(reopen(db).allEntries().isEmpty())
    }

    @Test fun attachmentsRoundTripAndCompaction() {
        var db = KdbxDatabase.create("T", pw)
        val e = db.root.addEntry("Doc", "", "", "", "")
        val a = ByteArray(5000) { (it * 7).toByte() }; val b = "ciao".toByteArray()
        db.addAttachment(e, "a.bin", a); db.addAttachment(e, "b.txt", b)
        db = reopen(db); val e1 = db.allEntries().single()
        assertEquals(listOf("a.bin", "b.txt"), e1.attachments)
        assertContentEquals(a, db.attachmentBytes(e1, "a.bin")); assertContentEquals(b, db.attachmentBytes(e1, "b.txt"))
        db.removeAttachment(e1, "a.bin")
        db = reopen(db); val e2 = db.allEntries().single()
        assertEquals(listOf("b.txt"), e2.attachments); assertContentEquals(b, db.attachmentBytes(e2, "b.txt"))
        assertNull(db.attachmentBytes(e2, "a.bin"))
    }

    @Test fun customFieldProtectionAndOtherAppsFieldsKept() {
        var db = KdbxDatabase.create("T", pw)
        val e = db.root.addEntry("X", "u", "p", "", "")
        e.setField("PIN", "1234", protect = true); e.setField("Nota", "visibile", protect = false)
        db = reopen(db); val e1 = db.allEntries().single()
        assertTrue(e1.isProtected("PIN")); assertFalse(e1.isProtected("Nota")); assertTrue(e1.isProtected("Password"))
        e1.title = "Y"                                  // modificare un campo non tocca la protezione degli altri
        assertTrue(e1.isProtected("PIN")); e1.removeField("Nota")
        assertEquals(mapOf("PIN" to "1234"), e1.customFields)
    }
}
