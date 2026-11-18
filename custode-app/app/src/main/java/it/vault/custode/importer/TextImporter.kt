package it.vault.custode.importer

import it.vault.custode.autofill.Matcher
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Una riga del file interpretata. [confident] = false significa "da controllare": l'interpretazione è un'ipotesi. */
class Parsed(
    var title: String, var user: String, var password: String, var url: String, var notes: String,
    val confident: Boolean, val raw: String,
)

/**
 * Importa password da un file di testo scritto a mano, con una riga per voce e nessuna regola fissa.
 * Non può indovinare sempre: per questo ogni riga porta un giudizio di sicurezza e l'app mostra un'anteprima.
 * Una riga non interpretabile finisce intera nelle note, così nulla va perso.
 */
object TextImporter {
    private val URL_RX = Regex("""(?i)(?:https?://|www\.)[^\s,;|]+|[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|it|org|net|eu|io|info|co|uk|de|fr|es|me|app|dev|biz|us|ch|at|nl|be)(?:/[^\s,;|]*)?""")
    private val EMAIL_RX = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val USER_LABEL = Regex("""(?i)\b(?:user(?:name)?|utente|nome utente|login|e-?mail|mail|account|id)\s*[:=]\s*(\S+)""")
    private val PASS_LABEL = Regex("""(?i)\b(?:pass(?:word)?|pwd|psw|pw|parola d'ordine)\s*[:=]\s*(\S+)""")
    private val SEPARATORS = Regex("""\s*(?:\t|;|\||\s[-–—]+>?\s|\s/\s|=>|->)\s*""")
    private val DECORATION = Regex("""^[\s\-=_*#~.]*$""")

    /** Legge i byte come UTF-8; se non sono UTF-8 validi (file di Windows) usa Windows-1252. */
    fun decode(bytes: ByteArray): String {
        val b = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) { String(b, charset("windows-1252")) }
    }

    fun parse(text: String): List<Parsed> =
        text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !DECORATION.matches(it) && !it.startsWith("#") && !it.startsWith("//") }
            .map { parseLine(it) }.toList()

    fun parseLine(raw: String): Parsed {
        val line = raw.trim()

        // 1) Etichette esplicite: "user: mario  password: abc"
        val pm = PASS_LABEL.find(line)
        if (pm != null) {
            val um = USER_LABEL.find(line)
            var rest = line
            listOfNotNull(pm.range, um?.range).sortedByDescending { it.first }.forEach { rest = rest.removeRange(it) }
            val url = URL_RX.find(rest)?.value ?: ""
            if (url.isNotEmpty()) rest = rest.replace(url, " ")
            var user = um?.groupValues?.get(1) ?: ""
            if (user.isEmpty()) EMAIL_RX.find(rest)?.value?.let { user = it; rest = rest.replace(it, " ") }
            val title = cleanTitle(rest).ifBlank { hostOrDomain(url, user) }
            return Parsed(title, user, pm.groupValues[1], url, "", confident = true, raw = raw)
        }

        // 2) Parti separate da ; | tab " - " ecc., altrimenti da spazi
        val parts = line.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        val bySeparator = parts.size in 2..6
        val items = if (bySeparator) parts else line.split(Regex("\\s+"))

        val url = items.firstOrNull { URL_RX.matches(it) }
        val email = items.firstOrNull { it != url && EMAIL_RX.matches(it) }
        val others = items.filter { it != url && it != email }

        fun unsure(): Parsed = Parsed(firstWords(line), "", "", url.orEmpty(), raw, confident = false, raw = raw)
        if (items.size < 2 || others.isEmpty() || others.size > 3) return unsure()

        val hasLink = url != null || email != null
        return when {
            hasLink && others.size == 1 -> Parsed(hostOrDomain(url.orEmpty(), email.orEmpty()), email.orEmpty(), others[0], url.orEmpty(), "", true, raw)
            hasLink && others.size == 2 && url != null && email == null ->
                Parsed(hostOrDomain(url, ""), others[0], others[1], url, "", true, raw)               // sito;utente;password
            hasLink && others.size == 2 -> Parsed(others[0], email.orEmpty(), others[1], url.orEmpty(), "", true, raw)
            hasLink -> Parsed(others[0], email.orEmpty().ifEmpty { others[1] }, others.last(), url.orEmpty(), "", false, raw)
            others.size == 2 -> Parsed(others[0], "", others[1], "", "", true, raw)                    // titolo + password
            else -> Parsed(others[0], others[1], others[2], "", "", false, raw)                         // titolo, utente, password (ipotesi)
        }
    }

    private fun cleanTitle(s: String) = s.replace(Regex("""[\s:,;|=\-–—]+"""), " ").trim()
    private fun firstWords(s: String) = s.split(Regex("\\s+")).take(2).joinToString(" ").take(40)
    private fun hostOrDomain(url: String, email: String): String =
        Matcher.hostOf(url) ?: email.substringAfter('@', "").substringBeforeLast('.').ifBlank { firstWords(url.ifBlank { email }) }
}
