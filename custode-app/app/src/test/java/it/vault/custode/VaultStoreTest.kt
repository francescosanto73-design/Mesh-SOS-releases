package it.vault.custode

import it.vault.custode.kdbx.WrongCredentialsException
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VaultStoreTest {
    private fun dir() = Files.createTempDirectory("custode").toFile()

    @Test fun createReopenAndEdit() {
        val d = dir()
        val s = VaultStore(d)
        assertFalse(s.exists)
        s.create("Test", "frase lunga 123".toCharArray(), null)
        s.db!!.root.addEntry("Gmail", "me@x.it", "p@ss-ünï", "https://mail.google.com", "nota\nmultilinea")
        s.db!!.root.addGroup("Lavoro").addEntry("VPN", "u", "v", "", "")
        s.save()
        s.lock(); assertNull(s.db)

        val s2 = VaultStore(d)
        assertTrue(s2.exists)
        s2.open("frase lunga 123".toCharArray(), null)
        val all = s2.db!!.allEntries().associateBy { it.title }
        assertEquals("p@ss-ünï", all["Gmail"]!!.password)
        assertEquals("nota\nmultilinea", all["Gmail"]!!.notes)
        assertEquals("v", all["VPN"]!!.password)
        // il file è un KDBX valido (firma 03 D9 A2 9A 67 FB 4B B5)
        val head = java.io.File(d, "vault.kdbx").readBytes().copyOf(8)
        assertArrayEquals(byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()), head)
    }

    @Test fun wrongPasswordRejected() {
        val d = dir(); val s = VaultStore(d)
        s.create("T", "giusta-123".toCharArray(), null); s.lock()
        try { VaultStore(d).open("sbagliata".toCharArray(), null); fail("doveva fallire") }
        catch (e: WrongCredentialsException) { /* ok */ }
    }

    @Test fun passwordInFileIsNotPlaintext() {
        val d = dir(); val s = VaultStore(d)
        s.create("T", "giusta-123".toCharArray(), null)
        s.db!!.root.addEntry("Banca", "u", "SEGRETISSIMA-PAROLA", "", ""); s.save()
        val raw = String(java.io.File(d, "vault.kdbx").readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("SEGRETISSIMA")); assertFalse(raw.contains("Banca"))
    }

    @Test fun changeKeyAndKeyFile() {
        val d = dir(); val s = VaultStore(d)
        s.create("T", "vecchia-123".toCharArray(), null)
        s.db!!.root.addEntry("X", "u", "p", "", ""); s.save()
        val kf = ByteArray(100) { it.toByte() }
        s.changeKey("nuova-456".toCharArray(), kf); s.lock()
        try { VaultStore(d).open("vecchia-123".toCharArray(), null); fail() } catch (e: WrongCredentialsException) {}
        try { VaultStore(d).open("nuova-456".toCharArray(), null); fail() } catch (e: WrongCredentialsException) {}
        val s3 = VaultStore(d); s3.open("nuova-456".toCharArray(), kf)
        assertEquals("p", s3.db!!.allEntries().single().password)
    }

    @Test fun deleteLeavesNoTrace() {
        val d = dir(); val s = VaultStore(d)
        s.create("T", "pw-123456".toCharArray(), null)
        val e = s.db!!.root.addEntry("Da eliminare", "u", "p", "", ""); s.save()
        s.db!!.delete(e); s.save(); s.lock()
        val s2 = VaultStore(d); s2.open("pw-123456".toCharArray(), null)
        assertTrue(s2.db!!.allEntries().isEmpty())
    }

    @Test fun generatorRespectsOptions() {
        repeat(200) {
            val p = PasswordGenerator.generate(PasswordGenerator.Options(length = 24, symbols = false))
            assertEquals(24, p.length); assertTrue(p.all { it.isLetterOrDigit() })
            assertTrue(p.any { it.isLowerCase() } && p.any { it.isUpperCase() } && p.any { it.isDigit() })
        }
        assertNotEquals(PasswordGenerator.generate(), PasswordGenerator.generate())
    }
}
