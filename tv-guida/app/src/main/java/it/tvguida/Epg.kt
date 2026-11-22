package it.tvguida

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

const val EPG_URL = "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String)

data class Channel(
    val id: String,
    val name: String,
    val logo: String?,
    val programmes: List<Programme>,
) {
    fun now(t: Long) = programmes.firstOrNull { t in it.start until it.stop }
    fun next(t: Long) = programmes.firstOrNull { it.start >= t }
}

object Epg {
    /** Scarica l'XMLTV (gz) nella cache e lo restituisce. */
    fun download(cache: File, url: String = EPG_URL): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.inputStream.use { input -> cache.outputStream().use { input.copyTo(it) } }
        return cache
    }

    /** Legge solo i programmi tra (adesso - 3h) e (adesso + 72h) per contenere la memoria. */
    fun parse(file: File, now: Long = System.currentTimeMillis()): List<Channel> {
        val from = now - 3 * 3600_000L
        val to = now + 72 * 3600_000L
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

        val names = LinkedHashMap<String, String>()
        val logos = HashMap<String, String>()
        val progs = HashMap<String, MutableList<Programme>>()

        open(file).use { stream ->
            val p = Xml.newPullParser()
            p.setInput(stream, null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "channel" -> {
                        val id = p.getAttributeValue(null, "id") ?: ""
                        var depth = 1
                        while (depth > 0) {
                            ev = p.next()
                            if (ev == XmlPullParser.START_TAG) {
                                depth++
                                if (p.name == "display-name" && id !in names) names[id] = p.nextText().trim().also { depth-- }
                                else if (p.name == "icon" && id !in logos) p.getAttributeValue(null, "src")?.let { logos[id] = it }
                            } else if (ev == XmlPullParser.END_TAG) depth--
                        }
                    }
                    "programme" -> {
                        val ch = p.getAttributeValue(null, "channel") ?: ""
                        val s = runCatching { fmt.parse(p.getAttributeValue(null, "start"))!!.time }.getOrNull()
                        val e = runCatching { fmt.parse(p.getAttributeValue(null, "stop"))!!.time }.getOrNull()
                        var title = ""
                        var desc = ""
                        var depth = 1
                        while (depth > 0) {
                            ev = p.next()
                            if (ev == XmlPullParser.START_TAG) {
                                depth++
                                when (p.name) {
                                    "title" -> { title = p.nextText().trim(); depth-- }
                                    "desc" -> { desc = p.nextText().trim(); depth-- }
                                }
                            } else if (ev == XmlPullParser.END_TAG) depth--
                        }
                        if (s != null && e != null && e > from && s < to) {
                            progs.getOrPut(ch) { mutableListOf() }.add(Programme(s, e, title, desc))
                        }
                    }
                }
                ev = p.next()
            }
        }
        return names.mapNotNull { (id, name) ->
            val list = progs[id]?.sortedBy { it.start } ?: return@mapNotNull null
            Channel(id, name, logos[id], list)
        }
    }

    private fun open(file: File): InputStream {
        val raw = file.inputStream().buffered()
        raw.mark(2)
        val b0 = raw.read(); val b1 = raw.read()
        raw.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw) else raw
    }
}
