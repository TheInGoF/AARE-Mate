/*
 * IDmate Auto: Fahrzeugdaten fuer die Anzeige.
 *
 * Alle Quellen (Lese-API des Servers, spaeter Stick ueber Bluetooth, Fahrzeug ueber Android Auto) liefern
 * dasselbe Telegramm {v, src, ts, seq, type, data} (Uebergabe vom 12.11.2026, docs/read-api.md in IDMate).
 * Ein Feld, das eine Quelle nicht kennt, fehlt im Telegramm; hier bleibt es dann null. Je Feld gilt der
 * neueste Zeitstempel, bei Gleichstand die Rangfolge Stick/OBD, Fahrzeug, Server, Tablet.
 */
package net.prototux.aare.dash;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class Telemetry {
    /** Quellen in der Rangfolge der Uebergabe (kleiner = Vorrang bei gleichem Zeitstempel). */
    static final String[] SOURCES = { "stick", "obd", "car", "backend", "tablet" };
    /** How long a live value of the car stays ahead of the server's (System L-087). */
    static final long LIVE_HOLD_MS = 120_000L;

    /** Ein Wert mit Herkunft. */
    static final class Value {
        final Object v;
        final String src;
        final long tsMs;
        Value(Object v, String src, long tsMs) { this.v = v; this.src = src; this.tsMs = tsMs; }
    }

    /** Stand je Quelle fuer die Fehlersuche. */
    static final class SourceState {
        long lastOkMs;          /* Empfang des letzten Telegramms (Uhr des Tablets) */
        long lastSeq = -1;
        long received, gaps;
        String error;           /* letzter Fehler, null = keiner */
        String note;            /* Hinweis der Quelle (z. B. Status je Wert der Fahrzeugdaten-Sonde) */
        String lastRaw;         /* letztes Telegramm im Wortlaut (gekuerzt), fuer die Fehlersuche */
    }

    /** Aus den Listen der Lese-API (Fahrten, Laden); null-Felder = unbekannt. */
    static final class Summary {
        Double consumption;         /* kWh/100 km, nach Strecke gewichtet ueber die letzten Fahrten */
        int trips;                  /* Anzahl Fahrten im Schnitt */
        Double tripKm;              /* letzte Fahrt */
        String tripTo;
        Double chargeKwh;           /* letzter Ladevorgang */
        String chargeAt;
        Boolean chargeDc;
        Double chargeKw;            /* Durchschnittsleistung (ab IDMate 1.2.339) */
    }

    private Summary mSummary = new Summary();
    private final Map<String, Value> mFields = new LinkedHashMap<>();
    /* System L-087: the last value of each source apart (the car's range beside IDmate's) */
    private final Map<String, Map<String, Value>> mBySource = new LinkedHashMap<>();
    /* the consumption of now from the car's power and speed (System L-087), smoothed over about half a minute */
    private double mNowKwh100 = Double.NaN;
    private long mNowAt;
    private final Map<String, SourceState> mSources = new LinkedHashMap<>();
    private String mOnly;       /* Fehlersuche: nur diese Quelle anzeigen; null = alle nach Rangfolge */

    private static int rank(String src) {
        for (int i = 0; i < SOURCES.length; i++) if (SOURCES[i].equals(src)) return i;
        return SOURCES.length;
    }

    synchronized SourceState source(String src) {
        SourceState s = mSources.get(src);
        if (s == null) mSources.put(src, s = new SourceState());
        return s;
    }

    synchronized Map<String, SourceState> sources() {
        return new LinkedHashMap<>(mSources);
    }

    synchronized void note(String src, String text) {
        note(src, text, text);
    }

    /** A note shown as [text] (the device's language), logged as [logged] (English). */
    synchronized void note(String src, String text, String logged) {
        /* the dashboard's page into the kept diagnosis log too (18.11.2026: "Das Dashboard laedt mit 51 nicht mehr" -
           the exported log had nothing of the dashboard to go by) */
        if ("stage".equals(src) && text != null && !text.equals(source(src).note)) net.prototux.aare.DiagLog.INSTANCE.add("dashboard: " + logged);
        source(src).note = text;
    }

    synchronized void error(String src, String message) {
        source(src).error = message;
    }

    /** Nur eine Quelle anzeigen (Schalter der Fehlersuche); null = alle. Verwirft die bisherigen Werte. */
    synchronized void only(String src) {
        mOnly = src;
        mFields.clear();
        /* each source's own values too: accept fills them from the chosen source only (else the server's range stayed
           beside the stick's) */
        mBySource.clear();
        mNowKwh100 = Double.NaN;
    }

    synchronized String only() {
        return mOnly;
    }

    /** Ein Telegramm uebernehmen. Liefert false, wenn es kein gueltiges Status-Telegramm ist. */
    synchronized boolean accept(String json) {
        try {
            JSONObject t = new JSONObject(json);
            if (t.optInt("v") != 1) return false;
            String src = t.optString("src");
            String type = t.optString("type");
            JSONObject data = t.optJSONObject("data");
            if (src.isEmpty() || data == null) return false;
            if (!java.util.Arrays.asList(SOURCES).contains(src)) return false;
            long ts;
            try {
                ts = Instant.parse(t.optString("ts")).toEpochMilli();
            } catch (RuntimeException e) {
                return false;
            }
            SourceState s = source(src);
            long seq = t.optLong("seq", -1);
            /* Luecken zaehlen nur, wo sie etwas bedeuten: der Zaehler des Servers laeuft ueber alle Abfragen des
               Fahrzeugs (auch anderer Geraete) und beginnt nach dessen Neustart neu - dort ist eine Luecke kein Verlust */
            if (!"backend".equals(src) && seq >= 0 && s.lastSeq >= 0 && seq > s.lastSeq + 1) s.gaps += seq - s.lastSeq - 1;
            if (seq >= 0) s.lastSeq = seq;
            s.received++;
            s.lastOkMs = System.currentTimeMillis();
            s.error = null;
            s.lastRaw = json.length() > 600 ? json.substring(0, 600) + "…" : json;
            if (!"status".equals(type) && !"position".equals(type) && !"charge".equals(type)) return true;
            if (data.has("items")) return true;                 /* Liste, kein Zustand */
            if (mOnly != null && !mOnly.equals(src)) return true;
            for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = data.get(k);
                if (v == JSONObject.NULL) continue;
                Value nv = new Value(v, src, ts);
                Map<String, Value> mine = mBySource.get(src);
                if (mine == null) mBySource.put(src, mine = new LinkedHashMap<>());
                mine.put(k, nv);
                Value old = mFields.get(k);
                /* System L-087: what the car itself says (stick, OBD, Android Auto) holds for two minutes against the
                   server - IDmate's own range or a level computed later would else push the car's live value aside */
                if (old != null && "backend".equals(src) && !"backend".equals(old.src) && !"tablet".equals(old.src)
                        && System.currentTimeMillis() - old.tsMs < LIVE_HOLD_MS) continue;
                if (old == null || ts > old.tsMs || (ts == old.tsMs && rank(src) <= rank(old.src)))
                    mFields.put(k, nv);
            }
            if (!"backend".equals(src) && "status".equals(type) && data.has("power") && data.has("speed")) nowConsumption(data, src, ts);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * The consumption of now in kWh/100 km from a live source's power (kW) and speed (km/h): only while moving faster
     * than 10 km/h, smoothed with a time constant of 30 s (a second's value jumps with every push of the pedal). Kept as
     * the field "consumption_now" of that source.
     */
    private void nowConsumption(JSONObject data, String src, long ts) {
        double kw = data.optDouble("power", Double.NaN), kmh = data.optDouble("speed", Double.NaN);
        if (Double.isNaN(kw) || Double.isNaN(kmh) || kmh < 10) return;
        double x = kw / kmh * 100.0;
        if (Double.isNaN(mNowKwh100) || ts - mNowAt > 120_000L || ts < mNowAt) mNowKwh100 = x;
        else {
            double a = 1 - Math.exp(-(ts - mNowAt) / 30_000.0);
            mNowKwh100 += a * (x - mNowKwh100);
        }
        mNowAt = ts;
        Value nv = new Value(Math.round(mNowKwh100 * 10) / 10.0, src, ts);
        mFields.put("consumption_now", nv);
        Map<String, Value> mine = mBySource.get(src);
        if (mine == null) mBySource.put(src, mine = new LinkedHashMap<>());
        mine.put("consumption_now", nv);
    }

    /** The last value of [field] from [src] alone, null when that source never sent it. */
    synchronized Value from(String src, String field) {
        Map<String, Value> m = mBySource.get(src);
        return m == null ? null : m.get(field);
    }

    /** Alle bekannten Felder mit Herkunft (Kopie), in der Reihenfolge des ersten Eintreffens. */
    synchronized Map<String, Value> fields() {
        return new LinkedHashMap<>(mFields);
    }

    synchronized Summary summary() {
        return mSummary;
    }

    /**
     * IDmate's own consumption model of the car (read API /energy-model, from IDMate 1.2.341): kWh/100 km by
     * the speed actually driven at the outside temperature of now, heating included. Null fields = not served.
     */
    static final class EnergyModel {
        double[] speeds = new double[0];    /* km/h, rising */
        double[] kwh100 = new double[0];
        Double tExt, capacityKwh;
        Double recentKwh100, recentSpeed, recentKm;     /* the trips ended in the last hours */
        int trips;
        long receivedMs;
        /** Extra kWh/100 km in rain by grade 1-3 (index 1-3), learned by IDmate (1.2.345); 0 = none learned. */
        double[] rain = new double[4];
    }

    private DashData.Rain mRain;

    /**
     * The answer of /rain: 0 no frames yet (ask again in 30 s), 1 the measured pictures without the forecast yet
     * ("complete": false, IDmate 1.2.346 - ask again in 5 s), 2 complete.
     */
    int acceptRain(String json) {
        try {
            JSONObject t = new JSONObject(json);
            if (!"rain".equals(t.optString("type"))) return 0;
            JSONObject d = t.getJSONObject("data");
            org.json.JSONArray f = d.optJSONArray("frames");
            if (f == null || f.length() == 0) return 0;
            long[] times = new long[f.length()];
            double[][] cells = new double[f.length()][];
            for (int i = 0; i < f.length(); i++) {
                JSONObject fr = f.getJSONObject(i);
                times[i] = java.time.Instant.parse(fr.getString("t")).toEpochMilli();
                org.json.JSONArray c = fr.optJSONArray("cells");
                int n = c == null ? 0 : c.length();
                double[] a = new double[n * 3];
                for (int k = 0; k < n; k++) {
                    org.json.JSONArray cell = c.getJSONArray(k);
                    a[3 * k] = cell.getDouble(0); a[3 * k + 1] = cell.getDouble(1); a[3 * k + 2] = cell.getDouble(2);
                }
                cells[i] = a;
            }
            /* read without the lock (megabytes on a rainy day); only the finished raster is put in under it */
            DashData.Rain rain = new DashData.Rain(d.optDouble("cell_km", 5.0), times, cells);
            synchronized (this) { mRain = rain; }
            return d.optBoolean("complete", true) ? 2 : 1;
        } catch (org.json.JSONException | java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    synchronized DashData.Rain rain() {
        return mRain;
    }

    private EnergyModel mEnergy;

    /** The answer of /energy-model; anything that is not such a telegram is ignored. */
    synchronized void acceptEnergyModel(String json) {
        try {
            JSONObject t = new JSONObject(json);
            if (!"energy_model".equals(t.optString("type"))) return;
            JSONObject d = t.getJSONObject("data");
            EnergyModel m = new EnergyModel();
            org.json.JSONArray c = d.optJSONArray("consumption");
            if (c != null) {
                m.speeds = new double[c.length()];
                m.kwh100 = new double[c.length()];
                for (int i = 0; i < c.length(); i++) {
                    m.speeds[i] = c.getJSONObject(i).getDouble("v");
                    m.kwh100[i] = c.getJSONObject(i).getDouble("kwh100");
                }
            }
            m.tExt = d.has("t_ext") ? d.getDouble("t_ext") : null;
            m.capacityKwh = d.has("capacity") ? d.getDouble("capacity") : null;
            JSONObject r = d.optJSONObject("recent");
            if (r != null && r.has("consumption") && r.has("speed")) {
                m.recentKwh100 = r.getDouble("consumption");
                m.recentSpeed = r.getDouble("speed");
                m.recentKm = r.has("distance") ? r.getDouble("distance") : null;
            }
            JSONObject basis = d.optJSONObject("basis");
            m.trips = basis != null ? basis.optInt("trips") : 0;
            org.json.JSONArray rain = d.optJSONArray("rain");
            if (rain != null) for (int i = 0; i < rain.length(); i++) {
                JSONObject g = rain.getJSONObject(i);
                int level = g.optInt("level");
                if (level >= 1 && level <= 3) m.rain[level] = g.optDouble("kwh100", 0);
            }
            m.receivedMs = System.currentTimeMillis();
            mEnergy = m;
        } catch (org.json.JSONException e) {
            /* kein Energiemodell: der alte Stand bleibt */
        }
    }

    synchronized EnergyModel energyModel() {
        return mEnergy;
    }

    /** Liste "trip" oder "charge" der Lese-API auswerten (neueste zuerst). */
    synchronized void acceptList(String json) {
        try {
            JSONObject t = new JSONObject(json);
            org.json.JSONArray items = t.getJSONObject("data").getJSONArray("items");
            Summary s = new Summary();
            Summary old = mSummary;
            s.consumption = old.consumption; s.trips = old.trips; s.tripKm = old.tripKm; s.tripTo = old.tripTo;
            s.chargeKwh = old.chargeKwh; s.chargeAt = old.chargeAt; s.chargeDc = old.chargeDc;
            s.chargeKw = old.chargeKw;
            if ("trip".equals(t.optString("type"))) {
                double km = 0, kwh = 0;
                int n = 0;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    if (i == 0) {
                        s.tripKm = it.has("distance") ? it.getDouble("distance") : null;
                        s.tripTo = it.optString("to", null);
                    }
                    if (it.has("distance") && it.has("energy") && it.getDouble("distance") >= 1) {
                        km += it.getDouble("distance");
                        kwh += it.getDouble("energy");
                        n++;
                    }
                }
                s.consumption = km > 0 ? kwh / km * 100 : null;
                s.trips = n;
            } else if ("charge".equals(t.optString("type")) && items.length() > 0) {
                JSONObject it = items.getJSONObject(0);
                s.chargeKwh = it.has("energy") ? it.getDouble("energy") : null;
                s.chargeAt = it.optString("at", null);
                s.chargeDc = it.has("dc") ? it.getBoolean("dc") : null;
                s.chargeKw = it.has("power_avg") ? it.getDouble("power_avg") : null;
            }
            mSummary = s;
        } catch (JSONException e) {
            /* unbrauchbare Liste: alte Zusammenfassung bleibt */
        }
    }

    synchronized Value get(String field) {
        return mFields.get(field);
    }

    /** Zahl eines Feldes oder null. */
    Double number(String field) {
        Value v = get(field);
        return v != null && v.v instanceof Number ? ((Number) v.v).doubleValue() : null;
    }

    Boolean flag(String field) {
        Value v = get(field);
        return v != null && v.v instanceof Boolean ? (Boolean) v.v : null;
    }
}
