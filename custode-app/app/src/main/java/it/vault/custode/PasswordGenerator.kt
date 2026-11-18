package it.vault.custode

import java.security.SecureRandom
import kotlin.math.ln

object PasswordGenerator {
    private val random = SecureRandom()
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"   // senza l ed o
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"     // senza I e O
    private const val DIGITS = "23456789"                    // senza 0 e 1
    private const val SYMBOLS = "!@#$%^&*-_=+?"

    class Options(val length: Int = 20, val lower: Boolean = true, val upper: Boolean = true,
                  val digits: Boolean = true, val symbols: Boolean = true)

    private fun pools(o: Options) = listOfNotNull(
        LOWER.takeIf { o.lower }, UPPER.takeIf { o.upper }, DIGITS.takeIf { o.digits }, SYMBOLS.takeIf { o.symbols },
    ).ifEmpty { listOf(LOWER) }

    /** Garantisce almeno un carattere per ogni gruppo scelto, poi mescola. */
    fun generate(o: Options = Options()): String {
        val pools = pools(o)
        val all = pools.joinToString("")
        val chars = pools.map { it[random.nextInt(it.length)] }.toMutableList()
        while (chars.size < o.length) chars += all[random.nextInt(all.length)]
        chars.shuffle(random)
        return chars.take(maxOf(o.length, pools.size)).joinToString("")
    }

    /** Entropia approssimativa in bit. */
    fun entropyBits(o: Options): Int = (o.length * ln(pools(o).joinToString("").length.toDouble()) / ln(2.0)).toInt()
}
