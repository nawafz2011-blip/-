package com.nawaf.mohammel

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** خيار جودة يظهر للمستخدم. id = الارتفاع (مثل "1080") أو "audio" أو "best". */
data class QualityOption(val id: String, val label: String, val detail: String, val isAudio: Boolean)

data class VideoSummary(
    val url: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val options: List<QualityOption>,
)

data class SavedFile(val uri: Uri, val mime: String, val displayName: String)

/**
 * المحرك: yt-dlp و ffmpeg مدمجين داخل التطبيق، يشتغلون على الجوال نفسه بدون أي سيرفر.
 */
object Engine {
    private const val FOLDER = "Mohammel"
    private val ready = CompletableDeferred<Unit>()
    @Volatile var initError: Throwable? = null
        private set

    /** يتنادى مرة وحدة عند تشغيل التطبيق (أول مرة ياخذ ثواني عشان يفك Python). */
    fun init(context: Context) {
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            ready.complete(Unit)
        } catch (t: Throwable) {
            initError = t
            ready.completeExceptionally(t)
        }
    }

    suspend fun awaitReady() = ready.await()

    val isReady: Boolean get() = ready.isCompleted

    /** يحدّث yt-dlp من GitHub (مرة باليوم كحد أقصى) لأن يوتيوب يغيّر موقعه باستمرار. */
    fun updateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_update", 0)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
        } catch (_: Throwable) {
            // نحاول مرة ثانية في التشغيل الجاي
        }
    }

    fun engineVersion(context: Context): String? = YoutubeDL.getInstance().versionName(context)

    private fun qualityLabel(h: Int): String = when {
        h >= 4320 -> "${h}p (8K)"
        h >= 2160 -> "${h}p (4K)"
        h >= 1440 -> "${h}p (2K)"
        h >= 1080 -> "${h}p (Full HD)"
        h >= 720 -> "${h}p (HD)"
        else -> "${h}p"
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) "~%.1f GB".format(mb / 1024) else "~%d MB".format(mb.toInt())
    }

    private fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    suspend fun fetchInfo(url: String): VideoSummary = withContext(Dispatchers.IO) {
        awaitReady()
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
        }
        val info = YoutubeDL.getInstance().getInfo(request)

        val sizes = sortedMapOf<Int, Long>(compareByDescending { it })
        var hasAudio = false
        for (f in info.formats.orEmpty()) {
            if (f.acodec != null && f.acodec != "none") hasAudio = true
            if (f.height <= 0 || f.vcodec == null || f.vcodec == "none") continue
            val size = maxOf(f.fileSize, f.fileSizeApproximate)
            sizes[f.height] = maxOf(sizes[f.height] ?: 0L, size)
        }

        val options = mutableListOf<QualityOption>()
        for ((h, size) in sizes) {
            val parts = mutableListOf("MP4")
            formatSize(size).takeIf { it.isNotEmpty() }?.let { parts += it }
            options += QualityOption(h.toString(), qualityLabel(h), parts.joinToString(" · "), false)
        }
        if (options.isEmpty()) options += QualityOption("best", "أفضل جودة", "MP4", false)
        if (hasAudio || options.isNotEmpty()) options += QualityOption("audio", "صوت فقط", "MP3", true)

        VideoSummary(
            url = url,
            title = info.title ?: info.fulltitle ?: "فيديو",
            subtitle = listOfNotNull(info.uploader, info.extractorKey, formatDuration(info.duration).ifEmpty { null })
                .joinToString(" · "),
            thumbnail = info.thumbnail,
            options = options,
        )
    }

    /**
     * يحمّل الفيديو داخل مجلد مؤقت، وبعدها ينقله للمعرض (Movies/Mohammel) أو للموسيقى (Music/Mohammel).
     */
    fun download(
        context: Context,
        url: String,
        option: QualityOption,
        processId: String,
        onProgress: (Float, Long) -> Unit,
    ): SavedFile {
        val workDir = File(context.cacheDir, "dl/$processId").apply { deleteRecursively(); mkdirs() }
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--newline")
                addOption("-o", "${workDir.absolutePath}/%(title).120B.%(ext)s")
                addOption("--concurrent-fragments", 4)
                if (option.isAudio) {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "192K")
                } else {
                    addOption("-f", "bv*+ba/b")
                    // نفضّل H.264 + AAC لأنها تشتغل على أي جهاز. 4K و8K ما لها H.264 في يوتيوب.
                    val res = option.id.toIntOrNull()
                    addOption("-S", (if (res != null) "res:$res" else "res") + ",vcodec:h264,acodec:aac,ext:mp4:m4a")
                    addOption("--merge-output-format", "mp4")
                }
            }
            YoutubeDL.getInstance().execute(request, processId) { progress, eta, _ ->
                onProgress(progress, eta)
            }

            val file = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("ما لقينا الملف بعد التحميل")
            return saveToGallery(context, file, option.isAudio)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun mimeOf(file: File, isAudio: Boolean): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (isAudio) "audio/mpeg" else "video/mp4"

    private fun saveToGallery(context: Context, file: File, isAudio: Boolean): SavedFile {
        val mime = mimeOf(file, isAudio)
        val name = file.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isAudio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val dir = (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/$FOLDER"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("ما قدرنا نحفظ الملف")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            return SavedFile(uri, mime, name)
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(
                if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            )
            val dir = File(base, FOLDER).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
            return SavedFile(uri, mime, name)
        }
    }

    /** يحوّل أخطاء yt-dlp لرسائل عربية مفهومة. */
    fun friendlyError(t: Throwable): String {
        val msg = (t.message ?: "") + " " + (t.cause?.message ?: "")
        return when {
            "Unsupported URL" in msg -> "هذا الموقع أو الرابط غير مدعوم."
            "Sign in to confirm" in msg || "bot" in msg.lowercase() -> "يوتيوب طلب تحقق. جرّب بعد شوي."
            "Private video" in msg || "private" in msg.lowercase() -> "الفيديو خاص أو محذوف."
            "Unable to resolve host" in msg || "Network is unreachable" in msg || "timed out" in msg ->
                "تأكد إن الإنترنت شغال."
            "No space left" in msg -> "المساحة في الجوال ما تكفي."
            else -> "ما قدرنا نجيب الفيديو. تأكد من الرابط وجرب مرة ثانية."
        }
    }

    /** يطلّع أول رابط من نص (لما المستخدم يشارك من يوتيوب يجي الرابط مع كلام ثاني). */
    fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }
}
