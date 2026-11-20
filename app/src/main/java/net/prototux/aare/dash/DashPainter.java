/*
 * IDmate Auto: zeichnet das Dashboard (Werte wie im Dashboard von IDmate) und die Zeilen der Fehlersuche.
 * Benutzt von der Ansicht neben der Karte (CarStage) und, wenn es keine Karte gibt, direkt auf der Flaeche
 * von Android Auto (MainScreen).
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.Locale;
import java.util.Map;

final class DashPainter {
    /* Farben des IDmate-Dashboards (dunkles Design) */
    static final int BG = Color.rgb(13, 17, 23), PANEL = Color.rgb(22, 27, 34), PANEL2 = Color.rgb(48, 54, 61);
    static final int TEXT = Color.rgb(240, 246, 252), DIM = Color.rgb(139, 148, 158);
    static final int ACCENT = Color.rgb(88, 166, 255), GREEN = Color.rgb(63, 185, 80);
    static final int RED = Color.rgb(248, 81, 73), WARN = Color.rgb(210, 153, 34);


    private final Context mCtx;
    private final Hub mHub;
    private final Telemetry mTelemetry;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    DashPainter(Context ctx, Hub hub) {
        mCtx = ctx;
        mHub = hub;
        mTelemetry = hub.telemetry;
    }

    private String s(int id) {
        return mCtx.getString(id);
    }

    private static String num(Double v, int digits) {
        return v == null ? "–" : String.format(Locale.getDefault(), "%." + digits + "f", v);
    }

    /**
     * Alles zeichnen. mapPlaceholder: links ein Kartenfeld mit Position zeichnen (wenn keine echte Karte
     * daneben liegt und die Flaeche breit genug ist).
     */
    void paint(Canvas cv, Rect r, float dp, boolean mapPlaceholder) {
        Paint p = mPaint;
        p.reset();
        p.setAntiAlias(true);
        if (mTelemetry.fields().isEmpty()) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(DIM);
            p.setTextSize(22 * dp);
            cv.drawText(s(mHub.linked() ? R.string.waiting : R.string.no_data), r.exactCenterX(), r.exactCenterY(), p);
        } else {
            dashboard(cv, p, r, dp, mapPlaceholder);
        }
        if (mHub.showDiagnosis()) drawDebug(cv, p, r, dp);
    }

    /*
     * Aufbau wie das Dashboard von IDmate: links die Karte, rechts Ladezustand mit Balken, Reichweite und die
     * uebrigen Werte. Ist die Flaeche schmal (geteilte Ansicht von Android Auto), entfaellt das Kartenfeld.
     */
    private void dashboard(Canvas cv, Paint p, Rect r, float dp, boolean mapPlaceholder) {
        float pad = 24 * dp, top = r.top + 76 * dp, bottom = r.bottom - 64 * dp;
        boolean wide = mapPlaceholder && r.width() > 1.35f * r.height();
        float colL = r.left + pad, colR = r.right - pad;
        if (wide) {
            float mapR = r.left + r.width() * 0.56f;
            mapPanel(cv, p, new RectF(r.left + pad, top, mapR, bottom), dp);
            colL = mapR + pad * 1.4f;
        }
        float y = top + 6 * dp;

        /* Ladezustand */
        Double soc = mTelemetry.number("soc");
        y = label(cv, p, s(R.string.dash_soc), colL, y, dp);
        y = big(cv, p, num(soc, 0), "%", colL, y, dp);
        float barH = 9 * dp;
        p.setColor(PANEL2);
        cv.drawRoundRect(colL, y, colR, y + barH, barH / 2, barH / 2, p);
        if (soc != null) {
            boolean charging = Boolean.TRUE.equals(mTelemetry.flag("charging"));
            p.setColor(soc < 15 ? RED : charging ? ACCENT : GREEN);
            float w = (colR - colL) * (float) Math.max(0, Math.min(100, soc)) / 100f;
            cv.drawRoundRect(colL, y, colL + Math.max(w, barH), y + barH, barH / 2, barH / 2, p);
        }
        y += barH + 12 * dp;
        if (Boolean.TRUE.equals(mTelemetry.flag("charging"))) {
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(ACCENT);
            p.setTextSize(18 * dp);
            cv.drawText(s(Boolean.TRUE.equals(mTelemetry.flag("dc")) ? R.string.charging_dc : R.string.charging), colL, y + 16 * dp, p);
            y += 26 * dp;
        }
        y += 14 * dp;

        /* Reichweite */
        y = label(cv, p, s(R.string.dash_range), colL, y, dp);
        y = big(cv, p, num(mTelemetry.number("range"), 0), "km", colL, y, dp);
        y += 10 * dp;

        /* Zeilen: Name links, Wert rechts */
        Telemetry.Summary sum = mTelemetry.summary();
        if (sum.consumption != null)
            y = row(cv, p, mCtx.getString(R.string.dash_consumption, sum.trips),
                    num(sum.consumption, 1) + " kWh/100km", colL, colR, y, dp);
        y = row(cv, p, s(R.string.dash_power), num(mTelemetry.number("power"), 0) + " kW", colL, colR, y, dp);
        y = row(cv, p, s(R.string.dash_speed), num(mTelemetry.number("speed"), 0) + " km/h", colL, colR, y, dp);
        if (mTelemetry.number("t_ext") != null || mTelemetry.number("t_batt") != null)
            y = row(cv, p, s(R.string.dash_temp), num(mTelemetry.number("t_ext"), 0) + " °C / "
                    + num(mTelemetry.number("t_batt"), 0) + " °C", colL, colR, y, dp);
        if (mTelemetry.number("odometer") != null)
            y = row(cv, p, s(R.string.dash_odometer), num(mTelemetry.number("odometer"), 0) + " km", colL, colR, y, dp);
        if (sum.tripKm != null && y < bottom - 60 * dp)
            y = row(cv, p, s(R.string.dash_last_trip), num(sum.tripKm, 1) + " km"
                    + (sum.tripTo != null ? " · " + sum.tripTo : ""), colL, colR, y, dp);
        if (sum.chargeKwh != null && y < bottom - 60 * dp)
            y = row(cv, p, s(R.string.dash_last_charge), num(sum.chargeKwh, 1) + " kWh"
                    + (sum.chargeKw != null ? " · Ø " + num(sum.chargeKw, 0) + " kW" : "")
                    + (Boolean.TRUE.equals(sum.chargeDc) ? " DC" : "")
                    + (sum.chargeAt != null ? " · " + sum.chargeAt : ""), colL, colR, y, dp);

        /* Alter des Ladezustands, wie "Vor 3 Min" im Dashboard */
        Telemetry.Value sv = mTelemetry.get("soc");
        if (sv != null) {
            long sec = Math.max(0, (System.currentTimeMillis() - sv.tsMs) / 1000);
            String age = sec < 90 ? sec + " s" : sec < 5400 ? sec / 60 + " min" : sec / 3600 + " h";
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(sec > 300 ? WARN : DIM);
            p.setTextSize(16 * dp);
            cv.drawText(mCtx.getString(R.string.dash_age, age), colL, y + 22 * dp, p);
        }
    }

    private float label(Canvas cv, Paint p, String text, float x, float y, float dp) {
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(DIM);
        p.setTextSize(15 * dp);
        p.setLetterSpacing(0.08f);
        cv.drawText(text, x, y + 15 * dp, p);
        p.setLetterSpacing(0);
        return y + 22 * dp;
    }

    private float big(Canvas cv, Paint p, String value, String unit, float x, float y, float dp) {
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(TEXT);
        p.setFakeBoldText(true);
        p.setTextSize(58 * dp);
        cv.drawText(value, x, y + 52 * dp, p);
        float w = p.measureText(value);
        p.setFakeBoldText(false);
        p.setColor(DIM);
        p.setTextSize(22 * dp);
        cv.drawText(unit, x + w + 8 * dp, y + 52 * dp, p);
        return y + 66 * dp;
    }

    private float row(Canvas cv, Paint p, String name, String value, float l, float r, float y, float dp) {
        p.setTextSize(19 * dp);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(TEXT);
        p.setFakeBoldText(true);
        float vw = p.measureText(value), max = (r - l) * 0.62f;
        String v = value;
        while (vw > max && v.length() > 4) {                    /* lange Ortsnamen kuerzen */
            v = v.substring(0, v.length() - 2);
            vw = p.measureText(v + "…");
        }
        cv.drawText(v.equals(value) ? v : v + "…", r, y + 22 * dp, p);
        p.setFakeBoldText(false);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(DIM);
        /* der Name endet vor dem Wert: lange Namen ("Verbrauch (Schnitt aus 5 Fahrten)") liefen in der schmalen
           Spalte neben der Karte in den Wert hinein (16.11.2026) */
        float room = (r - l) - vw - 14 * dp;
        String n = name;
        if (p.measureText(n) > room) {
            while (n.length() > 3 && p.measureText(n + "…") > room) n = n.substring(0, n.length() - 1);
            n = n.trim() + "…";
        }
        cv.drawText(n, l, y + 22 * dp, p);
        return y + 34 * dp;
    }

    /** Kartenfeld. Die Karte selbst kommt vom Server (noch nicht gebaut); bis dahin Position und Hinweis. */
    private void mapPanel(Canvas cv, Paint p, RectF m, float dp) {
        p.setColor(PANEL);
        cv.drawRoundRect(m, 18 * dp, 18 * dp, p);
        Double lat = mTelemetry.number("lat"), lon = mTelemetry.number("lon");
        float cx = m.centerX(), cy = m.centerY();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(PANEL2);
        for (int i = 1; i <= 3; i++) cv.drawCircle(cx, cy, i * Math.min(m.width(), m.height()) / 7f, p);
        p.setStyle(Paint.Style.FILL);
        if (lat != null && lon != null) {
            p.setColor(ACCENT);
            cv.drawCircle(cx, cy, 9 * dp, p);
            p.setColor(BG);
            cv.drawCircle(cx, cy, 4 * dp, p);
        }
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(DIM);
        p.setTextSize(16 * dp);
        cv.drawText(lat != null && lon != null
                ? String.format(Locale.ROOT, "%.5f, %.5f", lat, lon) : s(R.string.dash_no_pos), cx, m.bottom - 44 * dp, p);
        cv.drawText(s(R.string.dash_map), cx, m.bottom - 20 * dp, p);
    }

    /** Zeile je Quelle: Name, Alter des letzten Telegramms, Anzahl, Luecken, Fehler. */
    private void drawDebug(Canvas cv, Paint p, Rect r, float dp) {
        // unten rechts, rechtsbuendig: dort liegen keine Werte des Dashboards und nicht die Bedienleiste (links)
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(15 * dp);
        float y = r.bottom - 14 * dp;
        long now = System.currentTimeMillis();
        if (mTelemetry.only() != null) {
            p.setColor(WARN);
            cv.drawText(s(R.string.dbg_only) + " " + mTelemetry.only(), r.right - 20 * dp, y, p);
            y -= 20 * dp;
        }
        for (Map.Entry<String, Telemetry.SourceState> e : mTelemetry.sources().entrySet()) {
            Telemetry.SourceState st = e.getValue();
            StringBuilder sb = new StringBuilder(e.getKey());
            if (st.lastOkMs > 0) {
                sb.append(" · ").append((now - st.lastOkMs) / 1000).append(" s");
                sb.append(" · #").append(st.received);
                if (st.gaps > 0) sb.append(" · ").append(s(R.string.dbg_gaps)).append(' ').append(st.gaps);
            }
            if (st.error != null) sb.append(" · ").append(st.error);
            p.setColor(st.error != null ? WARN : DIM);
            cv.drawText(sb.toString(), r.right - 20 * dp, y, p);
            y -= 20 * dp;
        }
    }
}
