package io.freemytv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlin.math.abs

/**
 * Runs the mode switches in the background (HomeMode). As a job, the system keeps the app's main process running until
 * the switch finishes, even with the screen showing something else.
 */
class ModeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val extras = params.extras
        val action = extras.getString("action", "")
        val mode = extras.getString("mode", "")
        val app = applicationContext
        Thread({
            try {
                when (action) {
                    APPLY -> HomeMode.applyNow(app, mode)
                    VERIFY -> HomeMode.verifyNow(app)
                    REVERT -> HomeMode.revertHomeNow(app)
                    MEMORY -> HomeMode.measureMemoryNow(app, mode)
                }
            } catch (t: Throwable) {
                AppLog.w("job $action failed: $t")
            }
            jobFinished(params, false)
        }, "free-my-tv-$action").start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        const val APPLY = "apply"
        const val VERIFY = "verify"
        const val REVERT = "revert"
        const val MEMORY = "memory"

        /** Schedules a job (one pending per action: a new request replaces the old one). */
        @JvmStatic
        fun schedule(c: Context, action: String, mode: String?, delayMs: Long) {
            val scheduler = c.getSystemService(JobScheduler::class.java) ?: return
            val extras = PersistableBundle()
            extras.putString("action", action)
            extras.putString("mode", mode ?: "")
            val job = JobInfo.Builder(1000 + abs(action.hashCode() % 1000), ComponentName(c, ModeJob::class.java))
                .setExtras(extras)
            if (delayMs > 0) job.setMinimumLatency(delayMs)
            job.setOverrideDeadline(delayMs + 2_000)
            scheduler.schedule(job.build())
        }
    }
}
