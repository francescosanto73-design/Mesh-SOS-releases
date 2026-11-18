package it.vault.passwords

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VaultViewModel : ViewModel() {
    lateinit var vault: Vault
    var unlocked by mutableStateOf(false)
    var version by mutableIntStateOf(0) // forza il refresh della lista
    var bioEnabled by mutableStateOf(false)
    var pendingImport by mutableStateOf<ByteArray?>(null)
    var autoPrompted = false
    var keepUnlocked = false // true mentre è aperto un selettore file di sistema

    fun init(f: File) { if (!::vault.isInitialized) vault = Vault(f) }
    fun lock() {
        if (::vault.isInitialized) { vault.lock(); unlocked = false; version++ }
        autoPrompted = false
    }
}

class Actions(
    val copy: (String, String) -> Unit,
    val bioAvailable: Boolean,
    val unlockBio: () -> Unit,
    val setBio: (Boolean) -> Unit,
    val export: () -> Unit,
    val restore: () -> Unit,
)

class MainActivity : FragmentActivity() {
    private val vm: VaultViewModel by viewModels()
    private lateinit var bio: BioUnlock

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.keepUnlocked = false
        if (uri != null) {
            val ok = runCatching { contentResolver.openOutputStream(uri)?.use { vm.vault.exportTo(it) } ?: error("") }.isSuccess
            toast(if (ok) "Backup cifrato salvato" else "Esportazione fallita")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.keepUnlocked = false
        if (uri != null) {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                require(bytes.size < 20_000_000)
                vm.pendingImport = bytes
            }.onFailure { toast("File non leggibile") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Blocca screenshot e anteprima nelle app recenti.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        vm.init(File(filesDir, "vault.bin"))
        bio = BioUnlock(this)
        vm.bioEnabled = bio.enabled && bio.available()
        val actions = Actions(
            copy = ::copySecret,
            bioAvailable = bio.available(),
            unlockBio = ::unlockWithBio,
            setBio = ::setBio,
            export = {
                vm.keepUnlocked = true
                exportLauncher.launch("custode-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".cpv")
            },
            restore = { vm.keepUnlocked = true; importLauncher.launch(arrayOf("*/*")) },
        )
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App(vm, actions) } } }
    }

    override fun onResume() {
        super.onResume()
        if (!vm.unlocked && vm.vault.exists && vm.bioEnabled && !vm.autoPrompted) {
            vm.autoPrompted = true
            unlockWithBio()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !vm.keepUnlocked) vm.lock() // blocco automatico in background
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun prompt(title: String, cipher: Cipher, onOk: (Cipher) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Usa la password")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onOk)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    code != BiometricPrompt.ERROR_CANCELED
                ) toast(msg.toString())
            }
        }).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun setBio(on: Boolean) {
        if (!on) { bio.disable(); vm.bioEnabled = false; return }
        if (!vm.unlocked) return
        try {
            val cipher = bio.encryptCipher()
            prompt("Attiva lo sblocco con impronta", cipher) { c ->
                val raw = vm.vault.keyBytes()
                try {
                    bio.store(c, raw); vm.bioEnabled = true; toast("Sblocco con impronta attivato")
                } catch (e: Exception) {
                    bio.disable(); toast("Attivazione fallita")
                } finally { raw.fill(0) }
            }
        } catch (e: Exception) {
            bio.disable(); toast("Impronta non disponibile su questo telefono")
        }
    }

    private fun unlockWithBio() {
        val cipher = try {
            bio.decryptCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            bio.disable(); vm.bioEnabled = false
            toast("Le impronte sono cambiate: usa la password e riattiva lo sblocco"); return
        } catch (e: Exception) {
            toast("Sblocco con impronta non disponibile: usa la password"); return
        }
        prompt("Sblocca il caveau", cipher) { c ->
            val raw = runCatching { bio.read(c) }.getOrNull()
            if (raw == null) { toast("Sblocco non riuscito: usa la password"); return@prompt }
            try {
                vm.vault.unlockWithKey(raw); vm.unlocked = true; vm.version++
            } catch (e: Exception) {
                toast("Sblocco non riuscito: usa la password")
            } finally { raw.fill(0) }
        }
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
fun App(vm: VaultViewModel, act: Actions) {
    if (vm.unlocked) VaultScreen(vm, act) else LoginScreen(vm, act)
    vm.pendingImport?.let { ImportDialog(vm, act) }
}

@Composable
fun ImportDialog(vm: VaultViewModel, act: Actions) {
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) vm.pendingImport = null },
        title = { Text("Ripristina backup") },
        text = {
            Column {
                Text("Inserisci la password principale con cui è stato creato il backup. I dati attuali di questo telefono verranno sostituiti.")
                Spacer(Modifier.height(8.dp))
                SecretField("Password del backup", pw) { pw = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pw.isNotEmpty(), onClick = {
                busy = true; error = null
                val bytes = vm.pendingImport!!
                val chars = pw.toCharArray()
                scope.launch(Dispatchers.Default) {
                    val r = runCatching { vm.vault.replaceFrom(bytes, chars) }
                    chars.fill('\u0000')
                    withContext(Dispatchers.Main) {
                        busy = false
                        r.onSuccess { act.setBio(false); vm.pendingImport = null; vm.unlocked = true; vm.version++ }
                            .onFailure { error = if (it is WrongPasswordException) "Password errata" else "File non valido" }
                    }
                }
            }) { Text(if (busy) "Attendere…" else "Ripristina") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { vm.pendingImport = null }) { Text("Annulla") } },
    )
}

@Composable
fun LoginScreen(vm: VaultViewModel, act: Actions) {
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
                scope.launch(Dispatchers.Default) {
                    val result = runCatching { if (creating) vm.vault.create(chars) else vm.vault.unlock(chars) }
                    chars.fill('\u0000')
                    withContext(Dispatchers.Main) {
                        busy = false
                        result.onSuccess { pw = ""; pw2 = ""; vm.unlocked = true; vm.version++ }
                            .onFailure { error = if (it is WrongPasswordException) "Password errata" else "Errore: ${it.message}" }
                    }
                }
            },
        ) { Text(if (busy) "Attendere…" else if (creating) "Crea caveau" else "Sblocca") }
        if (!creating && vm.bioEnabled) {
            OutlinedButton(onClick = act.unlockBio, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Usa l'impronta")
            }
        }
        if (creating) {
            TextButton(onClick = act.restore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Ripristina da backup")
            }
        }
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
fun VaultScreen(vm: VaultViewModel, act: Actions) {
    val copy = act.copy
    var menu by remember { mutableStateOf(false) }
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
                actions = {
                    IconButton(onClick = { vm.lock() }) { Icon(Icons.Default.Lock, "Blocca") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Esporta backup cifrato") }, onClick = { menu = false; act.export() })
                        DropdownMenuItem(text = { Text("Ripristina da backup") }, onClick = { menu = false; act.restore() })
                        if (act.bioAvailable) DropdownMenuItem(
                            text = { Text(if (vm.bioEnabled) "Disattiva sblocco con impronta" else "Attiva sblocco con impronta") },
                            onClick = { menu = false; act.setBio(!vm.bioEnabled) },
                        )
                    }
                },
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
