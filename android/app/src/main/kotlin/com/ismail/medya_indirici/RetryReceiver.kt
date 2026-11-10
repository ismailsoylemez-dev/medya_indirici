package com.ismail.medya_indirici

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.work.WorkManager
import com.yausername.youtubedl_android.YoutubeDL

class RetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notificationId != -1) {
            NotificationHelper.cancelNotification(context, notificationId)
        }

        when (action) {
            ACTION_RETRY -> {
                val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
                val quality = intent.getStringExtra(EXTRA_QUALITY)
                    ?: HistoryStorage.getQuality(context)
                if (url.isNotBlank()) {
                    DownloadWorker.enqueue(
                        context = context,
                        url = url,
                        quality = quality,
                        existingDownloadId = downloadId.takeIf { it.isNotBlank() }
                    )
                    Toast.makeText(context, "İndirme yeniden başlatıldı", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_CANCEL -> {
                if (downloadId.isNotBlank()) {
                    try {
                        YoutubeDL.getInstance().destroyProcessById(downloadId)
                    } catch (_: Exception) {
                    }
                    WorkManager.getInstance(context.applicationContext)
                        .cancelUniqueWork("download_$downloadId")

                    val existing = HistoryStorage.getById(context, downloadId)
                    if (existing != null) {
                        val cancelled = existing.copy(
                            status = "cancelled",
                            progress = 0f,
                            etaInSeconds = -1L,
                            errorMessage = "İndirme iptal edildi"
                        )
                        HistoryStorage.upsert(context, cancelled)
                        ProgressStreamHandler.emitRecord(cancelled)
                    }
                    Toast.makeText(context, "İndirme iptal edildi", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val ACTION_RETRY = "com.ismail.medya_indirici.ACTION_RETRY"
        const val ACTION_CANCEL = "com.ismail.medya_indirici.ACTION_CANCEL"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}

