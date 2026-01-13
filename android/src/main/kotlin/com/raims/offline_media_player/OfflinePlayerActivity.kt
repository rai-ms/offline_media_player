package com.raims.offline_media_player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.raims.offline_media_player.R
import java.util.concurrent.TimeUnit

/**
 * Full-screen Activity for playing offline HLS content
 *
 * This Activity bypasses Flutter's PlatformView issues by running
 * video playback in a completely native Android context.
 *
 * Benefits:
 * - SurfaceView works correctly (not affected by Flutter compositing)
 * - Secure surface for DRM works properly
 * - Full hardware acceleration
 * - Better performance
 */
@UnstableApi
class OfflinePlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OfflinePlayerActivity"
        private const val EXTRA_CONTENT_ID = "content_id"
        private const val EXTRA_CONTENT_TITLE = "content_title"
        private const val EXTRA_RESUME_POSITION = "resume_position"

        const val RESULT_POSITION = "result_position"
        const val RESULT_COMPLETED = "result_completed"

        private const val CONTROLS_HIDE_DELAY_MS = 3000L
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L

        /**
         * Create intent to launch the offline player
         */
        fun createIntent(
            context: Context,
            contentId: String,
            contentTitle: String?,
            resumePositionMs: Long = 0
        ): Intent {
            return Intent(context, OfflinePlayerActivity::class.java).apply {
                putExtra(EXTRA_CONTENT_ID, contentId)
                putExtra(EXTRA_CONTENT_TITLE, contentTitle ?: "")
                putExtra(EXTRA_RESUME_POSITION, resumePositionMs)
            }
        }
    }

    // Views
    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: View
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvTitle: TextView

    // Player
    private var exoPlayer: ExoPlayer? = null
    private var contentId: String = ""
    private var contentTitle: String = ""
    private var resumePositionMs: Long = 0
    private var playbackCompleted = false

    // Handlers
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true

    // Download manager reference (shared instance)
    private var downloadManager: OfflineHlsDownloadManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "╔═══════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║       OFFLINE PLAYER ACTIVITY - CREATED                   ║")
        Log.d(TAG, "╚═══════════════════════════════════════════════════════════╝")

        // Set fullscreen and landscape
        setupFullscreen()

        // Set content view
        setContentView(R.layout.activity_offline_player)

        // Get intent extras
        contentId = intent.getStringExtra(EXTRA_CONTENT_ID) ?: ""
        contentTitle = intent.getStringExtra(EXTRA_CONTENT_TITLE) ?: ""
        resumePositionMs = intent.getLongExtra(EXTRA_RESUME_POSITION, 0)

        Log.d(TAG, "   contentId: $contentId")
        Log.d(TAG, "   contentTitle: $contentTitle")
        Log.d(TAG, "   resumePositionMs: $resumePositionMs")

        if (contentId.isEmpty()) {
            Log.e(TAG, "   ❌ No content ID provided!")
            finish()
            return
        }

        // Initialize views
        initViews()

        // Get shared download manager singleton (initialized by plugin)
        downloadManager = OfflineHlsDownloadManager.getInstance(this)

        // Initialize player
        initPlayer()

        // Start playback
        startPlayback()
    }

    private fun setupFullscreen() {
        // Force landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Hide system UI
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // Prevent screen capture for DRM

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        controlsOverlay = findViewById(R.id.controls_overlay)
        progressBar = findViewById(R.id.progress_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnBack = findViewById(R.id.btn_back)
        btnRewind = findViewById(R.id.btn_rewind)
        btnForward = findViewById(R.id.btn_forward)
        seekBar = findViewById(R.id.seek_bar)
        tvPosition = findViewById(R.id.tv_position)
        tvDuration = findViewById(R.id.tv_duration)
        tvTitle = findViewById(R.id.tv_title)

        tvTitle.text = contentTitle

        // Setup click listeners
        btnBack.setOnClickListener { onBackPressed() }

        btnPlayPause.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
            resetControlsHideTimer()
        }

        btnRewind.setOnClickListener {
            exoPlayer?.let { player ->
                val newPosition = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(newPosition)
            }
            resetControlsHideTimer()
        }

        btnForward.setOnClickListener {
            exoPlayer?.let { player ->
                val newPosition = (player.currentPosition + 10000).coerceAtMost(player.duration)
                player.seekTo(newPosition)
            }
            resetControlsHideTimer()
        }

        // Seek bar listener
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer?.let { player ->
                        val newPosition = (progress.toLong() * player.duration) / 1000
                        player.seekTo(newPosition)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                mainHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                resetControlsHideTimer()
            }
        })

        // Toggle controls on player view click
        playerView.setOnClickListener {
            toggleControls()
        }

        // Disable built-in controller (we use custom overlay)
        playerView.useController = false
    }

    private fun initPlayer() {
        Log.d(TAG, "Initializing ExoPlayer...")

        // Create renderers factory with decoder fallback
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(playerListener)
            }

        // Attach player to view
        playerView.player = exoPlayer

        Log.d(TAG, "   ✅ ExoPlayer initialized and attached to PlayerView")
    }

    private fun startPlayback() {
        val manager = downloadManager ?: return

        Log.d(TAG, "Starting playback for contentId: $contentId")

        // Check if content is downloaded
        if (!manager.isContentDownloaded(contentId)) {
            Log.e(TAG, "   ❌ Content not downloaded!")
            showError("Content not downloaded")
            return
        }

        // Get download request
        val downloadRequest = manager.getDownloadRequest(contentId)
        if (downloadRequest == null) {
            Log.e(TAG, "   ❌ Download request not found!")
            showError("Download not found")
            return
        }

        Log.d(TAG, "   Download request found:")
        Log.d(TAG, "   URI: ${downloadRequest.uri}")

        // Get cache
        val cache = manager.getDownloadCache()

        // Create cache data source factory
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Create HLS media source
        val hlsMediaSourceFactory = HlsMediaSource.Factory(cacheDataSourceFactory)
            .setAllowChunklessPreparation(true)

        val mediaItem = downloadRequest.toMediaItem()
        val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

        // Set media source and prepare
        exoPlayer?.apply {
            setMediaSource(mediaSource)
            prepare()

            if (resumePositionMs > 0) {
                seekTo(resumePositionMs)
            }

            playWhenReady = true
        }

        // Start position updates
        startPositionUpdates()

        // Auto-hide controls after delay
        resetControlsHideTimer()

        Log.d(TAG, "   ✅ Playback started")
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            Log.d(TAG, "onPlaybackStateChanged: $stateName")

            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    progressBar.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    progressBar.visibility = View.GONE
                    updateDuration()
                }
                Player.STATE_ENDED -> {
                    progressBar.visibility = View.GONE
                    playbackCompleted = true
                    showControls()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            updatePlayPauseButton(isPlaying)

            if (!isPlaying && exoPlayer?.playbackState != Player.STATE_BUFFERING) {
                showControls()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(TAG, "📐 onVideoSizeChanged: ${videoSize.width}x${videoSize.height}")
            if (videoSize.width > 0 && videoSize.height > 0) {
                Log.d(TAG, "   ✅ VIDEO IS RENDERING!")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "❌ Playback error: ${error.message}", error)
            progressBar.visibility = View.GONE
            showError(error.message ?: "Playback error")
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun updateDuration() {
        exoPlayer?.let { player ->
            if (player.duration > 0) {
                tvDuration.text = formatTime(player.duration)
            }
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
            resetControlsHideTimer()
        }
    }

    private fun showControls() {
        controlsOverlay.visibility = View.VISIBLE
        controlsVisible = true
    }

    private fun hideControls() {
        if (exoPlayer?.isPlaying == true) {
            controlsOverlay.visibility = View.GONE
            controlsVisible = false
        }
    }

    private fun resetControlsHideTimer() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                val position = player.currentPosition
                val duration = player.duration

                if (duration > 0) {
                    tvPosition.text = formatTime(position)
                    seekBar.progress = ((position * 1000) / duration).toInt()
                }
            }
            mainHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    private fun startPositionUpdates() {
        mainHandler.post(positionUpdateRunnable)
    }

    private fun stopPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdateRunnable)
    }

    private fun formatTime(timeMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(timeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeMs) % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun showError(message: String) {
        // Show error toast or dialog
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithResult()
    }

    private fun finishWithResult() {
        val resultIntent = Intent().apply {
            putExtra(RESULT_POSITION, exoPlayer?.currentPosition ?: 0L)
            putExtra(RESULT_COMPLETED, playbackCompleted)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        setupFullscreen()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - releasing player")
        stopPositionUpdates()
        mainHandler.removeCallbacks(hideControlsRunnable)
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
