package it.vault.custode.kdbx

import java.io.File
import kotlin.system.exitProcess

/** Strumento da riga di comando usato solo per i test di interoperabilità. */
private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
private fun unesc(s: String) = buildString {
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 1 < s.length) { append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2 } else { append(s[i]); i++ }
    }
}

fun main(args: Array<String>) {
    try {
        when (args.getOrNull(0)) {
            // dump <file> <password>  → una riga TSV per voce: gruppo, titolo, utente, password, url, note, campi extra
            "dump" -> {
                val db = KdbxDatabase.open(File(args[1]).readBytes(), args[2].toCharArray())
                fun walk(g: KdbxGroup, path: String) {
                    g.entries.forEach { e ->
                        println(listOf(path, e.title, e.username, e.password, e.url, e.notes,
                            e.customFields.entries.joinToString(";") { "${it.key}=${it.value}" }).joinToString("\t") { esc(it) })
                    }
                    g.groups.forEach { walk(it, "$path/${it.name}") }
                }
                walk(db.root, db.root.name)
            }
            // create <file> <password>  + stdin TSV (gruppo<TAB>titolo<TAB>utente<TAB>password<TAB>url<TAB>note)
            "create" -> {
                val db = KdbxDatabase.create("Custode", args[2].toCharArray())
                generateSequence(::readLine).filter { it.isNotBlank() }.forEach { line ->
                    val f = line.split("\t").map { unesc(it) }
                    var g = db.root
                    if (f[0].isNotEmpty()) f[0].split("/").forEach { n -> g = g.groups.firstOrNull { it.name == n } ?: g.addGroup(n) }
                    g.addEntry(f[1], f[2], f[3], f[4], f[5])
                }
                File(args[1]).writeBytes(db.toBytes())
            }
            // roundtrip <in> <out> <password>: apre e risalva senza modifiche
            "roundtrip" -> {
                val db = KdbxDatabase.open(File(args[1]).readBytes(), args[3].toCharArray())
                File(args[2]).writeBytes(db.toBytes())
            }
            else -> { System.err.println("uso: dump|create|roundtrip"); exitProcess(2) }
        }
    } catch (e: WrongCredentialsException) { System.err.println("WRONG_PASSWORD"); exitProcess(3) }
    catch (e: UnsupportedFormatException) { System.err.println("UNSUPPORTED: ${e.message}"); exitProcess(4) }
}
