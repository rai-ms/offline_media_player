import Foundation
import Flutter

/// Offline HLS Plugin for iOS
///
/// This plugin handles all offline HLS operations:
/// - Download HLS content using AVAssetDownloadURLSession
/// - Manage FairPlay offline licenses
/// - Play downloaded content via native AVPlayer
///
/// Flutter NEVER touches HLS files directly - all file operations are native only.
class OfflineMediaPlayerPlugin: NSObject, FlutterPlugin {

    private let TAG = "OfflineHlsPlugin"

    private var methodChannel: FlutterMethodChannel?
    private var progressChannel: FlutterEventChannel?
    private var playbackChannel: FlutterEventChannel?

    private var progressSink: FlutterEventSink?
    private var playbackSink: FlutterEventSink?

    static func register(with registrar: FlutterPluginRegistrar) {
        let instance = OfflineHlsPlugin()

        // Method channel
        let methodChannel = FlutterMethodChannel(
            name: "com.raims.offline_media_player/methods",
            binaryMessenger: registrar.messenger()
        )
        registrar.addMethodCallDelegate(instance, channel: methodChannel)
        instance.methodChannel = methodChannel

        // Progress event channel
        let progressChannel = FlutterEventChannel(
            name: "com.raims.offline_media_player/methods_progress",
            binaryMessenger: registrar.messenger()
        )
        progressChannel.setStreamHandler(ProgressStreamHandler(plugin: instance))
        instance.progressChannel = progressChannel

        // Playback event channel
        let playbackChannel = FlutterEventChannel(
            name: "com.raims.offline_media_player/methods_playback",
            binaryMessenger: registrar.messenger()
        )
        playbackChannel.setStreamHandler(PlaybackStreamHandler(plugin: instance))
        instance.playbackChannel = playbackChannel

        NSLog("\(instance.TAG): OfflineHlsPlugin registered")
    }

    // MARK: - Stream Handlers

    class ProgressStreamHandler: NSObject, FlutterStreamHandler {
        weak var plugin: OfflineMediaPlayerPlugin?

        init(plugin: OfflineMediaPlayerPlugin) {
            self.plugin = plugin
        }

        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            plugin?.progressSink = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            plugin?.progressSink = nil
            return nil
        }
    }

    class PlaybackStreamHandler: NSObject, FlutterStreamHandler {
        weak var plugin: OfflineMediaPlayerPlugin?

        init(plugin: OfflineMediaPlayerPlugin) {
            self.plugin = plugin
        }

        func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
            plugin?.playbackSink = events
            return nil
        }

        func onCancel(withArguments arguments: Any?) -> FlutterError? {
            plugin?.playbackSink = nil
            return nil
        }
    }

    // MARK: - Method Call Handler

    func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        NSLog("\(TAG): Method called: \(call.method)")

        switch call.method {
        case "initialize":
            handleInitialize(call: call, result: result)

        // Download operations
        case "startDownload":
            handleStartDownload(call: call, result: result)
        case "pauseDownload":
            handlePauseDownload(call: call, result: result)
        case "resumeDownload":
            handleResumeDownload(call: call, result: result)
        case "removeDownload":
            handleRemoveDownload(call: call, result: result)
        case "isDownloaded":
            handleIsDownloaded(call: call, result: result)
        case "getDownloadMetadata":
            handleGetDownloadMetadata(call: call, result: result)
        case "getAllDownloads":
            handleGetAllDownloads(result: result)
        case "getTotalStorageUsed":
            handleGetTotalStorageUsed(result: result)

        // License operations
        case "renewLicense":
            handleRenewLicense(call: call, result: result)
        case "isLicenseValid":
            handleIsLicenseValid(call: call, result: result)

        // Playback operations
        case "playOffline":
            handlePlayOffline(call: call, result: result)
        case "launchOfflinePlayer":
            handleLaunchOfflinePlayer(call: call, result: result)
        case "pausePlayback":
            handlePausePlayback(result: result)
        case "resumePlayback":
            handleResumePlayback(result: result)
        case "stopPlayback":
            handleStopPlayback(result: result)
        case "seekTo":
            handleSeekTo(call: call, result: result)
        case "getCurrentPosition":
            handleGetCurrentPosition(result: result)

        // Cleanup operations
        case "clearAllDownloads":
            handleClearAllDownloads(result: result)
        case "removeExpiredDownloads":
            handleRemoveExpiredDownloads(result: result)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // MARK: - Initialization

    private func handleInitialize(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let userId = args["userId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "userId required", details: nil))
            return
        }

        // Initialize download manager
        OfflineHlsDownloadManager.shared.initialize(userId: userId) { [weak self] event in
            self?.sendProgressEvent(event: event)
        }

        // Initialize player manager
        OfflineHlsPlayerManager.shared.initialize { [weak self] event in
            self?.sendPlaybackEvent(event: event)
        }

        NSLog("\(TAG): Initialized for user: \(userId)")
        result(true)
    }

    // MARK: - Download Operations

    private func handleStartDownload(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String,
              let manifestUrl = args["manifestUrl"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId and manifestUrl required", details: nil))
            return
        }

        let title = args["title"] as? String ?? ""
        let quality = args["quality"] as? String ?? "720p"
        let licenseUrl = args["licenseUrl"] as? String
        let drmHeaders = args["drmHeaders"] as? String
        let metadataJson = args["metadata"] as? String

        let success = OfflineHlsDownloadManager.shared.startDownload(
            contentId: contentId,
            manifestUrl: manifestUrl,
            title: title,
            quality: quality,
            licenseUrl: licenseUrl,
            drmHeaders: drmHeaders,
            metadataJson: metadataJson
        )

        result(success)
    }

    private func handlePauseDownload(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let success = OfflineHlsDownloadManager.shared.pauseDownload(contentId: contentId)
        result(success)
    }

    private func handleResumeDownload(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let success = OfflineHlsDownloadManager.shared.resumeDownload(contentId: contentId)
        result(success)
    }

    private func handleRemoveDownload(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let success = OfflineHlsDownloadManager.shared.removeDownload(contentId: contentId)
        result(success)
    }

    private func handleIsDownloaded(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let isDownloaded = OfflineHlsDownloadManager.shared.isContentDownloaded(contentId: contentId)
        result(isDownloaded)
    }

    private func handleGetDownloadMetadata(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let metadata = OfflineHlsDownloadManager.shared.getDownloadMetadata(contentId: contentId)
        result(metadata)
    }

    private func handleGetAllDownloads(result: @escaping FlutterResult) {
        let downloads = OfflineHlsDownloadManager.shared.getAllDownloads()
        result(downloads)
    }

    private func handleGetTotalStorageUsed(result: @escaping FlutterResult) {
        let bytes = OfflineHlsDownloadManager.shared.getTotalStorageUsed()
        result(bytes)
    }

    // MARK: - License Operations

    private func handleRenewLicense(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let success = OfflineHlsDownloadManager.shared.renewLicense(contentId: contentId)
        result(success)
    }

    private func handleIsLicenseValid(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let isValid = OfflineHlsDownloadManager.shared.isLicenseValid(contentId: contentId)
        result(isValid)
    }

    // MARK: - Playback Operations

    private func handlePlayOffline(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let resumePositionMs = (args["resumePositionMs"] as? Int) ?? 0

        let success = OfflineHlsPlayerManager.shared.playOffline(
            contentId: contentId,
            resumePositionMs: Int64(resumePositionMs)
        )
        result(success)
    }

    /// Launch native full-screen AVPlayerViewController for offline playback
    /// Similar to Android's OfflinePlayerActivity - presents a native full-screen player
    private func handleLaunchOfflinePlayer(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let contentId = args["contentId"] as? String else {
            result(FlutterError(code: "INVALID_ARGS", message: "contentId required", details: nil))
            return
        }

        let contentTitle = args["contentTitle"] as? String ?? ""
        let resumePositionMs = (args["resumePositionMs"] as? Int) ?? 0

        NSLog("\(TAG): Launching native player for \(contentId) at position \(resumePositionMs)")

        let success = OfflineHlsPlayerManager.shared.launchOfflinePlayer(
            contentId: contentId,
            contentTitle: contentTitle,
            resumePositionMs: Int64(resumePositionMs)
        )
        result(success)
    }

    private func handlePausePlayback(result: @escaping FlutterResult) {
        OfflineHlsPlayerManager.shared.pause()
        result(true)
    }

    private func handleResumePlayback(result: @escaping FlutterResult) {
        OfflineHlsPlayerManager.shared.resume()
        result(true)
    }

    private func handleStopPlayback(result: @escaping FlutterResult) {
        OfflineHlsPlayerManager.shared.stop()
        result(true)
    }

    private func handleSeekTo(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let positionMs = args["positionMs"] as? Int else {
            result(FlutterError(code: "INVALID_ARGS", message: "positionMs required", details: nil))
            return
        }

        OfflineHlsPlayerManager.shared.seekTo(positionMs: Int64(positionMs))
        result(true)
    }

    private func handleGetCurrentPosition(result: @escaping FlutterResult) {
        let position = OfflineHlsPlayerManager.shared.getCurrentPosition()
        result(position)
    }

    // MARK: - Cleanup Operations

    private func handleClearAllDownloads(result: @escaping FlutterResult) {
        OfflineHlsDownloadManager.shared.clearAllDownloads()
        result(true)
    }

    private func handleRemoveExpiredDownloads(result: @escaping FlutterResult) {
        OfflineHlsDownloadManager.shared.removeExpiredDownloads()
        result(true)
    }

    // MARK: - Event Sending

    private func sendProgressEvent(event: Dictionary<String, Any>) {
        DispatchQueue.main.async {
            self.progressSink?(event)
        }
    }

    private func sendPlaybackEvent(event: Dictionary<String, Any>) {
        DispatchQueue.main.async {
            self.playbackSink?(event)
        }
    }
}
