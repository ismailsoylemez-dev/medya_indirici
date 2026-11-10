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
                    YoutubeDL.getInstance().init(appContext)
                    FFmpeg.getInstance().init(appContext)
                    isInitialized = true
                    Log.i(TAG, "YoutubeDL ve FFmpeg basariyla baslatildi.")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "YoutubeDL/FFmpeg baslatma hatasi", e)
                    Result.failure(e)
                }
            }
        }
    }
}

