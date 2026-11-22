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

    private String mLogged;

    /* every new state of the stick into the kept diagnosis log too (22.11.2026: the log said nothing of the pairing) */
    private void log(String text) {
        if (text.equals(mLogged)) return;
        mLogged = text;
        net.prototux.aare.DiagLog.INSTANCE.add("stick: " + text, null, "DASH");
    }

    private void state(String text) {
        log(text);
        mTelemetry.note(SRC, text);
        mChanged.run();
    }

    private void fail(String text) {
        log(text);
        mTelemetry.error(SRC, text);
        mChanged.run();
    }

    /*
     * Pairing (22.11.2026: "Bluetooth Verbindungscode wurde einmal abgefragt und wurde aber bevor ich fertig eingegeben
     * habe ausgeblendet. Danach wurde kein Code mehr gefragt und verbunden hat er nicht"): Bluetooth gives the code 30 s,
     * then the pairing fails and the stick's window is used up. Before, Mate subscribed at once and waited for good on a
     * connection the stick sends nothing on. Now: pairing first and its end awaited; a failed one is told and Mate waits
     * for the driver ("Stick suchen" again, after "Geraet koppeln" on the stick) instead of trying on its own - outside the
     * stick's window every try would only ask for a code nobody knows.
     */
    private static long sPairAskedAt;
    private boolean mBonding;
    private android.content.BroadcastReceiver mBondWatch;

    /** The driver asked for the stick ("Stick suchen"): pairing may be started for the next few minutes. */
    static void pairNow() { sPairAskedAt = android.os.SystemClock.elapsedRealtime(); }

    private static boolean pairAllowed() { return android.os.SystemClock.elapsedRealtime() - sPairAskedAt < 5 * 60_000L; }

    private void watchBond(BluetoothGatt g) {
        unwatchBond();
        mBondWatch = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || g != mGatt || !d.getAddress().equals(g.getDevice().getAddress())) return;
                int s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (s == BluetoothDevice.BOND_BONDED) {
                    mBonding = false; unwatchBond();
                    state(mCtx.getString(R.string.stick_paired));
                    subscribe(g);
                } else if (s == BluetoothDevice.BOND_NONE
                        && i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING) {
                    /* only a pairing that was under way and ended without a key (an old one dropped is no failure) */
                    mBonding = false; unwatchBond();
                    sPairAskedAt = 0;
                    fail(mCtx.getString(R.string.stick_pair_failed));
                    close();                                    /* no try of its own: the driver starts it again */
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        /* sent by the Bluetooth process, not by Android itself: exported, or it would not arrive (Android 13+) */
        if (android.os.Build.VERSION.SDK_INT >= 33) mCtx.registerReceiver(mBondWatch, f, Context.RECEIVER_EXPORTED);
        else mCtx.registerReceiver(mBondWatch, f);
    }

    private void unwatchBond() {
        if (mBondWatch != null) { try { mCtx.unregisterReceiver(mBondWatch); } catch (RuntimeException ignored) { } }
        mBondWatch = null;
    }

    /*
     * A pairing only one side kept (22.11.2026: "fragt auf dem Tab keinen neuen Code ab, ich bekomme vom Tab keine neue
     * Verbindung"): Android holds the stick as paired, the stick has no key for it (its window was used up) - Android then
     * asks no code and the stick sends nothing. Such a pairing is dropped and made anew; removeBond is hidden (system API).
     */
    static boolean removeBond(BluetoothDevice d) {
        try {
            net.prototux.aare.HiddenApi.INSTANCE.exempt("Landroid/bluetooth/BluetoothDevice;");
            Object r = BluetoothDevice.class.getMethod("removeBond").invoke(d);
            return Boolean.TRUE.equals(r);
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "removeBond: " + e);
            return false;
        }
    }

    private boolean mGotData;
    /** Subscribed, yet nothing came: the pairing does not hold on the stick's side. */
    private final Runnable mNoData = this::noData;

    private void noData() {
        BluetoothGatt g = mGatt;
        if (g == null || mGotData || !mRunning) return;
        if (!pairAllowed()) { fail(mCtx.getString(R.string.stick_no_data)); return; }
        log("subscribed, but no data in 20 s - the old pairing is dropped and made anew");
        BluetoothDevice dev = g.getDevice();
        if (!removeBond(dev)) { fail(mCtx.getString(R.string.stick_unpair_by_hand)); return; }
        /* once the old pairing is gone (BOND_NONE), a new one is asked for: the code dialog comes again */
        mMain.postDelayed(() -> {
            if (g != mGatt) return;
            mBonding = true;
            watchBond(g);
            state(mCtx.getString(R.string.stick_pair_now));
            if (!dev.createBond()) { mBonding = false; unwatchBond(); fail(mCtx.getString(R.string.stick_pair_failed)); close(); }
        }, 1500);
    }

    private void subscribe(BluetoothGatt g) {
        mGotData = false;
        BluetoothGattService s = g.getService(SERVICE);
        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(TELEGRAM);
        if (c == null) { again("Geraet bietet keine Live-Daten an"); return; }
        g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { again("Merkmal ohne Abo-Schalter"); return; }
        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
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
            mBonding = false;
            unwatchBond();
            mMain.removeCallbacks(mNoData);
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
                } else if (mBonding) {
                    /* the pairing goes on without the connection; its end decides (watchBond) */
                    log("Verbindung waehrend der Kopplung getrennt (Status " + status + ")");
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
                int bond = g.getDevice().getBondState();
                if (bond != BluetoothDevice.BOND_BONDED) {
                    /* der Stick sendet erst nach der Kopplung - erst koppeln, dann abonnieren */
                    if (!pairAllowed()) {
                        fail(mCtx.getString(R.string.stick_not_paired));
                        close();
                        mRunning = false;                       /* waits for "Stick suchen" */
                        return;
                    }
                    mBonding = true;
                    watchBond(g);
                    state(mCtx.getString(R.string.stick_pair_now));
                    if (bond == BluetoothDevice.BOND_NONE && !g.getDevice().createBond()) {
                        mBonding = false; unwatchBond();
                        fail(mCtx.getString(R.string.stick_pair_failed));
                        close();
                    }
                    return;
                }
                subscribe(g);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            mMain.post(() -> {
                if (g != mGatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    state("abonniert (MTU " + mMtu + ", " + bond(g.getDevice().getBondState()) + ")");
                    mMain.removeCallbacks(mNoData);
                    mMain.postDelayed(mNoData, 20_000);
                }
                else again("Abonnieren abgelehnt (Status " + status + ")");
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (!mGotData) { mGotData = true; mMain.post(() -> log("first data from the stick")); }
            String json = Cbor.toJson(value);
            if (json == null || !mTelemetry.accept(json)) mTelemetry.error(SRC, "unlesbares Telegramm (" + value.length + " B)");
            mChanged.run();
        }
    };

    private static String bond(int s) {
        return s == BluetoothDevice.BOND_BONDED ? "gekoppelt" : s == BluetoothDevice.BOND_BONDING ? "Kopplung laeuft" : "nicht gekoppelt";
    }
}
