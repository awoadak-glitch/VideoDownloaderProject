package com.awr.downloaderstudio

object MediaDetector {
    fun isMedia(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains(".mpd") ||
                u.contains(".webm") || u.contains("videoplayback") || u.contains("mime=video")
    }

    fun isStream(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains(".mpd")
    }

    fun quality(url: String): String {
        val match = Regex("(2160|1440|1080|720|480|360|240)").find(url)
        return match?.value?.plus("P") ?: "AUTO"
    }

    fun type(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".m3u8") -> "HLS"
            u.contains(".mpd") -> "DASH"
            u.contains(".mp4") -> "MP4"
            u.contains(".webm") -> "WEBM"
            else -> "VIDEO"
        }
    }
}
