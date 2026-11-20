package net.prototux.aare.dash;

import net.prototux.aare.R;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/** Einstieg fuer Android Auto. */
public final class IdmateCarService extends CarAppService {
    /** AARE: quelloffene Telefon-Seite von Android Auto (Weg B der Uebergabe), Host fuer Car-App-Library-Apps. */
    static final String AARE_PACKAGE = "net.prototux.aare";
    static final String AARE_RELEASE_SHA256 = "63ef0de1db2c09bb7fb25ce7ea4adc3538af423b885278b59df2889c7f18286f";

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        /* Debug-Build: jeden Host zulassen (Simulator, selbst gebautes AARE).
           Release-Build: Googles Android Auto (Liste der Bibliothek) und AARE in der veroeffentlichten
           Fassung - dessen Signatur steht im README von AARE (github.com/prototux/AARE, "Installing"). */
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        HostValidator.Builder b = new HostValidator.Builder(getApplicationContext())
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .addAllowedHost(AARE_PACKAGE, AARE_RELEASE_SHA256);
        /* Mate itself as host: since 20.11.2026 it has its own application id (app.idmate.mate) and its own key,
           so the own package with the own signature, read at run time. */
        for (String digest : ownDigests()) b.addAllowedHost(getPackageName(), digest);
        return b.build();
    }

    /** SHA-256 of the own signing certificates, lower-case hex without colons, as HostValidator wants them. */
    private String[] ownDigests() {
        try {
            PackageManager pm = getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sigs = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            String[] out = new String[sigs.length];
            for (int i = 0; i < sigs.length; i++) {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[i].toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte x : d) hex.append(String.format(java.util.Locale.ROOT, "%02x", x));
                out[i] = hex.toString();
            }
            return out;
        } catch (Exception e) {
            return new String[0];
        }
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                Hub hub = Hub.get(getCarContext());
                MainScreen screen = new MainScreen(getCarContext(), hub);
                Runnable changed = screen::changed;
                hub.listen(changed);
                hub.acquire();
                /* Fahrzeugdaten-Sonde: fehlende Berechtigungen fragt Android Auto am Tablet ab */
                CarProbe probe = new CarProbe(getCarContext(), hub.telemetry, changed);
                java.util.List<String> missing = probe.missing();
                if (!missing.isEmpty() && getCarContext().getCarAppApiLevel() >= 3)
                    getCarContext().requestPermissions(missing, (granted, rejected) -> probe.start());
                probe.start();
                getLifecycle().addObserver(new DefaultLifecycleObserver() {
                    @Override
                    public void onDestroy(@NonNull LifecycleOwner owner) {
                        probe.stop();
                        hub.unlisten(changed);
                        hub.release();
                    }
                });
                return screen;
            }
        };
    }
}
