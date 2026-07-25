# J.A.R.V.I.S. – native Android-App

Eine eigenständige Android-App (kein Termux, kein Server nötig): Chat über Groq,
Wetter (Open-Meteo), Standort und **freihändige Sprache mit Weckwort „Jarvis"**.
Die Oberfläche liegt in `app/src/main/assets/index.html`, die App ist eine
schlanke WebView-Hülle mit **nativer Sprachbrücke** (Android `SpeechRecognizer`
+ `TextToSpeech`).

Beim ersten Start: **Zahnrad ⚙ → Groq-API-Key eintragen** (kostenlos auf
console.groq.com). Der Key bleibt nur auf dem Gerät.

---

## APK bauen – 3 Wege

### A) Am einfachsten ohne PC: GitHub Actions baut die APK
1. Lege dir auf github.com ein (auch leeres) Repository an.
2. Lade den **Inhalt dieses ZIP** dort hinein (Ordner `jarvis/android-app/`
   und `.github/workflows/jarvis-apk.yml` beibehalten).
3. Der Workflow **„Build Jarvis APK"** startet automatisch (Tab *Actions*).
4. Nach ~3 Minuten liegt die fertige **`jarvis.apk`** unter *Releases*
   (Tag `jarvis-app`) **und** als Artefakt im Actions-Lauf.
5. APK auf dem Handy herunterladen, antippen, „Installieren aus unbekannten
   Quellen" erlauben – fertig.

### B) Mit Android Studio (Klick-Lösung)
1. ZIP entpacken, in Android Studio **`jarvis/android-app`** öffnen.
2. Handy per USB anstecken (USB-Debugging an) und auf **Run ▶** drücken –
   die App wird direkt installiert.

### C) Auf der Kommandozeile
Voraussetzung: JDK 17 + Android SDK (`ANDROID_HOME` gesetzt).
```bash
cd jarvis/android-app
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk
```

---

## Technik
- `minSdk 24`, `targetSdk 34`, AGP 8.2.2, Java 17.
- Berechtigungen: Internet, Mikrofon (Sprache), Standort (Wetter).
- Kein Klartext-Key im Code – Eingabe in der App, Speicherung in `localStorage`.
- Der Debug-Build ist mit dem Standard-Debug-Schlüssel signiert und lässt sich
  ohne Weiteres sideloaden.
