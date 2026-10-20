package ru.tvvoice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Locale;

import ru.tvvoice.voice.VoxAccessibility;

/**
 * Разбор и исполнение команд.
 * Принцип (по мотивам jarvis): нормализация → обрезка глаголов/маркеров → нечёткий
 * матчинг приложения по окнам из 1–3 токенов (Vosk часто искажает отдельные слова,
 * но почти всегда хотя бы одно окно узнаваемо).
 */
public final class Engine {

    public interface Reporter { void show(String text); }

    private static final String TAG = "ru.tvvoice";
    public static final String BYEDPI_PKG = "io.github.romanvht.byedpi";
    public static final String TVBRO_PKG = "com.phlox.tvwebbrowser";
    public static final String YOUTUBE_PKG = "com.google.android.youtube.tv";

    /** Чётная строка — синонимы (что может выдать vosk), нечётная — пакет. */
    private static final String[][] APPS = {
        { "ютуб", "ютюб", "ю туб", "ютубе", "ютube", "юту сделать", "ютновь", "и тут", "ю пуст", "ютy бюджет" },
        { YOUTUBE_PKG },
        { "браузер", "брауser", "броузер", "бразуер", "браусер", "браузр", "б редактор", "брузер", "тв бро" },
        { TVBRO_PKG },
        { "кинопоиск", "кинопоист", "кинопо", "кино поиск" },
        { "ru.kinopoisk.tv" },
        { "иви", "айви", "ivi" },
        { "ru.ivi.client" },
        { "твизавр", "твзавр", "твизлр", "tvzavr" },
        { "com.tvzavr.android.player.free" },
        { "смотрешка", "смтрешка", "смотрелка", "сморешка" },
        { "tv.lfstrm.smotreshka" },
        { "хэпп", "хепп", "хэпи", "хеп", "х епп", "хепи" },
        { "su.happ.proxyutility" },
        { "дпи", "бай дпи", "байдпи", "бей дпи", "dpi", "dpи", "бай пицца", "д бил", "ди прайс" },
        { BYEDPI_PKG },
        { "настройки", "настройка", "настройк" },
        { "com.android.tv.settings" },
    };

    private Engine() {}

    /* ---------------- глаголы/маркеры ---------------- */

    private static final String[] V_OPEN = {
        "открой", "открыть", "открыт", "открыв", "откри", "олкри",
        "запусти", "запуск", "запуск", "запуст",
        "включи", "включить", "включа", "включение", "вкл", "включ",
    };
    private static final String[] V_CLOSE = {
        "закрой", "закрыть", "закрыва", "выключи", "выключить", "выключа",
        "остан", "останов", "стоп", "гаси", "выруб", "заверши", "закр",
    };
    private static final String[] V_SEARCH = {
        "найди", "найти", "поищи", "поищи", "найдите", "поиск", "найдет",
        "найдет", "найдит", "найде", "нади", "найдём", "найдемт", "найде",
        "искать", "ищи", "ищем", "искать",
    };
    private static final String[] V_FILM = {
        "фильм", "фильм", "кино", "сериал", "фильмо", "фильм",
    };
    private static final String[] NOISE = {
        "и", "в", "на", "по", "к", "для", "пожалуйста", "пожалуйста", "ну", "нет", "то", "это",
    };

    /* ---------------- утилиты ---------------- */

    static String norm(String u) {
        if (u == null) return "";
        String s = u.toLowerCase(Locale.ROOT).replace('ё', 'е');
        // единый алфавит: latin → ближайшая кириллица («dpi», «youtube», «ivi»)
        // a b c e g h i j k l m n o p q r s t u v w x y z
        String latin = "abceghijklmnopqrstuvwxyz";
        String cyr   = "абсегхийклмноркрстуввхуз";
        int n = Math.min(latin.length(), cyr.length());
        for (int i = 0; i < n; i++)
            s = s.replace(latin.charAt(i), cyr.charAt(i));
        s = s.replaceAll("[^а-я0-9 ]+", " ").trim();
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

    private static boolean wordMatch(String tok, String[] words) {
        for (String w : words) {
            if (tok.equals(w)) return true;
            if (tok.startsWith(w) && tok.length() <= w.length() + 3) return true;
            if (w.length() >= 5 && lev(tok, w) <= 1) return true;
        }
        return false;
    }

    private static boolean isVerbOpen(String tok)  { return wordMatch(tok, V_OPEN); }
    private static boolean isVerbClose(String tok) { return wordMatch(tok, V_CLOSE); }
    private static boolean isSearchMarker(String tok) { return wordMatch(tok, V_SEARCH); }
    private static boolean isFilmWord(String tok)  { return wordMatch(tok, V_FILM); }
    private static boolean isNoise(String tok) {
        for (String n : NOISE) if (tok.equals(n)) return true;
        return false;
    }

    /* ---------------- разбор ---------------- */

    /** Сопоставить пакет по окнам из 1–3 токенов. */
    static String matchPackage(String[] toks) {
        int n = toks.length;
        int best = Integer.MAX_VALUE; String pkg = null;
        for (int width = 1; width <= 3 && width <= n; width++) {
            for (int s = 0; s + width <= n; s++) {
                StringBuilder sb = new StringBuilder();
                boolean noise = true;
                for (int i = s; i < s + width; i++) {
                    String t = toks[i];
                    if (!isNoise(t)) { noise = false; sb.append(t); }
                }
                if (noise) continue;
                String packed = sb.toString();
                for (int g = 0; g < APPS.length; g += 2) {
                    for (String syn : APPS[g]) {
                        String sn = syn.replace(" ", "");
                        int d = lev(packed, sn);
                        int thr = Math.max(1, sn.length() / 4);
                        if (d <= thr && d < best) { best = d; pkg = APPS[g + 1][0]; }
                    }
                }
            }
        }
        return pkg;
    }

    /** Все xOffset разбора в одной структуре. */
    public static class Parsed {
        public String pkg;
        public boolean open;
        public boolean close;
        public boolean search;
        public String title;
    }

    public static Parsed parse(String utterance) {
        String s = norm(utterance);
        Parsed p = new Parsed();
        if (s.isEmpty()) return p;
        String[] raw = s.split(" ");

        StringBuilder clean = new StringBuilder();
        int lastSearchIdx = -1;
        java.util.List<String> toks = new java.util.ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            String t = raw[i];
            if (isVerbOpen(t))  { p.open = true; continue; }
            if (isVerbClose(t)) { p.close = true; continue; }
            if (isSearchMarker(t)) { p.search = true; lastSearchIdx = toks.size(); toks.add(t); continue; }
            if (isFilmWord(t) || isNoise(t)) { toks.add(t); continue; }
            toks.add(t);
        }
        // «чистые» токены для поиска приложения: без глаголов, маркеров, фильтов-шумов
        for (String t : toks) {
            if (isSearchMarker(t) || isFilmWord(t) || isNoise(t)) continue;
            clean.append(t).append(" ");
        }
        String[] cleanToks = clean.toString().trim().split(" ");
        if (cleanToks.length > 0 && !cleanToks[0].isEmpty()) p.pkg = matchPackage(cleanToks);

        // название: всё после ПОСЛЕДНЕГО «найди», но без филмовых слов на стыке,
        // и без названий приложений (когда «открой браузер найди фильм браузер»)
        if (p.search) {
            StringBuilder sb = new StringBuilder();
            boolean started = false;
            for (int i = lastSearchIdx + 1; i < toks.size(); i++) {
                String t = toks.get(i);
                boolean film = isFilmWord(t);
                boolean noise = isNoise(t);
                String aliasPkg = matchPackage(new String[]{ t });
                if (noise && !started) continue;
                if (film && !started) continue;
                if (aliasPkg != null) continue;
                if (noise) continue;
                if (film) continue;
                started = true;
                sb.append(t).append(" ");
            }
            String title = sb.toString().trim();
            p.title = title.isEmpty() ? null : title;
        }
        Log.i(TAG, "parsed: pkg=" + p.pkg + " open=" + p.open + " close=" + p.close
                + " search=" + p.search + " title=" + p.title);
        return p;
    }

    /* ---------------- исполнение ---------------- */

    public static void execute(Context c, String text, Reporter rep) {
        try {
            executeInner(c, text, rep);
        } catch (Throwable t) {
            // крах здесь убивал процесс, в котором висит служба доступности
            // (и вместе с ней — фильтр клавиш пульта). Никогда не падаём.
            Log.e(TAG, "exec failed", t);
            History.append(c, "ERR   " + text);
            try { rep.show("Ошибка разбора команды"); } catch (Throwable ignored) {}
        }
        History.append(c, "EXEC  " + text);
    }

    private static void executeInner(Context c, String text, Reporter rep) {
        text = text == null ? "" : text;
        Log.i(TAG, "exec: " + text);
        Parsed p = parse(text);

        if (p.pkg == null && !p.search) {
            rep.show("Не понял команду:\n" + text);
            return;
        }
        if (p.search && p.title == null && p.pkg == null) {
            rep.show("Не разобрал название.\nСкажите: «найди фильм <название>»");
            return;
        }

        if (BYEDPI_PKG.equals(p.pkg)) {
            dpiToggle(c, rep, p.close);
            if (p.search && p.title != null) comeToSearch(c, p.title, rep);
            return;
        }

        if (p.pkg != null) openApp(c, p.pkg, rep);

        if (p.search && p.title != null) {
            comeToSearch(c, p.title, rep);
            return;
        }
        // дойти до конца — браузер запустится через intent поиска (двойной старт не нужен)
    }

    private static void comeToSearch(Context c, String title, Reporter rep) {
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        search(c, title, rep);
    }

    private static void dpiToggle(Context c, Reporter rep, boolean wantClose) {
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
            case YOUTUBE_PKG: return "YouTube";
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
