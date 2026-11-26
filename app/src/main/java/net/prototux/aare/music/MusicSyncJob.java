package net.prototux.aare.music;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

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
        /* System I-142: the loading in a foreground service of its own (a job is stopped after some ten minutes, with the
           screen off the tablet slept); the job is done at once. Refused by Android: as before, in the job */
        if (!Offline.running()) try {
            startForegroundService(new android.content.Intent(this, MusicDownload.class).putExtra("why", "every six hours"));
            return false;
        } catch (RuntimeException e) {
            net.prototux.aare.DiagLog.INSTANCE.add("music offline: service refused (" + e.getClass().getSimpleName() + ") - loading in the job", null, "AUDIO");
        }
        Thread work = new Thread(() -> {
            int r = Offline.sync(this, null);
            /* System L-105: what was played offline told to Plex, and the bass of new offline songs measured over their whole length */
            try {
                Plex plex = Plex.load(this);
                if (plex != null) Plex.flushPlayed(this, plex);
                SongBass.measureOffline(this, 60);
            } catch (RuntimeException e) {
                Log.w("MusicSyncJob", "after the sync: " + e.getClass().getSimpleName());
            }
            /* I-118e: ohne Server (abgemeldet) kein neuer Versuch mit Backoff - erst zur naechsten Periode wieder */
            boolean server = r < 0 && Plex.load(this) != null;
            if (r < 0 && !server) Log.i("MusicSyncJob", "sync skipped: no server (signed out), next try at the next period");
            jobFinished(params, server);
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
