package net.prototux.aare.dash;

import android.content.Context;

/**
 * IDmate's own values for the rest of Mate (package-private otherwise): the battery's state of charge and the
 * range, from the IDmate server or the stick. Car apps (ABRP) get them when the car itself reports no energy
 * level (wish O102: "dann geben wir ihm halt den SoC").
 */
public final class DashData {
    private DashData() {}

    /** Values older than this are not passed on. */
    private static final long FRESH_MS = 10 * 60_000L;

    /** State of charge in percent, or null when IDmate has none or it is stale. */
    public static Double soc(Context c) { return fresh(c, "soc"); }

    /** Range in km, or null. */
    public static Double rangeKm(Context c) { return fresh(c, "range"); }

    /** When the state of charge was measured (ms since 1970), or 0. */
    public static long socAtMs(Context c) {
        Telemetry.Value v = Hub.get(c).telemetry.get("soc");
        return v != null ? v.tsMs : 0;
    }

    /**
     * State of charge in percent however old, down to [maxAgeMs]: a parked car's battery stays where it is, so
     * planning a trip (Google Maps' charging stops, O107) may use the value from the last drive.
     */
    public static Double socWithin(Context c, long maxAgeMs) {
        Telemetry.Value v = Hub.get(c).telemetry.get("soc");
        if (v == null || !(v.v instanceof Number)) return null;
        if (v.tsMs > 0 && System.currentTimeMillis() - v.tsMs > maxAgeMs) return null;
        return ((Number) v.v).doubleValue();
    }

    /** Outside temperature in degrees Celsius as the car last reported it (field t_ext), at most six hours old; or null. */
    public static Double outsideCelsius(Context c) {
        Telemetry.Value v = Hub.get(c).telemetry.get("t_ext");
        if (v == null || !(v.v instanceof Number)) return null;
        if (v.tsMs > 0 && System.currentTimeMillis() - v.tsMs > 6 * 3600_000L) return null;
        return ((Number) v.v).doubleValue();
    }

    /** The battery's temperature from IDmate or the stick (Navi N-019: warming before a fast charger), null when older than 30 min. */
    public static Double batteryCelsius(Context c) {
        Telemetry.Value v = Hub.get(c).telemetry.get("t_batt");
        if (v == null || !(v.v instanceof Number)) return null;
        if (v.tsMs > 0 && System.currentTimeMillis() - v.tsMs > 30 * 60_000L) return null;
        return ((Number) v.v).doubleValue();
    }

    /** When the battery's temperature was read (epoch ms), 0 when never. */
    public static long batteryAtMs(Context c) {
        Telemetry.Value v = Hub.get(c).telemetry.get("t_batt");
        return v == null ? 0L : v.tsMs;
    }

    /** IDmate's own consumption model of the car as the server serves it, for the rest of Mate. */
    public static final class Energy {
        /** km/h actually driven (rising) and kWh/100 km at each, heating included, at [tExt]. */
        public final double[] speeds, kwh100;
        public final Double tExt, capacityKwh, recentKwh100, recentSpeed, recentKm;
        public final int trips;
        Energy(Telemetry.EnergyModel m) {
            speeds = m.speeds; kwh100 = m.kwh100; tExt = m.tExt; capacityKwh = m.capacityKwh;
            recentKwh100 = m.recentKwh100; recentSpeed = m.recentSpeed; recentKm = m.recentKm; trips = m.trips;
        }
    }

    /**
     * IDmate's rain raster (read API /rain, 1.2.345, 18.11.2026: "Die Regenberechnung ist fertig"): cells of
     * [cellKm] with rain, by grade 1-3, for each 15-minute picture (past, now, forecast).
     */
    public static final class Rain {
        public final double cellKm;
        public final long[] times;          /* ms since 1970, oldest first */
        public final String[] kinds;        /* past, now, forecast */
        public final double[][] cells;      /* per frame: lat, lon, level, lat, lon, level ... */
        public final String[] sources;      /* all sources named in the frames, for the attribution */
        public final long receivedMs;
        public Rain(double cellKm, long[] times, String[] kinds, double[][] cells, String[] sources) {
            this.cellKm = cellKm; this.times = times; this.kinds = kinds; this.cells = cells; this.sources = sources;
            this.receivedMs = System.currentTimeMillis();
        }
    }

    /**
     * IDmate's rain raster for a plan made now, or null. The raster is fetched only when asked for here (megabytes on a
     * rainy day - no longer every five minutes all day): waits up to [waitMs] for the server's answer, so not for the
     * main thread. A raster up to ten minutes old is taken as it is.
     */
    public static Rain rainNow(Context c, long waitMs) {
        Hub hub = Hub.get(c);
        long until = System.currentTimeMillis() + waitMs;
        while (hub.awaitsRain() && System.currentTimeMillis() < until) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        Rain r = hub.telemetry.rain();
        return r != null && System.currentTimeMillis() - r.receivedMs < 600_000L ? r : null;
    }

    /** Extra kWh/100 km in rain by grade (index 1-3) as IDmate learned it; zeros without a model or a learned grade. */
    public static double[] rainExtra(Context c) {
        Telemetry.EnergyModel m = Hub.get(c).telemetry.energyModel();
        return m != null ? m.rain.clone() : new double[4];
    }

    /** Memory is tight: the dashboard's stage kept behind another area is taken down ({@link CarStage#dropParked}). */
    public static boolean dropParkedStage() { return CarStage.dropParked(); }

    /** Debug builds: an answer of /energy-model as if the server had sent it (tool debug_energy_model). */
    public static void debugEnergyModel(Context c, String json) { Hub.get(c).telemetry.acceptEnergyModel(json); }

    /** The model, when the server served one with a consumption curve within the last hour; null otherwise. */
    public static Energy energy(Context c) {
        Telemetry.EnergyModel m = Hub.get(c).telemetry.energyModel();
        if (m == null || m.speeds.length < 2 || System.currentTimeMillis() - m.receivedMs > 3600_000L) return null;
        return new Energy(m);
    }

    /** Average consumption over the last trips in kWh/100 km (IDmate's read API), or null. */
    public static Double consumption(Context c) { return Hub.get(c).telemetry.summary().consumption; }

    /** A number of IDmate's values (stick or server) at most [maxAgeMs] old, else null (trip log, System L-076). */
    public static Double number(Context c, String field, long maxAgeMs) {
        Telemetry.Value v = Hub.get(c).telemetry.get(field);
        if (v == null || !(v.v instanceof Number)) return null;
        if (v.tsMs > 0 && System.currentTimeMillis() - v.tsMs > maxAgeMs) return null;
        return ((Number) v.v).doubleValue();
    }

    /** A yes/no of IDmate's values (charging, dc) at most [maxAgeMs] old, else null. */
    public static Boolean flag(Context c, String field, long maxAgeMs) {
        Telemetry.Value v = Hub.get(c).telemetry.get(field);
        if (v == null || !(v.v instanceof Boolean)) return null;
        if (v.tsMs > 0 && System.currentTimeMillis() - v.tsMs > maxAgeMs) return null;
        return (Boolean) v.v;
    }

    private static Double fresh(Context c, String field) {
        Telemetry.Value v = Hub.get(c).telemetry.get(field);
        if (v == null || !(v.v instanceof Number)) return null;
        if (v.tsMs > 0 && System.currentTimeMillis() - v.tsMs > FRESH_MS) return null;
        return ((Number) v.v).doubleValue();
    }
}
