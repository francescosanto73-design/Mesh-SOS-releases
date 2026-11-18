package it.vault.custode

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import it.vault.custode.kdbx.KdbxEntry
import it.vault.custode.kdbx.KdbxGroup

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

@Composable
fun App(vm: AppVm, act: Actions) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (vm.unlocked) MainScreen(vm, act) else LoginScreen(vm, act)
        }
    }
    vm.pendingImport?.let { ImportDialog(vm, act) }
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
        TextButton(onClick = act.pickDatabase, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (creating) "Ho già un database KeePass (.kdbx)" else "Sostituisci con un altro database…")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppVm, act: Actions) {
    val db = vm.store.db ?: return
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var fab by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "newGroup", "changeKey", "info", "deleteGroup"
    var editor by remember { mutableStateOf<String?>(null) }  // "new" oppure UUID della voce
    val stack = vm.groupStack.toList()
    val group = remember(vm.version, stack) { resolve(db.root, stack) }
    val groups = remember(vm.version, stack) { group.groups.sortedBy { it.name.lowercase() } }
    val entries = remember(vm.version, stack, query) {
        val src = if (query.isBlank()) group.entries else db.allEntries().filter {
            it.title.contains(query, true) || it.username.contains(query, true) || it.url.contains(query, true)
        }
        src.sortedBy { it.title.lowercase() }
    }
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
                        DropdownMenuItem(text = { Text("Apri un altro database…") }, onClick = { menu = false; act.pickDatabase() })
                        DropdownMenuItem(text = { Text("Cambia password principale") }, onClick = { menu = false; dialog = "changeKey" })
                        if (act.bioAvailable) DropdownMenuItem(
                            text = { Text(if (vm.bioEnabled) "Disattiva sblocco con impronta" else "Attiva sblocco con impronta") },
                            onClick = { menu = false; act.setBio(!vm.bioEnabled) },
                        )
                        if (stack.isNotEmpty()) DropdownMenuItem(
                            text = { Text("Elimina questo gruppo", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; dialog = "deleteGroup" },
                        )
                        DropdownMenuItem(text = { Text("Informazioni") }, onClick = { menu = false; dialog = "info" })
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
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
                    Text(if (query.isBlank()) "Nessuna password qui.\nTocca + per aggiungerne una." else "Nessun risultato")
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

    vm.selectedEntry?.let { id ->
        val e = db.allEntries().firstOrNull { it.id == id }
        if (e == null) vm.selectedEntry = null else DetailDialog(
            e, act,
            onEdit = { editor = e.id; vm.selectedEntry = null },
            onDelete = { db.delete(e); vm.selectedEntry = null; vm.persist() },
            onClose = { vm.selectedEntry = null },
        )
    }
    editor?.let { id ->
        EditorDialog(
            initial = if (id == "new") null else db.allEntries().firstOrNull { it.id == id },
            onSave = { t, u, p, url, n ->
                if (id == "new") group.addEntry(t, u, p, url, n)
                else db.allEntries().firstOrNull { it.id == id }?.apply { title = t; username = u; password = p; this.url = url; notes = n }
                editor = null; vm.persist()
            },
            onClose = { editor = null },
        )
    }
    when (dialog) {
        "newGroup" -> TextPrompt("Nuovo gruppo", "Nome del gruppo", onOk = { group.addGroup(it); dialog = null; vm.persist() }, onClose = { dialog = null })
        "deleteGroup" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Eliminare \"${group.name}\"?") },
            text = { Text("Verranno eliminate anche le ${group.entriesRecursive().size} voci contenute. L'operazione non è reversibile.") },
            confirmButton = {
                TextButton(onClick = { db.delete(group); vm.groupStack.removeAt(vm.groupStack.lastIndex); dialog = null; vm.persist() }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annulla") } },
        )
        "changeKey" -> ChangeKeyDialog(vm, act, onClose = { dialog = null })
        "info" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Custode 1.0") },
            text = {
                Text("Gestore di password offline, senza accesso a internet.\n\nIl database è un file KDBX 4, lo stesso formato di KeePass, KeePassXC e KeePassDX: puoi esportarlo e aprirlo con quelle app.\n\nLa cifratura usa AES-256 con chiave derivata da Argon2d. Il nucleo del formato è stato scritto da zero e verificato contro KeePassXC; non è stato sottoposto a un audit indipendente.")
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

// ───────────────────────────── Dettaglio e modifica ─────────────────────────────

@Composable
fun DetailDialog(e: KdbxEntry, act: Actions, onEdit: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) CIcons.VisibilityOff else CIcons.Visibility, "Mostra o nascondi")
                    }
                    IconButton(onClick = { act.copy("password", e.password) }) { Icon(CIcons.Copy, "Copia password") }
                }
                if (e.url.isNotBlank()) Text("Sito: ${e.url}", Modifier.padding(top = 4.dp))
                e.customFields.forEach { (k, v) -> Text("$k: $v", Modifier.padding(top = 4.dp)) }
                if (e.notes.isNotBlank()) Text("Note: ${e.notes}", Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("Modifica") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirm = true }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onClose) { Text("Chiudi") }
            }
        },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Eliminare \"${e.title}\"?") }, text = { Text("L'operazione non è reversibile.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } },
    )
}

@Composable
fun EditorDialog(initial: KdbxEntry?, onSave: (String, String, String, String, String) -> Unit, onClose: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pw by remember { mutableStateOf(initial?.password ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
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
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) CIcons.VisibilityOff else CIcons.Visibility, "Mostra o nascondi")
                        }
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
                OutlinedTextField(notes, { notes = it }, label = { Text("Note") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), modifier = Modifier.testTag("save"), onClick = { onSave(title.trim(), user.trim(), pw, url.trim(), notes) }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
    )
}
