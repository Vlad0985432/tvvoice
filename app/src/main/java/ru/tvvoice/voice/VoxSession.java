package ru.tvvoice.voice;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.service.voice.VoiceInteractionSession;

import ru.tvvoice.Engine;
import ru.tvvoice.ModelProvider;
import ru.tvvoice.R;

/** Сессия ассистента: рисуется системным слоем, слушает микрофон, исполняет команду. */
public class VoxSession extends VoiceInteractionSession {

    private static final String TAG = "ru.tvvoice";

    private View mRoot;
    private TextView mStatus;
    private TextView mResult;
    private RecognizerThread mThread;

    public VoxSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        mRoot = LayoutInflater.from(getContext()).inflate(R.layout.session, null);
        mStatus = mRoot.findViewById(R.id.status);
        mResult = mRoot.findViewById(R.id.result);
        return mRoot;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        startListening();
    }

    @Override
    public void onHide() {
        super.onHide();
        stopListening();
    }

    public void startListening() {
        stopListening();
        if (!permissionCheck()) return;
        status("Говорите…");
        mThread = new RecognizerThread(getContext(), new RecognizerThread.Listener() {
            @Override public void onReady() {}
            @Override public void onPartial(String t) { result(t); }
            @Override public void onFinal(String t) {
                result(t);
                Engine.execute(getContext(), t, tt -> status(tt));
                try { Thread.sleep(1600); } catch (InterruptedException ignored) {}
                hide();
            }
            @Override public void onError(String what) {
                result(what);
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                hide();
            }
        });
        mThread.start();
    }

    public void stopListening() {
        if (mThread != null) { mThread.abort(); mThread = null; }
    }

    private boolean permissionCheck() {
        Context c = getContext();
        if (!ModelProvider.modelAvailable(c)) {
            status("Модель речи не установлена");
            return false;
        }
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status("Нет доступа к микрофону:\nadb shell pm grant ru.tvvoice android.permission.RECORD_AUDIO");
            return false;
        }
        return true;
    }

    private void status(String t) {
        if (mStatus != null && mRoot != null) mRoot.post(() -> mStatus.setText(t));
    }

    private void result(String t) {
        if (mResult != null && mRoot != null) mRoot.post(() -> mResult.setText(t));
    }
}
