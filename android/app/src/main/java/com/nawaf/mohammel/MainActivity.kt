package com.nawaf.mohammel

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.google.android.material.button.MaterialButton
import com.nawaf.mohammel.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var summary: VideoSummary? = null
    private var fetchJob: Job? = null
    private var pendingOption: QualityOption? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val opt = pendingOption
        pendingOption = null
        if (granted && opt != null) startDownload(opt)
        else if (!granted) setStatus("لازم تسمح بالتخزين عشان نحفظ الفيديو.", error = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.searchBtn.setOnClickListener { search() }
        b.urlInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        b.pasteBtn.setOnClickListener { pasteAndSearch() }
        b.cancelBtn.setOnClickListener { DownloadService.cancel(this) }
        b.updateBanner.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.DOWNLOAD_URL)))
        }
        b.footer.text = "النسخة ${BuildConfig.VERSION_NAME}\nحمّل المحتوى اللي تملكه أو المسموح بتحميله فقط."

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadState.status.collect { render(it) }
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
        val url = Engine.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        b.urlInput.setText(url)
        search()
    }

    private fun pasteAndSearch() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = Engine.extractUrl(text)
        if (url == null) {
            setStatus("ما فيه رابط في الحافظة. انسخ رابط الفيديو أول.", error = true)
            return
        }
        b.urlInput.setText(url)
        search()
    }

    private fun search() {
        val url = Engine.extractUrl(b.urlInput.text?.toString())
        if (url == null) {
            setStatus("الصق رابط صحيح يبدأ بـ https://", error = true)
            return
        }
        hideKeyboard()
        fetchJob?.cancel()
        b.resultCard.isVisible = false
        b.searchBtn.isEnabled = false
        setStatus("جاري البحث عن الفيديو...")
        fetchJob = lifecycleScope.launch {
            try {
                val s = Engine.fetchInfo(url)
                summary = s
                showSummary(s)
                setStatus("")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                setStatus(Engine.friendlyError(t), error = true)
            } finally {
                b.searchBtn.isEnabled = true
            }
        }
    }

    private fun showSummary(s: VideoSummary) {
        b.title.text = s.title
        b.subtitle.text = s.subtitle
        b.subtitle.isVisible = s.subtitle.isNotEmpty()
        b.thumb.isVisible = s.thumbnail != null
        s.thumbnail?.let { b.thumb.load(it) { crossfade(true) } }

        b.options.removeAllViews()
        for (opt in s.options) {
            val hi = !opt.isAudio && (opt.id.toIntOrNull() ?: 0) >= 1440
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "${opt.label}   ·   ${opt.detail}"
                isAllCaps = false
                textSize = 15f
                if (hi) setTextColor(ContextCompat.getColor(context, R.color.accent))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setOnClickListener { onOptionClicked(opt) }
            }
            b.options.addView(btn)
        }
        b.resultCard.isVisible = true
    }

    private fun onOptionClicked(opt: QualityOption) {
        if (DownloadState.status.value is DownloadStatus.Running) {
            Toast.makeText(this, "فيه تحميل شغال، انتظر لين يخلص.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingOption = opt
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownload(opt)
    }

    private fun startDownload(opt: QualityOption) {
        val s = summary ?: return
        DownloadService.start(this, s, opt)
    }

    private fun render(status: DownloadStatus) {
        when (status) {
            DownloadStatus.Idle -> b.progressCard.isVisible = false
            is DownloadStatus.Running -> {
                b.progressCard.isVisible = true
                b.progressTitle.text = status.title
                b.progressBar.isVisible = true
                if (status.progress < 0 || status.progress >= 100) {
                    b.progressBar.isIndeterminate = true
                } else {
                    b.progressBar.isIndeterminate = false
                    b.progressBar.setProgressCompat(status.progress.toInt(), true)
                }
                b.progressText.text = DownloadService.progressText(status.progress, status.etaSeconds)
                b.cancelBtn.isVisible = true
                b.openBtn.isVisible = false
            }
            is DownloadStatus.Done -> {
                b.progressCard.isVisible = true
                b.progressTitle.text = status.title
                b.progressBar.isVisible = false
                b.progressText.text = if (status.file.mime.startsWith("audio"))
                    "تم ✅ انحفظ في الموسيقى (Music/Mohammel)" else "تم ✅ انحفظ في المعرض (Movies/Mohammel)"
                b.cancelBtn.isVisible = false
                b.openBtn.isVisible = true
                b.openBtn.setOnClickListener { openFile(status.file) }
            }
            is DownloadStatus.Failed -> {
                b.progressCard.isVisible = true
                b.progressTitle.text = status.title
                b.progressBar.isVisible = false
                b.progressText.text = status.message
                b.cancelBtn.isVisible = false
                b.openBtn.isVisible = false
            }
            is DownloadStatus.Canceled -> {
                b.progressCard.isVisible = true
                b.progressTitle.text = status.title
                b.progressBar.isVisible = false
                b.progressText.text = "انلغى التحميل."
                b.cancelBtn.isVisible = false
                b.openBtn.isVisible = false
            }
        }
    }

    private fun openFile(file: SavedFile) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Throwable) {
            Toast.makeText(this, "ما فيه تطبيق يفتح هذا الملف.", Toast.LENGTH_SHORT).show()
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
}
