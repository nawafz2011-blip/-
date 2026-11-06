package com.nawaf.mohammel

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nawaf.mohammel.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var video: VideoSummary? = null
    private var playlist: PlaylistSummary? = null
    private var batchUrls: List<String> = emptyList()
    private var fetchJob: CoJob? = null
    private var pendingSpecs: List<JobSpec>? = null
    private val plChecks = mutableListOf<CheckBox>()
    private val jobViews = mutableMapOf<String, View>()

    private val presets = listOf(
        "best" to "أفضل جودة متوفرة", "2160" to "4K (2160p)", "1440" to "2K (1440p)",
        "1080" to "Full HD (1080p)", "720" to "HD (720p)", "480" to "480p", "360" to "360p",
        "audio" to "صوت فقط (MP3)",
    )

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val specs = pendingSpecs
        pendingSpecs = null
        if (granted && specs != null) enqueue(specs)
        else if (!granted) setStatus("لازم تسمح بالتخزين عشان نحفظ الملفات.", error = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.searchBtn.setOnClickListener { search() }
        b.pasteBtn.setOnClickListener { pasteAndSearch() }
        b.updateBanner.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.DOWNLOAD_URL)))
        }
        b.footer.text = "النسخة ${BuildConfig.VERSION_NAME}\nحمّل المحتوى اللي تملكه أو المسموح بتحميله فقط."

        b.tabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            b.downloadView.isVisible = id == R.id.tabDownload
            b.historyView.isVisible = id == R.id.tabHistory
            b.scroll.scrollTo(0, 0)
        }

        setupSingle()
        setupPlaylist()
        setupBatch()
        b.clearDone.setOnClickListener { JobQueue.clearFinished() }
        b.clearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("متأكد تبي تمسح السجل كامل؟")
                .setPositiveButton("امسح") { _, _ -> History.clear() }
                .setNegativeButton("لا", null)
                .show()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { JobQueue.jobs.collect { renderJobs(it) } }
                launch { History.items.collect { renderHistory(it) } }
            }
        }
        lifecycleScope.launch {
            if (UpdateChecker.newerVersionAvailable()) b.updateBanner.isVisible = true
        }
        lifecycleScope.launch {
            if (Engine.initError == null && !Engine.isReady) {
                setStatus("أول تشغيل: جاري تجهيز التطبيق... (ثواني)")
                runCatching { Engine.awaitReady() }
                if (b.status.text.startsWith("أول تشغيل")) setStatus("")
            }
            Engine.initError?.let { setStatus("صار خطأ في تجهيز التطبيق. سكّره وافتحه مرة ثانية.", error = true) }
        }

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** لما تشارك فيديو من يوتيوب أو تيك توك للتطبيق. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val urls = Engine.extractUrls(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (urls.isEmpty()) return
        b.tabs.check(R.id.tabDownload)
        b.urlInput.setText(urls.joinToString("\n"))
        search()
    }

    // ------------------------------------------------------------ البحث
    private fun pasteAndSearch() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val urls = Engine.extractUrls(text)
        if (urls.isEmpty()) {
            setStatus("ما فيه رابط في الحافظة. انسخ رابط الفيديو أول.", error = true)
            return
        }
        b.urlInput.setText(urls.joinToString("\n"))
        search()
    }

    private fun hideResults() {
        b.singleCard.isVisible = false
        b.playlistCard.isVisible = false
        b.batchCard.isVisible = false
    }

    private fun search() {
        val urls = Engine.extractUrls(b.urlInput.text?.toString())
        hideKeyboard()
        fetchJob?.cancel()
        hideResults()
        if (urls.isEmpty()) {
            setStatus("الصق رابط صحيح يبدأ بـ https://", error = true)
            return
        }
        if (urls.size > 1) {
            batchUrls = urls
            b.batchTitle.text = "${urls.size} روابط"
            b.batchCard.isVisible = true
            setStatus("")
            return
        }
        b.searchBtn.isEnabled = false
        setStatus("جاري البحث...")
        fetchJob = lifecycleScope.launch {
            try {
                when (val r = Engine.fetch(urls[0])) {
                    is VideoSummary -> showVideo(r)
                    is PlaylistSummary -> showPlaylist(r)
                }
                setStatus("")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                setStatus(Engine.friendlyError(t), error = true)
            } finally {
                b.searchBtn.isEnabled = true
            }
        }
    }

    // ------------------------------------------------------------ فيديو واحد
    private fun setupSingle() {
        b.extrasToggle.setOnClickListener {
            val open = !b.extras.isVisible
            b.extras.isVisible = open
            b.extrasToggle.text = (if (open) "▴ " else "▾ ") + "خيارات إضافية: قص، ترجمة، صورة الغلاف"
        }
        b.subsBtn.setOnClickListener {
            val v = video ?: return@setOnClickListener
            val lang = v.subtitles.getOrNull(b.subsLang.selectedItemPosition)?.code ?: return@setOnClickListener
            requestEnqueue(listOf(JobSpec(v.url, kind = "subs", lang = lang, title = v.title, thumbnail = v.thumbnail)))
        }
        b.thumbBtn.setOnClickListener {
            val v = video ?: return@setOnClickListener
            requestEnqueue(listOf(JobSpec(v.url, kind = "thumb", title = v.title, thumbnail = v.thumbnail)))
        }
    }

    private fun showVideo(v: VideoSummary) {
        video = v
        b.title.text = v.title
        b.subtitle.text = iso(v.subtitle)
        b.subtitle.isVisible = v.subtitle.isNotEmpty()
        b.thumb.isVisible = v.thumbnail != null
        v.thumbnail?.let { b.thumb.load(it) { crossfade(true) } }
        b.trimStart.setText("")
        b.trimEnd.setText("")
        b.trimEnd.hint = Engine.formatDuration(v.duration)

        b.options.removeAllViews()
        for (opt in v.options) {
            val hi = !opt.isAudio && (opt.id.toIntOrNull() ?: 0) >= 1440
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = iso("${opt.label}   ·   ${opt.detail}")
                isAllCaps = false
                textSize = 15f
                if (hi) setTextColor(ContextCompat.getColor(context, R.color.accent))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setOnClickListener { onQualityClicked(v, opt) }
            }
            b.options.addView(btn)
        }

        b.subsBlock.isVisible = v.subtitles.isNotEmpty()
        b.noSubs.isVisible = v.subtitles.isEmpty()
        b.subsLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, v.subtitles.map { it.name })
        v.subtitles.indexOfFirst { it.code.startsWith("ar") }.takeIf { it >= 0 }?.let { b.subsLang.setSelection(it) }
        b.thumbBtn.isVisible = v.thumbnail != null
        b.extras.isVisible = false
        b.extrasToggle.text = "▾ خيارات إضافية: قص، ترجمة، صورة الغلاف"
        b.singleCard.isVisible = true
    }

    private fun onQualityClicked(v: VideoSummary, opt: QualityOption) {
        val start = Engine.parseTime(b.trimStart.text?.toString())
        val end = Engine.parseTime(b.trimEnd.text?.toString())
        val error = when {
            start == -1 || end == -1 -> "اكتب الوقت بهالشكل: 1:20"
            start != null && end != null && end <= start -> "وقت النهاية لازم يكون بعد البداية."
            start != null && v.duration > 0 && start >= v.duration -> "وقت البداية بعد نهاية الفيديو."
            else -> null
        }
        if (error != null) {
            b.extras.isVisible = true
            setStatus(error, error = true)
            return
        }
        val spec = JobSpec(
            url = v.url,
            kind = if (opt.isAudio) "audio" else "video",
            quality = if (opt.isAudio) "best" else opt.id,
            start = start?.takeIf { it > 0 },
            end = end,
            title = v.title,
            thumbnail = v.thumbnail,
        )
        requestEnqueue(listOf(spec))
    }

    // ------------------------------------------------------------ قائمة تشغيل
    private fun qualityAdapter() =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.second })

    private fun presetSpec(url: String, spinner: Spinner, title: String? = null, thumb: String? = null): JobSpec {
        val id = presets[spinner.selectedItemPosition].first
        return if (id == "audio") JobSpec(url, kind = "audio", title = title, thumbnail = thumb)
        else JobSpec(url, kind = "video", quality = id, title = title, thumbnail = thumb)
    }

    private fun setupPlaylist() {
        b.plQuality.adapter = qualityAdapter()
        b.plQuality.setSelection(presets.indexOfFirst { it.first == "1080" })
        b.plAll.setOnClickListener {
            val all = b.plAll.isChecked
            plChecks.forEach { it.isChecked = all }
            updatePlCount()
        }
        b.plGo.setOnClickListener {
            val pl = playlist ?: return@setOnClickListener
            val specs = plChecks.mapIndexedNotNull { i, cb ->
                if (!cb.isChecked) null else pl.entries[i].let { presetSpec(it.url, b.plQuality, it.title, it.thumbnail) }
            }
            if (specs.isNotEmpty()) requestEnqueue(specs)
        }
    }

    private fun showPlaylist(pl: PlaylistSummary) {
        playlist = pl
        b.plTitle.text = pl.title
        b.plSub.text = iso(pl.subtitle)
        b.plList.removeAllViews()
        plChecks.clear()
        for (e in pl.entries) {
            val cb = CheckBox(this).apply {
                isChecked = true
                text = e.title + if (e.duration > 0) "  ·  ${Engine.formatDuration(e.duration)}" else ""
                textSize = 14f
                maxLines = 2
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setPadding(paddingLeft, 12, paddingRight, 12)
                setOnCheckedChangeListener { _, _ -> updatePlCount() }
            }
            plChecks += cb
            b.plList.addView(cb)
        }
        updatePlCount()
        b.playlistCard.isVisible = true
    }

    private fun updatePlCount() {
        val n = plChecks.count { it.isChecked }
        b.plGo.text = if (n > 0) "حمّل المحدد ($n)" else "اختر فيديو"
        b.plGo.isEnabled = n > 0
        b.plAll.isChecked = n == plChecks.size
    }

    // ------------------------------------------------------------ كذا رابط
    private fun setupBatch() {
        b.batchQuality.adapter = qualityAdapter()
        b.batchQuality.setSelection(presets.indexOfFirst { it.first == "1080" })
        b.batchGo.setOnClickListener {
            requestEnqueue(batchUrls.map { presetSpec(it, b.batchQuality) })
        }
    }

    // ------------------------------------------------------------ التحميل
    private fun requestEnqueue(specs: List<JobSpec>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSpecs = specs
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueue(specs)
    }

    private fun enqueue(specs: List<JobSpec>) {
        DownloadService.enqueue(this, specs)
        setStatus(if (specs.size > 1) "انضاف ${specs.size} تحميل للقائمة." else "")
        b.scroll.post { b.scroll.smoothScrollTo(0, b.queueHeader.top) }
    }

    private fun jobMeta(j: Job): String = when (j.status) {
        JobStatus.QUEUED -> "في الانتظار..."
        JobStatus.RUNNING -> DownloadService.progressText(j.progress, j.etaSeconds)
        JobStatus.DONE -> "تم ✓ " + Engine.formatSize(j.saved?.size ?: 0) + " · " + Engine.savedPlace(j.spec.kind)
        JobStatus.ERROR -> j.error ?: "صار خطأ."
        JobStatus.CANCELED -> "انلغى."
    }

    private fun renderJobs(jobs: List<Job>) {
        b.queueHeader.isVisible = jobs.isNotEmpty()
        b.clearDone.isVisible = jobs.any { !it.active }
        val ordered = jobs.sortedByDescending { it.created }
        val ids = ordered.map { it.id }.toSet()
        jobViews.keys.filterNot { it in ids }.forEach { jobViews.remove(it) }

        val views = ordered.map { j ->
            jobViews.getOrPut(j.id) { layoutInflater.inflate(R.layout.item_job, b.jobsList, false) }.also { bindJob(it, j) }
        }
        // نعيد ترتيب العناصر بس إذا تغيّرت، عشان التحديث يكون خفيف
        val same = b.jobsList.childCount == views.size && views.indices.all { b.jobsList.getChildAt(it) === views[it] }
        if (!same) {
            b.jobsList.removeAllViews()
            views.forEach { b.jobsList.addView(it) }
        }
    }

    private fun bindJob(v: View, j: Job) {
        val thumb = v.findViewById<ImageView>(R.id.jobThumb)
        if (thumb.tag != j.spec.thumbnail) {
            thumb.tag = j.spec.thumbnail
            if (j.spec.thumbnail != null) thumb.load(j.spec.thumbnail) else thumb.setImageDrawable(null)
        }
        v.findViewById<TextView>(R.id.jobTitle).text = j.title
        v.findViewById<TextView>(R.id.jobLabel).text = iso(j.spec.label)
        val meta = v.findViewById<TextView>(R.id.jobMeta)
        meta.text = iso(jobMeta(j))
        meta.setTextColor(
            ContextCompat.getColor(this, when (j.status) {
                JobStatus.DONE -> R.color.ok
                JobStatus.ERROR -> R.color.accent
                else -> R.color.muted
            })
        )

        val bar = v.findViewById<LinearProgressIndicator>(R.id.jobBar)
        val running = j.status == JobStatus.RUNNING
        val showBar = j.active || j.status == JobStatus.DONE
        val indeterminate = j.status == JobStatus.QUEUED || (running && (j.progress < 0 || j.progress >= 100))
        if (bar.isIndeterminate != indeterminate) {
            bar.isVisible = false // لازم نخفيه قبل ما نغيّر النوع
            bar.isIndeterminate = indeterminate
        }
        bar.isVisible = showBar
        if (!indeterminate) bar.setProgressCompat(if (j.status == JobStatus.DONE) 100 else j.progress.toInt(), true)
        bar.setIndicatorColor(ContextCompat.getColor(this, if (j.status == JobStatus.DONE) R.color.ok else R.color.accent))

        val actions = v.findViewById<LinearLayout>(R.id.jobActions)
        val key = j.status.name
        if (actions.tag != key) {
            actions.tag = key
            actions.removeAllViews()
            when (j.status) {
                JobStatus.QUEUED, JobStatus.RUNNING ->
                    actions.addView(smallButton("إلغاء", tonal = true) { DownloadService.cancel(this, j.id) })
                JobStatus.DONE -> {
                    actions.addView(smallButton("فتح", tonal = false) { j.saved?.let { openFile(it.uri, it.mime) } })
                    actions.addView(smallButton("إزالة", text = true) { JobQueue.remove(j.id) })
                }
                JobStatus.ERROR, JobStatus.CANCELED -> {
                    actions.addView(smallButton("إعادة", tonal = true) {
                        JobQueue.remove(j.id)
                        requestEnqueue(listOf(j.spec))
                    })
                    actions.addView(smallButton("إزالة", text = true) { JobQueue.remove(j.id) })
                }
            }
        }
    }

    // ------------------------------------------------------------ السجل
    private fun renderHistory(items: List<HistoryItem>) {
        b.tabHistory.text = if (items.isEmpty()) "السجل" else "السجل (${items.size})"
        b.historyEmpty.isVisible = items.isEmpty()
        b.clearHistory.isVisible = items.isNotEmpty()
        b.historyList.removeAllViews()
        for (h in items) {
            val v = layoutInflater.inflate(R.layout.item_job, b.historyList, false)
            val thumb = v.findViewById<ImageView>(R.id.jobThumb)
            if (h.spec.thumbnail != null) thumb.load(h.spec.thumbnail)
            v.findViewById<TextView>(R.id.jobTitle).text = h.title
            v.findViewById<TextView>(R.id.jobLabel).text = iso(listOf(h.label, Engine.formatSize(h.size)).filter { it.isNotEmpty() }.joinToString(" · "))
            v.findViewById<LinearProgressIndicator>(R.id.jobBar).isVisible = false
            v.findViewById<TextView>(R.id.jobMeta).text =
                DateUtils.getRelativeTimeSpanString(h.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            val actions = v.findViewById<LinearLayout>(R.id.jobActions)
            actions.addView(smallButton("حمّل", tonal = false) {
                b.tabs.check(R.id.tabDownload)
                requestEnqueue(listOf(h.spec))
            })
            if (h.uri != null) {
                actions.addView(smallButton("فتح", tonal = true) { History.uriOf(h)?.let { openFile(it, h.mime ?: "*/*") } })
            }
            actions.addView(smallButton("حذف", text = true) { History.delete(h.id) })
            b.historyList.addView(v)
        }
    }

    // ------------------------------------------------------------ أدوات
    private fun smallButton(label: String, tonal: Boolean = false, text: Boolean = false, onClick: () -> Unit): MaterialButton {
        val style = when {
            text -> androidx.appcompat.R.attr.borderlessButtonStyle
            tonal -> com.google.android.material.R.attr.materialButtonOutlinedStyle
            else -> com.google.android.material.R.attr.materialButtonStyle
        }
        return MaterialButton(this, null, style).apply {
            this.text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(28, 0, 28, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * resources.displayMetrics.density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun openFile(uri: Uri, mime: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Throwable) {
            Toast.makeText(this, "ما قدرنا نفتح الملف. يمكن انحذف، أو ما فيه تطبيق يفتحه.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setStatus(text: String, error: Boolean = false) {
        b.status.text = text
        b.status.setTextColor(ContextCompat.getColor(this, if (error) R.color.accent else R.color.muted))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.urlInput.windowToken, 0)
        b.urlInput.clearFocus()
    }

    /** يعزل الأرقام والكلمات الإنجليزية عشان ما تنقلب داخل النص العربي. */
    private fun iso(text: String): String =
        Regex("""[A-Za-z0-9~][A-Za-z0-9:.%/~()–\- ]*[A-Za-z0-9%)]|[A-Za-z0-9]""")
            .replace(text) { "⁦" + it.value.replace("–", "⁠–⁠") + "⁩" }
}
