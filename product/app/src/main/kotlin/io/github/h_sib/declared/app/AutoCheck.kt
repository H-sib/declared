package io.github.h_sib.declared.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.reduce
import io.github.h_sib.declared.core.report.ReportBuilder
import io.github.h_sib.declared.core.report.Sources
import io.github.h_sib.declared.core.schedule.AutoCheck
import io.github.h_sib.declared.core.schedule.AutoConfig
import io.github.h_sib.declared.core.state.ChangeItem
import io.github.h_sib.declared.sensors.AndroidAccessibilitySource
import io.github.h_sib.declared.sensors.AndroidAppOpsSource
import io.github.h_sib.declared.sensors.AndroidAttestationSource
import io.github.h_sib.declared.sensors.AndroidDeviceAdminSource
import io.github.h_sib.declared.sensors.AndroidDeviceSource
import io.github.h_sib.declared.sensors.AndroidInputMethodSource
import io.github.h_sib.declared.sensors.AndroidNetworkSource
import io.github.h_sib.declared.sensors.AndroidNotificationListenerSource
import io.github.h_sib.declared.sensors.AndroidPackageFactsSource
import io.github.h_sib.declared.sensors.AndroidReferenceSource
import io.github.h_sib.declared.sensors.AndroidUserCaSource
import io.github.h_sib.declared.sensors.AndroidWifiSource
import java.security.MessageDigest
import kotlin.concurrent.thread

/** The sources of one reading of the phone. The screen and the automatic check read the phone the same way, so they cannot disagree. */
object ReportSources {
    fun builder(app: Application) = ReportBuilder(
        Sources(
            accessibility = AndroidAccessibilitySource(app),
            notificationListeners = AndroidNotificationListenerSource(app),
            deviceAdmins = AndroidDeviceAdminSource(app),
            inputMethods = AndroidInputMethodSource(app),
            appOps = AndroidAppOpsSource(app),
            packages = AndroidPackageFactsSource(app),
            device = AndroidDeviceSource(app),
            network = AndroidNetworkSource(app),
            wifi = AndroidWifiSource(app),
            userCas = AndroidUserCaSource(),
            attestation = AndroidAttestationSource(),
            reference = AndroidReferenceSource(app),
        ),
    )
}

/**
 * The person's choice about automatic checks, kept on this phone. Off until they turn it on. What was already announced is kept as
 * short hashes, so no app name is written to disk in the clear.
 */
class AutoCheckStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun config() = AutoConfig(prefs.getBoolean(ENABLED, false), AutoCheck.clamp(prefs.getInt(MINUTES, AutoCheck.DEFAULT_MINUTES)))

    fun save(config: AutoConfig) {
        prefs.edit {
            putBoolean(ENABLED, config.enabled)
            putInt(MINUTES, AutoCheck.clamp(config.intervalMinutes))
            if (!config.enabled) {
                remove(TOLD)
                remove(ANNOUNCED)
            }
        }
    }

    fun told(): Set<String> = prefs.getStringSet(TOLD, emptySet())?.toSet() ?: emptySet()

    fun saveTold(told: Set<String>) {
        prefs.edit { putStringSet(TOLD, told) }
    }

    /** How many notifications automatic checks have posted since they were turned on. Kept so a lab script can see that a repeat was not announced. */
    fun announced(): Int = prefs.getInt(ANNOUNCED, 0)

    fun countAnnouncement() {
        prefs.edit { putInt(ANNOUNCED, announced() + 1) }
    }

    private companion object {
        const val FILE = "auto"
        const val ENABLED = "enabled"
        const val MINUTES = "minutes"
        const val TOLD = "told"
        const val ANNOUNCED = "announced"
    }
}

/** Asks Android to run [CheckJobService] at the chosen interval, or stops asking. Android decides the exact moment and may run it later. */
object AutoCheckScheduler {
    const val JOB_ID = 4201

    /** Makes the schedule match [config]. A schedule that already matches is left alone, so opening the app does not push the next check back. */
    fun apply(context: Context, config: AutoConfig) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (!config.enabled) {
            jobs.cancel(JOB_ID)
            return
        }
        val interval = AutoCheck.clamp(config.intervalMinutes) * 60_000L
        val pending = jobs.getPendingJob(JOB_ID)
        if (pending != null && pending.isPeriodic && pending.intervalMillis == interval) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, CheckJobService::class.java)).setPeriodic(interval).setPersisted(true).build(),
        )
    }
}

/** One short run of the automatic check, on its own thread. It reads the phone, never writes the baseline or the history, and then ends. */
class CheckJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!AutoCheckStore(this).config().enabled) {
            AutoCheckScheduler.apply(this, AutoConfig())
            return false
        }
        worker = thread(name = "declared-auto-check") {
            try {
                AutoChecker.run(application)
            } catch (e: Exception) {
                // A read that fails (the package manager can be busy) is simply tried again at the next interval; nothing is announced for it.
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return false
    }
}

object AutoChecker {
    /**
     * Reads the phone and compares it with what the person last saw (their saved baseline), with the same rules as the screen. If something
     * needs a look that they were not already told about, one notification says how many. Nothing is changed on the phone or in the app's files
     * except the record of what was announced.
     */
    fun run(app: Application) {
        val store = AutoCheckStore(app)
        if (!store.config().enabled) return
        val report = ReportSources.builder(app).build(Build.VERSION.SDK_INT)
        val withBaseline = reduce(AppState(), Event.StoreLoaded(EncryptedFileStore(app).load()))
        val changes = reduce(withBaseline, Event.ReportLoaded(report)).changes ?: return
        val decision = AutoCheck.decide(changes.items, store.told(), ::fingerprint)
        store.saveTold(decision.told)
        if (decision.notify) {
            ChangeNotifier.post(app, decision.newCount)
            store.countAnnouncement()
        }
    }

    private fun fingerprint(item: ChangeItem): String =
        MessageDigest.getInstance("SHA-256").digest(AutoCheck.key(item).toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

/** The one notification: how many changes need a look, never which app, so a locked screen shows nothing about the phone's contents. */
object ChangeNotifier {
    private const val CHANNEL = "changes"
    private const val ID = 1

    fun post(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Changes worth a look", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when an automatic check finds a change that needs a look."
            },
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra("start", "check").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hidden = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Declared").setContentText("A check found something.").build()
        manager.notify(
            ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(AutoCheck.NOTIFICATION_TITLE)
                .setContentText(AutoCheck.notificationText(count))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(hidden)
                .build(),
        )
    }
}
