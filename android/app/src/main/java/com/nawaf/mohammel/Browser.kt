package com.nawaf.mohammel

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

/**
 * المتصفح الداخلي: تفتح فيه المواقع، ولما تشغّل فيديو تضغط (حمّل) وينتقل الرابط لصفحة التحميل.
 */
class Browser(
    private val activity: AppCompatActivity,
    private val ui: MainUi,
    private val onDownload: (String) -> Unit,
) {
    private val b = ui.b
    private var web: WebView? = null

    fun setup() {
        ui.setupBrowserStart { open(it) }
        ui.showBrowserStart(true)

        b.webAddress.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                open(toUrl(b.webAddress.text?.toString().orEmpty()))
                true
            } else false
        }
        b.webHome.setOnClickListener {
            web?.stopLoading()
            ui.showBrowserStart(true)
        }
        b.webReload.setOnClickListener { web?.reload() }
        b.webBack.setOnClickListener { web?.takeIf { it.canGoBack() }?.goBack() }
        b.webForward.setOnClickListener { web?.takeIf { it.canGoForward() }?.goForward() }
        b.webDownload.setOnClickListener {
            val url = web?.url ?: return@setOnClickListener
            if (url.startsWith("http")) onDownload(url)
        }
    }

    fun open(url: String) {
        hideKeyboard()
        ui.showBrowserStart(false)
        ensureWeb().loadUrl(url)
        ui.setBrowserPage(url, isVideoPage(url))
    }

    /** زر الرجوع في الجوال: يرجع صفحة في المتصفح لو يقدر. */
    fun handleBack(): Boolean {
        val w = web ?: return false
        if (b.webStart.isVisible) return false
        if (w.canGoBack()) {
            w.goBack()
            return true
        }
        ui.showBrowserStart(true)
        return true
    }

    fun onPause() = web?.onPause()
    fun onResume() = web?.onResume()

    fun destroy() {
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): WebView {
        web?.let { return it }
        val w = WebView(activity)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // روابط مثل intent:// أو فتح تطبيق يوتيوب: نتجاهلها ونخلي المستخدم داخل المتصفح
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                b.webProgress.isVisible = true
                pageChanged(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                b.webProgress.isVisible = false
                pageChanged(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // يوتيوب وتيك توك يغيّرون الرابط بدون ما تنفتح صفحة جديدة
                pageChanged(url)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.webProgress.progress = newProgress
                b.webProgress.isVisible = newProgress < 100
            }
        }
        // روابط الملفات المباشرة (مثل ملف MP4) تروح للتحميل على طول
        w.setDownloadListener { url, _, _, _, _ -> if (url.startsWith("http")) onDownload(url) }

        b.webContainer.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        return w
    }

    private fun pageChanged(url: String) {
        if (!url.startsWith("http")) return
        ui.setBrowserPage(url, isVideoPage(url))
        b.webBack.isEnabled = web?.canGoBack() == true
        b.webForward.isEnabled = web?.canGoForward() == true
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.webAddress.windowToken, 0)
        b.webAddress.clearFocus()
    }

    companion object {
        /** يحوّل اللي كتبه المستخدم لرابط: إذا مو رابط نبحث عنه في Google. */
        fun toUrl(text: String): String {
            val t = text.trim()
            if (t.isEmpty()) return "https://www.google.com"
            if (t.startsWith("http://") || t.startsWith("https://")) return t
            if (!t.contains(' ') && Regex("""^[\w-]+(\.[\w-]+)+(/.*)?$""").matches(t)) return "https://$t"
            return "https://www.google.com/search?q=" + Uri.encode(t)
        }

        private val videoPatterns = listOf(
            Regex("""youtube\.com/(watch|shorts/|live/)"""), Regex("""youtu\.be/"""),
            Regex("""tiktok\.com/.+/video/"""), Regex("""(vm|vt)\.tiktok\.com/"""),
            Regex("""instagram\.com/(reel|reels|p|tv)/"""),
            Regex("""(x|twitter)\.com/[^/]+/status/"""),
            Regex("""facebook\.com/.*(watch|videos|reel)"""), Regex("""fb\.watch/"""),
            Regex("""snapchat\.com/(spotlight|t)/"""),
            Regex("""soundcloud\.com/[^/?]+/[^/?]+"""),
            Regex("""reddit\.com/r/[^/]+/comments/"""),
        )

        /** هل الصفحة غالباً صفحة فيديو؟ (عشان نبرز زر التحميل) */
        fun isVideoPage(url: String): Boolean = videoPatterns.any { it.containsMatchIn(url) }
    }
}
