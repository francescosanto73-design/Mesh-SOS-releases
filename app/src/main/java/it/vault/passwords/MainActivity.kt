package it.vault.passwords

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import java.io.File
import kotlinx.coroutines.launch

class VaultViewModel : ViewModel() {
    lateinit var vault: Vault
    var unlocked by mutableStateOf(false)
    var version by mutableIntStateOf(0) // forza il refresh della lista
    fun init(f: File) { if (!::vault.isInitialized) vault = Vault(f) }
    fun lock() { if (::vault.isInitialized) { vault.lock(); unlocked = false; version++ } }
}

class MainActivity : ComponentActivity() {
    private val vm: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Blocca screenshot e anteprima nelle app recenti.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        vm.init(File(filesDir, "vault.bin"))
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(vm, ::copySecret) } } }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.lock() // blocco automatico in background
    }

    private fun copySecret(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) {
                if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 30_000)
    }
}

@Composable
fun App(vm: VaultViewModel, copy: (String, String) -> Unit) {
    when {
        vm.unlocked -> VaultScreen(vm, copy)
        else -> LoginScreen(vm)
    }
}

@Composable
fun LoginScreen(vm: VaultViewModel) {
    val creating = !vm.vault.exists
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Text(
            if (creating) "Crea la password principale" else "Sblocca il caveau",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (creating) {
            Spacer(Modifier.height(8.dp))
            Text(
                "È l'unica password da ricordare. Se la dimentichi, i dati non sono recuperabili: non esiste nessun reset.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
        SecretField("Password principale", pw) { pw = it }
        if (creating) {
            Spacer(Modifier.height(8.dp))
            SecretField("Ripeti password", pw2) { pw2 = it }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && pw.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                error = null
                if (creating && pw.length < 8) { error = "Almeno 8 caratteri (meglio una frase lunga)"; return@Button }
                if (creating && pw != pw2) { error = "Le password non coincidono"; return@Button }
                busy = true
                val chars = pw.toCharArray()
                scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    val result = runCatching { if (creating) vm.vault.create(chars) else vm.vault.unlock(chars) }
                    chars.fill('\u0000')
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        busy = false
                        result.onSuccess { pw = ""; pw2 = ""; vm.unlocked = true; vm.version++ }
                            .onFailure { error = if (it is WrongPasswordException) "Password errata" else "Errore: ${it.message}" }
                    }
                }
            },
        ) { Text(if (busy) "Attendere…" else if (creating) "Crea caveau" else "Sblocca") }
    }
}

@Composable
fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(vm: VaultViewModel, copy: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Entry?>(null) }
    val shown = remember(vm.version, query) {
        vm.vault.entries
            .filter { query.isBlank() || it.title.contains(query, true) || it.username.contains(query, true) || it.url.contains(query, true) }
            .sortedBy { it.title.lowercase() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password") },
                actions = { IconButton(onClick = { vm.lock() }) { Icon(Icons.Default.Lock, "Blocca") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) { Icon(Icons.Default.Add, "Aggiungi") }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Cerca") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (vm.vault.entries.isEmpty()) "Nessuna password.\nTocca + per aggiungerne una." else "Nessun risultato")
                }
            } else LazyColumn {
                items(shown, key = { it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.title) },
                        supportingContent = { if (e.username.isNotBlank()) Text(e.username) },
                        trailingContent = {
                            IconButton(onClick = { copy("password", e.password) }) { Icon(Icons.Default.ContentCopy, "Copia password") }
                        },
                        modifier = Modifier.clickable { selected = e },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    selected?.let { e ->
        DetailDialog(
            e, copy,
            onEdit = { editing = e; showEditor = true; selected = null },
            onDelete = { vm.vault.delete(e.id); vm.version++; selected = null },
            onClose = { selected = null },
        )
    }
    if (showEditor) {
        EditorDialog(
            initial = editing,
            onSave = { vm.vault.upsert(it); vm.version++; showEditor = false },
            onClose = { showEditor = false },
        )
    }
}

@Composable
fun DetailDialog(e: Entry, copy: (String, String) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(e.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (e.username.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Utente: ${e.username}", Modifier.weight(1f))
                    IconButton(onClick = { copy("utente", e.username) }) { Icon(Icons.Default.ContentCopy, "Copia utente") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Password: " + if (show) e.password else "••••••••", Modifier.weight(1f))
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostra/nascondi")
                    }
                    IconButton(onClick = { copy("password", e.password) }) { Icon(Icons.Default.ContentCopy, "Copia password") }
                }
                if (e.url.isNotBlank()) Text("Sito: ${e.url}")
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
        title = { Text("Eliminare \"${e.title}\"?") },
        text = { Text("L'operazione non è reversibile.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } },
    )
}

@Composable
fun EditorDialog(initial: Entry?, onSave: (Entry) -> Unit, onClose: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pw by remember { mutableStateOf(initial?.password ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var visible by remember { mutableStateOf(initial == null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "Nuova password" else "Modifica") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Nome (es. Gmail)") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("Utente / email") }, singleLine = true)
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostra/nascondi")
                        }
                    },
                )
                TextButton(onClick = { pw = Vault.generatePassword(); visible = true }) {
                    Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Genera password sicura")
                }
                OutlinedTextField(url, { url = it }, label = { Text("Sito web") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Note") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: Entry(title = "")).copy(
                            title = title.trim(), username = user.trim(), password = pw, url = url.trim(), notes = notes,
                        ),
                    )
                },
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Annulla") } },
    )
}
