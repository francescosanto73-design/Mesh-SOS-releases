package it.tvguida

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Gli strumenti dell'assistente: guida TV, cambio canale, tasti IR, timer, sveglie, lista della spesa. */
class AssistantTools(
    private val activity: Activity,
    private val numbers: Numbers,
    private val remote: BroadlinkRemote,
    private val channels: () -> List<Channel>,
    private val remoteMessage: () -> String,
) : ToolHost {

    private val prefs = activity.getSharedPreferences("lista", Context.MODE_PRIVATE)
    private val hm = SimpleDateFormat("HH:mm", Locale.ITALY)

    // ---------- definizioni ----------

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun choice(desc: String, vararg v: String) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(v.toList()))

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", desc).put(
            "input_schema",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)),
        )

    override fun tools(): JSONArray {
        val a = JSONArray()
        a.put(tool(
            "cosa_in_onda",
            "Guida TV italiana: cosa c'è in onda su un canale, oppure cerca un programma o un film per titolo/argomento. Restituisce canale, orario e titolo; per un solo canale anche la trama.",
            JSONObject()
                .put("canale", str("Nome del canale, per esempio «Rai 3» o «Sky Cinema Uno». Omettilo per cercare su tutti."))
                .put("cerca", str("Testo da cercare nel titolo o nella trama, per esempio «Report» o «calcio»."))
                .put("quando", choice("Finestra di tempo. Predefinito: adesso.", "adesso", "prossime_ore", "stasera", "oggi")),
        ))
        a.put(tool(
            "cambia_canale",
            "Cambia canale sul box TV Sky digitando il numero col telecomando infrarosso. Indica il nome del canale oppure il numero.",
            JSONObject()
                .put("canale", str("Nome del canale"))
                .put("numero", int("Numero del canale sul box, se l'utente lo dice direttamente")),
        ))
        val keys = remote.learnedKeys()
        if (keys.isNotEmpty()) a.put(tool(
            "invia_tasto_tv",
            "Preme un tasto del telecomando della TV/box (volume, muto, accensione, frecce, ok…).",
            JSONObject()
                .put("tasto", str("Nome del tasto. Disponibili: ${keys.joinToString(", ")}"))
                .put("ripetizioni", int("Quante volte premerlo (1-10). Predefinito 1.")),
            listOf("tasto"),
        ))
        a.put(tool(
            "imposta_timer",
            "Imposta un timer a conto alla rovescia sul telefono.",
            JSONObject().put("minuti", int("Minuti")).put("secondi", int("Secondi")).put("etichetta", str("Nome del timer")),
        ))
        a.put(tool(
            "imposta_sveglia",
            "Imposta una sveglia o un promemoria a un'ora precisa del giorno (formato 24 ore) sul telefono.",
            JSONObject().put("ora", int("Ora 0-23")).put("minuti", int("Minuti 0-59")).put("etichetta", str("Testo della sveglia o del promemoria")),
            listOf("ora", "minuti"),
        ))
        a.put(tool(
            "lista_spesa",
            "Gestisce la lista della spesa salvata sul telefono.",
            JSONObject()
                .put("azione", choice("Cosa fare", "aggiungi", "rimuovi", "leggi", "svuota"))
                .put("voci", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Voci da aggiungere o togliere")),
            listOf("azione"),
        ))
        return a
    }

    // ---------- esecuzione ----------

    override suspend fun run(name: String, input: JSONObject): Pair<String, Boolean> = when (name) {
        "cosa_in_onda" -> onAir(input) to false
        "cambia_canale" -> changeChannel(input)
        "invia_tasto_tv" -> pressKey(input)
        "imposta_timer" -> timer(input)
        "imposta_sveglia" -> alarm(input)
        "lista_spesa" -> shopping(input) to false
        else -> "Strumento sconosciuto: $name" to true
    }

    // ---- guida TV ----

    private fun norm(s: String) = s.lowercase().replace(" hd", "").replace(Regex("[^a-z0-9+]"), "")

    private fun find(q: String, all: List<Channel>): List<Channel> {
        val nq = norm(q)
        if (nq.isEmpty()) return emptyList()
        val exact = all.filter { norm(it.name) == nq }
        if (exact.isNotEmpty()) return exact
        return all.filter { val n = norm(it.name); n.contains(nq) || (nq.contains(n) && n.length > 3) }
            .sortedBy { kotlin.math.abs(norm(it.name).length - nq.length) }
    }

    private fun num(ch: Channel) = numbers.get(ch.id)?.let { " (canale $it)" } ?: ""

    private fun window(quando: String, now: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        return when (quando) {
            "prossime_ore" -> now to now + 3 * 3600_000L
            "stasera" -> {
                cal.set(Calendar.HOUR_OF_DAY, 20); cal.set(Calendar.MINUTE, 30); cal.set(Calendar.SECOND, 0)
                val from = maxOf(cal.timeInMillis, now)
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 30)
                from to maxOf(cal.timeInMillis, from + 60_000)
            }
            "oggi" -> {
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                now to maxOf(cal.timeInMillis, now + 60_000)
            }
            else -> now to now + 60_000
        }
    }

    private fun onAir(i: JSONObject): String {
        val all = channels()
        if (all.isEmpty()) return "La guida TV non è ancora caricata."
        val now = System.currentTimeMillis()
        val canale = i.optString("canale").trim()
        val cerca = i.optString("cerca").trim()
        val quando = i.optString("quando", "adesso").ifBlank { "adesso" }
        val pool = if (canale.isNotEmpty()) find(canale, all) else all
        if (canale.isNotEmpty() && pool.isEmpty()) return "Nessun canale chiamato «$canale» nella guida."
        val (from, to) = window(quando, now)

        if (cerca.isEmpty()) {
            val sel = if (canale.isNotEmpty()) pool.take(5)
            else pool.filter { numbers.get(it.id) != null }.ifEmpty { pool }.take(25)
            return sel.joinToString("\n") { ch ->
                if (quando == "adesso") {
                    val cur = ch.now(now); val nxt = ch.next(now)
                    buildString {
                        append("${ch.name}${num(ch)}: ")
                        append(cur?.let { "ora «${it.title}» fino alle ${hm.format(Date(it.stop))}" } ?: "nessun dato")
                        nxt?.let { append("; poi «${it.title}» alle ${hm.format(Date(it.start))}") }
                        if (sel.size == 1 && !cur?.desc.isNullOrBlank()) append(". Trama: ${cur!!.desc.take(300)}")
                    }
                } else {
                    val ps = ch.programmes.filter { it.stop > from && it.start < to }.take(4)
                    "${ch.name}${num(ch)}: " + ps.joinToString("; ") { "${hm.format(Date(it.start))} ${it.title}" }.ifEmpty { "nessun dato" }
                }
            }
        }
        val hits = pool.flatMap { ch ->
            ch.programmes
                .filter { it.stop > from && it.start < to && (it.title.contains(cerca, true) || it.desc.contains(cerca, true)) }
                .map { ch to it }
        }.sortedBy { it.second.start }.take(15)
        if (hits.isEmpty()) return "Nessun programma trovato per «$cerca» in quella fascia."
        return hits.joinToString("\n") { (ch, p) -> "${ch.name}${num(ch)}: ${hm.format(Date(p.start))} ${p.title}" }
    }

    // ---- TV ----

    private suspend fun changeChannel(i: JSONObject): Pair<String, Boolean> {
        val n: Int
        val label: String
        if (i.optInt("numero") > 0) {
            n = i.getInt("numero"); label = "canale $n"
        } else {
            val q = i.optString("canale").trim()
            if (q.isEmpty()) return "Serve il nome o il numero del canale." to true
            val m = find(q, channels())
            if (m.isEmpty()) return "Nessun canale chiamato «$q» nella guida." to true
            val withNum = m.filter { numbers.get(it.id) != null }
            val pick = when {
                withNum.size == 1 -> withNum[0]
                m.size == 1 -> m[0]
                else -> return "Più canali possibili: ${m.take(5).joinToString(", ") { it.name + num(it) }}. Chiedi all'utente quale." to false
            }
            n = numbers.get(pick.id)
                ?: return "Il numero di «${pick.name}» non è ancora impostato: l'utente lo deve salvare toccando il canale nell'elenco." to true
            label = pick.name
        }
        return if (remote.goToChannel(n)) "Inviato il comando per $label (canale $n)." to false
        else "Non riuscito: ${remoteMessage()}" to true
    }

    private suspend fun pressKey(i: JSONObject): Pair<String, Boolean> {
        val key = i.optString("tasto").trim().lowercase()
        if (!remote.hasKey(key)) return "Tasto «$key» non imparato. Disponibili: ${remote.learnedKeys().joinToString(", ")}." to true
        val times = i.optInt("ripetizioni", 1).coerceIn(1, 10)
        return try {
            repeat(times) { remote.sendKey(key); delay(300) }
            "Premuto «$key» $times volte." to false
        } catch (e: Exception) { "Errore: ${e.message ?: e.javaClass.simpleName}" to true }
    }

    // ---- timer, sveglie ----

    private suspend fun launch(intent: Intent, ok: String): Pair<String, Boolean> = try {
        withContext(Dispatchers.Main) { activity.startActivity(intent) }
        ok to false
    } catch (e: ActivityNotFoundException) {
        "Nessuna app orologio disponibile sul telefono." to true
    } catch (e: Exception) { "Errore: ${e.message}" to true }

    private suspend fun timer(i: JSONObject): Pair<String, Boolean> {
        val secs = i.optInt("minuti") * 60 + i.optInt("secondi")
        if (secs <= 0) return "Serve una durata." to true
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, i.optString("etichetta"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(intent, "Timer di ${secs / 60} minuti e ${secs % 60} secondi impostato.")
    }

    private suspend fun alarm(i: JSONObject): Pair<String, Boolean> {
        val h = i.optInt("ora", -1); val m = i.optInt("minuti", -1)
        if (h !in 0..23 || m !in 0..59) return "Orario non valido." to true
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, i.optString("etichetta"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(intent, "Sveglia impostata alle %02d:%02d.".format(h, m))
    }

    // ---- lista della spesa ----

    private fun shopping(i: JSONObject): String {
        val arr = JSONArray(prefs.getString("items", "[]"))
        val items = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        val voci = i.optJSONArray("voci")?.let { a -> (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()
        when (i.optString("azione")) {
            "aggiungi" -> items += voci.filter { v -> items.none { it.equals(v, true) } }
            "rimuovi" -> items.removeAll { x -> voci.any { it.equals(x, true) } }
            "svuota" -> items.clear()
        }
        prefs.edit().putString("items", JSONArray(items).toString()).apply()
        return if (items.isEmpty()) "La lista della spesa è vuota." else "Lista della spesa: ${items.joinToString(", ")}."
    }
}
