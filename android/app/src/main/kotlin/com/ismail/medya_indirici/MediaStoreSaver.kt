package com.ismail.medya_indirici

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

data class SavedMediaResult(
    val uriString: String,
    val displayPath: String,
    val mimeType: String,
    val mediaType: String
)

object MediaStoreSaver {

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
    )
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "opus", "ogg", "wav", "flac", "wma"
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "webm", "mov", "3gp", "flv", "avi", "m4v", "ts"
    )

    fun saveFileToGallery(context: Context, sourceFile: File, preferredTitle: String?): SavedMediaResult {
        require(sourceFile.exists() && sourceFile.length() > 0L) {
            "Kaydedilecek medya dosyası bulunamadı veya boş."
        }

        val ext = sourceFile.extension.lowercase(Locale.ROOT).ifBlank { "mp4" }
        val mediaType = resolveMediaType(ext)
        val mimeType = resolveMimeType(ext, mediaType)
        val sanitizedBaseName = sanitizeFileName(preferredTitle ?: sourceFile.nameWithoutExtension)
            .ifBlank { "medya_${System.currentTimeMillis()}" }
        val finalFileName = "${sanitizedBaseName}_${System.currentTimeMillis() % 10000}.$ext"

        val relativeSubDir = when (mediaType) {
            "image" -> "${Environment.DIRECTORY_PICTURES}/MedyaIndirici"
            "audio" -> "${Environment.DIRECTORY_MUSIC}/MedyaIndirici"
            else -> "${Environment.DIRECTORY_MOVIES}/MedyaIndirici"
        }

        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collectionUri: Uri = when (mediaType) {
                "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSubDir)
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(collectionUri, values)
                ?: throw IllegalStateException("MediaStore kaydı oluşturulamadı.")

            try {
                resolver.openOutputStream(itemUri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                    }
                } ?: throw IllegalStateException("MediaStore yazma akışı açılamadı.")

                val completeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, completeValues, null, null)
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            } finally {
                sourceFile.delete()
            }

            return SavedMediaResult(
                uriString = itemUri.toString(),
                displayPath = "$relativeSubDir/$finalFileName",
                mimeType = mimeType,
                mediaType = mediaType
            )
        } else {
            val publicRootType = when (mediaType) {
                "image" -> Environment.DIRECTORY_PICTURES
                "audio" -> Environment.DIRECTORY_MUSIC
                else -> Environment.DIRECTORY_MOVIES
            }
            val destDir = File(
                Environment.getExternalStoragePublicDirectory(publicRootType),
                "MedyaIndirici"
            )
            if (!destDir.exists()) {
                destDir.mkdirs()
            }
            val destFile = File(destDir, finalFileName)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            sourceFile.delete()

            val collectionUri: Uri = when (mediaType) {
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                put(MediaStore.MediaColumns.SIZE, destFile.length())
            }
            val insertedUri = resolver.insert(collectionUri, values)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType),
                null
            )

            return SavedMediaResult(
                uriString = (insertedUri ?: Uri.fromFile(destFile)).toString(),
                displayPath = destFile.absolutePath,
                mimeType = mimeType,
                mediaType = mediaType
            )
        }
    }

    fun resolveMediaType(ext: String): String {
        val clean = ext.lowercase(Locale.ROOT)
        return when {
            imageExtensions.contains(clean) -> "image"
            audioExtensions.contains(clean) -> "audio"
            videoExtensions.contains(clean) -> "video"
            else -> "video"
        }
    }

    private fun resolveMimeType(ext: String, mediaType: String): String {
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))
        if (!fromMap.isNullOrBlank()) return fromMap
        return when (mediaType) {
            "image" -> "image/jpeg"
            "audio" -> "audio/mpeg"
            else -> "video/mp4"
        }
    }

    private fun sanitizeFileName(raw: String): String {
        return raw
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
    }
}

