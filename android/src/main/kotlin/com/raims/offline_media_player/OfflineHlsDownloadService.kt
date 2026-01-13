package com.raims.offline_media_player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
        private const val TAG = "OfflineHlsDownloadSvc"
        private const val CHANNEL_ID = "akku_ott_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val JOB_ID = 1002

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

    override fun onCreate() {
        super.onCreate()
        initNotificationHelper(this)
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
        val helper = notificationHelper ?: run {
            initNotificationHelper(this)
            notificationHelper!!
        }

        return helper.buildProgressNotification(
            this,
            R.drawable.ic_download,
            null, // No pending intent - user can open app from notification
            null, // No content text - shown in notification helper
            downloads,
            notMetRequirements
        )
    }
}
