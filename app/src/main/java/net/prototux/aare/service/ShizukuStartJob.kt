package net.prototux.aare.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import net.prototux.aare.DiagLog
import net.prototux.aare.adb.SelfAdb

/**
 * Starts Shizuku after a reboot (System L-063): Shizuku is gone after a restart, Mate starts it itself 45 s after the boot
 * once a network is there. A job, so the process is kept alive for the start instead of being free to end after the boot
 * broadcast.
 */
class ShizukuStartJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        SelfAdb.ensureShizuku(this, "tablet started") { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val ID = 0x5353 // "SS"
        private const val DELAY_MS = 45_000L

        /** Plans the start; false when the job could not be planned. */
        fun schedule(context: Context): Boolean = runCatching {
            val js = context.getSystemService(JobScheduler::class.java) ?: return false
            js.schedule(JobInfo.Builder(ID, ComponentName(context, ShizukuStartJob::class.java))
                .setMinimumLatency(DELAY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()) == JobScheduler.RESULT_SUCCESS
        }.onFailure { DiagLog.add("shizuku by Mate: start after boot not planned - ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }
}
