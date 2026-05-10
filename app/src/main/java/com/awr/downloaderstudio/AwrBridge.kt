package com.awr.downloaderstudio

import android.webkit.JavascriptInterface
import org.json.JSONObject

class AwrBridge(private val onFound: (MediaItem) -> Unit) {
    @JavascriptInterface
    fun onMediaFound(json: String) {
        try {
            val obj = JSONObject(json)
            val url = obj.optString("url")
            if (!MediaDetector.isMedia(url)) return
            onFound(
                MediaItem(
                    url = url,
                    title = obj.optString("title", "video"),
                    page = obj.optString("page", ""),
                    source = obj.optString("source", "js"),
                    quality = MediaDetector.quality(url),
                    type = MediaDetector.type(url)
                )
            )
        } catch (_: Exception) {}
    }
}
