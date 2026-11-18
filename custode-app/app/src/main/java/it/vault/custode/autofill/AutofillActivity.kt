package it.vault.custode.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.service.autofill.Dataset
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import it.vault.custode.*
import it.vault.custode.kdbx.KdbxEntry

/**
 * Aperta dal servizio di compilazione automatica: sblocca il database (password o impronta),
 * fa scegliere una voce e restituisce al sistema SOLO i valori di quella voce.
 * Alla chiusura il database viene sempre bloccato.
 */
class AutofillActivity : BaseVaultActivity() {
    companion object {
        const val EXTRA_MODE = "mode"; const val MODE_FILL = "fill"; const val MODE_SAVE = "save"
        const val EXTRA_IDS = "ids"; const val EXTRA_ROLES = "roles"
        const val EXTRA_PACKAGE = "pkg"; const val EXTRA_WEB = "web"; const val EXTRA_LABEL = "label"
        const val EXTRA_USER = "user"; const val EXTRA_PASSWORD = "password"
        const val ROLE_USER = 0; const val ROLE_PASSWORD = 1
    }

    private var finishedByUs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FILL
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
        val web = intent.getStringExtra(EXTRA_WEB)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        val actions = buildActions(allowImport = false)
        setContent {
            CustodeTheme {
                App(vm, actions, allowImport = false) {
                    if (mode == MODE_SAVE) SaveScreen(
                        vm, label, pkg, web, intent.getStringExtra(EXTRA_USER) ?: "", intent.getStringExtra(EXTRA_PASSWORD) ?: "",
                        onDone = { msg -> toast(msg); close(Activity.RESULT_OK) },
                        onCancel = { close(Activity.RESULT_CANCELED) },
                    ) else ChooseScreen(vm, label, pkg, web, onPick = ::deliver, onCancel = { close(Activity.RESULT_CANCELED) })
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deliver(e: KdbxEntry) {
        val ids = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_IDS) ?: return close(Activity.RESULT_CANCELED)
        val roles = intent.getIntegerArrayListExtra(EXTRA_ROLES) ?: return close(Activity.RESULT_CANCELED)
        val pres = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.af_title, e.title.ifBlank { "Custode" }); setTextViewText(R.id.af_sub, e.username)
        }
        val ds = Dataset.Builder(pres)
        ids.forEachIndexed { i, id ->
            ds.setValue(id, AutofillValue.forText(if (roles.getOrNull(i) == ROLE_USER) e.username else e.password))
        }
        finishedByUs = true
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds.build()))
        vm.lock()
        finish()
    }

    private fun close(result: Int) { finishedByUs = true; setResult(result); vm.lock(); finish() }

    override fun onStop() {
        super.onStop()
        // Mai lasciare il database sbloccato dietro un'altra app.
        if (!isChangingConfigurations && !vm.keepUnlocked && vm.busy == null && !finishedByUs) vm.lock()
    }
}
