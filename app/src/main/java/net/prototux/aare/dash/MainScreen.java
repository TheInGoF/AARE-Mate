/*
 * IDmate Auto: Hauptbildschirm. Android Auto erlaubt eine selbst gestaltete Flaeche nur in der Kategorie
 * Navigation (NavigationTemplate + AppManager.setSurfaceCallback).
 *
 * Mate zeigt das Dashboard seit 19.11.2026 selbst, direkt in seiner Auto-Oberflaeche (DashView); dieser Bildschirm
 * bleibt nur fuer einen anderen Wirt und zeichnet dort die Werte (DashPainter).
 */
package net.prototux.aare.dash;


import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

final class MainScreen extends Screen implements SurfaceCallback, DefaultLifecycleObserver {
    private final DashPainter mPainter;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            draw();
            mMain.postDelayed(this, 1000);      /* das Alter der Daten laeuft sichtbar mit */
        }
    };
    private Surface mSurface;
    private int mWidth, mHeight, mDpi = 160;
    private Rect mVisible;

    MainScreen(@NonNull CarContext ctx, @NonNull Hub hub) {
        super(ctx);
        mPainter = new DashPainter(ctx, hub);
        getLifecycle().addObserver(this);
    }

    /** Neue Daten: von beliebigem Thread aufrufbar. */
    void changed() {
        mMain.post(this::draw);
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(this);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        mMain.post(mTick);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        mMain.removeCallbacks(mTick);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(null);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new NavigationTemplate.Builder().build();
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer c) {
        mSurface = c.getSurface();
        mWidth = c.getWidth();
        mHeight = c.getHeight();
        mDpi = c.getDpi();
        draw();
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        mVisible = visibleArea;
        draw();
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer c) {
        mSurface = null;
    }

    private void draw() {
        Surface sf = mSurface;
        if (sf == null || !sf.isValid()) return;
        Canvas cv;
        try {
            cv = sf.lockCanvas(null);
        } catch (RuntimeException e) {
            return;                                             /* Flaeche gerade im Abbau */
        }
        try {
            cv.drawColor(DashPainter.BG);
            Rect r = mVisible != null && !mVisible.isEmpty() ? mVisible : new Rect(0, 0, mWidth, mHeight);
            mPainter.paint(cv, r, mDpi / 160f, true);
        } finally {
            sf.unlockCanvasAndPost(cv);
        }
    }
}
