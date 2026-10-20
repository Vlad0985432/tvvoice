package ru.tvvoice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipInputStream;

public final class ModelProvider {
    private static final String TAG = "ru.tvvoice";
    public static final String MODEL_ASSET = "model-small-ru-0.22.zip";
    private static final Object LOCK = new Object();
    private static volatile org.vosk.Model sModel;
    private static volatile Boolean sModelExists;

    private ModelProvider() {}

    /** Есть ли модель (распакованная или в assets). */
    public static boolean modelAvailable(Context ctx) {
        Boolean cached = sModelExists;
        if (cached != null) return cached;
        boolean ok = unpackedDir(ctx).exists();
        try {
            String[] assets = ctx.getAssets().list("");
            if (assets != null)
                for (String a : assets) if (MODEL_ASSET.equals(a)) { ok = true; break; }
        } catch (IOException ignored) {}
        sModelExists = ok;
        return ok;
    }

    public static File unpackedDir(Context ctx) {
        return new File(ctx.getFilesDir(), "model-small-ru-0.22");
    }

    /** Распаковать модель из assets во filesDir. Блокирует; вызывать из фонового потока. */
    public static synchronized void unpackIfNeeded(Context ctx) throws IOException {
        File dir = unpackedDir(ctx);
        if (dir.exists() && new File(dir, "am/final.mdl").exists()) return;
        File tmp = new File(ctx.getFilesDir(), "model.tmp");
        if (tmp.exists()) deleteRecursive(tmp);
        try (InputStream in = ctx.getAssets().open(MODEL_ASSET);
             ZipInputStream z = new ZipInputStream(in)) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                File out = new File(tmp, e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                File parent = out.getParentFile();
                if (parent != null && (!parent.isDirectory() && !parent.mkdirs()))
                    throw new IOException("bad path " + out);
                try (OutputStream fo = new FileOutputStream(out)) {
                    int n; while ((n = z.read(buf)) > 0) fo.write(buf, 0, n);
                }
                z.closeEntry();
            }
        }
        // в архиве один корневой каталог (вроде vosk-model-small-ru-0.22/) — спуститься в него
        File root = tmp;
        while (root.isDirectory()) {
            File[] kids = root.listFiles();
            if (kids == null || kids.length == 0) break;
            if (kids.length == 1 && kids[0].isDirectory()) { root = kids[0]; continue; }
            break;
        }
        File target = unpackedDir(ctx);
        if (target.exists()) deleteRecursive(target);
        if (!root.renameTo(target)) throw new IOException("rename failed");
        deleteRecursive(tmp);
        Log.i(TAG, "model unpacked");
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    /** Загрузить модель в память (медленно, 1–3 c). Блокирует; из фонового потока. */
    public static void loadModel(Context ctx) {
        synchronized (LOCK) {
            if (sModel != null) return;
            try {
                unpackIfNeeded(ctx);
                sModel = new org.vosk.Model(unpackedDir(ctx).getAbsolutePath());
                Log.i(TAG, "model loaded");
            } catch (Throwable t) {
                Log.e(TAG, "model load failed", t);
            }
        }
    }

    public static void preloadAsync(Context ctx) {
        new Thread(() -> loadModel(ctx.getApplicationContext()), "tvvoice-model").start();
    }

    public static org.vosk.Model get() { return sModel; }
}
