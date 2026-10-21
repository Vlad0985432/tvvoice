package ru.tvvoice.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final int LIVE_PEAK = 200; // уровень, при котором считаем вход живым

    private final Context ctx;
    private final Listener cb;
    private volatile AudioRecord mRec;
    private volatile Recognizer mRecognizer;
    private volatile boolean mAborted;
    private boolean mScoUsed;
    private int mModeBefore;
    private BroadcastReceiver mScoRx;
    private short[] sBuf;

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
        try { if (mScoRx != null) { ctx.unregisterReceiver(mScoRx); mScoRx = null; } } catch (Throwable ignored) {}
    }

    private class ScoReceiver extends BroadcastReceiver {
        int state = -1;
        @Override public void onReceive(Context c, Intent i) {
            state = i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            Log.i(TAG, "sco state=" + state);
        }
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

    /** Выбор устройства входа: SCO-тип, либо «Xiaomi RC», либо что угодно не builtin. */
    private AudioDeviceInfo preferScoDevice() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo wired = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return d;
                String n = String.valueOf(d.getProductName());
                if (n.contains("RC")) wired = d;
            }
            return wired;
        } catch (Throwable t) { return null; }
    }

    private int samplePeak(AudioRecord r, long ms) {
        try { r.startRecording(); } catch (Throwable ignored) {}
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
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired_hp";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "telephony";
            default: return "type" + t;
        }
    }

    /** Подбирает живой вход: сначала обычные источники, затем Bluetooth SCO. */
    private AudioRecord pickWorkingRecording() {
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
        };
        AudioRecord best = null;
        int bestPeak = 0;

        for (int src : sources) {
            AudioRecord r = open(src, null);
            if (r == null) continue;
            int p = samplePeak(r, 400);
            String rt = route(r);
            Log.i(TAG, "mic probe src=" + src + " route=" + rt + " peak=" + p);
            ru.tvvoice.History.append(ctx, "MIC  src=" + src + " route=" + rt + " peak=" + p);
            if (best != null) { try { best.stop(); best.release(); } catch (Throwable ignored) {} }
            best = r; bestPeak = p;
            if (p > LIVE_PEAK) return best;
        }

        // Все входы «мертвые» — включаем Bluetooth SCO (пуль передаёт микрофон через него)
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            ScoReceiver rx = new ScoReceiver();
            mScoRx = rx;
            try { ctx.registerReceiver(rx, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)); } catch (Throwable ignored) {}
            int beforeMode = am.getMode();
            try {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            } catch (Throwable ignored) {}
            try { Thread.sleep(1600); } catch (InterruptedException e) { return best; }
            AudioDeviceInfo prefer = preferScoDevice();

            for (int src : sources) {
                AudioRecord r = open(src, prefer);
                if (r == null) continue;
                int p = samplePeak(r, 350);
                String rt = route(r);
                Log.i(TAG, "mic probe2 src=" + src + " sco=" + rx.state + " route=" + rt + " peak=" + p);
                ru.tvvoice.History.append(ctx, "MIC2 src=" + src + " sco=" + rx.state + " route=" + rt + " peak=" + p);
                if (best != null) { try { best.stop(); best.release(); } catch (Throwable ignored) {} }
                best = r;
                if (p > bestPeak) {
                    bestPeak = p;
                    if (!mScoUsed) { mScoUsed = true; mModeBefore = beforeMode; }
                }
                if (p > LIVE_PEAK) return best;
            }
            if (!mScoUsed) { // SCO не дал ничего — гасим
                try {
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                    am.setMode(beforeMode);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.e(TAG, "sco probe failed", t);
        }
        return best;
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

            mRecognizer = new Recognizer(model, RATE);
            mRec = pickWorkingRecording();
            if (mRec == null) { cb.onError("микрофон недоступен"); return; }

            long started = System.currentTimeMillis();
            while (!mAborted && System.currentTimeMillis() - started < MAX_RUN_MS) {
                int n = mRec.read(buf(), 0, buf().length);
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
