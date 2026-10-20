package ru.tvvoice;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import ru.tvvoice.voice.RecognizerThread;

/** План B кнопки микрофона: обычная активность с тем же распознаванием. */
public class AssistActivity extends Activity implements Engine.Reporter {

    private TextView mStatus, mResult;
    private RecognizerThread mThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View v = LayoutInflater.from(this).inflate(R.layout.session, null);
        setContentView(v);
        mStatus = v.findViewById(R.id.status);
        mResult = v.findViewById(R.id.result);
        start("");
    }

    private void start(String hint) {
        if (mStatus != null) mStatus.setText("Говорите…");
        stop();
        mThread = new RecognizerThread(this, new RecognizerThread.Listener() {
            @Override public void onReady() {}
            @Override public void onPartial(String t) { ui(() -> { if (mResult != null) mResult.setText(t); }); }
            @Override public void onFinal(String t) {
                ui(() -> {
                    if (mResult != null) mResult.setText(t);
                    Engine.execute(AssistActivity.this, t, AssistActivity.this);
                });
            }
            @Override public void onError(String what) {
                ui(() -> { if (mResult != null) mResult.setText(what); });
            }
        });
        mThread.start();
    }

    private void ui(Runnable r) { runOnUiThread(r); }

    @Override public void show(String text) {
        ui(() -> { if (mStatus != null) mStatus.setText(text); });
    }

    private void stop() {
        if (mThread != null) { mThread.abort(); mThread = null; }
    }

    @Override protected void onPause() { stop(); super.onPause(); }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        stop();
        finish();
    }
}
