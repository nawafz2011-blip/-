package com.nawaf.mohammel

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * يشغّل طابور التحميلات واحد ورا الثاني، ويكمل حتى لو طلعت من التطبيق أو قفلت الشاشة.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: CoJob? = null
    private var lastNotify = 0L
    private var doneCount = 0
    private var failCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let { cancelJob(it) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_ALL) {
            JobQueue.jobs.value.filter { it.active }.forEach { cancelJob(it.id) }
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_NOT_STICKY

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFY_ID, progressNotification("جاري التجهيز...", -1f, -1), type)

        doneCount = 0
        failCount = 0
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mohammel:download")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        worker = scope.launch {
            try {
                Engine.awaitReady()
                while (true) {
                    var job = JobQueue.takeNext()
                    if (job == null) {
                        // مهلة قصيرة لو انضاف تحميل جديد بنفس اللحظة
                        delay(500)
                        job = JobQueue.takeNext() ?: break
                    }
                    runJob(job)
                }
            } catch (_: Throwable) {
                // فشل تجهيز المحرك: نعلّم الباقي كخطأ
                JobQueue.jobs.value.filter { it.active }.forEach { j ->
                    JobQueue.update(j.id) { it.copy(status = JobStatus.ERROR, error = "صار خطأ في تجهيز التطبيق.") }
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun runJob(job: Job) {
        try {
            val saved = Engine.download(this, job.spec, job.id) { p, eta ->
                JobQueue.update(job.id) { it.copy(progress = p, etaSeconds = eta) }
                val now = System.currentTimeMillis()
                if (now - lastNotify > 700) {
                    lastNotify = now
                    notify(progressNotification(job.title, p, eta))
                }
            }
            JobQueue.update(job.id) { it.copy(status = JobStatus.DONE, progress = 100f, saved = saved) }
            JobQueue.get(job.id)?.let { History.add(it) }
            doneCount++
        } catch (t: Throwable) {
            val canceled = t is YoutubeDL.CanceledException || JobQueue.get(job.id)?.status == JobStatus.CANCELED
            if (canceled) {
                JobQueue.update(job.id) { it.copy(status = JobStatus.CANCELED) }
            } else {
                JobQueue.update(job.id) { it.copy(status = JobStatus.ERROR, error = Engine.friendlyError(t)) }
                failCount++
            }
        }
    }

    private fun cancelJob(id: String) {
        if (JobQueue.cancel(id)) {
            JobQueue.update(id) { it.copy(status = JobStatus.CANCELED) }
            YoutubeDL.getInstance().destroyProcessById(id)
        }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (doneCount + failCount > 0) {
            val text = buildString {
                if (doneCount > 0) append("خلص $doneCount تحميل")
                if (failCount > 0) { if (isNotEmpty()) append(" · "); append("فشل $failCount") }
            }
            notify(doneNotification(text), DONE_ID)
        }
        stopSelf()
    }

    private fun notify(n: Notification, id: Int = NOTIFY_ID) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // المستخدم ما سمح بالإشعارات
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(title: String, progress: Float, eta: Long): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val left = JobQueue.pendingCount
        val b = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setSubText(if (left > 1) "باقي $left في القائمة" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, if (left > 1) "إلغاء الكل" else "إلغاء", cancel)
        if (progress < 0) {
            b.setContentText("جاري التجهيز...").setProgress(0, 0, true)
        } else {
            b.setContentText(progressText(progress, eta)).setProgress(100, progress.toInt(), false)
        }
        return b.build()
    }

    private fun doneNotification(text: String): Notification =
        NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFY_ID = 1
        private const val DONE_ID = 2
        const val ACTION_CANCEL = "cancel"
        const val ACTION_CANCEL_ALL = "cancel_all"
        const val EXTRA_ID = "id"

        fun progressText(progress: Float, eta: Long): String {
            if (progress < 0) return "جاري التجهيز..."
            if (progress >= 100) return "جاري المعالجة..."
            val parts = mutableListOf("%d%%".format(progress.toInt()))
            if (eta > 0) parts += "باقي " + if (eta >= 60) "%d:%02d".format(eta / 60, eta % 60) else "$eta ث"
            return parts.joinToString(" · ")
        }

        fun enqueue(context: Context, specs: List<JobSpec>): List<Job> {
            val jobs = JobQueue.add(specs)
            context.startForegroundService(Intent(context, DownloadService::class.java))
            return jobs
        }

        fun cancel(context: Context, id: String) {
            if (JobQueue.get(id)?.status == JobStatus.QUEUED) {
                JobQueue.cancel(id)
                return
            }
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id))
        }
    }
}
