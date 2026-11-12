package com.nawaf.mohammel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.widget.ImageView
import androidx.core.view.isVisible
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.NightMode
import com.nawaf.mohammel.databinding.ActivityMainBinding
import org.junit.Rule
import org.junit.Test

/** بيانات تجريبية للمعاينة فقط. */
object Samples {
    val video = VideoSummary(
        url = "https://youtu.be/sample",
        title = "رحلة إلى جبال السودة | أجمل الأماكن في أبها",
        subtitle = "قناة الرحلات · Youtube · 12:34",
        thumbnail = "thumb://1",
        duration = 754,
        options = listOf(
            QualityOption("2160", "2160p (4K)", "MP4 · ~860 MB", false),
            QualityOption("1440", "1440p (2K)", "MP4 · ~380 MB", false),
            QualityOption("1080", "1080p (Full HD)", "MP4 · ~190 MB", false),
            QualityOption("720", "720p (HD)", "MP4 · ~86 MB", false),
            QualityOption("480", "480p", "MP4 · ~40 MB", false),
            QualityOption("audio", "صوت فقط", "MP3", true),
        ),
        subtitles = listOf(SubtitleOption("ar", "العربية"), SubtitleOption("en-orig", "الإنجليزية (تلقائية)")),
    )

    val playlist = PlaylistSummary(
        title = "سلسلة تعلّم البرمجة من الصفر",
        subtitle = "أكاديمية التقنية · 6 فيديو",
        total = 6,
        entries = listOf(
            "مقدمة: ليش تتعلم البرمجة؟" to 420, "الدرس 1: المتغيرات" to 900, "الدرس 2: الشروط" to 1110,
            "الدرس 3: الحلقات" to 1260, "الدرس 4: الدوال" to 1500, "مشروع: آلة حاسبة" to 2100,
        ).mapIndexed { i, (t, d) -> PlaylistEntry("https://youtu.be/$i", t, d, "thumb://$i") },
    )

    private fun spec(title: String, kind: String = "video", quality: String = "1080", start: Int? = null, end: Int? = null, t: Int) =
        JobSpec("https://youtu.be/$t", kind = kind, quality = quality, start = start, end = end, title = title, thumbnail = "thumb://$t")

    val jobs = listOf(
        Job("a", spec("رحلة إلى جبال السودة | أجمل الأماكن في أبها", quality = "2160", t = 1), JobStatus.RUNNING, 64f, 42, created = 5),
        Job("b", spec("الدرس 2: الشروط", kind = "audio", t = 2), JobStatus.QUEUED, created = 6),
        Job("c", spec("أفضل لقطات المباراة", start = 80, end = 120, t = 3), JobStatus.DONE, 100f,
            saved = SavedFile(Uri.EMPTY, "video/mp4", "x.mp4", 48_000_000), created = 4),
        Job("d", spec("فيديو خاص", t = 4), JobStatus.ERROR, error = "الفيديو خاص أو محذوف.", created = 3),
    )

    private const val NOW = 1_762_430_000_000L
    val history = listOf(
        HistoryItem("1", spec("رحلة إلى جبال السودة", quality = "1080", t = 1), "1080p", "رحلة إلى جبال السودة | أجمل الأماكن في أبها", 190_000_000, NOW - 5 * 60_000, "content://x", "video/mp4"),
        HistoryItem("2", spec("قصيدة", kind = "audio", t = 5), "صوت MP3", "قصيدة جميلة بصوت رائع", 6_400_000, NOW - 3 * 3_600_000, "content://x", "audio/mpeg"),
        HistoryItem("3", spec("الدرس 1", start = 60, end = 180, t = 2), "720p · مقطع 1:00–3:00", "الدرس 1: المتغيرات", 22_000_000, NOW - 26 * 3_600_000, null, null),
        HistoryItem("4", spec("غلاف", kind = "thumb", t = 3), "صورة الغلاف", "أفضل لقطات المباراة", 180_000, NOW - 3 * 86_400_000, "content://x", "image/jpeg"),
    )
    const val now = NOW

    /** صورة مصغّرة تجريبية: تدرّج لوني فيه زر تشغيل. */
    fun thumb(key: String): Bitmap {
        val palette = listOf(
            intArrayOf(0xFF2B5876.toInt(), 0xFF4E4376.toInt()), intArrayOf(0xFFF7971E.toInt(), 0xFFFFD200.toInt()),
            intArrayOf(0xFF11998E.toInt(), 0xFF38EF7D.toInt()), intArrayOf(0xFFFC5C7D.toInt(), 0xFF6A82FB.toInt()),
            intArrayOf(0xFF373B44.toInt(), 0xFF4286F4.toInt()), intArrayOf(0xFFDA4453.toInt(), 0xFF89216B.toInt()),
        )
        val c = palette[(key.hashCode() and 0x7fffffff) % palette.size]
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawRect(0f, 0f, 640f, 360f, Paint().apply { shader = LinearGradient(0f, 0f, 640f, 360f, c[0], c[1], Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        cv.drawCircle(500f, 80f, 120f, p)
        cv.drawCircle(120f, 320f, 90f, p)
        cv.drawCircle(320f, 180f, 54f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) })
        cv.drawPath(Path().apply { moveTo(306f, 156f); lineTo(306f, 204f); lineTo(346f, 180f); close() },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c[0] })
        return bmp
    }
}

object NoActions : UiActions {
    override fun search() {}
    override fun paste() {}
    override fun enqueue(specs: List<JobSpec>) {}
    override fun cancel(id: String) {}
    override fun removeJob(id: String) {}
    override fun clearDone() {}
    override fun openFile(uri: Uri, mime: String) {}
    override fun clearHistory() {}
    override fun deleteHistory(id: String) {}
    override fun openUpdate() {}
}

fun buildUi(p: Paparazzi): MainUi {
    val binding = ActivityMainBinding.inflate(p.layoutInflater)
    val ui = MainUi(p.context, binding, NoActions) { view: ImageView, url: String? ->
        if (url != null) view.setImageBitmap(Samples.thumb(url)) else view.setImageDrawable(null)
    }
    ui.setup("1.0.3")
    ui.renderJobs(emptyList())
    ui.renderHistory(emptyList(), Samples.now)
    return ui
}

private val phone = DeviceConfig.PIXEL_6.copy(locale = "ar")

/** صور بحجم شاشة الجوال. */
class PhoneScreens {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = phone, theme = "Theme.Mohammel", maxPercentDifference = 100.0)

    @Test
    fun home() {
        val ui = buildUi(paparazzi)
        ui.renderHistory(Samples.history, Samples.now)
        paparazzi.snapshot(ui.b.root, "1_home")
    }

    @Test
    fun video() {
        val ui = buildUi(paparazzi)
        ui.showVideo(Samples.video)
        ui.b.urlInput.setText("https://youtu.be/sample")
        paparazzi.snapshot(ui.b.root, "2_video")
    }

    @Test
    fun browser() {
        val ui = buildUi(paparazzi)
        ui.setupBrowserStart { }
        ui.showBrowserStart(true)
        ui.showTab(MainUi.Tab.BROWSER)
        paparazzi.snapshot(ui.b.root, "8_browser")
    }

    @Test
    fun welcome() {
        val ui = buildUi(paparazzi)
        val dim = android.widget.FrameLayout(paparazzi.context)
        dim.addView(ui.b.root)
        val shade = android.view.View(paparazzi.context).apply { setBackgroundColor(0x99000000.toInt()) }
        dim.addView(shade)
        val card = paparazzi.layoutInflater.inflate(R.layout.dialog_welcome, dim, false)
        val lp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER,
        ).apply { leftMargin = 64; rightMargin = 64 }
        dim.addView(card, lp)
        paparazzi.snapshot(dim, "0_welcome")
    }

    @Test
    fun history() {
        val ui = buildUi(paparazzi)
        ui.renderHistory(Samples.history, Samples.now)
        ui.showTab(MainUi.Tab.HISTORY)
        paparazzi.snapshot(ui.b.root, "6_history")
    }
}

/** صور للصفحة كاملة من فوق لتحت. */
class FullScreens {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = phone,
        theme = "Theme.Mohammel",
        renderingMode = SessionParams.RenderingMode.V_SCROLL,
        maxPercentDifference = 100.0,
    )

    @Test
    fun videoWithTrim() {
        val ui = buildUi(paparazzi)
        ui.b.urlInput.setText("https://youtu.be/sample")
        ui.showVideo(Samples.video)
        ui.openTrim()
        ui.b.trimStart.setText("1:20")
        ui.b.trimEnd.setText("2:00")
        paparazzi.snapshot(ui.b.root, "3_video_full")
    }

    @Test
    fun videoWithSubs() {
        val ui = buildUi(paparazzi)
        ui.b.urlInput.setText("https://youtu.be/sample")
        ui.showVideo(Samples.video)
        ui.previewEmbedSubs()
        paparazzi.snapshot(ui.b.root, "3b_video_subs")
    }

    @Test
    fun downloads() {
        val ui = buildUi(paparazzi)
        ui.b.urlInput.setText("https://youtu.be/sample")
        ui.renderJobs(Samples.jobs)
        paparazzi.snapshot(ui.b.root, "4_downloads")
    }

    @Test
    fun playlist() {
        val ui = buildUi(paparazzi)
        ui.b.urlInput.setText("https://youtube.com/playlist?list=sample")
        ui.showPlaylist(Samples.playlist)
        paparazzi.snapshot(ui.b.root, "5_playlist")
    }
}

/** الوضع الليلي. */
class DarkScreens {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = phone.copy(nightMode = NightMode.NIGHT), theme = "Theme.Mohammel", maxPercentDifference = 100.0)

    @Test
    fun videoDark() {
        val ui = buildUi(paparazzi)
        ui.b.urlInput.setText("https://youtu.be/sample")
        ui.showVideo(Samples.video)
        paparazzi.snapshot(ui.b.root, "7_video_dark")
    }
}
