/*
 * IDmate Auto: Buehne des Dashboards, gewoehnliche Ansichten direkt in Mates Auto-Oberflaeche (19.11.2026: "alle
 * Funktionen des Dashboards ... soll ja in Mate liegen. Also muss Mate auch selber das Dashboard zeichnen"):
 *   links  die Karte, von Mate selbst gezeichnet (DashMap.kt, System L-079): Regionspaket als Grundkarte, Reichweite,
 *          Wind, Regen und Spur von IDmate darueber, Bedienung und langes Druecken in der Karte selbst,
 *   rechts die Werte (DashPainter).
 * Die fruehere Web-Ansicht der IDmate-Seite samt Steuerskript und Kachelspeicher ist entfernt (21.11.2026).
 */
package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

final class CarStage {
    private static final String TAG = "IDmateStage";

    private final Context mCtx;
    private final Hub mHub;
    private final DashPainter mPainter;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** The car screen's context the views were made with: another one (a new connection) builds them anew. */
    private Context mHostCtx;
    private FrameLayout mRoot, mMapFrame;
    private View mDash;
    private float mDp = 1;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (mDash != null) mDash.invalidate();
            mMain.postDelayed(this, 1000);
        }
    };

    /** Night on the car's screen (System L-074): the dashboard's map dark, like Mate Navi's. */
    private static volatile boolean sNight;

    /** Night or day before the map exists: it takes it when it is built. */
    static void nightLater(boolean on) { sNight = on; DashMapView.night(on); }

    /** Night on the car's screen (the values' painter: the charge bar's glow). */
    static boolean isNight() { return sNight; }

    /** The car's lights changed to night or day: the map's colours follow at once. Main thread. */
    void night(boolean on) {
        DashMapView.night(on);
        sNight = on;
    }

    CarStage(Context ctx, Hub hub) {
        mCtx = ctx;
        mHub = hub;
        mPainter = new DashPainter(ctx, hub);
        sLast = this;
        // the tiles the former web view kept (TileCache, gone with it): their folder in the cache is dropped once
        final java.io.File old = new java.io.File(ctx.getCacheDir(), "maptiles");
        if (old.exists()) new Thread(() -> deleteTree(old), "drop-old-tiles").start();
    }

    private static void deleteTree(java.io.File f) {
        java.io.File[] in = f.listFiles();
        if (in != null) for (java.io.File c : in) deleteTree(c);
        if (!f.delete()) Log.w(TAG, "not deleted: " + f.getName());
    }

    private static volatile CarStage sLast;

    /**
     * Memory is tight (net.prototux.aare.Memory): a stage kept behind another area is taken down now instead of after
     * ten minutes. Coming back builds it anew. True when there was one to take down.
     */
    public static boolean dropParked() {
        final CarStage s = sLast;
        if (s == null || !s.mParked || s.mRoot == null) return false;
        s.mMain.post(() -> { if (s.mParked) { Log.i(TAG, "Buehne abgebaut: Speicher knapp"); s.stop(); } });
        return true;
    }

    Hub hub() {
        return mHub;
    }

    boolean running() {
        return mRoot != null;
    }

    /* Flaeche weg (ein anderer Bereich ist vorn): Buehne behalten, damit die Karte beim Zurueckkommen sofort steht */
    private static final long PARK_MAX_MS = 10 * 60 * 1000L;
    private boolean mParked;
    private int mDpiI;
    private final Runnable mParkEnd = () -> { if (mParked) { Log.i(TAG, "Buehne nach Pause abgebaut"); stop(); } };

    /** Not on the screen any more (another area in front): the views are kept, at rest, to come back at once. Taken down after a longer pause. */
    void park() {
        if (mRoot == null || mParked) return;
        if (mRoot.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) mRoot.getParent()).removeView(mRoot);
        mParked = true;
        mMain.removeCallbacks(mTick);
        mMain.removeCallbacks(mParkEnd);
        mMain.postDelayed(mParkEnd, PARK_MAX_MS);
        Log.i(TAG, "Buehne geparkt");
    }

    /**
     * The stage's views for the car screen [ctx] at [dpi]: the kept ones when they were made for the same screen
     * (nothing loads anew), else built anew. Null when there is no map (no link to IDmate): the caller paints the values.
     */
    View view(Context ctx, int dpi) {
        if (mRoot != null && mHostCtx == ctx && mDpiI == dpi) {
            if (mRoot.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) mRoot.getParent()).removeView(mRoot);
            if (mParked) {
                mParked = false;
                mMain.removeCallbacks(mParkEnd);
                mMain.removeCallbacks(mTick);
                mMain.post(mTick);
                Log.i(TAG, "Buehne fortgesetzt");
            }
            return mRoot;
        }
        return start(ctx, dpi) ? mRoot : null;
    }

    /** Buehne aufbauen. Liefert false, wenn das Geraet es nicht zulaesst (dann zeichnet DashView die Werte selbst). */
    boolean start(Context host, int dpi) {
        stop();
        Link link = Link.load(mCtx);
        if (link == null) { mHub.telemetry.note("stage", mCtx.getString(R.string.stage_no_link), "no map: no link to IDmate set up"); return false; }
        /* IDMate liefert die Kartenansicht nur ueber HTTPS (Ausnahme: Testserver auf dem eigenen Geraet) */
        if (!link.secure() && !link.url.startsWith("http://127.0.0.1")) {
            mHub.telemetry.note("stage", mCtx.getString(R.string.stage_no_https), "no map: link without HTTPS");
            return false;
        }
        try {
            /* Mate's size on the car screen ([dpi], as the rest of Mate's car screen): the own buttons and the page alike */
            mDp = dpi / 160f;
            mDpiI = dpi;
            mHostCtx = host;

            mRoot = new FrameLayout(host);
            mRoot.setBackgroundColor(DashPainter.BG);
            LinearLayout row = new LinearLayout(mHostCtx);
            mMapFrame = new FrameLayout(mHostCtx);
            mMapFrame.setBackgroundColor(DashPainter.PANEL);
            final float radius = 18 * mDp;
            /*
             * The rounded corners are painted over the map instead of cutting the map to them (18.11.2026: "die Karte
             * vom Dashboard laeuft immer noch unfluessig"): a page cut to a rounded outline is drawn into a picture of
             * its own first and laid in from there, with every frame of a drag or a pinch.
             */
            mMapFrame.setForeground(new android.graphics.drawable.Drawable() {
                private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                private final android.graphics.Path mPath = new android.graphics.Path();
                @Override public void draw(android.graphics.Canvas c) {
                    android.graphics.Rect b = getBounds();
                    mPaint.setColor(DashPainter.BG);
                    mPath.reset();
                    mPath.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                    mPath.addRect(b.left, b.top, b.right, b.bottom, android.graphics.Path.Direction.CW);
                    mPath.addRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, android.graphics.Path.Direction.CW);
                    c.drawPath(mPath, mPaint);
                }
                @Override public void setAlpha(int a) { }
                @Override public void setColorFilter(android.graphics.ColorFilter f) { }
                @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
            });

            /*
             * The map drawn by Mate (System L-079, 21.11.2026: "Die Dashboard-Seite sollte schon lange keine Website mehr
             * sein"): the region package on the device as base map, IDmate's range, wind, rain and trail over it, the
             * controls and the long press in the map itself (DashMap.kt). The web view of IDmate's page is gone.
             */
            DashMapView.night(sNight);
            mMapFrame.addView(DashMapView.create(mHostCtx, dpi, at -> {
                java.util.function.Consumer<String> g = sGo;
                if (g != null) g.accept(at);
            }), 0, new FrameLayout.LayoutParams(-1, -1));

            mDash = new View(mHostCtx) {
                private final Rect mArea = new Rect();
                @Override protected void onDraw(Canvas cv) {
                    mArea.set(0, 0, getWidth(), getHeight());
                    mPainter.paint(cv, mArea, mDp, false);
                }
            };
            LinearLayout.LayoutParams lpMap = new LinearLayout.LayoutParams(0, -1, 56);
            lpMap.setMargins((int) (24 * mDp), (int) (24 * mDp), 0, (int) (24 * mDp));
            row.addView(mMapFrame, lpMap);
            row.addView(mDash, new LinearLayout.LayoutParams(0, -1, 44));
            mRoot.addView(row, new FrameLayout.LayoutParams(-1, -1));
            /* narrow (a share of the split): only the values, no map */
            mRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (mMapFrame == null) return;
                int vis = (r - l) > 1.35f * (b - t) ? View.VISIBLE : View.GONE;
                if (mMapFrame.getVisibility() != vis) mMapFrame.post(() -> { if (mMapFrame != null) mMapFrame.setVisibility(vis); });
            });
            Log.i(TAG, "Buehne in Mates Oberflaeche, " + dpi + " dpi");

            mMain.post(mTick);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Buehne nicht moeglich: " + e);
            mHub.telemetry.note("stage", e.getClass().getSimpleName() + ": " + e.getMessage());
            stop();
            return false;
        }
    }

    void stop() {
        mParked = false;
        mMain.removeCallbacks(mParkEnd);
        mMain.removeCallbacks(mTick);
        try {
            if (mRoot != null && mRoot.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) mRoot.getParent()).removeView(mRoot);
        } catch (RuntimeException e) {
            Log.w(TAG, "Abbau: " + e);
        }
        mDash = null;
        mRoot = null;
        mMapFrame = null;
        mHostCtx = null;
    }

    void changed() {
        if (mDash != null) mDash.postInvalidate();
    }

    /** Where a place picked on the map goes ("lat,lon"): the car's navigation; set by the car screen. */
    static volatile java.util.function.Consumer<String> sGo;

    /** Debug and the screen's "Neu laden": the stage built anew. */
    void reload() {
        Context host = mHostCtx;
        int dpi = mDpiI;
        android.view.ViewGroup parent = mRoot != null && mRoot.getParent() instanceof android.view.ViewGroup ? (android.view.ViewGroup) mRoot.getParent() : null;
        if (host == null) return;
        stop();
        if (start(host, dpi) && parent != null) parent.addView(mRoot, new android.view.ViewGroup.LayoutParams(-1, -1));
    }
}
