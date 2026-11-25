package it.tvguida

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Comanda un Broadlink RM (mini/pro/RM4) sulla rete Wi-Fi locale con il suo protocollo UDP (porta 80).
 * I tasti 0-9 del telecomando Sky vanno "imparati" una volta: si punta il telecomando verso il Broadlink
 * e si preme il tasto; il codice IR viene salvato e poi rispedito per digitare il numero del canale.
 */
class BroadlinkRemote(ctx: Context, private val onState: (String) -> Unit) {
    private val prefs = ctx.getSharedPreferences("broadlink", Context.MODE_PRIVATE)

    var host: String? = prefs.getString("host", null); private set
    private var devtype = prefs.getInt("devtype", 0)
    private var mac: ByteArray? = prefs.getString("mac", null)?.let(::unhex)

    private var key = INIT_KEY
    private var id = ByteArray(4)
    private var count = (0..0xffff).random()
    private var authed = false

    val configured get() = host != null && mac != null
    fun hasCode(d: Int) = prefs.contains("k$d")
    val learnedCount get() = (0..9).count { hasCode(it) }

    // ---------- configurazione ----------

    /** Cerca un Broadlink in rete (broadcast) oppure all'indirizzo indicato. */
    suspend fun find(ip: String?): Boolean = withContext(Dispatchers.IO) {
        val target = ip?.takeIf { it.isNotBlank() } ?: "255.255.255.255"
        onState("Ricerca Broadlink…")
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 4000
                val hello = helloPacket(s.localPort)
                s.send(DatagramPacket(hello, hello.size, InetAddress.getByName(target), 80))
                val buf = ByteArray(1024)
                val r = DatagramPacket(buf, buf.size)
                s.receive(r)
                if (r.length < 0x40) { onState("Risposta non valida"); return@withContext false }
                host = r.address.hostAddress
                devtype = (buf[0x34].toInt() and 0xff) or ((buf[0x35].toInt() and 0xff) shl 8)
                mac = buf.copyOfRange(0x3a, 0x40)
                authed = false; key = INIT_KEY; id = ByteArray(4)
                prefs.edit().putString("host", host).putInt("devtype", devtype).putString("mac", hex(mac!!)).apply()
                onState("Broadlink trovato: $host (tipo 0x${devtype.toString(16)})")
                true
            }
        } catch (e: Exception) {
            onState("Broadlink non trovato (${e.javaClass.simpleName}). Controlla di essere sul suo stesso Wi-Fi o inserisci l'IP.")
            false
        }
    }

    /** Entra in modalità apprendimento e aspetta fino a 30 s che si prema un tasto sul telecomando. */
    suspend fun learn(digit: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureAuth()
            onState("Punta il telecomando Sky verso il Broadlink e premi il tasto $digit…")
            cmd(0x03)
            var code: ByteArray? = null
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end && code == null) {
                delay(1000)
                code = try { cmd(0x04).takeIf { it.size > 4 } } catch (_: Exception) { null }
            }
            if (code == null) { onState("Nessun segnale ricevuto per il tasto $digit"); return@withContext false }
            prefs.edit().putString("k$digit", hex(trim(code))).apply()
            onState("Tasto $digit salvato (${learnedCount}/10)")
            true
        } catch (e: Exception) {
            authed = false
            onState("Errore: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    // ---------- uso ----------

    /** Digita il numero del canale inviando i codici IR delle cifre. */
    suspend fun goToChannel(number: Int): Boolean {
        if (!configured) { onState("Configura il Broadlink dal pulsante «Telecomando»"); return false }
        val digits = number.toString().map { it - '0' }
        val missing = digits.firstOrNull { !hasCode(it) }
        if (missing != null) { onState("Manca il tasto $missing: impara i tasti da «Telecomando»"); return false }
        return try {
            for (d in digits) { sendDigit(d); delay(450) }
            onState("Inviato canale $number")
            true
        } catch (e: Exception) {
            onState("Errore: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun sendDigit(d: Int) = withContext(Dispatchers.IO) {
        val code = unhex(prefs.getString("k$d", null)!!)
        try { ensureAuth(); cmd(0x02, code) }
        catch (e: Exception) { authed = false; ensureAuth(); cmd(0x02, code) } // un secondo tentativo con nuova autenticazione
    }

    // ---------- protocollo ----------

    private fun ensureAuth() {
        if (authed) return
        key = INIT_KEY; id = ByteArray(4)
        val p = ByteArray(0x50)
        for (i in 0x04..0x12) p[i] = 0x31
        p[0x1e] = 1; p[0x2d] = 1
        "Test 1".toByteArray().copyInto(p, 0x30)
        val resp = exchange(0x65, p)
        id = resp.copyOfRange(0, 4)
        key = resp.copyOfRange(4, 0x14)
        authed = true
    }

    private val rm4 get() = devtype in RM4_TYPES

    /** Comando RM: 0x02 invia codice, 0x03 entra in apprendimento, 0x04 legge il codice appreso. */
    private fun cmd(command: Int, data: ByteArray = ByteArray(0)): ByteArray {
        if (rm4) {
            val len = data.size + 4
            val p = ByteArray(8 + data.size)
            p[0] = (len and 0xff).toByte(); p[1] = (len shr 8).toByte()
            p[4] = command.toByte()
            data.copyInto(p, 8)
            val r = exchange(0x6a, p)
            val l = (r[0].toInt() and 0xff) or ((r[1].toInt() and 0xff) shl 8)
            return r.copyOfRange(6, minOf(l + 2, r.size))
        }
        val p = ByteArray(4 + data.size)
        p[0] = command.toByte()
        data.copyInto(p, 4)
        return exchange(0x6a, p).let { it.copyOfRange(4, it.size) }
    }

    /** Invia un pacchetto cifrato e restituisce il payload decifrato della risposta. */
    private fun exchange(command: Int, payload: ByteArray): ByteArray {
        val h = host ?: error("Broadlink non configurato")
        val m = mac ?: error("Broadlink non configurato")
        count = (count + 1) and 0xffff
        val head = ByteArray(0x38)
        byteArrayOf(0x5a, 0xa5.toByte(), 0xaa.toByte(), 0x55, 0x5a, 0xa5.toByte(), 0xaa.toByte(), 0x55).copyInto(head)
        head[0x24] = (devtype and 0xff).toByte(); head[0x25] = (devtype shr 8).toByte()
        head[0x26] = (command and 0xff).toByte(); head[0x27] = (command shr 8).toByte()
        head[0x28] = (count and 0xff).toByte(); head[0x29] = (count shr 8).toByte()
        m.copyInto(head, 0x2a)
        id.copyInto(head, 0x30)
        val pc = checksum(payload)
        head[0x34] = (pc and 0xff).toByte(); head[0x35] = (pc shr 8).toByte()
        val padded = payload.copyOf(((payload.size + 15) / 16) * 16)
        val packet = head + aes(Cipher.ENCRYPT_MODE, padded)
        val c = checksum(packet)
        packet[0x20] = (c and 0xff).toByte(); packet[0x21] = (c shr 8).toByte()

        DatagramSocket().use { s ->
            s.soTimeout = 5000
            s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(h), 80))
            val buf = ByteArray(2048)
            val r = DatagramPacket(buf, buf.size)
            s.receive(r)
            if (r.length < 0x38) error("Risposta troppo corta")
            val err = (buf[0x22].toInt() and 0xff) or ((buf[0x23].toInt() and 0xff) shl 8)
            if (err != 0) error("Errore dispositivo 0x${err.toString(16)}")
            val enc = buf.copyOfRange(0x38, 0x38 + ((r.length - 0x38) / 16) * 16)
            return aes(Cipher.DECRYPT_MODE, enc)
        }
    }

    private fun aes(mode: Int, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
        return c.doFinal(data)
    }

    private fun helloPacket(localPort: Int): ByteArray {
        val p = ByteArray(0x30)
        val cal = Calendar.getInstance()
        val tz = cal.timeZone.getOffset(cal.timeInMillis) / 3_600_000
        if (tz < 0) { p[8] = (-tz - 1).toByte(); p[9] = -1; p[10] = -1; p[11] = -1 } else p[8] = tz.toByte()
        val y = cal.get(Calendar.YEAR)
        p[0x0c] = (y and 0xff).toByte(); p[0x0d] = (y shr 8).toByte()
        p[0x0e] = cal.get(Calendar.MINUTE).toByte(); p[0x0f] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        p[0x10] = (y % 100).toByte()
        p[0x11] = (((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1).toByte()
        p[0x12] = cal.get(Calendar.DAY_OF_MONTH).toByte(); p[0x13] = (cal.get(Calendar.MONTH) + 1).toByte()
        p[0x1c] = (localPort and 0xff).toByte(); p[0x1d] = (localPort shr 8).toByte()
        p[0x26] = 6
        val c = checksum(p)
        p[0x20] = (c and 0xff).toByte(); p[0x21] = (c shr 8).toByte()
        return p
    }

    private fun checksum(b: ByteArray): Int = (0xbeaf + b.sumOf { it.toInt() and 0xff }) and 0xffff

    /** Un codice IR Broadlink ha lunghezza nei byte 2-3: toglie l'eventuale riempimento finale. */
    private fun trim(code: ByteArray): ByteArray {
        if (code.size < 4) return code
        val len = (code[2].toInt() and 0xff) or ((code[3].toInt() and 0xff) shl 8)
        return if (4 + len <= code.size) code.copyOf(4 + len) else code
    }

    companion object {
        private val INIT_KEY = unhex("097628343fe99e23765c1513accf8b02")
        private val IV = unhex("562e17996d093d28ddb3ba695a2e6f58")
        private val RM4_TYPES = setOf(
            0x51da, 0x5f36, 0x6026, 0x6070, 0x610e, 0x610f, 0x61a2, 0x62bc, 0x62be,
            0x6364, 0x648d, 0x649b, 0x6507, 0x6508, 0x6539, 0x653a, 0x653c,
        )
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
