package it.vault.custode.importer

import org.junit.Assert.*
import org.junit.Test

class TextImporterTest {
    private fun p(line: String) = TextImporter.parseLine(line)
    private fun assertFields(x: Parsed, title: String, user: String, pw: String, url: String = "") {
        assertEquals("titolo", title, x.title); assertEquals("utente", user, x.user)
        assertEquals("password", pw, x.password); assertEquals("url", url, x.url)
    }

    @Test fun titleEmailPassword() {
        val x = p("Gmail mario@example.com Abc123!x"); assertFields(x, "Gmail", "mario@example.com", "Abc123!x"); assertTrue(x.confident)
    }
    @Test fun semicolonSeparated() {
        val x = p("Netflix;luca@x.it;frase con spazi"); assertFields(x, "Netflix", "luca@x.it", "frase con spazi"); assertTrue(x.confident)
    }
    @Test fun siteUserPasswordWithDashes() {
        val x = p("banca.it - mrossi - Segreto99"); assertFields(x, "banca.it", "mrossi", "Segreto99", "banca.it"); assertTrue(x.confident)
    }
    @Test fun explicitLabels() {
        assertFields(p("Amazon user: lucia pass: Zz9!zz"), "Amazon", "lucia", "Zz9!zz")
        assertFields(p("Facebook utente=anna.rossi password=ciao123"), "Facebook", "anna.rossi", "ciao123")
        val x = p("https://www.posta.it username: gino Password: x1y2"); assertEquals("gino", x.user); assertEquals("x1y2", x.password); assertEquals("posta.it", x.title)
    }
    @Test fun titleAndPasswordOnly() {
        val x = p("Bancomat 4821"); assertFields(x, "Bancomat", "", "4821"); assertTrue(x.confident)
    }
    @Test fun threeWordsIsAGuessAndFlagged() {
        val x = p("Conto Intesa abc123"); assertFalse("ipotesi: va controllata", x.confident); assertEquals("abc123", x.password)
    }
    @Test fun unparseableLineKeptWholeInNotes() {
        val raw = "Wifi di casa del mare rete Fastweb codice scritto sul router"
        val x = p(raw); assertFalse(x.confident); assertEquals(raw, x.notes); assertEquals("", x.password)
    }
    @Test fun singleWordGoesToNotes() {
        val x = p("Appunti"); assertFalse(x.confident); assertEquals("Appunti", x.notes)
    }
    @Test fun linkOnlyWithPassword() {
        val x = p("mail.example.com;nome@example.com;pw-segreta"); assertEquals("pw-segreta", x.password); assertEquals("nome@example.com", x.user); assertEquals("mail.example.com", x.url)
    }
    @Test fun skipsDecorationCommentsAndBlankLines() {
        val r = TextImporter.parse("# elenco\n\n-----\nGmail mario@x.it pw1\n====\n// nota\nBancomat 1234\n")
        assertEquals(listOf("Gmail", "Bancomat"), r.map { it.title })
    }
    @Test fun decodesUtf8AndWindows1252() {
        assertEquals("Città è", TextImporter.decode("Città è".toByteArray(Charsets.UTF_8)))
        assertEquals("Città è", TextImporter.decode("Città è".toByteArray(charset("windows-1252"))))
        assertEquals("ok", TextImporter.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ok".toByteArray()))
    }
    @Test fun neverLosesALine() {
        val lines = listOf("Gmail a@b.it pw", "roba strana senza senso ma con molte parole in fila", "X", "sito.it;u;p", "A B C D E F G")
        val out = TextImporter.parse(lines.joinToString("\n"))
        assertEquals(lines.size, out.size)
        out.forEach { assertTrue(it.title.isNotBlank() || it.notes.isNotBlank() || it.password.isNotBlank()) }
    }

    // ---- formato "Nome;Utente;Password;" ----
    @Test fun oneEntryPerLineWithTrailingSemicolon() {
        val r = TextImporter.parse("Gmail;mario@x.it;Pw-1;\nBanca;mrossi;Segreto99;\nWifi;casa;frase lunga con spazi;")
        assertEquals(3, r.size); assertTrue(r.all { it.confident })
        assertEquals(Triple("Gmail", "mario@x.it", "Pw-1"), Triple(r[0].title, r[0].user, r[0].password))
        assertEquals("frase lunga con spazi", r[2].password)
    }
    @Test fun oneEntryPerLineWithoutTrailingSemicolon() {
        val r = TextImporter.parse("Gmail;mario;pw1\nBanca;mrossi;pw2")
        assertTrue(r.all { it.confident }); assertEquals("pw2", r[1].password)
    }
    @Test fun threeLinesPerEntrySeparatedByBlankLines() {
        val r = TextImporter.parse("Gmail;\nmario@x.it;\nPw-1;\n\nBanca;\nmrossi;\nSegreto99;\n")
        assertEquals(2, r.size); assertTrue(r.all { it.confident })
        assertEquals(Triple("Banca", "mrossi", "Segreto99"), Triple(r[1].title, r[1].user, r[1].password))
    }
    @Test fun threeLinesPerEntryWithoutBlankLines() {
        val r = TextImporter.parse("Gmail;\nmario@x.it;\nPw-1;\nBanca;\nmrossi;\nSegreto99;")
        assertEquals(listOf("Gmail", "Banca"), r.map { it.title }); assertTrue(r.all { it.confident })
        assertEquals("Segreto99", r[1].password)
    }
    @Test fun semicolonInsidePasswordAndEmptyUserAreKept() {
        val r = TextImporter.parse("Sito;\n;\npa;ss;\n\nAltro;\nuser;\npw;")
        assertEquals(Triple("Sito", "", "pa;ss"), Triple(r[0].title, r[0].user, r[0].password))
        assertEquals("pw", r[1].password)
    }
    @Test fun incompleteBlockIsFlaggedAndKeptInNotes() {
        val r = TextImporter.parse("Gmail;\nmario;\nPw-1;\n\nBanca;\nmrossi;\n\nSito;\nu;\np;")
        assertEquals(3, r.size)
        assertFalse(r[1].confident); assertTrue(r[1].notes.contains("mrossi"))
        assertTrue(r[0].confident && r[2].confident)
    }
    @Test fun freeTextStillUsesTheGeneralParser() {
        val r = TextImporter.parse("Gmail mario@x.it Pw-1\nBancomat 1234")
        assertEquals(listOf("Gmail", "Bancomat"), r.map { it.title })
    }
    @Test fun strayBlankLineDoesNotBreakConsecutiveTriples() {
        val body = (1..10).joinToString("\n") { "Sito$it;\nu$it;\npw$it;" }
        val r = TextImporter.parse(body.replace("pw5;", "pw5;\n"))      // una riga vuota capitata per caso
        assertEquals(10, r.size); assertTrue(r.all { it.confident }); assertEquals("pw7", r[6].password)
    }
}
