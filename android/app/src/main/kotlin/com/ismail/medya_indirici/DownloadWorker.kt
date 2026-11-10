package com.ismail.medya_indirici

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?.takeIf { it.isNotBlank() }
            ?: id.toString()
        val url = inputData.getString(KEY_URL)?.trim().orEmpty()
        val notificationId = abs(downloadId.hashCode()).coerceAtLeast(1000)
        return NotificationHelper.buildForegroundInfo(
            context = applicationContext,
            notificationId = notificationId,
            downloadId = downloadId,
            title = "Hazırlanıyor...",
            contentText = url.ifBlank { "Medya hazırlanıyor" },
            progress = 0,
            indeterminate = true
        )
    }

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
                    title = currentTitle,
                    contentText = "İndirme başlatılıyor...",
                    progress = 0,
                    indeterminate = true
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Foreground servis baslatilamadi: ${e.message}")
        }

        val tempDir = File(applicationContext.cacheDir, "ytdlp_temp/$downloadId")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
        tempDir.mkdirs()

        try {
            // Hızlı yol: Eğer bağlantı doğrudan bir görsel dosyası ise yt-dlp başlatmadan anında indir
            if (quality != "audio" && isDirectImageUrl(url)) {
                val directImage = tryDownloadDirectMediaStream(url, tempDir, currentTitle, allowImage = true)
                if (directImage != null && directImage.exists() && directImage.length() > 0L) {
                    return@withContext finalizeSavedFiles(
                        downloadedFiles = listOf(directImage),
                        downloadId = downloadId,
                        notificationId = notificationId,
                        url = url,
                        title = currentTitle,
                        quality = quality,
                        timestamp = timestamp
                    )
                }
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

            val cookieFile = prepareCookieFile(tempDir)
            val downloadPlaylist = HistoryStorage.isDownloadPlaylistEnabled(applicationContext)

            // Arka planda bloklamadan hızlı sayfa başlığını (og:title / <title>) çek
            val titleJob = CoroutineScope(Dispatchers.IO).launch {
                val fastTitle = fetchLightweightPageTitle(url)
                if (!fastTitle.isNullOrBlank() && currentTitle.startsWith("Medya")) {
                    currentTitle = fastTitle
                    val currentRec = HistoryStorage.getById(applicationContext, downloadId)
                    if (currentRec != null && currentRec.status == "downloading") {
                        val updated = currentRec.copy(title = currentTitle)
                        HistoryStorage.upsert(applicationContext, updated)
                        ProgressStreamHandler.emitRecord(updated)
                    }
                }
            }

            var downloadedFiles = emptyList<File>()
            var ytdlpError: Exception? = null

            val progressCallback: (Float, Long, String) -> Unit = { progress, etaInSeconds, line ->
                parseTitleFromYtDlpLine(line)?.let { parsedTitle ->
                    if (parsedTitle.isNotBlank()) {
                        currentTitle = parsedTitle
                    }
                }

                val safeProgress = progress.coerceIn(0f, 100f)
                val progressInt = safeProgress.toInt()

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

                val etaText = if (etaInSeconds > 0) " • ${etaInSeconds}sn kaldı" else ""
                val statusText = if (progressInt > 0) "%$progressInt$etaText" else "Video hazırlanıyor..."
                updateForegroundSafe(
                    notificationId = notificationId,
                    downloadId = downloadId,
                    title = currentTitle,
                    contentText = statusText,
                    progress = progressInt,
                    indeterminate = progressInt <= 0
                )
            }

            try {
                val primaryRequest = buildDownloadRequest(
                    url = url,
                    tempDir = tempDir,
                    quality = quality,
                    downloadPlaylist = downloadPlaylist,
                    cookieFile = cookieFile,
                    useFastYoutubeClient = isYoutubeUrl(url),
                    useFallbackFormat = false
                )
                YoutubeDL.getInstance().execute(primaryRequest, downloadId, progressCallback)
                downloadedFiles = collectDownloadedFiles(tempDir, preferVideoOrAudio = true)
            } catch (e: YoutubeDL.CanceledException) {
                titleJob.cancel()
                throw e
            } catch (firstEx: Exception) {
                ytdlpError = firstEx
                downloadedFiles = collectDownloadedFiles(tempDir, preferVideoOrAudio = true)

                // Eğer YouTube veya video indirmesi ilk denemede başarısız olduysa, yedek istemci/format ile anında tekrar dene
                if (downloadedFiles.isEmpty() && shouldRetryYtDlpWithFallback(url, firstEx)) {
                    Log.w(TAG, "Birincil yt-dlp denemesi basarisiz (${firstEx.message}), yedek profil deneniyor...")
                    cleanPartialFiles(tempDir)

                    if (!hasTriedEngineAutoUpdate && isLikelyOutdatedExtractorError(firstEx)) {
                        hasTriedEngineAutoUpdate = true
                        try {
                            updateForegroundSafe(
                                notificationId = notificationId,
                                downloadId = downloadId,
                                title = currentTitle,
                                contentText = "Motor güncelleniyor...",
                                progress = 0,
                                indeterminate = true
                            )
                            YoutubeDL.getInstance().updateYoutubeDL(
                                applicationContext,
                                YoutubeDL.UpdateChannel._NIGHTLY
                            )
                        } catch (_: Exception) {
                        }
                    }

                    try {
                        val fallbackRequest = buildDownloadRequest(
                            url = url,
                            tempDir = tempDir,
                            quality = quality,
                            downloadPlaylist = downloadPlaylist,
                            cookieFile = cookieFile,
                            useFastYoutubeClient = false,
                            useFallbackFormat = true
                        )
                        YoutubeDL.getInstance().execute(fallbackRequest, downloadId, progressCallback)
                        downloadedFiles = collectDownloadedFiles(tempDir, preferVideoOrAudio = true)
                        if (downloadedFiles.isNotEmpty()) {
                            ytdlpError = null
                        }
                    } catch (e: YoutubeDL.CanceledException) {
                        titleJob.cancel()
                        throw e
                    } catch (secondEx: Exception) {
                        ytdlpError = secondEx
                        downloadedFiles = collectDownloadedFiles(tempDir, preferVideoOrAudio = true)
                    }
                }
            } finally {
                titleJob.cancel()
                cookieFile?.delete()
            }

            // Eğer yt-dlp dosya üretemediyse:
            // KURAL: Linkte video varsa ASLA kapak görseli (og:image) indirme!
            // Yalnızca linkte video yoksa (sadece görsel paylaşımı ise) görsel indir.
            if (downloadedFiles.isEmpty()) {
                if (isVideoOnlyPlatformUrl(url)) {
                    throw ytdlpError ?: IllegalStateException("Video indirilemedi.")
                }

                val fallbackFiles = trySmartMediaFallback(
                    urlString = url,
                    tempDir = tempDir,
                    title = currentTitle,
                    ytdlpError = ytdlpError,
                    allowImageFallback = quality != "audio"
                )
                if (fallbackFiles.isNotEmpty()) {
                    downloadedFiles = fallbackFiles
                } else if (ytdlpError != null) {
                    throw ytdlpError
                } else {
                    throw IllegalStateException("İndirilebilir video veya görsel bulunamadı.")
                }
            }

            return@withContext finalizeSavedFiles(
                downloadedFiles = downloadedFiles,
                downloadId = downloadId,
                notificationId = notificationId,
                url = url,
                title = currentTitle,
                quality = quality,
                timestamp = timestamp
            )
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

    private fun finalizeSavedFiles(
        downloadedFiles: List<File>,
        downloadId: String,
        notificationId: Int,
        url: String,
        title: String,
        quality: String,
        timestamp: Long
    ): Result {
        var lastSaved: SavedMediaResult? = null
        for ((index, file) in downloadedFiles.withIndex()) {
            val itemTitle = if (downloadedFiles.size > 1) {
                "${title}_${index + 1}"
            } else {
                title
            }
            lastSaved = MediaStoreSaver.saveFileToGallery(
                context = applicationContext,
                sourceFile = file,
                preferredTitle = itemTitle
            )
        }

        val savedResult = lastSaved
            ?: throw IllegalStateException("Medya galeriye kaydedilemedi.")

        val completedRecord = DownloadRecord(
            id = downloadId,
            url = url,
            title = title,
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
            title = title,
            mediaUri = savedResult.uriString,
            mimeType = savedResult.mimeType
        )

        return Result.success()
    }

    private fun buildDownloadRequest(
        url: String,
        tempDir: File,
        quality: String,
        downloadPlaylist: Boolean,
        cookieFile: File?,
        useFastYoutubeClient: Boolean,
        useFallbackFormat: Boolean
    ): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--no-part")
            addOption("--no-write-thumbnail")
            addOption("--socket-timeout", "20")
            addOption("--retries", "2")
            addOption("--concurrent-fragments", "4")
            if (!downloadPlaylist) {
                addOption("--no-playlist")
            } else {
                addOption("--yes-playlist")
            }
            if (cookieFile != null && cookieFile.exists()) {
                addOption("--cookies", cookieFile.absolutePath)
            }
            if (useFastYoutubeClient) {
                // visionos istemcisi 2.5MB player JS indirme + QuickJS gecikmesini ve 6sn reklam beklemesini atlar
                addOption("--extractor-args", "youtube:player_client=visionos")
            }
            when (quality) {
                "audio" -> {
                    addOption("-f", "ba/b")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                }
                "720p" -> {
                    if (useFallbackFormat) {
                        addOption("-f", "b[height<=720]/bv*[height<=720]+ba/b")
                    } else {
                        addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
                        addOption("-S", "proto:https,res:720,vcodec:h264,ext:mp4:m4a")
                    }
                    addOption("--merge-output-format", "mp4")
                }
                else -> {
                    if (useFallbackFormat) {
                        addOption("-f", "b/bv*+ba")
                    } else {
                        addOption("-f", "bv*+ba/b")
                        addOption("-S", "proto:https,res,vcodec:h264,ext:mp4:m4a")
                    }
                    addOption("--merge-output-format", "mp4")
                }
            }
            addOption("-o", File(tempDir, "%(title).70B_%(id)s.%(ext)s").absolutePath)
        }
    }

    private fun parseTitleFromYtDlpLine(line: String): String? {
        val trimmed = line.trim()
        val destPath = when {
            trimmed.startsWith("[download] Destination:") ->
                trimmed.removePrefix("[download] Destination:").trim()
            trimmed.startsWith("[Merger] Merging formats into") ->
                trimmed.removePrefix("[Merger] Merging formats into").trim().trim('"')
            else -> return null
        }
        val fileName = File(destPath).nameWithoutExtension
            .replace(Regex("\\.f\\d+$"), "") // .f137 veya .f140 ara akış uzantısını temizle
        val candidate = fileName.substringBeforeLast('_', fileName).trim()
        return candidate.takeIf { it.length >= 2 }
    }

    private fun fetchLightweightPageTitle(urlString: String): String? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
            }
            conn.connect()
            val type = conn.contentType?.lowercase(Locale.ROOT).orEmpty()
            if (!type.contains("text/html")) return null

            val chunk = CharArray(32 * 1024)
            val html = conn.inputStream.bufferedReader().use { reader ->
                val read = reader.read(chunk)
                if (read > 0) String(chunk, 0, read) else ""
            }
            val ogTitle = Regex(
                """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
                ?: Regex(
                    """<title[^>]*>([^<]+)</title>""",
                    RegexOption.IGNORE_CASE
                ).find(html)?.groupValues?.getOrNull(1)

            ogTitle
                ?.replace("&#39;", "'")
                ?.replace("&quot;", "\"")
                ?.replace("&amp;", "&")
                ?.replace(Regex("\\s*-\\s*YouTube\\s*$", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
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

    private fun collectDownloadedFiles(tempDir: File, preferVideoOrAudio: Boolean): List<File> {
        if (!tempDir.exists()) return emptyList()
        val allFiles = tempDir.walkTopDown()
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

        if (!preferVideoOrAudio || allFiles.size <= 1) {
            return allFiles
        }

        // Eğer klasörde hem video/ses hem de kapak görseli varsa, yalnızca video/ses dosyalarını al
        val videoOrAudio = allFiles.filter { file ->
            val type = MediaStoreSaver.resolveMediaType(file.extension)
            type == "video" || type == "audio"
        }
        return if (videoOrAudio.isNotEmpty()) videoOrAudio else allFiles
    }

    private fun cleanPartialFiles(tempDir: File) {
        if (!tempDir.exists()) return
        tempDir.walkTopDown()
            .filter { it.isFile && it.name != "cookies.txt" }
            .forEach {
                try {
                    it.delete()
                } catch (_: Exception) {
                }
            }
    }

    private fun isDirectImageUrl(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        val ext = cleanPath.substringAfterLast('.', "")
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")) {
            return true
        }
        val host = try {
            URL(url).host.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            ""
        }
        return host == "pbs.twimg.com" && cleanPath.contains("/media/") ||
            host == "i.redd.it" ||
            host == "i.imgur.com" && !cleanPath.endsWith(".mp4") && !cleanPath.endsWith(".gifv")
    }

    private fun isYoutubeUrl(url: String): Boolean {
        val host = try {
            URL(url).host.lowercase(Locale.ROOT).removePrefix("www.")
        } catch (_: Exception) {
            return false
        }
        return host == "youtube.com" ||
            host == "m.youtube.com" ||
            host == "music.youtube.com" ||
            host == "youtu.be"
    }

    /**
     * Kesin olarak bir video platformu / video akışı olan bağlantıları tespit eder.
     * Bu bağlantılarda og:image her zaman videonun kapak fotoğrafıdır (thumbnail) ve ASLA görsel olarak indirilmemelidir.
     */
    private fun isVideoOnlyPlatformUrl(url: String): Boolean {
        val parsed = try {
            URL(url)
        } catch (_: Exception) {
            return false
        }
        val host = parsed.host.lowercase(Locale.ROOT).removePrefix("www.")
        val path = parsed.path.lowercase(Locale.ROOT)

        // YouTube (Topluluk/post görselleri hariç tüm YouTube linkleri videodur)
        if (host == "youtu.be" || host == "music.youtube.com") return true
        if (host == "youtube.com" || host == "m.youtube.com") {
            return !path.startsWith("/post/") && !path.contains("/community")
        }

        // Diğer saf video platformları ve video path'leri
        if (host in setOf(
                "vimeo.com",
                "player.vimeo.com",
                "dailymotion.com",
                "dai.ly",
                "twitch.tv",
                "clips.twitch.tv",
                "kick.com",
                "streamable.com",
                "v.redd.it",
                "fb.watch"
            )
        ) {
            return true
        }

        if ((host == "instagram.com" || host == "m.instagram.com") &&
            (path.startsWith("/reel/") || path.startsWith("/reels/") || path.startsWith("/tv/"))
        ) {
            return true
        }

        if ((host.endsWith("tiktok.com")) && path.contains("/video/")) {
            return true
        }

        return false
    }

    private fun shouldRetryYtDlpWithFallback(url: String, error: Exception): Boolean {
        if (isYoutubeUrl(url) || isVideoOnlyPlatformUrl(url)) return true
        val msg = (error.message ?: "").lowercase(Locale.ROOT)
        // Eğer hata "no video formats found" veya "unsupported url" ise bu muhtemelen bir görsel linkidir, yt-dlp tekrar denemeye gerek yok
        if (msg.contains("no video formats found") || msg.contains("unsupported url")) {
            return false
        }
        return true
    }

    private fun isLikelyOutdatedExtractorError(error: Exception): Boolean {
        val msg = (error.message ?: "").lowercase(Locale.ROOT)
        return msg.contains("http error 403") ||
            msg.contains("unable to extract") ||
            msg.contains("nsig") ||
            msg.contains("signature") ||
            msg.contains("requested format is not available")
    }

    /**
     * Akıllı Medya Yedek İndirici:
     * 1. Önce bağlantıda video olup olmadığını kontrol eder (X/Twitter syndication, direkt video akışı, og:video).
     *    Video varsa VİDEO indirir.
     * 2. Yalnızca bağlantıda video YOKSA (sadece görsel paylaşımı ise) görseli indirir.
     * 3. Sayfa bir video sayfasıysa (og:type=video* veya video hatası varsa) ASLA kapak görselini (og:image) indirmez.
     */
    private fun trySmartMediaFallback(
        urlString: String,
        tempDir: File,
        title: String,
        ytdlpError: Exception?,
        allowImageFallback: Boolean
    ): List<File> {
        // 1. X / Twitter özel çözümleyici (Tweet içinde video varsa videoyu, sadece fotoğraf varsa fotoğrafları indirir)
        val tweetMedia = tryResolveTwitterMedia(urlString, tempDir, title, allowImageFallback)
        if (tweetMedia.isNotEmpty()) {
            return tweetMedia
        }

        // Eğer yt-dlp "video bulundu ama 403/giriş hatası yüzünden indirilemedi" diyorsa kapak fotoğrafını indirme
        val errMsg = (ytdlpError?.message ?: "").lowercase(Locale.ROOT)
        val ytdlpFoundVideoButFailed = errMsg.isNotBlank() &&
            !errMsg.contains("no video formats found") &&
            !errMsg.contains("unsupported url") &&
            !errMsg.contains("not a valid url") &&
            (errMsg.contains("unable to download video data") ||
                errMsg.contains("http error 403") ||
                errMsg.contains("sign in to confirm") ||
                errMsg.contains("video unavailable"))

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
            val contentType = connection.contentType?.lowercase(Locale.ROOT).orEmpty()

            // Doğrudan video veya ses akışı
            if (contentType.startsWith("video/") || contentType.startsWith("audio/")) {
                val ext = guessExtensionFromMimeOrUrl(contentType, urlString)
                val outFile = File(tempDir, "direct_${System.currentTimeMillis()}.$ext")
                connection.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return listOf(outFile)
            }

            // Doğrudan görsel dosyası
            if (allowImageFallback && contentType.startsWith("image/")) {
                val ext = guessExtensionFromMimeOrUrl(contentType, urlString)
                val outFile = File(tempDir, "direct_${System.currentTimeMillis()}.$ext")
                connection.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return listOf(outFile)
            }

            if (contentType.contains("text/html")) {
                val html = connection.inputStream.bufferedReader().use { it.readText() }

                val ogType = extractMetaContent(html, "og:type")?.lowercase(Locale.ROOT).orEmpty()
                val isHtmlMarkedAsVideo = ogType.startsWith("video") ||
                    html.contains("\"VideoObject\"") ||
                    ytdlpFoundVideoButFailed

                // Sayfada doğrudan video URL'si (og:video / contentUrl) var mı kontrol et
                val ogVideoCandidates = listOfNotNull(
                    extractMetaContent(html, "og:video:secure_url"),
                    extractMetaContent(html, "og:video:url"),
                    extractMetaContent(html, "og:video"),
                    Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)
                ).map { it.replace("&amp;", "&") }

                for (videoUrl in ogVideoCandidates) {
                    if (videoUrl.startsWith("http")) {
                        // Sadece gerçek video akışı ise indir (HTML embed sayfalarını .mp4 diye kaydetme!)
                        val videoFile = tryDownloadDirectMediaStream(
                            targetUrl = videoUrl,
                            tempDir = tempDir,
                            title = title,
                            allowImage = false
                        )
                        if (videoFile != null) {
                            return listOf(videoFile)
                        }
                    }
                }

                // Eğer sayfada video olduğu belirlendiyse (ancak indirilemediyse) ASLA kapak görselini (og:image) indirme!
                if (isHtmlMarkedAsVideo || !allowImageFallback) {
                    return emptyList()
                }

                // Sayfada video YOKSA (sadece görsel içeren bir bağlantıysa) görseli indir
                val ogImage = (extractMetaContent(html, "og:image:secure_url")
                    ?: extractMetaContent(html, "og:image:url")
                    ?: extractMetaContent(html, "og:image"))
                    ?.replace("&amp;", "&")

                if (!ogImage.isNullOrBlank() && ogImage.startsWith("http")) {
                    val imageFile = tryDownloadDirectMediaStream(
                        targetUrl = ogImage,
                        tempDir = tempDir,
                        title = title,
                        allowImage = true
                    )
                    if (imageFile != null) {
                        return listOf(imageFile)
                    }
                }
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * X / Twitter paylaşımlarında:
     * - Tweet içinde video/gif varsa en yüksek kaliteli MP4 videoyu indirir.
     * - Tweet içinde sadece fotoğraf varsa fotoğrafları indirir (video varsa kapak fotoğrafını indirmez).
     */
    private fun tryResolveTwitterMedia(
        urlString: String,
        tempDir: File,
        title: String,
        allowImageFallback: Boolean
    ): List<File> {
        val tweetId = Regex("""(?:twitter\.com|x\.com)/[^/]+/status/(\d+)""", RegexOption.IGNORE_CASE)
            .find(urlString)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        return try {
            val apiUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=a"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return emptyList()

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val mediaDetails = root.optJSONArray("mediaDetails") ?: return emptyList()

            val videoUrls = mutableListOf<String>()
            val photoUrls = mutableListOf<String>()

            for (i in 0 until mediaDetails.length()) {
                val media = mediaDetails.optJSONObject(i) ?: continue
                when (media.optString("type")) {
                    "video", "animated_gif" -> {
                        val variants = media.optJSONObject("video_info")?.optJSONArray("variants")
                        var bestBitrate = -1
                        var bestUrl: String? = null
                        if (variants != null) {
                            for (j in 0 until variants.length()) {
                                val v = variants.optJSONObject(j) ?: continue
                                if (v.optString("content_type") == "video/mp4") {
                                    val bitrate = v.optInt("bitrate", 0)
                                    val u = v.optString("url")
                                    if (u.isNotBlank() && bitrate >= bestBitrate) {
                                        bestBitrate = bitrate
                                        bestUrl = u
                                    }
                                }
                            }
                        }
                        if (!bestUrl.isNullOrBlank()) {
                            videoUrls.add(bestUrl)
                        }
                    }
                    "photo" -> {
                        val photoUrl = media.optString("media_url_https")
                        if (photoUrl.isNotBlank()) {
                            photoUrls.add("$photoUrl?name=orig")
                        }
                    }
                }
            }

            // KURAL: Tweet'te video varsa SADECE videoyu indir; video yoksa fotoğrafı indir
            val targetUrls = if (videoUrls.isNotEmpty()) {
                videoUrls to false
            } else if (allowImageFallback && photoUrls.isNotEmpty()) {
                photoUrls to true
            } else {
                return emptyList()
            }

            val results = mutableListOf<File>()
            for ((idx, mediaUrl) in targetUrls.first.withIndex()) {
                val downloaded = tryDownloadDirectMediaStream(
                    targetUrl = mediaUrl,
                    tempDir = tempDir,
                    title = "${title}_${idx + 1}",
                    allowImage = targetUrls.second
                )
                if (downloaded != null) {
                    results.add(downloaded)
                }
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryDownloadDirectMediaStream(
        targetUrl: String,
        tempDir: File,
        title: String,
        allowImage: Boolean
    ): File? {
        return try {
            val mediaConn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
            }
            mediaConn.connect()
            if (mediaConn.responseCode !in 200..299) return null

            val mediaMime = mediaConn.contentType?.lowercase(Locale.ROOT).orEmpty()
            // HTML sayfasını (ör. youtube.com/embed/...) medya dosyası sanıp indirmeyi kesin olarak engelle
            if (mediaMime.contains("text/html") || mediaMime.contains("application/xhtml")) {
                return null
            }

            val isVideoOrAudio = mediaMime.startsWith("video/") || mediaMime.startsWith("audio/")
            val isImage = mediaMime.startsWith("image/")

            if (!isVideoOrAudio && !(allowImage && isImage)) {
                return null
            }

            val ext = guessExtensionFromMimeOrUrl(mediaMime, targetUrl)
            val safePrefix = title.replace(Regex("[^a-zA-Z0-9ğüşöçıİĞÜŞÖÇ_-]"), "_").take(30)
            val outFile = File(tempDir, "${safePrefix}_${System.currentTimeMillis()}.$ext")
            mediaConn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractMetaContent(html: String, property: String): String? {
        val escaped = Regex.escape(property)
        return Regex(
            """<meta[^>]+(?:property|name)=["']$escaped["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
            ?: Regex(
                """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$escaped["']""",
                RegexOption.IGNORE_CASE
            ).find(html)?.groupValues?.getOrNull(1)
    }

    private fun guessExtensionFromMimeOrUrl(contentType: String, url: String): String {
        return when {
            contentType.contains("png") -> "png"
            contentType.contains("webp") -> "webp"
            contentType.contains("gif") -> "gif"
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("mp4") -> "mp4"
            contentType.contains("webm") -> "webm"
            contentType.contains("mpeg") || contentType.contains("mp3") -> "mp3"
            else -> {
                val cleanPath = url.substringBefore('?').substringAfterLast('.', "")
                    .lowercase(Locale.ROOT)
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
        val lower = msg.lowercase(Locale.ROOT)
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
        val lower = msg.lowercase(Locale.ROOT)
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

        @Volatile
        private var hasTriedEngineAutoUpdate = false

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
            val cleanUrl = url.trim()

            val initialMediaType = when {
                resolvedQuality == "audio" -> "audio"
                cleanUrl.substringBefore('?').lowercase(Locale.ROOT).substringAfterLast('.', "") in
                    setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif") -> "image"
                else -> "video"
            }

            val initialRecord = DownloadRecord(
                id = downloadId,
                url = cleanUrl,
                title = try {
                    "Medya (${URL(cleanUrl).host.removePrefix("www.")})"
                } catch (_: Exception) {
                    "Medya"
                },
                status = "queued",
                progress = 0f,
                etaInSeconds = -1L,
                quality = resolvedQuality,
                mediaType = initialMediaType,
                filePath = null,
                mediaUri = null,
                mimeType = null,
                errorMessage = null,
                timestamp = timestamp
            )
            HistoryStorage.upsert(appContext, initialRecord)
            ProgressStreamHandler.emitRecord(initialRecord)

            val inputData = Data.Builder()
                .putString(KEY_URL, cleanUrl)
                .putString(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_QUALITY, resolvedQuality)
                .putLong(KEY_TIMESTAMP, timestamp)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
