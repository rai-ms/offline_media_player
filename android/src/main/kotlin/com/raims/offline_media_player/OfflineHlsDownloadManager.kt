package com.raims.offline_media_player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Offline HLS Download Manager - FIXED VERSION
 *
 * CRITICAL FIXES APPLIED:
 * 1. ✅ MIME Type set to APPLICATION_M3U8
 * 2. ✅ Auth headers passed via DataSourceFactory
 * 3. ✅ CacheDataSource for proper segment caching
 * 4. ✅ Comprehensive debug logging
 * 5. ✅ Dedicated offline cache (not shared with streaming)
 * 6. ✅ Foreground service for Android 8+
 */
@UnstableApi
class OfflineHlsDownloadManager(
    private val context: Context,
    private val userId: String,
    private val progressCallback: (Map<String, Any?>) -> Unit
) {

    companion object {
        private const val TAG = "🔔OfflineHlsDownloadMgr"
        private const val DOWNLOAD_DIR = "offline_hls"
        private const val METADATA_PREFS = "offline_hls_metadata"
        private const val LICENSE_PREFS = "offline_hls_licenses"
        private const val USER_ID_PREFS = "offline_hls_user_prefs"
        private const val USER_ID_KEY = "current_user_id"
        private const val MAX_PARALLEL_DOWNLOADS = 2
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000

        // Singleton instance for sharing across plugin and activity
        @Volatile
        private var instance: OfflineHlsDownloadManager? = null

        /**
         * Get or create a singleton instance for playback use.
         * Uses stored userId from SharedPreferences to ensure correct cache directory is used.
         */
        fun getInstance(context: Context): OfflineHlsDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    // Get stored userId from SharedPreferences
                    val prefs = context.applicationContext.getSharedPreferences(USER_ID_PREFS, Context.MODE_PRIVATE)
                    val storedUserId = prefs.getString(USER_ID_KEY, "") ?: ""
                    Log.d(TAG, "getInstance: Creating new instance with stored userId='$storedUserId'")

                    OfflineHlsDownloadManager(
                        context = context.applicationContext,
                        userId = storedUserId, // Use stored userId, not empty!
                        progressCallback = { } // No progress callback needed for playback
                    ).also { instance = it }
                }
            }
        }

        /**
         * Set the singleton instance (called by plugin during initialization)
         * Also persists userId to SharedPreferences for recovery after process death
         */
        fun setInstance(manager: OfflineHlsDownloadManager) {
            instance = manager
        }

        /**
         * Store userId in SharedPreferences for persistence across process restarts
         */
        fun storeUserId(context: Context, userId: String) {
            val prefs = context.applicationContext.getSharedPreferences(USER_ID_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(USER_ID_KEY, userId).apply()
            Log.d(TAG, "storeUserId: Persisted userId='$userId'")
        }
    }

    private val downloadDirectory: File
    private val downloadCache: Cache
    private val databaseProvider: StandaloneDatabaseProvider
    private val httpDataSourceFactory: DefaultHttpDataSource.Factory
    private val downloadManager: DownloadManager
    private val downloadExecutor: Executor = Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS)

    // NOTE: Individual notifications REMOVED to prevent duplicates
    // The DownloadService already provides a foreground notification with progress
    // Having both causes duplicate notifications on Android
    // Keeping NotificationManager only for cancelling stale notifications
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Progress polling for more frequent UI updates
    private val progressPollingHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isPollingActive = false
    private var pollCount = 0 // Track poll iterations to avoid stopping too early
    private val MIN_POLL_COUNT = 5 // Minimum polls before allowing auto-stop
    private val progressPollingRunnable = object : Runnable {
        override fun run() {
            pollCount++
            pollDownloadProgress()
            if (isPollingActive) {
                progressPollingHandler.postDelayed(this, 1000) // Poll every 1 second
            }
        }
    }

    // Persistent auth headers - set before starting download
    private var authHeaders: Map<String, String> = emptyMap()

    private val metadataPrefs = context.getSharedPreferences(
        "${METADATA_PREFS}_$userId",
        Context.MODE_PRIVATE
    )

    private val licensePrefs = context.getSharedPreferences(
        "${LICENSE_PREFS}_$userId",
        Context.MODE_PRIVATE
    )

    init {
        Log.d(TAG, "============================================")
        Log.d(TAG, "🚀 Initializing OfflineHlsDownloadManager")
        Log.d(TAG, "   User: $userId")
        Log.d(TAG, "============================================")

        // Create dedicated download directory (NOT shared with streaming cache)
        downloadDirectory = File(context.filesDir, "$DOWNLOAD_DIR/$userId")
        if (!downloadDirectory.exists()) {
            downloadDirectory.mkdirs()
            Log.d(TAG, "📁 Created download directory: ${downloadDirectory.absolutePath}")
        }

        // Initialize database provider
        databaseProvider = StandaloneDatabaseProvider(context)

        // Initialize dedicated offline cache with NoOpCacheEvictor (never auto-delete)
        downloadCache = SimpleCache(
            downloadDirectory,
            NoOpCacheEvictor(), // Never evict - user manages storage
            databaseProvider
        )
        Log.d(TAG, "💾 Offline cache initialized at: ${downloadDirectory.absolutePath}")

        // Create HTTP data source factory with auth headers support
        // NOTE: Headers will be dynamically added via setDefaultRequestProperties before each download
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(context, "AkkuOTT"))
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)

        // Create CacheDataSource.Factory that wraps httpDataSourceFactory
        // This is CRITICAL for HLS segment downloads to work correctly
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheWriteDataSinkFactory(null) // Let DownloadManager handle cache writing
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Initialize download manager with CacheDataSource
        downloadManager = DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            cacheDataSourceFactory, // ✅ Use CacheDataSource for proper segment handling
            downloadExecutor
        ).apply {
            maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
            minRetryCount = 5
            addListener(createDownloadListener())
        }

        // Register with DownloadService
        OfflineHlsDownloadService.setDownloadManager(downloadManager)
        OfflineHlsDownloadService.initNotificationHelper(context)

        // NOTE: Individual notification channel removed - using only DownloadService notification

        Log.d(TAG, "✅ OfflineHlsDownloadManager initialized successfully")
    }

    // NOTE: Individual notification methods removed to prevent duplicates
    // DownloadService provides its own foreground notification with combined progress
    // This simplifies the notification system and avoids user confusion

    private fun createDownloadListener(): DownloadManager.Listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?
        ) {
            val contentId = download.request.id
            val stateStr = downloadStateToString(download.state)
            val progress = download.percentDownloaded

            Log.d(TAG, "📥 Download state changed:")
            Log.d(TAG, "   ContentId: $contentId")
            Log.d(TAG, "   State: $stateStr (${download.state})")
            Log.d(TAG, "   Progress: $progress%")
            Log.d(TAG, "   Downloaded: ${download.bytesDownloaded} bytes")
            Log.d(TAG, "   Total: ${download.contentLength} bytes")

            if (finalException != null) {
                Log.e(TAG, "❌ Download exception for $contentId:", finalException)
            }

            // NOTE: Individual notifications removed - DownloadService handles the foreground notification
            // Only send progress events to Flutter for UI updates

            when (download.state) {
                Download.STATE_QUEUED -> {
                    Log.d(TAG, "⏳ Download queued: $contentId")
                    sendProgressEvent(contentId, "queued", 0f)
                }
                Download.STATE_DOWNLOADING -> {
                    val progressPercent = progress / 100f
                    Log.d(TAG, "⬇️ Downloading: $contentId - $progress%")
                    sendProgressEvent(
                        contentId,
                        "progress",
                        progressPercent,
                        download.bytesDownloaded,
                        download.contentLength
                    )
                }
                Download.STATE_COMPLETED -> {
                    Log.d(TAG, "✅ Download COMPLETED: $contentId")
                    Log.d(TAG, "   Final size: ${download.bytesDownloaded} bytes")
                    updateMetadataState(contentId, 4)
                    // Pass actual downloaded bytes for file size tracking
                    sendProgressEvent(
                        contentId,
                        "completed",
                        1f,
                        download.bytesDownloaded,
                        download.bytesDownloaded  // Use downloaded as total since it's complete
                    )
                }
                Download.STATE_FAILED -> {
                    Log.e(TAG, "❌ Download FAILED: $contentId")
                    updateMetadataState(contentId, 5)
                    sendProgressEvent(contentId, "failed", 0f)
                }
                Download.STATE_STOPPED -> {
                    Log.d(TAG, "⏸️ Download paused: $contentId")
                    updateMetadataState(contentId, 3)
                    sendProgressEvent(contentId, "paused", progress / 100f)
                }
                Download.STATE_REMOVING -> {
                    Log.d(TAG, "🗑️ Removing download: $contentId")
                }
                Download.STATE_RESTARTING -> {
                    Log.d(TAG, "🔄 Restarting download: $contentId")
                    sendProgressEvent(contentId, "resumed", progress / 100f)
                }
            }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            Log.d(TAG, "🗑️ Download removed: ${download.request.id}")
        }
    }

    private fun downloadStateToString(state: Int): String {
        return when (state) {
            Download.STATE_QUEUED -> "QUEUED"
            Download.STATE_STOPPED -> "STOPPED"
            Download.STATE_DOWNLOADING -> "DOWNLOADING"
            Download.STATE_COMPLETED -> "COMPLETED"
            Download.STATE_FAILED -> "FAILED"
            Download.STATE_REMOVING -> "REMOVING"
            Download.STATE_RESTARTING -> "RESTARTING"
            else -> "UNKNOWN($state)"
        }
    }

    private fun sendProgressEvent(
        contentId: String,
        type: String,
        progress: Float,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0
    ) {
        progressCallback(
            mapOf(
                "contentId" to contentId,
                "type" to type,
                "progress" to progress.toDouble(),
                "downloadedBytes" to downloadedBytes,
                "totalBytes" to totalBytes
            )
        )
    }

    /**
     * Start progress polling for more frequent UI updates
     */
    private fun startProgressPolling() {
        if (!isPollingActive) {
            isPollingActive = true
            pollCount = 0 // Reset poll count
            // Delay first poll by 500ms to give download time to be registered
            progressPollingHandler.postDelayed(progressPollingRunnable, 500)
            Log.d(TAG, "📊 Started progress polling (with 500ms initial delay)")
        }
    }

    /**
     * Stop progress polling when no active downloads
     */
    private fun stopProgressPolling() {
        isPollingActive = false
        progressPollingHandler.removeCallbacks(progressPollingRunnable)
        Log.d(TAG, "📊 Stopped progress polling")
    }

    /**
     * Poll current download progress for all active downloads
     * This provides more frequent progress updates to Flutter
     */
    private fun pollDownloadProgress() {
        try {
            val currentDownloads = downloadManager.currentDownloads
            var hasActiveDownloads = false

            Log.d(TAG, "📊 pollDownloadProgress: pollCount=$pollCount, currentDownloads.size=${currentDownloads.size}")

            for (download in currentDownloads) {
                when (download.state) {
                    Download.STATE_DOWNLOADING -> {
                        hasActiveDownloads = true
                        val contentId = download.request.id
                        val progress = download.percentDownloaded / 100f

                        // Send progress update
                        sendProgressEvent(
                            contentId,
                            "progress",
                            progress,
                            download.bytesDownloaded,
                            download.contentLength
                        )
                        Log.d(TAG, "📊 Polled progress: $contentId - ${download.percentDownloaded}%")
                    }
                    Download.STATE_QUEUED -> {
                        hasActiveDownloads = true
                        Log.d(TAG, "📊 Download queued, keeping polling active")
                    }
                    Download.STATE_RESTARTING -> {
                        hasActiveDownloads = true
                    }
                }
            }

            // Stop polling if no active downloads AND we've polled at least MIN_POLL_COUNT times
            // This prevents stopping too early when download is just starting
            if (!hasActiveDownloads && isPollingActive && pollCount >= MIN_POLL_COUNT) {
                Log.d(TAG, "📊 No active downloads after $pollCount polls, stopping")
                stopProgressPolling()
            } else if (!hasActiveDownloads && pollCount < MIN_POLL_COUNT) {
                Log.d(TAG, "📊 No active downloads but only $pollCount polls (min=$MIN_POLL_COUNT), continuing...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error polling download progress", e)
        }
    }

    // ============================================================
    // DOWNLOAD OPERATIONS
    // ============================================================

    /**
     * Set authorization headers for downloads
     * MUST be called before startDownload() with valid auth token
     */
    fun setAuthHeaders(headers: Map<String, String>) {
        Log.d(TAG, "🔐 Setting auth headers: ${headers.keys}")
        authHeaders = headers
        httpDataSourceFactory.setDefaultRequestProperties(headers)
    }

    /**
     * Start HLS download with proper MIME type and auth headers
     */
    fun startDownload(
        contentId: String,
        manifestUrl: String,
        title: String,
        quality: String,
        licenseUrl: String?,
        drmHeadersJson: String?,
        metadataJson: String?,
        authHeadersJson: String? = null
    ): Boolean {
        return try {
            Log.d(TAG, "============================================")
            Log.d(TAG, "🚀 STARTING DOWNLOAD")
            Log.d(TAG, "   ContentId: $contentId")
            Log.d(TAG, "   Manifest: $manifestUrl")
            Log.d(TAG, "   Quality: $quality")
            Log.d(TAG, "   LicenseUrl: ${licenseUrl ?: "NONE"}")
            Log.d(TAG, "   AuthHeadersJson: ${authHeadersJson?.take(100) ?: "NULL"}")
            Log.d(TAG, "============================================")

            // Apply headers from Flutter (includes Referer for CDN hotlink protection)
            authHeadersJson?.let { json ->
                if (json.isNotEmpty() && json != "{}") {
                    try {
                        val headersObj = JSONObject(json)
                        val headers = mutableMapOf<String, String>()
                        headersObj.keys().forEach { key ->
                            headers[key] = headersObj.getString(key)
                            Log.d(TAG, "🔐 Header: $key = ${headersObj.getString(key).take(30)}...")
                        }
                        setAuthHeaders(headers)
                        Log.d(TAG, "🔐 Applied ${headers.size} headers from Flutter")
                    } catch (e: Exception) {
                        Log.e(TAG, "⚠️ Failed to parse headers JSON: $json", e)
                    }
                }
            }

            // Save metadata - ensure manifestUrl and title are included
            val finalMetadataJson = if (metadataJson != null) {
                try {
                    val metadataObj = JSONObject(metadataJson)
                    // Add manifestUrl if not present
                    if (!metadataObj.has("manifestUrl")) {
                        metadataObj.put("manifestUrl", manifestUrl)
                    }
                    // Always set title from the passed parameter (for notifications)
                    metadataObj.put("title", title)
                    metadataObj.put("quality", quality)
                    metadataObj.toString()
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Failed to add manifestUrl/title to metadata", e)
                    metadataJson
                }
            } else {
                // Create minimal metadata with manifestUrl
                JSONObject().apply {
                    put("contentId", contentId)
                    put("manifestUrl", manifestUrl)
                    put("title", title)
                    put("quality", quality)
                }.toString()
            }
            Log.d(TAG, "💾 Saving metadata with title='$title' for: $contentId")

            // Use commit() for synchronous write - ensures metadata is available for notification
            metadataPrefs.edit().putString(contentId, finalMetadataJson).commit()
            Log.d(TAG, "💾 Saved metadata for: $contentId (title=$title)")

            // Build MediaItem with CORRECT MIME TYPE
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(manifestUrl)
                .setMediaId(contentId)
                .setMimeType(MimeTypes.APPLICATION_M3U8) // ✅ CRITICAL: Set HLS MIME type

            Log.d(TAG, "📋 MediaItem MIME type: ${MimeTypes.APPLICATION_M3U8}")

            // Configure DRM if license URL provided
            if (!licenseUrl.isNullOrEmpty()) {
                Log.d(TAG, "🔐 Configuring Widevine DRM")

                val drmHeaders = drmHeadersJson?.let { json ->
                    try {
                        val jsonObj = JSONObject(json)
                        val headers = mutableMapOf<String, String>()
                        jsonObj.keys().forEach { key ->
                            headers[key] = jsonObj.getString(key)
                        }
                        headers
                    } catch (e: Exception) {
                        Log.e(TAG, "⚠️ Failed to parse DRM headers", e)
                        emptyMap()
                    }
                } ?: emptyMap()

                // Configure MediaItem with DRM
                mediaItemBuilder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(licenseUrl)
                        .setLicenseRequestHeaders(drmHeaders)
                        .setMultiSession(false)
                        .build()
                )
            }

            val mediaItem = mediaItemBuilder.build()
            Log.d(TAG, "📋 MediaItem built successfully")

            // Send "preparing" event so UI shows progress immediately
            sendProgressEvent(contentId, "preparing", 0f)
            Log.d(TAG, "📡 Sent 'preparing' event to Flutter")

            // Start progress polling for more frequent UI updates
            startProgressPolling()

            // ============================================
            // DIRECT DOWNLOAD - Skip DownloadHelper.prepare()
            // This avoids the "prepare" request that CDNs like BunnyCDN block
            // The actual segment downloads will work like normal streaming
            // ============================================
            Log.d(TAG, "🚀 Using DIRECT download (skipping DownloadHelper.prepare)")
            Log.d(TAG, "   This avoids CDN download protection that blocks prepare requests")

            val directRequest = DownloadRequest.Builder(contentId, Uri.parse(manifestUrl))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setData(metadataJson?.toByteArray()) // Store metadata for later retrieval
                .build()

            Log.d(TAG, "📦 Direct DownloadRequest created:")
            Log.d(TAG, "   ID: ${directRequest.id}")
            Log.d(TAG, "   URI: ${directRequest.uri}")
            Log.d(TAG, "   MIME: ${directRequest.mimeType}")

            // Start download via foreground service
            Log.d(TAG, "📤 SENDING TO DownloadService.sendAddDownload()...")
            Log.d(TAG, "   contentId: $contentId")
            Log.d(TAG, "   foreground: true")
            Log.d(TAG, "   ⚠️ This will trigger DownloadService to create/update foreground notification")

            DownloadService.sendAddDownload(
                context,
                OfflineHlsDownloadService::class.java,
                directRequest,
                /* foreground= */ true
            )

            Log.d(TAG, "🚀 DownloadService.sendAddDownload() COMPLETED")
            Log.d(TAG, "✅ Download will proceed segment-by-segment like streaming")

            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ startDownload FAILED", e)
            sendProgressEvent(contentId, "failed", 0f)
            false
        }
    }

    // Old DownloadHelper approach - kept for reference but not used
    private fun startDownloadWithHelper_DISABLED(
        contentId: String,
        manifestUrl: String,
        title: String,
        quality: String,
        licenseUrl: String?,
        metadataJson: String?
    ) {
        try {
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(manifestUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setMediaId(contentId)

            val mediaItem = mediaItemBuilder.build()

            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            val helper = DownloadHelper.forMediaItem(
                context,
                mediaItem,
                renderersFactory,
                httpDataSourceFactory
            )

            helper.prepare(object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper) {
                    Log.d(TAG, "✅ DownloadHelper prepared!")
                    Log.d(TAG, "   Period count: ${helper.periodCount}")

                    // Log available tracks - DETAILED
                    var totalVideoTracks = 0
                    var totalAudioTracks = 0

                    for (periodIndex in 0 until helper.periodCount) {
                        val mappedTrackInfo = helper.getMappedTrackInfo(periodIndex)
                        Log.d(TAG, "   Period $periodIndex:")
                        Log.d(TAG, "     Renderer count: ${mappedTrackInfo.rendererCount}")

                        if (mappedTrackInfo.rendererCount == 0) {
                            Log.e(TAG, "   ❌ NO RENDERERS FOUND - This indicates manifest parsing failed!")
                            Log.e(TAG, "   ❌ Possible causes:")
                            Log.e(TAG, "      1. Variant playlist URLs not accessible")
                            Log.e(TAG, "      2. Auth headers not being passed to sub-requests")
                            Log.e(TAG, "      3. Proxy URL structure not compatible")
                        }

                        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                            val trackType = mappedTrackInfo.getRendererType(rendererIndex)
                            val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                            val trackTypeName = when (trackType) {
                                C.TRACK_TYPE_VIDEO -> "VIDEO"
                                C.TRACK_TYPE_AUDIO -> "AUDIO"
                                C.TRACK_TYPE_TEXT -> "TEXT"
                                else -> "OTHER($trackType)"
                            }
                            Log.d(TAG, "     Renderer $rendererIndex: type=$trackTypeName, groups=${trackGroups.length}")

                            if (trackType == C.TRACK_TYPE_VIDEO) {
                                for (groupIndex in 0 until trackGroups.length) {
                                    val group = trackGroups[groupIndex]
                                    for (trackIndex in 0 until group.length) {
                                        val format = group.getFormat(trackIndex)
                                        totalVideoTracks++
                                        Log.d(TAG, "       Video track: ${format.width}x${format.height} @ ${format.bitrate} bps (${format.codecs})")
                                    }
                                }
                            }
                            if (trackType == C.TRACK_TYPE_AUDIO) {
                                for (groupIndex in 0 until trackGroups.length) {
                                    val group = trackGroups[groupIndex]
                                    totalAudioTracks += group.length
                                    Log.d(TAG, "       Audio tracks in group: ${group.length}")
                                }
                            }
                        }
                    }

                    Log.d(TAG, "   📊 Total video tracks: $totalVideoTracks")
                    Log.d(TAG, "   📊 Total audio tracks: $totalAudioTracks")

                    // If no tracks found, create a direct download request
                    if (totalVideoTracks == 0 && totalAudioTracks == 0) {
                        Log.w(TAG, "⚠️ No tracks found from DownloadHelper!")
                        Log.w(TAG, "⚠️ Creating direct DownloadRequest instead...")

                        // Create download request directly without track selection
                        // This will download ALL variants and let ExoPlayer handle it
                        val directRequest = DownloadRequest.Builder(contentId, Uri.parse(manifestUrl))
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build()

                        Log.d(TAG, "📦 Direct DownloadRequest created:")
                        Log.d(TAG, "   ID: ${directRequest.id}")
                        Log.d(TAG, "   URI: ${directRequest.uri}")
                        Log.d(TAG, "   MIME: ${directRequest.mimeType}")

                        // Start download via foreground service
                        DownloadService.sendAddDownload(
                            context,
                            OfflineHlsDownloadService::class.java,
                            directRequest,
                            /* foreground= */ true
                        )

                        Log.d(TAG, "🚀 Direct download request sent to DownloadService!")
                        helper.release()
                        return
                    }

                    // Select quality track
                    selectQualityTrack(helper, quality)

                    // Build download request
                    val request = helper.getDownloadRequest(contentId, null)
                    Log.d(TAG, "📦 DownloadRequest created:")
                    Log.d(TAG, "   ID: ${request.id}")
                    Log.d(TAG, "   URI: ${request.uri}")
                    Log.d(TAG, "   MIME: ${request.mimeType}")
                    Log.d(TAG, "   Data length: ${request.data?.size ?: 0} bytes")
                    Log.d(TAG, "   Stream keys count: ${request.streamKeys.size}")

                    // Log each stream key for debugging
                    request.streamKeys.forEachIndexed { index, key ->
                        Log.d(TAG, "   StreamKey[$index]: period=${key.periodIndex}, group=${key.groupIndex}, track=${key.streamIndex}")
                    }

                    // ✅ Verify MIME type is HLS
                    if (request.mimeType != MimeTypes.APPLICATION_M3U8) {
                        Log.w(TAG, "⚠️ MIME type mismatch! Expected M3U8, got: ${request.mimeType}")
                    }

                    // CRITICAL: If no stream keys, the download will not download any segments!
                    if (request.streamKeys.isEmpty()) {
                        Log.w(TAG, "⚠️ NO STREAM KEYS IN REQUEST! Download may only fetch manifest.")
                        Log.w(TAG, "⚠️ Attempting to build request with all tracks selected...")

                        // Try selecting all tracks as fallback
                        for (periodIndex in 0 until helper.periodCount) {
                            helper.clearTrackSelections(periodIndex)
                            val defaultParams = androidx.media3.common.TrackSelectionParameters.Builder(context).build()
                            helper.addTrackSelection(periodIndex, defaultParams)
                        }

                        val retryRequest = helper.getDownloadRequest(contentId, null)
                        Log.d(TAG, "📦 Retry DownloadRequest - Stream keys: ${retryRequest.streamKeys.size}")

                        if (retryRequest.streamKeys.isNotEmpty()) {
                            // Use the retry request
                            DownloadService.sendAddDownload(
                                context,
                                OfflineHlsDownloadService::class.java,
                                retryRequest,
                                /* foreground= */ true
                            )
                            Log.d(TAG, "🚀 Retry download request sent to DownloadService!")
                            helper.release()
                            return
                        }
                    }

                    // Start download via foreground service
                    DownloadService.sendAddDownload(
                        context,
                        OfflineHlsDownloadService::class.java,
                        request,
                        /* foreground= */ true // ✅ CRITICAL: Run in foreground
                    )

                    Log.d(TAG, "🚀 Download request sent to DownloadService!")
                    helper.release()
                }

                override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                    Log.e(TAG, "❌ DownloadHelper preparation FAILED!", e)
                    Log.e(TAG, "   Error type: ${e.javaClass.simpleName}")
                    Log.e(TAG, "   Message: ${e.message}")
                    e.cause?.let { cause ->
                        Log.e(TAG, "   Cause: ${cause.javaClass.simpleName} - ${cause.message}")
                    }

                    sendProgressEvent(contentId, "failed", 0f)
                    helper.release()
                }
            })

            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ startDownload() FAILED!", e)
            false
        }
    }

    /**
     * Select quality track for download.
     *
     * CRITICAL: Add video and audio as SEPARATE track selections.
     * Each call to addTrackSelection() adds stream keys to the download request.
     */
    private fun selectQualityTrack(helper: DownloadHelper, quality: String) {
        try {
            val targetHeight = quality.replace("p", "").toIntOrNull() ?: 720
            Log.d(TAG, "🎯 Selecting video quality: ${targetHeight}p")

            for (periodIndex in 0 until helper.periodCount) {
                val mappedTrackInfo = helper.getMappedTrackInfo(periodIndex)
                Log.d(TAG, "   Period $periodIndex: ${mappedTrackInfo.rendererCount} renderers")

                // Find best video and first audio track
                var videoRendererIndex = -1
                var audioRendererIndex = -1
                var bestVideoGroup: androidx.media3.common.TrackGroup? = null
                var bestVideoTrackIndex = 0
                var bestVideoDiff = Int.MAX_VALUE
                var audioGroup: androidx.media3.common.TrackGroup? = null

                for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                    val trackType = mappedTrackInfo.getRendererType(rendererIndex)
                    val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)

                    when (trackType) {
                        C.TRACK_TYPE_VIDEO -> {
                            if (videoRendererIndex == -1) {
                                videoRendererIndex = rendererIndex
                            }
                            // Find track closest to target height
                            for (groupIndex in 0 until trackGroups.length) {
                                val group = trackGroups[groupIndex]
                                for (trackIndex in 0 until group.length) {
                                    val format = group.getFormat(trackIndex)
                                    val diff = kotlin.math.abs(format.height - targetHeight)
                                    Log.d(TAG, "     Checking video: ${format.width}x${format.height} @ ${format.bitrate}bps, diff=$diff")
                                    if (diff < bestVideoDiff) {
                                        bestVideoDiff = diff
                                        bestVideoGroup = group
                                        bestVideoTrackIndex = trackIndex
                                    }
                                }
                            }
                        }
                        C.TRACK_TYPE_AUDIO -> {
                            if (audioRendererIndex == -1 && trackGroups.length > 0) {
                                audioRendererIndex = rendererIndex
                                audioGroup = trackGroups[0]
                            }
                        }
                    }
                }

                // Clear existing selections first
                helper.clearTrackSelections(periodIndex)

                // Build a SINGLE TrackSelectionParameters with BOTH video and audio overrides
                // This is critical - calling addTrackSelection multiple times can add unwanted tracks
                val paramsBuilder = androidx.media3.common.TrackSelectionParameters.Builder(context)

                // Add VIDEO track override
                if (bestVideoGroup != null) {
                    val selectedFormat = bestVideoGroup.getFormat(bestVideoTrackIndex)
                    Log.d(TAG, "   ✅ Adding video selection: ${selectedFormat.width}x${selectedFormat.height} @ ${selectedFormat.bitrate}bps (track index: $bestVideoTrackIndex)")

                    val videoOverride = androidx.media3.common.TrackSelectionOverride(
                        bestVideoGroup,
                        listOf(bestVideoTrackIndex)
                    )
                    paramsBuilder.addOverride(videoOverride)
                }

                // Add AUDIO track override to the SAME parameters
                if (audioGroup != null) {
                    Log.d(TAG, "   ✅ Adding audio selection: group with ${audioGroup.length} track(s)")

                    val audioOverride = androidx.media3.common.TrackSelectionOverride(
                        audioGroup,
                        listOf(0) // First audio track
                    )
                    paramsBuilder.addOverride(audioOverride)
                }

                // Add SINGLE track selection with both overrides
                val params = paramsBuilder.build()
                Log.d(TAG, "   TrackSelectionParameters overrides count: ${params.overrides.size}")
                helper.addTrackSelection(periodIndex, params)

                Log.d(TAG, "✅ Track selection completed for period $periodIndex")
            }

            // Log what will be downloaded
            Log.d(TAG, "📦 Building download request with selected tracks...")

        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Quality selection failed, using default selection", e)
            e.printStackTrace()

            // Fallback: add default selection for all periods
            try {
                for (periodIndex in 0 until helper.periodCount) {
                    val defaultParams = androidx.media3.common.TrackSelectionParameters.Builder(context).build()
                    helper.addTrackSelection(periodIndex, defaultParams)
                }
                Log.d(TAG, "⚠️ Applied default track selection as fallback")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Even fallback selection failed", e2)
            }
        }
    }

    fun pauseDownload(contentId: String): Boolean {
        return try {
            Log.d(TAG, "⏸️ Pausing download: $contentId")
            DownloadService.sendSetStopReason(
                context,
                OfflineHlsDownloadService::class.java,
                contentId,
                /* stopReason= */ 1, // User requested stop
                /* foreground= */ false
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Pause failed", e)
            false
        }
    }

    fun resumeDownload(contentId: String): Boolean {
        return try {
            Log.d(TAG, "▶️ Resuming download: $contentId")
            DownloadService.sendSetStopReason(
                context,
                OfflineHlsDownloadService::class.java,
                contentId,
                /* stopReason= */ Download.STOP_REASON_NONE,
                /* foreground= */ true
            )
            // Restart progress polling for UI updates
            // Polling stops when download is paused (no active downloads)
            // so we need to restart it when resuming
            startProgressPolling()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Resume failed", e)
            false
        }
    }

    fun removeDownload(contentId: String): Boolean {
        return try {
            Log.d(TAG, "🗑️ Removing download: $contentId")
            DownloadService.sendRemoveDownload(
                context,
                OfflineHlsDownloadService::class.java,
                contentId,
                /* foreground= */ false
            )
            // Remove metadata and license
            metadataPrefs.edit().remove(contentId).apply()
            licensePrefs.edit().remove(contentId).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Remove failed", e)
            false
        }
    }

    fun isContentDownloaded(contentId: String): Boolean {
        val download = downloadManager.downloadIndex.getDownload(contentId)
        val isComplete = download?.state == Download.STATE_COMPLETED
        Log.d(TAG, "📊 isContentDownloaded($contentId): $isComplete")
        return isComplete
    }

    fun cancelDownload(contentId: String): Boolean {
        return try {
            Log.d(TAG, "❌ Cancelling download: $contentId")
            DownloadService.sendRemoveDownload(
                context,
                OfflineHlsDownloadService::class.java,
                contentId,
                /* foreground= */ false
            )
            // Remove metadata and license
            metadataPrefs.edit().remove(contentId).apply()
            licensePrefs.edit().remove(contentId).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Cancel failed", e)
            false
        }
    }

    fun getDownloadState(contentId: String): String? {
        val download = downloadManager.downloadIndex.getDownload(contentId) ?: return null
        val metadataJson = metadataPrefs.getString(contentId, null)
        return try {
            val result = JSONObject()
            result.put("contentId", contentId)
            result.put("state", downloadStateToInt(download.state))
            result.put("progress", download.percentDownloaded / 100.0)
            result.put("downloadedBytes", download.bytesDownloaded)
            result.put("totalBytes", download.contentLength)
            if (metadataJson != null) {
                val metadata = JSONObject(metadataJson)
                result.put("metadata", metadata)
            }
            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting download state", e)
            null
        }
    }

    fun getDownloadProgress(contentId: String): Float {
        val download = downloadManager.downloadIndex.getDownload(contentId)
        return download?.percentDownloaded ?: 0f
    }

    fun getDownloadMetadata(contentId: String): String? {
        return metadataPrefs.getString(contentId, null)
    }

    fun getAllDownloads(): String {
        val downloads = JSONArray()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            val download = cursor.download
            val metadataJson = metadataPrefs.getString(download.request.id, null)
            metadataJson?.let {
                try {
                    val metadata = JSONObject(it)
                    metadata.put("state", downloadStateToInt(download.state))
                    metadata.put("progress", download.percentDownloaded / 100.0)
                    metadata.put("downloadedBytes", download.bytesDownloaded)
                    metadata.put("totalBytes", download.contentLength)
                    downloads.put(metadata)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing metadata", e)
                }
            }
        }
        cursor.close()
        return downloads.toString()
    }

    // Alias for backward compatibility
    fun getAllDownloadsJson(): String = getAllDownloads()

    private fun downloadStateToInt(state: Int): Int {
        return when (state) {
            Download.STATE_QUEUED -> 1
            Download.STATE_DOWNLOADING -> 2
            Download.STATE_STOPPED -> 3
            Download.STATE_COMPLETED -> 4
            Download.STATE_FAILED -> 5
            else -> 0
        }
    }

    private fun updateMetadataState(contentId: String, state: Int) {
        val json = metadataPrefs.getString(contentId, null) ?: return
        try {
            val metadata = JSONObject(json)
            metadata.put("state", state)
            if (state == 4) {
                metadata.put("downloadedAt", System.currentTimeMillis())
                metadata.put("expiryDate", System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
            }
            metadataPrefs.edit().putString(contentId, metadata.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating metadata state", e)
        }
    }

    fun getTotalStorageUsed(): Long {
        var totalBytes = 0L
        downloadDirectory.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalBytes += file.length()
            }
        }
        return totalBytes
    }

    // ============================================================
    // LICENSE OPERATIONS
    // ============================================================

    fun renewLicense(contentId: String): Boolean {
        Log.d(TAG, "🔐 License renewal requested for: $contentId")
        // TODO: Implement Widevine license renewal
        return true
    }

    fun isLicenseValid(contentId: String): Boolean {
        val keySetId = getLicenseKeySetId(contentId)
        if (keySetId == null) {
            Log.d(TAG, "⚠️ No license found for: $contentId")
            return false
        }
        Log.d(TAG, "✅ License exists for: $contentId")
        return true
    }

    private fun saveLicenseKeySetId(contentId: String, keySetId: ByteArray) {
        val encoded = android.util.Base64.encodeToString(keySetId, android.util.Base64.DEFAULT)
        licensePrefs.edit().putString(contentId, encoded).apply()
        Log.d(TAG, "💾 Saved keySetId for: $contentId")
    }

    fun getLicenseKeySetId(contentId: String): ByteArray? {
        val encoded = licensePrefs.getString(contentId, null) ?: return null
        return android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    fun clearAllDownloads() {
        Log.d(TAG, "🗑️ Clearing all downloads...")
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            val download = cursor.download
            DownloadService.sendRemoveDownload(
                context,
                OfflineHlsDownloadService::class.java,
                download.request.id,
                false
            )
        }
        cursor.close()
        metadataPrefs.edit().clear().apply()
        licensePrefs.edit().clear().apply()
    }

    fun removeExpiredDownloads() {
        val now = System.currentTimeMillis()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            val download = cursor.download
            val metadataJson = metadataPrefs.getString(download.request.id, null)
            metadataJson?.let {
                try {
                    val metadata = JSONObject(it)
                    val expiryDate = metadata.optLong("expiryDate", Long.MAX_VALUE)
                    if (now > expiryDate) {
                        removeDownload(download.request.id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking expiry", e)
                }
            }
        }
        cursor.close()
    }

    fun getDownloadCache(): Cache = downloadCache

    /**
     * Get the download request for a given content ID
     * Used by player to access the downloaded content
     */
    fun getDownloadRequest(contentId: String): DownloadRequest? {
        val download = downloadManager.downloadIndex.getDownload(contentId)
        return download?.request
    }

    /**
     * Get the download for a given content ID
     * Provides full download info including state
     */
    fun getDownload(contentId: String): Download? {
        return downloadManager.downloadIndex.getDownload(contentId)
    }

    fun release() {
        Log.d(TAG, "🛑 Releasing OfflineHlsDownloadManager...")
        downloadManager.release()
        downloadCache.release()
    }
}
