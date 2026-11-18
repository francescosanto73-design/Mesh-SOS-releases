"""Interoperabilità con KeePassXC reale (keepassxc-cli): lettura, scrittura e roundtrip."""
import os, subprocess, sys, tempfile, shutil, re
ENV = {**os.environ, "QT_QPA_PLATFORM": "offscreen", "JAVA_TOOL_OPTIONS": ""}
CLI = os.path.join(os.path.dirname(__file__), "..", "build", "install", "custode-core", "bin", "custode-core")
tmp = tempfile.mkdtemp(); fails = []
PW = "frase-lunga-€-ü"
def run(cmd, stdin=None): return subprocess.run(cmd, input=stdin, capture_output=True, text=True, env=ENV)
def xc(*a, pw=PW, extra=""): return run(["keepassxc-cli", *a], stdin=pw + "\n" + extra)
def check(n, c, d=""):
    print(("PASS " if c else "FAIL ") + n + ("" if c else f" -> {d}")); (c or fails.append(n))

# 1) KeePassXC crea -> Custode legge
f1 = os.path.join(tmp, "xc.kdbx")
r = run(["keepassxc-cli", "db-create", "-p", "--decryption-time", "200", f1], stdin=PW + "\n" + PW + "\n")
check("KeePassXC crea il database", r.returncode == 0 and os.path.exists(f1), r.stderr)
info = xc("db-info", f1).stdout
print("   info:", " | ".join(l.strip() for l in info.splitlines() if re.search("Cipher|KDF|Format", l)))
for title, user, pw_, url in [("GitHub", "mario", "Pa$$w0rd!\"'<>&", "https://github.com"), ("日本語", "ユーザー", "пароль🔑", "")]:
    extra = f"{pw_}\n"
    r = run(["keepassxc-cli", "add", "-u", user, "--url", url, "-p", f1, title], stdin=PW + "\n" + pw_ + "\n")
    check(f"KeePassXC aggiunge '{title}'", r.returncode == 0, r.stderr)
blob = os.path.join(tmp, "allegato.bin"); open(blob, "wb").write(bytes(range(256)) * 40)
r = xc("attachment-import", f1, "GitHub", "chiave.bin", blob, extra=""); check("KeePassXC aggiunge un allegato", r.returncode == 0, r.stderr)
r = run([CLI, "dump", f1, PW])
check("Custode apre il file di KeePassXC", r.returncode == 0, r.stderr)
rows = [l.split("\t") for l in r.stdout.splitlines()]
byt = {x[1]: x for x in rows}
check("dati e simboli corretti (GitHub)", byt.get("GitHub", [None]*7)[2:5] == ["mario", "Pa$$w0rd!\"'<>&", "https://github.com"], str(byt.get("GitHub")))
check("unicode corretto (日本語)", byt.get("日本語", [None]*7)[2:4] == ["ユーザー", "пароль🔑"], str(byt.get("日本語")))
check("password errata rifiutata", run([CLI, "dump", f1, "no"]).returncode == 3)

# 2) Custode scrive -> KeePassXC legge
f2 = os.path.join(tmp, "kt.kdbx")
tsv = "Lavoro\tServer\troot\tS3gr3to!#\thttps://srv\tnota\\ndue righe\n\tBanca\tmrossi\t" + "x" * 200 + "\t\t\n"
r = run([CLI, "create", f2, PW], stdin=tsv)
check("Custode crea il database", r.returncode == 0, r.stderr)
r = xc("ls", "-R", f2); check("KeePassXC elenca il database di Custode", r.returncode == 0 and "Server" in r.stdout and "Banca" in r.stdout, r.stdout + r.stderr)
r = xc("show", "-s", f2, "Lavoro/Server")
check("KeePassXC legge utente/password/url/note", all(x in r.stdout for x in ["root", "S3gr3to!#", "https://srv", "due righe"]), r.stdout + r.stderr)
r = xc("show", "-s", f2, "Banca"); check("password lunga (200) corretta", "x" * 200 in r.stdout, r.stdout[:200])
r = xc("ls", f2, pw="sbagliata"); check("KeePassXC rifiuta password errata", r.returncode != 0)
r = xc("db-info", f2); print("   info Custode:", " | ".join(l.strip() for l in r.stdout.splitlines() if re.search("Cipher|KDF|Format", l)))

# 3) roundtrip di un file KeePassXC con Custode, poi KeePassXC lo rilegge
f3 = os.path.join(tmp, "xc_rt.kdbx")
r = run([CLI, "roundtrip", f1, f3, PW]); check("roundtrip Custode su file KeePassXC", r.returncode == 0, r.stderr)
r = xc("show", "-s", f3, "GitHub"); check("KeePassXC rilegge il file risalvato da Custode", "Pa$$w0rd!\"'<>&" in r.stdout and "mario" in r.stdout, r.stdout + r.stderr)

check("il file risalvato è KDBX 4 con Argon2", "Argon2" in xc("db-info", f3).stdout, xc("db-info", f3).stdout[:300])
out = os.path.join(tmp, "esportato.bin")
r = xc("attachment-export", f3, "GitHub", "chiave.bin", out)
check("allegato conservato byte per byte dopo la conversione v3→v4", os.path.exists(out) and open(out, "rb").read() == open(blob, "rb").read(), r.stderr)

# 4) file chiave (XML di KeePassXC, file generico, 32 byte grezzi) con e senza password
def kcheck(label, kfile_path, make_in_xc):
    if make_in_xc:
        r = run(["keepassxc-cli", "db-create", "-p", "-k", kfile_path, "--decryption-time", "100", os.path.join(tmp, label + ".kdbx")], stdin=PW + "\n" + PW + "\n")
    else:
        r = run(["keepassxc-cli", "db-create", "-p", "-k", kfile_path, "--decryption-time", "100", os.path.join(tmp, label + ".kdbx")], stdin=PW + "\n" + PW + "\n")
    f = os.path.join(tmp, label + ".kdbx")
    check(f"[{label}] KeePassXC crea db con password + file chiave", r.returncode == 0 and os.path.exists(f), r.stderr[:200])
    r = run(["keepassxc-cli", "add", "-k", kfile_path, "-u", "u1", "-p", f, "Voce"], stdin=PW + "\nsegreto-" + label + "\n")
    check(f"[{label}] KeePassXC aggiunge una voce", r.returncode == 0, r.stderr[:200])
    r = subprocess.run([CLI, "dump", f, PW], capture_output=True, text=True, env={**ENV, "KFILE": kfile_path})
    check(f"[{label}] Custode apre con password + file chiave", r.returncode == 0 and "segreto-" + label in r.stdout, r.stderr[:200] + r.stdout[:200])
    r = subprocess.run([CLI, "dump", f, PW], capture_output=True, text=True, env=ENV)
    check(f"[{label}] senza il file chiave l'accesso è rifiutato", r.returncode == 3, str(r.returncode))
    # Custode crea con file chiave -> KeePassXC apre
    g = os.path.join(tmp, label + "_kt.kdbx")
    r = subprocess.run([CLI, "create", g, PW], input="\tTest\tu\tp\t\t\n", capture_output=True, text=True, env={**ENV, "KFILE": kfile_path})
    r2 = run(["keepassxc-cli", "ls", "-k", kfile_path, g], stdin=PW + "\n")
    check(f"[{label}] KeePassXC apre il db creato da Custode con file chiave", r.returncode == 0 and r2.returncode == 0 and "Test" in r2.stdout, r.stderr[:200] + r2.stderr[:200])

xml_key = os.path.join(tmp, "k.keyx")
r = run(["keepassxc-cli", "db-create", "-p", "-k", xml_key, "--decryption-time", "100", os.path.join(tmp, "gen.kdbx")], stdin=PW + "\n" + PW + "\n")
print("   generazione keyfile XML:", "ok" if os.path.exists(xml_key) else "NON creato: " + r.stderr[:150])
if os.path.exists(xml_key):
    print("   ", open(xml_key).read()[:120].replace("\n", " "))
    kcheck("xml", xml_key, True)
gen = os.path.join(tmp, "generico.bin"); open(gen, "wb").write(os.urandom(100)); kcheck("generico", gen, True)
raw32 = os.path.join(tmp, "raw32.bin"); open(raw32, "wb").write(os.urandom(32)); kcheck("32byte", raw32, True)

shutil.rmtree(tmp)
print("\nRISULTATO:", "TUTTI I TEST PASSATI" if not fails else f"{len(fails)} FALLITI: {fails}")
sys.exit(1 if fails else 0)
