/*
 * IDmate Auto: Zugang zur Lese-API eines Fahrzeugs. Der Inhalt kommt aus dem QR-Code, den IDMate beim
 * Anlegen des Zugangs einmalig zeigt:
 *   {"v":1,"t":"idmate-read","url":"https://host/api/v1","token":"...","vehicle":"id7","name":"ID.7"}
 * Gespeichert in den privaten Einstellungen der App (kein Backup, siehe Manifest). Das Token wird nie
 * protokolliert und nie angezeigt.
 */
package net.prototux.aare.dash;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;

final class Link {
    final String url, token, vehicle, name;

    private Link(String url, String token, String vehicle, String name) {
        this.url = url;
        this.token = token;
        this.vehicle = vehicle;
        this.name = name;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dash_link", Context.MODE_PRIVATE);
    }

    /** Warum ein QR-Inhalt abgelehnt wird (fuer die Meldung an den Benutzer). */
    static final int OK = 0, NOT_OURS = 1, VERSION = 2, ADDRESS = 3, PLAIN_HTTP = 4, TOKEN = 5;

    /** QR-Inhalt pruefen. Liefert null, wenn er kein Zugang zur Lese-API ist. */
    static Link parse(String qr) {
        return parse(qr, new int[1]);
    }

    /** Wie parse(String); why[0] nennt den Grund einer Ablehnung. */
    static Link parse(String qr, int[] why) {
        why[0] = NOT_OURS;
        try {
            JSONObject o = new JSONObject(qr.trim());
            if (!"idmate-read".equals(o.optString("t"))) return null;
            why[0] = VERSION;
            if (o.optInt("v") != 1) return null;
            why[0] = ADDRESS;
            String url = o.optString("url").trim(), token = o.optString("token");
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            /* In IDMate ohne "https://" eingetragene Adresse: gemeint ist die verschluesselte */
            if (!url.isEmpty() && !url.contains("://")) url = "https://" + url;
            URI u = URI.create(url);
            String scheme = u.getScheme(), host = u.getHost();
            if (host == null) return null;
            /* HTTPS, oder unverschluesselt nur ins eigene Netz bzw. VPN (IDMate: die Datenabfragen gehen auch
               ueber eine VPN-Adresse mit HTTP; die Kartenansicht gibt es nur ueber HTTPS, siehe secure()) */
            if (!"https".equals(scheme)) {
                if ("http".equals(scheme)) why[0] = PLAIN_HTTP;
                if (!("http".equals(scheme) && privateHost(host))) return null;
            }
            why[0] = TOKEN;
            if (token.length() < 16) return null;
            why[0] = OK;
            return new Link(url, token, o.optString("vehicle"), o.optString("name"));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Verschluesselte Verbindung: nur dann liefert IDMate die Kartenansicht (/display). */
    boolean secure() {
        return url.startsWith("https://");
    }

    /**
     * Adresse im eigenen Netz oder VPN: das eigene Geraet, private IPv4-Bereiche (10/8, 172.16/12, 192.168/16),
     * der Bereich der VPN-Dienste (100.64/10), verbindungslokale Adressen, IPv6 fc00::/7 und Namen, die es nur
     * im eigenen Netz gibt (ohne Punkt, .local, .lan, .home.arpa, .internal, .fritz.box).
     */
    static boolean privateHost(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.equals("localhost")) return true;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] p = h.split("\\.");
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254);
        }
        if (h.startsWith("[")) return h.startsWith("[fc") || h.startsWith("[fd") || h.startsWith("[fe80") || h.equals("[::1]");
        if (!h.contains(".")) return true;
        for (String s : new String[] { ".local", ".lan", ".home.arpa", ".internal", ".fritz.box" })
            if (h.endsWith(s)) return true;
        return false;
    }

    static Link load(Context c) {
        SharedPreferences p = prefs(c);
        String url = p.getString("url", null), token = p.getString("token", null);
        if (url == null || token == null) return null;
        return new Link(url, token, p.getString("vehicle", ""), p.getString("name", ""));
    }

    void save(Context c) {
        prefs(c).edit().putString("url", url).putString("token", token)
                .putString("vehicle", vehicle).putString("name", name).apply();
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }
}
