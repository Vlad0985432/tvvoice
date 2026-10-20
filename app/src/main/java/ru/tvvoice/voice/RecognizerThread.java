package ru.tvvoice.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
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
    private boolean mScoUsed;
    private int mModeBefore;

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
        restoreRouting();
    }

    private void restoreRouting() {
        try {
            if (mScoUsed) {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                am.stopBluetoothSco();
                am.setBluetoothScoOn(false);
                am.setMode(mModeBefore);
                mScoUsed = false;
            }
        } catch (Throwable ignored) {}
    }

    private AudioRecord open(int source, AudioDeviceInfo prefer) {
        int min = AudioRecord.getMinBufferSize(RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord r = new AudioRecord(source, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, RATE * 2));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            try { r.release(); } catch (Throwable ignored) {}
            return null;
        }
        if (prefer != null) {
            try { r.setPreferredDevice(prefer); } catch (Throwable ignored) {}
        }
        return r;
    }

    private AudioDeviceInfo btMicInput() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return d;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private int samplePeak(AudioRecord r, long ms) {
        short[] buf = new short[RATE / 10];
        int peak = 0;
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            int n = r.read(buf, 0, buf.length);
            for (int i = 0; i < n; i++) {
                int a = Math.abs(buf[i]);
                if (a > peak) peak = a;
            }
        }
        return peak;
    }

    private String route(AudioRecord r) {
        try {
            AudioDeviceInfo d = r.getRoutedDevice();
            if (d == null) return "?";
            return typeName(d.getType()) + "/" + d.getProductName();
        } catch (Throwable t) { return "?"; }
    }

    private static String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "builtin";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bt_sco";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "telephony";
            default: return "type" + t;
        }
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

            mRec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, null);
            if (mRec == null) { cb.onError("микрофон недоступен"); return; }
            mRecognizer = new Recognizer(model, RATE);

            int peak = samplePeak(mRec, 500);
            String route1 = route(mRec);
            ru.tvvoice.History.append(ctx, "MIC  src=VR route=" + route1 + " peak=" + peak);
            Log.i(TAG, "mic probe route=" + route1 + " peak=" + peak);

            if (peak < 50) { // вход «мёртвый» — вероятно, записывается тишина ТВ-входа
                mRec.stop(); mRec.release(); mRec = null;
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                AudioDeviceInfo bt = btMicInput();
                if (bt != null) {
                    mModeBefore = am.getMode();
                    try {
                        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        am.startBluetoothSco();
                        am.setBluetoothScoOn(true);
                    } catch (Throwable ignored) {}
                    Thread.sleep(1400);
                    AudioRecord rec2 = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, bt);
                    if (rec2 == null) rec2 = open(MediaRecorder.AudioSource.MIC, bt);
                    if (rec2 != null) {
                        mRec = rec2; mScoUsed = true;
                        int peak2 = samplePeak(mRec, 400);
                        String route2 = route(mRec);
                        ru.tvvoice.History.append(ctx, "MIC2 src=SCO route=" + route2 + " peak=" + peak2);
                        Log.i(TAG, "mic probe2 route=" + route2 + " peak=" + peak2);
                        if (peak2 < 50) { // SCO тоже тишина — вернём путь 1
                            stopScoOnly();
                            mRec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, null);
                            if (mRec == null) { cb.onError("микрофон недоступен"); return; }
                        }
                    } else {
                        stopScoOnly();
                        mRec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, null);
                        if (mRec == null) { cb.onError("микрофон недоступен"); return; }
                    }
                } else {
                    mRec = open(MediaRecorder.AudioSource.VOICE_RECOGNITION, null);
                    if (mRec == null) { cb.onError("микрофон недоступен"); return; }
                }
            }
            mRec.startRecording();

            long started = System.currentTimeMillis();
            while (!mAborted && System.currentTimeMillis() - started < MAX_RUN_MS) {
                int n = mRec.read(buf(), 0, 1600);
                if (n <= 0) continue;
                if (mRecognizer.acceptWaveForm(buf(), n)) {
                    String t = extract(mRecognizer.getResult());
                    if (!t.isEmpty()) {
                        Log.i(TAG, "final=" + t);
                        ru.tvvoice.History.append(ctx, "OK   " + t);
                        cb.onFinal(t); return;
                    }
                } else {
                    String p = extract(mRecognizer.getPartialResult());
                    if (!p.isEmpty()) cb.onPartial(p);
                }
            }
            String last = extract(mRecognizer.getPartialResult());
            if (!last.isEmpty()) {
                Log.i(TAG, "final(timeout)=" + last);
                ru.tvvoice.History.append(ctx, "TIMEOUT " + last);
                cb.onFinal(last); return;
            }
            ru.tvvoice.History.append(ctx, "SILENCE /");
            cb.onError("не слышу команду");
        } catch (Throwable t) {
            Log.e(TAG, "recognizer died", t);
            cb.onError("Ошибка: " + t.getMessage());
        } finally {
            stopNative();
        }
    }

    private void stopScoOnly() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            am.stopBluetoothSco();
            am.setBluetoothScoOn(false);
            am.setMode(mModeBefore);
        } catch (Throwable ignored) {}
    }

    private short[] sBuf;
    private short[] buf() {
        if (sBuf == null) sBuf = new short[1600];
        return sBuf;
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
