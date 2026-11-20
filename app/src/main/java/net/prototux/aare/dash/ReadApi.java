/*
 * IDmate Auto: Abruf der Lese-API von IDMate (GET /api/v1/..., Bearer-Token je Fahrzeug).
 * Fragt den Zustand im Takt ab, den der Server in /config nennt, und reicht die Telegramme an Telemetry.
 * Die App liest nur; sie sendet dem Server nichts ausser dem Token.
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class ReadApi {
    private static final String TAG = "IDmateApi";
    static final String SRC = "backend";
    private static final long LISTS_MS = 5 * 60 * 1000;
    /* complete every 5 min; the forecast still coming (IDmate 1.2.346 "complete": false) after 5 s; nothing yet after 30 s */
    private static final long RAIN_MS = 5 * 60_000L, RAIN_REST_MS = 5_000L, RAIN_RETRY_MS = 30_000L;
    private static final int CONNECT_MS = 4000, READ_MS = 6000, MAX_BYTES = 256 * 1024;
    /*
     * The rain raster is far larger than every other answer: the cells with rain around the whole range, in 13 pictures.
     * A front over a 350 km circle is some thousand cells a picture, 400 KB and more - within the 256 KB of the other
     * answers the raster was dropped on just the days it matters.
     */
    static final int RAIN_MAX_BYTES = 8 * 1024 * 1024;

    private final Link mLink;
    private final Telemetry mTelemetry;
    private final Runnable mChanged;
    private volatile Thread mThread;
    private volatile int mDriveS = 10, mParkS = 60;
    /*
     * The rain raster is megabytes on a rainy day, read and taken apart on every fetch. It is needed for a charging
     * plan only (18.11.2026: "Wetter und Regen nur im Dashboard, nicht in der Navi-Karte ... so flüssig wie möglich"),
     * so it is fetched while a plan asked for it within the last ten minutes, not all day long.
     */
    private static final long RAIN_WANTED_MS = 10 * 60_000L;
    private volatile long mRainWantedAt, mRainAnsweredAt;
    private final Object mWake = new Object();

    ReadApi(Link link, Telemetry telemetry, Runnable changed) {
        mLink = link;
        mTelemetry = telemetry;
        mChanged = changed;
    }

    void start() {
        if (mThread != null && mThread.isAlive()) return;
        Thread t = new Thread(this::run, "idmate-readapi");
        mThread = t;
        t.start();
    }

    void stop() {
        Thread t = mThread;
        mThread = null;
        if (t != null) t.interrupt();
    }

    /** A charging plan needs the rain: fetched with the next round, which starts at once when the last answer is old. */
    void wantRain() {
        mRainWantedAt = System.currentTimeMillis();
        if (!rainFresh()) synchronized (mWake) { mWake.notifyAll(); }
    }

    /** Whether /rain was answered (with a raster or not) within the last five minutes. */
    boolean rainFresh() {
        return System.currentTimeMillis() - mRainAnsweredAt < RAIN_MS;
    }

    private void run() {
        boolean configured = false;
        long lists = 0;
        long rainAt = 0;
        int rainState = 0;      /* 0 nothing yet, 1 without the forecast, 2 complete */
        while (mThread == Thread.currentThread()) {
            try {
                if (!configured) {
                    JSONObject iv = new JSONObject(get("/config")).optJSONObject("interval");
                    if (iv != null) {
                        mDriveS = clamp(iv.optInt("drive", 10));
                        mParkS = clamp(iv.optInt("park", 60));
                    }
                    configured = true;
                }
                if (!mTelemetry.accept(get("/status"))) mTelemetry.error(SRC, "Antwort ist kein Telegramm");
                if (System.currentTimeMillis() - lists > LISTS_MS) {        /* Fahrten und Laden aendern sich selten */
                    for (String path : new String[] { "/trips?limit=10", "/charging?limit=1" }) {
                        String list = get(path);
                        mTelemetry.accept(list);            /* zaehlt mit, damit die Listen keine Luecken vortaeuschen */
                        mTelemetry.acceptList(list);
                    }
                    try {
                        mTelemetry.acceptEnergyModel(get("/energy-model"));
                    } catch (IOException e) {
                        /* Server vor 1.2.341 kennt die Abfrage nicht: kein Fehler der Verbindung */
                    }
                    lists = System.currentTimeMillis();
                }
                /* IDmate's rain raster (1.2.345): every 5 minutes at most, sooner while the server is still working it out */
                if (System.currentTimeMillis() - mRainWantedAt < RAIN_WANTED_MS
                        && System.currentTimeMillis() - rainAt > (rainState == 2 ? RAIN_MS : rainState == 1 ? RAIN_REST_MS : RAIN_RETRY_MS)) {
                    rainAt = System.currentTimeMillis();
                    try {
                        rainState = mTelemetry.acceptRain(get("/rain", RAIN_MAX_BYTES));
                    } catch (IOException e) {
                        /* an older server has no /rain (404): quiet. Anything else is said, and asked again in 5 minutes */
                        if (e.getMessage() == null || !e.getMessage().contains("404")) Log.w(TAG, "Regen: " + e);
                        rainState = 2;
                    }
                    /* the measured pictures are enough for a plan that waits; the forecast follows with the next round */
                    if (rainState != 0) mRainAnsweredAt = System.currentTimeMillis();
                }
            } catch (IOException | org.json.JSONException e) {
                mTelemetry.error(SRC, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                Log.w(TAG, "Abruf: " + e);
            } catch (RuntimeException e) {
                /* 19.11.2026 (the level on arrival stopped following the car until Mate started anew: "unter bestimmten
                   Umstaenden nicht mehr weiterrechnet ... dann dauerhaft zu haengen"): any other fault ended this thread for
                   good - and with it every new level from IDmate, as start() saw the dead thread as running. Now it is said
                   and the next round comes as always. */
                mTelemetry.error(SRC, e.getClass().getSimpleName() + ": " + e.getMessage());
                net.prototux.aare.DiagLog.INSTANCE.add("IDmate: fetching the car's values failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ") - tried again with the next round");
            }
            try {
                mChanged.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "Anzeige: " + e);
            }
            Double speed = mTelemetry.number("speed");
            boolean driving = speed != null && speed > 1;
            try {
                synchronized (mWake) { mWake.wait((driving ? mDriveS : mParkS) * 1000L); }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static int clamp(int s) {
        return Math.max(2, Math.min(600, s));
    }

    private String get(String path) throws IOException {
        return get(path, MAX_BYTES);
    }

    private String get(String path, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(mLink.url + path).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + mLink.token);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Zugang ungueltig (401)");
            if (code == 404) throw new IOException("Lese-API ist am Server aus (404)");
            if (code == 429) throw new IOException("zu viele Fehlversuche (429)");
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (out.size() + n > maxBytes) throw new IOException("Antwort zu gross");
                    out.write(buf, 0, n);
                }
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
