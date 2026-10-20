package ru.tvvoice.voice;

import android.content.Intent;
import android.service.voice.VoiceInteractionService;

import ru.tvvoice.ModelProvider;

public class VoxService extends VoiceInteractionService {

    // заранее грузим модель: кнопкой ждать 1–3 с нельзя
    @Override
    public void onReady() {
        super.onReady();
        ModelProvider.preloadAsync(this);
    }
}
