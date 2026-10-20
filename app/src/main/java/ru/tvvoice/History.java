package ru.tvvoice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Журнал распознанных фраз (+ошибок) в доступный adb-файл, для дотюнки словаря. */
public final class History {

    private static final String TAG = "ru.tvvoice";
    private static final int MAX_LINE = 4000; // обрезать лог, чтобы не дуть бесконечно

    private History() {}

    private static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, "history.log");
    }

    public static void append(Context ctx, String what) {
        try {
            File f = file(ctx);
            // ротация: если файл вырос — начинаем заново
            if (f.length() > MAX_LINE * 200) f.delete();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(new Date());
                w.write(ts + "  " + what + "\n");
            }
        } catch (Throwable t) {
            Log.w(TAG, "history append failed", t);
        }
    }
}
