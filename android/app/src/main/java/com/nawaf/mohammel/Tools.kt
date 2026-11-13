package com.nawaf.mohammel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * أدوات على ملفات موجودة في الجوال: ضغط الفيديو، وتحويل الفيديو لـ MP3.
 * تستخدم ffmpeg اللي داخل التطبيق (نفس اللي يستخدمه yt-dlp).
 */
object Tools {
    private val running = ConcurrentHashMap<String, Process>()

    class Canceled : Exception()

    /** الـ spec.url هنا رابط الملف داخل الجوال (content://). */
    fun run(ctx: Context, spec: JobSpec, processId: String, onProgress: (Float, Long) -> Unit): SavedFile {
        val work = File(ctx.cacheDir, "tool/$processId").apply { deleteRecursively(); mkdirs() }
        try {
            val input = copyIn(ctx, Uri.parse(spec.url), work)
            val base = (spec.title ?: input.nameWithoutExtension).replace(Regex("""[\\/:*?"<>|]"""), " ").take(100).trim()
            val (out, args, kind) = when (spec.kind) {
                "tomp3" -> Triple(
                    File(work, "$base.mp3"),
                    listOf("-vn", "-c:a", "libmp3lame", "-b:a", "192k"),
                    "audio",
                )
                else -> {
                    // ضغط: "small" = 720p تقريباً بجودة جيدة، "tiny" = 480p حجم أصغر بكثير
                    val tiny = spec.quality == "tiny"
                    val height = if (tiny) 480 else 720
                    val crf = if (tiny) "32" else "28"
                    Triple(
                        File(work, "$base (مضغوط).mp4"),
                        listOf(
                            "-vf", "scale=-2:'min($height,ih)'",
                            "-c:v", "libx264", "-preset", "veryfast", "-crf", crf,
                            "-c:a", "aac", "-b:a", if (tiny) "96k" else "128k",
                            "-movflags", "+faststart",
                        ),
                        "video",
                    )
                }
            }
            try {
                ffmpeg(ctx, input, out, args, processId, onProgress)
            } catch (e: Canceled) {
                throw e
            } catch (e: Exception) {
                if (spec.kind != "compress") throw e
                // بعض نسخ ffmpeg ما فيها libx264: نجرب ترميز أبسط
                out.delete()
                val fallback = listOf("-vf", args[1], "-c:v", "mpeg4", "-q:v", if (spec.quality == "tiny") "8" else "5", "-c:a", "aac", "-b:a", "128k")
                ffmpeg(ctx, input, out, fallback, processId, onProgress)
            }
            if (!out.exists() || out.length() == 0L) throw IllegalStateException("ما قدرنا نجهّز الملف.")
            return Engine.saveFile(ctx, out, kind)
        } finally {
            work.deleteRecursively()
        }
    }

    fun cancel(processId: String): Boolean {
        val p = running.remove(processId) ?: return false
        p.destroy()
        return true
    }

    /** ننسخ الملف من الجوال لمجلد مؤقت، لأن ffmpeg يحتاج مسار ملف عادي. */
    private fun copyIn(ctx: Context, uri: Uri, dir: File): File {
        var name = "input"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val file = File(dir, "in_" + name.replace(Regex("""[\\/]"""), "_"))
        ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("ما قدرنا نفتح الملف.")
        return file
    }

    private fun ffmpeg(
        ctx: Context, input: File, output: File, args: List<String>,
        processId: String, onProgress: (Float, Long) -> Unit,
    ) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val packages = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        val cmd = listOf(bin.absolutePath, "-y", "-hide_banner", "-nostdin", "-i", input.absolutePath) +
            args + listOf("-progress", "pipe:1", "-nostats", output.absolutePath)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg", "aria2c")
            .joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath

        val p = pb.start()
        running[processId] = p
        var durationUs = 0L
        val tail = ArrayDeque<String>()
        val started = System.currentTimeMillis()
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (tail.size > 20) tail.removeFirst()
                tail.addLast(line)
                if (durationUs == 0L) {
                    Regex("""Duration: (\d+):(\d+):(\d+)\.(\d+)""").find(line)?.destructured?.let { (h, m, s, cs) ->
                        durationUs = ((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 100 + cs.take(2).toLong()) * 10_000
                    }
                }
                if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                    val us = line.substringAfter('=').toLongOrNull() ?: return@forEachLine
                    if (durationUs > 0) {
                        val frac = (us.toFloat() / durationUs).coerceIn(0f, 0.99f)
                        val elapsed = (System.currentTimeMillis() - started) / 1000f
                        val eta = if (frac > 0.02f) ((elapsed / frac) - elapsed).toLong() else -1
                        onProgress(frac * 100, eta)
                    }
                }
            }
            val code = p.waitFor()
            if (!running.containsKey(processId)) throw Canceled()
            if (code != 0) throw IllegalStateException("ffmpeg: " + tail.takeLast(3).joinToString(" | "))
        } finally {
            running.remove(processId)
        }
    }
}
