package com.raims.offline_media_player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Offline Media Player Plugin
 *
 * A Flutter plugin for downloading and playing HLS/DASH media offline.
 * Supports:
 * - HLS download with quality selection
 * - Widevine DRM for protected content
 * - Background downloads with notifications
 * - Offline playback with native player
 *
 * @author Shubham Rai (rai-ms)
 */
@OptIn(UnstableApi::class)
class OfflineMediaPlayerPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    companion object {
        private const val TAG = "OfflineMediaPlugin"
        private const val METHOD_CHANNEL = "com.raims.offline_media_player/methods"
        private const val PROGRESS_CHANNEL = "com.raims.offline_media_player/progress"
        private const val PLAYBACK_CHANNEL = "com.raims.offline_media_player/playback"

        const val NATIVE_PLAYER_REQUEST_CODE = 9001
    }

    private lateinit var methodChannel: MethodChannel
    private lateinit var progressChannel: EventChannel
    private lateinit var playbackChannel: EventChannel

    private var progressSink: EventChannel.EventSink? = null
    private var playbackSink: EventChannel.EventSink? = null

    private var context: Context? = null
    private var activity: Activity? = null

    private var downloadManager: OfflineHlsDownloadManager? = null
    private var playerManager: OfflineHlsPlayerManager? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(TAG, "onAttachedToEngine")
        context = flutterPluginBinding.applicationContext

        // Method channel
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)

        // Progress event channel
        progressChannel = EventChannel(flutterPluginBinding.binaryMessenger, PROGRESS_CHANNEL)
        progressChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                progressSink = events
            }

            override fun onCancel(arguments: Any?) {
                progressSink = null
            }
        })

        // Playback event channel
        playbackChannel = EventChannel(flutterPluginBinding.binaryMessenger, PLAYBACK_CHANNEL)
        playbackChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                playbackSink = events
            }

            override fun onCancel(arguments: Any?) {
                playbackSink = null
            }
        })

        // Register platform view for embedded player
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "com.raims.offline_media_player/player_view",
            OfflineHlsPlayerViewFactory { playerManager }
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(TAG, "onDetachedFromEngine")
        methodChannel.setMethodCallHandler(null)
        progressChannel.setStreamHandler(null)
        playbackChannel.setStreamHandler(null)
        playerManager?.release()
        context = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d(TAG, "onAttachedToActivity")
        activity = binding.activity

        binding.addActivityResultListener { requestCode, resultCode, data ->
            if (requestCode == NATIVE_PLAYER_REQUEST_CODE) {
                handleNativePlayerResult(resultCode, data)
                true
            } else {
                false
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    // Handle method calls from Flutter
    override fun onMethodCall(call: MethodCall, result: Result) {
        Log.d(TAG, "Method called: ${call.method}")

        when (call.method) {
            "initialize" -> handleInitialize(call, result)

            // Download operations
            "startDownload" -> handleStartDownload(call, result)
            "pauseDownload" -> handlePauseDownload(call, result)
            "resumeDownload" -> handleResumeDownload(call, result)
            "cancelDownload" -> handleCancelDownload(call, result)
            "removeDownload" -> handleRemoveDownload(call, result)
            "isDownloaded" -> handleIsDownloaded(call, result)
            "getDownloadState" -> handleGetDownloadState(call, result)
            "getAllDownloads" -> handleGetAllDownloads(result)
            "getTotalStorageUsed" -> handleGetTotalStorageUsed(result)

            // Device capability
            "checkDeviceCapability" -> handleCheckDeviceCapability(result)

            // License operations
            "renewLicense" -> handleRenewLicense(call, result)
            "isLicenseValid" -> handleIsLicenseValid(call, result)

            // Playback operations
            "playOffline" -> handlePlayOffline(call, result)
            "launchNativePlayer" -> handleLaunchNativePlayer(call, result)
            "launchOfflinePlayer" -> handleLaunchNativePlayer(call, result) // Alias for Flutter
            "pausePlayback" -> handlePausePlayback(result)
            "resumePlayback" -> handleResumePlayback(result)
            "stopPlayback" -> handleStopPlayback(result)
            "seekTo" -> handleSeekTo(call, result)
            "getCurrentPosition" -> handleGetCurrentPosition(result)
            "getDuration" -> handleGetDuration(result)

            // Cleanup
            "clearAllDownloads" -> handleClearAllDownloads(result)
            "removeExpiredDownloads" -> handleRemoveExpiredDownloads(result)

            else -> result.notImplemented()
        }
    }

    // MARK: - Initialization

    private fun handleInitialize(call: MethodCall, result: Result) {
        val userId = call.argument<String>("userId") ?: ""

        val ctx = context ?: run {
            result.error("NO_CONTEXT", "Context not available", null)
            return
        }

        // Create new download manager instance with userId and progress callback
        downloadManager = OfflineHlsDownloadManager(
            context = ctx,
            userId = userId,
            progressCallback = { event ->
                sendProgressEvent(event)
            }
        )
        // Set as singleton for shared access
        OfflineHlsDownloadManager.setInstance(downloadManager!!)

        // Initialize player manager
        playerManager = OfflineHlsPlayerManager(ctx, downloadManager!!) { event ->
            sendPlaybackEvent(event)
        }

        Log.d(TAG, "Initialized for user: $userId")
        result.success(true)
    }

    // MARK: - Download Operations

    private fun handleStartDownload(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val manifestUrl = call.argument<String>("manifestUrl") ?: ""
        val title = call.argument<String>("title") ?: ""
        val quality = call.argument<String>("quality") ?: "720p"
        val licenseUrl = call.argument<String>("licenseUrl")
        val drmHeadersJson = call.argument<String>("drmHeaders")
        val authHeadersJson = call.argument<String>("authHeaders")
        val metadataJson = call.argument<String>("metadata")

        if (contentId.isEmpty() || manifestUrl.isEmpty()) {
            result.error("INVALID_ARGS", "contentId and manifestUrl required", null)
            return
        }

        val success = downloadManager?.startDownload(
            contentId = contentId,
            manifestUrl = manifestUrl,
            title = title,
            quality = quality,
            licenseUrl = licenseUrl,
            drmHeadersJson = drmHeadersJson,
            metadataJson = metadataJson,
            authHeadersJson = authHeadersJson
        ) ?: false

        result.success(success)
    }

    private fun handlePauseDownload(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val success = downloadManager?.pauseDownload(contentId) ?: false
        result.success(success)
    }

    private fun handleResumeDownload(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val success = downloadManager?.resumeDownload(contentId) ?: false
        result.success(success)
    }

    private fun handleCancelDownload(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val success = downloadManager?.cancelDownload(contentId) ?: false
        result.success(success)
    }

    private fun handleRemoveDownload(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val success = downloadManager?.removeDownload(contentId) ?: false
        result.success(success)
    }

    private fun handleIsDownloaded(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val isDownloaded = downloadManager?.isContentDownloaded(contentId) ?: false
        result.success(isDownloaded)
    }

    private fun handleGetDownloadState(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val state = downloadManager?.getDownloadState(contentId)
        result.success(state)
    }

    private fun handleGetAllDownloads(result: Result) {
        val downloads = downloadManager?.getAllDownloadsJson() ?: "[]"
        result.success(downloads)
    }

    private fun handleGetTotalStorageUsed(result: Result) {
        val bytes = downloadManager?.getTotalStorageUsed() ?: 0L
        result.success(bytes)
    }

    // MARK: - Device Capability

    private fun handleCheckDeviceCapability(result: Result) {
        val ctx = context ?: run {
            result.error("NO_CONTEXT", "Context not available", null)
            return
        }

        val checker = DeviceCapabilityChecker(ctx)
        val capability = checker.checkDeviceEligibility()

        val response = mapOf(
            "isEligible" to capability.isEligible,
            "widevineSecurityLevel" to capability.widevineSecurityLevel,
            "hasSecureDecoders" to capability.hasSecureDecoders,
            "secureDecoderCount" to capability.secureDecoderCount,
            "reason" to capability.reason,
            "deviceInfo" to capability.deviceInfo
        )

        result.success(response)
    }

    // MARK: - License Operations

    private fun handleRenewLicense(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val success = downloadManager?.renewLicense(contentId) ?: false
        result.success(success)
    }

    private fun handleIsLicenseValid(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val isValid = downloadManager?.isLicenseValid(contentId) ?: false
        result.success(isValid)
    }

    // MARK: - Playback Operations

    private fun handlePlayOffline(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val resumePositionMs = call.argument<Number>("resumePositionMs")?.toLong() ?: 0L

        val success = playerManager?.playOffline(contentId, resumePositionMs) ?: false
        result.success(success)
    }

    private fun handleLaunchNativePlayer(call: MethodCall, result: Result) {
        val contentId = call.argument<String>("contentId") ?: ""
        val contentTitle = call.argument<String>("contentTitle") ?: ""
        val resumePositionMs = call.argument<Number>("resumePositionMs")?.toLong() ?: 0L

        val act = activity ?: run {
            result.error("NO_ACTIVITY", "Activity not available", null)
            return
        }

        val intent = OfflinePlayerActivity.createIntent(
            act,
            contentId,
            contentTitle,
            resumePositionMs
        )

        act.startActivityForResult(intent, NATIVE_PLAYER_REQUEST_CODE)
        result.success(true)
    }

    private fun handleNativePlayerResult(resultCode: Int, data: Intent?) {
        val position = data?.getLongExtra(OfflinePlayerActivity.RESULT_POSITION, 0L) ?: 0L
        val completed = data?.getBooleanExtra(OfflinePlayerActivity.RESULT_COMPLETED, false) ?: false

        val event = mapOf(
            "type" to "nativePlayerClosed",
            "positionMs" to position,
            "completed" to completed
        )

        sendPlaybackEvent(event)
    }

    private fun handlePausePlayback(result: Result) {
        playerManager?.pause()
        result.success(true)
    }

    private fun handleResumePlayback(result: Result) {
        playerManager?.resume()
        result.success(true)
    }

    private fun handleStopPlayback(result: Result) {
        playerManager?.stop()
        result.success(true)
    }

    private fun handleSeekTo(call: MethodCall, result: Result) {
        val positionMs = call.argument<Number>("positionMs")?.toLong() ?: 0L
        playerManager?.seekTo(positionMs)
        result.success(true)
    }

    private fun handleGetCurrentPosition(result: Result) {
        val position = playerManager?.getCurrentPosition() ?: 0L
        result.success(position)
    }

    private fun handleGetDuration(result: Result) {
        val duration = playerManager?.getDuration() ?: 0L
        result.success(duration)
    }

    // MARK: - Cleanup

    private fun handleClearAllDownloads(result: Result) {
        downloadManager?.clearAllDownloads()
        result.success(true)
    }

    private fun handleRemoveExpiredDownloads(result: Result) {
        downloadManager?.removeExpiredDownloads()
        result.success(true)
    }

    // MARK: - Event Sending

    private fun sendProgressEvent(event: Map<String, Any?>) {
        activity?.runOnUiThread {
            progressSink?.success(event)
        }
    }

    private fun sendPlaybackEvent(event: Map<String, Any?>) {
        activity?.runOnUiThread {
            playbackSink?.success(event)
        }
    }
}
