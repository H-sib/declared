package lab.probe

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import java.io.File

/** B-series: a 15-minute periodic job that logs when it actually ran, plus the app's own power-management state. */
object Heartbeat {
    private const val JOB_ID = 4711

    fun log(ctx: Context, line: String) {
        File(ctx.filesDir, "heartbeat.log").appendText("${System.currentTimeMillis()},$line\n")
    }

    fun schedule(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, HeartbeatService::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        val result = scheduler.schedule(info)
        log(ctx, "scheduled result=$result")
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        log(ctx, "cancelled")
    }
}

class HeartbeatService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val usage = getSystemService(UsageStatsManager::class.java)
        val activity = getSystemService(ActivityManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        Heartbeat.log(
            this,
            "ran bucket=${usage.appStandbyBucket} bgRestricted=${activity.isBackgroundRestricted} " +
                "ignoringBatteryOptimizations=${power.isIgnoringBatteryOptimizations(packageName)}",
        )
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
