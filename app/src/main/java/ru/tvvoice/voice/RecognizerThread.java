package ru.tvvoice.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import ru.tvvoice.ModelProvider;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Один прогон: AudioRecord → vosk → колбэки. */
public class RecognizerThread extends Thread {

    public interface Listener {
        void onReady();
        void onPartial(String text);
        void onFinal(String text);
        void onError(String what);
    }

    private static final String TAG = "ru.tvvoice";
    private static final int RATE = 16000;
    private static final long MAX_RUN_MS = 10_000;

    private final Context ctx;
    private final Listener cb;
    private volatile AudioRecord mRec;
    private volatile Recognizer mRecognizer;
    private volatile boolean mAborted;

    public RecognizerThread(Context ctx, Listener cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    public void abort() {
        mAborted = true;
        try { stopNative(); } catch (Throwable ignored) {}
    }

    private void stopNative() {
        try { if (mRecognizer != null) mRecognizer.close(); } catch (Throwable ignored) {}
        try { if (mRec != null) { mRec.stop(); mRec.release(); } } catch (Throwable ignored) {}
        mRecognizer = null; mRec = null;
    }

    @Override
    public void run() {
        try {
            Model model = ModelProvider.get();
            if (model == null) {
                cb.onPartial("Первая загрузка, подождите…");
                ModelProvider.loadModel(ctx.getApplicationContext());
                model = ModelProvider.get();
            }
            if (model == null) { cb.onError("модель не загрузилась"); return; }
            cb.onReady();
            if (mAborted) return;

            int min = AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mRec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, RATE * 2));
            if (mRec.getState() != AudioRecord.STATE_INITIALIZED) {
                cb.onError("микрофон недоступен");
                return;
            }
            mRecognizer = new Recognizer(model, RATE);
            short[] buf = new short[RATE / 10];
            mRec.startRecording();

            long started = System.currentTimeMillis();
            while (!mAborted && System.currentTimeMillis() - started < MAX_RUN_MS) {
                int n = mRec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (mRecognizer.acceptWaveForm(buf, n)) {
                    String t = extract(mRecognizer.getResult());
                    if (!t.isEmpty()) { Log.i(TAG, "final=" + t); cb.onFinal(t); return; }
                } else {
                    String p = extract(mRecognizer.getPartialResult());
                    if (!p.isEmpty()) cb.onPartial(p);
                }
            }
            String last = extract(mRecognizer.getPartialResult());
            if (!last.isEmpty()) { cb.onFinal(last); return; }
            cb.onError("не слышу команду");
        } catch (Throwable t) {
            Log.e(TAG, "recognizer died", t);
            cb.onError("Ошибка: " + t.getMessage());
        } finally {
            stopNative();
        }
    }

    private static String extract(String json) {
        if (json == null) return "";
        int i = json.indexOf("\"text\"");
        if (i < 0) return "";
        int colon = json.indexOf(':', i + 6);
        if (colon < 0) return "";
        int a = json.indexOf('"', colon + 1);
        int b = json.indexOf('"', a + 1);
        if (a < 0 || b < 0 || b <= a) return "";
        return json.substring(a + 1, b);
    }
}
