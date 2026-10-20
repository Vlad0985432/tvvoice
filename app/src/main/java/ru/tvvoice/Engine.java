package ru.tvvoice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import ru.tvvoice.voice.VoxAccessibility;

/** Разбор распознанной фразы и исполнение команд. */
public final class Engine {

    public interface Reporter { void show(String text); }

    private static final String TAG = "ru.tvvoice";
    public static final String BYEDPI_PKG = "io.github.romanvht.byedpi";
    public static final String TVBRO_PKG = "com.phlox.tvwebbrowser";

    /** Чётная строка — синонимы (как может их услышать vosk-small-ru), нечётная — пакет. */
    private static final String[][] APPS = {
        { "ютуб", "юттуб", "ютыб", "ю туб" },
        { "com.google.android.youtube.tv" },
        { "браузер", "броузер", "бразуер", "тв бро" },
        { TVBRO_PKG },
        { "кинопоиск", "кино поист", "кинопоист" },
        { "ru.kinopoisk.tv" },
        { "иви", "айви" },
        { "ru.ivi.client" },
        { "твизавр", "твзавр" },
        { "com.tvzavr.android.player.free" },
        { "смотрешка", "смтрешка", "смотрелка" },
        { "tv.lfstrm.smotreshka" },
        { "хэпп", "хепп", "хэпи", "х еп" },
        { "su.happ.proxyutility" },
        { "дпи", "бай дпи", "байдпи", "бай дипи", "buy dpi", "byedpi", "dpi" },
        { BYEDPI_PKG },
        { "настройки", "настройка", "настройик" },
        { "com.android.tv.settings" },
    };

    private Engine() {}

    /* ---------------- разбор ---------------- */

    static String norm(String u) {
        if (u == null) return "";
        String s = u.toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^а-яa-z0-9 ]+", " ")
                .trim();
        return s.replaceAll(" +", " ");
    }

    static int lev(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
        return d[a.length()][b.length()];
    }

    private static boolean startsAny(String tok, String... prefixes) {
        for (String p : prefixes) if (tok.startsWith(p)) return true;
        return false;
    }

    private static boolean isVerbOpen(String tok) {
        if (startsAny(tok, "закр", "выкл", "остан", "стоп", "гаси")) return false;
        if (tok.startsWith("включ") || tok.startsWith("вктлюч") || tok.startsWith("вьлюч")) return true;
        return startsAny(tok, "откр", "запус", "start", "олкр");
    }

    private static boolean isVerbClose(String tok) {
        return startsAny(tok, "закр", "выкл", "остан", "стоп", "гаси");
    }

    private static boolean isSearchMarker(String tok) {
        String[] v = {"найди", "найти", "поищи", "найдите", "поиск"};
        for (String s : v) if (lev(tok, s) <= 1) return true;
        return false;
    }

    private static boolean isFilmWord(String tok) {
        String[] v = {"фильм", "кино", "сериал", "филм", "фильмо"};
        for (String s : v) if (lev(tok, s) <= 1) return true;
        return false;
    }

    private static boolean isNoiseWord(String tok) {
        String[] v = {"и", "в", "на", "по", "видео"};
        for (String s : v) if (tok.equals(s)) return true;
        return false;
    }

    /** Пакет по фразе либо null. */
    public static String parseApp(String utterance) {
        String s = norm(utterance);
        if (s.isEmpty()) return null;
        String[] toks = s.split(" ");
        int start = 0;
        if (isVerbOpen(toks[start]) || isVerbClose(toks[start])) start++;
        while (start < toks.length && (isSearchMarker(toks[start]) || isFilmWord(toks[start])
                || toks[start].equals("и") || toks[start].equals("браузер") || toks[start].equals("в")))
            start++;
        if (start >= toks.length) return null;
        StringBuilder rest = new StringBuilder();
        for (int i = start; i < toks.length; i++) if (!isNoiseWord(toks[i])) rest.append(toks[i]);
        String packed = rest.toString();
        if (packed.isEmpty()) return null;

        int best = Integer.MAX_VALUE; String pkg = null;
        for (int g = 0; g < APPS.length; g += 2) {
            for (String syn : APPS[g]) {
                String synN = syn.replace(" ", "");
                int d = lev(packed, synN);
                int thr = Math.max(1, synN.length() / 4);
                if (d <= thr && d < best) { best = d; pkg = APPS[g + 1][0]; }
            }
        }
        if (pkg != null) return pkg;

        for (int g = 0; g < APPS.length; g += 2)
            for (String syn : APPS[g]) {
                String synN = syn.replace(" ", "");
                if (packed.length() < synN.length()) continue;
                String head = packed.substring(0, synN.length());
                if (lev(head, synN) <= Math.max(1, synN.length() / 4)) return APPS[g + 1][0];
            }
        return null;
    }

    public static boolean isClose(String utterance) {
        for (String t : norm(utterance).split(" ")) if (isVerbClose(t)) return true;
        return false;
    }

    public static boolean hasSearch(String utterance) {
        for (String t : norm(utterance).split(" ")) if (isSearchMarker(t)) return true;
        return false;
    }

    /** Название для поиска из фразы («открой браузер найди фильм дюна»). */
    public static String parseTitle(String utterance) {
        String[] toks = norm(utterance).split(" ");
        StringBuilder sb = new StringBuilder();
        boolean inSearch = false;
        for (String t : toks) {
            if (!inSearch) {
                if (isSearchMarker(t)) inSearch = true;
                continue;
            }
            if (isVerbOpen(t) || isVerbClose(t) || isNoiseWord(t) || isFilmWord(t)) {
                if (!sb.toString().trim().isEmpty()) break;
                continue;
            }
            sb.append(t).append(" ");
        }
        String title = sb.toString().trim();
        Log.d(TAG, "title=" + title);
        return title.isEmpty() ? null : title;
    }

    /* ---------------- исполнение ---------------- */

    public static void execute(Context c, String text, Reporter rep) {
        text = text == null ? "" : text;
        Log.i(TAG, "exec: " + text);
        String pkg = parseApp(text);
        boolean search = hasSearch(text);
        String title = search ? parseTitle(text) : null;

        if (search && title == null) {
            rep.show("Не разобрал название.\nСкажите: «найди фильм <название>»");
            return;
        }

        if (pkg != null && BYEDPI_PKG.equals(pkg)) {
            dpiToggle(c, rep, isClose(text));
            if (search && title != null) comeToSearch(c, title, rep);
            return;
        }

        if (pkg != null && !search) {
            openApp(c, pkg, rep);
            return;
        }

        if (search && title != null) {
            comeToSearch(c, title, rep);
            return;
        }

        rep.show("Не понял команду");
    }

    private static void comeToSearch(Context c, String title, Reporter rep) {
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        search(c, title, rep);
    }

    private static void dpiToggle(Context c, Reporter rep, boolean wantClose) {
        // служба доступности биндится асинхронно — даём до 3 с после старта процесса
        boolean acc = VoxAccessibility.ready();
        for (int i = 0; i < 10 && !acc; i++) {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            acc = VoxAccessibility.ready();
        }
        Log.i(TAG, "acc ready=" + acc);
        if (!acc) {
            rep.show("Служба доступности не активна — запускаю приложение");
            openApp(c, BYEDPI_PKG, rep);
            return;
        }
        String st = VoxAccessibility.dpiStatus(9000);
        Log.i(TAG, "dpi status=" + st + " (wantClose=" + wantClose + ")");
        if ("connected".equals(st)) {
            if (!wantClose) { rep.show("БайДПИ уже включён"); return; }
            act(rep, true);
            return;
        }
        if ("disconnected".equals(st)) {
            if (wantClose) { rep.show("БайДПИ уже выключен"); return; }
            act(rep, false);
            return;
        }
        rep.show("Не вижу ByeByeDPI — запускаю");
        openApp(c, BYEDPI_PKG, rep);
    }

    private static void act(Reporter rep, boolean closing) {
        boolean ok = VoxAccessibility.clickDpiToggle(5000);
        Log.i(TAG, "toggle click ok=" + ok);
        rep.show(ok ? (closing ? "Выключаю БайДПИ" : "Включаю БайДПИ") : "Клик не удался");
    }

    /* ---------------- действия ---------------- */

    public static void search(Context c, String title, Reporter rep) {
        try {
            String q = URLEncoder.encode(title + " смотреть онлайн", "UTF-8");
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://yandex.ru/search/?text=" + q));
            i.setPackage(TVBRO_PKG);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            rep.show("Ищу: " + title);
        } catch (Exception e) {
            Log.e(TAG, "search failed", e);
            rep.show("Ошибка поиска");
        }
    }

    public static void openApp(Context c, String pkg, Reporter rep) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { rep.show("Приложение не найдено: " + pkg); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        rep.show("Открываю " + label(pkg));
    }

    public static String label(String pkg) {
        if (BYEDPI_PKG.equals(pkg)) return "БайДПИ";
        switch (pkg) {
            case "com.google.android.youtube.tv": return "YouTube";
            case "ru.kinopoisk.tv": return "Кинопоиск";
            case "ru.ivi.client": return "IVI";
            case "tv.lfstrm.smotreshka": return "Смотрешка";
            case "com.tvzavr.android.player.free": return "TVzavr";
            case "su.happ.proxyutility": return "Happ";
            case "com.android.tv.settings": return "Настройки";
            default: return pkg;
        }
    }
}
