package it.vault.custode.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.service.autofill.Dataset
import android.view.View
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import it.vault.custode.VaultStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Simula quello che fa Android: apre l'activity di compilazione automatica e ne legge il risultato. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutofillActivityTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun wait(cond: () -> Boolean) = rule.waitUntil(60_000, cond)

    @Test fun fillFlowReturnsOnlyTheChosenEntryAndLocksAgain() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = VaultStore.get(ctx)
        store.create("T", "pw-autofill-1".toCharArray(), null)
        store.db!!.root.addEntry("Altro", "x", "NON-QUESTA", "https://altro.it", "")
        store.db!!.root.addEntry("Banca", "mario", "SEGRETO-123", "https://www.banca.esempio.it/accesso", "")
        store.save(); store.lock()

        val user = View(ctx).autofillId; val pass = View(ctx).autofillId
        assertNotNull(user); assertNotNull(pass)
        val intent = Intent(ctx, AutofillActivity::class.java)
            .putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_FILL)
            .putParcelableArrayListExtra(AutofillActivity.EXTRA_IDS, arrayListOf(user, pass))
            .putIntegerArrayListExtra(AutofillActivity.EXTRA_ROLES, arrayListOf(AutofillActivity.ROLE_USER, AutofillActivity.ROLE_PASSWORD))
            .putExtra(AutofillActivity.EXTRA_PACKAGE, "org.mozilla.firefox")
            .putExtra(AutofillActivity.EXTRA_WEB, "banca.esempio.it")
            .putExtra(AutofillActivity.EXTRA_LABEL, "banca.esempio.it")

        val scenario = ActivityScenario.launchActivityForResult<AutofillActivity>(intent)
        rule.onNodeWithText("Sblocca Custode").assertIsDisplayed()          // database bloccato: prima l'autenticazione
        rule.onNodeWithTag("pw").performTextInput("pw-autofill-1")
        rule.onNodeWithTag("submit").performClick()
        wait { rule.onAllNodesWithTag("choose").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Corrispondenti").assertIsDisplayed()          // la voce giusta è proposta per prima
        rule.onNodeWithTag("row-Banca").performClick()

        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        val ds = result.resultData.getParcelableExtra<Dataset>(AutofillManager.EXTRA_AUTHENTICATION_RESULT)
        assertNotNull("il sistema deve ricevere un Dataset", ds)
        // I getter di Dataset sono nascosti: li leggiamo per riflessione, solo nel test.
        val values = (ds!!.javaClass.getMethod("getFieldValues").invoke(ds) as List<*>).map { (it as AutofillValue).textValue.toString() }
        assertEquals(listOf("mario", "SEGRETO-123"), values)
        assertFalse(values.contains("NON-QUESTA"))
        assertNull("alla fine il database deve tornare bloccato", store.db)
    }

    @Test fun saveFlowCreatesEntryAfterUnlock() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = VaultStore.get(ctx)
        store.create("T", "pw-autofill-2".toCharArray(), null); store.lock()
        val intent = Intent(ctx, AutofillActivity::class.java)
            .putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_SAVE)
            .putExtra(AutofillActivity.EXTRA_PACKAGE, "com.android.chrome")
            .putExtra(AutofillActivity.EXTRA_WEB, "nuovo-sito.it")
            .putExtra(AutofillActivity.EXTRA_LABEL, "nuovo-sito.it")
            .putExtra(AutofillActivity.EXTRA_USER, "luca")
            .putExtra(AutofillActivity.EXTRA_PASSWORD, "P4ss-nuova!")
        val scenario = ActivityScenario.launchActivityForResult<AutofillActivity>(intent)
        rule.onNodeWithTag("pw").performTextInput("pw-autofill-2")
        rule.onNodeWithTag("submit").performClick()
        wait { rule.onAllNodesWithTag("save").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("save").performClick()
        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        store.open("pw-autofill-2".toCharArray(), null)
        val e = store.db!!.allEntries().single()
        assertEquals("nuovo-sito.it", e.title); assertEquals("luca", e.username); assertEquals("P4ss-nuova!", e.password)
        assertEquals("https://nuovo-sito.it", e.url)
    }
}
