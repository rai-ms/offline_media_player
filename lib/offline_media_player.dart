/// Offline Media Player - A Flutter plugin for offline HLS/DASH video downloads
///
/// This plugin provides:
/// - HLS/DASH video downloads with quality selection
/// - Widevine DRM support for protected content (Android)
/// - FairPlay DRM support for protected content (iOS)
/// - Background downloads with progress tracking
/// - Offline playback with native player
///
/// ## Usage
///
/// ```dart
/// // Initialize
/// await OfflineMediaPlayer.instance.initialize(userId: 'user123');
///
/// // Check device capability (for DRM content)
/// final capability = await OfflineMediaPlayer.instance.checkDeviceCapability();
/// if (!capability.isEligible) {
///   print('Device not eligible: ${capability.reason}');
/// }
///
/// // Start download
/// await OfflineMediaPlayer.instance.startDownload(
///   contentId: 'movie_123',
///   manifestUrl: 'https://example.com/video.m3u8',
///   title: 'Movie Title',
///   quality: DownloadQuality.hd720,
/// );
///
/// // Listen to download progress
/// OfflineMediaPlayer.instance.downloadProgressStream.listen((event) {
///   print('Progress: ${event.progress}%');
/// });
///
/// // Play offline content
/// await OfflineMediaPlayer.instance.launchNativePlayer(
///   contentId: 'movie_123',
///   title: 'Movie Title',
/// );
/// ```
///
/// @author Shubham Rai (rai-ms)
library offline_media_player;

import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

part 'src/models.dart';
part 'src/offline_media_player_impl.dart';

/// Main entry point for the Offline Media Player plugin.
///
/// Use [OfflineMediaPlayer.instance] to access the singleton instance.
abstract class OfflineMediaPlayer {
  /// Singleton instance of [OfflineMediaPlayer].
  static OfflineMediaPlayer get instance => _OfflineMediaPlayerImpl.instance;

  /// Initialize the plugin with user ID.
  ///
  /// This must be called before using any other methods.
  /// The [userId] is used to separate downloads for different users.
  Future<bool> initialize({required String userId});

  /// Check if the device supports offline DRM playback.
  ///
  /// Returns [DeviceCapability] with details about:
  /// - Widevine security level (L1/L2/L3)
  /// - Secure decoder availability
  /// - Overall eligibility for offline DRM
  ///
  /// For non-DRM content, all devices are eligible.
  Future<DeviceCapability> checkDeviceCapability();

  // ============================================================
  // Download Operations
  // ============================================================

  /// Start downloading content for offline playback.
  ///
  /// [contentId] - Unique identifier for the content
  /// [manifestUrl] - HLS/DASH manifest URL
  /// [title] - Display title for notifications
  /// [quality] - Preferred download quality
  /// [licenseUrl] - DRM license server URL (optional)
  /// [drmHeaders] - Headers for DRM license requests (optional)
  /// [authHeaders] - Auth headers for manifest/segment requests (optional)
  /// [metadata] - Additional metadata to store with download (optional)
  ///
  /// Returns true if download started successfully.
  Future<bool> startDownload({
    required String contentId,
    required String manifestUrl,
    required String title,
    DownloadQuality quality = DownloadQuality.hd720,
    String? licenseUrl,
    Map<String, String>? drmHeaders,
    Map<String, String>? authHeaders,
    Map<String, dynamic>? metadata,
  });

  /// Pause an active download.
  Future<bool> pauseDownload(String contentId);

  /// Resume a paused download.
  Future<bool> resumeDownload(String contentId);

  /// Cancel and remove an active download.
  Future<bool> cancelDownload(String contentId);

  /// Remove a completed download from storage.
  Future<bool> removeDownload(String contentId);

  /// Check if content is downloaded and available offline.
  Future<bool> isDownloaded(String contentId);

  /// Get download state for content.
  Future<DownloadState?> getDownloadState(String contentId);

  /// Get all downloads (active and completed).
  Future<List<DownloadInfo>> getAllDownloads();

  /// Get total storage used by downloads in bytes.
  Future<int> getTotalStorageUsed();

  /// Stream of download progress events.
  Stream<DownloadProgressEvent> get downloadProgressStream;

  // ============================================================
  // License Operations (DRM)
  // ============================================================

  /// Renew DRM license for offline content.
  Future<bool> renewLicense(String contentId);

  /// Check if DRM license is still valid.
  Future<bool> isLicenseValid(String contentId);

  // ============================================================
  // Playback Operations
  // ============================================================

  /// Launch native full-screen player for offline content.
  ///
  /// This opens a native video player (ExoPlayer on Android,
  /// AVPlayerViewController on iOS) for the best playback experience.
  ///
  /// [contentId] - ID of the downloaded content
  /// [title] - Title to display in player
  /// [resumePositionMs] - Position to resume from (in milliseconds)
  Future<bool> launchNativePlayer({
    required String contentId,
    required String title,
    int resumePositionMs = 0,
  });

  /// Play offline content (for embedded player use).
  Future<bool> playOffline({
    required String contentId,
    int resumePositionMs = 0,
  });

  /// Pause current playback.
  Future<void> pausePlayback();

  /// Resume current playback.
  Future<void> resumePlayback();

  /// Stop current playback.
  Future<void> stopPlayback();

  /// Seek to position in milliseconds.
  Future<void> seekTo(int positionMs);

  /// Get current playback position in milliseconds.
  Future<int> getCurrentPosition();

  /// Get total duration in milliseconds.
  Future<int> getDuration();

  /// Stream of playback events.
  Stream<PlaybackEvent> get playbackEventStream;

  // ============================================================
  // Cleanup Operations
  // ============================================================

  /// Remove all downloads and clear storage.
  Future<void> clearAllDownloads();

  /// Remove expired downloads (30 days old or license expired).
  Future<void> removeExpiredDownloads();
}
