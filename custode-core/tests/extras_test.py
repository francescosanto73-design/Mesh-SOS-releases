"""TOTP, cestino, storico, allegati: Custode contro KeePassXC e pykeepass."""
import os, subprocess, sys, tempfile, shutil, time
from pykeepass import create_database, PyKeePass
ENV = {**os.environ, "QT_QPA_PLATFORM": "offscreen", "JAVA_TOOL_OPTIONS": ""}
CLI = os.path.join(os.path.dirname(__file__), "..", "build", "install", "custode-core", "bin", "custode-core")
tmp = tempfile.mkdtemp(); fails = []; PW = "prova-extra-123"
def run(cmd, stdin=None): return subprocess.run(cmd, input=stdin, capture_output=True, text=True, env=ENV)
def check(n, c, d=""):
    print(("PASS " if c else "FAIL ") + n + ("" if c else f" -> {d}")); (c or fails.append(n))

# 1) TOTP: stessi codici di KeePassXC (otpauth, campi KeePass 2, SHA256/8 cifre)
f = os.path.join(tmp, "otp.kdbx"); kp = create_database(f, password=PW)
cases = {
    "uri-sha1":   ("otp", "otpauth://totp/Test:a?secret=JBSWY3DPEHPK3PXP&period=30&digits=6&issuer=Test"),
    "uri-sha256": ("otp", "otpauth://totp/Test:b?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA&period=30&digits=8&algorithm=SHA256"),
    "uri-60s":    ("otp", "otpauth://totp/Test:c?secret=JBSWY3DPEHPK3PXP&period=60&digits=6"),
    "formato-key": ("otp", "key=JBSWY3DPEHPK3PXP&size=6&step=30"),
}
for t, (k, v) in cases.items():
    e = kp.add_entry(kp.root_group, t, "u", "p"); e.otp = v
e = kp.add_entry(kp.root_group, "keepass2", "u", "p")
for k, v in {"TimeOtp-Secret-Base32": "JBSWY3DPEHPK3PXP", "TimeOtp-Period": "30", "TimeOtp-Length": "6", "TimeOtp-Algorithm": "HMAC-SHA-1"}.items(): e.set_custom_property(k, v)
kp.save()
for t in list(cases):
    for attempt in range(3):
        ours = run([CLI, "totp", f, PW, t]).stdout.strip().split("\t")
        xc = run(["keepassxc-cli", "show", "-t", f, t], stdin=PW + "\n").stdout.strip()
        if ours[0] == xc: break
        time.sleep(3)  # forse a cavallo di un cambio di finestra
    check(f"TOTP [{t}] uguale a KeePassXC", ours[0] == xc and len(xc) >= 6, f"nostro={ours} keepassxc={xc!r}")

# KeePass 2 (campi TimeOtp-*) non è letto da KeePassXC: si verifica che dia lo stesso codice del caso uri-sha1 (stesso segreto, RFC 6238)
a = run([CLI, "totp", f, PW, "keepass2"]).stdout.split("\t")[0]; b = run([CLI, "totp", f, PW, "uri-sha1"]).stdout.split("\t")[0]
check("TOTP [keepass2 TimeOtp-*] coerente con lo stesso segreto in otpauth", a == b and len(a) == 6, f"{a} vs {b}")
# i segreti "nudi" non vanno interpretati (KeePassXC li legge diversamente)
e = kp.add_entry(kp.root_group, "nudo", "u", "p"); e.otp = "JBSWY3DPEHPK3PXP"; kp.save()
check("TOTP [segreto nudo] ignorato invece di dare un codice sbagliato", run([CLI, "totp", f, PW, "nudo"]).stdout.strip() == "NOTOTP")

# 2) storico, allegati, cestino scritti da Custode -> letti da pykeepass e KeePassXC
f2 = os.path.join(tmp, "ops_in.kdbx"); kp = create_database(f2, password=PW)
kp.add_entry(kp.root_group, "Modificabile", "u", "originale"); kp.add_entry(kp.root_group, "DaCestinare", "u", "x"); kp.save()
f3 = os.path.join(tmp, "ops_out.kdbx")
r = run([CLI, "ops", f2, f3, PW]); check("Custode applica modifiche, allegato e cestino", r.returncode == 0, r.stderr[:300])
k = PyKeePass(f3, password=PW)
e = k.find_entries(title="Modificabile", first=True)
check("pykeepass: password attuale", e.password == "ancora-piu-nuova", e.password)
check("pykeepass: storico (una versione per ogni modifica, allegato incluso)", [h.password for h in e.history] == ["originale", "nuova-password", "ancora-piu-nuova"], str([h.password for h in e.history]))
check("pykeepass: campo extra", e.get_custom_property("Extra") == "valore")
check("pykeepass: allegato", len(e.attachments) == 1 and e.attachments[0].filename == "nota.txt" and e.attachments[0].data == b"contenuto allegato")
d = k.find_entries(title="DaCestinare", first=True)
check("pykeepass: la voce è nel cestino", k.recyclebin_group is not None and d.parentgroup.uuid == k.recyclebin_group.uuid)
out = os.path.join(tmp, "x.txt")
r = run(["keepassxc-cli", "attachment-export", f3, "Modificabile", "nota.txt", out], stdin=PW + "\n")
check("KeePassXC esporta l'allegato", os.path.exists(out) and open(out, "rb").read() == b"contenuto allegato", r.stderr[:200])
r = run(["keepassxc-cli", "ls", "-R", f3], stdin=PW + "\n")
check("KeePassXC vede il cestino con la voce dentro", "Cestino/" in r.stdout and "DaCestinare" in r.stdout, r.stdout)
r = run(["keepassxc-cli", "show", "-s", "--all", f3, "Modificabile"], stdin=PW + "\n")
check("KeePassXC legge la voce modificata", "ancora-piu-nuova" in r.stdout and "valore" in r.stdout, r.stdout[:300])
shutil.rmtree(tmp)
print("\nRISULTATO:", "TUTTI I TEST PASSATI" if not fails else f"{len(fails)} FALLITI: {fails}")
sys.exit(1 if fails else 0)
