/*
 * IDmate Auto: die eine Stelle, an der die Fahrzeugdaten zusammenlaufen. Android Auto (IdmateCarService) und
 * die Seite am Tablet (SetupActivity) benutzen dieselbe Telemetry und dieselben Quellen.
 *
 * Schalter der Fehlersuche "Quelle": beide (Vorrang nach Zeitstempel), nur Server, nur Stick. Die Quelle Stick
 * (Bluetooth, StickLink) braucht die Firmware mit "Live-Daten ueber Bluetooth" und eine Kopplung.
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

final class Hub {
    static final String MODE_ALL = "all", MODE_BACKEND = "backend", MODE_STICK = "stick", MODE_CAR = "car";

    private static Hub sHub;

    static synchronized Hub get(Context c) {
        if (sHub == null) sHub = new Hub(c.getApplicationContext());
        return sHub;
    }

    final Telemetry telemetry = new Telemetry();
    private final Context mCtx;
    private final CopyOnWriteArrayList<Runnable> mListeners = new CopyOnWriteArrayList<>();
    private int mUsers;
    private ReadApi mApi;
    private StickLink mStick;

    private Hub(Context c) {
        mCtx = c;
        applyMode();
    }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences("dash_hub", Context.MODE_PRIVATE);
    }

    String mode() {
        return prefs().getString("mode", MODE_ALL);
    }

    /** Quelle umschalten; verwirft die angezeigten Werte, damit nichts aus der anderen Quelle stehen bleibt. */
    synchronized void mode(String mode) {
        prefs().edit().putString("mode", mode).apply();
        applyMode();
        restart();
    }

    private void applyMode() {
        String m = mode();
        telemetry.only(MODE_BACKEND.equals(m) ? ReadApi.SRC : MODE_STICK.equals(m) ? "stick"
                : MODE_CAR.equals(m) ? "car" : null);
    }

    /**
     * Diagnose der Quellen (backend, stick, car: Alter, Anzahl, Luecken) auf dem Dashboard zeigen. Aus als Vorgabe:
     * sie lag ueber den Werten des Dashboards (Wunsch des Benutzers 15.11.2026: nur unten rechts, abschaltbar).
     */
    boolean showDiagnosis() {
        return prefs().getBoolean("diagnosis", false);
    }

    void showDiagnosis(boolean on) {
        prefs().edit().putBoolean("diagnosis", on).apply();
    }

    /** Stick benutzen (am Tablet eingeschaltet, sobald der Benutzer "Stick suchen" gewaehlt hat). */
    boolean stickEnabled() {
        return prefs().getBoolean("stick", false);
    }

    synchronized void stickEnabled(boolean on) {
        prefs().edit().putBoolean("stick", on).apply();
        if (!on) StickLink.forget(mCtx);
        restart();
    }

    boolean linked() {
        return Link.load(mCtx) != null;
    }

    void listen(Runnable r) {
        mListeners.add(r);
    }

    void unlisten(Runnable r) {
        mListeners.remove(r);
    }

    private void changed() {
        for (Runnable r : mListeners) r.run();
    }

    /** Ein Nutzer mehr (Android-Auto-Sitzung oder offene Seite): Quellen laufen, solange es Nutzer gibt. */
    synchronized void acquire() {
        if (mUsers++ == 0) start();
    }

    synchronized void release() {
        if (--mUsers <= 0) {
            mUsers = 0;
            stop();
        }
    }

    /** Nach geaenderter Verbindung oder Quelle neu starten. */
    synchronized void restart() {
        stop();
        if (mUsers > 0) start();
        changed();
    }

    /** Asks the reader for the rain raster ({@link ReadApi#wantRain}); false without a reader or once it has answered lately. */
    synchronized boolean awaitsRain() {
        if (mApi == null) return false;
        mApi.wantRain();
        return !mApi.rainFresh();
    }

    private void start() {
        Link link = Link.load(mCtx);
        if (link != null && !MODE_STICK.equals(mode()) && !MODE_CAR.equals(mode())) {
            mApi = new ReadApi(link, telemetry, this::changed);
            mApi.start();
        }
        if (!MODE_BACKEND.equals(mode()) && !MODE_CAR.equals(mode()) && stickEnabled()) {
            mStick = new StickLink(mCtx, telemetry, this::changed);
            mStick.start();
        }
    }

    private void stop() {
        if (mApi != null) mApi.stop();
        mApi = null;
        if (mStick != null) mStick.stop();
        mStick = null;
    }
}
