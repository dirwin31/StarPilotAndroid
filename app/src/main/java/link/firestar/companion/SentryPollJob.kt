package link.firestar.companion

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper

class SentryPollJob : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var active: JobParameters? = null
    override fun onStartJob(params: JobParameters): Boolean {
        active = params
        Thread { runCatching { SentryPoller.poll(applicationContext) }; handler.post { if (active === params) { active = null; jobFinished(params, false) } } }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { active = null; return true }
    companion object {
        private const val JOB = 5
        fun schedule(ctx: Context) { ctx.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(JOB, ComponentName(ctx, SentryPollJob::class.java))
            .setPeriodic(15 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()) }
        fun cancel(ctx: Context) { ctx.getSystemService(JobScheduler::class.java).cancel(JOB) }
    }
}
