package it.vault.custode.autofill

import it.vault.custode.kdbx.KdbxEntry

/** Abbina le voci del database al sito web (dominio) o all'app (nome del pacchetto) che chiede le credenziali. */
object Matcher {
    fun hostOf(s: String): String? {
        var t = s.trim().lowercase()
        if (t.isEmpty()) return null
        t = t.substringAfter("://", t)
        t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        t = t.removePrefix("www.")
        return t.takeIf { it.contains('.') }
    }

    /** Stesso host, oppure uno è sottodominio dell'altro (con il punto come confine: "evil-esempio.it" NON corrisponde a "esempio.it"). */
    fun domainMatches(entryUrl: String, webDomain: String): Boolean {
        val a = hostOf(entryUrl) ?: return false
        val b = hostOf(webDomain) ?: return false
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }

    fun appMatches(url: String, androidAppField: String, pkg: String): Boolean =
        androidAppField.trim() == pkg || url.trim().equals("androidapp://$pkg", ignoreCase = true)

    fun matches(e: KdbxEntry, pkg: String, web: String?): Boolean =
        if (!web.isNullOrBlank()) domainMatches(e.url, web) else appMatches(e.url, e.field("AndroidApp"), pkg)
}
