package ru.tvvoice;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.content.pm.PackageManager;

public class MainActivity extends Activity implements Engine.Reporter {

    private TextView mOut;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // разогрев модели: процесс висит дальше, кнопка микрофона откроется мгновенно
        ModelProvider.preloadAsync(this);
        View v = getLayoutInflater().inflate(R.layout.session, null);
        setContentView(v);
        mOut = v.findViewById(R.id.result);
        v.findViewById(R.id.status).setVisibility(View.GONE);

        String test = getIntent().getStringExtra("text");
        if (test == null) {
            String tb64 = getIntent().getStringExtra("tb64");
            if (tb64 != null) test = new String(android.util.Base64.decode(tb64, android.util.Base64.DEFAULT));
        }
        if (test != null) {
            final String t = test;
            new Thread(() -> Engine.execute(MainActivity.this, t, MainActivity.this), "tvvoice-exec").start();
            return;
        }
        Vlog();
    }

    private void cmd(String label, boolean ok, String... errs) {
        StringBuilder b = new StringBuilder();
        b.append(label).append(": ").append(ok ? "OK" : "нет");
        if (!ok) for (String e : errs) b.append('\n').append(e);
        mOut.append(b);
        mOut.append("\n\n");
    }

    private void Vlog() {
        StringBuilder b = new StringBuilder("Статус Голос ТВ\n\n");
        b.append("Модель VOSK: ").append(
                ModelProvider.modelAvailable(this) ? "встроена, распаковывается при первом старте" : "НЕТ в AP")
         .append('\n');
        b.append("Микрофон: ").append(checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ? "разрешён" : "НЕТ\n  adb shell pm grant ru.tvvoice android.permission.RECORD_AUDIO").append('\n');
        String asst = Settings.Secure.getString(getContentResolver(), "assistant");
        b.append("Assistant: ").append(asst == null ? "не назначен" : asst);
        if (asst == null || !asst.contains("ru.tvvoice"))
            b.append("\n  adb shell settings put secure assistant ru.tvvoice/ru.tvvoice.voice.VoxService");
        b.append('\n');
        String vi = Settings.Secure.getString(getContentResolver(), "voice_interaction_service");
        b.append("voice_interaction: ").append(vi == null ? "не назначен" : vi);
        if (vi == null || !vi.contains("ru.tvvoice"))
            b.append("\n  adb shell settings put secure voice_interaction_service ru.tvvoice/ru.tvvoice.voice.VoxService");
        b.append('\n');
        String as = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        b.append("Accessibility: ").append(as == null ? "выключен" : as);
        if (as == null || !as.contains("ru.tvvoice"))
            b.append("\n  adb shell settings put secure enabled_accessibility_services ru.tvvoice/ru.tvvoice.voice.VoxAccessibility");
        if (as == null || !as.contains("ru.tvvoice"))
            b.append("\n  adb shell settings put secure accessibility_enabled 1");
        b.append("\n\nГолос: скажите после кнопки микрофона:\n")
         .append(" включи/выключи бай дпи\n")
         .append(" открой ютуб/кинопоиск/браузер/хэпп\n")
         .append(" найди фильм <название>\n")
         .append(" тест без голоса:\n")
         .append(" adb shell am start ru.tvvoice/.MainActivity --es text \"открой ютуб\"");
        mOut.setText(b.toString());
    }

    @Override public void show(String text) {
        if (mOut != null) mOut.setText(text + "\n\n" + mOut.getText());
    }
}
