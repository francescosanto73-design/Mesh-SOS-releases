package it.tvguida

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private lateinit var remote: SkyRemote
    private var remoteState by mutableStateOf("Non collegato")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = SkyRemote(this) { remoteState = it }
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
                var showDevices by remember { mutableStateOf(false) }
                var tick by remember { mutableStateOf(0) } // forza il ricalcolo dei numeri

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

                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    if (ok) { remote.start(); showDevices = true } else remoteState = "Permesso Bluetooth negato"
                }

                LaunchedEffect(Unit) { load(false) }

                val now = System.currentTimeMillis()
                val shown = channels
                    .filter { query.isBlank() || it.name.contains(query, true) || it.now(now)?.title?.contains(query, true) == true }
                    .sortedWith(compareBy({ numbers.get(it.id) ?: Int.MAX_VALUE }, { it.name }))

                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("In TV ora") },
                        actions = {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                else { remote.start(); showDevices = true }
                            }) { Text(if (remote.connected) "Box ✓" else "Collega box") }
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
                                    if (!remote.goToChannel(n)) remoteState = "Box non collegato: usa «Collega box»"
                                }
                            }, enabled = text.isNotBlank()) { Text("Vai al canale") }
                        },
                        dismissButton = {
                            TextButton(onClick = { numbers.set(ch.id, text.toIntOrNull()); tick++; selected = null }) { Text("Salva numero") }
                        },
                    )
                }

                if (showDevices) {
                    AlertDialog(
                        onDismissRequest = { showDevices = false },
                        title = { Text("Scegli il box Sky") },
                        text = {
                            Column {
                                Text("Prima associa il telefono al box: sul Sky Stream apri Impostazioni › Bluetooth e cerca questo telefono. Poi tocca qui il box.", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                remote.bondedDevices().forEach { d ->
                                    TextButton(onClick = { remote.connect(d); showDevices = false }) { Text(d.name ?: d.address) }
                                }
                                TextButton(onClick = { remote.start(); remote.adapter?.let { startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Impostazioni Bluetooth") }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Chiudi") } },
                    )
                }
            }
        }
    }

    override fun onDestroy() { remote.stop(); super.onDestroy() }

    private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t))
}
