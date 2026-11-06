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
import org.json.JSONObject
import java.io.File

/** خيار جودة يظهر للمستخدم. id = الارتفاع (مثل "1080") أو "audio" أو "best". */
data class QualityOption(val id: String, val label: String, val detail: String, val isAudio: Boolean)

data class SubtitleOption(val code: String, val name: String)

sealed interface FetchResult

data class VideoSummary(
    val url: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val duration: Int,
    val options: List<QualityOption>,
    val subtitles: List<SubtitleOption>,
) : FetchResult

data class PlaylistEntry(val url: String, val title: String, val duration: Int, val thumbnail: String?)

data class PlaylistSummary(
    val title: String,
    val subtitle: String,
    val total: Int,
    val entries: List<PlaylistEntry>,
) : FetchResult

/** وش نحمّل بالضبط. kind = video | audio | thumb | subs */
data class JobSpec(
    val url: String,
    val kind: String = "video",
    val quality: String = "best",
    val start: Int? = null,
    val end: Int? = null,
    val lang: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
) {
    val label: String
        get() {
            var l = when (kind) {
                "audio" -> "صوت MP3"
                "thumb" -> "صورة الغلاف"
                "subs" -> "ترجمة ($lang)"
                else -> if (quality == "best") "أفضل جودة" else "${quality}p"
            }
            if ((kind == "video" || kind == "audio") && (start != null || end != null)) {
                l += " · مقطع ${Engine.formatDuration(start ?: 0).ifEmpty { "0:00" }}–${end?.let { Engine.formatDuration(it) } ?: "النهاية"}"
            }
            return l
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url); put("kind", kind); put("quality", quality)
        put("start", start ?: JSONObject.NULL); put("end", end ?: JSONObject.NULL)
        put("lang", lang ?: JSONObject.NULL); put("title", title ?: JSONObject.NULL)
        put("thumbnail", thumbnail ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = JobSpec(
            url = o.getString("url"),
            kind = o.optString("kind", "video"),
            quality = o.optString("quality", "best"),
            start = o.optIntOrNull("start"),
            end = o.optIntOrNull("end"),
            lang = o.optStringOrNull("lang"),
            title = o.optStringOrNull("title"),
            thumbnail = o.optStringOrNull("thumbnail"),
        )
    }
}

data class SavedFile(val uri: Uri, val mime: String, val displayName: String, val size: Long)

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }?.toInt()

/**
 * المحرك: yt-dlp و ffmpeg مدمجين داخل التطبيق، يشتغلون على الجوال نفسه بدون أي سيرفر.
 */
object Engine {
    private const val FOLDER = "Mohammel"
    private const val MAX_PLAYLIST = 300
    private val ready = CompletableDeferred<Unit>()
    @Volatile var initError: Throwable? = null
        private set

    private val LANG_NAMES = mapOf(
        "ar" to "العربية", "en" to "الإنجليزية", "fr" to "الفرنسية", "es" to "الإسبانية",
        "de" to "الألمانية", "tr" to "التركية", "ur" to "الأردية", "hi" to "الهندية",
        "id" to "الإندونيسية", "fa" to "الفارسية", "ru" to "الروسية", "ja" to "اليابانية",
        "ko" to "الكورية", "zh" to "الصينية", "it" to "الإيطالية", "pt" to "البرتغالية",
    )

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
        return when {
            mb >= 1024 -> "%.1f GB".format(mb / 1024)
            mb >= 1 -> "%d MB".format(mb.toInt())
            else -> "%d KB".format(maxOf(1, (bytes / 1024).toInt()))
        }
    }

    fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "1:20" → 80 ، "90" → 90 ، "1:02:03" → 3723 ، فاضي → null ، غلط → -1 */
    fun parseTime(raw: String?): Int? {
        val text = raw?.trim()?.map { c -> if (c in '٠'..'٩') ('0' + (c - '٠')) else c }?.joinToString("") ?: return null
        if (text.isEmpty()) return null
        if (!Regex("""^\d+(:\d{1,2}){0,2}$""").matches(text)) return -1
        return text.split(":").fold(0) { acc, p -> acc * 60 + p.toInt() }
    }

    private fun bestThumb(o: JSONObject): String? {
        o.optStringOrNull("thumbnail")?.let { return it }
        val arr = o.optJSONArray("thumbnails") ?: return null
        if (arr.length() == 0) return null
        return arr.optJSONObject(arr.length() - 1)?.optStringOrNull("url")
    }

    suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.IO) {
        awaitReady()
        val request = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--flat-playlist")
            addOption("--no-playlist")
            addOption("--playlist-end", MAX_PLAYLIST)
        }
        val response = YoutubeDL.getInstance().execute(request)
        val info = JSONObject(response.out.trim().lines().last { it.startsWith("{") })
        val type = info.optString("_type")
        if (type == "playlist" || type == "multi_video") parsePlaylist(info) else parseVideo(url, info)
    }

    private fun parsePlaylist(info: JSONObject): PlaylistSummary {
        val arr = info.optJSONArray("entries")
        val entries = mutableListOf<PlaylistEntry>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                var u = e.optStringOrNull("webpage_url") ?: e.optStringOrNull("url") ?: continue
                if (!u.startsWith("http") && e.optString("ie_key") == "Youtube") u = "https://www.youtube.com/watch?v=$u"
                entries += PlaylistEntry(u, e.optStringOrNull("title") ?: "فيديو", e.optIntOrNull("duration") ?: 0, bestThumb(e))
                if (entries.size >= MAX_PLAYLIST) break
            }
        }
        if (entries.isEmpty()) throw IllegalStateException("ما فيه فيديوهات في هذا الرابط")
        val total = info.optIntOrNull("playlist_count") ?: entries.size
        val more = if (total > entries.size) " (أول ${entries.size} من $total)" else ""
        return PlaylistSummary(
            title = info.optStringOrNull("title") ?: "قائمة تشغيل",
            subtitle = listOfNotNull(
                info.optStringOrNull("uploader") ?: info.optStringOrNull("channel"),
                "${entries.size} فيديو$more",
            ).joinToString(" · "),
            total = total,
            entries = entries,
        )
    }

    private fun parseVideo(url: String, info: JSONObject): VideoSummary {
        val sizes = sortedMapOf<Int, Long>(compareByDescending { it })
        var hasAudio = false
        val formats = info.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val acodec = f.optStringOrNull("acodec")
                val vcodec = f.optStringOrNull("vcodec")
                if (acodec != null && acodec != "none") hasAudio = true
                val h = f.optIntOrNull("height") ?: 0
                if (h <= 0 || vcodec == null || vcodec == "none") continue
                val size = maxOf(f.optLong("filesize", 0), f.optLong("filesize_approx", 0))
                sizes[h] = maxOf(sizes[h] ?: 0L, size)
            }
        }

        val options = mutableListOf<QualityOption>()
        for ((h, size) in sizes) {
            val parts = mutableListOf("MP4")
            formatSize(size).takeIf { it.isNotEmpty() }?.let { parts += "~$it" }
            options += QualityOption(h.toString(), qualityLabel(h), parts.joinToString(" · "), false)
        }
        if (options.isEmpty()) options += QualityOption("best", "أفضل جودة", "MP4", false)
        if (hasAudio || options.isNotEmpty()) options += QualityOption("audio", "صوت فقط", "MP3", true)

        val subs = mutableListOf<SubtitleOption>()
        val seen = mutableSetOf<String>()
        info.optJSONObject("subtitles")?.keys()?.forEach { code ->
            if (code != "live_chat" && seen.add(code)) {
                subs += SubtitleOption(code, LANG_NAMES[code.substringBefore("-")] ?: code)
            }
        }
        info.optJSONObject("automatic_captions")?.keys()?.forEach { code ->
            if ((code.endsWith("-orig") || code == "ar" || code == "en") && seen.add(code)) {
                val base = code.substringBefore("-")
                subs += SubtitleOption(code, "${LANG_NAMES[base] ?: base} (تلقائية)")
            }
        }

        val duration = info.optIntOrNull("duration") ?: 0
        return VideoSummary(
            url = info.optStringOrNull("webpage_url") ?: url,
            title = info.optStringOrNull("title") ?: "فيديو",
            subtitle = listOfNotNull(
                info.optStringOrNull("uploader") ?: info.optStringOrNull("channel"),
                info.optStringOrNull("extractor_key"),
                formatDuration(duration).ifEmpty { null },
            ).joinToString(" · "),
            thumbnail = bestThumb(info),
            duration = duration,
            options = options,
            subtitles = subs,
        )
    }

    /**
     * يحمّل داخل مجلد مؤقت، وبعدها ينقل الملف للمعرض أو الموسيقى أو الصور أو التنزيلات.
     * onTitle يتنادى أول ما نعرف عنوان الفيديو (للروابط اللي انضافت بدون عنوان).
     */
    fun download(
        context: Context,
        spec: JobSpec,
        processId: String,
        onProgress: (Float, Long) -> Unit,
    ): SavedFile {
        val workDir = File(context.cacheDir, "dl/$processId").apply { deleteRecursively(); mkdirs() }
        try {
            val request = YoutubeDLRequest(spec.url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--newline")
                addOption("-o", "${workDir.absolutePath}/%(title).120B.%(ext)s")
                when (spec.kind) {
                    "audio" -> {
                        addOption("-f", "bestaudio/best")
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "192K")
                    }
                    "thumb" -> {
                        addOption("--skip-download")
                        addOption("--write-thumbnail")
                        addOption("--convert-thumbnails", "jpg")
                    }
                    "subs" -> {
                        addOption("--skip-download")
                        addOption("--write-subs")
                        addOption("--write-auto-subs")
                        addOption("--sub-langs", spec.lang ?: "ar")
                        addOption("--sub-format", "srt/vtt/best")
                        addOption("--convert-subs", "srt")
                    }
                    else -> {
                        addOption("-f", "bv*+ba/b")
                        // نفضّل H.264 + AAC لأنها تشتغل على أي جهاز. 4K و8K ما لها H.264 في يوتيوب.
                        val res = spec.quality.toIntOrNull()
                        addOption("-S", (if (res != null) "res:$res" else "res") + ",vcodec:h264,acodec:aac,ext:mp4:m4a")
                        addOption("--merge-output-format", "mp4")
                    }
                }
                if (spec.kind == "video" || spec.kind == "audio") {
                    addOption("--concurrent-fragments", 4)
                    if (spec.start != null || spec.end != null) {
                        addOption("--download-sections", "*${spec.start ?: 0}-${spec.end?.toString() ?: "inf"}")
                        addOption("--force-keyframes-at-cuts")
                    }
                }
            }
            YoutubeDL.getInstance().execute(request, processId) { progress, eta, _ ->
                onProgress(progress, eta)
            }

            val exts = when (spec.kind) {
                "subs" -> setOf("srt", "vtt", "ass", "ttml")
                "thumb" -> setOf("jpg", "jpeg", "png", "webp")
                else -> null
            }
            val file = workDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.filter { exts == null || it.extension.lowercase() in exts }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException(
                    if (spec.kind == "subs") "ما فيه ترجمة بهذي اللغة." else "ما لقينا الملف بعد التحميل."
                )
            return save(context, file, spec.kind)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun mimeOf(file: File, kind: String): String {
        val ext = file.extension.lowercase()
        if (ext == "srt") return "application/x-subrip"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (kind) {
            "audio" -> "audio/mpeg"
            "thumb" -> "image/jpeg"
            "subs" -> "text/plain"
            else -> "video/mp4"
        }
    }

    private fun save(context: Context, file: File, kind: String): SavedFile {
        val mime = mimeOf(file, kind)
        val name = file.name
        val size = file.length()
        val publicDir = when (kind) {
            "audio" -> Environment.DIRECTORY_MUSIC
            "thumb" -> Environment.DIRECTORY_PICTURES
            "subs" -> Environment.DIRECTORY_DOWNLOADS
            else -> Environment.DIRECTORY_MOVIES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
            val collection = when (kind) {
                "audio" -> MediaStore.Audio.Media.getContentUri(vol)
                "thumb" -> MediaStore.Images.Media.getContentUri(vol)
                "subs" -> MediaStore.Downloads.getContentUri(vol)
                else -> MediaStore.Video.Media.getContentUri(vol)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/$FOLDER")
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
            return SavedFile(uri, mime, name, size)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), FOLDER).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", dest)
            return SavedFile(uri, mime, name, size)
        }
    }

    fun savedPlace(kind: String): String = when (kind) {
        "audio" -> "الموسيقى (Music/Mohammel)"
        "thumb" -> "الصور (Pictures/Mohammel)"
        "subs" -> "التنزيلات (Download/Mohammel)"
        else -> "المعرض (Movies/Mohammel)"
    }

    /** يحوّل أخطاء yt-dlp لرسائل عربية مفهومة. */
    fun friendlyError(t: Throwable): String {
        if (t is IllegalStateException && t.message != null) return t.message!!
        val msg = (t.message ?: "") + " " + (t.cause?.message ?: "")
        val low = msg.lowercase()
        return when {
            "Unsupported URL" in msg -> "هذا الموقع أو الرابط غير مدعوم."
            "Sign in to confirm" in msg || "bot" in low -> "يوتيوب طلب تحقق. جرّب بعد شوي."
            "private" in low -> "الفيديو خاص أو محذوف."
            "requested format is not available" in low -> "الجودة هذي مو متوفرة لهذا الفيديو."
            "Unable to resolve host" in msg || "Network is unreachable" in msg || "timed out" in low ->
                "تأكد إن الإنترنت شغال."
            "no space left" in low -> "المساحة في الجوال ما تكفي."
            else -> "ما قدرنا نجيب الفيديو. تأكد من الرابط وجرب مرة ثانية."
        }
    }

    /** يطلّع كل الروابط من نص (لما المستخدم يشارك من يوتيوب يجي الرابط مع كلام ثاني). */
    fun extractUrls(text: String?): List<String> =
        Regex("""https?://[^\s"'<>]+""").findAll(text ?: "")
            .map { it.value.trimEnd('.', ',', ')') }
            .distinct()
            .toList()
}
