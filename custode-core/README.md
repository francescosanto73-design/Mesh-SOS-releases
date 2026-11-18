# custode-core — nucleo KDBX 4 scritto da zero

Libreria Kotlin pura (JVM/Android) che legge e scrive database KeePass, scritta dalla specifica pubblica del formato.
Non contiene codice di KeePassDX.

- Lettura KDBX 4 e KDBX 3; scrittura sempre KDBX 4 (un file KDBX 3 viene convertito al salvataggio, con KDF Argon2d)
- Cifrari: AES-256-CBC, ChaCha20; KDF: Argon2d/Argon2id, AES-KDF; HMAC per blocco; gzip
- Il documento XML è conservato: storico, allegati, campi personalizzati e dati di altre app non vanno persi
- Difese su file ostili: niente DTD/entità XML, limiti su memoria/iterazioni Argon2, ogni blocco verificato prima dell'uso

## Test
`tests/interop_test.py` (pykeepass) e `tests/keepassxc_test.py` (KeePassXC reale): lettura, scrittura, roundtrip,
password errata, file manomesso, allegati, unicode. Girano anche in GitHub Actions ("Test nucleo KDBX").

## Non ancora supportato
File chiave (keyfile), scrittura KDBX 3, formato KDB 1.x, TOTP/passkey, cestino, storico delle modifiche alla creazione di nuove versioni di una voce.
