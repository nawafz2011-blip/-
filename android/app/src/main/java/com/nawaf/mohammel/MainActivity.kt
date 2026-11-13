package com.nawaf.mohammel

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nawaf.mohammel.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), UiActions {
    private lateinit var ui: MainUi
    private lateinit var browser: Browser
    private val b: ActivityMainBinding get() = ui.b
    private var fetchJob: CoJob? = null
    private var pendingSpecs: List<JobSpec>? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val specs = pendingSpecs
        pendingSpecs = null
        if (granted && specs != null) startDownloads(specs)
        else if (!granted) ui.setStatus("لازم تسمح بالتخزين عشان نحفظ الملفات.", error = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // أندرويد 15 يخلي التطبيق يرسم تحت شريط الحالة (الساعة والبطارية).
        // نترك مسافة بقدر الشريط فوق، وبقدر الكيبورد تحت لما ينفتح.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, ime.bottom)
            binding.bottomNav.isVisible = ime.bottom == 0
            insets
        }
        ui = MainUi(this, binding, this) { view, url ->
            if (url != null) view.load(url) { crossfade(true) } else view.setImageDrawable(null)
        }
        ui.setup(BuildConfig.VERSION_NAME)
        ui.alwaysRemoveMusic = Azl.alwaysRemove(this)

        browser = Browser(this, ui) { url ->
            // زر (حمّل) في المتصفح: ننقل الرابط لصفحة التحميل ونبحث عنه
            ui.showTab(MainUi.Tab.DOWNLOAD)
            b.urlInput.setText(url)
            search()
        }
        browser.setup()

        // زر الرجوع: يرجع صفحة في المتصفح، أو يرجع لتبويب التحميل
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ui.currentTab == MainUi.Tab.BROWSER && browser.handleBack() -> Unit
                    ui.currentTab != MainUi.Tab.DOWNLOAD -> ui.showTab(MainUi.Tab.DOWNLOAD)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        if (savedInstanceState == null) showWelcome()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { JobQueue.jobs.collect { ui.renderJobs(it) } }
                launch { History.items.collect { ui.renderHistory(it) } }
            }
        }
        lifecycleScope.launch {
            if (UpdateChecker.newerVersionAvailable()) b.updateBanner.isVisible = true
        }
        lifecycleScope.launch {
            if (Engine.initError == null && !Engine.isReady) {
                ui.setStatus("أول تشغيل: جاري تجهيز التطبيق... (ثواني)")
                runCatching { Engine.awaitReady() }
                if (b.status.text.startsWith("أول تشغيل")) ui.setStatus("")
            }
            Engine.initError?.let { ui.setStatus("صار خطأ في تجهيز التطبيق. سكّره وافتحه مرة ثانية.", error = true) }
        }

        handleShare(intent)
    }

    /** رسالة الترحيب كل ما ينفتح التطبيق. */
    private fun showWelcome() {
        val view = layoutInflater.inflate(R.layout.dialog_welcome, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<android.view.View>(R.id.welcomeOk).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    override fun onPause() {
        browser.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
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
        ui.showTab(MainUi.Tab.DOWNLOAD)
        b.urlInput.setText(urls.joinToString("\n"))
        search()
    }

    // ------------------------------------------------------------ UiActions
    override fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val urls = Engine.extractUrls(text)
        if (urls.isEmpty()) {
            ui.setStatus("ما فيه رابط في الحافظة. انسخ رابط الفيديو أول.", error = true)
            return
        }
        b.urlInput.setText(urls.joinToString("\n"))
        search()
    }

    override fun search() {
        val urls = Engine.extractUrls(b.urlInput.text?.toString())
        hideKeyboard()
        fetchJob?.cancel()
        ui.hideResults()
        if (urls.isEmpty()) {
            ui.setStatus("الصق رابط صحيح يبدأ بـ https://", error = true)
            return
        }
        if (urls.size > 1) {
            ui.showBatch(urls)
            ui.setStatus("")
            return
        }
        b.searchBtn.isEnabled = false
        ui.setStatus("جاري البحث...")
        fetchJob = lifecycleScope.launch {
            try {
                when (val r = Engine.fetch(urls[0])) {
                    is VideoSummary -> ui.showVideo(r)
                    is PlaylistSummary -> ui.showPlaylist(r)
                }
                ui.setStatus("")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                ui.setStatus(Engine.friendlyError(t), error = true)
            } finally {
                b.searchBtn.isEnabled = true
            }
        }
    }

    override fun enqueue(specs: List<JobSpec>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSpecs = specs
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownloads(specs)
    }

    private fun startDownloads(specs: List<JobSpec>) {
        DownloadService.enqueue(this, specs)
        ui.setStatus(if (specs.size > 1) "انضاف ${specs.size} تحميل للقائمة." else "")
        b.scroll.post { b.scroll.smoothScrollTo(0, b.queueHeader.top) }
    }

    override fun cancel(id: String) = DownloadService.cancel(this, id)

    override fun removeJob(id: String) = JobQueue.remove(id)

    override fun clearDone() = JobQueue.clearFinished()

    override fun openFile(uri: Uri, mime: String) {
        // الفيديو والصوت والصور تنفتح داخل التطبيق
        if (PlayerActivity.canPlay(mime)) {
            startActivity(PlayerActivity.intent(this, uri, mime, displayName(uri) ?: ""))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Throwable) {
            Toast.makeText(this, "ما قدرنا نفتح الملف. يمكن انحذف، أو ما فيه تطبيق يفتحه.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------ الأدوات (ضغط، MP3)
    private var pendingTool: String? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = pendingTool
        pendingTool = null
        if (uri == null || kind == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
            // بعض التطبيقات ما تسمح، نكمل عادي
        }
        startTool(kind, uri, displayName(uri) ?: "فيديو")
    }

    override fun pickForTool(kind: String) {
        pendingTool = kind
        pickVideo.launch(arrayOf("video/*"))
    }

    override fun fileOptions(uri: Uri, mime: String, title: String) {
        val video = mime.startsWith("video")
        val items = mutableListOf("▶  تشغيل", "📤  مشاركة")
        if (video) {
            items += "🗜️  ضغط الفيديو"
            items += "🎵  تحويل إلى MP3"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openFile(uri, mime)
                    1 -> startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "مشاركة",
                        )
                    )
                    2 -> startTool("compress", uri, title)
                    3 -> startTool("tomp3", uri, title)
                }
            }
            .show()
    }

    private fun startTool(kind: String, uri: Uri, title: String) {
        if (kind == "tomp3") {
            enqueue(listOf(JobSpec(uri.toString(), kind = "tomp3", title = title)))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("🗜️ ضغط الفيديو")
            .setItems(
                arrayOf(
                    "حجم أصغر، وجودة جيدة (720p)",
                    "حجم أصغر بكثير (480p)، مناسب للواتساب",
                )
            ) { _, which ->
                val q = if (which == 1) "tiny" else "small"
                enqueue(listOf(JobSpec(uri.toString(), kind = "compress", quality = q, title = title)))
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    override fun clearHistory() {
        AlertDialog.Builder(this)
            .setMessage("متأكد تبي تمسح السجل كامل؟")
            .setPositiveButton("امسح") { _, _ -> History.clear() }
            .setNegativeButton("لا", null)
            .show()
    }

    override fun deleteHistory(id: String) = History.delete(id)

    // ------------------------------------------------------------ إزالة الموسيقى (عزل)
    override fun chooseQuality(spec: JobSpec) {
        if (Azl.alwaysRemove(this)) {
            removeMusic(spec.url)
            return
        }
        showMusicSheet(spec)
    }

    override fun musicFromLink() {
        val url = Engine.extractUrls(b.urlInput.text?.toString()).firstOrNull()
        if (url == null) {
            ui.setStatus("الصق رابط المقطع أولًا، ثم اضغط (إزالة الموسيقى).", error = true)
            return
        }
        removeMusic(url)
    }

    override fun setAlwaysRemoveMusic(on: Boolean) {
        Azl.setAlwaysRemove(this, on)
        ui.alwaysRemoveMusic = on
        Toast.makeText(
            this,
            if (on) "تم: كل مقطع سيُرسل إلى «عزل» لإزالة الموسيقى." else "تم: سنسألك قبل كل تحميل.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** السؤال بعد اختيار الجودة: نزيل الموسيقى أو نحمّل المقطع كما هو. */
    private fun showMusicSheet(spec: JobSpec) {
        val view = layoutInflater.inflate(R.layout.sheet_music, null)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(view)
        sheet.window?.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        view.findViewById<android.view.View>(R.id.sheetYes).setOnClickListener {
            sheet.dismiss()
            removeMusic(spec.url)
        }
        view.findViewById<android.view.View>(R.id.sheetNo).setOnClickListener {
            sheet.dismiss()
            enqueue(listOf(spec))
        }
        view.findViewById<android.view.View>(R.id.sheetNote).isVisible = !Azl.installed(this)
        sheet.show()
    }

    /** يرسل الرابط لـ «عزل». إذا مو مثبت يطلب تحميله. */
    private fun removeMusic(url: String) {
        if (!Azl.installed(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("تطبيق «عزل» غير مثبّت")
                .setMessage("لإزالة الموسيقى تحتاج تطبيق «عزل». حمِّله وثبِّته، ثم ارجع واضغط الزر مرة أخرى.")
                .setPositiveButton("حمِّل «عزل»") { _, _ -> Azl.openDownloadPage(this) }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }
        if (Azl.countAndShouldTip(this)) {
            // المستخدم يستخدمها كثير: نعلّمه الاختصار
            MaterialAlertDialogBuilder(this)
                .setTitle("💡 اختصار")
                .setMessage("لاحظنا أنك تزيل الموسيقى كثيرًا.\n\nفي المرة القادمة يمكنك فتح تطبيق «عزل» مباشرة ولصق الرابط فيه، دون المرور على «محمّل».")
                .setPositiveButton("حسنًا، تابِع") { _, _ -> Azl.send(this, url) }
                .setCancelable(false)
                .show()
            return
        }
        Azl.send(this, url)
    }

    override fun openUpdate() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.DOWNLOAD_URL)))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.urlInput.windowToken, 0)
        b.urlInput.clearFocus()
    }
}
