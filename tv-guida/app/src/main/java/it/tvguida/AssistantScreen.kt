package it.tvguida

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private data class Msg(val mine: Boolean, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(assistant: Assistant, settings: AssistantSettings, voice: Voice, onBack: () -> Unit) {
    BackHandler { voice.stopSpeaking(); onBack() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(settings.apiKey.isBlank()) }
    val list = rememberLazyListState()

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        input = ""
        msgs += Msg(true, t)
        scope.launch {
            busy = true; status = "Penso…"
            val ans = try {
                assistant.ask(t) { step -> status = "Eseguo: $step…" }
            } catch (e: Exception) { "Errore: ${e.message ?: e.javaClass.simpleName}" }
            msgs += Msg(false, ans)
            status = ""
            busy = false
            if (settings.speak) voice.speak(ans)
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.listen() else status = "Permesso del microfono negato"
    }
    SideEffect {
        voice.onText = { send(it) }
        voice.onListening = { listening = it; if (it) status = "Ti ascolto…" else if (!busy) status = "" }
        voice.onFail = { status = it }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Assistente") },
            navigationIcon = { IconButton(onClick = { voice.stopSpeaking(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Impostazioni") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (msgs.isEmpty()) item {
                    Text(
                        "Prova: «Cosa c'è su Rai 3?», «Metti Sky Cinema Uno», «Imposta un timer di 10 minuti», «Aggiungi latte alla lista della spesa» o una domanda qualsiasi.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                items(msgs) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.widthIn(max = 320.dp),
                        ) { Text(m.text, Modifier.padding(12.dp)) }
                    }
                }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Scrivi o usa il microfono") }, maxLines = 3,
                )
                IconButton(
                    enabled = !busy,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.listen()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) {
                    Icon(Icons.Default.Mic, "Parla", tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                IconButton(enabled = !busy && input.isNotBlank(), onClick = { send(input) }) { Icon(Icons.AutoMirrored.Filled.Send, "Invia") }
            }
        }
    }

    if (showSettings) {
        var key by remember { mutableStateOf(settings.apiKey) }
        var model by remember { mutableStateOf(settings.model) }
        var speak by remember { mutableStateOf(settings.speak) }
        AlertDialog(
            onDismissRequest = { if (settings.apiKey.isNotBlank() || key.isBlank()) showSettings = false },
            title = { Text("Impostazioni assistente") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Serve una chiave API di Anthropic (console.anthropic.com → API keys). Resta solo su questo telefono; ogni richiesta ha un piccolo costo a consumo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = key, onValueChange = { key = it }, singleLine = true, label = { Text("Chiave API (sk-ant-…)") })
                    OutlinedTextField(
                        value = model, onValueChange = { model = it }, singleLine = true, label = { Text("Modello") },
                        supportingText = { Text("Più veloce ed economico: claude-haiku-5-5") },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = speak, onCheckedChange = { speak = it })
                        Spacer(Modifier.width(8.dp)); Text("Leggi le risposte ad alta voce")
                    }
                    TextButton(onClick = { assistant.reset(); msgs.clear() }) { Text("Azzera la conversazione") }
                }
            },
            confirmButton = {
                TextButton(onClick = { settings.apiKey = key; settings.model = model; settings.speak = speak; showSettings = false }) { Text("Salva") }
            },
        )
    }
}
