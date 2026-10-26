# Zettelwirtschaft

Minimalistische Android-App zum Erfassen von Ausgaben – nur **Datum, Zweck, Betrag**.

- Ausgaben erfassen, nach Monat ansehen, Summe pro Monat, Einträge löschen
- **„CSV teilen“** erzeugt jederzeit die CSV des angezeigten Monats und öffnet den normalen Android-Teilen-Dialog (Mail, Messenger, Drive, Nextcloud …)
- **Automatisch einmal im Monat:** Am Monatsanfang wird die CSV des Vormonats erstellt und per Benachrichtigung zum Teilen angeboten (nur wenn es Einträge gibt)
- Daten bleiben lokal auf dem Gerät, keine Internet-Berechtigung

CSV-Format (öffnet direkt in deutschem Excel/LibreOffice):

```
Datum;Zweck;Betrag
03.10.2026;Tanken;45,30
12.10.2026;Bürobedarf;12,99
```

Semikolon-getrennt, Dezimalkomma, UTF-8 mit BOM.

## Installation

Die aktuelle APK gibt es immer unter
`https://github.com/<user>/<repo>/releases/latest/download/zettelwirtschaft.apk`

Auf dem Handy herunterladen, „Installation aus unbekannten Quellen“ für den Browser erlauben, installieren.
Tipp: Mit [Obtainium](https://github.com/ImranR98/Obtainium) und der Repo-URL bekommt man Updates automatisch angeboten.

## Release veröffentlichen

### Einmalig: Signatur-Schlüssel anlegen

```bash
keytool -genkeypair -v -keystore release.jks -alias zettelwirtschaft \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64     # macOS: base64 -i release.jks
```

Im Repo unter *Settings → Secrets and variables → Actions* anlegen:

| Secret                      | Inhalt                       |
| --------------------------- | ---------------------------- |
| `ANDROID_KEYSTORE_BASE64`   | Inhalt von `release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore-Passwort            |
| `ANDROID_KEY_ALIAS`         | `zettelwirtschaft`           |
| `ANDROID_KEY_PASSWORD`      | Schlüssel-Passwort           |

`release.jks` sicher aufbewahren – ohne denselben Schlüssel lassen sich Updates nicht über die installierte App installieren.

### Jede neue Version

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions baut die signierte APK und hängt sie als `zettelwirtschaft.apk` an ein neues Release.
Pushes auf `main` und Pull Requests erzeugen eine Debug-APK als Build-Artefakt.

## Lokal bauen

Android Studio öffnen oder:

```bash
./gradlew assembleDebug
```

Benötigt JDK 17+ und das Android SDK (compileSdk 35). minSdk 26 (Android 8).
