package com.awr.downloaderstudio

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.widget.Toast

class Downloader(private val context: Context, private val onLog: (String) -> Unit) {
    fun download(item: MediaItem) {
        if (MediaDetector.isStream(item.url)) {
            convertHlsPlaceholder(item)
            return
        }

        val filename = cleanName(item.title) + "-" + System.currentTimeMillis() + ext(item.url)
        val req = DownloadManager.Request(Uri.parse(item.url))
            .setTitle(filename)
            .setDescription(item.type + " " + item.quality)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

        val cookie = CookieManager.getInstance().getCookie(item.url)
        if (!cookie.isNullOrBlank()) req.addRequestHeader("Cookie", cookie)
        req.addRequestHeader("User-Agent", "Mozilla/5.0 AWRDownloaderStudio/1.0")
        if (item.page.isNotBlank()) req.addRequestHeader("Referer", item.page)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(req)
        onLog("Started: $filename")
        Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
    }

    private fun convertHlsPlaceholder(item: MediaItem) {
        onLog("HLS/DASH detected: ${item.quality}. FFmpeg local bridge placeholder.")
        Toast.makeText(context, "HLS found. Add FFmpeg bridge to convert locally.", Toast.LENGTH_LONG).show()
    }

    private fun cleanName(s: String): String {
        return s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60).ifBlank { "video" }
    }

    private fun ext(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".webm") -> ".webm"
            else -> ".mp4"
        }
    }
}
