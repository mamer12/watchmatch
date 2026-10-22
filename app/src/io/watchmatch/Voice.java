package io.watchmatch;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Voice search inside the app: the TV's own recognition service (SpeechRecognizer; Katniss on Google TV) with no
 * window of its own over the search box. What it has heard so far, then the sentence, go to the Listener, and
 * Chime's sounds mark the start, the end and a stop. {app} keeps no audio. Main thread only.
 */
final class Voice implements RecognitionListener {
    interface Listener {
        /** The words so far (partial results), while listening. */
        void heard(String soFar);

        /** The sentence: listening is over. */
        void said(String sentence);

        /** Over without a sentence: `why` for the box, or null when it was cancelled. */
        void stopped(String why);
    }

    private final Context context;
    private final Listener listener;
    private final Chime chime;
    private SpeechRecognizer recognizer;
    private boolean listening;

    Voice(Context c, Listener l) {
        context = c;
        listener = l;
        chime = new Chime(c);
    }

    static boolean available(Context c) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(c);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A language picked in the settings; null on "auto": the recognizer's own (the TV's, which may be Arabic). */
    static String language() {
        return "auto".equals(Prefs.language()) ? null : Ui.locale().stripExtensions().toLanguageTag();
    }

    boolean listening() {
        return listening;
    }

    /** Needs RECORD_AUDIO. */
    void start() {
        if (listening) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        String lang = language();
        if (lang != null) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        listening = true;
        chime.start();
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
            }
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            AppLog.w("voice search: " + e.getMessage());
            onError(SpeechRecognizer.ERROR_CLIENT);
        }
    }

    /** Back, OK or the mic again: stops without searching. */
    void cancel() {
        if (!listening) return;
        listening = false;
        if (recognizer != null) recognizer.cancel();
        chime.stop();
        listener.stopped(null);
    }

    void release() {
        listening = false;
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        chime.release();
    }

    @Override public void onPartialResults(Bundle b) {
        String s = first(b);
        if (listening && s != null) listener.heard(s);
    }

    @Override public void onResults(Bundle b) {
        if (!listening) return;
        String s = first(b);
        if (s == null) {
            onError(SpeechRecognizer.ERROR_NO_MATCH);
            return;
        }
        listening = false;
        chime.done();
        listener.said(s);
    }

    @Override public void onError(int error) {
        if (!listening) return;
        listening = false;
        AppLog.w("voice search: error " + error);
        chime.stop();
        listener.stopped(message(error));
    }

    @Override public void onReadyForSpeech(Bundle b) {}

    @Override public void onBeginningOfSpeech() {}

    @Override public void onRmsChanged(float db) {}

    @Override public void onBufferReceived(byte[] buffer) {}

    @Override public void onEndOfSpeech() {}

    @Override public void onEvent(int type, Bundle b) {}

    private static String first(Bundle b) {
        ArrayList<String> said = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (said == null) return null;
        for (String s : said) if (s != null && !s.trim().isEmpty()) return s.trim();
        return null;
    }

    static String micNeeded() {
        return Ui.tr("Permita o microfone para buscar por voz.", "Allow the microphone to search by voice.",
                "Permite el micrófono para buscar por voz.");
    }

    private static String message(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return Ui.tr("Não entendi. Tente de novo.", "Didn't catch that. Try again.", "No te entendí. Inténtalo de nuevo.");
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return Ui.tr("A busca por voz precisa de internet.", "Voice search needs the internet.",
                        "La búsqueda por voz necesita internet.");
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return Ui.tr("O microfone está ocupado. Tente de novo.", "The microphone is busy. Try again.",
                        "El micrófono está ocupado. Inténtalo de nuevo.");
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return micNeeded();
            default:
                return Ui.tr("A busca por voz parou. Tente de novo.", "Voice search stopped. Try again.",
                        "La búsqueda por voz se detuvo. Inténtalo de nuevo.");
        }
    }
}
