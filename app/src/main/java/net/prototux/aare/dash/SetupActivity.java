/*
 * IDmate Auto: Seite am Tablet - Verbindung einrichten, Stick, Fehlersuche. Gestaltet wie das Dashboard von
 * IDmate: dunkler Grund, Karten, Tuerkis des Logos als Akzent.
 *
 * Verbindung: QR-Code aus IDmate scannen (Admin -> Interfaces -> Lese-API -> QR-Code erzeugen) oder dessen Inhalt
 * einfuegen. Nur im Debug-Build nimmt die Seite den Inhalt auch als Start-Extra "qr" an (adb), damit die
 * Testumgebung ohne Kamera auskommt - in einem Release-Build koennte sonst eine fremde App die Verbindung auf
 * ihren Server umlenken.
 *
 * Fehlersuche: Schalter der Quelle (alle / nur Server / nur Stick / nur Fahrzeug) und, jede Sekunde aufgefrischt,
 * je Quelle Alter, Anzahl, Luecken, Fehler und letztes Telegramm sowie je Feld Wert, Herkunft und Alter.
 * Das Token erscheint nirgends.
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.Locale;
import java.util.Map;

public final class SetupActivity extends ComponentActivity {
    /* Farben des IDmate-Dashboards und des Logos */
    private static final int BG = 0xFF0D1117, PANEL = 0xFF161B22, PANEL_HIGH = 0xFF21262D, LINE = 0xFF30363D;
    private static final int TEXT = 0xFFF0F6FC, DIM = 0xFF8B949E, TEAL = 0xFF1DE9B6, INK = 0xFF0E1B2C;
    private static final int RED = 0xFFF85149, GREEN = 0xFF3FB950;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            debug();
            mMain.postDelayed(this, 1000);
        }
    };
    private Hub mHub;
    private float mDp;
    private TextView mState, mStateDot, mStick, mDebug;
    private LinearLayout mModes;
    /* QR-Code des Dashboards: der Inhalt geht an take() */
    private final ActivityResultLauncher<ScanOptions> mScan = registerForActivityResult(new ScanContract(), r -> {
        if (r.getContents() != null) take(r.getContents());
    });

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mHub = Hub.get(this);
        mDp = getResources().getDisplayMetrics().density;
        /* hinter den (durchsichtigen) Systemleisten; der Inhalt bleibt per fitsSystemWindows frei davon */
        getWindow().getDecorView().setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));

        /* Kopf: Logo und Name */
        LinearLayout head = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.dash_ic_app);
        head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView name = text(getString(R.string.dash_name), 24, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setPadding(dp(12), 0, 0, 0);
        head.addView(name);
        root.addView(head);

        /* Verbindung */
        LinearLayout link = card(root, R.string.setup_title);
        LinearLayout stateRow = row();
        mStateDot = text("●", 14, DIM);
        mStateDot.setPadding(0, 0, dp(8), 0);
        stateRow.addView(mStateDot);
        mState = text("", 15, TEXT);
        stateRow.addView(mState);
        link.addView(stateRow);
        LinearLayout buttons = row();
        buttons.setPadding(0, dp(14), 0, 0);
        buttons.addView(button(R.string.setup_scan, true, v -> {
            mScan.launch(new ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.setup_scan_prompt)).setBeepEnabled(false).setOrientationLocked(false)
                    .setCaptureActivity(ScanActivity.class));
        }));
        buttons.addView(button(R.string.setup_clear, false, v -> {
            Link.clear(this);
            mHub.restart();
            show(null);
        }));
        link.addView(buttons);
        LinearLayout paste = row();
        paste.setPadding(0, dp(10), 0, 0);
        EditText qr = new EditText(this);
        qr.setHint(R.string.setup_hint);
        qr.setHintTextColor(DIM);
        qr.setTextColor(TEXT);
        qr.setTextSize(14);
        qr.setSingleLine(true);
        qr.setBackground(shape(PANEL_HIGH, 10, 0));
        qr.setPadding(dp(12), dp(10), dp(12), dp(10));
        qr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        qrLp.setMarginEnd(dp(10));
        paste.addView(qr, qrLp);
        paste.addView(button(R.string.setup_save, false, v -> {
            /* Leeres Feld: kein Fehler, sondern der Hinweis, dass der Scan von selbst verbindet */
            if (qr.getText().toString().trim().isEmpty()) {
                show(null);
                mState.setText(getString(R.string.setup_paste_empty) + "\n" + mState.getText());
            } else if (take(qr.getText().toString())) qr.setText("");
        }));
        link.addView(paste);

        /* Stick */
        LinearLayout stick = card(root, R.string.stick_title);
        stick.addView(text(getString(R.string.stick_hint), 14, DIM));
        mStick = text("", 15, TEXT);
        mStick.setPadding(0, dp(10), 0, 0);
        stick.addView(mStick);
        LinearLayout stickRow = row();
        stickRow.setPadding(0, dp(12), 0, 0);
        stickRow.addView(button(R.string.stick_search, true, v -> {
            if (!StickLink.permitted(this)) requestPermissions(StickLink.permissions(), 1);
            else mHub.stickEnabled(true);
            debug();
        }));
        stickRow.addView(button(R.string.stick_off, false, v -> {
            mHub.stickEnabled(false);
            debug();
        }));
        stick.addView(stickRow);
        /* 22.11.2026: the pairing dropped on the tablet, for a clean new one - a row of its own (three buttons do not fit) */
        stickRow = row();
        stickRow.setPadding(0, dp(10), 0, 0);
        stickRow.addView(button(R.string.stick_unpair, false, v -> {
            mHub.stickEnabled(false);
            boolean ok = StickLink.unpair(this);
            android.widget.Toast.makeText(this, ok ? R.string.stick_unpaired : R.string.stick_unpair_by_hand, android.widget.Toast.LENGTH_LONG).show();
            debug();
        }));
        stick.addView(stickRow);

        /* Fehlersuche */
        LinearLayout dbg = card(root, R.string.dbg_title);
        dbg.addView(text(getString(R.string.dbg_source), 14, DIM));
        mModes = row();
        mModes.setPadding(0, dp(8), 0, 0);
        String[] modes = { Hub.MODE_ALL, Hub.MODE_BACKEND, Hub.MODE_STICK, Hub.MODE_CAR };
        int[] labels = { R.string.dbg_src_all, R.string.dbg_src_backend, R.string.dbg_src_stick, R.string.dbg_src_car };
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            TextView chip = text(getString(labels[i]), 14, TEXT);
            chip.setTag(mode);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setOnClickListener(v -> {
                if (!mode.equals(mHub.mode())) mHub.mode(mode);
                modes();
                debug();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            mModes.addView(chip, lp);
        }
        dbg.addView(mModes);
        // Die Diagnose auf dem Dashboard im Auto: aus als Vorgabe, hier ein- und ausschalten
        android.widget.Switch show = new android.widget.Switch(this);
        show.setText(R.string.dbg_show_on_dash);
        show.setTextSize(14);
        show.setTextColor(TEXT);
        show.setPadding(0, dp(12), 0, 0);
        show.setChecked(mHub.showDiagnosis());
        show.setOnCheckedChangeListener((v, on) -> mHub.showDiagnosis(on));
        dbg.addView(show);
        mDebug = text("", 13, DIM);
        mDebug.setTypeface(Typeface.MONOSPACE);
        mDebug.setTextIsSelectable(true);
        mDebug.setPadding(0, dp(12), 0, 0);
        dbg.addView(mDebug);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
        modes();

        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String extra = debuggable ? getIntent().getStringExtra("qr") : null;
        if (extra == null || !take(extra)) show(null);
    }

    /* ── Bausteine ────────────────────────────────────────────────────────── */

    private int dp(float v) {
        return Math.round(v * mDp);
    }

    private GradientDrawable shape(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * mDp);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Karte mit Ueberschrift in Grossbuchstaben, wie die Beschriftungen des Dashboards. */
    private LinearLayout card(LinearLayout parent, int title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(PANEL, 16, LINE));
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        TextView t = text(getString(title).toUpperCase(Locale.getDefault()), 13, DIM);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(10));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        parent.addView(c, lp);
        return c;
    }

    /** Knopf: gefuellt in Tuerkis fuer die Hauptaktion, sonst mit Rand. */
    private TextView button(int label, boolean primary, View.OnClickListener onClick) {
        TextView b = text(getString(label), 15, primary ? INK : TEXT);
        b.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        GradientDrawable g = primary ? shape(TEAL, 12, 0) : shape(Color.TRANSPARENT, 12, LINE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, shape(Color.WHITE, 12, 0)));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(10));
        b.setLayoutParams(lp);
        return b;
    }

    private void modes() {
        for (int i = 0; i < mModes.getChildCount(); i++) {
            TextView chip = (TextView) mModes.getChildAt(i);
            boolean on = chip.getTag().equals(mHub.mode());
            chip.setBackground(on ? shape(0xFF0E3B36, 20, TEAL) : shape(Color.TRANSPARENT, 20, LINE));
            chip.setTextColor(on ? TEAL : TEXT);
        }
    }

    /* ── Ablauf ───────────────────────────────────────────────────────────── */

    @Override
    protected void onStart() {
        super.onStart();
        mHub.acquire();
        mMain.post(mTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mMain.removeCallbacks(mTick);
        mHub.release();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 1) return;
        if (StickLink.permitted(this)) { mHub.stickEnabled(true); return; }
        // refused: said so; refused for good (no dialog any more), the app's settings opened to allow it there
        boolean again = false;
        for (String p : StickLink.permissions()) if (shouldShowRequestPermissionRationale(p)) again = true;
        // before Android 12 the search needs the location permission, not a Bluetooth one (I-118h)
        android.widget.Toast.makeText(this, android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                ? R.string.stick_permission_denied : R.string.stick_permission_denied_location, android.widget.Toast.LENGTH_LONG).show();
        if (!again) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", getPackageName(), null)));
            } catch (android.content.ActivityNotFoundException ignored) { }
        }
    }

    private boolean take(String text) {
        int[] why = new int[1];
        Link l = Link.parse(text, why);
        if (l == null) {
            show(getString(why[0] == Link.VERSION ? R.string.setup_bad_version
                    : why[0] == Link.ADDRESS ? R.string.setup_bad_address
                    : why[0] == Link.PLAIN_HTTP ? R.string.setup_bad_http
                    : why[0] == Link.TOKEN ? R.string.setup_bad_token : R.string.setup_bad)
                    + (why[0] == Link.NOT_OURS ? "\n" + getString(R.string.setup_bad_read, text.length(), preview(text)) : ""));
            return false;
        }
        l.save(this);
        mHub.restart();
        show(null);
        /* Deutlich sagen, dass es geklappt hat und nichts weiter zu tun ist */
        String done = getString(R.string.setup_done, l.name.isEmpty() ? l.vehicle : l.name);
        android.text.SpannableString line = new android.text.SpannableString(done + "\n" + l.url);
        line.setSpan(new android.text.style.ForegroundColorSpan(GREEN), 0, done.length(), 0);
        line.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, done.length(), 0);
        mState.setText(line);
        android.widget.Toast.makeText(this, done, android.widget.Toast.LENGTH_LONG).show();
        return true;
    }

    /** Anfang des gelesenen Inhalts fuer die Fehlermeldung - nie bis zum Token (das steht hinter der Adresse). */
    private static String preview(String text) {
        String t = text.trim().replace('\n', ' ');
        int cut = t.indexOf("token");
        if (cut < 0 || cut > 40) cut = Math.min(40, t.length());
        return t.substring(0, cut) + (cut < t.length() ? "…" : "");
    }

    private void show(String error) {
        Link l = Link.load(this);
        String s = l == null ? getString(R.string.setup_none)
                : getString(R.string.setup_linked, l.name.isEmpty() ? l.vehicle : l.name, l.url);
        mState.setText(error == null ? s : error + "\n" + s);
        mStateDot.setTextColor(error != null ? RED : l == null ? DIM : GREEN);
    }

    private static String age(long now, long t) {
        return t <= 0 ? "–" : (now - t) / 1000 + " s";
    }

    private void debug() {
        Telemetry t = mHub.telemetry;
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        Map<String, Telemetry.SourceState> sources = t.sources();
        if (sources.isEmpty()) sb.append(getString(R.string.dbg_none)).append('\n');
        String stick = null;
        for (Map.Entry<String, Telemetry.SourceState> e : sources.entrySet()) {
            Telemetry.SourceState s = e.getValue();
            if (StickLink.SRC.equals(e.getKey())) stick = s.error != null ? s.error : s.note;
            sb.append(String.format(Locale.ROOT, "%-8s %s %s · #%d", e.getKey(),
                    getString(R.string.dbg_age), age(now, s.lastOkMs), s.received));
            if (!ReadApi.SRC.equals(e.getKey()))                /* beim Server sagt die Zaehlung nichts ueber Verluste */
                sb.append(String.format(Locale.ROOT, " · %s %d", getString(R.string.dbg_gaps), s.gaps));
            sb.append(String.format(Locale.ROOT, " · seq %d\n", s.lastSeq));
            if (s.error != null) sb.append("  ! ").append(s.error).append('\n');
            if (s.note != null) sb.append("  ").append(s.note).append('\n');
            if (s.lastRaw != null) sb.append("  ").append(s.lastRaw).append('\n');
        }
        sb.append('\n');
        for (Map.Entry<String, Telemetry.Value> e : t.fields().entrySet()) {
            Telemetry.Value v = e.getValue();
            sb.append(String.format(Locale.ROOT, "%-9s %-12s %-8s %s\n", e.getKey(), v.v, v.src, age(now, v.tsMs)));
        }
        mDebug.setText(sb);
        mStick.setText(mHub.stickEnabled() ? (stick == null ? "…" : stick) : getString(R.string.stick_state_off));
    }
}
