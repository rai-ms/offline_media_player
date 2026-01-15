package com.raims.offline_media_player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.raims.offline_media_player.R
import org.json.JSONObject

/**
 * Background Download Service for offline HLS content
 *
 * This service runs in the foreground while downloads are active,
 * showing a notification with download progress.
 *
 * It integrates with ExoPlayer's download system to:
 * - Download HLS segments in the background
 * - Resume downloads after app restart
 * - Handle network changes gracefully
 */
@OptIn(UnstableApi::class)
class OfflineHlsDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description
) {

    companion object {
        private const val TAG = "🔔OfflineHlsDownloadSvc"
        private const val CHANNEL_ID = "akku_ott_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val JOB_ID = 1002
        private const val METADATA_PREFS = "offline_hls_metadata"

        private var downloadManagerInstance: DownloadManager? = null
        private var notificationHelper: DownloadNotificationHelper? = null

        /**
         * Set the download manager instance (called from OfflineHlsDownloadManager)
         */
        fun setDownloadManager(manager: DownloadManager) {
            downloadManagerInstance = manager
        }

        /**
         * Initialize notification helper
         */
        fun initNotificationHelper(context: Context) {
            if (notificationHelper == null) {
                createNotificationChannel(context)
                notificationHelper = DownloadNotificationHelper(context, CHANNEL_ID)
            }
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Offline content downloads"
                    setShowBadge(false)
                }

                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
            }
        }
    }

    private val metadataPrefs: SharedPreferences by lazy {
        getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 OfflineHlsDownloadService onCreate()")
        initNotificationHelper(this)
    }

    override fun onDestroy() {
        Log.d(TAG, "🛑 OfflineHlsDownloadService onDestroy()")
        super.onDestroy()
    }

    override fun getDownloadManager(): DownloadManager {
        return downloadManagerInstance
            ?: throw IllegalStateException("DownloadManager not initialized. Call setDownloadManager() first.")
    }

    override fun getScheduler(): Scheduler? {
        return if (Build.VERSION.SDK_INT >= 21) {
            PlatformScheduler(this, JOB_ID)
        } else {
            null
        }
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        Log.d(TAG, "📢 getForegroundNotification called:")
        Log.d(TAG, "   downloads.size = ${downloads.size}")
        Log.d(TAG, "   notMetRequirements = $notMetRequirements")

        // Build custom notification with content title
        if (downloads.isNotEmpty()) {
            val activeDownload = downloads.firstOrNull { it.state == Download.STATE_DOWNLOADING }
                ?: downloads.firstOrNull { it.state == Download.STATE_QUEUED }
                ?: downloads.first()

            val contentId = activeDownload.request.id
            val progress = activeDownload.percentDownloaded.toInt()
            val isPaused = activeDownload.state == Download.STATE_STOPPED

            // Get title from metadata - try request data first, then SharedPreferences
            var title = "Downloading..."
            try {
                // First try to get from download request's embedded data
                val requestData = activeDownload.request.data
                if (requestData != null && requestData.isNotEmpty()) {
                    val metadata = JSONObject(String(requestData))
                    title = metadata.optString("title", "")
                    Log.d(TAG, "   📋 Got title from request data: $title")
                }

                // Fallback to SharedPreferences if title is empty
                if (title.isEmpty()) {
                    val metadataJson = metadataPrefs.getString(contentId, null)
                    Log.d(TAG, "   📋 SharedPrefs metadataJson for $contentId: ${metadataJson?.take(100)}...")
                    if (metadataJson != null) {
                        val metadata = JSONObject(metadataJson)
                        title = metadata.optString("title", "Downloading...")
                        Log.d(TAG, "   📋 Got title from SharedPrefs: $title")
                    }
                }

                if (title.isEmpty()) {
                    title = "Downloading..."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading metadata for notification", e)
                title = "Downloading..."
            }

            Log.d(TAG, "   📥 Active: id=$contentId, state=${activeDownload.state}, progress=$progress%, title=$title")

            // Build status text
            val statusText = when {
                isPaused -> "Paused - $progress% complete"
                notMetRequirements != 0 -> "Waiting for network..."
                downloads.size > 1 -> "Downloading ${downloads.size} items... $progress%"
                else -> "Downloading... $progress%"
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, progress, false)
                .build()

            Log.d(TAG, "📢 Built custom notification: title=$title, status=$statusText")
            return notification
        }

        // Fallback to default notification helper
        val helper = notificationHelper ?: run {
            Log.d(TAG, "   ⚠️ notificationHelper was null, initializing...")
            initNotificationHelper(this)
            notificationHelper!!
        }

        val notification = helper.buildProgressNotification(
            this,
            R.drawable.ic_download,
            null,
            null,
            downloads,
            notMetRequirements
        )

        Log.d(TAG, "📢 Built fallback notification")
        return notification
    }
}
