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
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.SmsManager;
import android.webkit.GeolocationPermissions;
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

    private WebView web;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final String[] PERMS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

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

        web.loadUrl("file:///android_asset/index.html");
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
                if (p != null && !p.exists() && !p.mkdirs()) return "Ordner nicht anlegbar: " + p;
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
                for (ApplicationInfo a : pm.getInstalledApplications(0)) {
                    Intent i = pm.getLaunchIntentForPackage(a.packageName);
                    if (i == null) continue;
                    String label = String.valueOf(pm.getApplicationLabel(a)).toLowerCase(Locale.ROOT);
                    if (label.contains(q) || a.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return "Starte " + pm.getApplicationLabel(a) + ", Meister.";
                    }
                }
                return "App nicht gefunden: " + query;
            } catch (Exception e) { return "Fehler: " + e; }
        }

        // --- Telefon -------------------------------------------------------
        @JavascriptInterface
        public String call(String number) {
            try {
                Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "Rufe " + number + " an, Meister.";
            } catch (SecurityException e) {
                return "Anruf-Berechtigung fehlt.";
            } catch (Exception e) { return "Fehler: " + e; }
        }

        @JavascriptInterface
        public String sendSms(String number, String text) {
            try {
                SmsManager sm = Build.VERSION.SDK_INT >= 31
                        ? getSystemService(SmsManager.class)
                        : SmsManager.getDefault();
                sm.sendTextMessage(number, null, text, null, null);
                return "SMS an " + number + " gesendet, Meister.";
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
