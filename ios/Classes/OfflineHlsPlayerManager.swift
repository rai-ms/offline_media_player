import Foundation
import AVFoundation
import AVKit
import UIKit

/// Offline HLS Player Manager for iOS
///
/// Manages playback of downloaded HLS content using AVPlayer.
/// This player:
/// - Reads HLS segments from local storage
/// - Handles FairPlay offline licenses
/// - Reports playback events to Flutter
/// - Provides native full-screen player via AVPlayerViewController
class OfflineHlsPlayerManager: NSObject {

    static let shared = OfflineHlsPlayerManager()

    private let TAG = "OfflineHlsPlayerMgr"

    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    private var playerViewController: AVPlayerViewController?
    private var timeObserver: Any?
    private var currentContentId: String?
    private var currentContentTitle: String?
    private var playbackCallback: ((Dictionary<String, Any>) -> Void)?

    private var isObserving = false
    private var resumePosition: Int64 = 0

    private override init() {
        super.init()
    }

    func initialize(playbackCallback: @escaping (Dictionary<String, Any>) -> Void) {
        self.playbackCallback = playbackCallback
    }

    // MARK: - Playback Operations

    func playOffline(contentId: String, resumePositionMs: Int64) -> Bool {
        NSLog("\(TAG): Starting offline playback: \(contentId) at position \(resumePositionMs)")

        // Check if content is downloaded
        guard let asset = OfflineHlsDownloadManager.shared.getAssetForPlayback(contentId: contentId) else {
            NSLog("\(TAG): Content not downloaded: \(contentId)")
            sendPlaybackEvent(type: "error", extras: ["message": "Content not downloaded"])
            return false
        }

        // Release existing player if any
        release()

        // Create player item and player
        playerItem = AVPlayerItem(asset: asset)
        player = AVPlayer(playerItem: playerItem)

        // Add observers
        addObservers()

        currentContentId = contentId

        // Seek to resume position
        if resumePositionMs > 0 {
            let time = CMTime(value: resumePositionMs, timescale: 1000)
            player?.seek(to: time)
        }

        // Start playback
        player?.play()

        NSLog("\(TAG): Offline playback started for: \(contentId)")
        return true
    }

    /// Launch native full-screen player (AVPlayerViewController)
    /// Similar to Android's OfflinePlayerActivity - presents a native full-screen player
    /// with all standard iOS playback controls.
    func launchOfflinePlayer(contentId: String, contentTitle: String, resumePositionMs: Int64) -> Bool {
        NSLog("\(TAG): Launching native player: \(contentId) - \(contentTitle) at position \(resumePositionMs)")

        // Check if content is downloaded
        guard let asset = OfflineHlsDownloadManager.shared.getAssetForPlayback(contentId: contentId) else {
            NSLog("\(TAG): Content not downloaded: \(contentId)")
            sendPlaybackEvent(type: "error", extras: ["message": "Content not downloaded"])
            return false
        }

        // Release existing player if any
        release()

        // Store content info
        currentContentId = contentId
        currentContentTitle = contentTitle
        resumePosition = resumePositionMs

        // Create player item and player
        playerItem = AVPlayerItem(asset: asset)
        player = AVPlayer(playerItem: playerItem)

        // Add observers
        addObservers()

        // Create AVPlayerViewController for native full-screen playback
        playerViewController = AVPlayerViewController()
        playerViewController?.player = player
        playerViewController?.delegate = self
        playerViewController?.allowsPictureInPicturePlayback = true
        playerViewController?.showsPlaybackControls = true

        // Configure for DRM content - prevent screen capture
        #if os(iOS)
        if #available(iOS 11.0, *) {
            playerViewController?.requiresLinearPlayback = false
        }
        #endif

        // Get the root view controller
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootVC = windowScene.windows.first?.rootViewController else {
            NSLog("\(TAG): Failed to get root view controller")
            return false
        }

        // Present the player view controller full screen
        playerViewController?.modalPresentationStyle = .fullScreen

        // Seek to resume position before presenting
        if resumePositionMs > 0 {
            let time = CMTime(value: resumePositionMs, timescale: 1000)
            player?.seek(to: time)
        }

        // Present and start playback
        rootVC.present(playerViewController!, animated: true) { [weak self] in
            self?.player?.play()
            self?.sendPlaybackEvent(type: "playing", extras: nil)
        }

        NSLog("\(TAG): Native player launched for: \(contentId)")
        return true
    }

    func pause() {
        player?.pause()
        sendPlaybackEvent(type: "paused", extras: nil)
    }

    func resume() {
        player?.play()
        sendPlaybackEvent(type: "playing", extras: nil)
    }

    func stop() {
        release()
    }

    func seekTo(positionMs: Int64) {
        let time = CMTime(value: positionMs, timescale: 1000)
        player?.seek(to: time)
    }

    func getCurrentPosition() -> Int64 {
        guard let currentTime = player?.currentTime() else { return 0 }
        return Int64(CMTimeGetSeconds(currentTime) * 1000)
    }

    func getDuration() -> Int64 {
        guard let duration = playerItem?.duration else { return 0 }
        if duration.isIndefinite { return 0 }
        return Int64(CMTimeGetSeconds(duration) * 1000)
    }

    func isPlaying() -> Bool {
        return player?.rate ?? 0 > 0
    }

    func release() {
        removeObservers()

        player?.pause()
        player = nil
        playerItem = nil
        currentContentId = nil
        currentContentTitle = nil
        resumePosition = 0

        // Dismiss player view controller if presented
        if let pvc = playerViewController {
            pvc.dismiss(animated: true)
            playerViewController = nil
        }
    }

    // MARK: - Observers

    private func addObservers() {
        guard let playerItem = playerItem, let player = player else { return }

        // Status observer
        playerItem.addObserver(self, forKeyPath: "status", options: [.new, .initial], context: nil)

        // Playback buffer observer
        playerItem.addObserver(self, forKeyPath: "playbackBufferEmpty", options: [.new], context: nil)
        playerItem.addObserver(self, forKeyPath: "playbackLikelyToKeepUp", options: [.new], context: nil)

        // Time observer for position updates
        let interval = CMTime(seconds: 1.0, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            guard let self = self else { return }

            let positionMs = Int64(CMTimeGetSeconds(time) * 1000)
            let durationMs = self.getDuration()

            self.sendPlaybackEvent(type: "position", extras: [
                "positionMs": positionMs,
                "durationMs": durationMs
            ])
        }

        // End of playback observer
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerDidFinishPlaying),
            name: .AVPlayerItemDidPlayToEndTime,
            object: playerItem
        )

        // Error observer
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerDidFailWithError),
            name: .AVPlayerItemFailedToPlayToEndTime,
            object: playerItem
        )

        isObserving = true
    }

    private func removeObservers() {
        guard isObserving else { return }

        if let playerItem = playerItem {
            playerItem.removeObserver(self, forKeyPath: "status")
            playerItem.removeObserver(self, forKeyPath: "playbackBufferEmpty")
            playerItem.removeObserver(self, forKeyPath: "playbackLikelyToKeepUp")
        }

        if let timeObserver = timeObserver {
            player?.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }

        NotificationCenter.default.removeObserver(self)

        isObserving = false
    }

    override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        guard let keyPath = keyPath else { return }

        switch keyPath {
        case "status":
            if let statusNumber = change?[.newKey] as? NSNumber,
               let status = AVPlayerItem.Status(rawValue: statusNumber.intValue) {
                switch status {
                case .readyToPlay:
                    sendPlaybackEvent(type: "playing", extras: nil)
                case .failed:
                    let message = playerItem?.error?.localizedDescription ?? "Playback failed"
                    sendPlaybackEvent(type: "error", extras: ["message": message])
                case .unknown:
                    break
                @unknown default:
                    break
                }
            }

        case "playbackBufferEmpty":
            if let isEmpty = change?[.newKey] as? Bool, isEmpty {
                sendPlaybackEvent(type: "buffering", extras: ["isBuffering": true])
            }

        case "playbackLikelyToKeepUp":
            if let isLikely = change?[.newKey] as? Bool, isLikely {
                sendPlaybackEvent(type: "buffering", extras: ["isBuffering": false])
            }

        default:
            break
        }
    }

    @objc private func playerDidFinishPlaying() {
        sendPlaybackEvent(type: "ended", extras: nil)
    }

    @objc private func playerDidFailWithError(notification: Notification) {
        if let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error {
            sendPlaybackEvent(type: "error", extras: ["message": error.localizedDescription])
        }
    }

    // MARK: - Helpers

    private func sendPlaybackEvent(type: String, extras: [String: Any]?) {
        var event: [String: Any] = [
            "type": type,
            "contentId": currentContentId ?? ""
        ]

        if let extras = extras {
            for (key, value) in extras {
                event[key] = value
            }
        }

        playbackCallback?(event)
    }
}

// MARK: - AVPlayerViewControllerDelegate

extension OfflineHlsPlayerManager: AVPlayerViewControllerDelegate {

    /// Called when the user dismisses the player view controller
    func playerViewControllerDidStopPictureInPicture(_ playerViewController: AVPlayerViewController) {
        NSLog("\(TAG): PiP stopped")
    }

    /// Called when the user taps the done button or swipes to dismiss
    func playerViewController(_ playerViewController: AVPlayerViewController, willEndFullScreenPresentationWithAnimationCoordinator coordinator: UIViewControllerTransitionCoordinator) {
        NSLog("\(TAG): Player will end full screen presentation")

        // Save current position before dismissing
        let currentPosition = getCurrentPosition()
        let duration = getDuration()

        sendPlaybackEvent(type: "dismissed", extras: [
            "positionMs": currentPosition,
            "durationMs": duration
        ])
    }

    /// Called when PiP is about to start
    func playerViewControllerWillStartPictureInPicture(_ playerViewController: AVPlayerViewController) {
        NSLog("\(TAG): PiP will start")
    }

    /// Called to restore UI for PiP playback
    func playerViewController(_ playerViewController: AVPlayerViewController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        // Get the root view controller
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = windowScene.windows.first?.rootViewController {
            rootVC.present(playerViewController, animated: true) {
                completionHandler(true)
            }
        } else {
            completionHandler(false)
        }
    }
}
