package com.ismail.medya_indirici

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object ProgressStreamHandler : EventChannel.StreamHandler {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    fun emitRecord(record: DownloadRecord) {
        val map = record.toMap()
        mainHandler.post {
            try {
                eventSink?.success(map)
            } catch (_: Exception) {
            }
        }
    }
}

