package ru.nova.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String NAME = "Нова";                       // имя ассистента
    private static final String BRAIN_URL = "http://127.0.0.1:8765"; // мозг в Termux
    private static final int MIC_REQUEST = 1;

    private TextToSpeech tts;          // «рот»
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer; // «уши»
    private ScrollView scroll;
    private TextView log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); // работает даже на заблокированном экране
        }
        buildScreen();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("ru", "RU"));
                ttsReady = true;
            }
        });

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new Listener());

        startListening(); // открылись — сразу слушаем
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startListening(); // снова нажали на наушник — снова слушаем
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        if (ttsReady) tts.stop(); // замолкаем, чтобы не слушать саму себя
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"); // русский язык прямо в коде!
        recognizer.cancel();
        recognizer.startListening(intent);
        show("🎤 Слушаю...");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == MIC_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            show("Без доступа к микрофону я тебя не услышу.");
        }
    }

    // Отправляем вопрос мозгу в отдельном потоке, чтобы экран не зависал
    private void askBrain(String question) {
        show("🧠 Думаю...");
        new Thread(() -> {
            String answer;
            try {
                answer = post(question);
            } catch (Exception e) {
                answer = "Мой мозг не отвечает. Запусти brain_server.py в Termux.";
            }
            final String text = answer;
            runOnUiThread(() -> say(text));
        }).start();
    }

    // Обычный интернет-запрос к серверу brain_server.py
    private String post(String question) throws Exception {
        byte[] body = question.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(BRAIN_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(60000);
            conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private void say(String text) {
        show(NAME + ": " + text);
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    private void show(String line) {
        log.append(line + "\n\n");
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // Простой экран: переписка и кнопка «Спросить»
    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 120, 48, 48);

        log = new TextView(this);
        log.setTextSize(18);
        scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button talk = new Button(this);
        talk.setText("🎤 Спросить");
        talk.setTextSize(20);
        talk.setOnClickListener(v -> startListening());
        root.addView(talk, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        recognizer.destroy();
        tts.shutdown();
        super.onDestroy();
    }

    // Что делать, когда распознавание речи что-то услышало
    private class Listener implements RecognitionListener {
        @Override
        public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) {
                say("Я не расслышала, повтори, пожалуйста.");
                return;
            }
            String question = list.get(0);
            show("Ты: " + question);
            askBrain(question);
        }

        @Override
        public void onError(int error) {
            if (error == SpeechRecognizer.ERROR_CLIENT) return; // служебная, не мешает
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                say("Я не расслышала, повтори, пожалуйста.");
            } else {
                show("Ошибка распознавания, код " + error);
            }
        }

        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    }
}
