package it.vault.custode.autofill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import it.vault.custode.AppVm
import it.vault.custode.CIcons
import it.vault.custode.PasswordGenerator
import it.vault.custode.kdbx.KdbxEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseScreen(vm: AppVm, label: String, pkg: String, web: String?, onPick: (KdbxEntry) -> Unit, onCancel: () -> Unit) {
    val db = vm.store.db ?: return
    var query by remember { mutableStateOf("") }
    val all = remember(vm.version) { db.activeEntries().sortedBy { it.title.lowercase() } }
    val matching = remember(all) { all.filter { Matcher.matches(it, pkg, web) } }
    val others = remember(all, query) {
        all.filter { it !in matching && (query.isBlank() || it.title.contains(query, true) || it.username.contains(query, true) || it.url.contains(query, true)) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Compila: $label", maxLines = 1) }, actions = { TextButton(onClick = onCancel) { Text("Annulla") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            // Il campo di ricerca sta fuori dalla lista (come nella schermata principale).
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Cerca tra tutte le voci") },
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )
            LazyColumn(Modifier.testTag("choose")) {
                if (matching.isNotEmpty()) {
                    item { Text("Corrispondenti", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.labelLarge) }
                    items(matching, key = { "m" + it.id }) { EntryRow(it, onPick) }
                } else {
                    item {
                        Text("Nessuna voce corrisponde a $label. Scegline una a mano: controlla che sia quella giusta prima di compilare.",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (others.isNotEmpty()) item { Text("Altre voci", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.labelLarge) }
                items(others, key = { "o" + it.id }) { EntryRow(it, onPick) }
            }
        }
    }
}

@Composable
private fun EntryRow(e: KdbxEntry, onPick: (KdbxEntry) -> Unit) {
    ListItem(
        headlineContent = { Text(e.title.ifBlank { "(senza nome)" }) },
        supportingContent = { if (e.username.isNotBlank()) Text(e.username) },
        modifier = Modifier.clickable { onPick(e) }.testTag("row-" + e.title),
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveScreen(
    vm: AppVm, label: String, pkg: String, web: String?, user0: String, password0: String,
    onDone: (String) -> Unit, onCancel: () -> Unit,
) {
    val db = vm.store.db ?: return
    val existing = remember { db.activeEntries().firstOrNull { Matcher.matches(it, pkg, web) && it.username == user0 } }
    var title by remember { mutableStateOf(existing?.title ?: label) }
    var user by remember { mutableStateOf(user0) }
    var pw by remember { mutableStateOf(password0) }
    var show by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(if (existing != null) "Aggiorna password" else "Salva in Custode") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (existing != null) Text("Esiste già una voce \"${existing.title}\" per questo sito/app con lo stesso utente: verrà aggiornata la password (la vecchia resta nello storico).")
            OutlinedTextField(title, { title = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("title"))
            OutlinedTextField(user, { user = it }, label = { Text("Utente") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                pw, { pw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) { Icon(if (show) CIcons.VisibilityOff else CIcons.Visibility, "Mostra o nascondi") }
                },
            )
            Text("Da: $label", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = title.isNotBlank() && pw.isNotEmpty(), modifier = Modifier.testTag("save"), onClick = {
                    if (existing != null) {
                        db.edit(existing) { password = pw }
                        vm.persist(); onDone("Password aggiornata")
                    } else {
                        val e = db.root.addEntry(title.trim(), user.trim(), pw, if (!web.isNullOrBlank()) "https://$web" else "androidapp://$pkg", "")
                        if (web.isNullOrBlank()) e.setField("AndroidApp", pkg)
                        vm.persist(); onDone("Salvato in Custode")
                    }
                }) { Text(if (existing != null) "Aggiorna" else "Salva") }
                TextButton(onClick = onCancel) { Text("Non salvare") }
            }
        }
    }
}
