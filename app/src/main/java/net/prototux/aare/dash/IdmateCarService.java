package net.prototux.aare.dash;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.validation.HostValidator;

/**
 * The dashboard's entry in the app list. Since 19.11.2026 Mate draws the dashboard itself (DashArea/DashView) and binds
 * no session here; the service is not exported, so no other host can bind it either. It stays as the mark by which the
 * app list knows the dashboard (isDashId).
 */
public final class IdmateCarService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        /* not exported: only this app could bind it */
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                return new Screen(getCarContext()) {
                    @NonNull
                    @Override
                    public Template onGetTemplate() {
                        return new MessageTemplate.Builder(getCarContext().getString(net.prototux.aare.R.string.dash_name)).build();
                    }
                };
            }
        };
    }
}
