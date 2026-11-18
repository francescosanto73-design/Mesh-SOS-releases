package it.vault.custode

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import it.vault.custode.importer.TextImporter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextImportFlowTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun wait(cond: () -> Boolean) {
        rule.mainClock.autoAdvance = false
        val end = System.currentTimeMillis() + 60_000
        while (true) {
            rule.mainClock.advanceTimeByFrame()
            if (cond()) return
            if (System.currentTimeMillis() > end) { println(rule.onRoot(useUnmergedTree = true).printToString(100)); fail("timeout") }
            Thread.sleep(30)
        }
    }

    @Test fun previewThenImportCreatesSeparateGroup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = VaultStore.get(ctx)
        store.create("T", "pw-import-1".toCharArray(), null)
        store.db!!.root.addEntry("Esistente", "u", "p", "", ""); store.save()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            wait { rule.onAllNodesWithText("Esistente").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { act ->
                val vm = ViewModelProvider(act)[AppVm::class.java]
                vm.textImport = TextImporter.parse("Gmail mario@x.it Pw-1\nConto Intesa abc123\nWifi di casa del mare rete Fastweb codice sul router")
            }
            wait { rule.onAllNodesWithText("Anteprima dell'importazione").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("3 righe, 2 da controllare", substring = true).assertIsDisplayed()
            assertTrue("l'anteprima non deve scrivere nulla nel database", store.db!!.root.groups.isEmpty())

            rule.onNodeWithTag("doImport").performClick(); repeat(4) { rule.mainClock.advanceTimeByFrame() }
            wait { store.db!!.root.groups.isNotEmpty() }

            val g = store.db!!.root.groups.single()
            assertEquals("Importate dal file di testo", g.name)
            val byTitle = g.entries.associateBy { it.title }
            assertEquals("mario@x.it", byTitle["Gmail"]!!.username); assertEquals("Pw-1", byTitle["Gmail"]!!.password)
            assertEquals("abc123", byTitle["Conto"]!!.password)
            val wifi = g.entries.single { it.password.isEmpty() }
            assertTrue("la riga non interpretata finisce intera nelle note", wifi.notes.contains("codice sul router"))
            assertEquals("Esistente", store.db!!.root.entries.single().title)          // le voci esistenti non si toccano
            // salvato su disco
            store.lock(); store.open("pw-import-1".toCharArray(), null)
            assertEquals(3, store.db!!.root.groups.single().entries.size)
        } finally { scenario.close(); store.lock() }
    }
}
