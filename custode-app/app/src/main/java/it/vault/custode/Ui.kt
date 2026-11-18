package it.vault.custode

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import it.vault.custode.kdbx.KdbxDatabase
import it.vault.custode.kdbx.KdbxEntry
import it.vault.custode.kdbx.KdbxGroup
import it.vault.custode.kdbx.Totp
import kotlinx.coroutines.delay

@Composable
fun CustodeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
}

/** [unlocked] è la schermata mostrata a database sbloccato (elenco, oppure scelta voce per la compilazione automatica). */
@Composable
fun App(vm: AppVm, act: Actions, allowImport: Boolean = true, unlocked: @Composable () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (vm.unlocked) unlocked() else LoginScreen(vm, act)
        }
    }
    if (allowImport) vm.pendingImport?.let { ImportDialog(vm, act) }
    vm.busy?.let { msg ->
        Dialog(onDismissRequest = {}) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp)); Text(msg)
                }
            }
        }
    }
}

@Composable
fun SecretField(label: String, value: String, tag: String = "", onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
fun KeyFileRow(vm: AppVm, act: Actions) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CIcons.Key, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        if (vm.keyFileName == null) {
            TextButton(onClick = act.pickKeyFile) { Text("Aggiungi un file chiave (facoltativo)") }
        } else {
            Text(vm.keyFileName!!, Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = { vm.keyFile = null; vm.keyFileName = null }) { Text("Rimuovi") }
        }
    }
}

// ───────────────────────────── Accesso ─────────────────────────────

@Composable
fun LoginScreen(vm: AppVm, act: Actions) {
    val creating = !vm.store.exists
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Text(if (creating) "Crea la password principale" else "Sblocca Custode", style = MaterialTheme.typography.headlineSmall)
        if (creating) {
            Spacer(Modifier.height(8.dp))
            Text(
                "È l'unica password da ricordare. Se la dimentichi i dati non sono recuperabili: non esiste nessun reset.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        SecretField("Password principale", pw, "pw") { pw = it }
        if (creating) { Spacer(Modifier.height(8.dp)); SecretField("Ripeti password", pw2, "pw2") { pw2 = it } }
        KeyFileRow(vm, act)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = vm.busy == null && (pw.isNotEmpty() || vm.keyFile != null),
            modifier = Modifier.fillMaxWidth().testTag("submit"),
            onClick = {
                error = null
                if (creating && pw.isNotEmpty() && pw.length < 8) { error = "Almeno 8 caratteri (meglio una frase lunga)"; return@Button }
                if (creating && pw != pw2) { error = "Le password non coincidono"; return@Button }
                val chars = pw.toCharArray(); val kf = vm.keyFile
                vm.work(if (creating) "Creazione del database…" else "Sblocco…", {
                    try {
                        val p = chars.takeIf { it.isNotEmpty() }
                        if (creating) vm.store.create("Custode", p, kf) else vm.store.open(p, kf)
                    } finally { chars.fill('\u0000') }
                }) { pw = ""; pw2 = ""; vm.unlocked = true; vm.version++ }
            },
        ) { Text(if (creating) "Crea database" else "Sblocca") }
        if (!creating && vm.bioEnabled) {
            OutlinedButton(onClick = act.unlockBio, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Usa l'impronta") }
        }
        act.pickDatabase?.let { pick ->
            TextButton(onClick = pick, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (creating) "Ho già un database KeePass (.kdbx)" else "Sostituisci con un altro database…")
            }
        }
    }
}

@Composable
fun ImportDialog(vm: AppVm, act: Actions) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (vm.busy == null) vm.pendingImport = null },
        title = { Text("Apri database KeePass") },
        text = {
            Column {
                Text("Inserisci la password del database (e il file chiave, se lo usa). I dati attuali di questa app verranno sostituiti. Funziona con file KDBX 3 e 4 (KeePass, KeePassXC, KeePassDX).")
                Spacer(Modifier.height(8.dp))
                SecretField("Password del database", pw, "importPw") { pw = it }
                KeyFileRow(vm, act)
            }
        },
        confirmButton = {
            TextButton(enabled = vm.busy == null && (pw.isNotEmpty() || vm.keyFile != null), onClick = {
                val bytes = vm.pendingImport ?: return@TextButton
                val chars = pw.toCharArray(); val kf = vm.keyFile
                vm.work("Apertura del database…", {
                    try { vm.store.replaceWith(bytes, chars.takeIf { it.isNotEmpty() }, kf) } finally { chars.fill('\u0000') }
                }) { upgraded ->
                    act.setBio(false)
                    vm.pendingImport = null; vm.unlocked = true; vm.groupStack.clear(); vm.version++
                    vm.message = if (upgraded) "Database importato e convertito in KDBX 4" else "Database importato"
                }
            }) { Text("Apri") }
        },
        dismissButton = { TextButton(enabled = vm.busy == null, onClick = { vm.pendingImport = null }) { Text("Annulla") } },
    )
}

// ───────────────────────────── Elenco ─────────────────────────────

private fun resolve(root: KdbxGroup, stack: List<String>): KdbxGroup {
    var g = root
    for (id in stack) g = g.groups.firstOrNull { it.id == id } ?: break
    return g
}

/** Tutti i gruppi (cestino escluso) con il percorso leggibile, per "Sposta in…". */
private fun groupChoices(db: KdbxDatabase): List<Pair<String, KdbxGroup>> {
    val bin = db.recycleBin
    val out = ArrayList<Pair<String, KdbxGroup>>()
    fun walk(g: KdbxGroup, path: String) {
        if (bin != null && g.id == bin.id) return
        out += path to g
        g.groups.sortedBy { it.name.lowercase() }.forEach { walk(it, "$path / ${it.name}") }
    }
    walk(db.root, "Radice")
    return out
}

private fun shortDate(iso: String) = iso.replace('T', ' ').removeSuffix("Z").take(16)
private fun hiddenField(k: String) = k == "otp" || k.startsWith("TimeOtp-")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppVm, act: Actions) {
    val db = vm.store.db ?: return
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var fab by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<String?>(null) }  // "new" oppure UUID della voce
    val stack = vm.groupStack.toList()
    val group = remember(vm.version, stack) { resolve(db.root, stack) }
    val inBin = remember(vm.version, stack) { db.isInRecycleBin(group) || db.recycleBin?.id == group.id }
    val groups = remember(vm.version, stack) { group.groups.sortedBy { it.name.lowercase() } }
    val entries = remember(vm.version, stack, query) {
        val src = if (query.isBlank()) group.entries else db.activeEntries().filter {
            it.title.contains(query, true) || it.username.contains(query, true) || it.url.contains(query, true)
        }
        src.sortedBy { it.title.lowercase() }
    }
    var imported by remember { mutableStateOf<Int?>(null) }
    BackHandler(enabled = stack.isNotEmpty()) { vm.groupStack.removeAt(vm.groupStack.lastIndex) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (stack.isEmpty()) "Custode" else group.name) },
                navigationIcon = {
                    if (stack.isNotEmpty()) IconButton(onClick = { vm.groupStack.removeAt(vm.groupStack.lastIndex) }) {
                        Icon(Icons.Default.ArrowBack, "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.lock() }, modifier = Modifier.testTag("lock")) { Icon(Icons.Default.Lock, "Blocca") }
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu")) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Esporta backup (.kdbx)") }, onClick = { menu = false; act.export() })
                        act.pickDatabase?.let { pick -> DropdownMenuItem(text = { Text("Apri un altro database…") }, onClick = { menu = false; pick() }) }
                        DropdownMenuItem(text = { Text("Importa da un file di testo…") }, onClick = { menu = false; act.pickTextFile() })
                        DropdownMenuItem(text = { Text("Cambia password principale") }, onClick = { menu = false; dialog = "changeKey" })
                        if (act.bioAvailable) DropdownMenuItem(
                            text = { Text(if (vm.bioEnabled) "Disattiva sblocco con impronta" else "Attiva sblocco con impronta") },
                            onClick = { menu = false; act.setBio(!vm.bioEnabled) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (act.autofillEnabled()) "Compilazione automatica: attiva ✓" else "Attiva la compilazione automatica") },
                            onClick = { menu = false; act.enableAutofill() },
                        )
                        DropdownMenuItem(text = { Text("Impostazioni") }, onClick = { menu = false; dialog = "settings" })
                        if (inBin && db.recycleBin?.id == group.id) DropdownMenuItem(
                            text = { Text("Svuota il cestino", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; dialog = "emptyBin" },
                        )
                        if (stack.isNotEmpty() && db.recycleBin?.id != group.id) DropdownMenuItem(
                            text = { Text(if (inBin) "Elimina questo gruppo definitivamente" else "Sposta il gruppo nel cestino", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; dialog = "deleteGroup" },
                        )
                        DropdownMenuItem(text = { Text("Informazioni") }, onClick = { menu = false; dialog = "info" })
                    }
                },
            )
        },
        floatingActionButton = {
            if (!inBin) Box {
                FloatingActionButton(onClick = { fab = true }, modifier = Modifier.testTag("add")) { Icon(Icons.Default.Add, "Aggiungi") }
                DropdownMenu(expanded = fab, onDismissRequest = { fab = false }) {
                    DropdownMenuItem(text = { Text("Nuova password") }, onClick = { fab = false; editor = "new" })
                    DropdownMenuItem(text = { Text("Nuovo gruppo") }, onClick = { fab = false; dialog = "newGroup" })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Cerca") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("search"),
            )
            val showGroups = query.isBlank()
            if ((!showGroups || groups.isEmpty()) && entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isNotBlank()) "Nessun risultato" else if (inBin) "Il cestino è vuoto" else "Nessuna password qui.\nTocca + per aggiungerne una.")
                }
            } else LazyColumn(Modifier.testTag("list")) {
                if (showGroups) items(groups, key = { "g" + it.id }) { g ->
                    ListItem(
                        headlineContent = { Text(g.name) },
                        supportingContent = { Text("${g.entriesRecursive().size} voci") },
                        leadingContent = { Icon(CIcons.Folder, null) },
                        modifier = Modifier.clickable { vm.groupStack.add(g.id) },
                    )
                    HorizontalDivider()
                }
                items(entries, key = { "e" + it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.title.ifBlank { "(senza nome)" }) },
                        supportingContent = { if (e.username.isNotBlank()) Text(e.username) },
                        trailingContent = { IconButton(onClick = { act.copy("password", e.password) }) { Icon(CIcons.Copy, "Copia password") } },
                        modifier = Modifier.clickable { vm.selectedEntry = e.id },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    vm.textImport?.let { rows ->
        TextImportDialog(rows, onCancel = { vm.textImport = null; vm.textUri = null }, onImport = { chosen ->
            val g = db.root.addGroup("Importate dal file di testo")
            chosen.forEach { g.addEntry(it.title.ifBlank { "(senza nome)" }, it.user, it.password, it.url, it.notes) }
            vm.textImport = null; vm.groupStack.clear(); vm.persist(); imported = chosen.size
        })
    }
    imported?.let { n ->
        AlertDialog(
            onDismissRequest = { imported = null },
            title = { Text("Importate $n voci") },
            text = { Text("Sono nel gruppo \"Importate dal file di testo\": controllale e spostale dove preferisci.\n\nIl file di testo contiene tutte le password in chiaro. Ti consiglio di eliminarlo subito, e di svuotare il cestino del telefono.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = act.deleteTextFile()
                    vm.message = if (ok) "File di testo eliminato" else "Non sono riuscito a eliminarlo da qui: eliminalo dall'app File"
                    imported = null
                }) { Text("Elimina il file ora") }
            },
            dismissButton = { TextButton(onClick = { vm.textUri = null; imported = null }) { Text("Più tardi") } },
        )
    }
    vm.selectedEntry?.let { id ->
        val e = db.allEntries().firstOrNull { it.id == id }
        if (e == null) vm.selectedEntry = null else DetailDialog(
            vm, e, act,
            onEdit = { editor = e.id; vm.selectedEntry = null },
            onClose = { vm.selectedEntry = null },
        )
    }
    editor?.let { id ->
        val existing = if (id == "new") null else db.allEntries().firstOrNull { it.id == id }
        EditorDialog(
            initial = existing,
            onSave = { d ->
                val e = existing ?: group.addEntry(d.title, d.user, d.password, d.url, d.notes).also { applyExtras(it, d) }
                if (existing != null) db.edit(e) {
                    title = d.title; username = d.user; password = d.password; url = d.url; notes = d.notes
                    applyExtras(this, d)
                }
                editor = null; vm.persist()
            },
            onClose = { editor = null },
        )
    }
    when (dialog) {
        "newGroup" -> TextPrompt("Nuovo gruppo", "Nome del gruppo", onOk = { group.addGroup(it); dialog = null; vm.persist() }, onClose = { dialog = null })
        "deleteGroup" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (inBin) "Eliminare \"${group.name}\" per sempre?" else "Spostare \"${group.name}\" nel cestino?") },
            text = { Text("Contiene ${group.entriesRecursive().size} voci." + if (inBin) " L'operazione non è reversibile." else " Potrai ripristinarlo dal cestino.") },
            confirmButton = {
                TextButton(onClick = {
                    db.trash(group); vm.groupStack.removeAt(vm.groupStack.lastIndex); dialog = null; vm.persist()
                }) { Text(if (inBin) "Elimina" else "Sposta") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annulla") } },
        )
        "emptyBin" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Svuotare il cestino?") }, text = { Text("Le voci eliminate non potranno più essere recuperate.") },
            confirmButton = { TextButton(onClick = { db.emptyRecycleBin(); dialog = null; vm.persist() }) { Text("Svuota") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annulla") } },
        )
        "changeKey" -> ChangeKeyDialog(vm, act, onClose = { dialog = null })
        "settings" -> SettingsDialog(act.settings, onClose = { dialog = null })
        "info" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Custode 1.2") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Gestore di password offline, senza accesso a internet.\n\nIl database è un file KDBX 4, lo stesso formato di KeePass, KeePassXC e KeePassDX: puoi esportarlo e aprirlo con quelle app.\n\nCifratura AES-256 con chiave derivata da Argon2d. Il nucleo del formato è scritto da zero e verificato contro KeePassXC; non ha avuto un audit indipendente.\n\nCompilazione automatica: in Chrome attiva anche Impostazioni di Chrome → Compilazione automatica con un altro servizio.")
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Chiudi") } },
        )
    }
}

@Composable
fun TextPrompt(title: String, label: String, onOk: (String) -> Unit, onClose: () -> Unit) {
    var v by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.testTag("prompt")) },
        confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = { onOk(v.trim()) }) { Text("Crea") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
    )
}

@Composable
fun ChangeKeyDialog(vm: AppVm, act: Actions, onClose: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (vm.busy == null) onClose() },
        title = { Text("Cambia password principale") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SecretField("Nuova password", pw) { pw = it }
                Spacer(Modifier.height(8.dp))
                SecretField("Ripeti la nuova password", pw2) { pw2 = it }
                KeyFileRow(vm, act)
                Text("Se non scegli un file chiave, il database non lo richiederà più.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = vm.busy == null && (pw.isNotEmpty() || vm.keyFile != null), onClick = {
                if (pw.isNotEmpty() && pw.length < 8) { error = "Almeno 8 caratteri"; return@TextButton }
                if (pw != pw2) { error = "Le password non coincidono"; return@TextButton }
                val chars = pw.toCharArray(); val kf = vm.keyFile
                vm.work("Aggiornamento della cifratura…", {
                    try { vm.store.changeKey(chars.takeIf { it.isNotEmpty() }, kf) } finally { chars.fill('\u0000') }
                }) { act.setBio(false); vm.message = "Password cambiata. Riattiva lo sblocco con impronta dal menu."; onClose() }
            }) { Text("Cambia") }
        },
        dismissButton = { TextButton(enabled = vm.busy == null, onClick = onClose) { Text("Annulla") } },
    )
}

@Composable
fun SettingsDialog(s: Settings, onClose: () -> Unit) {
    var lock by remember { mutableIntStateOf(s.lockDelaySec) }
    var clip by remember { mutableIntStateOf(s.clipboardSec) }
    @Composable fun option(selected: Boolean, label: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick); Text(label)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Impostazioni") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Blocca quando esco dall'app", style = MaterialTheme.typography.titleSmall)
                Settings.LOCK_OPTIONS.forEach { (v, l) -> option(lock == v, l) { lock = v; s.lockDelaySec = v } }
                Spacer(Modifier.height(12.dp))
                Text("Cancella gli appunti dopo", style = MaterialTheme.typography.titleSmall)
                Settings.CLIP_OPTIONS.forEach { (v, l) -> option(clip == v, l) { clip = v; s.clipboardSec = v } }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fatto") } },
    )
}

// ───────────────────────────── Dettaglio ─────────────────────────────

@Composable
fun DetailDialog(vm: AppVm, e: KdbxEntry, act: Actions, onEdit: () -> Unit, onClose: () -> Unit) {
    val db = vm.store.db ?: return
    var show by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) } // "history" | "move"
    val inBin = remember(vm.version) { db.isInRecycleBin(e) }
    val totp = remember(vm.version, e.id) { Totp.of(e) }
    val rawOtp = e.field("otp")

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(e.title.ifBlank { "(senza nome)" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (e.username.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Utente: ${e.username}", Modifier.weight(1f))
                    IconButton(onClick = { act.copy("utente", e.username) }) { Icon(CIcons.Copy, "Copia utente") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Password: " + if (show) e.password else "••••••••", Modifier.weight(1f))
                    IconButton(onClick = { show = !show }) { Icon(if (show) CIcons.VisibilityOff else CIcons.Visibility, "Mostra o nascondi") }
                    IconButton(onClick = { act.copy("password", e.password) }) { Icon(CIcons.Copy, "Copia password") }
                }
                if (totp != null) TotpRow(totp, act)
                else if (rawOtp.isNotBlank()) Text("Codice a due fattori non riconosciuto (formato non supportato)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (e.url.isNotBlank()) Text("Sito: ${e.url}", Modifier.padding(top = 4.dp))
                e.customFields.filterKeys { !hiddenField(it) }.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$k: " + if (e.isProtected(k) && !show) "••••••••" else v, Modifier.weight(1f))
                        IconButton(onClick = { act.copy(k, v) }) { Icon(CIcons.Copy, "Copia $k") }
                    }
                }
                if (e.notes.isNotBlank()) Text("Note: ${e.notes}", Modifier.padding(top = 8.dp))

                // Allegati
                e.attachments.forEach { name ->
                    val data = db.attachmentBytes(e, name)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📎 $name (${(data?.size ?: 0) / 1024 + 1} KB)", Modifier.weight(1f))
                        TextButton(onClick = { data?.let { act.saveFile(name, it) } }) { Text("Salva") }
                        TextButton(onClick = { db.removeAttachment(e, name); vm.persist() }) { Text("Togli") }
                    }
                }
                Row {
                    if (!inBin) TextButton(onClick = {
                        act.pickAttachment { name, bytes -> db.addAttachment(e, name, bytes); vm.persist() }
                    }) { Text("Allega file") }
                    if (e.history.isNotEmpty()) TextButton(onClick = { sub = "history" }) { Text("Storico (${e.history.size})") }
                    TextButton(onClick = { sub = "move" }) { Text(if (inBin) "Ripristina in…" else "Sposta…") }
                }
            }
        },
        confirmButton = { if (!inBin) TextButton(onClick = onEdit) { Text("Modifica") } else TextButton(onClick = { db.restore(e); vm.persist(); vm.message = "Voce ripristinata"; onClose() }) { Text("Ripristina") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirm = true }) { Text(if (inBin) "Elimina" else "Cestino", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onClose) { Text("Chiudi") }
            }
        },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (inBin) "Eliminare \"${e.title}\" per sempre?" else "Spostare \"${e.title}\" nel cestino?") },
        text = { Text(if (inBin) "L'operazione non è reversibile." else "Potrai ripristinarla dal cestino.") },
        confirmButton = {
            TextButton(onClick = {
                db.trash(e); confirm = false; onClose(); vm.persist()
                vm.message = if (inBin) "Eliminata definitivamente" else "Spostata nel cestino"
            }) { Text(if (inBin) "Elimina" else "Sposta") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } },
    )
    when (sub) {
        "history" -> HistoryDialog(vm, e, onClose = { sub = null })
        "move" -> AlertDialog(
            onDismissRequest = { sub = null },
            title = { Text(if (inBin) "Ripristina in…" else "Sposta in…") },
            text = {
                LazyColumn { items(groupChoices(db), key = { it.second.id }) { (path, g) ->
                    Text(path, Modifier.fillMaxWidth().clickable {
                        db.move(e, g); sub = null; onClose(); vm.persist()
                    }.padding(vertical = 12.dp))
                } }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { sub = null }) { Text("Annulla") } },
        )
    }
}

@Composable
fun TotpRow(spec: it.vault.custode.kdbx.TotpSpec, act: Actions) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() / 1000 } }
    val code = Totp.code(spec, now); val left = Totp.secondsLeft(spec, now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Codice: ${code.chunked((code.length + 1) / 2).joinToString(" ")}  (${left}s)", Modifier.weight(1f).testTag("totp"))
        IconButton(onClick = { act.copy("codice", code) }) { Icon(CIcons.Copy, "Copia codice") }
    }
}

@Composable
fun HistoryDialog(vm: AppVm, e: KdbxEntry, onClose: () -> Unit) {
    val db = vm.store.db ?: return
    var show by remember { mutableStateOf(false) }
    val versions = remember(vm.version) { e.history.reversed() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Storico di \"${e.title}\"") },
        text = {
            LazyColumn {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = show, onCheckedChange = { show = it }); Spacer(Modifier.width(8.dp)); Text("Mostra le password")
                    }
                }
                items(versions) { v ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(shortDate(v.modified), style = MaterialTheme.typography.labelLarge)
                        Text("Utente: ${v.username}")
                        Text("Password: " + if (show) v.password else "••••••••")
                        TextButton(onClick = { db.restoreVersion(e, v); vm.persist(); vm.message = "Versione ripristinata"; onClose() }) { Text("Ripristina questa versione") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Chiudi") } },
    )
}

// ───────────────────────────── Modifica ─────────────────────────────

class EditorData(
    val title: String, val user: String, val password: String, val url: String, val notes: String,
    val custom: List<Triple<String, String, Boolean>>, val totp: String,
)

/** Campi personalizzati e TOTP (la parte comune a nuove voci e modifiche). */
private fun applyExtras(e: KdbxEntry, d: EditorData) {
    val keep = d.custom.map { it.first }.toSet()
    e.customFields.keys.filter { !hiddenField(it) && it !in keep }.forEach { e.removeField(it) }
    d.custom.forEach { (k, v, p) -> e.setField(k, v, protect = p) }
    val t = d.totp.trim()
    if (t.isEmpty()) e.removeField("otp") else e.setField("otp", t, protect = true)
}

/** Converte quanto scritto nel campo TOTP: otpauth:// o key=… restano così; un segreto base32 diventa un otpauth://. */
private fun normalizeTotp(raw: String, title: String, user: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    if (Totp.parse(t) != null) return t
    return Totp.uri(t, title.ifBlank { "Custode" }, user)?.takeIf { Totp.parse(it) != null }
}

@Composable
fun EditorDialog(initial: KdbxEntry?, onSave: (EditorData) -> Unit, onClose: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pw by remember { mutableStateOf(initial?.password ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var totp by remember { mutableStateOf(initial?.field("otp") ?: "") }
    var totpError by remember { mutableStateOf(false) }
    val custom = remember {
        mutableStateListOf<Triple<String, String, Boolean>>().apply {
            initial?.customFields?.filterKeys { !hiddenField(it) }?.forEach { (k, v) -> add(Triple(k, v, initial.isProtected(k))) }
        }
    }
    var visible by remember { mutableStateOf(initial == null) }
    var length by remember { mutableFloatStateOf(20f) }
    var symbols by remember { mutableStateOf(true) }
    val opts = PasswordGenerator.Options(length = length.toInt(), symbols = symbols)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "Nuova password" else "Modifica") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Nome (es. Gmail)") }, singleLine = true, modifier = Modifier.testTag("title"))
                OutlinedTextField(user, { user = it }, label = { Text("Utente / email") }, singleLine = true, modifier = Modifier.testTag("user"))
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.testTag("password"),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) { Icon(if (visible) CIcons.VisibilityOff else CIcons.Visibility, "Mostra o nascondi") }
                    },
                )
                Text("Generatore: ${length.toInt()} caratteri, ~${PasswordGenerator.entropyBits(opts)} bit", style = MaterialTheme.typography.bodySmall)
                Slider(value = length, onValueChange = { length = it }, valueRange = 8f..64f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = symbols, onCheckedChange = { symbols = it }); Spacer(Modifier.width(8.dp)); Text("Simboli")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pw = PasswordGenerator.generate(opts); visible = true }, modifier = Modifier.testTag("generate")) { Text("Genera") }
                }
                OutlinedTextField(url, { url = it }, label = { Text("Sito web") }, singleLine = true)
                OutlinedTextField(
                    totp, { totp = it; totpError = false }, label = { Text("Codice a due fattori (segreto o link otpauth://)") },
                    singleLine = true, isError = totpError, modifier = Modifier.testTag("totpField"),
                    supportingText = { if (totpError) Text("Formato non valido: serve il segreto base32 o un link otpauth://totp/…") },
                )
                OutlinedTextField(notes, { notes = it }, label = { Text("Note") }, minLines = 2)
                custom.forEachIndexed { i, (k, v, p) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(k, { custom[i] = Triple(it, v, p) }, label = { Text("Nome campo") }, singleLine = true)
                            OutlinedTextField(v, { custom[i] = Triple(k, it, p) }, label = { Text("Valore") }, singleLine = true,
                                visualTransformation = if (p) PasswordVisualTransformation() else VisualTransformation.None)
                        }
                        Column {
                            Switch(checked = p, onCheckedChange = { custom[i] = Triple(k, v, it) })
                            Text("Segreto", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { custom.removeAt(i) }) { Text("Togli") }
                        }
                    }
                }
                TextButton(onClick = { custom.add(Triple("", "", false)) }) { Text("Aggiungi campo personalizzato") }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), modifier = Modifier.testTag("save"), onClick = {
                val t = normalizeTotp(totp, title, user)
                if (t == null) { totpError = true; return@TextButton }
                val fields = custom.filter { it.first.isNotBlank() && it.first !in setOf("Title", "UserName", "Password", "URL", "Notes") && !hiddenField(it.first) }
                onSave(EditorData(title.trim(), user.trim(), pw, url.trim(), notes, fields, t))
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
    )
}

// ───────────────────────────── Importazione da file di testo ─────────────────────────────

@Composable
fun TextImportDialog(rows: List<it.vault.custode.importer.Parsed>, onCancel: () -> Unit, onImport: (List<it.vault.custode.importer.Parsed>) -> Unit) {
    val items = remember(rows) { mutableStateListOf<it.vault.custode.importer.Parsed>().apply { addAll(rows) } }
    var onlyDoubtful by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<it.vault.custode.importer.Parsed?>(null) }
    val doubtful = items.count { !it.confident }
    val shown = if (onlyDoubtful) items.filter { !it.confident } else items.toList()

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.padding(16.dp)) {
                Text("Anteprima dell'importazione", style = MaterialTheme.typography.titleLarge)
                Text("${items.size} righe, $doubtful da controllare (⚠). Correggi o togli quelle sbagliate prima di importare.", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = onlyDoubtful, onCheckedChange = { onlyDoubtful = it }); Spacer(Modifier.width(8.dp)); Text("Solo da controllare")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = showRaw, onCheckedChange = { showRaw = it }); Spacer(Modifier.width(8.dp)); Text("Mostra la riga originale e le password")
                }
                LazyColumn(Modifier.weight(1f).testTag("importList")) {
                    items(shown) { r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text((if (r.confident) "" else "⚠ ") + r.title.ifBlank { "(senza nome)" }, style = MaterialTheme.typography.titleSmall)
                            if (r.user.isNotBlank()) Text("Utente: ${r.user}")
                            if (r.password.isNotEmpty()) Text("Password: " + if (showRaw) r.password else "••••••••")
                            if (r.url.isNotBlank()) Text("Sito: ${r.url}")
                            if (r.notes.isNotBlank() && !(showRaw.not() && r.password.isEmpty() && r.notes == r.raw)) Text("Note: ${r.notes}")
                            else if (r.notes.isNotBlank()) Text("Riga non interpretata: finirà nelle note", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { editing = r }) { Text("Modifica") }
                                TextButton(onClick = { items.remove(r) }) { Text("Togli") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Annulla") }
                    Button(enabled = items.isNotEmpty(), onClick = { onImport(items.toList()) }, modifier = Modifier.testTag("doImport")) { Text("Importa ${items.size}") }
                }
            }
        }
    }
    editing?.let { r ->
        var t by remember(r) { mutableStateOf(r.title) }; var u by remember(r) { mutableStateOf(r.user) }
        var p by remember(r) { mutableStateOf(r.password) }; var n by remember(r) { mutableStateOf(r.notes) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("Modifica riga") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(t, { t = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(u, { u = it }, label = { Text("Utente") }, singleLine = true)
                    OutlinedTextField(p, { p = it }, label = { Text("Password") }, singleLine = true)
                    OutlinedTextField(n, { n = it }, label = { Text("Note") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = items.indexOf(r)
                    if (i >= 0) items[i] = it.vault.custode.importer.Parsed(t.trim(), u.trim(), p, r.url, n, confident = true, raw = r.raw)
                    editing = null
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Annulla") } },
        )
    }
}
