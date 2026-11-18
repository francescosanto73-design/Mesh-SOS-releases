package it.vault.custode.autofill

/** Descrizione di un campo di testo di un'altra app o pagina web (senza dipendenze da Android: testabile). */
class FieldInfo(
    val hints: List<String> = emptyList(),   // autofillHints + attributo html autocomplete, minuscoli
    val inputType: Int = 0,
    val htmlType: String? = null,
    val name: String = "",                   // html name/id/placeholder, minuscolo
    val label: String = "",                  // idEntry/hint/aria-label, minuscolo
    val visible: Boolean = true,
    val value: String? = null,
)

/** Indici (nella lista dei campi) dell'utente e delle password riconosciute. */
class Form(val username: Int?, val passwords: List<Int>)

object FormDetector {
    private val PASS_HINTS = setOf("password", "newpassword", "current-password", "new-password")
    private val USER_HINTS = setOf("username", "emailaddress", "email", "phone", "phonenumber", "tel")
    private val USER_RX = Regex("(user|login|e-?mail|account|identif|utente|nickname|phone|mobile|cellulare|telefono)")
    private val EXCLUDE_RX = Regex("(search|cerca|captcha|coupon|promo|otp|2fa|verification)")

    // Costanti di android.text.InputType (valori fissi, copiati per non dipendere da Android).
    private const val CLASS_MASK = 0xF; private const val VARIATION_MASK = 0xFF0
    private const val CLASS_TEXT = 0x1; private const val CLASS_NUMBER = 0x2
    private const val VAR_PASSWORD = 0x80; private const val VAR_VISIBLE_PASSWORD = 0x90; private const val VAR_WEB_PASSWORD = 0xE0
    private const val VAR_NUMBER_PASSWORD = 0x10

    fun isPassword(f: FieldInfo): Boolean {
        if (f.hints.any { it in PASS_HINTS || it.contains("password") }) return true
        if (f.htmlType == "password") return true
        val cls = f.inputType and CLASS_MASK; val v = f.inputType and VARIATION_MASK
        if (cls == CLASS_TEXT && (v == VAR_PASSWORD || v == VAR_VISIBLE_PASSWORD || v == VAR_WEB_PASSWORD)) return true
        if (cls == CLASS_NUMBER && v == VAR_NUMBER_PASSWORD) return true
        return false
    }

    private fun hasUserHint(f: FieldInfo) = f.hints.any { it in USER_HINTS }
    private fun excluded(f: FieldInfo) = f.htmlType in setOf("search", "hidden") || EXCLUDE_RX.containsMatchIn(f.name + " " + f.label)
    private fun looksLikeUser(f: FieldInfo) = hasUserHint(f) || f.htmlType in setOf("email", "tel") || USER_RX.containsMatchIn(f.name + " " + f.label)

    /** Riconosce un modulo di accesso/registrazione. Restituisce null se non sembra contenere credenziali. */
    fun detect(fields: List<FieldInfo>): Form? {
        val idx = fields.indices.filter { fields[it].visible }
        val pw = idx.filter { isPassword(fields[it]) }
        val candidates = idx.filter { !isPassword(fields[it]) && !excluded(fields[it]) }
        var user = candidates.firstOrNull { hasUserHint(fields[it]) }
        if (user == null && pw.isNotEmpty()) user = candidates.lastOrNull { it < pw.first() }      // il campo subito prima della password
        if (user == null && pw.isEmpty()) user = candidates.firstOrNull { looksLikeUser(fields[it]) } // accesso in due passaggi
        if (user == null && pw.isEmpty()) return null
        // Con tre password (modulo "cambia password") si compila solo la prima, cioè quella attuale.
        return Form(user, pw.take(if (pw.size >= 3) 1 else 2))
    }
}
