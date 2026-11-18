package com.scriptprep.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Premium: listens while the actor says a line so the rehearsal can move on by itself. */
final class HandsFree {
    interface Listener {
        void onListening();

        void onPartial(String text);

        void onHeard(String text);

        /** Nothing recognisable was said before the recogniser gave up. */
        void onSilence();

        void onFailure(boolean permanent);
    }

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private final Context context;
    private SpeechRecognizer recognizer;
    private Listener listener;

    HandsFree(Context context) {
        this.context = context;
    }

    static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    void start(Listener target) {
        stop();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        }
        listener = target;
        recognizer.setRecognitionListener(new Callbacks(target));
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException error) {
            listener = null;
            target.onFailure(false);
        }
    }

    boolean isListening() {
        return listener != null;
    }

    void stop() {
        listener = null;
        if (recognizer != null) {
            recognizer.cancel();
        }
    }

    void destroy() {
        stop();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    /** Share of the expected words that were heard, ignoring case, accents and order. */
    static double coverage(String expected, String heard) {
        List<String> wanted = words(expected);
        if (wanted.isEmpty()) {
            return 1;
        }
        Map<String, Integer> available = new HashMap<>();
        for (String word : words(heard)) {
            Integer count = available.get(word);
            available.put(word, count == null ? 1 : count + 1);
        }
        int matched = 0;
        for (String word : wanted) {
            Integer count = available.get(word);
            if (count != null && count > 0) {
                available.put(word, count - 1);
                matched++;
            }
        }
        return matched / (double) wanted.size();
    }

    private static List<String> words(String text) {
        String folded = Normalizer.normalize(text.toLowerCase(TURKISH), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").replace('ı', 'i');
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(folded);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private final class Callbacks implements RecognitionListener {
        private final Listener target;

        Callbacks(Listener target) {
            this.target = target;
        }

        private boolean current() {
            return listener == target;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            if (current()) {
                target.onListening();
            }
        }

        @Override
        public void onBeginningOfSpeech() {}

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {}

        @Override
        public void onError(int error) {
            if (!current()) {
                return;
            }
            listener = null;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> target.onSilence();
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> target.onFailure(true);
                default -> target.onFailure(false);
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (!current()) {
                return;
            }
            listener = null;
            String best = first(results);
            if (best.isEmpty()) {
                target.onSilence();
            } else {
                target.onHeard(best);
            }
        }

        @Override
        public void onPartialResults(Bundle results) {
            if (current()) {
                target.onPartial(first(results));
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}

        private String first(Bundle results) {
            ArrayList<String> texts = results == null ? null
                : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return texts == null || texts.isEmpty() || texts.get(0) == null ? "" : texts.get(0);
        }
    }
}
