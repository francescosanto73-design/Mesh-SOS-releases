package it.vault.custode

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Avvia l'app vera (Robolectric) e simula l'uso: crea, aggiungi, blocca, sblocca. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(tag: String) = rule.waitUntil(60_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(t: String) = rule.waitUntil(60_000) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() }

    @Test fun createAddLockUnlock() {
        rule.onNodeWithText("Crea la password principale").assertIsDisplayed()

        // password troppo corta rifiutata
        rule.onNodeWithTag("pw").performTextInput("corta"); rule.onNodeWithTag("pw2").performTextInput("corta")
        rule.onNodeWithTag("submit").performClick()
        rule.onNodeWithText("Almeno 8 caratteri (meglio una frase lunga)").assertIsDisplayed()

        rule.onNodeWithTag("pw").performTextClearance(); rule.onNodeWithTag("pw2").performTextClearance()
        rule.onNodeWithTag("pw").performTextInput("cavallo batteria graffetta 42")
        rule.onNodeWithTag("pw2").performTextInput("cavallo batteria graffetta 42")
        rule.onNodeWithTag("submit").performClick()
        waitFor("add")
        assertTrue(File(rule.activity.filesDir, "vault.kdbx").exists())

        // aggiungi una voce
        rule.onNodeWithTag("add").performClick()
        rule.onNodeWithText("Nuova password").performClick()
        rule.onNodeWithTag("title").performTextInput("Gmail")
        rule.onNodeWithTag("user").performTextInput("mario@example.com")
        rule.onNodeWithTag("generate").performClick()
        rule.onNodeWithTag("save").performClick()
        waitForText("Gmail")
        rule.onNodeWithText("mario@example.com").assertIsDisplayed()

        // blocca: la lista non è più visibile e il file non contiene il testo in chiaro
        rule.onNodeWithTag("lock").performClick()
        rule.onNodeWithText("Sblocca Custode").assertIsDisplayed()
        rule.onAllNodesWithText("Gmail").assertCountEquals(0)
        val raw = String(File(rule.activity.filesDir, "vault.kdbx").readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("Gmail")); assertFalse(raw.contains("mario@example.com"))

        // password errata, poi corretta
        rule.onNodeWithTag("pw").performTextInput("password sbagliata")
        rule.onNodeWithTag("submit").performClick()
        waitForText("Password o file chiave errati")
        rule.onNodeWithTag("pw").performTextClearance()
        rule.onNodeWithTag("pw").performTextInput("cavallo batteria graffetta 42")
        rule.onNodeWithTag("submit").performClick()
        waitForText("Gmail")
        rule.onNodeWithText("mario@example.com").assertIsDisplayed()
    }
}
