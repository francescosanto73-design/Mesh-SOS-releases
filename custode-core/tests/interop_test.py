"""Test di interoperabilità KDBX 4: Custode (Kotlin) contro pykeepass (implementazione indipendente)."""
import os, subprocess, sys, tempfile, shutil
from pykeepass import create_database, PyKeePass

CLI = os.path.join(os.path.dirname(__file__), "..", "build", "install", "custode-core", "bin", "custode-core")
tmp = tempfile.mkdtemp()
fails = []

def esc(s): return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
def cli(*args, stdin=None):
    return subprocess.run([CLI, *args], input=stdin, capture_output=True, text=True,
                          env={**os.environ, "JAVA_TOOL_OPTIONS": ""})
def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {detail}"))
    if not cond: fails.append(name)
def dump_rows(out):
    rows = []
    for line in out.splitlines():
        f = line.split("\t")
        rows.append(tuple(x.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\") for x in f))
    return sorted(rows)

PW = "pässwörd-€-日本語"
DATA = [
    ("Email", "mario@example.com", "S3cr3t!#&<>\"'", "https://mail.example.com", "riga1\nriga2\ttab"),
    ("Banca", "mrossi", "p" * 300, "", ""),
    ("日本語タイトル", "ユーザー", "пароль-🔑", "http://x.y", "note ünïcode"),
]

# 1) pykeepass crea -> Custode legge
p1 = os.path.join(tmp, "from_py.kdbx")
kp = create_database(p1, password=PW)
check("pykeepass crea un file KDBX 4", kp.version == (4, 0) or kp.version[0] == 4, str(kp.version))
sub = kp.add_group(kp.root_group, "Lavoro")
for i, (t, u, p, url, n) in enumerate(DATA):
    e = kp.add_entry(sub if i == 1 else kp.root_group, t, u, p, url=url or None, notes=n or None)
kp.find_entries(title="Email", first=True).set_custom_property("PIN", "1234")
kp.save()
r = cli("dump", p1, PW)
check("Custode apre il file di pykeepass", r.returncode == 0, r.stderr)
exp = sorted(
    (kp.root_group.name if i != 1 else kp.root_group.name + "/Lavoro", t, u, p, url, n, "PIN=1234" if t == "Email" else "")
    for i, (t, u, p, url, n) in enumerate(DATA))
got = dump_rows(r.stdout)
check("contenuto identico (utf-8, note multilinea, simboli, campo personalizzato)", got == exp, f"\n got={got}\n exp={exp}")

# 2) password sbagliata / file manomesso
r = cli("dump", p1, "sbagliata")
check("password errata rifiutata (exit 3)", r.returncode == 3, f"{r.returncode} {r.stderr}")
raw = bytearray(open(p1, "rb").read()); raw[-40] ^= 1
open(os.path.join(tmp, "tampered.kdbx"), "wb").write(raw)
r = cli("dump", os.path.join(tmp, "tampered.kdbx"), PW)
check("file manomesso rifiutato", r.returncode in (3, 4), f"{r.returncode} {r.stderr}")
open(os.path.join(tmp, "junk.kdbx"), "wb").write(b"non sono un database" * 10)
r = cli("dump", os.path.join(tmp, "junk.kdbx"), PW)
check("file non valido rifiutato senza crash (exit 4)", r.returncode == 4, f"{r.returncode} {r.stderr[:200]}")

# 3) Custode crea -> pykeepass legge
tsv = "\n".join("\t".join(esc(x) for x in ("" if i != 1 else "Lavoro/Sotto", t, u, p, url, n)) for i, (t, u, p, url, n) in enumerate(DATA)) + "\n"
p2 = os.path.join(tmp, "from_kt.kdbx")
r = cli("create", p2, PW, stdin=tsv)
check("Custode crea il database", r.returncode == 0, r.stderr)
try:
    k2 = PyKeePass(p2, password=PW)
    ok = True
except Exception as ex:
    ok = False; detail = repr(ex)
check("pykeepass apre il file di Custode", ok, locals().get("detail", ""))
if ok:
    pyrows = sorted((e.parentgroup.path and "/".join(e.parentgroup.path) or "", e.title, e.username or "", e.password or "", e.url or "", e.notes or "") for e in k2.entries)
    ktrows = sorted((("" if i != 1 else "Lavoro/Sotto"), t, u, p, url, n) for i, (t, u, p, url, n) in enumerate(DATA))
    # pykeepass indica il percorso senza il gruppo radice
    check("pykeepass vede gli stessi dati scritti da Custode", pyrows == ktrows, f"\n py={pyrows}\n kt={ktrows}")
    try: PyKeePass(p2, password="sbagliata"); wrong_ok = False
    except Exception: wrong_ok = True
    check("pykeepass rifiuta password errata sul file di Custode", wrong_ok)

# 4) round trip: apri e risalva con Custode, nulla va perso (storico, allegati, campi extra)
p3 = os.path.join(tmp, "hist.kdbx")
kp = create_database(p3, password=PW)
e = kp.add_entry(kp.root_group, "Con storico", "u", "v1")
kp.save(); e.password = "v2"; e.save_history() if hasattr(e, "save_history") else None; e.password = "v3"
e.set_custom_property("Extra", "valore-extra")
att = kp.add_binary(b"CONTENUTO-ALLEGATO-\x00\x01\xff" * 50)
e.add_attachment(att, "file.bin")
kp.save()
p4 = os.path.join(tmp, "hist_rt.kdbx")
r = cli("roundtrip", p3, p4, PW)
check("roundtrip Custode senza errori", r.returncode == 0, r.stderr)
a, b = PyKeePass(p3, password=PW), PyKeePass(p4, password=PW)
ea, eb = a.find_entries(title="Con storico", first=True), b.find_entries(title="Con storico", first=True)
check("roundtrip: password e campo extra conservati", eb.password == "v3" and eb.get_custom_property("Extra") == "valore-extra")
check("roundtrip: storico conservato", len(eb.history) == len(ea.history), f"{len(eb.history)} vs {len(ea.history)}")
check("roundtrip: allegato conservato byte per byte",
      len(eb.attachments) == 1 and eb.attachments[0].data == ea.attachments[0].data)
check("il roundtrip cambia i byte del file (nuovi salt/IV)", open(p3, "rb").read() != open(p4, "rb").read())

shutil.rmtree(tmp)
print("\nRISULTATO:", "TUTTI I TEST PASSATI" if not fails else f"{len(fails)} FALLITI: {fails}")
sys.exit(1 if fails else 0)
