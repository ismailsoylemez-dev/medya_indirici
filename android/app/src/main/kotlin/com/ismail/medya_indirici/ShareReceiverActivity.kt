package com.ismail.medya_indirici

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            ?: ""

        val extractedUrl = extractFirstHttpUrl(sharedText)
        if (extractedUrl == null) {
            Toast.makeText(applicationContext, "Geçerli link bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }

        if (HistoryStorage.isUrlCurrentlyActive(applicationContext, extractedUrl)) {
            Toast.makeText(
                applicationContext,
                "Bu bağlantı zaten indirme kuyruğunda",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val checkDuplicates = HistoryStorage.isCheckDuplicatesEnabled(applicationContext)
        val alreadyCompleted = checkDuplicates &&
            HistoryStorage.isUrlCompleted(applicationContext, extractedUrl)

        DownloadWorker.enqueue(
            context = applicationContext,
            url = extractedUrl,
            quality = HistoryStorage.getQuality(applicationContext)
        )

        val message = if (alreadyCompleted) {
            "Daha önce indirilmişti • İndirme tekrar başladı"
        } else {
            "İndirme başladı"
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

        fun extractFirstHttpUrl(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val match = URL_REGEX.find(text) ?: return null
            return match.value.trimEnd('.', ',', ';', ')', ']', '!')
        }
    }
}

