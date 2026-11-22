package it.tvguida

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface Row
private data class Header(val label: String) : Row
private data class Item(val p: Programme) : Row

/** Palinsesto di un canale, raggruppato per giorno. Toccando un programma si apre la descrizione. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    channel: Channel,
    number: Int?,
    onBack: () -> Unit,
    onSaveNumber: (Int?) -> Unit,
    onGo: (Int) -> Unit,
) {
    BackHandler(onBack = onBack)
    val now = System.currentTimeMillis()
    val dayFmt = SimpleDateFormat("EEEE d MMMM", Locale.ITALY)
    val hm = SimpleDateFormat("HH:mm", Locale.ITALY)
    val rows = remember(channel) {
        val out = mutableListOf<Row>()
        var last = ""
        channel.programmes.filter { it.stop > now }.forEach { p ->
            val day = dayFmt.format(Date(p.start)).replaceFirstChar { it.uppercase() }
            if (day != last) { out += Header(day); last = day }
            out += Item(p)
        }
        out
    }
    var text by remember(channel.id) { mutableStateOf(number?.toString() ?: "") }
    var open by remember(channel.id) { mutableStateOf<Programme?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Programmazione di ${channel.name}", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = { Text("N° canale") }, singleLine = true, modifier = Modifier.width(120.dp),
                )
                Button(
                    enabled = text.isNotBlank(),
                    onClick = { val n = text.toIntOrNull(); onSaveNumber(n); if (n != null) onGo(n) },
                ) { Text("Vai al canale") }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                items(rows) { r ->
                    when (r) {
                        is Header -> Text(
                            r.label, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                        is Item -> {
                            val p = r.p
                            val live = now in p.start until p.stop
                            Column(Modifier.fillMaxWidth().clickable { open = if (open == p) null else p }.padding(vertical = 8.dp)) {
                                Row {
                                    Text("${hm.format(Date(p.start))}–${hm.format(Date(p.stop))}", modifier = Modifier.width(110.dp))
                                    Text(p.title, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal)
                                }
                                if (open == p && p.desc.isNotBlank()) {
                                    Text(p.desc, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 110.dp, top = 4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
