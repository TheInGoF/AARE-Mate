/*
 * Musik in Mate: Anmeldung am Plex-Konto mit einem Code (plex.tv/link) und das Finden des eigenen Servers.
 *
 * Ablauf: plex.tv gibt einen vierstelligen Code aus; der Benutzer gibt ihn auf plex.tv/link ein (an einem
 * beliebigen Geraet, mit seinem Konto). Danach liefert plex.tv ein Konto-Token und die Liste der Server mit
 * ihren Adressen: im eigenen Netz, von aussen, ueber die Weiterleitung von Plex. Die App nimmt die erste, die
 * antwortet, und sucht neu, wenn sie nicht mehr antwortet (zu Hause / unterwegs).
 *
 * Kennwort und Konto-Name sieht die App nie. Das Konto-Token bleibt in den Einstellungen der App auf dem
 * Tablet und wird nie protokolliert oder angezeigt. An plex.tv gehen nur eine zufaellige Geraetekennung und
 * der Name der App.
 */
package net.prototux.aare.music;

import net.prototux.aare.R;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PlexAccount {
    private PlexAccount() { }

    static final class Pin {
        long id;
        String code;
    }

    static final class Server {
        String id, name, token;
        final List<String> uris = new ArrayList<>();    /* beste zuerst */
    }

    private static final String BASE = "https://plex.tv";
    private static long sChecked;       /* wann der Server zuletzt geantwortet hat (elapsedRealtime) */

    /** plex.tv; in Test-Builds ueber die Seite auf einen Nachbau umstellbar (MusicSetupActivity). */
    private static String base(Context c) {
        return Plex.prefs(c).getString("account_base", BASE);
    }

    /** Zufaellige Kennung dieser Installation, wie sie plex.tv von jedem Geraet verlangt. */
    private static String client(Context c) {
        SharedPreferences p = Plex.prefs(c);
        String id = p.getString("client", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("client", id).apply();
        }
        return id;
    }

    /** Neuen Code holen. */
    static Pin start(Context c) throws IOException {
        try {
            JSONObject o = new JSONObject(Plex.http("POST", base(c) + "/api/v2/pins", null, client(c), "strong=false", 8000));
            Pin p = new Pin();
            p.id = o.getLong("id");
            p.code = o.getString("code");
            return p;
        } catch (JSONException e) {
            throw new Plex.Failure("unexpected answer from plex.tv", R.string.music_plex_unexpected);
        }
    }

    /** Ist der Code bestaetigt? Liefert dann das Konto-Token, sonst null. */
    static String poll(Context c, long id) throws IOException {
        try {
            JSONObject o = new JSONObject(Plex.http("GET", base(c) + "/api/v2/pins/" + id, null, client(c), null, 8000));
            String t = o.isNull("authToken") ? null : o.optString("authToken", null);
            return t == null || t.isEmpty() ? null : t;
        } catch (JSONException e) {
            throw new Plex.Failure("unexpected answer from plex.tv", R.string.music_plex_unexpected);
        }
    }

    /** Die Server des Kontos mit ihren Adressen. */
    static List<Server> servers(Context c, String account) throws IOException {
        List<Server> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Plex.http("GET", base(c) + "/api/v2/resources?includeHttps=1&includeRelay=1",
                    account, client(c), null, 8000));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (!("," + o.optString("provides") + ",").contains(",server,")) continue;
                Server s = new Server();
                s.id = o.optString("clientIdentifier");
                s.name = o.optString("name", "Plex");
                s.token = o.optString("accessToken", account);
                JSONArray conns = o.optJSONArray("connections");
                /* Reihenfolge: eigenes Netz, von aussen, Weiterleitung von Plex */
                for (int rank = 0; rank < 3 && conns != null; rank++) {
                    for (int k = 0; k < conns.length(); k++) {
                        JSONObject n = conns.getJSONObject(k);
                        boolean relay = n.optBoolean("relay"), local = n.optBoolean("local");
                        if ((relay ? 2 : local ? 0 : 1) != rank) continue;
                        String uri = Plex.clean(n.optString("uri"));     /* nur https (Plex.clean) */
                        if (uri != null && !s.uris.contains(uri)) s.uris.add(uri);
                    }
                }
                if (!s.id.isEmpty() && !s.uris.isEmpty()) out.add(s);
            }
        } catch (JSONException e) {
            throw new Plex.Failure("unexpected answer from plex.tv", R.string.music_plex_unexpected);
        }
        return out;
    }

    /** Die erste Adresse des Servers nehmen, die antwortet, und alles speichern. Liefert den Namen oder null. */
    static String connect(Context c, String account, Server s) {
        for (String uri : s.uris) {
            String name = Plex.probe(uri, s.token, 3000);
            if (name == null) continue;
            Plex.prefs(c).edit().putString("url", uri).putString("token", s.token)
                    .putString("account", account).putString("server", s.id).apply();
            sChecked = android.os.SystemClock.elapsedRealtime();
            return name;
        }
        return null;
    }

    /**
     * Der Zugang fuer den Musikdienst. Mit Konto: antwortet die gespeicherte Adresse nicht mehr (anderes Netz),
     * wird ueber plex.tv neu gesucht. Hoechstens einmal je Minute geprueft. Nicht im Hauptthread aufrufen.
     */
    static Plex current(Context c) {
        Plex p = Plex.load(c);
        SharedPreferences prefs = Plex.prefs(c);
        String account = prefs.getString("account", null), id = prefs.getString("server", null);
        if (account == null || id == null) return p;
        long now = android.os.SystemClock.elapsedRealtime();
        if (p != null && sChecked != 0 && now - sChecked < 60_000) return p;
        if (p != null && Plex.probe(p.url, p.token, 3000) != null) {
            sChecked = now;
            return p;
        }
        try {
            for (Server s : servers(c, account))
                if (s.id.equals(id) && connect(c, account, s) != null) return Plex.load(c);
        } catch (IOException e) {
            /* plex.tv nicht erreichbar: mit der alten Adresse weiter, der Aufrufer meldet den Fehler */
        }
        return p;
    }
}
