package com.ismail.medya_indirici

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL)?.trim().orEmpty()
        if (url.isBlank()) {
            return@withContext Result.failure()
        }

        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val quality = inputData.getString(KEY_QUALITY)
            ?.takeIf { it.isNotBlank() }
            ?: HistoryStorage.getQuality(applicationContext)
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        val notificationId = abs(downloadId.hashCode()).coerceAtLeast(1000)

        var currentTitle = extractHostTitle(url)
        var currentMediaType = if (quality == "audio") "audio" else "video"

        val initialRecord = DownloadRecord(
            id = downloadId,
            url = url,
            title = currentTitle,
            status = "downloading",
            progress = 0f,
            etaInSeconds = -1L,
            quality = quality,
            mediaType = currentMediaType,
            filePath = null,
            mediaUri = null,
            mimeType = null,
            errorMessage = null,
            timestamp = timestamp
        )
        HistoryStorage.upsert(applicationContext, initialRecord)
        ProgressStreamHandler.emitRecord(initialRecord)

        try {
            setForeground(
                NotificationHelper.buildForegroundInfo(
                    context = applicationContext,
                    notificationId = notificationId,
                    downloadId = downloadId,
                    title = "Hazırlanıyor...",
                    contentText = url,
                    progress = 0,
                    indeterminate = true
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Foreground servis baslatilamadi: ${e.message}")
        }

        val initResult = App.ensureInitialized(applicationContext)
        if (initResult.isFailure) {
            return@withContext handleError(
                downloadId = downloadId,
                notificationId = notificationId,
                url = url,
                title = currentTitle,
                quality = quality,
                mediaType = currentMediaType,
                timestamp = timestamp,
                throwable = initResult.exceptionOrNull()
                    ?: IllegalStateException("Motor başlatılamadı")
            )
        }

        val tempDir = File(applicationContext.cacheDir, "ytdlp_temp/$downloadId")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        val cookieFile = prepareCookieFile(tempDir)

        try {
            val downloadPlaylist = HistoryStorage.isDownloadPlaylistEnabled(applicationContext)

            // ADIM 5 - Medya çözümleme (getInfo)
            val resolvedTitle = tryResolveMediaTitle(
                url = url,
                cookieFile = cookieFile
            )
            if (!resolvedTitle.isNullOrBlank()) {
                currentTitle = resolvedTitle
                val infoRecord = initialRecord.copy(title = currentTitle)
                HistoryStorage.upsert(applicationContext, infoRecord)
                ProgressStreamHandler.emitRecord(infoRecord)
                updateForegroundSafe(
                    notificationId = notificationId,
                    downloadId = downloadId,
                    title = currentTitle,
                    contentText = "İndiriliyor...",
                    progress = 0,
                    indeterminate = true
                )
            }

            // ADIM 6 - İndirme
            var downloadedFiles = emptyList<File>()
            var ytdlpError: Exception? = null

            try {
                val request = buildDownloadRequest(
                    url = url,
                    tempDir = tempDir,
                    quality = quality,
                    downloadPlaylist = downloadPlaylist,
                    cookieFile = cookieFile
                )

                var lastNotificationUpdateMs = 0L
                var lastNotifiedProgress = -1

                YoutubeDL.getInstance().execute(
                    request,
                    downloadId
                ) { progress, etaInSeconds, _ ->
                    val safeProgress = progress.coerceIn(0f, 100f)
                    val progressInt = safeProgress.toInt()
                    val now = System.currentTimeMillis()

                    val progressRecord = DownloadRecord(
                        id = downloadId,
                        url = url,
                        title = currentTitle,
                        status = "downloading",
                        progress = safeProgress,
                        etaInSeconds = etaInSeconds,
                        quality = quality,
                        mediaType = currentMediaType,
                        filePath = null,
                        mediaUri = null,
                        mimeType = null,
                        errorMessage = null,
                        timestamp = timestamp
                    )
                    HistoryStorage.upsert(applicationContext, progressRecord)
                    ProgressStreamHandler.emitRecord(progressRecord)

                    if (now - lastNotificationUpdateMs >= 500L || abs(progressInt - lastNotifiedProgress) >= 2) {
                        lastNotificationUpdateMs = now
                        lastNotifiedProgress = progressInt
                        val etaText = if (etaInSeconds > 0) " • ${etaInSeconds}sn kaldı" else ""
                        updateForegroundSafe(
                            notificationId = notificationId,
                            downloadId = downloadId,
                            title = currentTitle,
                            contentText = "%$progressInt$etaText",
                            progress = progressInt,
                            indeterminate = progressInt <= 0
                        )
                    }
                }

                cookieFile?.delete()
                downloadedFiles = collectDownloadedFiles(tempDir)
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: Exception) {
                ytdlpError = e
                cookieFile?.delete()
                downloadedFiles = collectDownloadedFiles(tempDir)
            }

            // Eğer yt-dlp görsel/direkt medya için format bulamadıysa direkt/OG görsel-video yedek indiriciyi dene
            if (downloadedFiles.isEmpty()) {
                val fallbackFile = tryDirectOrOgMediaDownload(url, tempDir, currentTitle)
                if (fallbackFile != null && fallbackFile.exists() && fallbackFile.length() > 0L) {
                    downloadedFiles = listOf(fallbackFile)
                } else if (ytdlpError != null) {
                    throw ytdlpError
                } else {
                    throw IllegalStateException("İndirilen medya dosyası oluşturulamadı.")
                }
            }

            // ADIM 7 - Galeriye kaydetme
            var lastSaved: SavedMediaResult? = null
            for ((index, file) in downloadedFiles.withIndex()) {
                val itemTitle = if (downloadedFiles.size > 1) {
                    "${currentTitle}_${index + 1}"
                } else {
                    currentTitle
                }
                lastSaved = MediaStoreSaver.saveFileToGallery(
                    context = applicationContext,
                    sourceFile = file,
                    preferredTitle = itemTitle
                )
            }

            val savedResult = lastSaved
                ?: throw IllegalStateException("Medya galeriye kaydedilemedi.")

            currentMediaType = savedResult.mediaType

            // ADIM 8 - Bitiş
            val completedRecord = DownloadRecord(
                id = downloadId,
                url = url,
                title = currentTitle,
                status = "completed",
                progress = 100f,
                etaInSeconds = 0L,
                quality = quality,
                mediaType = savedResult.mediaType,
                filePath = savedResult.displayPath,
                mediaUri = savedResult.uriString,
                mimeType = savedResult.mimeType,
                errorMessage = null,
                timestamp = timestamp
            )
            HistoryStorage.upsert(applicationContext, completedRecord)
            ProgressStreamHandler.emitRecord(completedRecord)

            NotificationHelper.showCompletedNotification(
                context = applicationContext,
                notificationId = notificationId,
                title = currentTitle,
                mediaUri = savedResult.uriString,
                mimeType = savedResult.mimeType
            )

            return@withContext Result.success()
        } catch (e: YoutubeDL.CanceledException) {
            val cancelledRecord = DownloadRecord(
                id = downloadId,
                url = url,
                title = currentTitle,
                status = "cancelled",
                progress = 0f,
                etaInSeconds = -1L,
                quality = quality,
                mediaType = currentMediaType,
                filePath = null,
                mediaUri = null,
                mimeType = null,
                errorMessage = "İndirme kullanıcı tarafından iptal edildi",
                timestamp = timestamp
            )
            HistoryStorage.upsert(applicationContext, cancelledRecord)
            ProgressStreamHandler.emitRecord(cancelledRecord)
            return@withContext Result.failure()
        } catch (e: Exception) {
            return@withContext handleError(
                downloadId = downloadId,
                notificationId = notificationId,
                url = url,
                title = currentTitle,
                quality = quality,
                mediaType = currentMediaType,
                timestamp = timestamp,
                throwable = e
            )
        } finally {
            try {
                if (tempDir.exists()) {
                    tempDir.deleteRecursively()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun tryResolveMediaTitle(url: String, cookieFile: File?): String? {
        return try {
            val infoRequest = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--socket-timeout", "15")
                if (cookieFile != null && cookieFile.exists()) {
                    addOption("--cookies", cookieFile.absolutePath)
                }
            }
            val info = YoutubeDL.getInstance().getInfo(infoRequest)
            info.title?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "getInfo baslik cozumlemesi atlandi: ${e.message}")
            null
        }
    }

    private fun buildDownloadRequest(
        url: String,
        tempDir: File,
        quality: String,
        downloadPlaylist: Boolean,
        cookieFile: File?
    ): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--no-part")
            addOption("--socket-timeout", "30")
            addOption("--retries", "3")
            if (!downloadPlaylist) {
                addOption("--no-playlist")
            } else {
                addOption("--yes-playlist")
            }
            if (cookieFile != null && cookieFile.exists()) {
                addOption("--cookies", cookieFile.absolutePath)
            }
            when (quality) {
                "audio" -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
                "720p" -> {
                    addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
                    addOption("-S", "res:720,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
                else -> {
                    addOption("-f", "bv*+ba/b")
                    addOption("-S", "ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
            }
            addOption("-o", File(tempDir, "%(title).70B_%(id)s.%(ext)s").absolutePath)
        }
    }

    private fun prepareCookieFile(tempDir: File): File? {
        val rawCookies = HistoryStorage.getCookies(applicationContext).trim()
        if (rawCookies.isBlank()) return null
        return try {
            val file = File(tempDir, "cookies.txt")
            val content = if (rawCookies.startsWith("# Netscape HTTP Cookie File")) {
                rawCookies
            } else {
                "# Netscape HTTP Cookie File\n$rawCookies"
            }
            file.writeText(content)
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun collectDownloadedFiles(tempDir: File): List<File> {
        if (!tempDir.exists()) return emptyList()
        return tempDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.name != "cookies.txt" &&
                    !file.name.endsWith(".part", ignoreCase = true) &&
                    !file.name.endsWith(".ytdl", ignoreCase = true) &&
                    !file.name.endsWith(".temp", ignoreCase = true) &&
                    file.length() > 0L
            }
            .sortedBy { it.lastModified() }
            .toList()
    }

    private fun tryDirectOrOgMediaDownload(urlString: String, tempDir: File, title: String): File? {
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
            }
            connection.connect()
            val contentType = connection.contentType?.lowercase().orEmpty()

            if (contentType.startsWith("image/") || contentType.startsWith("video/") || contentType.startsWith("audio/")) {
                val ext = guessExtensionFromMimeOrUrl(contentType, urlString)
                val outFile = File(tempDir, "direct_${System.currentTimeMillis()}.$ext")
                connection.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return outFile
            }

            if (contentType.contains("text/html")) {
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                val ogVideo = Regex(
                    """<meta[^>]+property=["']og:video(?::secure_url|:url)?["'][^>]+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video(?::secure_url|:url)?["']""",
                        RegexOption.IGNORE_CASE
                    ).find(html)?.groupValues?.getOrNull(1)

                val ogImage = Regex(
                    """<meta[^>]+property=["']og:image(?::secure_url|:url)?["'][^>]+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.getOrNull(1)
                    ?: Regex(
                        """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image(?::secure_url|:url)?["']""",
                        RegexOption.IGNORE_CASE
                    ).find(html)?.groupValues?.getOrNull(1)

                val targetMediaUrl = (ogVideo ?: ogImage)?.replace("&amp;", "&")
                if (!targetMediaUrl.isNullOrBlank() && targetMediaUrl.startsWith("http")) {
                    val mediaConn = (URL(targetMediaUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 20000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    mediaConn.connect()
                    val mediaMime = mediaConn.contentType?.lowercase().orEmpty()
                    val ext = guessExtensionFromMimeOrUrl(mediaMime, targetMediaUrl)
                    val outFile = File(tempDir, "${title.take(30)}_${System.currentTimeMillis()}.$ext")
                    mediaConn.inputStream.use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    return outFile
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun guessExtensionFromMimeOrUrl(contentType: String, url: String): String {
        return when {
            contentType.contains(" png") || contentType.contains("/png") -> "png"
            contentType.contains("webp") -> "webp"
            contentType.contains("gif") -> "gif"
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("mp4") -> "mp4"
            contentType.contains("webm") -> "webm"
            contentType.contains("mpeg") || contentType.contains("mp3") -> "mp3"
            else -> {
                val cleanPath = url.substringBefore('?').substringAfterLast('.', "")
                    .lowercase()
                if (cleanPath in listOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "webm", "mp3", "m4a")) {
                    cleanPath
                } else if (contentType.startsWith("image/")) {
                    "jpg"
                } else {
                    "mp4"
                }
            }
        }
    }

    private fun updateForegroundSafe(
        notificationId: Int,
        downloadId: String,
        title: String,
        contentText: String,
        progress: Int,
        indeterminate: Boolean
    ) {
        try {
            setForegroundAsync(
                NotificationHelper.buildForegroundInfo(
                    context = applicationContext,
                    notificationId = notificationId,
                    downloadId = downloadId,
                    title = title,
                    contentText = contentText,
                    progress = progress,
                    indeterminate = indeterminate
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun handleError(
        downloadId: String,
        notificationId: Int,
        url: String,
        title: String,
        quality: String,
        mediaType: String,
        timestamp: Long,
        throwable: Throwable
    ): Result {
        val rawMessage = throwable.message ?: throwable.toString()
        val isNetworkError = isTransientNetworkError(rawMessage)
        val shortReason = classifyErrorReason(rawMessage)

        // ADIM 9: Ağ hatası ise WorkManager Result.retry() (max 3 deneme, exponential backoff)
        if (isNetworkError && runAttemptCount < 2) {
            val retryingRecord = DownloadRecord(
                id = downloadId,
                url = url,
                title = title,
                status = "retrying",
                progress = 0f,
                etaInSeconds = -1L,
                quality = quality,
                mediaType = mediaType,
                filePath = null,
                mediaUri = null,
                mimeType = null,
                errorMessage = "Ağ hatası, yeniden deneniyor (${runAttemptCount + 1}/3)...",
                timestamp = timestamp
            )
            HistoryStorage.upsert(applicationContext, retryingRecord)
            ProgressStreamHandler.emitRecord(retryingRecord)
            return Result.retry()
        }

        val failedRecord = DownloadRecord(
            id = downloadId,
            url = url,
            title = title,
            status = "failed",
            progress = 0f,
            etaInSeconds = -1L,
            quality = quality,
            mediaType = mediaType,
            filePath = null,
            mediaUri = null,
            mimeType = null,
            errorMessage = shortReason,
            timestamp = timestamp
        )
        HistoryStorage.upsert(applicationContext, failedRecord)
        ProgressStreamHandler.emitRecord(failedRecord)

        NotificationHelper.showErrorNotification(
            context = applicationContext,
            notificationId = notificationId,
            downloadId = downloadId,
            url = url,
            quality = quality,
            shortReason = shortReason
        )

        return Result.failure()
    }

    private fun isTransientNetworkError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("timed out") ||
            lower.contains("timeout") ||
            lower.contains("unable to resolve host") ||
            lower.contains("network is unreachable") ||
            lower.contains("connection reset") ||
            lower.contains("temporary failure in name resolution") ||
            lower.contains("http error 502") ||
            lower.contains("http error 503") ||
            lower.contains("http error 504")
    }

    private fun classifyErrorReason(msg: String): String {
        val lower = msg.lowercase()
        return when {
            lower.contains("login required") ||
                lower.contains("sign in to confirm") ||
                lower.contains("cookies") ||
                lower.contains("private") ||
                lower.contains("age-restricted") -> "Giriş / Cookie gerekli"
            lower.contains("unsupported url") -> "Desteklenmeyen site veya bağlantı"
            lower.contains("no video formats found") -> "İndirilebilir medya formatı bulunamadı"
            lower.contains("video unavailable") ||
                lower.contains("not available") ||
                lower.contains("http error 404") -> "Medya mevcut değil veya silinmiş"
            lower.contains("http error 403") -> "Erişim engellendi (HTTP 403 / yt-dlp güncelleyin)"
            lower.contains("http error 429") -> "Çok fazla istek (HTTP 429)"
            isTransientNetworkError(msg) -> "Ağ bağlantı hatası"
            else -> msg.lineSequence()
                .firstOrNull { it.contains("ERROR:", ignoreCase = true) }
                ?.substringAfter("ERROR:")
                ?.trim()
                ?.take(80)
                ?: "İndirme işlemi başarısız oldu"
        }
    }

    private fun extractHostTitle(url: String): String {
        return try {
            val host = URL(url).host.removePrefix("www.")
            "Medya ($host)"
        } catch (_: Exception) {
            "Medya İndiriliyor"
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_URL = "key_url"
        const val KEY_DOWNLOAD_ID = "key_download_id"
        const val KEY_QUALITY = "key_quality"
        const val KEY_TIMESTAMP = "key_timestamp"

        fun enqueue(
            context: Context,
            url: String,
            quality: String? = null,
            existingDownloadId: String? = null
        ): String {
            val appContext = context.applicationContext
            val downloadId = existingDownloadId ?: UUID.randomUUID().toString()
            val resolvedQuality = quality ?: HistoryStorage.getQuality(appContext)
            val timestamp = System.currentTimeMillis()

            val initialRecord = DownloadRecord(
                id = downloadId,
                url = url.trim(),
                title = try {
                    "Medya (${URL(url.trim()).host.removePrefix("www.")})"
                } catch (_: Exception) {
                    "Medya"
                },
                status = "queued",
                progress = 0f,
                etaInSeconds = -1L,
                quality = resolvedQuality,
                mediaType = if (resolvedQuality == "audio") "audio" else "video",
                filePath = null,
                mediaUri = null,
                mimeType = null,
                errorMessage = null,
                timestamp = timestamp
            )
            HistoryStorage.upsert(appContext, initialRecord)
            ProgressStreamHandler.emitRecord(initialRecord)

            val inputData = Data.Builder()
                .putString(KEY_URL, url.trim())
                .putString(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_QUALITY, resolvedQuality)
                .putLong(KEY_TIMESTAMP, timestamp)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15L,
                    TimeUnit.SECONDS
                )
                .addTag("download_$downloadId")
                .build()

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "download_$downloadId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

            return downloadId
        }
    }
}

