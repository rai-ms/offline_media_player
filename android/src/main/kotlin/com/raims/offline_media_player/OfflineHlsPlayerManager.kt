package com.raims.offline_media_player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView

/**
 * Offline HLS Player Manager
 *
 * Manages playback of downloaded HLS + DRM content using ExoPlayer.
 *
 * CRITICAL DESIGN DECISIONS FOR DRM OFFLINE PLAYBACK:
 * 1. ExoPlayer is a SINGLETON - created once, reused for all playback
 * 2. Decoder fallback ENABLED - prevents vendor decoder bugs causing black screen
 * 3. PlayerView with SurfaceView MUST be attached BEFORE prepare() is called
 * 4. SurfaceView MUST be marked as SECURE for Widevine L1
 * 5. Never recreate ExoPlayer during playback lifecycle
 *
 * If audio plays but video is black, the issue is ALWAYS:
 * - Secure decoder output not bound to a valid secure surface
 */
@UnstableApi
class OfflineHlsPlayerManager(
    private val context: Context,
    private val downloadManager: OfflineHlsDownloadManager,
    private val playbackCallback: (Map<String, Any?>) -> Unit
) {

    companion object {
        private const val TAG = "OfflineHlsPlayerMgr"
        private const val POSITION_UPDATE_INTERVAL_MS = 1000L
    }

    // SINGLETON ExoPlayer - created once, never recreated
    // CRITICAL: Enable decoder fallback for DRM playback
    private val exoPlayer: ExoPlayer by lazy {
        Log.d(TAG, "=== Creating SINGLETON ExoPlayer ===")

        // Create renderers factory with decoder fallback ENABLED
        // This prevents HEVC + DRM failures and vendor decoder bugs
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)  // 🔥 CRITICAL FOR DRM

        ExoPlayer.Builder(context, renderersFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(playerListener)
                Log.d(TAG, "✅ ExoPlayer singleton created")
                Log.d(TAG, "   decoderFallback=ENABLED")
                Log.d(TAG, "   handleAudioBecomingNoisy=true")
            }
    }

    private var playerView: PlayerView? = null
    private var currentContentId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlayerViewAttached = false
    private var isSurfaceSecure = false

    // Pending playback - used when playOffline is called before PlayerView is attached
    private var pendingMediaSource: MediaSource? = null
    private var pendingResumePositionMs: Long = 0
    private var isPendingPlayback = false

    // Fail-fast video rendering check
    // If video size remains 0x0 after playback starts + timeout, video is NOT rendering
    private var videoRenderingCheckStarted = false
    private var hasReceivedVideoSize = false
    private val VIDEO_RENDER_CHECK_DELAY_MS = 3000L  // Wait 3 seconds to detect video rendering

    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            if (exoPlayer.isPlaying) {
                sendPlaybackEvent(
                    "position",
                    mapOf(
                        "positionMs" to exoPlayer.currentPosition.toInt(),
                        "durationMs" to exoPlayer.duration.toInt()
                    )
                )
            }
            mainHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.d(TAG, "onPlaybackStateChanged: $stateName")

            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    sendPlaybackEvent("buffering", mapOf("isBuffering" to true))
                }
                Player.STATE_READY -> {
                    Log.d(TAG, "✅ Player STATE_READY")
                    Log.d(TAG, "   Video should now be visible on secure surface")
                    Log.d(TAG, "   isPlayerViewAttached=$isPlayerViewAttached")
                    Log.d(TAG, "   isSurfaceSecure=$isSurfaceSecure")
                    sendPlaybackEvent("playing", mapOf("isBuffering" to false))

                    // Start video rendering check (fail-fast mechanism)
                    startVideoRenderingCheck()
                }
                Player.STATE_ENDED -> {
                    sendPlaybackEvent("ended", null)
                    stopPositionUpdates()
                    cancelVideoRenderingCheck()
                }
                Player.STATE_IDLE -> {
                    // Player is idle
                    cancelVideoRenderingCheck()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
            if (isPlaying) {
                sendPlaybackEvent("playing", null)
                startPositionUpdates()
            } else {
                val state = exoPlayer.playbackState
                if (state != Player.STATE_BUFFERING && state != Player.STATE_ENDED) {
                    sendPlaybackEvent("paused", null)
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(TAG, "📐 onVideoSizeChanged: ${videoSize.width}x${videoSize.height}")

            if (videoSize.width > 0 && videoSize.height > 0) {
                Log.d(TAG, "   ✅ VIDEO IS RENDERING - Widevine successfully bound to secure surface")
                hasReceivedVideoSize = true
                cancelVideoRenderingCheck()
            } else {
                Log.w(TAG, "   ⚠️ Video size is 0x0 - video may not be rendering")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "❌ Playback error: ${error.message}", error)
            Log.e(TAG, "   errorCode: ${error.errorCode}")
            Log.e(TAG, "   errorCodeName: ${error.errorCodeName}")

            // Check for DRM-related errors
            val isDrmError = error.errorCode in listOf(
                PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
                PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
                PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
                PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION
            )

            if (isDrmError) {
                Log.e(TAG, "   🔐 DRM ERROR DETECTED - Device may not support offline DRM playback")
            }

            sendPlaybackEvent(
                "error",
                mapOf(
                    "message" to (error.message ?: "Playback error"),
                    "code" to error.errorCode,
                    "isDrmError" to isDrmError
                )
            )
            stopPositionUpdates()
            cancelVideoRenderingCheck()
        }
    }

    /**
     * Start a delayed check to verify video is actually rendering
     *
     * FAIL-FAST MECHANISM:
     * If audio is playing but no video size is reported after 3 seconds,
     * Widevine is blocking video output because the device cannot provide
     * a secure video path. We stop playback and notify Flutter.
     */
    private fun startVideoRenderingCheck() {
        if (videoRenderingCheckStarted) return

        videoRenderingCheckStarted = true
        hasReceivedVideoSize = false

        Log.d(TAG, "")
        Log.d(TAG, "🔍 Starting video rendering check (fail-fast mechanism)")
        Log.d(TAG, "   Will verify video is rendering in ${VIDEO_RENDER_CHECK_DELAY_MS}ms...")

        mainHandler.postDelayed(videoRenderingCheckRunnable, VIDEO_RENDER_CHECK_DELAY_MS)
    }

    private fun cancelVideoRenderingCheck() {
        if (videoRenderingCheckStarted) {
            mainHandler.removeCallbacks(videoRenderingCheckRunnable)
            videoRenderingCheckStarted = false
        }
    }

    private val videoRenderingCheckRunnable = Runnable {
        Log.d(TAG, "")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "🔍 VIDEO RENDERING CHECK RESULT")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")

        val currentVideoSize = exoPlayer.videoSize
        val isPlaying = exoPlayer.isPlaying
        val isPlayingAudio = exoPlayer.playbackState == Player.STATE_READY

        Log.d(TAG, "   hasReceivedVideoSize: $hasReceivedVideoSize")
        Log.d(TAG, "   currentVideoSize: ${currentVideoSize.width}x${currentVideoSize.height}")
        Log.d(TAG, "   isPlaying: $isPlaying")
        Log.d(TAG, "   playbackState: ${exoPlayer.playbackState}")

        if (hasReceivedVideoSize || (currentVideoSize.width > 0 && currentVideoSize.height > 0)) {
            Log.d(TAG, "   ✅ VIDEO IS RENDERING CORRECTLY")
            Log.d(TAG, "   Widevine DRM successfully bound to secure surface")
        } else if (isPlayingAudio) {
            // FAIL-FAST: Audio is playing but video is not rendering
            // This is Widevine intentionally blocking video output
            Log.e(TAG, "   ❌ FAIL-FAST: VIDEO NOT RENDERING!")
            Log.e(TAG, "   Audio is playing but video size is 0x0")
            Log.e(TAG, "   This means Widevine is blocking video output")
            Log.e(TAG, "   The device cannot provide a secure video path for DRM content")
            Log.e(TAG, "")
            Log.e(TAG, "   STOPPING PLAYBACK - Device is not eligible for offline DRM")

            // Stop playback and notify Flutter
            stop()

            sendPlaybackEvent(
                "videoNotRendering",
                mapOf(
                    "message" to "Video cannot be displayed on this device. " +
                            "The device does not support the secure video path required for offline DRM content.",
                    "reason" to "WIDEVINE_SECURE_OUTPUT_BLOCKED",
                    "isDeviceIneligible" to true
                )
            )
        }

        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "")

        videoRenderingCheckStarted = false
    }

    /**
     * Play downloaded content offline
     *
     * CRITICAL PLAYBACK ORDER FOR DRM:
     * 1. Ensure PlayerView with SECURE SurfaceView is attached
     * 2. Set media source
     * 3. Prepare (decoder locks to surface at this point)
     * 4. Seek to position
     * 5. Play
     *
     * @param contentId The content ID to play
     * @param resumePositionMs Position to resume from (0 for start)
     * @return true if playback started successfully
     */
    fun playOffline(contentId: String, resumePositionMs: Long): Boolean {
        return try {
            Log.d(TAG, "")
            Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
            Log.d(TAG, "║         STARTING OFFLINE DRM HLS PLAYBACK                ║")
            Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
            Log.d(TAG, "   contentId: $contentId")
            Log.d(TAG, "   resumePositionMs: $resumePositionMs")
            Log.d(TAG, "   Player singleton reused: true")

            // Check if content is downloaded
            if (!downloadManager.isContentDownloaded(contentId)) {
                Log.e(TAG, "❌ Content not downloaded: $contentId")
                sendPlaybackEvent("error", mapOf("message" to "Content not downloaded"))
                return false
            }

            // Get the download request
            val downloadRequest = downloadManager.getDownloadRequest(contentId)
            if (downloadRequest == null) {
                Log.e(TAG, "❌ No download request found for: $contentId")
                sendPlaybackEvent("error", mapOf("message" to "Download request not found"))
                return false
            }

            Log.d(TAG, "")
            Log.d(TAG, "📦 Download request found:")
            Log.d(TAG, "   ID: ${downloadRequest.id}")
            Log.d(TAG, "   URI: ${downloadRequest.uri}")
            Log.d(TAG, "   MIME: ${downloadRequest.mimeType}")

            // Stop any current playback (but don't release player - it's a singleton)
            exoPlayer.stop()
            exoPlayer.clearMediaItems()

            // Get the cache
            val cache = downloadManager.getDownloadCache()
            Log.d(TAG, "   Cache: ${cache.cacheSpace} bytes used")

            // Create cache data source factory for offline playback
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(null) // No upstream - offline only
                .setCacheWriteDataSinkFactory(null) // No writing - already cached
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            // Create HLS media source factory
            val hlsMediaSourceFactory = HlsMediaSource.Factory(cacheDataSourceFactory)
                .setAllowChunklessPreparation(true)

            // Build media item from download request
            val mediaItem = downloadRequest.toMediaItem()

            Log.d(TAG, "")
            Log.d(TAG, "🎬 MediaItem created:")
            Log.d(TAG, "   MediaId: ${mediaItem.mediaId}")
            Log.d(TAG, "   URI: ${mediaItem.localConfiguration?.uri}")

            // Create media source
            val mediaSource = hlsMediaSourceFactory.createMediaSource(mediaItem)

            // ═══════════════════════════════════════════════════════════════
            // CRITICAL: CHECK IF SURFACE IS ATTACHED BEFORE PREPARE
            // For DRM, the secure decoder locks to the surface at prepare() time
            // If no surface, we MUST wait for it to be attached
            // ═══════════════════════════════════════════════════════════════
            Log.d(TAG, "")
            Log.d(TAG, "🔐 Surface attachment check (CRITICAL FOR DRM):")
            Log.d(TAG, "   PlayerView attached: ${playerView != null}")
            Log.d(TAG, "   isPlayerViewAttached: $isPlayerViewAttached")
            Log.d(TAG, "   isSurfaceSecure: $isSurfaceSecure")

            val currentView = playerView
            if (currentView != null) {
                // PlayerView is available - proceed with playback immediately
                Log.d(TAG, "   ✅ PlayerView available - starting playback NOW")
                startPlaybackWithView(currentView, mediaSource, resumePositionMs)
            } else {
                // PlayerView NOT available yet - store pending playback
                Log.w(TAG, "   ⏳ PlayerView not available yet - deferring playback")
                Log.w(TAG, "      Playback will start when PlayerView is attached")
                pendingMediaSource = mediaSource
                pendingResumePositionMs = resumePositionMs
                isPendingPlayback = true
            }

            currentContentId = contentId
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start offline playback", e)
            sendPlaybackEvent("error", mapOf("message" to (e.message ?: "Failed to start playback")))
            false
        }
    }

    /**
     * Start playback with an attached PlayerView
     * This is the actual playback initiation that requires a valid surface
     */
    private fun startPlaybackWithView(view: PlayerView, mediaSource: MediaSource, resumePositionMs: Long) {
        Log.d(TAG, "")
        Log.d(TAG, "▶️ Starting playback with attached PlayerView...")

        // Attach player to view BEFORE prepare
        view.player = exoPlayer
        isPlayerViewAttached = true

        // Verify and set secure surface
        verifySurfaceConfiguration(view)

        // Set media source and prepare
        Log.d(TAG, "   Setting media source and preparing...")
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()

        // Seek to resume position
        if (resumePositionMs > 0) {
            Log.d(TAG, "   Seeking to: ${resumePositionMs}ms")
            exoPlayer.seekTo(resumePositionMs)
        }

        // Start playback
        exoPlayer.playWhenReady = true
        startPositionUpdates()

        Log.d(TAG, "")
        Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║         PLAYBACK STARTED WITH SURFACE                    ║")
        Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
        Log.d(TAG, "   PlayerView attached: $isPlayerViewAttached")
        Log.d(TAG, "   Surface secure: $isSurfaceSecure")
        Log.d(TAG, "   playWhenReady: true")
        Log.d(TAG, "")
    }

    /**
     * Recursively find a SurfaceView in a view hierarchy
     */
    private fun findSurfaceViewRecursively(view: View): SurfaceView? {
        if (view is SurfaceView) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findSurfaceViewRecursively(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * Verify the SurfaceView is properly configured for secure DRM playback
     */
    private fun verifySurfaceConfiguration(view: PlayerView) {
        // Find SurfaceView recursively
        val surfaceView = findSurfaceViewRecursively(view)

        if (surfaceView != null) {
            Log.d(TAG, "   🔍 SurfaceView found (recursive search):")
            Log.d(TAG, "      class: ${surfaceView.javaClass.name}")
            Log.d(TAG, "      holder.isCreating: ${surfaceView.holder.isCreating}")
            Log.d(TAG, "      holder.surface: ${surfaceView.holder.surface}")
            Log.d(TAG, "      holder.surface.isValid: ${surfaceView.holder.surface?.isValid}")

            // Ensure surface is marked as secure for DRM
            surfaceView.setSecure(true)
            isSurfaceSecure = true
            Log.d(TAG, "      setSecure(true) called ✅")
        } else {
            Log.e(TAG, "   ❌ No SurfaceView found in PlayerView hierarchy!")
            Log.e(TAG, "      This will cause DRM playback to fail")
            isSurfaceSecure = false
        }
    }

    /**
     * Pause current playback
     */
    fun pause() {
        Log.d(TAG, "pause()")
        exoPlayer.pause()
    }

    /**
     * Resume current playback
     */
    fun resume() {
        Log.d(TAG, "resume()")
        exoPlayer.play()
    }

    /**
     * Stop current playback (does NOT release the singleton player)
     */
    fun stop() {
        Log.d(TAG, "stop()")
        stopPositionUpdates()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentContentId = null
    }

    /**
     * Seek to position
     */
    fun seekTo(positionMs: Long) {
        Log.d(TAG, "seekTo($positionMs)")
        exoPlayer.seekTo(positionMs)
    }

    /**
     * Get current playback position
     */
    fun getCurrentPosition(): Long {
        return exoPlayer.currentPosition
    }

    /**
     * Get total duration
     */
    fun getDuration(): Long {
        return exoPlayer.duration
    }

    /**
     * Check if player is currently playing
     */
    fun isPlaying(): Boolean {
        return exoPlayer.isPlaying
    }

    /**
     * Release the player completely (only call on app shutdown)
     */
    fun release() {
        Log.d(TAG, "release() - releasing singleton player")
        stopPositionUpdates()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
        currentContentId = null
        isPlayerViewAttached = false
        isSurfaceSecure = false
    }

    private fun startPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdateRunnable)
        mainHandler.post(positionUpdateRunnable)
    }

    private fun stopPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdateRunnable)
    }

    private fun sendPlaybackEvent(type: String, extras: Map<String, Any?>?) {
        val event = mutableMapOf<String, Any?>(
            "type" to type,
            "contentId" to currentContentId
        )
        extras?.let { event.putAll(it) }

        mainHandler.post {
            playbackCallback(event)
        }
    }

    // ============================================================
    // PLAYER VIEW ATTACHMENT (for PlatformView integration)
    // ============================================================

    /**
     * Attach a PlayerView to display the video
     * Called by OfflineHlsPlayerView when it's created
     *
     * CRITICAL: This MUST happen BEFORE prepare() for DRM content
     * The secure decoder locks to the surface at prepare() time
     *
     * If there's pending playback, it will be started immediately
     */
    fun attachPlayerView(view: PlayerView) {
        Log.d(TAG, "")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "📺 attachPlayerView called")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "   Player singleton exists: true")
        Log.d(TAG, "   Player playbackState: ${exoPlayer.playbackState}")
        Log.d(TAG, "   Player isPlaying: ${exoPlayer.isPlaying}")
        Log.d(TAG, "   Pending playback: $isPendingPlayback")

        playerView = view

        // Check if there's pending playback waiting for this view
        if (isPendingPlayback && pendingMediaSource != null) {
            Log.d(TAG, "   ✅ Pending playback found - starting NOW with attached surface")
            val mediaSource = pendingMediaSource!!
            val resumePositionMs = pendingResumePositionMs

            // Clear pending state
            pendingMediaSource = null
            pendingResumePositionMs = 0
            isPendingPlayback = false

            // Start playback with the newly attached view
            startPlaybackWithView(view, mediaSource, resumePositionMs)
        } else {
            // No pending playback - just attach the view
            Log.d(TAG, "   No pending playback - just attaching view")

            // Verify secure surface configuration
            verifySurfaceConfiguration(view)

            // Attach the player to the view
            view.player = exoPlayer
            isPlayerViewAttached = true

            Log.d(TAG, "   surfaceAttached ✅")
            Log.d(TAG, "   secureSurface=$isSurfaceSecure")
        }

        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "")
    }

    /**
     * Detach the PlayerView
     * Called when OfflineHlsPlayerView is disposed
     */
    fun detachPlayerView() {
        Log.d(TAG, "detachPlayerView()")
        playerView?.player = null
        playerView = null
        isPlayerViewAttached = false
        isSurfaceSecure = false
    }

    /**
     * Get the current ExoPlayer instance
     */
    fun getPlayer(): ExoPlayer = exoPlayer
}
