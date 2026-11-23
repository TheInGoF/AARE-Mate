/*
 * Musik in Mate: Seite am Tablet - am Plex-Konto anmelden (Code fuer plex.tv/link) oder den Server von Hand
 * eintragen, und pruefen. Farben aus res/values/mate_colors.xml.
 * Anmeldung, Adresse und Token bleiben auf dem Tablet; Token werden nie angezeigt.
 */
package net.prototux.aare.music;

import net.prototux.aare.R;

import android.app.Activity;
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
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public final class MusicSetupActivity extends Activity {

    /** Ob ein Plex-Server eingerichtet ist (Adresse und Token liegen vor) – fuer die Startseite der App. */
    public static boolean isSetUp(android.content.Context c) {
        return Plex.load(c) != null;
    }

    /* Farben: aus res/values/mate_colors.xml, der einen Stelle fuer alle Farben von Mate (colors() in onCreate) */
    private int BG, PANEL, PANEL_HIGH, LINE, TEXT, DIM, TEAL, INK, PLATE;

    private void colors() {
        BG = getColor(R.color.mate_bg); PANEL = getColor(R.color.mate_surface); PANEL_HIGH = getColor(R.color.mate_surface_high);
        LINE = getColor(R.color.mate_line); TEXT = getColor(R.color.mate_text); DIM = getColor(R.color.mate_text_dim);
        TEAL = getColor(R.color.mate_accent); INK = getColor(R.color.mate_on_accent); PLATE = getColor(R.color.mate_plate);
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private float mDp;
    private TextView mPlex, mCode;
    private LinearLayout mServers;
    private int mLogin;         /* zaehlt die Anmeldeversuche; ein aelterer Versuch hoert auf */

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        colors();
        mDp = getResources().getDisplayMetrics().density;
        /* hinter den (durchsichtigen) Systemleisten; der Inhalt bleibt per fitsSystemWindows frei davon */
        getWindow().getDecorView().setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        LinearLayout head = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.music_ic_app);
        head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView name = text(getString(R.string.music_name), 24, TEXT);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setPadding(dp(12), 0, 0, 0);
        head.addView(name);
        root.addView(head);

        /* Musik: eigener Plex-Server */
        LinearLayout music = card(root, R.string.music_title);
        music.addView(text(getString(R.string.music_hint), 14, DIM));
        mPlex = text("", 15, TEXT);
        mPlex.setPadding(0, dp(10), 0, dp(10));
        music.addView(mPlex);
        mCode = text("", 44, TEAL);
        mCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mCode.setLetterSpacing(0.3f);
        mCode.setPadding(0, 0, 0, dp(10));
        mCode.setVisibility(View.GONE);
        music.addView(mCode);
        mServers = new LinearLayout(this);
        mServers.setOrientation(LinearLayout.VERTICAL);
        music.addView(mServers);
        LinearLayout loginRow = row();
        loginRow.addView(button(R.string.music_login, true, v -> login()));
        loginRow.addView(button(R.string.music_clear, false, v -> {
            mLogin++;
            mCode.setVisibility(View.GONE);
            mServers.removeAllViews();
            Plex.clear(this);
            plex();
        }));
        music.addView(loginRow);
        TextView manual = text(getString(R.string.music_manual), 13, DIM);
        manual.setPadding(0, dp(22), 0, dp(8));
        music.addView(manual);
        /* nur in Test-Builds: plex.tv durch einen Nachbau im eigenen Netz ersetzen */
        String base = getIntent().getStringExtra("plex_tv");
        if (base != null && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            String b = Plex.clean(base);
            if (b != null && Hosts.isPrivate(java.net.URI.create(b).getHost()))
                Plex.prefs(this).edit().putString("account_base", b).apply();
        }
        EditText plexUrl = field(R.string.music_url, false);
        EditText plexToken = field(R.string.music_token, true);
        music.addView(plexUrl);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = dp(8);
        music.addView(plexToken, gap);
        LinearLayout plexRow = row();
        plexRow.setPadding(0, dp(12), 0, 0);
        plexRow.addView(button(R.string.music_save, false, v -> {
            mLogin++;
            mCode.setVisibility(View.GONE);
            mServers.removeAllViews();
            if (Plex.save(this, plexUrl.getText().toString(), plexToken.getText().toString())) {
                plexUrl.setText("");
                plexToken.setText("");
            } else if (plexUrl.length() > 0 || plexToken.length() > 0) {
                mPlex.setText(R.string.music_bad);
                return;
            }
            plex();
        }));
        music.addView(plexRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
        plex();
    }

    @Override
    protected void onDestroy() {
        mLogin++;
        super.onDestroy();
    }

    /** Anmeldung am Plex-Konto: Code zeigen, auf die Bestaetigung warten, Server suchen. */
    private void login() {
        final int run = ++mLogin;
        mServers.removeAllViews();
        mCode.setVisibility(View.GONE);
        mPlex.setText(R.string.music_contacting);
        new Thread(() -> {
            try {
                PlexAccount.Pin pin = PlexAccount.start(this);
                ui(run, () -> {
                    mCode.setText(pin.code);
                    mCode.setVisibility(View.VISIBLE);
                    mPlex.setText(R.string.music_code_hint);
                });
                String account = null;
                for (int i = 0; i < 300 && account == null && run == mLogin; i++) {      /* zehn Minuten */
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    account = PlexAccount.poll(this, pin.id);
                }
                if (run != mLogin) return;
                if (account == null) {
                    ui(run, () -> {
                        mCode.setVisibility(View.GONE);
                        mPlex.setText(R.string.music_expired);
                    });
                    return;
                }
                ui(run, () -> {
                    mCode.setVisibility(View.GONE);
                    mPlex.setText(R.string.music_finding);
                });
                final String token = account;
                java.util.List<PlexAccount.Server> servers = PlexAccount.servers(this, token);
                if (servers.isEmpty()) ui(run, () -> mPlex.setText(R.string.music_no_server));
                else if (servers.size() == 1) connect(run, token, servers.get(0));
                else ui(run, () -> {
                    mPlex.setText(R.string.music_choose);
                    for (PlexAccount.Server s : servers) {
                        TextView b = button(0, false, v -> {
                            mServers.removeAllViews();
                            new Thread(() -> connect(run, token, s), "mate-plex-connect").start();
                        });
                        b.setText(s.name);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                        lp.bottomMargin = dp(8);
                        mServers.addView(b, lp);
                    }
                });
            } catch (java.io.IOException e) {
                ui(run, () -> {
                    mCode.setVisibility(View.GONE);
                    mPlex.setText(getString(R.string.music_login_failed, Plex.Failure.text(this, e)));
                });
            }
        }, "mate-plex-login").start();
    }

    /** Im Hintergrund: Adresse des Servers suchen und speichern. */
    private void connect(int run, String account, PlexAccount.Server s) {
        ui(run, () -> mPlex.setText(getString(R.string.music_checking, s.name)));
        boolean ok = PlexAccount.connect(this, account, s) != null;
        ui(run, () -> {
            if (ok) plex();
            else mPlex.setText(getString(R.string.music_unreachable, s.name));
        });
    }

    private void ui(int run, Runnable r) {
        mMain.post(() -> {
            if (run == mLogin && !isDestroyed()) r.run();
        });
    }

    private EditText field(int hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(TEXT);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setBackground(shape(PANEL_HIGH, 10, 0));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        return e;
    }

    /** Zustand von Plex: Name des Servers und Zahl der Listen, oder der Fehler. Fragt den Server im Hintergrund. */
    private void plex() {
        Plex p = Plex.load(this);
        if (p == null) {
            mPlex.setText(R.string.music_none);
            return;
        }
        mPlex.setText(getString(R.string.music_checking, p.url));
        new Thread(() -> {
            String msg;
            try {
                msg = getString(R.string.music_ok, p.name(), p.playlists().size(), p.url);
            } catch (java.io.IOException e) {
                msg = getString(R.string.music_check_failed, p.url, Plex.Failure.text(this, e));
            }
            final String m = msg;
            mMain.post(() -> mPlex.setText(m));
        }, "mate-plex-test").start();
    }

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
        TextView b = text(label == 0 ? "" : getString(label), 15, primary ? INK : TEXT);
        b.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        GradientDrawable g = primary ? shape(TEAL, 12, 0) : shape(Color.TRANSPARENT, 12, LINE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(PLATE), g, shape(Color.WHITE, 12, 0)));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(10));
        b.setLayoutParams(lp);
        return b;
    }
}
