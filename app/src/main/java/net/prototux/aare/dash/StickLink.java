/*
 * IDmate Auto: Live-Daten direkt vom Telemetrie-Stick ueber Bluetooth (Quelle "stick").
 * Gegenstueck zu mod_live der Stick-Firmware (IDTelemetry docs/live-ble.md):
 *   Dienst 1d7e0001-6d61-7465-2d6c-697665000000, Merkmal 1d7e0002-... (Notify): ein CBOR-Telegramm je Nachricht.
 * Der Stick sendet nur an ein mit Code gekoppeltes Geraet. Die Kopplung fuehrt Android selbst (Dialog mit dem
 * sechsstelligen Code aus den Einstellungen des Sticks); die App speichert keinen Code.
 *
 * Ablauf: Stick suchen (nur Geraete, die den Dienst anbieten) -> verbinden -> groessere Nachrichten aushandeln
 * (MTU 247) -> koppeln, falls noetig -> Nachrichten abonnieren. Reisst die Verbindung, wird nach kurzer Pause
 * neu verbunden. Zustand und letzter Fehler stehen in der Fehlersuche (Telemetry.note / error).
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Collections;
import java.util.UUID;

@SuppressLint("MissingPermission")      /* geprueft in permitted(); ohne Berechtigung tut die Klasse nichts */
final class StickLink {
    private static final String TAG = "IDmateStick";
    static final String SRC = "stick";
    static final UUID SERVICE = UUID.fromString("1d7e0001-6d61-7465-2d6c-697665000000");
    static final UUID TELEGRAM = UUID.fromString("1d7e0002-6d61-7465-2d6c-697665000000");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int MTU = 247, SCAN_MS = 20000, RETRY_MS = 5000;

    private final Context mCtx;
    private final Telemetry mTelemetry;
    private final Runnable mChanged;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private BluetoothGatt mGatt;
    private BluetoothLeScanner mScanner;
    private boolean mRunning, mScanning;
    private int mMtu = 23;
    private final Runnable mRetry = this::connect;
    private final Runnable mScanEnd = () -> {
        stopScan();
        state("kein Stick gefunden (ist \"Live-Daten ueber Bluetooth\" am Stick eingeschaltet?)");
        mMain.postDelayed(mRetry, RETRY_MS * 4);
    };

    StickLink(Context ctx, Telemetry telemetry, Runnable changed) {
        mCtx = ctx.getApplicationContext();
        mTelemetry = telemetry;
        mChanged = changed;
    }

    static String[] permissions() {
        return new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT };
    }

    static boolean permitted(Context c) {
        for (String p : permissions())
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private static String saved(Context c) {
        return c.getSharedPreferences("dash_stick", Context.MODE_PRIVATE).getString("address", null);
    }

    /** Gemerkten Stick vergessen (die Kopplung selbst loescht man in den Bluetooth-Einstellungen von Android). */
    static void forget(Context c) {
        c.getSharedPreferences("dash_stick", Context.MODE_PRIVATE).edit().clear().apply();
    }

    private void state(String text) {
        mTelemetry.note(SRC, text);
        mChanged.run();
    }

    private void fail(String text) {
        mTelemetry.error(SRC, text);
        mChanged.run();
    }

    void start() {
        mMain.post(() -> {
            if (mRunning) return;
            mRunning = true;
            connect();
        });
    }

    void stop() {
        mMain.post(() -> {
            mRunning = false;
            mMain.removeCallbacks(mRetry);
            mMain.removeCallbacks(mScanEnd);
            stopScan();
            close();
        });
    }

    private void close() {
        if (mGatt != null) {
            try {
                mGatt.disconnect();
                mGatt.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "schliessen: " + e);
            }
        }
        mGatt = null;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = mCtx.getSystemService(BluetoothManager.class);
        return m == null ? null : m.getAdapter();
    }

    private void connect() {
        if (!mRunning) return;
        mMain.removeCallbacks(mRetry);
        if (!permitted(mCtx)) {
            fail("Bluetooth-Berechtigung fehlt (Seite am Tablet: \"Stick suchen\")");
            return;
        }
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) {
            fail("Bluetooth ist aus");
            mMain.postDelayed(mRetry, RETRY_MS * 2);
            return;
        }
        String address = saved(mCtx);
        if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) {
            open(ad.getRemoteDevice(address));
            return;
        }
        mScanner = ad.getBluetoothLeScanner();
        if (mScanner == null) {
            fail("Bluetooth-Suche nicht moeglich");
            return;
        }
        state("suche Stick …");
        mScanning = true;
        mScanner.startScan(
                Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), mScan);
        mMain.postDelayed(mScanEnd, SCAN_MS);
    }

    private void stopScan() {
        if (mScanning && mScanner != null) {
            try {
                mScanner.stopScan(mScan);
            } catch (RuntimeException e) {
                Log.w(TAG, "Suche beenden: " + e);
            }
        }
        mScanning = false;
    }

    private final ScanCallback mScan = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (!mScanning) return;
            mMain.removeCallbacks(mScanEnd);
            stopScan();
            mCtx.getSharedPreferences("dash_stick", Context.MODE_PRIVATE).edit()
                    .putString("address", r.getDevice().getAddress()).apply();
            open(r.getDevice());
        }

        @Override public void onScanFailed(int errorCode) {
            mScanning = false;
            mMain.removeCallbacks(mScanEnd);
            fail("Bluetooth-Suche fehlgeschlagen (" + errorCode + ")");
            mMain.postDelayed(mRetry, RETRY_MS * 2);
        }
    };

    private void open(BluetoothDevice d) {
        close();
        state("verbinde mit " + d.getAddress() + " …");
        mGatt = d.connectGatt(mCtx, false, mCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void again(String why) {
        fail(why);
        close();
        if (mRunning) mMain.postDelayed(mRetry, RETRY_MS);
    }

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            mMain.post(() -> {
                if (g != mGatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state("verbunden, handle Nachrichtengroesse aus …");
                    if (!g.requestMtu(MTU)) g.discoverServices();
                } else {
                    again("Verbindung getrennt (Status " + status + ")");
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            mMain.post(() -> {
                if (g != mGatt) return;
                mMtu = mtu;
                g.discoverServices();
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            mMain.post(() -> {
                if (g != mGatt) return;
                BluetoothGattService s = g.getService(SERVICE);
                BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(TELEGRAM);
                if (c == null) {
                    forget(mCtx);                               /* falsches Geraet gemerkt: neu suchen */
                    again("Geraet bietet keine Live-Daten an");
                    return;
                }
                if (g.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                    state("koppeln: Code aus den Einstellungen des Sticks eingeben");
                    g.getDevice().createBond();                 /* der Stick sendet erst nach der Kopplung */
                }
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                if (d == null) {
                    again("Merkmal ohne Abo-Schalter");
                    return;
                }
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            mMain.post(() -> {
                if (g != mGatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS)
                    state("abonniert (MTU " + mMtu + ", " + bond(g.getDevice().getBondState()) + ")");
                else again("Abonnieren abgelehnt (Status " + status + ")");
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            String json = Cbor.toJson(value);
            if (json == null || !mTelemetry.accept(json)) mTelemetry.error(SRC, "unlesbares Telegramm (" + value.length + " B)");
            mChanged.run();
        }
    };

    private static String bond(int s) {
        return s == BluetoothDevice.BOND_BONDED ? "gekoppelt" : s == BluetoothDevice.BOND_BONDING ? "Kopplung laeuft" : "nicht gekoppelt";
    }
}
