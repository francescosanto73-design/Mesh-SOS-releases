package it.vault.custode.kdbx

import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Parametri di un codice TOTP (RFC 6238). */
class TotpSpec(val secret: ByteArray, val period: Int = 30, val digits: Int = 6, val algorithm: String = "SHA1")

object Totp {
    /** Legge il TOTP di una voce: campo `otp` (otpauth:// o key=...&size=...&step=..., come KeePassXC) o campi `TimeOtp-*` (KeePass 2). */
    fun of(entry: KdbxEntry): TotpSpec? {
        entry.field("otp").takeIf { it.isNotBlank() }?.let { parse(it)?.let { s -> return s } }
        val b32 = entry.field("TimeOtp-Secret-Base32")
        if (b32.isNotBlank()) {
            val secret = base32(b32) ?: return null
            val algo = when (entry.field("TimeOtp-Algorithm").uppercase()) { "HMAC-SHA-256" -> "SHA256"; "HMAC-SHA-512" -> "SHA512"; else -> "SHA1" }
            return TotpSpec(secret, entry.field("TimeOtp-Period").toIntOrNull() ?: 30, entry.field("TimeOtp-Length").toIntOrNull() ?: 6, algo)
        }
        return null
    }

    fun parse(s: String): TotpSpec? {
        val t = s.trim()
        if (t.startsWith("otpauth://", ignoreCase = true)) {
            if (!t.substring(10).startsWith("totp", ignoreCase = true)) return null
            val q = t.substringAfter('?', "")
            val p = q.split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            if (p["encoder"].equals("steam", true)) return null // Steam Guard non supportato
            return spec(base32(p["secret"] ?: return null) ?: return null, p["period"], p["digits"], p["algorithm"])
        }
        if (t.contains("key=")) { // formato storico di KeePassXC: key=...&size=...&step=...
            val p = t.split('&', ';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
            return spec(base32(p["key"] ?: return null) ?: return null, p["step"], p["size"], null)
        }
        // Segreti "nudi" e il formato seed;periodo;cifre: KeePassXC li interpreta in modo diverso dallo standard,
        // quindi non li leggiamo (meglio nessun codice che un codice sbagliato).
        return null
    }

    private fun spec(secret: ByteArray, period: String?, digits: String?, algo: String?): TotpSpec? {
        if (secret.isEmpty()) return null
        val a = when (algo?.uppercase()) { null, "", "SHA1" -> "SHA1"; "SHA256" -> "SHA256"; "SHA512" -> "SHA512"; else -> return null }
        val per = period?.toIntOrNull() ?: 30; val dig = digits?.toIntOrNull() ?: 6
        if (per !in 1..3600 || dig !in 4..10) return null
        return TotpSpec(secret, per, dig, a)
    }

    fun code(s: TotpSpec, epochSeconds: Long = System.currentTimeMillis() / 1000): String {
        val counter = epochSeconds / s.period
        val msg = ByteArray(8) { i -> (counter shr (56 - 8 * i)).toByte() }
        val mac = Mac.getInstance("Hmac" + s.algorithm).apply { init(SecretKeySpec(s.secret, "RAW")) }
        val h = mac.doFinal(msg)
        val o = h.last().toInt() and 0xF
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var m = 1L; repeat(s.digits) { m *= 10 }
        return (bin.toLong() % m).toString().padStart(s.digits, '0')
    }

    fun secondsLeft(s: TotpSpec, epochSeconds: Long = System.currentTimeMillis() / 1000): Int = s.period - (epochSeconds % s.period).toInt()

    /** URI otpauth:// da salvare nel campo `otp` (compatibile con KeePassXC) a partire da un segreto base32. */
    fun uri(base32Secret: String, issuer: String, account: String): String? {
        val clean = base32Secret.filter { !it.isWhitespace() && it != '-' }.uppercase()
        if (base32(clean) == null) return null
        fun e(x: String) = URLEncoder.encode(x, "UTF-8").replace("+", "%20")
        return "otpauth://totp/${e(issuer)}:${e(account)}?secret=$clean&period=30&digits=6&issuer=${e(issuer)}"
    }

    fun base32(input: String): ByteArray? {
        val s = input.filter { !it.isWhitespace() && it != '=' && it != '-' }.uppercase()
        if (s.isEmpty()) return null
        var buffer = 0; var bits = 0; val out = java.io.ByteArrayOutputStream()
        for (c in s) {
            val v = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v; bits += 5
            if (bits >= 8) { bits -= 8; out.write((buffer shr bits) and 0xFF) }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
