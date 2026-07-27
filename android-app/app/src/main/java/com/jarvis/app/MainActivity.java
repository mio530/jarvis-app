package com.jarvis.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.ActivityNotFoundException;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.SmsManager;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * J.A.R.V.I.S. – native Android-Huelle mit vollem Geraete-Zugriff.
 *
 * Die Oberflaeche liegt in assets/index.html und laeuft in einem WebView.
 * Zwei Bruecken stehen dem JavaScript zur Verfuegung:
 *   AndroidVoice  – Sprachein- und -ausgabe (SpeechRecognizer / TextToSpeech)
 *   AndroidDevice – Dateien, Apps, Anrufe, SMS, Taschenlampe, Shell, ...
 */
public class MainActivity extends AppCompatActivity {

    /** Laeuft ein Dateidialog aus der Oberflaeche, wartet hier die Rueckmeldung.
     *  Ohne onShowFileChooser tut ein Datei-Feld in einer WebView GAR NICHTS -
     *  beim Antippen passiert einfach nichts. */
    private ValueCallback<Uri[]> dateiRueckmeldung;
    private static final int DATEI_WAHL = 8021;

    private WebView web;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Beim Start werden nur unkritische Berechtigungen erfragt.
     * SMS und Telefonie sind unter Android "eingeschraenkte Berechtigungen" und
     * werden bei seitlich installierten Apps blockiert – wir fragen sie deshalb
     * erst dann, wenn sie wirklich gebraucht werden, und weichen sonst auf die
     * jeweilige System-App aus.
     */
    private static final String[] PERMS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    private boolean has(String perm) {
        return androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static final String TAG = "JARVIS";
    /**
     * Eigener Ordner fuer Oberflaeche, Absturzberichte und Protokolle.
     * Ab Android 11 darf eine App /sdcard/Jarvis nur mit ausdruecklich
     * erteiltem Vollzugriff anlegen. Klappt das nicht, wird der
     * app-eigene Ordner genutzt - der geht immer und ohne Nachfrage.
     */
    private File jarvisDir() {
        File d = new File(Environment.getExternalStorageDirectory(), "Jarvis");
        if (d.isDirectory() || d.mkdirs()) return d;
        File fallback = new File(getExternalFilesDir(null), "Jarvis");
        if (!fallback.isDirectory()) fallback.mkdirs();
        android.util.Log.d(TAG, "kein Zugriff auf /sdcard/Jarvis, nutze " + fallback);
        return fallback;
    }

    /** Schreibt jeden unbehandelten Absturz in eine Datei, damit er lesbar bleibt. */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String report = "Zeit: " + new java.util.Date() + "\n"
                        + "Version: " + versionString() + "\n"
                        + "Geraet: " + Build.MANUFACTURER + " " + Build.MODEL
                        + " (Android " + Build.VERSION.RELEASE + ")\n"
                        + "Thread: " + thread.getName() + "\n\n" + sw;
                FileOutputStream out = new FileOutputStream(new File(jarvisDir(), "crash.txt"));
                out.write(report.getBytes(StandardCharsets.UTF_8));
                out.close();
                android.util.Log.e(TAG, "Absturz gespeichert", ex);
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    private String versionString() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + " (Build " + code + ")";
        } catch (Exception e) { return "?"; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashHandler();

        List<String> ask = new ArrayList<>();
        for (String p : PERMS) ask.add(p);
        if (Build.VERSION.SDK_INT >= 33) ask.add("android.permission.POST_NOTIFICATIONS");
        ActivityCompat.requestPermissions(this, ask.toArray(new String[0]), 1);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // Die Oberflaeche hat eigene Schriftgroessen. Ohne das hier wuerde die
        // Systemschriftgroesse alles zusaetzlich vergroessern und das Layout
        // aus dem Bild schieben ("wie reingezoomt").
        s.setTextZoom(100);
        s.setUseWideViewPort(true);      // viewport-Angabe der Seite beachten
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                main.post(() -> request.grant(request.getResources()));
            }
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            /** Datei- und Bildauswahl aus der Oberflaeche. */
            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                // Einen noch offenen Dialog sauber beenden
                if (dateiRueckmeldung != null) dateiRueckmeldung.onReceiveValue(null);
                dateiRueckmeldung = callback;
                try {
                    Intent i = params.createIntent();
                    if (i == null) {
                        i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                    }
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(i, "Datei auswaehlen"), DATEI_WAHL);
                    return true;
                } catch (Exception e) {
                    dateiRueckmeldung = null;
                    main.post(() -> Toast.makeText(MainActivity.this,
                            "Dateiauswahl nicht moeglich: " + e, Toast.LENGTH_LONG).show());
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new VoiceBridge(), "AndroidVoice");
        web.addJavascriptInterface(new DeviceBridge(), "AndroidDevice");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.GERMAN);
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onError(String id) { jsCall("window.onTtsEnd&&onTtsEnd()"); }
            @Override public void onDone(String id) { jsCall("window.onTtsEnd&&onTtsEnd()"); }
        });

        loadInterface();
    }

    /**
     * Laedt die Oberflaeche. Liegt unter /sdcard/Jarvis/index.html eine Datei,
     * wird diese bevorzugt – so lassen sich Aktualisierungen einspielen,
     * ohne die App neu zu bauen ("Selbst-Update").
     */
    private void loadInterface() {
        File custom = new File(jarvisDir(), "index.html");
        if (custom.exists() && custom.length() > 5000) {
            android.util.Log.d(TAG, "Lade aktualisierte Oberflaeche: " + custom);
            web.loadUrl("file://" + custom.getAbsolutePath());
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private void jsCall(final String js) {
        main.post(() -> web.evaluateJavascript(js, null));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }

    // ---------------------------------------------------------------- Sprache
    private class VoiceBridge {
        @JavascriptInterface
        public void speak(final String text) {
            main.post(() -> { if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis"); });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            main.post(() -> { if (tts != null) tts.stop(); });
        }

        @JavascriptInterface
        public void startListening() {
            main.post(MainActivity.this::startRecognizer);
        }

        @JavascriptInterface
        public void stopListening() {
            main.post(() -> { if (recognizer != null) recognizer.cancel(); });
        }
    }

    // ------------------------------------------------------------ Geraete-Bruecke
    /** Alle Methoden geben eine Zeichenkette zurueck, die Jarvis im Chat anzeigt. */
    private class DeviceBridge {

        // --- Dateien -------------------------------------------------------
        @JavascriptInterface
        public String listFiles(String path) {
            try {
                File dir = new File(resolve(path));
                if (!dir.exists()) return "Nicht gefunden: " + dir.getAbsolutePath();
                if (dir.isFile()) return "Datei (" + dir.length() + " Bytes): " + dir.getAbsolutePath();
                File[] fs = dir.listFiles();
                if (fs == null) return "Kein Zugriff auf " + dir.getAbsolutePath()
                        + " - erlaube 'Zugriff auf alle Dateien' in den Android-Einstellungen.";
                StringBuilder sb = new StringBuilder(dir.getAbsolutePath() + " (" + fs.length + " Eintraege):\n");
                int n = 0;
                for (File f : fs) {
                    if (n++ > 200) { sb.append("... gekuerzt"); break; }
                    sb.append(f.isDirectory() ? "[Ordner] " : "         ").append(f.getName());
                    if (f.isFile()) sb.append("  (").append(f.length() / 1024).append(" kB)");
                    sb.append('\n');
                }
                return sb.toString();
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String readFile(String path) {
            try {
                File f = new File(resolve(path));
                if (!f.exists()) return "Nicht gefunden: " + f.getAbsolutePath();
                if (f.length() > 400000) return "Datei zu gross (" + f.length() / 1024 + " kB).";
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int read = in.read(buf);
                in.close();
                return new String(buf, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            } catch (Exception e) { return "Fehler beim Lesen: " + e; }
        }

        @JavascriptInterface
        public String writeFile(String path, String content) {
            try {
                File f = new File(resolve(path));
                File p = f.getParentFile();
                if (p != null && !p.isDirectory() && !p.mkdirs())
                    return "Ordner nicht anlegbar: " + p +
                           " – dafuer braucht die App Vollzugriff auf Dateien. " +
                           "Einstellungen, Apps, Jarvis, Berechtigungen, " +
                           "\"Zugriff auf alle Dateien\" erlauben.";
                FileOutputStream out = new FileOutputStream(f);
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.close();
                return "Gespeichert: " + f.getAbsolutePath();
            } catch (Exception e) { return "Fehler beim Schreiben: " + e; }
        }

        @JavascriptInterface
        public String deleteFile(String path) {
            try {
                File f = new File(resolve(path));
                if (!f.exists()) return "Nicht gefunden: " + f.getAbsolutePath();
                return f.delete() ? "Geloescht: " + f.getAbsolutePath() : "Loeschen nicht erlaubt.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Oeffnet die Android-Seite fuer den Vollzugriff aufs Dateisystem (Android 11+). */
        @JavascriptInterface
        public String requestAllFiles() {
            if (Build.VERSION.SDK_INT < 30) return "Auf dieser Android-Version nicht noetig.";
            if (Environment.isExternalStorageManager()) return "Voller Dateizugriff ist bereits erteilt.";
            main.post(() -> {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            });
            return "Bitte 'Zugriff auf alle Dateien' aktivieren, Meister.";
        }

        // --- Apps ----------------------------------------------------------
        @JavascriptInterface
        public String listApps() {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                StringBuilder sb = new StringBuilder("Installierte Apps:\n");
                int n = 0;
                for (ApplicationInfo a : apps) {
                    if (pm.getLaunchIntentForPackage(a.packageName) == null) continue;
                    if (n++ > 150) { sb.append("... gekuerzt"); break; }
                    sb.append("- ").append(pm.getApplicationLabel(a)).append("  (")
                      .append(a.packageName).append(")\n");
                }
                return sb.toString();
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String launchApp(String query) {
            try {
                PackageManager pm = getPackageManager();
                String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
                if (q.isEmpty()) return "Welche App, Meister?";

                // Nicht den ERSTEN Treffer nehmen, sondern nach Guete bewerten.
                // Sonst gewinnt "Sticker Maker for WhatsApp" gegen WhatsApp,
                // weil dessen Paketname das Wort ebenfalls enthaelt.
                int besteGuete = 0;
                List<ApplicationInfo> beste = new ArrayList<>();
                for (ApplicationInfo a : pm.getInstalledApplications(0)) {
                    if (pm.getLaunchIntentForPackage(a.packageName) == null) continue;
                    String label = String.valueOf(pm.getApplicationLabel(a)).toLowerCase(Locale.ROOT);
                    String paket = a.packageName.toLowerCase(Locale.ROOT);
                    int guete = 0;
                    if (label.equals(q))                      guete = 100;   // genau der Name
                    else if (paket.equals(q))                 guete = 95;
                    else if (paket.endsWith("." + q))         guete = 90;    // com.whatsapp
                    else if (label.startsWith(q + " "))       guete = 80;    // "Google Maps" fuer "google"
                    else if (label.startsWith(q))             guete = 70;
                    else if (wortTreffer(label, q))           guete = 60;    // eigenes Wort im Namen
                    else if (label.contains(q))               guete = 30;
                    else if (paket.contains(q))               guete = 10;    // schwaechster Treffer
                    if (guete == 0) continue;
                    if (guete > besteGuete) { besteGuete = guete; beste.clear(); }
                    if (guete == besteGuete) beste.add(a);
                }
                if (beste.isEmpty()) return "App nicht gefunden: " + query;

                // Mehrere gleich gute? Dann nicht raten, sondern nachfragen.
                // "amazon" kann Shopping, Music oder Luna sein - und "Schach"
                // ist zweimal installiert.
                if (beste.size() > 1) {
                    StringBuilder sb = new StringBuilder("MEHRERE");
                    for (int i = 0; i < beste.size() && i < 6; i++)
                        sb.append('|').append(pm.getApplicationLabel(beste.get(i)));
                    return sb.toString();
                }
                ApplicationInfo ziel = beste.get(0);
                Intent i = pm.getLaunchIntentForPackage(ziel.packageName);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Starte " + pm.getApplicationLabel(ziel) + ", Meister.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Kommt der Suchbegriff im Namen als eigenes Wort vor? */
        private boolean wortTreffer(String label, String q) {
            for (String w : label.split("[^\\p{L}\\p{N}]+"))
                if (w.equals(q)) return true;
            return false;
        }

        /**
         * Sucht Kontakte nach Namen und liefert "Name|Nummer" je Zeile.
         * Ohne das hier hatte das Modell keine Moeglichkeit, an eine Nummer zu
         * kommen - und hat prompt eine erfunden und angerufen.
         */
        @JavascriptInterface
        public String findContact(String name) {
            if (!has(Manifest.permission.READ_CONTACTS))
                return "KEIN_RECHT";
            String q = name == null ? "" : name.trim();
            if (q.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                      ContactsContract.CommonDataKinds.Phone.NUMBER },
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                        new String[]{ "%" + q + "%" }, null);
                java.util.HashSet<String> gesehen = new java.util.HashSet<>();
                while (c != null && c.moveToNext() && gesehen.size() < 20) {
                    String n = c.getString(0), nr = c.getString(1);
                    if (n == null || nr == null) continue;
                    String sauber = nr.replaceAll("[^+0-9]", "");
                    if (sauber.length() < 4) continue;
                    if (!gesehen.add(n + "|" + sauber)) continue;
                    sb.append(n).append('|').append(sauber).append('\n');
                }
            } catch (Exception e) { return "FEHLER: " + e; }
            finally { if (c != null) c.close(); }
            return sb.toString().trim();
        }

        /**
         * Legt die aktualisierte Oberflaeche dort ab, wo die App sie auch
         * wirklich lesen darf. Vorher schrieb das Selbst-Update fest nach
         * /sdcard/Jarvis - und scheiterte ab Android 11 ohne Vollzugriff.
         */
        @JavascriptInterface
        public String updateInterface(String html) {
            try {
                if (html == null || html.length() < 5000)
                    return "Inhalt zu klein - Abbruch.";
                File ziel = new File(jarvisDir(), "index.html");
                FileOutputStream out = new FileOutputStream(ziel);
                out.write(html.getBytes(StandardCharsets.UTF_8));
                out.close();
                return "Gespeichert: " + ziel.getAbsolutePath();
            } catch (Exception e) { return "Fehler beim Schreiben: " + e; }
        }

        // --- Telefon -------------------------------------------------------
        @JavascriptInterface
        public String call(String number) {
            try {
                if (has(Manifest.permission.CALL_PHONE)) {
                    Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return "Rufe " + number + " an, Meister.";
                }
                // Ohne Berechtigung: Telefon-App mit vorgewaehlter Nummer oeffnen
                Intent d = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
                d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(d);
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.CALL_PHONE}, 2);
                return "Telefon geoeffnet mit " + number + " – zum Waehlen antippen, Meister.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String sendSms(String number, String text) {
            try {
                if (has(Manifest.permission.SEND_SMS)) {
                    SmsManager sm = Build.VERSION.SDK_INT >= 31
                            ? getSystemService(SmsManager.class)
                            : SmsManager.getDefault();
                    sm.sendTextMessage(number, null, text, null, null);
                    return "SMS an " + number + " gesendet, Meister.";
                }
                // Ohne Berechtigung: Nachrichten-App mit fertigem Text oeffnen
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
                i.putExtra("sms_body", text);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Nachrichten-App geoeffnet – nur noch senden, Meister. "
                        + "(Direktversand: Einstellungen > Apps > J.A.R.V.I.S. > Menue > "
                        + "'Eingeschraenkte Einstellungen zulassen', dann SMS erlauben.)";
            } catch (Exception e) { return "SMS fehlgeschlagen: " + e; }
        }

        // --- Hardware ------------------------------------------------------
        @JavascriptInterface
        public String torch(boolean on) {
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Boolean has = cm.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (Boolean.TRUE.equals(has)) {
                        cm.setTorchMode(id, on);
                        return "Taschenlampe " + (on ? "an" : "aus") + ", Meister.";
                    }
                }
                return "Kein Blitzlicht gefunden.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return "Kein Vibrationsmotor.";
                if (Build.VERSION.SDK_INT >= 26)
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
                return "Vibration, Meister.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String systemInfo() {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
                StatFs st = new StatFs(Environment.getExternalStorageDirectory().getPath());
                long freeGb = (st.getAvailableBytes() / 1024 / 1024 / 1024);
                long totGb = (st.getTotalBytes() / 1024 / 1024 / 1024);
                Runtime rt = Runtime.getRuntime();
                return "Akku " + bat + "% - Speicher frei " + freeGb + " von " + totGb + " GB - "
                        + "Geraet " + Build.MANUFACTURER + " " + Build.MODEL
                        + " - Android " + Build.VERSION.RELEASE
                        + " - Kerne " + rt.availableProcessors();
            } catch (Exception e) { return "Fehler: " + e; }
        }

        // --- Zwischenablage / Meldungen ------------------------------------
        @JavascriptInterface
        public String copy(String text) {
            main.post(() -> {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("jarvis", text));
            });
            return "Kopiert, Meister.";
        }

        @JavascriptInterface
        public String paste() {
            try {
                ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb == null || cb.getPrimaryClip() == null || cb.getPrimaryClip().getItemCount() == 0)
                    return "Zwischenablage ist leer.";
                return "Zwischenablage: " + cb.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String notify(String text) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            "jarvis", "Jarvis", NotificationManager.IMPORTANCE_DEFAULT));
                }
                NotificationCompat.Builder n = new NotificationCompat.Builder(MainActivity.this, "jarvis")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("J.A.R.V.I.S.")
                        .setContentText(text)
                        .setAutoCancel(true);
                if (nm != null) nm.notify((int) (System.currentTimeMillis() % 100000), n.build());
                return "Benachrichtigung gesendet.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String toast(String text) {
            main.post(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show());
            return "";
        }

        // --- Shell ----------------------------------------------------------
        /** Fuehrt einen Befehl aus (ohne Root - im App-Kontext). */
        @JavascriptInterface
        public String exec(String cmd) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line; int n = 0;
                while ((line = r.readLine()) != null && n++ < 400) sb.append(line).append('\n');
                r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while ((line = r.readLine()) != null && n++ < 400) sb.append(line).append('\n');
                p.waitFor();
                String out = sb.toString().trim();
                return out.isEmpty() ? "(kein Rueckgabewert)" : out;
            } catch (Exception e) { return "Fehler: " + e; }
        }

        // --- Diagnose ------------------------------------------------------
        /** Version, Gerät und Herkunft der geladenen Oberfläche. */
        @JavascriptInterface
        public String appInfo() {
            File custom = new File(jarvisDir(), "index.html");
            return "App " + versionString()
                    + " | Oberflaeche: " + (custom.exists() && custom.length() > 5000
                        ? "aktualisiert (" + custom.length() / 1024 + " kB)" : "eingebaut")
                    + " | " + Build.MANUFACTURER + " " + Build.MODEL
                    + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                    + " | Ordner: " + jarvisDir().getAbsolutePath();
        }

        /** Liest das System-Protokoll dieser App (die letzten n Zeilen). */
        @JavascriptInterface
        public String logcat(int lines) {
            if (lines <= 0 || lines > 500) lines = 150;
            String out = exec("logcat -d -v brief -t " + lines + " *:W");
            return out.isEmpty() ? "(Protokoll leer)" : out;
        }

        /** Gibt den letzten gespeicherten Absturzbericht zurueck. */
        @JavascriptInterface
        public String lastCrash() {
            File f = new File(jarvisDir(), "crash.txt");
            if (!f.exists()) return "Kein Absturzbericht vorhanden.";
            String s = readFile(f.getAbsolutePath());
            return s.length() > 4000 ? s.substring(0, 4000) + "\n... gekuerzt" : s;
        }

        /**
         * Suchtabelle: App -> Adressmuster -> Paketname.
         * Fast jede App nimmt eine Adresse entgegen und springt direkt in ihre
         * Suche. Damit kann Jarvis IN den Apps etwas tun, statt sie nur zu
         * oeffnen - ganz ohne Sonderrechte.
         */
        private final String[][] SUCHZIELE = {
            {"youtube",   "https://www.youtube.com/results?search_query=%s", "com.google.android.youtube"},
            {"spotify",   "spotify:search:%s",                               "com.spotify.music"},
            {"maps",      "geo:0,0?q=%s",                                    "com.google.android.apps.maps"},
            {"karten",    "geo:0,0?q=%s",                                    "com.google.android.apps.maps"},
            {"play",      "market://search?q=%s",                            "com.android.vending"},
            {"playstore", "market://search?q=%s",                            "com.android.vending"},
            {"amazon",    "https://www.amazon.de/s?k=%s",                    "com.amazon.mShop.android.shopping"},
            {"ebay",      "https://www.kleinanzeigen.de/s-%s/k0",            "com.ebay.kleinanzeigen"},
            {"kleinanzeigen","https://www.kleinanzeigen.de/s-%s/k0",         "com.ebay.kleinanzeigen"},
            {"tiktok",    "https://www.tiktok.com/search?q=%s",              "com.zhiliaoapp.musically"},
            {"instagram", "https://www.instagram.com/explore/tags/%s/",      "com.instagram.android"},
            {"twitch",    "https://www.twitch.tv/search?term=%s",            "tv.twitch.android.app"},
            {"netflix",   "https://www.netflix.com/search?q=%s",             "com.netflix.mediaclient"},
            {"github",    "https://github.com/search?q=%s",                  "com.github.android"},
            {"wikipedia", "https://de.wikipedia.org/w/index.php?search=%s",  ""},
            {"idealo",    "https://www.idealo.de/preisvergleich/MainSearchProductCategory.html?q=%s", "de.idealo.android"},
            {"db",        "https://www.bahn.de/buchung/fahrplan/suche#sts=true&so=%s", "de.hafas.android.db"},
            {"bahn",      "https://www.bahn.de/buchung/fahrplan/suche#sts=true&so=%s", "de.hafas.android.db"},
            {"google",    "https://www.google.com/search?q=%s",              ""},
            {"web",       "https://www.google.com/search?q=%s",              ""}
        };

        /** Oeffnet die Suche der genannten App. Fehlt die App, geht es im Browser. */
        @JavascriptInterface
        public String searchIn(String app, String query) {
            String a = app == null ? "" : app.trim().toLowerCase(Locale.ROOT);
            String q = query == null ? "" : query.trim();
            if (q.isEmpty()) return "Wonach soll ich suchen, Meister?";
            String muster = null, paket = "";
            for (String[] z : SUCHZIELE)
                if (z[0].equals(a)) { muster = z[1]; paket = z[2]; break; }
            if (muster == null) {
                // Unbekannte App: erst starten, dann im Netz suchen
                String r = launchApp(a);
                String w = openUrl("https://www.google.com/search?q="
                        + Uri.encode(a + " " + q));
                return r.startsWith("Starte")
                        ? r + " Die App kennt keine direkte Suche, ich suche daneben im Netz."
                        : w;
            }
            String url = String.format(muster, Uri.encode(q));
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!paket.isEmpty()) i.setPackage(paket);
                startActivity(i);
                return "Suche in " + a + " nach \"" + q + "\", Meister.";
            } catch (ActivityNotFoundException e) {
                // App nicht da -> ohne Paketbindung, also im Browser
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                            url.startsWith("http") ? url
                            : "https://www.google.com/search?q=" + Uri.encode(a + " " + q)));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    return a + " ist nicht installiert - ich suche im Browser, Meister.";
                } catch (Exception e2) { return "Fehler: " + e2; }
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Text in eine bestimmte App teilen (WhatsApp, Telegram, Mail ...). */
        @JavascriptInterface
        public String sendTo(String app, String text) {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                String a = app == null ? "" : app.trim().toLowerCase(Locale.ROOT);
                String paket = "";
                if (a.contains("whats")) paket = "com.whatsapp";
                else if (a.contains("telegram")) paket = "org.telegram.messenger";
                else if (a.contains("signal")) paket = "org.thoughtcrime.securesms";
                else if (a.contains("mail") || a.contains("gmail")) paket = "com.google.android.gm";
                else if (a.contains("discord")) paket = "com.discord";
                if (!paket.isEmpty()) i.setPackage(paket);
                startActivity(paket.isEmpty()
                        ? Intent.createChooser(i, "Senden mit")
                              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : i);
                return "Geoeffnet zum Senden" + (paket.isEmpty() ? "" : " in " + a) + ", Meister.";
            } catch (ActivityNotFoundException e) {
                return "Diese App ist nicht installiert, Meister.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** WhatsApp-Nachricht an eine Nummer vorbereiten. */
        @JavascriptInterface
        public String whatsapp(String nummer, String text) {
            try {
                String n = (nummer == null ? "" : nummer).replaceAll("[^+0-9]", "").replace("+", "");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://wa.me/" + n + "?text=" + Uri.encode(text == null ? "" : text)));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "WhatsApp geoeffnet, Meister - zum Senden noch antippen.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Kontaktkarte anzeigen. */
        @JavascriptInterface
        public String showContact(String name) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI,
                                Uri.encode(name == null ? "" : name)));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Kontakt geoeffnet, Meister.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Navigation starten. */
        @JavascriptInterface
        public String navigate(String ziel) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("google.navigation:q=" + Uri.encode(ziel == null ? "" : ziel)));
                i.setPackage("com.google.android.apps.maps");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Navigation nach " + ziel + " gestartet, Meister.";
            } catch (ActivityNotFoundException e) {
                return openUrl("https://www.google.com/maps/dir/?api=1&destination="
                        + Uri.encode(ziel == null ? "" : ziel));
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Wecker stellen. */
        @JavascriptInterface
        public String setAlarm(int stunde, int minute, String label) {
            try {
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
                i.putExtra(AlarmClock.EXTRA_HOUR, stunde);
                i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
                if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return String.format(Locale.GERMANY, "Wecker auf %02d:%02d gestellt, Meister.", stunde, minute);
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Kurzzeitwecker stellen. */
        @JavascriptInterface
        public String setTimer(int sekunden, String label) {
            try {
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER);
                i.putExtra(AlarmClock.EXTRA_LENGTH, sekunden);
                if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Timer auf " + (sekunden / 60) + " Minuten "
                        + (sekunden % 60 > 0 ? sekunden % 60 + " Sekunden " : "") + "gestellt, Meister.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Termin im Kalender anlegen (Kalender oeffnet sich zum Bestaetigen). */
        @JavascriptInterface
        public String addEvent(String titel, long beginn, int dauerMin) {
            try {
                Intent i = new Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, titel == null ? "Termin" : titel)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginn)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                                  beginn + (long) Math.max(dauerMin, 15) * 60000L);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Kalender geoeffnet, Meister - zum Speichern noch bestaetigen.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Laedt die Oberflaeche neu (nach einem Selbst-Update). */
        @JavascriptInterface
        public void reload() {
            main.post(MainActivity.this::loadInterface);
        }

        @JavascriptInterface
        public String openUrl(String url) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Geoeffnet: " + url;
            } catch (Exception e) { return "Fehler: " + e; }
        }

        /** Kurze Pfad-Abkuerzungen: ~, downloads, dcim, pictures, musik, dokumente */
        private String resolve(String path) {
            String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (path == null || path.trim().isEmpty()) return ext;
            String p = path.trim();
            String low = p.toLowerCase(Locale.ROOT);
            if (p.equals("~") || low.equals("home") || low.equals("intern")) return ext;
            if (low.equals("downloads") || low.equals("download")) return ext + "/Download";
            if (low.equals("dcim") || low.equals("fotos") || low.equals("kamera")) return ext + "/DCIM";
            if (low.equals("pictures") || low.equals("bilder")) return ext + "/Pictures";
            if (low.equals("music") || low.equals("musik")) return ext + "/Music";
            if (low.equals("documents") || low.equals("dokumente")) return ext + "/Documents";
            if (p.startsWith("~/")) return ext + p.substring(1);
            if (!p.startsWith("/")) return ext + "/" + p;
            return p;
        }
    }

    // ------------------------------------------------------------ Spracherkennung
    private void startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            jsCall("window.onSpeechEnd&&onSpeechEnd()");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new SimpleListener());
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try { recognizer.startListening(i); } catch (Exception e) {
            jsCall("window.onSpeechEnd&&onSpeechEnd()");
        }
    }

    private class SimpleListener implements android.speech.RecognitionListener {
        @Override public void onResults(Bundle results) {
            ArrayList<String> m = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (m != null && !m.isEmpty())
                jsCall("window.onSpeechResult&&onSpeechResult('" + esc(m.get(0)) + "')");
            else
                jsCall("window.onSpeechEnd&&onSpeechEnd()");
        }
        @Override public void onError(int error) { jsCall("window.onSpeechEnd&&onSpeechEnd()"); }
        @Override public void onReadyForSpeech(Bundle p) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float v) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle p) {}
        @Override public void onEvent(int e, Bundle p) {}
    }

    /** Ergebnis des Dateidialogs an die Oberflaeche zurueckgeben.
     *  Wird das vergessen, bleibt das Datei-Feld nach dem Abbrechen fuer
     *  immer blockiert und laesst sich nicht erneut oeffnen. */
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == DATEI_WAHL) {
            Uri[] treffer = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    treffer = new Uri[n];
                    for (int i = 0; i < n; i++)
                        treffer[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    treffer = new Uri[]{ data.getData() };
                }
            }
            if (dateiRueckmeldung != null) {
                dateiRueckmeldung.onReceiveValue(treffer);
                dateiRueckmeldung = null;
            }
            return;
        }
        super.onActivityResult(code, result, data);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
    }
}
