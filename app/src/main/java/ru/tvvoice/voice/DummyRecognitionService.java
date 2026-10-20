package ru.tvvoice.voice;

import android.content.Intent;
import android.speech.RecognitionService;

/** Минимальный RecognitionService: система требует его presence, распознаём сами через Vosk. */
public class DummyRecognitionService extends RecognitionService {

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        try { callback.error(1); } catch (Throwable ignored) {} // ERROR_NETWORK_TIMEOUT=no? error(int) - просто отказ
    }

    @Override
    protected void onCancel(Callback callback) {}

    @Override
    protected void onStopListening(Callback callback) {}
}
