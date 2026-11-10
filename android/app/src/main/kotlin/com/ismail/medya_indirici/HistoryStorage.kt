package com.ismail.medya_indirici

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DownloadRecord(
    val id: String,
    val url: String,
    val title: String,
    val status: String,
    val progress: Float,
    val etaInSeconds: Long,
    val quality: String,
    val mediaType: String,
    val filePath: String?,
    val mediaUri: String?,
    val mimeType: String?,
    val errorMessage: String?,
    val timestamp: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("title", title)
        put("status", status)
        put("progress", progress.toDouble())
        put("etaInSeconds", etaInSeconds)
        put("quality", quality)
        put("mediaType", mediaType)
        put("filePath", filePath ?: JSONObject.NULL)
        put("mediaUri", mediaUri ?: JSONObject.NULL)
        put("mimeType", mimeType ?: JSONObject.NULL)
        put("errorMessage", errorMessage ?: JSONObject.NULL)
        put("timestamp", timestamp)
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "url" to url,
        "title" to title,
        "status" to status,
        "progress" to progress.toDouble(),
        "etaInSeconds" to etaInSeconds,
        "quality" to quality,
        "mediaType" to mediaType,
        "filePath" to filePath,
        "mediaUri" to mediaUri,
        "mimeType" to mimeType,
        "errorMessage" to errorMessage,
        "timestamp" to timestamp
    )

    companion object {
        fun fromJson(obj: JSONObject): DownloadRecord {
            return DownloadRecord(
                id = obj.optString("id", ""),
                url = obj.optString("url", ""),
                title = obj.optString("title", "Medya"),
                status = obj.optString("status", "queued"),
                progress = obj.optDouble("progress", 0.0).toFloat(),
                etaInSeconds = obj.optLong("etaInSeconds", -1L),
                quality = obj.optString("quality", "best"),
                mediaType = obj.optString("mediaType", "video"),
                filePath = if (obj.isNull("filePath")) null else obj.optString("filePath"),
                mediaUri = if (obj.isNull("mediaUri")) null else obj.optString("mediaUri"),
                mimeType = if (obj.isNull("mimeType")) null else obj.optString("mimeType"),
                errorMessage = if (obj.isNull("errorMessage")) null else obj.optString("errorMessage"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

object HistoryStorage {
    private const val PREFS_NAME = "medya_indirici_native_store"
    private const val KEY_HISTORY = "download_history_json"
    private const val KEY_QUALITY = "setting_quality"
    private const val KEY_CHECK_DUPLICATES = "setting_check_duplicates"
    private const val KEY_DOWNLOAD_PLAYLIST = "setting_download_playlist"
    private const val KEY_COOKIES = "setting_cookies"
    private const val KEY_YTDLP_CHANNEL = "setting_ytdlp_channel"
    private const val MAX_HISTORY_ITEMS = 300

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(context: Context): List<DownloadRecord> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<DownloadRecord>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                list.add(DownloadRecord.fromJson(item))
            }
            list.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getById(context: Context, id: String): DownloadRecord? {
        return getAll(context).firstOrNull { it.id == id }
    }

    @Synchronized
    fun upsert(context: Context, record: DownloadRecord) {
        val current = getAll(context).toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            current[index] = record
        } else {
            current.add(0, record)
        }
        val trimmed = current.sortedByDescending { it.timestamp }.take(MAX_HISTORY_ITEMS)
        saveList(context, trimmed)
    }

    @Synchronized
    fun deleteById(context: Context, id: String) {
        val filtered = getAll(context).filterNot { it.id == id }
        saveList(context, filtered)
    }

    @Synchronized
    fun clearNonActive(context: Context) {
        val activeOnly = getAll(context).filter {
            it.status == "queued" || it.status == "downloading" || it.status == "retrying"
        }
        saveList(context, activeOnly)
    }

    @Synchronized
    fun isUrlCurrentlyActive(context: Context, url: String): Boolean {
        val normalized = url.trim()
        return getAll(context).any {
            it.url.trim() == normalized &&
                (it.status == "queued" || it.status == "downloading" || it.status == "retrying")
        }
    }

    @Synchronized
    fun isUrlCompleted(context: Context, url: String): Boolean {
        val normalized = url.trim()
        return getAll(context).any {
            it.url.trim() == normalized && it.status == "completed"
        }
    }

    private fun saveList(context: Context, list: List<DownloadRecord>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun getQuality(context: Context): String =
        prefs(context).getString(KEY_QUALITY, "best") ?: "best"

    fun isCheckDuplicatesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_DUPLICATES, true)

    fun isDownloadPlaylistEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOWNLOAD_PLAYLIST, false)

    fun getCookies(context: Context): String =
        prefs(context).getString(KEY_COOKIES, "") ?: ""

    fun getYtDlpChannel(context: Context): String =
        prefs(context).getString(KEY_YTDLP_CHANNEL, "STABLE") ?: "STABLE"

    fun getSettingsMap(context: Context): Map<String, Any> = mapOf(
        "quality" to getQuality(context),
        "checkDuplicates" to isCheckDuplicatesEnabled(context),
        "downloadPlaylist" to isDownloadPlaylistEnabled(context),
        "cookies" to getCookies(context),
        "ytDlpChannel" to getYtDlpChannel(context),
        "videoFolder" to "Movies/MedyaIndirici",
        "imageFolder" to "Pictures/MedyaIndirici",
        "audioFolder" to "Music/MedyaIndirici"
    )

    fun saveSettings(context: Context, map: Map<String, Any?>) {
        val editor = prefs(context).edit()
        (map["quality"] as? String)?.let { editor.putString(KEY_QUALITY, it) }
        (map["checkDuplicates"] as? Boolean)?.let { editor.putBoolean(KEY_CHECK_DUPLICATES, it) }
        (map["downloadPlaylist"] as? Boolean)?.let { editor.putBoolean(KEY_DOWNLOAD_PLAYLIST, it) }
        (map["cookies"] as? String)?.let { editor.putString(KEY_COOKIES, it) }
        (map["ytDlpChannel"] as? String)?.let { editor.putString(KEY_YTDLP_CHANNEL, it) }
        editor.apply()
    }
}

