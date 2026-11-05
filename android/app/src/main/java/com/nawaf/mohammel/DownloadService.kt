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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * التحميل يشتغل هنا عشان يكمل حتى لو طلعت من التطبيق أو قفلت الشاشة.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var canceled = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            canceled = true
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        val url = intent?.getStringExtra(EXTRA_URL) ?: return stop()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "فيديو"
        val option = QualityOption(
            id = intent.getStringExtra(EXTRA_QUALITY) ?: "best",
            label = intent.getStringExtra(EXTRA_LABEL) ?: "",
            detail = "",
            isAudio = intent.getBooleanExtra(EXTRA_AUDIO, false),
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFY_ID, progressNotification(title, -1f, -1), type)

        canceled = false
        DownloadState.set(DownloadStatus.Running(title, -1f, -1))
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mohammel:download")
            .apply { acquire(3 * 60 * 60 * 1000L) }

        job = scope.launch {
            try {
                Engine.awaitReady()
                val saved = Engine.download(this@DownloadService, url, option, PROCESS_ID) { p, eta ->
                    DownloadState.set(DownloadStatus.Running(title, p, eta))
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 700) {
                        lastNotify = now
                        notify(progressNotification(title, p, eta))
                    }
                }
                DownloadState.set(DownloadStatus.Done(title, saved))
                finish(doneNotification(title, "انحفظ في " + if (option.isAudio) "الموسيقى" else "المعرض", saved))
            } catch (t: Throwable) {
                if (canceled) {
                    DownloadState.set(DownloadStatus.Canceled(title))
                    finish(null)
                } else {
                    val msg = Engine.friendlyError(t)
                    DownloadState.set(DownloadStatus.Failed(title, msg))
                    finish(doneNotification(title, msg, null))
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
        return START_NOT_STICKY
    }

    private fun stop(): Int { stopSelf(); return START_NOT_STICKY }

    private fun finish(notification: Notification?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (notification != null) notify(notification, DONE_ID)
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
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "إلغاء", cancel)
        if (progress < 0) {
            b.setContentText("جاري التجهيز...").setProgress(0, 0, true)
        } else {
            b.setContentText(progressText(progress, eta)).setProgress(100, progress.toInt(), false)
        }
        return b.build()
    }

    private fun doneNotification(title: String, text: String, file: SavedFile?): Notification {
        val content = if (file != null) {
            PendingIntent.getActivity(
                this, 2,
                Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else openAppIntent()
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PROCESS_ID = "mohammel-download"
        private const val NOTIFY_ID = 1
        private const val DONE_ID = 2
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_LABEL = "label"
        const val EXTRA_AUDIO = "audio"

        fun progressText(progress: Float, eta: Long): String {
            if (progress < 0) return "جاري التجهيز..."
            val parts = mutableListOf("%d%%".format(progress.toInt()))
            if (eta > 0) parts += "باقي " + if (eta >= 60) "%d:%02d".format(eta / 60, eta % 60) else "$eta ث"
            if (progress >= 100) return "جاري دمج الصوت مع الصورة..."
            return parts.joinToString(" · ")
        }

        fun start(context: Context, summary: VideoSummary, option: QualityOption) {
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, summary.url)
                .putExtra(EXTRA_TITLE, summary.title)
                .putExtra(EXTRA_QUALITY, option.id)
                .putExtra(EXTRA_LABEL, option.label)
                .putExtra(EXTRA_AUDIO, option.isAudio)
            context.startForegroundService(i)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
