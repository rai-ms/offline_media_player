import Foundation
import AVFoundation

/// Offline HLS Download Manager for iOS
///
/// Uses AVAssetDownloadURLSession to download HLS content for offline playback.
/// This includes:
/// - HLS segment downloads
/// - FairPlay offline license management
/// - Download progress tracking
/// - Storage management
///
/// All file operations are native-only. Flutter never sees file paths.
class OfflineHlsDownloadManager: NSObject {

    static let shared = OfflineHlsDownloadManager()

    private let TAG = "OfflineHlsDownloadMgr"
    private let METADATA_KEY = "offline_hls_metadata"

    private var downloadSession: AVAssetDownloadURLSession!
    private var activeDownloads: [String: AVAssetDownloadTask] = [:]
    private var progressCallback: ((Dictionary<String, Any>) -> Void)?

    private var userId: String = ""

    private override init() {
        super.init()
        setupDownloadSession()
    }

    private func setupDownloadSession() {
        let config = URLSessionConfiguration.background(withIdentifier: "com.raims.offline_media_player.offlineHls")
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true

        downloadSession = AVAssetDownloadURLSession(
            configuration: config,
            assetDownloadDelegate: self,
            delegateQueue: OperationQueue.main
        )
    }

    // MARK: - Initialization

    func initialize(userId: String, progressCallback: @escaping (Dictionary<String, Any>) -> Void) {
        self.userId = userId
        self.progressCallback = progressCallback
        NSLog("\(TAG): Initialized for user: \(userId)")
    }

    // MARK: - Download Operations

    func startDownload(
        contentId: String,
        manifestUrl: String,
        title: String,
        quality: String,
        licenseUrl: String?,
        drmHeaders: String?,
        metadataJson: String?
    ) -> Bool {
        guard let url = URL(string: manifestUrl) else {
            NSLog("\(TAG): Invalid manifest URL: \(manifestUrl)")
            return false
        }

        NSLog("\(TAG): Starting download: \(contentId) from \(manifestUrl)")

        // Send "preparing" event immediately so UI shows progress
        sendProgressEvent(contentId: contentId, type: "preparing", progress: 0)

        // Save metadata
        if let metadata = metadataJson {
            saveMetadata(contentId: contentId, json: metadata)
        }

        // Create AVURLAsset
        let asset = AVURLAsset(url: url)

        // Create download task
        // For HLS, we use preferredMediaSelection for quality
        guard let task = downloadSession.makeAssetDownloadTask(
            asset: asset,
            assetTitle: title,
            assetArtworkData: nil,
            options: [AVAssetDownloadTaskMinimumRequiredMediaBitrateKey: bitrateForQuality(quality)]
        ) else {
            NSLog("\(TAG): Failed to create download task for \(contentId)")
            return false
        }

        // Store task reference
        activeDownloads[contentId] = task
        task.taskDescription = contentId

        // Start download
        task.resume()

        sendProgressEvent(contentId: contentId, type: "queued", progress: 0)

        NSLog("\(TAG): Download started: \(contentId)")
        return true
    }

    private func bitrateForQuality(_ quality: String) -> Int {
        // Convert quality string to bitrate (bits per second)
        switch quality.lowercased() {
        case "1080p":
            return 5_000_000 // 5 Mbps
        case "720p":
            return 2_500_000 // 2.5 Mbps
        case "480p":
            return 1_000_000 // 1 Mbps
        case "360p":
            return 500_000 // 0.5 Mbps
        default:
            return 2_500_000 // Default to 720p
        }
    }

    func pauseDownload(contentId: String) -> Bool {
        guard let task = activeDownloads[contentId] else {
            NSLog("\(TAG): No active download for \(contentId)")
            return false
        }

        task.suspend()
        updateMetadataState(contentId: contentId, state: 3) // paused
        sendProgressEvent(contentId: contentId, type: "paused", progress: 0)

        return true
    }

    func resumeDownload(contentId: String) -> Bool {
        guard let task = activeDownloads[contentId] else {
            NSLog("\(TAG): No active download for \(contentId)")
            return false
        }

        task.resume()
        updateMetadataState(contentId: contentId, state: 2) // downloading
        sendProgressEvent(contentId: contentId, type: "resumed", progress: 0)

        return true
    }

    func removeDownload(contentId: String) -> Bool {
        // Cancel active download if exists
        if let task = activeDownloads[contentId] {
            task.cancel()
            activeDownloads.removeValue(forKey: contentId)
        }

        // Get the stored asset location and delete
        if let location = getAssetLocation(contentId: contentId) {
            do {
                try FileManager.default.removeItem(at: location)
                NSLog("\(TAG): Removed download: \(contentId)")
            } catch {
                NSLog("\(TAG): Error removing download: \(error.localizedDescription)")
            }
        }

        // Remove metadata
        removeMetadata(contentId: contentId)

        return true
    }

    func isContentDownloaded(contentId: String) -> Bool {
        if let location = getAssetLocation(contentId: contentId) {
            return FileManager.default.fileExists(atPath: location.path)
        }
        return false
    }

    func getDownloadMetadata(contentId: String) -> String? {
        let defaults = UserDefaults.standard
        let key = "\(METADATA_KEY)_\(userId)_\(contentId)"
        return defaults.string(forKey: key)
    }

    func getAllDownloads() -> String {
        let defaults = UserDefaults.standard
        var downloads: [[String: Any]] = []

        // Get all keys matching our pattern
        let allKeys = defaults.dictionaryRepresentation().keys
        let prefix = "\(METADATA_KEY)_\(userId)_"

        for key in allKeys {
            if key.hasPrefix(prefix) {
                if let jsonString = defaults.string(forKey: key),
                   let data = jsonString.data(using: .utf8),
                   var json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {

                    // Check if asset exists
                    let contentId = String(key.dropFirst(prefix.count))
                    if let location = getAssetLocation(contentId: contentId) {
                        if FileManager.default.fileExists(atPath: location.path) {
                            // Get file size
                            if let attrs = try? FileManager.default.attributesOfItem(atPath: location.path) {
                                json["downloadedBytes"] = attrs[.size] as? Int ?? 0
                            }
                        }
                    }

                    downloads.append(json)
                }
            }
        }

        if let data = try? JSONSerialization.data(withJSONObject: downloads),
           let jsonString = String(data: data, encoding: .utf8) {
            return jsonString
        }

        return "[]"
    }

    func getTotalStorageUsed() -> Int64 {
        var totalBytes: Int64 = 0

        let defaults = UserDefaults.standard
        let allKeys = defaults.dictionaryRepresentation().keys
        let prefix = "\(METADATA_KEY)_\(userId)_"

        for key in allKeys {
            if key.hasPrefix(prefix) {
                let contentId = String(key.dropFirst(prefix.count))
                if let location = getAssetLocation(contentId: contentId) {
                    if let attrs = try? FileManager.default.attributesOfItem(atPath: location.path) {
                        totalBytes += attrs[.size] as? Int64 ?? 0
                    }
                }
            }
        }

        return totalBytes
    }

    // MARK: - License Operations

    func renewLicense(contentId: String) -> Bool {
        // TODO: Implement FairPlay license renewal
        NSLog("\(TAG): License renewal requested for: \(contentId)")
        return true
    }

    func isLicenseValid(contentId: String) -> Bool {
        // TODO: Implement FairPlay license validation
        NSLog("\(TAG): License validation requested for: \(contentId)")
        return true
    }

    // MARK: - Cleanup

    func clearAllDownloads() {
        let defaults = UserDefaults.standard
        let allKeys = defaults.dictionaryRepresentation().keys
        let prefix = "\(METADATA_KEY)_\(userId)_"

        for key in allKeys {
            if key.hasPrefix(prefix) {
                let contentId = String(key.dropFirst(prefix.count))
                _ = removeDownload(contentId: contentId)
            }
        }
    }

    func removeExpiredDownloads() {
        let now = Date().timeIntervalSince1970 * 1000 // milliseconds
        let defaults = UserDefaults.standard
        let allKeys = defaults.dictionaryRepresentation().keys
        let prefix = "\(METADATA_KEY)_\(userId)_"

        for key in allKeys {
            if key.hasPrefix(prefix) {
                if let jsonString = defaults.string(forKey: key),
                   let data = jsonString.data(using: .utf8),
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let expiryDate = json["expiryDate"] as? Double {

                    if now > expiryDate {
                        let contentId = String(key.dropFirst(prefix.count))
                        _ = removeDownload(contentId: contentId)
                    }
                }
            }
        }
    }

    // MARK: - Playback

    func getAssetForPlayback(contentId: String) -> AVURLAsset? {
        guard let location = getAssetLocation(contentId: contentId) else {
            return nil
        }

        return AVURLAsset(url: location)
    }

    // MARK: - Private Helpers

    private func saveMetadata(contentId: String, json: String) {
        let defaults = UserDefaults.standard
        let key = "\(METADATA_KEY)_\(userId)_\(contentId)"
        defaults.set(json, forKey: key)
    }

    private func removeMetadata(contentId: String) {
        let defaults = UserDefaults.standard
        let key = "\(METADATA_KEY)_\(userId)_\(contentId)"
        defaults.removeObject(forKey: key)
    }

    private func updateMetadataState(contentId: String, state: Int) {
        guard let jsonString = getDownloadMetadata(contentId: contentId),
              let data = jsonString.data(using: .utf8),
              var json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }

        json["state"] = state

        if state == 4 { // completed
            let now = Date().timeIntervalSince1970 * 1000
            json["downloadedAt"] = now
            json["expiryDate"] = now + (30 * 24 * 60 * 60 * 1000) // 30 days
        }

        if let newData = try? JSONSerialization.data(withJSONObject: json),
           let newJsonString = String(data: newData, encoding: .utf8) {
            saveMetadata(contentId: contentId, json: newJsonString)
        }
    }

    private func getAssetLocation(contentId: String) -> URL? {
        // Get stored location from UserDefaults
        let defaults = UserDefaults.standard
        let locationKey = "offline_hls_location_\(userId)_\(contentId)"

        if let path = defaults.string(forKey: locationKey) {
            return URL(fileURLWithPath: path)
        }
        return nil
    }

    private func saveAssetLocation(contentId: String, location: URL) {
        let defaults = UserDefaults.standard
        let locationKey = "offline_hls_location_\(userId)_\(contentId)"
        defaults.set(location.path, forKey: locationKey)
    }

    private func sendProgressEvent(contentId: String, type: String, progress: Double, downloadedBytes: Int64 = 0, totalBytes: Int64 = 0) {
        let event: [String: Any] = [
            "contentId": contentId,
            "type": type,
            "progress": progress,
            "downloadedBytes": downloadedBytes,
            "totalBytes": totalBytes
        ]

        progressCallback?(event)
    }
}

// MARK: - AVAssetDownloadDelegate

extension OfflineHlsDownloadManager: AVAssetDownloadDelegate {

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didLoad timeRange: CMTimeRange, totalTimeRangesLoaded loadedTimeRanges: [NSValue], timeRangeExpectedToLoad: CMTimeRange) {

        guard let contentId = assetDownloadTask.taskDescription else { return }

        // Calculate progress
        var percentComplete = 0.0
        for value in loadedTimeRanges {
            let loadedTimeRange = value.timeRangeValue
            percentComplete += CMTimeGetSeconds(loadedTimeRange.duration) / CMTimeGetSeconds(timeRangeExpectedToLoad.duration)
        }

        sendProgressEvent(contentId: contentId, type: "progress", progress: percentComplete)
    }

    func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        guard let contentId = assetDownloadTask.taskDescription else { return }

        NSLog("\(TAG): Download finished to: \(location.path)")

        // Save the location for later playback
        saveAssetLocation(contentId: contentId, location: location)

        // Update metadata
        updateMetadataState(contentId: contentId, state: 4) // completed

        // Remove from active downloads
        activeDownloads.removeValue(forKey: contentId)

        sendProgressEvent(contentId: contentId, type: "completed", progress: 1.0)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let assetTask = task as? AVAssetDownloadTask,
              let contentId = assetTask.taskDescription else { return }

        if let error = error {
            NSLog("\(TAG): Download failed: \(error.localizedDescription)")

            updateMetadataState(contentId: contentId, state: 5) // failed
            activeDownloads.removeValue(forKey: contentId)

            sendProgressEvent(contentId: contentId, type: "failed", progress: 0)
        }
    }
}
