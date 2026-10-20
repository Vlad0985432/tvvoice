package ru.tvvoice.voice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/** Клик по тогглу ByeByeDPI и GLOBAL_ACTION_HOME для «закрой». */
public class VoxAccessibility extends AccessibilityService {

    private static final String TAG = "ru.tvvoice";
    private static final String DPI_PKG = "io.github.romanvht.byedpi";
    private static final String BTN = "io.github.romanvht.byedpi:id/status_button_card";
    private static final String STATUS = "io.github.romanvht.byedpi:id/status_text";

    private static volatile VoxAccessibility sInstance;
    private static volatile long sLastVoiceKeyMs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "accessibility connected");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && (code == android.view.KeyEvent.KEYCODE_VOICE_ASSIST   // 231
                 || code == android.view.KeyEvent.KEYCODE_ASSIST)) {     // 219
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sLastVoiceKeyMs > 1500) {
                sLastVoiceKeyMs = now;
                Log.i(TAG, "voice key captured: " + code);
                Intent i = new Intent(this, ru.tvvoice.AssistActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            }
            return true; // съедаем событие, чтобы не открывался katniss
        }
        return false;
    }

    @Override public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public boolean onUnbind(Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    public static boolean ready() { return sInstance != null; }

    /** "connected" / "disconnected" / null. Пытается до timeoutMs, запуская приложение. */
    public static String dpiStatus(long timeoutMs) {
        VoxAccessibility svc = sInstance;
        if (svc == null) return null;
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean launched = false;
        while (System.currentTimeMillis() < deadline) {
            String st = svc.readStatus();
            if (st != null) {
                st = st.toLowerCase(java.util.Locale.ROOT);
                if (st.contains("подключен")) return "connected";
                if (st.contains("отключен")) return "disconnected";
            }
            if (!launched) {
                Intent i = svc.getPackageManager().getLaunchIntentForPackage(DPI_PKG);
                if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); svc.startActivity(i); }
                launched = true;
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private String readStatus() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(STATUS);
            if (list == null || list.isEmpty()) return null;
            CharSequence t = list.get(0).getText();
            return t == null ? "" : t.toString();
        } catch (Throwable t) {
            Log.e(TAG, "readStatus", t);
            return null;
        }
    }

    /** true если клик выполнен; ждёт появления ноды до timeoutMs. */
    public static boolean clickDpiToggle(long timeoutMs) {
        VoxAccessibility svc = sInstance;
        if (svc == null) return false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (svc.tryClick()) return true;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private boolean tryClick() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(BTN);
            if (list == null || list.isEmpty()) return false;
            return list.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            Log.e(TAG, "tryClick", t);
            return false;
        }
    }

    public static boolean goHome() {
        VoxAccessibility svc = sInstance;
        if (svc == null) return false;
        return svc.performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
