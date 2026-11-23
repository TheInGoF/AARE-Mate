/*
 * Musik in Mate: Zugang zum eigenen Plex-Server (MusicService) und die wenigen Abfragen, die sie
 * braucht: Wiedergabelisten, deren Titel, Suche. Adresse und Token kommen aus der Anmeldung am Plex-Konto
 * (PlexAccount) oder traegt der Benutzer am Tablet von Hand ein; das Token wird nie protokolliert oder angezeigt. Nur HTTPS (clean).
 *
 * Plex liefert mit "Accept: application/json" JSON: {"MediaContainer":{"Metadata":[...]}}.
 */
package net.prototux.aare.music;

import net.prototux.aare.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

final class Plex {
    /** Eine Wiedergabeliste oder ein Titel. */
    static final class Item {
        String key;             /* ratingKey */
        String title, artist, album;
        long durationMs;
        int count;              /* Titel in der Liste */
        int year;               /* Erscheinungsjahr eines Albums, 0 = unbekannt */
        int stars;              /* Bewertung des Benutzers in halben Sternen: 0 = keine, 10 = fuenf Sterne */
        String part;            /* Pfad der Audiodatei am Server, nur bei Titeln */
        String thumb;           /* Pfad des Bildes */
    }

    /** Ein Fehler von plex.tv oder vom Server (net.prototux.aare.ShownFailure, so heisst er auch in PlexAccount). */
    static final class Failure extends net.prototux.aare.ShownFailure {
        Failure(String log, int text, Object... args) {
            super(log, text, args);
        }
    }

    final String url, token;

    private Plex(String url, String token) {
        this.url = url;
        this.token = token;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("plex", Context.MODE_PRIVATE);
    }

    /** Adresse pruefen: https://host[:port]. Liefert die bereinigte Adresse oder null. */
    static String clean(String url) {
        try {
            String s = url.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            URI u = URI.create(s);
            String scheme = u.getScheme(), host = u.getHost();
            if (host == null || (u.getPath() != null && !u.getPath().isEmpty())) return null;
            /* nur verschluesselt: Mate laesst keine unverschluesselten Verbindungen zu */
            if (!"https".equals(scheme)) return null;
            return s;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Plex load(Context c) {
        SharedPreferences p = prefs(c);
        String url = p.getString("url", null), token = p.getString("token", null);
        return url == null || token == null ? null : new Plex(url, token);
    }

    static boolean save(Context c, String url, String token) {
        String u = clean(url);
        if (u == null || token == null || token.trim().length() < 8) return false;
        /* von Hand eingetragen: kein Konto, die Adresse wird nicht neu gesucht */
        prefs(c).edit().putString("url", u).putString("token", token.trim()).remove("account").remove("server").apply();
        return true;
    }

    /**
     * Abmelden: nur Zugang und was zum Server gehoert. Abonnierte Listen, ihre Dateien und die Einstellungen bleiben
     * (die loescht Offline.clear eigens).
     */
    static void clear(Context c) {
        prefs(c).edit().remove("url").remove("token").remove("account").remove("server")
                .remove("last_id").remove("last_pos").remove("last_mix").apply();
    }

    /** Adresse einer Datei oder eines Bildes mit Token (fuer den Player; erscheint in keinem Protokoll der App). */
    Uri media(String path) {
        return Uri.parse(url + path).buildUpon().appendQueryParameter("X-Plex-Token", token).build();
    }

    /**
     * Adresse eines Bildes, vom Server auf hoechstens px Kantenlaenge verkleinert: die Originale (Albumbilder,
     * zusammengesetzte Listenbilder) sind um ein Vielfaches groesser, als eine Kachel sie braucht - viele davon
     * zugleich zu laden bremst Bild und Ton.
     */
    Uri art(String path, int px) {
        return Uri.parse(url + "/photo/:/transcode").buildUpon()
                .appendQueryParameter("width", String.valueOf(px)).appendQueryParameter("height", String.valueOf(px))
                .appendQueryParameter("minSize", "1").appendQueryParameter("upscale", "0")
                .appendQueryParameter("url", path)
                .appendQueryParameter("X-Plex-Token", token).build();
    }

    List<Item> playlists() throws IOException {
        return items(get("/playlists?playlistType=audio"), false);
    }

    /** Ein Ausschnitt der Titel einer Liste. Nie die ganze Liste: sie kann die gesamte Sammlung enthalten. */
    List<Item> tracks(String playlistKey, int start, int size) throws IOException {
        return items(get("/playlists/" + Uri.encode(playlistKey) + "/items?X-Plex-Container-Start=" + Math.max(0, start)
                + "&X-Plex-Container-Size=" + Math.max(0, size)), true);
    }

    /** Die Musik-Bibliothek des Servers (Abschnitt vom Typ "artist"); null, wenn es keine gibt. Einmal je Zugang erfragt. */
    private String mSection;
    private static String sSectionUrl, sSection;        /* gemerkt ueber die Abfragen hinweg, je Server */

    private String section() throws IOException {
        if (mSection != null) return mSection;
        synchronized (Plex.class) { if (url.equals(sSectionUrl) && sSection != null) return mSection = sSection; }
        try {
            JSONArray a = new JSONObject(get("/library/sections")).getJSONObject("MediaContainer").optJSONArray("Directory");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if ("artist".equals(o.optString("type"))) {
                    mSection = o.optString("key");
                    synchronized (Plex.class) { sSectionUrl = url; sSection = mSection; }
                    return mSection;
                }
            }
        } catch (JSONException e) {
            throw new Failure("unexpected answer from Plex", R.string.music_plex_unexpected);
        }
        throw new Failure("no music library on the server", R.string.music_plex_no_library);
    }

    /** Ein Ausschnitt aller Kuenstler (type 8) bzw. Alben (type 9), nach Namen sortiert. */
    List<Item> artists(int start, int size) throws IOException {
        return items(get("/library/sections/" + Uri.encode(section()) + "/all?type=8&sort=titleSort&X-Plex-Container-Start="
                + Math.max(0, start) + "&X-Plex-Container-Size=" + Math.max(0, size)), false);
    }

    List<Item> albums(int start, int size) throws IOException {
        return items(get("/library/sections/" + Uri.encode(section()) + "/all?type=9&sort=titleSort&X-Plex-Container-Start="
                + Math.max(0, start) + "&X-Plex-Container-Size=" + Math.max(0, size)), false);
    }

    /** Die Alben eines Kuenstlers. */
    List<Item> albumsOf(String artistKey) throws IOException {
        return items(get("/library/metadata/" + Uri.encode(artistKey) + "/children"), false);
    }

    /** Die Titel eines Albums, in dessen Reihenfolge. */
    List<Item> albumTracks(String albumKey) throws IOException {
        return items(get("/library/metadata/" + Uri.encode(albumKey) + "/children"), true);
    }

    /** Ein Ausschnitt aller Titel eines Kuenstlers. */
    List<Item> artistTracks(String artistKey, int start, int size) throws IOException {
        return items(get("/library/metadata/" + Uri.encode(artistKey) + "/allLeaves?X-Plex-Container-Start=" + Math.max(0, start)
                + "&X-Plex-Container-Size=" + Math.max(0, size)), true);
    }

    /** Ein Ausschnitt aller Titel eines Kuenstlers, nach dem Titel sortiert: zum Heraussuchen ohne Suche. */
    List<Item> artistTracksByTitle(String artistKey, int start, int size) throws IOException {
        return items(get("/library/sections/" + Uri.encode(section()) + "/all?type=10&artist.id=" + Uri.encode(artistKey)
                + "&sort=titleSort&X-Plex-Container-Start=" + Math.max(0, start) + "&X-Plex-Container-Size=" + Math.max(0, size)), true);
    }

    /** Die Genres der Alben: Schluessel und Name. */
    List<Item> genres() throws IOException {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(get("/library/sections/" + Uri.encode(section()) + "/genre?type=9"))
                    .getJSONObject("MediaContainer").optJSONArray("Directory");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.key = o.optString("key");
                it.title = o.optString("title");
                if (!it.key.isEmpty() && !it.title.isEmpty()) out.add(it);
            }
        } catch (JSONException e) {
            throw new Failure("unexpected answer from Plex", R.string.music_plex_unexpected);
        }
        return out;
    }

    /** Ein Ausschnitt der Alben eines Genres. */
    List<Item> genreAlbums(String genreKey, int start, int size) throws IOException {
        return items(get("/library/sections/" + Uri.encode(section()) + "/all?type=9&genre=" + Uri.encode(genreKey)
                + "&sort=titleSort&X-Plex-Container-Start=" + Math.max(0, start) + "&X-Plex-Container-Size=" + Math.max(0, size)), false);
    }

    /** Titel eines Genres in zufaelliger Folge (der Server mischt, jedes Mal neu). */
    List<Item> genreShuffle(String genreKey, int size) throws IOException {
        return items(get("/library/sections/" + Uri.encode(section()) + "/all?type=10&album.genre=" + Uri.encode(genreKey)
                + "&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=" + Math.max(0, size)), true);
    }

    /** Bewertung eines Titels am Server setzen: 0 (keine) bis 10 (fuenf Sterne), wie in Plex und Plexamp. */
    void rate(String trackKey, int halfStars) throws IOException {
        http("PUT", url + "/:/rate?key=" + Uri.encode(trackKey) + "&identifier=com.plexapp.plugins.library&rating="
                + Math.max(0, Math.min(10, halfStars)), token, CLIENT_FALLBACK, null, 5000);
    }

    /** Zahl der Titel einer Liste. */
    int count(String playlistKey) throws IOException {
        try {
            JSONObject c = new JSONObject(get("/playlists/" + Uri.encode(playlistKey)
                    + "/items?X-Plex-Container-Start=0&X-Plex-Container-Size=0")).getJSONObject("MediaContainer");
            return c.optInt("totalSize", c.optInt("size", 0));
        } catch (JSONException e) {
            throw new Failure("unexpected answer from Plex", R.string.music_plex_unexpected);
        }
    }

    /**
     * Suche wie man sie im Auto meint: ein Bandname bringt die Titel der Band, ein Albumname die des Albums, dazu
     * die Titel, die selbst so heissen. Erst die Titel der gefundenen Kuenstler, dann der Alben, dann die Einzeltreffer;
     * jeder Titel nur einmal.
     */
    /** Titel, deren Name zur Anfrage passt (Sprachwunsch nach einem bestimmten Lied). */
    List<Item> searchTracks(String query) throws IOException {
        return items(get("/search?type=10&limit=30&query=" + Uri.encode(query)), true);
    }

    List<Item> search(String query) throws IOException {
        String q = Uri.encode(query);
        java.util.LinkedHashMap<String, Item> out = new java.util.LinkedHashMap<>();
        /* type 8 = Kuenstler, 9 = Album, 10 = Titel */
        for (Item artist : items(get("/search?type=8&limit=4&query=" + q), false))
            add(out, items(get("/library/metadata/" + Uri.encode(artist.key) + "/allLeaves?X-Plex-Container-Start=0&X-Plex-Container-Size=" + SEARCH_MAX), true));
        for (Item album : items(get("/search?type=9&limit=6&query=" + q), false))
            add(out, items(get("/library/metadata/" + Uri.encode(album.key) + "/children"), true));
        add(out, items(get("/search?type=10&limit=40&query=" + q), true));
        return new ArrayList<>(out.values());
    }

    private static final int SEARCH_MAX = 300;

    private static void add(java.util.Map<String, Item> out, List<Item> more) {
        for (Item it : more) if (out.size() < SEARCH_MAX) out.putIfAbsent(it.key, it);
    }

    /** Name des Servers; zugleich die Probe, ob Adresse und Token stimmen. */
    String name() throws IOException {
        try {
            return new JSONObject(get("/")).getJSONObject("MediaContainer").optString("friendlyName", "Plex");
        } catch (JSONException e) {
            throw new Failure("answer is not from a Plex server", R.string.music_plex_not_server);
        }
    }

    private static List<Item> items(String json, boolean tracks) throws IOException {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).getJSONObject("MediaContainer").optJSONArray("Metadata");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.key = o.optString("ratingKey");
                it.title = o.optString("title");
                it.count = o.optInt("leafCount");
                it.year = o.optInt("year");
                it.stars = (int) Math.round(o.optDouble("userRating", 0));
                it.durationMs = o.optLong("duration");
                it.artist = o.optString("grandparentTitle", o.optString("originalTitle", ""));
                it.album = o.optString("parentTitle", "");
                it.thumb = o.optString("thumb", o.optString("composite", null));
                if (tracks) {
                    JSONArray media = o.optJSONArray("Media");
                    JSONArray parts = media == null || media.length() == 0 ? null : media.getJSONObject(0).optJSONArray("Part");
                    if (parts == null || parts.length() == 0) continue;     /* ohne Datei nicht spielbar */
                    it.part = parts.getJSONObject(0).optString("key", null);
                    if (it.part == null) continue;
                }
                if (!it.key.isEmpty()) out.add(it);
            }
        } catch (JSONException e) {
            throw new Failure("unexpected answer from Plex", R.string.music_plex_unexpected);
        }
        return out;
    }

    /**
     * Was der Server ueber die Lautheit eines Titels weiss (seine eigene Analyse, "Loudness"): { gain, albumGain, loudness, peak },
     * gain in dB = um so viel muesste der Titel lauter (+) oder leiser (-) spielen, um den Bezugspegel zu treffen. NaN = nicht bekannt.
     */
    float[] loudness(String key) throws IOException {
        float[] out = { Float.NaN, Float.NaN, Float.NaN, Float.NaN };
        try {
            JSONArray a = new JSONObject(get("/library/metadata/" + Uri.encode(key))).getJSONObject("MediaContainer").optJSONArray("Metadata");
            JSONArray media = a == null || a.length() == 0 ? null : a.getJSONObject(0).optJSONArray("Media");
            JSONArray parts = media == null || media.length() == 0 ? null : media.getJSONObject(0).optJSONArray("Part");
            JSONArray streams = parts == null || parts.length() == 0 ? null : parts.getJSONObject(0).optJSONArray("Stream");
            for (int i = 0; streams != null && i < streams.length(); i++) {
                JSONObject s = streams.getJSONObject(i);
                if (s.optInt("streamType") != 2) continue;
                out[0] = (float) s.optDouble("gain", Double.NaN);
                out[1] = (float) s.optDouble("albumGain", Double.NaN);
                out[2] = (float) s.optDouble("loudness", Double.NaN);
                out[3] = (float) s.optDouble("peak", Double.NaN);
                break;
            }
        } catch (JSONException e) {
            throw new Failure("unexpected answer from Plex", R.string.music_plex_unexpected);
        }
        return out;
    }

    private String get(String path) throws IOException {
        return http("GET", url + path, token, CLIENT_FALLBACK, null, 5000);
    }

    /** Antwortet unter dieser Adresse ein Plex-Server auf dieses Token? Liefert seinen Namen oder null. */
    static String probe(String url, String token, int connectMs) {
        try {
            return new JSONObject(http("GET", url + "/", token, CLIENT_FALLBACK, null, connectMs))
                    .getJSONObject("MediaContainer").optString("friendlyName", "Plex");
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static final String CLIENT_FALLBACK = "mate-music";

    /** Eine Abfrage an einen Plex-Server oder an plex.tv. Das Token steht nur im Kopf, nie in einem Protokoll. */
    static String http(String method, String address, String token, String client, String form, int connectMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("X-Plex-Token", token);
            c.setRequestProperty("X-Plex-Client-Identifier", client);
            c.setRequestProperty("X-Plex-Product", "Mate");
            if (form != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.getOutputStream().write(form.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 401) throw new Failure("Plex: access denied (401)", R.string.music_plex_denied);
            if (code != 200 && code != 201) throw new Failure("Plex: HTTP " + code, R.string.music_plex_http, code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (out.size() + n > 32 * 1024 * 1024) throw new Failure("Plex: answer too large", R.string.music_plex_too_large);
                    out.write(buf, 0, n);
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
