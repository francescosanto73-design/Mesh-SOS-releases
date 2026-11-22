package it.tvguida

import android.content.Context

/**
 * Numero di canale sul box Sky per ogni id EPG. L'utente può modificarlo dall'app;
 * i valori predefiniti sono solo un punto di partenza e vanno verificati sul proprio box.
 */
class Numbers(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("numbers", Context.MODE_PRIVATE)

    fun get(id: String): Int? =
        if (prefs.contains(id)) prefs.getInt(id, 0).takeIf { it > 0 } else DEFAULTS[id]

    fun set(id: String, number: Int?) {
        prefs.edit().apply { if (number == null) remove(id) else putInt(id, number) }.apply()
    }

    companion object {
        val DEFAULTS = mapOf(
            "Sky.Uno.it" to 108,
            "Sky.Atlantic.it" to 110,
            "Sky.Serie.it" to 112,
            "Sky.Investigation.it" to 114,
            "Sky.Crime.it" to 119,
            "Sky.Arte.it" to 120,
            "Sky.Documentaries.it" to 122,
            "Sky.Nature.it" to 123,
            "Sky.Cinema.Uno.it" to 301,
            "Sky.Cinema.Due.it" to 302,
            "Sky.Sport.Uno.it" to 201,
            "Sky.Sport.Calcio.it" to 202,
        )
    }
}
