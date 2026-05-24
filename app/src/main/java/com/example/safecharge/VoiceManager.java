package com.example.safecharge;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

public class VoiceManager {

    private TextToSpeech tts;
    private boolean ready = false;

    public VoiceManager(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int res = tts.setLanguage(Locale.US);
                ready = (res != TextToSpeech.LANG_MISSING_DATA &&
                        res != TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });
    }

    public void speak(String text) {
        if (ready && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "CHARGE_TTS");
        }
    }

    public void release() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}