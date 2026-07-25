package com.jarvis.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Locale;

/**
 * J.A.R.V.I.S. – native Android-Hülle.
 * Zeigt die eigenständige Web-Oberfläche (assets/index.html) in einem WebView und
 * stellt eine native Sprachbrücke bereit: android.speech.SpeechRecognizer (Zuhören)
 * und TextToSpeech (Vorlesen) – erreichbar aus JavaScript über "AndroidVoice".
 */
public class MainActivity extends AppCompatActivity {

    private WebView web;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, 1);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

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

    /** Ruft eine JS-Funktion im WebView auf (immer auf dem Main-Thread). */
    private void jsCall(final String js) {
        main.post(() -> web.evaluateJavascript(js, null));
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }

    /** Von JavaScript aufrufbar: AndroidVoice.speak/startListening/stopListening. */
    private class VoiceBridge {
        @JavascriptInterface
        public void speak(final String text) {
            main.post(() -> {
                if (tts != null)
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
            });
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
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
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
