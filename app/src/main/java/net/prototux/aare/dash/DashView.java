package net.prototux.aare.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;

/**
 * The dashboard as Mate's car screen shows it (19.11.2026: "alle Funktionen des Dashboards und dieser ganzen
 * Connections soll ja in Mate liegen. Also muss Mate auch selber das Dashboard zeichnen"): the stage's views put straight
 * into the car screen, no car app in between - no surface handed over, no display of its own laid on it.
 *
 * One stage per process: [show] gives its views (kept while another area is in front, so the map stands at once on
 * coming back), [hide] puts them to rest. The data sources run from the first [show] until ten minutes after [hide].
 */
public final class DashView {
    private DashView() {}

    private static final long HOLD_MS = 10 * 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static CarStage sStage;
    private static boolean sHeld;
    private static long sSeq;
    private static final Runnable CHANGED = () -> MAIN.post(() -> {
        CarStage st = sStage;
        if (st != null) st.changed();
    });
    private static final Runnable LET_GO = () -> {
        if (!sHeld) return;
        sHeld = false;
        Hub hub = sStage != null ? sStage.hub() : null;
        if (hub != null) { hub.unlisten(CHANGED); hub.release(); }
    };

    /**
     * The dashboard's views for the car screen [ctx] at Mate's density [dpi]; a place picked on the map goes to [go].
     * Main thread.
     */
    public static View show(Context ctx, int dpi, java.util.function.Consumer<String> go) {
        Context app = ctx.getApplicationContext();
        Hub hub = Hub.get(app);
        MAIN.removeCallbacks(LET_GO);
        if (!sHeld) {
            sHeld = true;
            hub.listen(CHANGED);
            hub.acquire();
        }
        if (sStage == null) sStage = new CarStage(app, hub);
        CarStage.sCurrent = sStage;
        CarStage.sGo = go;
        boolean kept = sStage.running();
        View v = sStage.view(ctx, dpi);
        if (v != null) {
            net.prototux.aare.DiagLog.INSTANCE.add(kept && sStage.running() ? "dashboard: shown again (page kept)" : "dashboard: page being built in Mate's car screen (" + dpi + " dpi)");
            return v;
        }
        net.prototux.aare.DiagLog.INSTANCE.add("dashboard: no page (no link to IDmate) - Mate draws the values itself");
        return values(ctx, hub, dpi);
    }

    /** Not on the screen any more: the views rest; the data goes on for a while. Main thread. */
    public static void hide() {
        if (sStage != null) sStage.park();
        MAIN.removeCallbacks(LET_GO);
        MAIN.postDelayed(LET_GO, HOLD_MS);
    }

    private static boolean sCar;

    /**
     * A car connected: IDmate's values (the level the navigation counts the arrival from) are fetched from now on, whatever
     * is on the screen - before, only while the dashboard was there (19.11.2026). Main thread.
     */
    public static void carStart(Context ctx) {
        if (sCar) return;
        sCar = true;
        Hub.get(ctx.getApplicationContext()).acquire();
    }

    /** The car gone. Main thread. */
    public static void carStop(Context ctx) {
        if (!sCar) return;
        sCar = false;
        Hub.get(ctx.getApplicationContext()).release();
    }

    /** Night or day on the car's screen (System L-074): the map's colours follow. Main thread. */
    public static void night(boolean on) {
        if (sStage != null) sStage.night(on); else CarStage.nightLater(on);
    }

    /** "Neu laden": the page built anew. Main thread. */
    public static void reload() {
        if (sStage == null || !sStage.running()) return;
        net.prototux.aare.DiagLog.INSTANCE.add("dashboard: page built anew (Neu laden)");
        sStage.reload();
    }

    /** The car's own values (speed in km/h, odometer in km; null where not known) for the dashboard, as the car's source. */
    public static void carValues(Context ctx, Float speedKmh, Float odometerKm) {
        if (!sHeld) return;
        JSONObject d = new JSONObject();
        try {
            if (speedKmh != null) d.put("speed", Math.round(speedKmh * 10) / 10.0);
            if (odometerKm != null) d.put("odometer", Math.round(odometerKm * 10) / 10.0);
            if (d.length() == 0) return;
            JSONObject t = new JSONObject();
            t.put("v", 1).put("src", CarProbe.SRC).put("ts", Instant.now().toString()).put("seq", ++sSeq).put("type", "status").put("data", d);
            Hub.get(ctx.getApplicationContext()).telemetry.accept(t.toString());
        } catch (JSONException e) {
            return;
        }
        CHANGED.run();
    }

    /** Without a link to IDmate: the values painted, as before on the car app's surface. */
    private static View values(Context ctx, Hub hub, int dpi) {
        final DashPainter painter = new DashPainter(ctx.getApplicationContext(), hub);
        final float dp = dpi / 160f;
        return new View(ctx) {
            private final Rect mArea = new Rect();
            private final Runnable mTick = new Runnable() {
                @Override public void run() { invalidate(); postDelayed(this, 1000); }
            };
            @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(mTick); }
            @Override protected void onDetachedFromWindow() { removeCallbacks(mTick); super.onDetachedFromWindow(); }
            @Override protected void onDraw(Canvas cv) {
                cv.drawColor(DashPainter.BG);
                mArea.set(0, 0, getWidth(), getHeight());
                painter.paint(cv, mArea, dp, true);
            }
        };
    }
}
