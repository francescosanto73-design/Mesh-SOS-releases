package it.tvguida

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssistantSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    var apiKey: String
        get() = p.getString("key", "") ?: ""
        set(v) = p.edit().putString("key", v.trim()).apply()
    var model: String
        get() = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = p.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()
    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()

    companion object { const val DEFAULT_MODEL = "claude-opus-5-5" }
}

/** Strumenti che Claude può chiamare: definizioni JSON ed esecuzione sul telefono. */
interface ToolHost {
    fun tools(): JSONArray
    /** Restituisce (testo del risultato, è un errore). */
    suspend fun run(name: String, input: JSONObject): Pair<String, Boolean>
}

/** Assistente vocale: parla con l'API Messages di Claude e fa girare il ciclo degli strumenti. */
class Assistant(private val settings: AssistantSettings, private val host: ToolHost) {
    // Memoria della conversazione: solo i testi di domande e risposte (niente strumenti), ultimi 10 scambi.
    private val history = mutableListOf<JSONObject>()

    suspend fun ask(text: String, onStep: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank()) error("Manca la chiave API: aprila dalle impostazioni dell'assistente.")
        val msgs = JSONArray()
        history.forEach { msgs.put(it) }
        msgs.put(JSONObject().put("role", "user").put("content", text))

        var answer = ""
        for (round in 0 until 6) {
            val resp = call(msgs)
            val content = resp.getJSONArray("content")
            // Si rimanda il contenuto così com'è (compresi eventuali blocchi di ragionamento).
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            val stop = resp.optString("stop_reason")
            if (stop == "refusal") { answer = "Non posso aiutarti con questa richiesta."; break }

            val blocks = (0 until content.length()).map { content.getJSONObject(it) }
            val uses = blocks.filter { it.optString("type") == "tool_use" }
            if (stop != "tool_use" || uses.isEmpty()) {
                answer = blocks.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }.trim()
                if (stop == "max_tokens") answer += " …"
                break
            }
            val results = JSONArray()
            for (u in uses) {
                onStep(u.getString("name"))
                val (out, isErr) = try {
                    host.run(u.getString("name"), u.optJSONObject("input") ?: JSONObject())
                } catch (e: Exception) { "Errore: ${e.message ?: e.javaClass.simpleName}" to true }
                results.put(JSONObject().put("type", "tool_result").put("tool_use_id", u.getString("id")).put("content", out).apply {
                    if (isErr) put("is_error", true)
                })
            }
            msgs.put(JSONObject().put("role", "user").put("content", results))
        }
        if (answer.isBlank()) answer = "Fatto."
        history += JSONObject().put("role", "user").put("content", text)
        history += JSONObject().put("role", "assistant").put("content", answer)
        while (history.size > 20) { history.removeAt(0); history.removeAt(0) }
        answer
    }

    fun reset() = history.clear()

    private fun system(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALY).format(Date())
        return """
Sei un assistente vocale in italiano sul telefono dell'utente, in stile Alexa.
Rispondi in modo breve e naturale, pensato per essere letto ad alta voce: niente markdown, elenchi puntati, asterischi o emoji.
Per la TV, i tasti del telecomando, i timer, le sveglie e la lista della spesa usa gli strumenti. Non dire di aver eseguito un'azione se lo strumento non l'ha confermata; se fallisce, spiega brevemente perché.
Se una richiesta è ambigua (per esempio più canali possibili) chiedi una conferma breve. Per le domande generali rispondi direttamente.
Adesso è $now.
""".trim()
    }

    private fun call(msgs: JSONArray): JSONObject {
        return try { post(msgs, extras = true) }
        catch (e: BadRequest) { post(msgs, extras = false) } // riprova senza opzioni avanzate (es. modello che non le accetta)
    }

    private class BadRequest(msg: String) : Exception(msg)

    private fun post(msgs: JSONArray, extras: Boolean): JSONObject {
        val body = JSONObject()
            .put("model", settings.model)
            .put("max_tokens", 1024)
            .put("system", system())
            .put("tools", host.tools())
            .put("messages", msgs)
        if (extras) {
            body.put("output_config", JSONObject().put("effort", "low")) // risposte rapide
            body.put("fallbacks", "default")                              // alternativa se il modello rifiuta
        }
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", settings.apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        if (extras) conn.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code in 200..299) return JSONObject(text)
        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
        when (code) {
            400 -> if (extras) throw BadRequest(msg) else error("Richiesta non valida: $msg")
            401 -> error("Chiave API non valida.")
            403 -> error("Accesso negato: $msg")
            429 -> error("Troppe richieste o credito esaurito, riprova tra poco.")
            else -> error("Errore $code: $msg")
        }
    }
}
