package it.vault.custode

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import it.vault.custode.kdbx.Totp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeaturesFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private fun waitText(t: String) = rule.waitUntil(30_000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() }
    private fun waitTag(t: String) = rule.waitUntil(30_000) { rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }

    @Test fun totpShownTrashAndRestore() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = VaultStore.get(ctx)
        store.create("T", "pw-features-1".toCharArray(), null)
        val e = store.db!!.root.addEntry("Banca", "mario", "pw", "", "")
        e.setField("otp", "otpauth://totp/Banca:mario?secret=JBSWY3DPEHPK3PXP&period=30&digits=6", protect = true)
        store.save()                                            // resta sbloccato: l'app lo riprende

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
        waitText("Banca")
        rule.onNodeWithText("Banca").performClick()
        waitTag("totp")
        val shown = rule.onNodeWithTag("totp").fetchSemanticsNode().config.first { it.key.name == "Text" }.value.toString()
        val digits = shown.substringAfter("Codice: ").substringBefore("(").filter { it.isDigit() }
        val expected = Totp.code(Totp.parse("otpauth://totp/Banca:mario?secret=JBSWY3DPEHPK3PXP")!!)
        // Il codice mostrato è quello corrente (o quello di un attimo prima, se la finestra è scattata durante il test).
        assertEquals(6, digits.length)
        assertTrue("$digits non è un codice TOTP valido", digits == expected || digits == Totp.code(Totp.parse("otpauth://totp/B?secret=JBSWY3DPEHPK3PXP")!!, System.currentTimeMillis() / 1000 - 30))

        // Cestino
        rule.onAllNodesWithText("Cestino")[0].performClick()
        rule.onNodeWithText("Sposta").performClick()
        rule.waitUntil(30_000) { store.db!!.isInRecycleBin(store.db!!.allEntries().single()) }
        assertTrue(store.db!!.activeEntries().isEmpty())
        waitText("Cestino")                                      // il gruppo compare nell'elenco
        rule.onNodeWithText("Cestino").performClick()
        waitText("Banca")
        rule.onNodeWithText("Banca").performClick()
        rule.onNodeWithText("Ripristina").performClick()
        rule.waitUntil(30_000) { store.db!!.activeEntries().size == 1 }
        // il file su disco riflette tutto
        store.lock(); store.open("pw-features-1".toCharArray(), null)
        assertEquals("Banca", store.db!!.activeEntries().single().title)
        } finally {
            scenario.close()    // ferma il contatore del codice TOTP: non deve restare attivo per i test successivi
            store.lock()
        }
    }
}
