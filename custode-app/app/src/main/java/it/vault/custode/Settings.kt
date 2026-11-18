package it.vault.custode

import android.content.Context

/** Preferenze semplici. Niente di sensibile: solo tempi. */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Secondi di attesa prima del blocco quando l'app va in background (0 = subito). */
    var lockDelaySec: Int
        get() = sp.getInt("lock", 0)
        set(v) { sp.edit().putInt("lock", v).apply() }

    /** Secondi dopo cui la password copiata viene cancellata dagli appunti (0 = mai). */
    var clipboardSec: Int
        get() = sp.getInt("clip", 30)
        set(v) { sp.edit().putInt("clip", v).apply() }

    companion object {
        val LOCK_OPTIONS = listOf(0 to "Subito", 30 to "Dopo 30 secondi", 60 to "Dopo 1 minuto", 300 to "Dopo 5 minuti", 900 to "Dopo 15 minuti")
        val CLIP_OPTIONS = listOf(15 to "15 secondi", 30 to "30 secondi", 60 to "1 minuto", 0 to "Mai")
    }
}
