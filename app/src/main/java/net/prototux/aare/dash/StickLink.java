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
        state(R.string.stick_none_found);
        mMain.postDelayed(mRetry, RETRY_MS * 4);
    };

    StickLink(Context ctx, Telemetry telemetry, Runnable changed) {
        mCtx = ctx.getApplicationContext();
        mTelemetry = telemetry;
        mChanged = changed;
    }

    /** Android 12+: the Bluetooth runtime rights; before that the install-time Bluetooth rights and the location for the scan. */
    static String[] permissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            return new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT };
        return new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
    }

    static boolean permitted(Context c) {
        for (String p : permissions())
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private static String saved(Context c) {
        return c.getSharedPreferences("dash_stick", Context.MODE_PRIVATE).getString("address", null);
    }

    /**
     * "Kopplung loesen" (22.11.2026: "Wie kann ich den Telemetrie-Stick jetzt vom Tablet entfernen, damit ich nochmal eine
     * vernuenftige Kopplung stattfinden lassen kann?"): the tablet's pairing with the remembered stick dropped and the stick
     * forgotten. False when Android would not drop it (then by hand: Bluetooth settings, device "OBDII").
     */
    static boolean unpair(Context c) {
        String address = saved(c);
        boolean ok = true;
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        BluetoothAdapter ad = m == null ? null : m.getAdapter();
        if (ad != null && permitted(c)) {
            java.util.Set<BluetoothDevice> bonded = ad.getBondedDevices();
            if (bonded != null) for (BluetoothDevice d : bonded) {
                boolean ours = address != null ? d.getAddress().equals(address) : "OBDII".equals(d.getName());
                if (ours && !removeBond(d)) ok = false;
            }
        }
        forget(c);
        net.prototux.aare.DiagLog.INSTANCE.add("stick: pairing dropped by the driver" + (ok ? "" : " - Android refused, by hand"), null, "DASH");
        return ok;
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

    /* the log in English whatever the device's language; the screen in the device's */
    private String english(int id, Object... args) {
        return net.prototux.aare.Language.resources(mCtx, java.util.Locale.ENGLISH).getString(id, args);
    }

    private void state(int id, Object... args) {
        log(english(id, args));
        mTelemetry.note(SRC, mCtx.getString(id, args));
        mChanged.run();
    }

    private void fail(int id, Object... args) {
        log(english(id, args));
        mTelemetry.error(SRC, mCtx.getString(id, args));
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
                BluetoothDevice d = android.os.Build.VERSION.SDK_INT >= 33
                        ? i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                        : i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || g != mGatt || !d.getAddress().equals(g.getDevice().getAddress())) return;
                int s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                if (s == BluetoothDevice.BOND_BONDED) {
                    mBonding = false; unwatchBond();
                    state(R.string.stick_paired);
                    subscribe(g);
                } else if (s == BluetoothDevice.BOND_NONE
                        && i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING) {
                    /* only a pairing that was under way and ended without a key (an old one dropped is no failure) */
                    mBonding = false; unwatchBond();
                    sPairAskedAt = 0;
                    fail(R.string.stick_pair_failed);
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

    private boolean mServicesAsked, mSubscribed;

    private void askServices(BluetoothGatt g) {
        if (mServicesAsked) return;
        mServicesAsked = true;
        state(R.string.stick_reading_services);
        if (!g.discoverServices()) again(R.string.stick_services_unreadable);
    }

    /** Connected 25 s ago and still not subscribed: the old pairing is dropped and made anew, or the driver is told how. */
    private final Runnable mStuck = this::stuck;

    private void stuck() {
        BluetoothGatt g = mGatt;
        if (g == null || mSubscribed || mBonding || !mRunning) return;
        log("connected 25 s ago, still not subscribed (bond " + english(bond(g.getDevice().getBondState())) + ")");
        if (!pairAllowed()) { fail(R.string.stick_no_data); close(); mRunning = false; return; }
        mGotData = false;
        noData();
    }

    private boolean mGotData;
    /** Subscribed, yet nothing came: the pairing does not hold on the stick's side. */
    private final Runnable mNoData = this::noData;

    private void noData() {
        BluetoothGatt g = mGatt;
        if (g == null || mGotData || !mRunning) return;
        if (!pairAllowed()) { fail(R.string.stick_no_data); return; }
        log("subscribed, but no data in 20 s - the old pairing is dropped and made anew");
        BluetoothDevice dev = g.getDevice();
        if (!removeBond(dev)) { fail(R.string.stick_unpair_by_hand); return; }
        /* once the old pairing is gone (BOND_NONE), a new one is asked for: the code dialog comes again */
        mMain.postDelayed(() -> {
            if (g != mGatt) return;
            mBonding = true;
            watchBond(g);
            state(R.string.stick_pair_now);
            if (!dev.createBond()) { mBonding = false; unwatchBond(); fail(R.string.stick_pair_failed); close(); }
        }, 1500);
    }

    private void subscribe(BluetoothGatt g) {
        mGotData = false;
        BluetoothGattService s = g.getService(SERVICE);
        BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(TELEGRAM);
        if (c == null) { again(R.string.stick_no_live_data); return; }
        g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) { again(R.string.stick_no_cccd); return; }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            /* before Android 13 the value goes with the descriptor */
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(d);
        }
    }

    void start() {
        mMain.post(() -> {
            if (mRunning) return;
            mRunning = true;
            mMisses = 0;
            connect();
        });
    }

    void stop() {
        mMain.post(() -> {
            mRunning = false;
            mBonding = false;
            unwatchBond();
            mMain.removeCallbacks(mNoData);
            mMain.removeCallbacks(mStuck);
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
            fail(R.string.stick_no_permission);
            return;
        }
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) {
            fail(R.string.stick_bt_off);
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
            fail(R.string.stick_scan_impossible);
            return;
        }
        state(R.string.stick_searching);
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
            fail(R.string.stick_scan_failed, errorCode);
            mMain.postDelayed(mRetry, RETRY_MS * 2);
        }
    };

    /** Tries in a row that did not connect (System I-122); reset by a connection. */
    private int mMisses;
    /** After this many misses the stick is far (car parked, stick asleep): Android waits for it quietly. */
    private static final int MISSES_TO_WAIT = 3;

    private void open(BluetoothDevice d) {
        close();
        mSubscribed = false;
        /* System I-122 (log 24.11.2026: every 35 s "Connecting" and "Connection lost (status 147)" while the car stood):
           after a few direct tries Android's own waiting connection - no time-out, no new try every 35 s, it connects
           as soon as the stick is there again */
        boolean wait = mMisses >= MISSES_TO_WAIT;
        if (wait && mMisses == MISSES_TO_WAIT) log("stick not reached " + mMisses + " times - waiting for it in the background");
        if (!wait) state(R.string.stick_connecting, d.getAddress());
        mGatt = d.connectGatt(mCtx, wait, mCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void again(int why, Object... args) {
        /* while waiting in the background a lost try is no news: neither the log nor the screen hear of it again */
        if (mMisses < MISSES_TO_WAIT) fail(why, args);
        close();
        mMisses++;
        if (mRunning) mMain.postDelayed(mRetry, RETRY_MS);
    }

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            mMain.post(() -> {
                if (g != mGatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (mMisses >= MISSES_TO_WAIT) log("stick back after " + mMisses + " misses");
                    mMisses = 0;
                    state(R.string.stick_mtu);
                    mServicesAsked = false;
                    if (!g.requestMtu(MTU)) askServices(g);
                    /* the answer to the size may never come (22.11.2026: "Er sagt bei dem Stick immer noch verbunden, handle
                       Nachrichtengroesse aus. Dabei ist er nicht verbunden") - on after 3 s without it */
                    mMain.postDelayed(() -> { if (g == mGatt && !mServicesAsked) { log("no answer to the message size in 3 s - on without it"); askServices(g); } }, 3_000);
                    /* nothing subscribed 25 s after connecting: the pairing does not hold (System I-084) */
                    mMain.removeCallbacks(mStuck);
                    mMain.postDelayed(mStuck, 25_000);
                } else if (mBonding) {
                    /* the pairing goes on without the connection; its end decides (watchBond) */
                    log("connection lost during the pairing (status " + status + ")");
                } else {
                    again(R.string.stick_disconnected, status);
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            mMain.post(() -> {
                if (g != mGatt) return;
                mMtu = mtu;
                askServices(g);
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            mMain.post(() -> {
                if (g != mGatt) return;
                BluetoothGattService s = g.getService(SERVICE);
                BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(TELEGRAM);
                if (c == null) {
                    forget(mCtx);                               /* falsches Geraet gemerkt: neu suchen */
                    again(R.string.stick_no_live_data);
                    return;
                }
                int bond = g.getDevice().getBondState();
                if (bond != BluetoothDevice.BOND_BONDED) {
                    /* der Stick sendet erst nach der Kopplung - erst koppeln, dann abonnieren */
                    if (!pairAllowed()) {
                        fail(R.string.stick_not_paired);
                        close();
                        mRunning = false;                       /* waits for "Stick suchen" */
                        return;
                    }
                    mBonding = true;
                    watchBond(g);
                    state(R.string.stick_pair_now);
                    if (bond == BluetoothDevice.BOND_NONE && !g.getDevice().createBond()) {
                        mBonding = false; unwatchBond();
                        fail(R.string.stick_pair_failed);
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
                    mSubscribed = true;
                    mMain.removeCallbacks(mStuck);
                    int b = bond(g.getDevice().getBondState());
                    /* the pairing's word in each one's language: English in the log, the device's on the screen */
                    log(english(R.string.stick_subscribed, mMtu, english(b)));
                    mTelemetry.note(SRC, mCtx.getString(R.string.stick_subscribed, mMtu, mCtx.getString(b)));
                    mChanged.run();
                    mMain.removeCallbacks(mNoData);
                    mMain.postDelayed(mNoData, 20_000);
                }
                else again(R.string.stick_subscribe_refused, status);
            });
        }

        /* Android 13 and later */
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            telegram(value);
        }

        /* before Android 13: the value with the characteristic */
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (android.os.Build.VERSION.SDK_INT >= 33) return;      /* the overload above gets it */
            byte[] value = c.getValue();
            if (value != null) telegram(value);
        }
    };

    private void telegram(byte[] value) {
        if (!mGotData) { mGotData = true; mMain.post(() -> log("first data from the stick")); }
        String json = Cbor.toJson(value);
        if (json == null || !mTelemetry.accept(json)) mTelemetry.error(SRC, mCtx.getString(R.string.stick_bad_telegram, value.length));
        mChanged.run();
    }

    private static int bond(int s) {
        return s == BluetoothDevice.BOND_BONDED ? R.string.stick_bond_bonded
                : s == BluetoothDevice.BOND_BONDING ? R.string.stick_bond_bonding : R.string.stick_bond_none;
    }
}
