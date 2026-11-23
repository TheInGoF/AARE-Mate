package net.prototux.aare.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Abonnierte Wiedergabelisten auf dem Geraet (18.11.2026: "Auch Playlisten abonnieren, dass die immer aktuell offline
 * gehalten werden"). Je Liste ein Verzeichnis ihrer Titel (Reihenfolge wie am Server) und die Audiodateien selbst;
 * ein Abgleich holt, was neu ist, und loescht, was in keiner abonnierten Liste mehr steht. Liegen Liste und Datei vor,
 * spielt Mate Musik sie ohne Netz.
 */
public final class Offline {
    private static final String TAG = "MateMusicOffline";
    static final String KEY_LISTS = "offline_lists";
    static final String KEY_WIFI = "offline_wifi_only";
    static final String KEY_LAST = "offline_last_sync";
    /** Hoechstens so viele Titel je Liste (eine "Liste" kann die ganze Sammlung sein). */
    static final int LIST_MAX = 5000;
    /** So oft wird von selbst abgeglichen. */
    static final long EVERY_MS = 6 * 3600_000L;

    private static File sDir;

    private Offline() {}

    /** The place changed (Storage.move): the folder is looked up anew. */
    public static synchronized void forgetDir() { sDir = null; }

    static synchronized File dir(Context c) {
        if (sDir == null) {
            // in the place chosen for the large files: the device or the memory card (Storage, System L-086)
            sDir = net.prototux.aare.Storage.INSTANCE.dir(c.getApplicationContext(), net.prototux.aare.Storage.MUSIC);
            new File(sDir, "t").mkdirs();
            new File(sDir, "l").mkdirs();
            new File(sDir, "a").mkdirs();
        }
        return sDir;
    }

    /** Die Audiodatei eines Titels, wenn sie vollstaendig geladen ist; sonst null. */
    static File track(Context c, String key) {
        if (key == null || key.isEmpty()) return null;
        File f = new File(new File(dir(c), "t"), safe(key));
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** Das Bild zu einem Bildpfad des Servers, wenn geladen; sonst null. */
    static File art(Context c, String thumb) {
        if (thumb == null) return null;
        File f = new File(new File(dir(c), "a"), artName(thumb));
        return f.isFile() && f.length() > 0 ? f : null;
    }

    public static Set<String> lists(Context c) {
        return new HashSet<>(Plex.prefs(c).getStringSet(KEY_LISTS, new HashSet<>()));
    }

    public static boolean subscribed(Context c, String key) {
        return lists(c).contains(key);
    }

    public static void subscribe(Context c, String key, boolean on) {
        Set<String> s = lists(c);
        if (on) s.add(key); else s.remove(key);
        Plex.prefs(c).edit().putStringSet(KEY_LISTS, s).apply();
    }

    public static boolean wifiOnly(Context c) { return Plex.prefs(c).getBoolean(KEY_WIFI, true); }

    public static void setWifiOnly(Context c, boolean on) { Plex.prefs(c).edit().putBoolean(KEY_WIFI, on).apply(); }

    public static long lastSync(Context c) { return Plex.prefs(c).getLong(KEY_LAST, 0); }

    /** Die gespeicherte Liste (Titel in der Reihenfolge des Servers) oder null, wenn sie nicht abonniert oder noch nie geladen ist. */
    static List<Plex.Item> list(Context c, String key) {
        if (!subscribed(c, key)) return null;
        File f = new File(new File(dir(c), "l"), safe(key) + ".json");
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(read(f));
            JSONArray a = o.getJSONArray("tracks");
            List<Plex.Item> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                Plex.Item it = new Plex.Item();
                it.key = t.optString("key");
                it.title = t.optString("title");
                it.artist = t.optString("artist");
                it.album = t.optString("album");
                it.durationMs = t.optLong("ms");
                it.stars = t.optInt("stars");
                it.part = t.optString("part", null);
                it.thumb = t.has("thumb") ? t.optString("thumb") : null;
                out.add(it);
            }
            return out;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Die Wiedergabelisten des Servers als { Schluessel, Name (Titelzahl) }; null, wenn er nicht erreichbar ist. */
    public static List<String[]> serverLists(Context c) {
        Plex plex = PlexAccount.current(c);
        if (plex == null) return null;
        try {
            List<String[]> out = new ArrayList<>();
            for (Plex.Item p : plex.playlists()) out.add(new String[] { p.key, p.title + " (" + p.count + ")" });
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /** Name der Liste, wie zuletzt geladen. */
    static String title(Context c, String key) {
        File f = new File(new File(dir(c), "l"), safe(key) + ".json");
        try {
            return new JSONObject(read(f)).optString("title", "");
        } catch (IOException | JSONException e) {
            return "";
        }
    }

    /** Stand einer Liste fuer die Einstellungen: { Titel in der Liste, davon geladen }. */
    public static int[] state(Context c, String key) {
        List<Plex.Item> l = list(c, key);
        if (l == null) return new int[] { 0, 0 };
        int have = 0;
        for (Plex.Item t : l) if (track(c, t.key) != null) have++;
        return new int[] { l.size(), have };
    }

    public static long bytes(Context c) {
        long n = 0;
        for (String sub : new String[] { "t", "a", "l" }) {
            File[] fs = new File(dir(c), sub).listFiles();
            if (fs != null) for (File f : fs) n += f.length();
        }
        return n;
    }

    /** Alles Geladene loeschen und die Abos beenden. */
    public static void clear(Context c) {
        Plex.prefs(c).edit().remove(KEY_LISTS).apply();
        for (String sub : new String[] { "t", "a", "l" }) {
            File[] fs = new File(dir(c), sub).listFiles();
            if (fs != null) for (File f : fs) f.delete();
        }
    }

    /** Darf jetzt geladen werden (Netz da; bei "nur WLAN" ein Netz ohne Kosten)? */
    public static boolean netOk(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            return !wifiOnly(c) || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Fortschritt eines Abgleichs: Text fuer die Einstellungen, null = fertig. */
    public interface Progress { void on(String text); }

    private static final Object SYNC = new Object();
    private static volatile boolean sRunning;

    public static boolean running() { return sRunning; }

    /**
     * Gleicht alle abonnierten Listen ab (blockiert, nicht im Hauptfaden aufrufen). Laeuft schon einer, kehrt er sofort zurueck.
     * Liefert die Zahl neu geladener Titel, -1 wenn nicht moeglich (kein Server, kein passendes Netz).
     */
    public static int sync(Context c, Progress p) {
        if (sRunning) return 0;
        synchronized (SYNC) {
            sRunning = true;
            try {
                return syncNow(c.getApplicationContext(), p);
            } finally {
                sRunning = false;
                if (p != null) p.on(null);
            }
        }
    }

    private static int syncNow(Context c, Progress p) {
        Plex plex = PlexAccount.current(c);
        if (plex == null || !netOk(c)) return -1;
        Set<String> subs = lists(c);
        Set<String> keep = new HashSet<>(), keepArt = new HashSet<>();
        int loaded = 0;
        boolean complete = true;
        int found = 0;
        try {
            List<Plex.Item> all = plex.playlists();
            for (Plex.Item pl : all) {
                if (!subs.contains(pl.key)) continue;
                found++;
                List<Plex.Item> tracks = new ArrayList<>();
                for (int at = 0; at < LIST_MAX; at += 500) {
                    List<Plex.Item> part = plex.tracks(pl.key, at, 500);
                    tracks.addAll(part);
                    if (part.size() < 500) break;
                }
                writeList(c, pl.key, pl.title, tracks);
                LinkedHashSet<String> thumbs = new LinkedHashSet<>();
                for (Plex.Item t : tracks) { keep.add(safe(t.key)); if (t.thumb != null) thumbs.add(t.thumb); }
                for (String th : thumbs) keepArt.add(artName(th));
                int i = 0;
                for (Plex.Item t : tracks) {
                    i++;
                    if (track(c, t.key) != null || t.part == null) continue;
                    if (!netOk(c)) { complete = false; break; }
                    if (p != null) p.on(pl.title + ": " + i + " / " + tracks.size());
                    try {
                        download(plex.media(t.part).toString(), new File(new File(dir(c), "t"), safe(t.key)));
                        loaded++;
                    } catch (IOException e) {
                        complete = false;
                        Log.w(TAG, "Titel nicht geladen: " + e.getClass().getSimpleName());
                    }
                }
                for (String th : thumbs) {
                    if (art(c, th) != null || !netOk(c)) continue;
                    try { download(plex.art(th, 640).toString(), new File(new File(dir(c), "a"), artName(th))); }
                    catch (IOException ignored) { }
                }
            }
            /* Abos, die es am Server nicht mehr gibt, bleiben abonniert, behalten aber ihr Verzeichnis nicht */
        } catch (IOException e) {
            Log.w(TAG, "Abgleich: " + e.getClass().getSimpleName());
            return -1;
        }
        /* aufraeumen: was in keiner abonnierten Liste mehr steht - nur wenn der Server die Abos auch kennt; sonst (anderes
           Konto, anderer Server, Listen umbenannt) wuerde alles Geladene geloescht (23.11.2026: "Musik ist gelöscht") */
        if (found == 0 || found < subs.size()) {
            Log.w(TAG, "Abgleich: " + found + " von " + subs.size() + " Abos am Server gefunden, nichts geloescht");
            if (complete) Plex.prefs(c).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
            return loaded;
        }
        for (String sub : new String[] { "t", "a" }) {
            File[] fs = new File(dir(c), sub).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName();
                if (n.endsWith(".part") || !(sub.equals("t") ? keep : keepArt).contains(n)) f.delete();
            }
        }
        File[] ls = new File(dir(c), "l").listFiles();
        if (ls != null) for (File f : ls) {
            String k = f.getName().replace(".json", "");
            boolean sub = false;
            for (String s : subs) if (safe(s).equals(k)) sub = true;
            if (!sub) f.delete();
        }
        if (complete) Plex.prefs(c).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply();
        Log.i(TAG, "Abgleich: " + loaded + " Titel neu, " + keep.size() + " vorhanden");
        return loaded;
    }

    private static void writeList(Context c, String key, String title, List<Plex.Item> tracks) throws IOException {
        try {
            JSONArray a = new JSONArray();
            for (Plex.Item t : tracks) {
                JSONObject o = new JSONObject().put("key", t.key).put("title", t.title).put("artist", t.artist)
                        .put("album", t.album).put("ms", t.durationMs).put("stars", t.stars).put("part", t.part);
                if (t.thumb != null) o.put("thumb", t.thumb);
                a.put(o);
            }
            File f = new File(new File(dir(c), "l"), safe(key) + ".json");
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(new JSONObject().put("title", title).put("tracks", a).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) throw new IOException("Liste nicht gespeichert");
        } catch (JSONException e) {
            throw new IOException("Liste nicht gespeichert");
        }
    }

    /** Laedt in eine .part-Datei und benennt erst nach vollstaendigem Empfang um - halbe Dateien werden nie gespielt. */
    private static void download(String address, File to) throws IOException {
        File tmp = new File(to.getPath() + ".part");
        HttpURLConnection h = (HttpURLConnection) new URL(address).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            long want = h.getContentLengthLong();
            long got = 0;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); got += n; }
            }
            if (want > 0 && got != want) throw new IOException("unvollstaendig");
            if (!tmp.renameTo(to)) throw new IOException("nicht gespeichert");
        } finally {
            h.disconnect();
            tmp.delete();
        }
    }

    private static String safe(String key) {
        return key.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String artName(String thumb) {
        return Integer.toHexString(thumb.hashCode()) + "_" + thumb.length() + ".jpg";
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    static Uri fileUri(File f) { return Uri.fromFile(f); }
}
