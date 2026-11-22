# Custode Password

App Android (Kotlin + Jetpack Compose) per custodire le password in locale, cifrate.

- Cifratura: PBKDF2-HMAC-SHA256 (210k iterazioni) + AES-256-GCM, un solo file `vault.bin` nella memoria privata dell'app
- Nessun permesso, nessuna rete, backup Android disattivato
- Blocco automatico in background, FLAG_SECURE, appunti sensibili cancellati dopo 30 s
- Generatore di password, ricerca, modifica ed eliminazione
- Sblocco con impronta (chiave nell'Android Keystore, invalidata se cambiano le impronte)
- Esporta/ripristina backup cifrato (file `.cpv`, apribile solo con la password principale)

## Ottenere l'APK
Su GitHub: **Actions → Build APK → ultima esecuzione → Artifacts → CustodePassword-debug**,
poi copia `app-debug.apk` sul telefono e installalo (consenti "origini sconosciute").
In alternativa apri la cartella in Android Studio e premi Run.

Attenzione: se dimentichi la password principale i dati non sono recuperabili.

## Custode (fork di KeePassDX)
La cartella [`custode-kdx/`](custode-kdx/) contiene una versione di **KeePassDX 4.5.8** con nome e icona propri,
compilata da GitHub Actions ("Build Custode") a partire dal codice sorgente. Licenza GPL-3, vedi `custode-kdx/NOTICE.md`.
Formato database: KeePass (KDBX), compatibile con KeePassXC, KeePassDX e altri.

## TV Guida (`tv-guida/`)
App Android che mostra cosa c'è in TV (guida XMLTV italiana, canali Sky inclusi) e, toccando un programma, cambia canale sul box Sky Stream: il telefono si associa al box come tastiera Bluetooth (HID) e digita il numero del canale. I numeri predefiniti sono solo indicativi: si correggono dall'app. APK da GitHub Actions ("Build TV Guida").
