package com.nawaf.mohammel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** يشيّك إذا فيه نسخة أحدث من التطبيق في GitHub Releases. */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/nawafz2011-blip/-/releases/latest"
    const val DOWNLOAD_URL = "https://github.com/nawafz2011-blip/-/releases/latest/download/Mohammel.apk"

    suspend fun newerVersionAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val tag = JSONObject(body).optString("tag_name")
            val latest = Regex("""(\d+)$""").find(tag)?.value?.toIntOrNull() ?: return@withContext false
            latest > BuildConfig.VERSION_CODE
        } catch (_: Throwable) {
            false
        }
    }
}
