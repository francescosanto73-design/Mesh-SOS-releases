package it.tvguida

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var remote: BroadlinkRemote
    private lateinit var voice: Voice
    private var remoteState by mutableStateOf("Broadlink non configurato")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = BroadlinkRemote(this) { remoteState = it }
        voice = Voice(this)
        val assistantSettings = AssistantSettings(this)
        val numbers = Numbers(this)
        val cache = File(cacheDir, "epg.xml.gz")

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
                var loading by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var query by remember { mutableStateOf("") }
                var selected by remember { mutableStateOf<Channel?>(null) }
                var showAssistant by remember { mutableStateOf(false) }
                var showRemote by remember { mutableStateOf(false) }
                var tick by remember { mutableStateOf(0) } // forza il ricalcolo dei numeri

                val channelsRef = rememberUpdatedState(channels)
                val tools = remember { AssistantTools(this@MainActivity, numbers, remote, { channelsRef.value }, { remoteState }) }
                val assistant = remember { Assistant(assistantSettings, tools) }

                fun load(force: Boolean) = scope.launch {
                    loading = true; error = null
                    try {
                        channels = withContext(Dispatchers.IO) {
                            val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 6 * 3600_000L
                            if (force || stale) Epg.download(cache)
                            Epg.parse(cache)
                        }
                    } catch (e: Exception) {
                        error = "Errore guida: ${e.message}"
                        if (cache.exists() && channels.isEmpty())
                            runCatching { channels = withContext(Dispatchers.IO) { Epg.parse(cache) } }
                    }
                    loading = false
                }

                LaunchedEffect(Unit) { load(false) }

                val now = System.currentTimeMillis()
                val shown = channels
                    .filter { query.isBlank() || it.name.contains(query, true) || it.now(now)?.title?.contains(query, true) == true }
                    .sortedWith(compareBy({ numbers.get(it.id) ?: Int.MAX_VALUE }, { it.name }))

                if (showAssistant) {
                    AssistantScreen(assistant, assistantSettings, voice) { showAssistant = false }
                } else Scaffold(floatingActionButton = {
                    FloatingActionButton(onClick = { showAssistant = true }) { Icon(Icons.Default.Mic, "Assistente") }
                }, topBar = {
                    TopAppBar(
                        title = { Text("In TV ora") },
                        actions = {
                            TextButton(onClick = { showRemote = true }) { Text(if (remote.configured) "Telecomando ✓" else "Telecomando") }
                            IconButton(onClick = { load(true) }) { Icon(Icons.Default.Refresh, "Aggiorna") }
                        },
                    )
                }) { pad ->
                    Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("Cerca canale o programma") }, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(remoteState, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        key(tick) {
                            LazyColumn {
                                items(shown, key = { it.id }) { ch ->
                                    val cur = ch.now(now); val nxt = ch.next(now)
                                    ListItem(
                                        modifier = Modifier.clickable { selected = ch },
                                        overlineContent = { Text("${numbers.get(ch.id)?.toString() ?: "—"} · ${ch.name}") },
                                        headlineContent = { Text(cur?.title ?: "Nessun dato", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { nxt?.let { Text("Poi ${hm(it.start)}: ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                                        trailingContent = { cur?.let { Text("fino ${hm(it.stop)}") } },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                selected?.let { ch ->
                    var text by remember(ch.id) { mutableStateOf(numbers.get(ch.id)?.toString() ?: "") }
                    val cur = ch.now(now)
                    AlertDialog(
                        onDismissRequest = { selected = null },
                        title = { Text(ch.name) },
                        text = {
                            Column {
                                Text(cur?.title ?: "", fontWeight = FontWeight.SemiBold)
                                if (!cur?.desc.isNullOrBlank()) Text(cur!!.desc, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = text, onValueChange = { text = it.filter(Char::isDigit).take(4) },
                                    label = { Text("Numero canale sul box") }, singleLine = true,
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                val n = text.toIntOrNull()
                                numbers.set(ch.id, n); tick++
                                selected = null
                                if (n != null) scope.launch {
                                    remote.goToChannel(n)
                                }
                            }, enabled = text.isNotBlank()) { Text("Vai al canale") }
                        },
                        dismissButton = {
                            TextButton(onClick = { numbers.set(ch.id, text.toIntOrNull()); tick++; selected = null }) { Text("Salva numero") }
                        },
                    )
                }

                if (showRemote) {
                    var ip by remember { mutableStateOf(remote.host ?: "") }
                    var test by remember { mutableStateOf("") }
                    var extra by remember { mutableStateOf("") }
                    var busy by remember { mutableStateOf(false) }
                    var learned by remember { mutableStateOf(remote.learnedCount) }
                    AlertDialog(
                        onDismissRequest = { if (!busy) showRemote = false },
                        title = { Text("Telecomando Broadlink") },
                        text = {
                            Column {
                                Text("Il telefono deve essere sullo stesso Wi-Fi del Broadlink.", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(value = ip, onValueChange = { ip = it.trim() }, singleLine = true,
                                    label = { Text("IP Broadlink (vuoto = cerca)") }, modifier = Modifier.fillMaxWidth())
                                Button(enabled = !busy, onClick = { scope.launch { busy = true; remote.find(ip); ip = remote.host ?: ip; busy = false } }) {
                                    Text("Cerca / collega")
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Tasti imparati: $learned/10. Tocca una cifra, poi punta il telecomando Sky verso il Broadlink e premi quel tasto.", style = MaterialTheme.typography.bodySmall)
                                for (row in listOf(0..4, 5..9)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (d in row) OutlinedButton(
                                        enabled = !busy && remote.configured,
                                        onClick = { scope.launch { busy = true; remote.learn(d); learned = remote.learnedCount; busy = false } },
                                        contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f),
                                    ) { Text(if (remote.hasCode(d)) "$d ✓" else "$d") }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Altri tasti (per l'assistente): ${remote.learnedKeys().filter { it.length > 1 }.joinToString(", ").ifEmpty { "nessuno" }}", style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = extra, onValueChange = { extra = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) },
                                        label = { Text("Nome (es. volume_su)") }, singleLine = true, modifier = Modifier.weight(1f))
                                    Button(enabled = !busy && remote.configured && extra.length > 1,
                                        onClick = { scope.launch { busy = true; remote.learnKey(extra); busy = false } }) { Text("Impara") }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = test, onValueChange = { test = it.filter(Char::isDigit).take(4) },
                                        label = { Text("Prova canale") }, singleLine = true, modifier = Modifier.weight(1f))
                                    Button(enabled = !busy && test.isNotBlank(),
                                        onClick = { scope.launch { busy = true; remote.goToChannel(test.toInt()); busy = false } }) { Text("Invia") }
                                }
                                Text(remoteState, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                            }
                        },
                        confirmButton = { TextButton(enabled = !busy, onClick = { showRemote = false }) { Text("Chiudi") } },
                    )
                }
            }
        }
    }

    override fun onDestroy() { voice.release(); super.onDestroy() }

    private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t))
}
