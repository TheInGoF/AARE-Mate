package net.prototux.aare.music;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Haelt die abonnierten Listen aktuell (Offline): alle sechs Stunden, sobald ein passendes Netz da ist
 * (bei "nur WLAN" eines ohne Kosten) und der Akku nicht fast leer ist. Ohne Abo wird nichts geplant.
 */
public final class MusicSyncJob extends JobService {
    private static final int ID = 0x4d53;   /* "MS" */

    /** Plant den Abgleich (neu), wenn Listen abonniert sind; sonst hebt es ihn auf. Mehrfach aufrufen schadet nicht. */
    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (Offline.lists(c).isEmpty()) { js.cancel(ID); return; }
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, MusicSyncJob.class))
                .setRequiredNetworkType(Offline.wifiOnly(c) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(Offline.EVERY_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Thread work = new Thread(() -> {
            int r = Offline.sync(this, null);
            jobFinished(params, r < 0);
        }, "music-sync");
        work.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        /* der laufende Abgleich prueft das Netz vor jedem Titel; halbe Dateien bleiben nie liegen */
        return true;
    }
}
