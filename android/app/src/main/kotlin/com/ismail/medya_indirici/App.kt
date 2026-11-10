package com.ismail.medya_indirici

import android.app.Application
import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class App : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        applicationScope.launch {
            ensureInitialized(this@App)
        }
    }

    companion object {
        private const val TAG = "MedyaIndiriciApp"
        const val BUNDLED_YTDLP_VERSION = "2026.10.26"
        private const val PREF_BUNDLED_SYNCED = "bundled_ytdlp_synced_version"

        private val initMutex = Mutex()
        @Volatile
        var isInitialized = false
            private set

        suspend fun ensureInitialized(context: Context): Result<Unit> {
            if (isInitialized) return Result.success(Unit)
            return initMutex.withLock {
                if (isInitialized) return@withLock Result.success(Unit)
                try {
                    val appContext = context.applicationContext
                    syncBundledYtDlpIfNeeded(appContext)
                    YoutubeDL.getInstance().init(appContext)
                    FFmpeg.getInstance().init(appContext)
                    val ytdlpPrefs = appContext.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                    if (ytdlpPrefs.getString("dlpVersion", null).isNullOrBlank()) {
                        ytdlpPrefs.edit()
                            .putString("dlpVersion", BUNDLED_YTDLP_VERSION)
                            .putString("dlpVersionName", BUNDLED_YTDLP_VERSION)
                            .apply()
                    }
                    isInitialized = true
                    Log.i(TAG, "YoutubeDL ($BUNDLED_YTDLP_VERSION) ve FFmpeg basariyla baslatildi.")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "YoutubeDL/FFmpeg baslatma hatasi", e)
                    Result.failure(e)
                }
            }
        }

        private fun syncBundledYtDlpIfNeeded(appContext: Context) {
            try {
                val prefs = appContext.getSharedPreferences("medya_indirici_engine_prefs", Context.MODE_PRIVATE)
                val syncedVersion = prefs.getString(PREF_BUNDLED_SYNCED, null)
                val ytdlpBinary = File(appContext.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
                if (syncedVersion != BUNDLED_YTDLP_VERSION) {
                    if (ytdlpBinary.exists()) {
                        ytdlpBinary.delete()
                    }
                    appContext.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                        .edit()
                        .putString("dlpVersion", BUNDLED_YTDLP_VERSION)
                        .putString("dlpVersionName", BUNDLED_YTDLP_VERSION)
                        .apply()
                    prefs.edit().putString(PREF_BUNDLED_SYNCED, BUNDLED_YTDLP_VERSION).apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bundled yt-dlp senkronizasyon uyarisi: ${e.message}")
            }
        }
    }
}


