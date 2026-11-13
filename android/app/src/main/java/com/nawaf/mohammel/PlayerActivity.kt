package com.nawaf.mohammel

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.load
import com.nawaf.mohammel.databinding.ActivityPlayerBinding

/**
 * المشغّل الداخلي: يشغّل الفيديو والصوت داخل التطبيق، ويعرض الصور.
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    private var speedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(b.topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + v.paddingStart.coerceAtMost(0), bars.top, bars.right, 0)
            insets
        }

        val uri = intent.data ?: run { finish(); return }
        val mime = intent.type ?: "video/*"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        b.title.text = title
        b.back.setOnClickListener { finish() }
        b.share.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "مشاركة",
                )
            )
        }

        if (mime.startsWith("image")) {
            b.image.isVisible = true
            b.playerView.isVisible = false
            b.speed.isVisible = false
            b.image.load(uri)
            return
        }

        val p = ExoPlayer.Builder(this).build()
        player = p
        b.playerView.player = p
        b.playerView.setShowSubtitleButton(true)
        b.playerView.setShowNextButton(false)
        b.playerView.setShowPreviousButton(false)
        // الصوت: نخلي أدوات التحكم ظاهرة دايم
        if (mime.startsWith("audio")) {
            b.playerView.controllerShowTimeoutMs = 0
            b.playerView.controllerHideOnTouch = false
            b.audioArt.isVisible = true
        }
        p.setMediaItem(
            MediaItem.Builder().setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        )
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // ما تطفي الشاشة وقت التشغيل
                b.playerView.keepScreenOn = isPlaying
            }
        })
        savedInstanceState?.getLong("pos")?.let { p.seekTo(it) }
        p.prepare()
        p.playWhenReady = true

        b.speed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val s = speeds[speedIndex]
            p.playbackParameters = PlaybackParameters(s)
            b.speed.text = if (s == 1f) "1x" else "${s}x".replace(".0x", "x")
        }
        b.playerView.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { v ->
                if (!mime.startsWith("audio")) b.topBar.isVisible = v == android.view.View.VISIBLE
            }
        )
        applyFullscreen(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFullscreen(newConfig)
    }

    /** بالعرض: ملء الشاشة بدون أشرطة النظام. */
    private fun applyFullscreen(c: Configuration) {
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, uri: Uri, mime: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setDataAndType(uri, mime)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        fun canPlay(mime: String) = mime.startsWith("video") || mime.startsWith("audio") || mime.startsWith("image")
    }
}
