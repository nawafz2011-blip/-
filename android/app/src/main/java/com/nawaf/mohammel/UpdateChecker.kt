package com.nawaf.mohammel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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

    /** يحمّل ملف التحديث داخل التطبيق، مع النسبة (0 إلى 100). */
    suspend fun download(ctx: Context, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Mohammel.apk")
        var conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        // GitHub يحوّل الرابط لسيرفر الملفات: نتبع التحويل
        repeat(5) {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: error("redirect")
                conn.disconnect()
                conn = URL(URL(conn.url, next).toString()).openConnection() as HttpURLConnection
            } else return@repeat
        }
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last) {
                            last = p
                            withContext(Dispatchers.Main) { onProgress(p) }
                        }
                    }
                }
            }
        }
        conn.disconnect()
        out
    }

    /** أندرويد يطلب إذن "تثبيت تطبيقات من هذا المصدر" أول مرة بس. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun permissionIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))

    /** يفتح شاشة التثبيت على طول. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
