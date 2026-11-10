package com.ismail.medya_indirici

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.yausername.youtubedl_android.YoutubeDL
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FlutterActivity() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL_NAME
        ).setStreamHandler(ProgressStreamHandler)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL_NAME
        ).setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startDownload" -> {
                val rawText = call.argument<String>("url")?.trim().orEmpty()
                val quality = call.argument<String>("quality")
                    ?: HistoryStorage.getQuality(applicationContext)
                val force = call.argument<Boolean>("force") ?: false

                val extractedUrl = ShareReceiverActivity.extractFirstHttpUrl(rawText)
                if (extractedUrl == null) {
                    result.success(
                        mapOf(
                            "status" to "invalid_url",
                            "message" to "Geçerli bir http/https bağlantısı bulunamadı."
                        )
                    )
                    return
                }

                if (HistoryStorage.isUrlCurrentlyActive(applicationContext, extractedUrl)) {
                    result.success(
                        mapOf(
                            "status" to "already_active",
                            "message" to "Bu bağlantı şu anda zaten indiriliyor."
                        )
                    )
                    return
                }

                if (!force &&
                    HistoryStorage.isCheckDuplicatesEnabled(applicationContext) &&
                    HistoryStorage.isUrlCompleted(applicationContext, extractedUrl)
                ) {
                    result.success(
                        mapOf(
                            "status" to "duplicate",
                            "url" to extractedUrl,
                            "message" to "Bu bağlantı daha önce indirildi. Tekrar indirmek istiyor musunuz?"
                        )
                    )
                    return
                }

                val downloadId = DownloadWorker.enqueue(
                    context = applicationContext,
                    url = extractedUrl,
                    quality = quality
                )
                result.success(
                    mapOf(
                        "status" to "enqueued",
                        "downloadId" to downloadId,
                        "url" to extractedUrl
                    )
                )
            }

            "cancelDownload" -> {
                val downloadId = call.argument<String>("id").orEmpty()
                if (downloadId.isNotBlank()) {
                    try {
                        YoutubeDL.getInstance().destroyProcessById(downloadId)
                    } catch (_: Exception) {
                    }
                    WorkManager.getInstance(applicationContext)
                        .cancelUniqueWork("download_$downloadId")

                    val existing = HistoryStorage.getById(applicationContext, downloadId)
                    if (existing != null) {
                        val cancelled = existing.copy(
                            status = "cancelled",
                            progress = 0f,
                            etaInSeconds = -1L,
                            errorMessage = "İndirme iptal edildi"
                        )
                        HistoryStorage.upsert(applicationContext, cancelled)
                        ProgressStreamHandler.emitRecord(cancelled)
                    }
                }
                result.success(true)
            }

            "retryDownload" -> {
                val downloadId = call.argument<String>("id").orEmpty()
                val existing = HistoryStorage.getById(applicationContext, downloadId)
                if (existing != null) {
                    DownloadWorker.enqueue(
                        context = applicationContext,
                        url = existing.url,
                        quality = existing.quality,
                        existingDownloadId = existing.id
                    )
                    result.success(true)
                } else {
                    result.success(false)
                }
            }

            "getHistory" -> {
                val list = HistoryStorage.getAll(applicationContext).map { it.toMap() }
                result.success(list)
            }

            "deleteHistoryItem" -> {
                val id = call.argument<String>("id").orEmpty()
                if (id.isNotBlank()) {
                    HistoryStorage.deleteById(applicationContext, id)
                }
                result.success(true)
            }

            "clearHistory" -> {
                HistoryStorage.clearNonActive(applicationContext)
                result.success(true)
            }

            "getSettings" -> {
                result.success(HistoryStorage.getSettingsMap(applicationContext))
            }

            "saveSettings" -> {
                val map = call.arguments as? Map<String, Any?> ?: emptyMap()
                HistoryStorage.saveSettings(applicationContext, map)
                result.success(HistoryStorage.getSettingsMap(applicationContext))
            }

            "getYtDlpVersion" -> {
                mainScope.launch {
                    val version = withContext(Dispatchers.IO) {
                        App.ensureInitialized(applicationContext)
                        try {
                            YoutubeDL.getInstance().version(applicationContext) ?: "Bilinmiyor"
                        } catch (_: Exception) {
                            "Hazır"
                        }
                    }
                    result.success(version)
                }
            }

            "updateYtDlp" -> {
                val channelName = call.argument<String>("channel")
                    ?: HistoryStorage.getYtDlpChannel(applicationContext)
                mainScope.launch {
                    val response = withContext(Dispatchers.IO) {
                        val initRes = App.ensureInitialized(applicationContext)
                        if (initRes.isFailure) {
                            return@withContext mapOf(
                                "status" to "ERROR",
                                "message" to (initRes.exceptionOrNull()?.message ?: "Motor başlatılamadı")
                            )
                        }
                        try {
                            val updateChannel = if (channelName.equals("NIGHTLY", ignoreCase = true)) {
                                YoutubeDL.UpdateChannel._NIGHTLY
                            } else {
                                YoutubeDL.UpdateChannel._STABLE
                            }
                            val status = YoutubeDL.getInstance()
                                .updateYoutubeDL(applicationContext, updateChannel)
                            val newVersion = YoutubeDL.getInstance().version(applicationContext) ?: ""
                            mapOf(
                                "status" to (status?.name ?: "DONE"),
                                "version" to newVersion,
                                "message" to when (status) {
                                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp başarıyla güncellendi ($newVersion)"
                                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp zaten güncel ($newVersion)"
                                    else -> "Güncelleme tamamlandı"
                                }
                            )
                        } catch (e: Exception) {
                            mapOf(
                                "status" to "ERROR",
                                "message" to (e.message ?: "yt-dlp güncellenirken hata oluştu")
                            )
                        }
                    }
                    result.success(response)
                }
            }

            "openMedia" -> {
                val mediaUri = call.argument<String>("mediaUri")
                val mimeType = call.argument<String>("mimeType") ?: "*/*"
                if (mediaUri.isNullOrBlank()) {
                    result.success(false)
                    return
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(mediaUri), mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    result.success(true)
                } catch (_: Exception) {
                    result.success(false)
                }
            }

            "getSystemStatus" -> {
                result.success(
                    mapOf(
                        "notificationsGranted" to NotificationHelper.canPostNotifications(applicationContext),
                        "batteryOptimizationIgnored" to isIgnoringBatteryOptimizations()
                    )
                )
            }

            "requestNotificationPermission" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        result.success(true)
                    } else {
                        pendingPermissionResult = result
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQ_CODE_NOTIFICATIONS
                        )
                    }
                } else {
                    result.success(true)
                }
            }

            "requestIgnoreBatteryOptimizations" -> {
                try {
                    if (!isIgnoringBatteryOptimizations()) {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    result.success(true)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        result.success(true)
                    } catch (_: Exception) {
                        result.success(false)
                    }
                }
            }

            else -> result.notImplemented()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermissionResult?.success(granted)
            pendingPermissionResult = null
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val METHOD_CHANNEL_NAME = "com.ismail.medya_indirici/commands"
        const val EVENT_CHANNEL_NAME = "com.ismail.medya_indirici/progress"
        private const val REQ_CODE_NOTIFICATIONS = 1001
    }
}
