package com.example.app.git

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground-сервис, в котором выполняется git clone.
 * Не зависит от жизненного цикла фрагмента/Activity.
 */
class GitCloneService : Service() {

    companion object {
        const val ACTION_START = "com.example.app.git.START"
        const val ACTION_CANCEL = "com.example.app.git.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_REPO_NAME = "repo_name"
        const val EXTRA_DESTINATION = "destination"

        private const val CHANNEL_ID = "git_clone"
        private const val PROGRESS_NOTIFICATION_ID = 4101
        private const val RESULT_NOTIFICATION_ID = 4102
        private const val NOTIFICATION_THROTTLE_MS = 500L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cloneJob: Job? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                GitCloneManager.cancel()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val repoName = intent.getStringExtra(EXTRA_REPO_NAME).orEmpty()
                val destination = intent.getStringExtra(EXTRA_DESTINATION)
                if (url == null || destination == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (cloneJob?.isActive == true) return START_NOT_STICKY

                createChannel()
                ServiceCompat.startForeground(
                    this,
                    PROGRESS_NOTIFICATION_ID,
                    progressNotification(repoName, "Подключение к репозиторию…", 0, 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                runClone(url, repoName, File(destination))
            }
        }
        return START_NOT_STICKY
    }

    private fun runClone(url: String, repoName: String, destination: File) {
        cloneJob = serviceScope.launch {
            val result = GitRepositoryCloner(applicationContext).clone(
                repositoryUrl = url,
                destination = destination,
                isCancelled = { GitCloneManager.cancelRequested }
            ) { task, completed, total ->
                GitCloneManager.publishProgress(task, completed, total)
                notifyProgress(repoName, task, completed, total)
            }

            result.fold(
                onSuccess = { dir ->
                    GitCloneManager.publishSuccess(repoName, dir.absolutePath)
                    showResult("Клонирование завершено", "$repoName → ${dir.absolutePath}")
                },
                onFailure = { error ->
                    val message = if (GitCloneManager.cancelRequested) {
                        "Клонирование отменено"
                    } else {
                        error.message ?: error.javaClass.simpleName
                    }
                    GitCloneManager.publishFailure(repoName, message)
                    showResult("Ошибка клонирования: $repoName", message)
                }
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15: у dataSync-сервиса есть лимит по времени, завершаемся аккуратно.
        GitCloneManager.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- уведомления ----

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Git Clone", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun progressNotification(
        repoName: String,
        task: String,
        completed: Int,
        total: Int
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, GitCloneService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Git clone: $repoName")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", cancelIntent)

        if (total > 0) {
            val percent = (completed * 100L / total).toInt().coerceIn(0, 100)
            builder.setContentText("$task — $percent%")
            builder.setProgress(total, completed.coerceIn(0, total), false)
        } else {
            builder.setContentText(task)
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun notifyProgress(repoName: String, task: String, completed: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFICATION_THROTTLE_MS) return
        lastNotifyAt = now
        getSystemService(NotificationManager::class.java)
            .notify(PROGRESS_NOTIFICATION_ID, progressNotification(repoName, task, completed, total))
    }

    private fun showResult(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }
}
