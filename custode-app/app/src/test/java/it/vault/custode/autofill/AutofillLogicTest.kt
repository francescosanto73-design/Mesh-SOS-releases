package it.vault.custode.autofill

import org.junit.Assert.*
import org.junit.Test

class AutofillLogicTest {
    private val TEXT = 0x1; private val PASSWORD = 0x81; private val WEB_PASSWORD = 0xE1; private val EMAIL = 0x21

    @Test fun nativeLoginForm() {
        val f = listOf(FieldInfo(hints = listOf("username")), FieldInfo(hints = listOf("password")))
        val form = FormDetector.detect(f)!!
        assertEquals(0, form.username); assertEquals(listOf(1), form.passwords)
    }
    @Test fun inputTypeOnlyPasswordAndPrecedingFieldIsUser() {
        val f = listOf(FieldInfo(inputType = TEXT), FieldInfo(inputType = PASSWORD))
        val form = FormDetector.detect(f)!!
        assertEquals(0, form.username); assertEquals(listOf(1), form.passwords)
        assertTrue(FormDetector.isPassword(FieldInfo(inputType = WEB_PASSWORD)))
        assertTrue(FormDetector.isPassword(FieldInfo(inputType = 0x12))) // numero + password (PIN)
        assertFalse(FormDetector.isPassword(FieldInfo(inputType = EMAIL)))
    }
    @Test fun webFormByHtmlAttributes() {
        val f = listOf(
            FieldInfo(htmlType = "search", name = "q"),                       // barra di ricerca: ignorata
            FieldInfo(htmlType = "email", name = "email address"),
            FieldInfo(htmlType = "password", hints = listOf("current-password")),
        )
        val form = FormDetector.detect(f)!!
        assertEquals(1, form.username); assertEquals(listOf(2), form.passwords)
    }
    @Test fun usernameOnlyStep() {
        val form = FormDetector.detect(listOf(FieldInfo(htmlType = "text", name = "login"), FieldInfo(htmlType = "search")))!!
        assertEquals(0, form.username); assertTrue(form.passwords.isEmpty())
    }
    @Test fun signupFillsBothPasswordFieldsButChangePasswordOnlyCurrent() {
        val signup = listOf(FieldInfo(hints = listOf("username")), FieldInfo(hints = listOf("new-password")), FieldInfo(hints = listOf("new-password")))
        assertEquals(listOf(1, 2), FormDetector.detect(signup)!!.passwords)
        val change = listOf(FieldInfo(inputType = PASSWORD), FieldInfo(inputType = PASSWORD), FieldInfo(inputType = PASSWORD))
        assertEquals(listOf(0), FormDetector.detect(change)!!.passwords)
    }
    @Test fun notALoginForm() {
        assertNull(FormDetector.detect(listOf(FieldInfo(htmlType = "search"), FieldInfo(name = "nome"), FieldInfo(name = "citta"))))
        assertNull(FormDetector.detect(emptyList()))
        // campi invisibili non contano
        assertNull(FormDetector.detect(listOf(FieldInfo(inputType = PASSWORD, visible = false))))
    }

    @Test fun domainMatching() {
        assertTrue(Matcher.domainMatches("https://www.esempio.it/login", "esempio.it"))
        assertTrue(Matcher.domainMatches("esempio.it", "accedi.esempio.it"))
        assertTrue(Matcher.domainMatches("https://accedi.esempio.it", "esempio.it"))
        assertFalse(Matcher.domainMatches("https://esempio.it", "evil-esempio.it"))     // attacco con dominio simile
        assertFalse(Matcher.domainMatches("https://esempio.it", "esempio.it.evil.com"))
        assertFalse(Matcher.domainMatches("https://esempio.it", "altro.it"))
        assertFalse(Matcher.domainMatches("", "esempio.it"))
        assertFalse(Matcher.domainMatches("https://esempio.it", "com"))
        assertTrue(Matcher.domainMatches("https://utente:pw@esempio.it:8443/x?y=1#z", "esempio.it"))
    }
    @Test fun appMatching() {
        assertTrue(Matcher.appMatches("", "com.banca.app", "com.banca.app"))
        assertTrue(Matcher.appMatches("androidapp://com.banca.app", "", "com.banca.app"))
        assertFalse(Matcher.appMatches("", "com.banca.app2", "com.banca.app"))
        assertFalse(Matcher.appMatches("https://banca.it", "", "com.banca.app"))
    }
}
