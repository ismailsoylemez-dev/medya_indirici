package com.ismail.medya_indirici

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo

object NotificationHelper {
    const val CHANNEL_PROGRESS = "medya_indirici_progress_channel"
    const val CHANNEL_RESULT = "medya_indirici_result_channel"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS,
                "Aktif İndirmeler",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Devam eden medya indirme ilerleme bildirimleri"
                setShowBadge(false)
            }

            val resultChannel = NotificationChannel(
                CHANNEL_RESULT,
                "İndirme Sonuçları",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Tamamlanan ve hatalı medya indirme bildirimleri"
                enableVibration(true)
            }

            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(resultChannel)
        }
    }

    fun buildForegroundInfo(
        context: Context,
        notificationId: Int,
        downloadId: String,
        title: String,
        contentText: String,
        progress: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        createChannels(context)

        val cancelIntent = Intent(context, RetryReceiver::class.java).apply {
            action = RetryReceiver.ACTION_CANCEL
            putExtra(RetryReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(RetryReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "İptal",
                cancelPendingIntent
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    fun showCompletedNotification(
        context: Context,
        notificationId: Int,
        title: String,
        mediaUri: String?,
        mimeType: String?
    ) {
        if (!canPostNotifications(context)) return
        createChannels(context)

        val viewIntent = if (!mediaUri.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(mediaUri), mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 10000,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("İndirildi: $title")
            .setContentText("Medyayı açmak için dokunun")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId + 10000, notification)
    }

    fun showErrorNotification(
        context: Context,
        notificationId: Int,
        downloadId: String,
        url: String,
        quality: String,
        shortReason: String
    ) {
        if (!canPostNotifications(context)) return
        createChannels(context)

        val retryIntent = Intent(context, RetryReceiver::class.java).apply {
            action = RetryReceiver.ACTION_RETRY
            putExtra(RetryReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            putExtra(RetryReceiver.EXTRA_URL, url)
            putExtra(RetryReceiver.EXTRA_QUALITY, quality)
            putExtra(RetryReceiver.EXTRA_NOTIFICATION_ID, notificationId + 20000)
        }
        val retryPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 20000,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 20000,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("İndirilemedi: $shortReason")
            .setContentText(url)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$shortReason\n$url"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.stat_notify_sync,
                "Tekrar dene",
                retryPendingIntent
            )
            .build()

        NotificationManagerCompat.from(context).notify(notificationId + 20000, notification)
    }

    fun cancelNotification(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

