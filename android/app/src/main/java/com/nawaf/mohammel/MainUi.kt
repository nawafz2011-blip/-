package com.nawaf.mohammel

import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import android.widget.ProgressBar
import com.nawaf.mohammel.databinding.ActivityMainBinding

/** الأفعال اللي تحتاجها الشاشة من الـ Activity. */
interface UiActions {
    fun search()
    fun paste()
    fun enqueue(specs: List<JobSpec>)
    fun cancel(id: String)
    fun removeJob(id: String)
    fun clearDone()
    fun openFile(uri: Uri, mime: String)
    fun clearHistory()
    fun deleteHistory(id: String)
    fun openUpdate()
}

/**
 * كل رسم الشاشة هنا (بدون منطق التحميل)، عشان نقدر نطلع منها صور في الاختبارات.
 */
class MainUi(
    private val ctx: Context,
    val b: ActivityMainBinding,
    private val actions: UiActions,
    private val loadImage: (ImageView, String?) -> Unit,
) {
    private val inflater = LayoutInflater.from(ctx)
    var video: VideoSummary? = null
        private set
    private var playlist: PlaylistSummary? = null
    private var batchUrls: List<String> = emptyList()
    private val plChecks = mutableListOf<CheckBox>()
    private val jobViews = mutableMapOf<String, View>()

    private val presets = listOf(
        "best" to "أفضل جودة متوفرة", "2160" to "4K (2160p)", "1440" to "2K (1440p)",
        "1080" to "Full HD (1080p)", "720" to "HD (720p)", "480" to "480p", "360" to "360p",
        "audio" to "صوت فقط (MP3)",
    )

    private fun color(id: Int) = ContextCompat.getColor(ctx, id)
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun setup(versionName: String) {
        b.versionPill.text = iso("v$versionName")
        b.searchBtn.setOnClickListener { actions.search() }
        b.pasteBtn.setOnClickListener { actions.paste() }
        b.updateBanner.setOnClickListener { actions.openUpdate() }
        b.clearDone.setOnClickListener { actions.clearDone() }
        b.clearHistory.setOnClickListener { actions.clearHistory() }

        b.bottomNav.setOnItemSelectedListener { item ->
            applyTab(Tab.fromMenu(item.itemId))
            true
        }

        b.trimChip.setOnClickListener { togglePanel(b.trimPanel) }
        b.subsChip.setOnClickListener { togglePanel(b.subsPanel) }
        b.subsMode.addOnButtonCheckedListener { _, _, _ -> updateSubsMode() }
        b.subsBtn.setOnClickListener {
            val v = video ?: return@setOnClickListener
            val lang = v.subtitles.getOrNull(b.subsLang.selectedItemPosition)?.code ?: return@setOnClickListener
            actions.enqueue(listOf(JobSpec(v.url, kind = "subs", lang = lang, title = v.title, thumbnail = v.thumbnail)))
        }
        b.thumbBtn.setOnClickListener {
            val v = video ?: return@setOnClickListener
            actions.enqueue(listOf(JobSpec(v.url, kind = "thumb", title = v.title, thumbnail = v.thumbnail)))
        }

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
            if (specs.isNotEmpty()) actions.enqueue(specs)
        }

        b.batchQuality.adapter = qualityAdapter()
        b.batchQuality.setSelection(presets.indexOfFirst { it.first == "1080" })
        b.batchGo.setOnClickListener { actions.enqueue(batchUrls.map { presetSpec(it, b.batchQuality) }) }
    }

    enum class Tab(val menuId: Int) {
        DOWNLOAD(R.id.nav_download), BROWSER(R.id.nav_browser), HISTORY(R.id.nav_history);

        companion object {
            fun fromMenu(id: Int) = entries.firstOrNull { it.menuId == id } ?: DOWNLOAD
        }
    }

    /** يتنادى لما يتغيّر التبويب (عشان الـ Activity تجهّز المتصفح أول مرة). */
    var onTabChanged: ((Tab) -> Unit)? = null

    var currentTab: Tab = Tab.DOWNLOAD
        private set

    /** يغيّر التبويب من الكود (يحدّث الشريط السفلي والمحتوى). */
    fun showTab(tab: Tab) {
        if (b.bottomNav.selectedItemId != tab.menuId) b.bottomNav.selectedItemId = tab.menuId // يستدعي applyTab
        applyTab(tab)
    }

    private fun applyTab(tab: Tab) {
        currentTab = tab
        b.scroll.isVisible = tab != Tab.BROWSER
        b.browserView.isVisible = tab == Tab.BROWSER
        b.downloadView.isVisible = tab == Tab.DOWNLOAD
        b.historyView.isVisible = tab == Tab.HISTORY
        b.scroll.scrollTo(0, 0)
        onTabChanged?.invoke(tab)
    }

    // ------------------------------------------------------------ المتصفح (الصفحة الأولى)
    data class Site(val name: String, val url: String, val symbol: String, val color: Int)

    val sites = listOf(
        Site("يوتيوب", "https://m.youtube.com", "▶", 0xFFFF0033.toInt()),
        Site("تيك توك", "https://www.tiktok.com", "♪", 0xFF111111.toInt()),
        Site("إنستقرام", "https://www.instagram.com", "◎", 0xFFD62976.toInt()),
        Site("X", "https://x.com", "𝕏", 0xFF000000.toInt()),
        Site("فيسبوك", "https://m.facebook.com/watch", "f", 0xFF1877F2.toInt()),
        Site("سناب", "https://www.snapchat.com/spotlight", "👻", 0xFFFFC800.toInt()),
        Site("ساوند كلاود", "https://m.soundcloud.com", "☁", 0xFFFF5500.toInt()),
        Site("ريديت", "https://www.reddit.com", "r", 0xFFFF4500.toInt()),
        Site("Google", "https://www.google.com", "G", 0xFF4285F4.toInt()),
    )

    /** يبني اختصارات المواقع. onOpen ينفتح فيه الرابط في المتصفح. */
    fun setupBrowserStart(onOpen: (String) -> Unit) {
        val grid = b.webShortcuts
        grid.removeAllViews()
        val cell = (ctx.resources.displayMetrics.widthPixels - dp(32)) / 3
        for (site in sites) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(ctx, android.R.drawable.list_selector_background)
                setOnClickListener { onOpen(site.url) }
            }
            val circle = TextView(ctx).apply {
                text = site.symbol
                textSize = 24f
                setTextColor(if (site.color == 0xFFFFC800.toInt()) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(site.color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            }
            val name = TextView(ctx).apply {
                text = site.name
                textSize = 13f
                setTextColor(color(R.color.text))
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
            box.addView(circle)
            box.addView(name)
            grid.addView(box, android.widget.GridLayout.LayoutParams().apply { width = cell })
        }
    }

    /** true = صفحة الاختصارات، false = صفحة موقع مفتوحة. */
    fun showBrowserStart(start: Boolean) {
        b.webStart.isVisible = start
        b.webContainer.isVisible = !start
        b.webNav.isVisible = !start
        b.webDownload.isVisible = !start
        if (start) {
            b.webAddress.setText("")
            b.webProgress.isVisible = false
        }
    }

    /** يغيّر شكل زر التحميل حسب الصفحة: فيديو واضح أو صفحة عادية. */
    fun setBrowserPage(url: String, isVideo: Boolean) {
        if (!b.webAddress.hasFocus()) b.webAddress.setText(url)
        b.webDownload.text = if (isVideo) "حمّل هذا الفيديو" else "حمّل من هذي الصفحة"
        b.webDownload.backgroundTintList = ColorStateList.valueOf(color(if (isVideo) R.color.accent else R.color.muted))
        if (isVideo) b.webDownload.extend() else b.webDownload.shrink()
    }

    private fun togglePanel(panel: View) {
        val open = !panel.isVisible
        b.trimPanel.isVisible = false
        b.subsPanel.isVisible = false
        panel.isVisible = open
        setChipActive(b.trimChip, b.trimPanel.isVisible)
        setChipActive(b.subsChip, b.subsPanel.isVisible || embedLang() != null)
    }

    /** لغة الترجمة اللي تنحط داخل الفيديو، أو null. */
    private fun embedLang(): String? {
        val v = video ?: return null
        if (b.subsMode.checkedButtonId != R.id.subsEmbed) return null
        return v.subtitles.getOrNull(b.subsLang.selectedItemPosition)?.code
    }

    private fun updateSubsMode() {
        when (b.subsMode.checkedButtonId) {
            R.id.subsEmbed -> {
                b.subsHint.text = "اختر الجودة من فوق، والترجمة بتنحط داخل الفيديو. تقدر تشغّلها أو تطفيها من المشغّل."
                b.subsHint.isVisible = true
                b.subsBtn.isVisible = false
            }
            R.id.subsFile -> {
                b.subsHint.text = "الترجمة بتنزل ملف لحالها (SRT)، بدون الفيديو."
                b.subsHint.isVisible = true
                b.subsBtn.isVisible = true
            }
            else -> {
                b.subsHint.isVisible = false
                b.subsBtn.isVisible = false
            }
        }
        setChipActive(b.subsChip, b.subsPanel.isVisible || embedLang() != null)
    }

    private fun setChipActive(chip: MaterialButton, active: Boolean) {
        chip.backgroundTintList = ColorStateList.valueOf(color(if (active) R.color.accent else R.color.accent_soft))
        val fg = color(if (active) android.R.color.white else R.color.accent)
        chip.setTextColor(fg)
        chip.iconTint = ColorStateList.valueOf(fg)
    }

    fun openTrim() {
        if (!b.trimPanel.isVisible) togglePanel(b.trimPanel)
    }

    fun setStatus(text: String, error: Boolean = false) {
        b.status.text = text
        b.status.isVisible = text.isNotEmpty()
        b.status.setTextColor(color(if (error) R.color.accent else R.color.muted))
    }

    fun hideResults() {
        b.singleCard.isVisible = false
        b.playlistCard.isVisible = false
        b.batchCard.isVisible = false
        b.sites.isVisible = true
    }

    // ------------------------------------------------------------ فيديو واحد
    fun showVideo(v: VideoSummary) {
        video = v
        b.sites.isVisible = false
        b.title.text = v.title
        b.subtitle.text = iso(v.subtitle)
        b.subtitle.isVisible = v.subtitle.isNotEmpty()
        b.thumb.isVisible = v.thumbnail != null
        loadImage(b.thumb, v.thumbnail)
        b.durationBadge.text = iso(Engine.formatDuration(v.duration))
        b.durationBadge.isVisible = v.duration > 0 && v.thumbnail != null
        b.trimStart.setText("")
        b.trimEnd.setText("")
        b.trimEnd.hint = Engine.formatDuration(v.duration).ifEmpty { "النهاية" }

        b.options.removeAllViews()
        var row: LinearLayout? = null
        v.options.forEachIndexed { i, opt ->
            if (i % 2 == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { if (i > 0) topMargin = dp(8) }
                }
                b.options.addView(row)
            }
            val r = row!!
            if (i % 2 == 1) r.addView(View(ctx), LinearLayout.LayoutParams(dp(8), 1))
            r.addView(qualityCard(v, opt, r))
        }
        if (v.options.size % 2 == 1) {
            row?.addView(View(ctx), LinearLayout.LayoutParams(dp(8), 1))
            row?.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        }

        b.subsBlock.isVisible = v.subtitles.isNotEmpty()
        b.noSubs.isVisible = v.subtitles.isEmpty()
        b.subsLang.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, v.subtitles.map { it.name })
        v.subtitles.indexOfFirst { it.code.startsWith("ar") }.takeIf { it >= 0 }?.let { b.subsLang.setSelection(it) }
        b.subsMode.check(R.id.subsNone)
        updateSubsMode()
        b.thumbBtn.isVisible = v.thumbnail != null
        b.trimPanel.isVisible = false
        b.subsPanel.isVisible = false
        setChipActive(b.trimChip, false)
        setChipActive(b.subsChip, false)
        setChipActive(b.thumbBtn, false)
        b.singleCard.isVisible = true
    }

    private fun qualityCard(v: VideoSummary, opt: QualityOption, parent: LinearLayout): View {
        val card = inflater.inflate(R.layout.item_quality, parent, false) as MaterialCardView
        val h = opt.id.toIntOrNull() ?: 0
        val hi = !opt.isAudio && h >= 1440
        card.findViewById<TextView>(R.id.qLabel).text = iso(
            when {
                opt.isAudio -> "صوت MP3"
                h >= 4320 -> "8K"
                h >= 2160 -> "4K"
                h >= 1440 -> "2K"
                h > 0 -> "${h}p"
                else -> opt.label
            }
        )
        val detail = when {
            opt.isAudio -> "صوت بس"
            h >= 1440 -> listOf("${h}p", opt.detail.substringAfter("· ", "")).filter { it.isNotEmpty() }.joinToString(" · ")
            h >= 1080 -> listOf("Full HD", opt.detail.substringAfter("· ", "")).filter { it.isNotEmpty() }.joinToString(" · ")
            h >= 720 -> listOf("HD", opt.detail.substringAfter("· ", "")).filter { it.isNotEmpty() }.joinToString(" · ")
            else -> opt.detail
        }
        card.findViewById<TextView>(R.id.qDetail).text = iso(detail)
        val icon = card.findViewById<ImageView>(R.id.qIcon)
        icon.setImageResource(if (opt.isAudio) R.drawable.ic_music else R.drawable.ic_movie)
        if (hi) {
            card.setCardBackgroundColor(color(R.color.accent_soft))
            card.findViewById<TextView>(R.id.qLabel).setTextColor(color(R.color.accent))
        }
        card.setOnClickListener { onQualityClicked(v, opt) }
        return card
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
            openTrim()
            setStatus(error, error = true)
            return
        }
        actions.enqueue(
            listOf(
                JobSpec(
                    url = v.url,
                    kind = if (opt.isAudio) "audio" else "video",
                    quality = if (opt.isAudio) "best" else opt.id,
                    start = start?.takeIf { it > 0 },
                    end = end,
                    title = v.title,
                    thumbnail = v.thumbnail,
                    embedSubs = if (opt.isAudio) null else embedLang(),
                )
            )
        )
    }

    /** للمعاينة: يفتح الترجمة على وضع (مع الفيديو). */
    fun previewEmbedSubs() {
        if (!b.subsPanel.isVisible) togglePanel(b.subsPanel)
        b.subsMode.check(R.id.subsEmbed)
    }

    // ------------------------------------------------------------ قائمة تشغيل وكذا رابط
    private fun qualityAdapter() =
        ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, presets.map { it.second })

    private fun presetSpec(url: String, spinner: Spinner, title: String? = null, thumb: String? = null): JobSpec {
        val id = presets[spinner.selectedItemPosition].first
        return if (id == "audio") JobSpec(url, kind = "audio", title = title, thumbnail = thumb)
        else JobSpec(url, kind = "video", quality = id, title = title, thumbnail = thumb)
    }

    fun showPlaylist(pl: PlaylistSummary) {
        playlist = pl
        b.sites.isVisible = false
        b.plTitle.text = pl.title
        b.plSub.text = iso(pl.subtitle)
        b.plList.removeAllViews()
        plChecks.clear()
        for (e in pl.entries) {
            val cb = CheckBox(ctx).apply {
                isChecked = true
                text = iso(e.title + if (e.duration > 0) "  ·  ${Engine.formatDuration(e.duration)}" else "")
                textSize = 14f
                maxLines = 2
                setTextColor(color(R.color.text))
                buttonTintList = ColorStateList.valueOf(color(R.color.accent))
                setPadding(paddingLeft, dp(6), paddingRight, dp(6))
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

    fun showBatch(urls: List<String>) {
        batchUrls = urls
        b.sites.isVisible = false
        b.batchTitle.text = "${urls.size} روابط"
        b.batchCard.isVisible = true
    }

    // ------------------------------------------------------------ التحميلات
    private fun jobMeta(j: Job): String = when (j.status) {
        JobStatus.QUEUED -> "في الانتظار..."
        JobStatus.RUNNING -> DownloadService.progressText(j.progress, j.etaSeconds)
        JobStatus.DONE -> "تم ✓  " + Engine.formatSize(j.saved?.size ?: 0)
        JobStatus.ERROR -> j.error ?: "صار خطأ."
        JobStatus.CANCELED -> "انلغى."
    }

    fun renderJobs(jobs: List<Job>) {
        b.queueHeader.isVisible = jobs.isNotEmpty()
        b.clearDone.isVisible = jobs.any { !it.active }
        val ordered = jobs.sortedByDescending { it.created }
        val ids = ordered.map { it.id }.toSet()
        jobViews.keys.filterNot { it in ids }.forEach { jobViews.remove(it) }

        val views = ordered.map { j ->
            jobViews.getOrPut(j.id) { inflater.inflate(R.layout.item_job, b.jobsList, false) }.also { bindJob(it, j) }
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
        if (thumb.tag != (j.spec.thumbnail ?: "")) {
            thumb.tag = j.spec.thumbnail ?: ""
            loadImage(thumb, j.spec.thumbnail)
        }
        v.findViewById<TextView>(R.id.jobTitle).text = j.title
        v.findViewById<TextView>(R.id.jobLabel).text = iso(j.spec.label)
        val meta = v.findViewById<TextView>(R.id.jobMeta)
        meta.text = iso(jobMeta(j))
        meta.setTextColor(
            color(when (j.status) {
                JobStatus.DONE -> R.color.ok
                JobStatus.ERROR -> R.color.accent
                else -> R.color.muted
            })
        )

        val bar = v.findViewById<ProgressBar>(R.id.jobBar)
        val running = j.status == JobStatus.RUNNING
        bar.isVisible = j.active || j.status == JobStatus.DONE
        bar.isIndeterminate = j.status == JobStatus.QUEUED || (running && (j.progress < 0 || j.progress >= 100))
        bar.progress = if (j.status == JobStatus.DONE) 100 else j.progress.toInt().coerceIn(0, 100)
        bar.progressTintList = ColorStateList.valueOf(color(if (j.status == JobStatus.DONE) R.color.ok else R.color.accent))

        val actionsBox = v.findViewById<LinearLayout>(R.id.jobActions)
        val key = j.status.name
        if (actionsBox.tag != key) {
            actionsBox.tag = key
            actionsBox.removeAllViews()
            when (j.status) {
                JobStatus.QUEUED, JobStatus.RUNNING ->
                    actionsBox.addView(smallButton("إلغاء", Kind.TONAL) { actions.cancel(j.id) })
                JobStatus.DONE -> {
                    actionsBox.addView(smallButton("إزالة", Kind.TEXT) { actions.removeJob(j.id) })
                    actionsBox.addView(smallButton("فتح", Kind.FILLED) { j.saved?.let { actions.openFile(it.uri, it.mime) } })
                }
                JobStatus.ERROR, JobStatus.CANCELED -> {
                    actionsBox.addView(smallButton("إزالة", Kind.TEXT) { actions.removeJob(j.id) })
                    actionsBox.addView(smallButton("إعادة", Kind.TONAL) {
                        actions.removeJob(j.id)
                        actions.enqueue(listOf(j.spec))
                    })
                }
            }
        }
    }

    // ------------------------------------------------------------ السجل
    fun renderHistory(items: List<HistoryItem>, now: Long = System.currentTimeMillis()) {
        val badge = b.bottomNav.getOrCreateBadge(R.id.nav_history)
        badge.isVisible = items.isNotEmpty()
        badge.number = items.size
        badge.backgroundColor = color(R.color.accent)
        b.historyEmpty.isVisible = items.isEmpty()
        b.clearHistory.isVisible = items.isNotEmpty()
        b.historyList.removeAllViews()
        for (h in items) {
            val v = inflater.inflate(R.layout.item_job, b.historyList, false)
            loadImage(v.findViewById(R.id.jobThumb), h.spec.thumbnail)
            v.findViewById<TextView>(R.id.jobTitle).text = h.title
            v.findViewById<TextView>(R.id.jobLabel).text = iso(h.label)
            v.findViewById<ProgressBar>(R.id.jobBar).isVisible = false
            v.findViewById<TextView>(R.id.jobMeta).text = iso(
                listOf(
                    DateUtils.getRelativeTimeSpanString(h.date, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                    Engine.formatSize(h.size),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
            )
            val box = v.findViewById<LinearLayout>(R.id.jobActions)
            box.addView(smallButton("حذف", Kind.TEXT) { actions.deleteHistory(h.id) })
            if (h.uri != null) {
                box.addView(smallButton("فتح", Kind.TONAL) { History.uriOf(h)?.let { actions.openFile(it, h.mime ?: "*/*") } })
            }
            box.addView(smallButton("حمّل", Kind.FILLED) {
                showTab(Tab.DOWNLOAD)
                actions.enqueue(listOf(h.spec))
            })
            b.historyList.addView(v)
        }
    }

    // ------------------------------------------------------------ أدوات
    private enum class Kind { FILLED, TONAL, TEXT }

    private fun smallButton(label: String, kind: Kind, onClick: () -> Unit): MaterialButton {
        val style = when (kind) {
            Kind.TEXT -> androidx.appcompat.R.attr.borderlessButtonStyle
            else -> com.google.android.material.R.attr.materialButtonStyle
        }
        return MaterialButton(ctx, null, style).apply {
            text = label
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            setPadding(dp(14), 0, dp(14), 0)
            when (kind) {
                Kind.TONAL -> {
                    backgroundTintList = ColorStateList.valueOf(color(R.color.accent_soft))
                    setTextColor(color(R.color.accent))
                }
                Kind.TEXT -> setTextColor(color(R.color.muted))
                Kind.FILLED -> Unit
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    companion object {
        /** يعزل الأرقام والكلمات الإنجليزية عشان ما تنقلب داخل النص العربي. */
        fun iso(text: String): String =
            Regex("""[A-Za-z0-9~][A-Za-z0-9:.%/~()–\- ]*[A-Za-z0-9%)]|[A-Za-z0-9]""")
                .replace(text) { "⁦" + it.value.replace("–", "⁠–⁠") + "⁩" }
    }
}
