package it.vault.custode.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import it.vault.custode.R

/**
 * Compilazione automatica. Il servizio NON legge mai il database: propone una sola voce "Custode" che,
 * se scelta, apre [AutofillActivity] (sblocco con password/impronta e scelta della voce).
 * Le password restano quindi cifrate finché l'utente non le autorizza esplicitamente.
 */
class CustodeAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val parsed = StructureParser.parse(structure)
        if (parsed == null || parsed.packageName == packageName) return callback.onSuccess(null) // mai dentro Custode stesso
        val form = FormDetector.detect(parsed.fields) ?: return callback.onSuccess(null)

        val ids = ArrayList<AutofillId>(); val roles = ArrayList<Int>()
        form.username?.let { ids += parsed.ids[it]; roles += AutofillActivity.ROLE_USER }
        form.passwords.forEach { ids += parsed.ids[it]; roles += AutofillActivity.ROLE_PASSWORD }

        val label = parsed.webDomain ?: appLabel(parsed.packageName)
        val intent = Intent(this, AutofillActivity::class.java)
            .putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_FILL)
            .putParcelableArrayListExtra(AutofillActivity.EXTRA_IDS, ids)
            .putIntegerArrayListExtra(AutofillActivity.EXTRA_ROLES, roles)
            .putExtra(AutofillActivity.EXTRA_PACKAGE, parsed.packageName)
            .putExtra(AutofillActivity.EXTRA_WEB, parsed.webDomain)
            .putExtra(AutofillActivity.EXTRA_LABEL, label)
        // Mutabile: il sistema aggiunge i propri extra (es. il risultato dell'autenticazione).
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val sender = PendingIntent.getActivity(this, nextCode(), intent, flags).intentSender

        val ds = Dataset.Builder(presentation(label)).setAuthentication(sender)
        ids.forEach { ds.setValue(it, null) }   // valore vuoto: lo fornirà l'activity dopo lo sblocco

        val resp = FillResponse.Builder().addDataset(ds.build())
        form.passwords.firstOrNull()?.let { firstPw ->
            val required = form.passwords.map { parsed.ids[it] }.toTypedArray()
            val save = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME, required)
            form.username?.let { save.setOptionalIds(arrayOf(parsed.ids[it])) }
            save.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            resp.setSaveInfo(save.build())
        }
        callback.onSuccess(resp.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val parsed = StructureParser.parse(structure) ?: return callback.onSuccess()
        val form = FormDetector.detect(parsed.fields) ?: return callback.onSuccess()
        val password = form.passwords.firstNotNullOfOrNull { parsed.fields[it].value?.takeIf { v -> v.isNotEmpty() } }
            ?: return callback.onSuccess()
        val user = form.username?.let { parsed.fields[it].value } ?: ""
        if (Build.VERSION.SDK_INT < 28) { callback.onFailure("Per salvare da qui serve Android 9 o successivo: aggiungi la voce da Custode"); return }
        val label = parsed.webDomain ?: appLabel(parsed.packageName)
        val intent = Intent(this, AutofillActivity::class.java)
            .putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_SAVE)
            .putExtra(AutofillActivity.EXTRA_PACKAGE, parsed.packageName)
            .putExtra(AutofillActivity.EXTRA_WEB, parsed.webDomain)
            .putExtra(AutofillActivity.EXTRA_LABEL, label)
            .putExtra(AutofillActivity.EXTRA_USER, user)
            .putExtra(AutofillActivity.EXTRA_PASSWORD, password)
        val pi = PendingIntent.getActivity(this, nextCode(), intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        callback.onSuccess(pi.intentSender)   // il sistema apre Custode per sbloccare e confermare il salvataggio
    }

    private fun presentation(label: String) = RemoteViews(packageName, R.layout.autofill_item).apply {
        setTextViewText(R.id.af_title, "Custode")
        setTextViewText(R.id.af_sub, label)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private var counter = 0
    private fun nextCode() = ++counter
}
