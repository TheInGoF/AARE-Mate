/*
 * IDmate Auto: Fahrzeugdaten-Sonde (Quelle "car"). Fragt ueber Android Auto ab, was das Auto selbst liefert:
 * Ladestand und Reichweite (CarInfo.EnergyLevel), Tempo, Kilometerstand, Modell und Antriebsart.
 * Was ankommt, haengt vom Hersteller ab - genau das soll die Sonde am echten Fahrzeug zeigen. Je Wert wird
 * der Status festgehalten (ok / nicht eingebaut / nicht verfuegbar) und in der Fehlersuche angezeigt.
 *
 * Braucht Car-App-API 3 und je Wert eine Berechtigung, die der Benutzer am Tablet erteilt:
 *   com.google.android.gms.permission.CAR_FUEL (Ladestand, Reichweite), CAR_SPEED, CAR_MILEAGE.
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.content.pm.PackageManager;
import android.util.Log;

import androidx.car.app.CarContext;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.CarInfo;
import androidx.car.app.hardware.info.EnergyLevel;
import androidx.car.app.hardware.info.EnergyProfile;
import androidx.car.app.hardware.info.Mileage;
import androidx.car.app.hardware.info.Model;
import androidx.car.app.hardware.info.Speed;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

final class CarProbe {
    private static final String TAG = "IDmateCar";
    static final String SRC = "car";
    static final String P_FUEL = "com.google.android.gms.permission.CAR_FUEL";
    static final String P_SPEED = "com.google.android.gms.permission.CAR_SPEED";
    static final String P_MILEAGE = "com.google.android.gms.permission.CAR_MILEAGE";

    private final CarContext mCtx;
    private final Telemetry mTelemetry;
    private final Runnable mChanged;
    private final Map<String, String> mStatus = new LinkedHashMap<>();
    private CarInfo mInfo;
    private long mSeq;
    private boolean mEnergy, mSpeedOn, mMileageOn;

    private final OnCarDataAvailableListener<EnergyLevel> mEnergyL = e -> {
        JSONObject d = new JSONObject();
        put(d, "soc", e.getBatteryPercent(), 1f);
        put(d, "range", e.getRangeRemainingMeters(), 0.001f);
        send(d);
    };
    private final OnCarDataAvailableListener<Speed> mSpeedL = s -> {
        JSONObject d = new JSONObject();
        put(d, "speed", s.getRawSpeedMetersPerSecond(), 3.6f);
        send(d);
    };
    private final OnCarDataAvailableListener<Mileage> mMileageL = m -> {
        JSONObject d = new JSONObject();
        /* Heisst "Meters", liefert ueber Android Auto aber Kilometer (Simulator, 12.11.2026: Vorgabe 23456 km
           kam als 23456.0 an). Am echten Fahrzeug gegen den Tacho pruefen. */
        put(d, "odometer", m.getOdometerMeters(), 1f);
        send(d);
    };

    CarProbe(CarContext ctx, Telemetry telemetry, Runnable changed) {
        mCtx = ctx;
        mTelemetry = telemetry;
        mChanged = changed;
    }

    private boolean granted(String permission) {
        return mCtx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Berechtigungen, die noch fehlen (zum Anfragen am Tablet). */
    List<String> missing() {
        List<String> l = new ArrayList<>();
        for (String p : new String[] { P_FUEL, P_SPEED, P_MILEAGE }) if (!granted(p)) l.add(p);
        return l;
    }

    void start() {
        stop();
        if (mCtx.getCarAppApiLevel() < 3) {
            note("Car-App-API " + mCtx.getCarAppApiLevel() + " < 3: keine Fahrzeugdaten");
            return;
        }
        Executor ex = mCtx.getMainExecutor();
        try {
            mInfo = mCtx.getCarService(CarHardwareManager.class).getCarInfo();
            mInfo.fetchModel(ex, (Model m) -> {
                status("model", m.getManufacturer().getStatus());
                String name = text(m.getManufacturer()) + " " + text(m.getName()) + " " + text(m.getYear());
                mStatus.put("model", name.trim().isEmpty() ? statusName(m.getName().getStatus()) : name.trim());
                publish();
            });
            mInfo.fetchEnergyProfile(ex, (EnergyProfile p) -> {
                mStatus.put("ev_connectors", p.getEvConnectorTypes().getStatus() == CarValue.STATUS_SUCCESS
                        ? String.valueOf(p.getEvConnectorTypes().getValue())
                        : statusName(p.getEvConnectorTypes().getStatus()));
                publish();
            });
            if (granted(P_FUEL)) {
                mInfo.addEnergyLevelListener(ex, mEnergyL);
                mEnergy = true;
            } else mStatus.put("soc", "Berechtigung fehlt");
            if (granted(P_SPEED)) {
                mInfo.addSpeedListener(ex, mSpeedL);
                mSpeedOn = true;
            } else mStatus.put("speed", "Berechtigung fehlt");
            if (granted(P_MILEAGE)) {
                mInfo.addMileageListener(ex, mMileageL);
                mMileageOn = true;
            } else mStatus.put("odometer", "Berechtigung fehlt");
            publish();
        } catch (RuntimeException e) {
            Log.w(TAG, "Sonde: " + e);
            note(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void stop() {
        if (mInfo == null) return;
        try {
            if (mEnergy) mInfo.removeEnergyLevelListener(mEnergyL);
            if (mSpeedOn) mInfo.removeSpeedListener(mSpeedL);
            if (mMileageOn) mInfo.removeMileageListener(mMileageL);
        } catch (RuntimeException e) {
            Log.w(TAG, "Sonde abmelden: " + e);
        }
        mEnergy = mSpeedOn = mMileageOn = false;
        mInfo = null;
    }

    private static String text(CarValue<?> v) {
        return v.getStatus() == CarValue.STATUS_SUCCESS && v.getValue() != null ? String.valueOf(v.getValue()) : "";
    }

    private static String statusName(int status) {
        switch (status) {
            case CarValue.STATUS_SUCCESS: return "ok";
            case CarValue.STATUS_UNIMPLEMENTED: return "nicht eingebaut";
            case CarValue.STATUS_UNAVAILABLE: return "nicht verfuegbar";
            default: return "unbekannt";
        }
    }

    private void status(String field, int status) {
        mStatus.put(field, statusName(status));
    }

    private void put(JSONObject d, String field, CarValue<Float> v, float factor) {
        status(field, v.getStatus());
        if (v.getStatus() != CarValue.STATUS_SUCCESS || v.getValue() == null) return;
        try {
            d.put(field, Math.round(v.getValue() * factor * 10) / 10.0);
        } catch (JSONException e) {
            /* nur bei NaN/unendlich: Feld weglassen */
        }
    }

    private void send(JSONObject data) {
        try {
            JSONObject t = new JSONObject();
            t.put("v", 1).put("src", SRC).put("ts", Instant.now().toString()).put("seq", ++mSeq)
                    .put("type", "status").put("data", data);
            mTelemetry.accept(t.toString());
        } catch (JSONException e) {
            return;
        }
        publish();
    }

    private void note(String text) {
        mTelemetry.note(SRC, text);
        mChanged.run();
    }

    private void publish() {
        mTelemetry.note(SRC, mStatus.toString());
        mChanged.run();
    }
}
