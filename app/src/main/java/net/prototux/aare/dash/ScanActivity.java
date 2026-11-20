package net.prototux.aare.dash;

import android.os.Bundle;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.camera.CameraSettings;

/**
 * QR-Scanner mit stetigem Autofokus. Die Bibliothek stellt sonst alle zwei Sekunden neu scharf
 * (Fokusart "auto" mit Zeitgeber) - das Bild pumpt und der Code wird nur zwischendurch lesbar.
 */
public final class ScanActivity extends CaptureActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        /* Die Kamera oeffnet erst in onResume; bis dahin gelten geaenderte Einstellungen noch. */
        DecoratedBarcodeView view = findViewById(com.google.zxing.client.android.R.id.zxing_barcode_scanner);
        CameraSettings s = view.getBarcodeView().getCameraSettings();
        s.setFocusMode(CameraSettings.FocusMode.CONTINUOUS);
        s.setMeteringEnabled(true);          /* Schaerfe und Belichtung auf die Bildmitte */
    }
}
