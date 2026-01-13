part of '../offline_media_player.dart';

/// Download quality options
enum DownloadQuality {
  /// 360p - Low quality, smallest file size
  sd360('360p', 500000),

  /// 480p - Standard definition
  sd480('480p', 1000000),

  /// 720p - HD quality (recommended)
  hd720('720p', 2500000),

  /// 1080p - Full HD quality
  fullHd1080('1080p', 5000000),

  /// 1440p - 2K quality
  qhd1440('1440p', 8000000),

  /// 2160p - 4K quality
  uhd4k('2160p', 15000000);

  const DownloadQuality(this.label, this.bitrate);

  /// Human-readable label (e.g., "720p")
  final String label;

  /// Target bitrate in bits per second
  final int bitrate;
}

/// Download state
enum DownloadState {
  /// Download not started
  none,

  /// Preparing/queued for download
  queued,

  /// Currently downloading
  downloading,

  /// Download paused
  paused,

  /// Download completed successfully
  completed,

  /// Download failed
  failed,

  /// Removing download
  removing,
}

/// Device capability for offline DRM playback
class DeviceCapability {
  /// Whether device is eligible for offline DRM playback
  final bool isEligible;

  /// Widevine security level (L1, L2, L3, or UNKNOWN)
  final String widevineSecurityLevel;

  /// Whether device has secure video decoders
  final bool hasSecureDecoders;

  /// Number of secure decoders available
  final int secureDecoderCount;

  /// Human-readable reason for eligibility status
  final String reason;

  /// Device information (manufacturer, model, etc.)
  final Map<String, dynamic> deviceInfo;

  const DeviceCapability({
    required this.isEligible,
    required this.widevineSecurityLevel,
    required this.hasSecureDecoders,
    required this.secureDecoderCount,
    required this.reason,
    required this.deviceInfo,
  });

  factory DeviceCapability.fromMap(Map<dynamic, dynamic> map) {
    return DeviceCapability(
      isEligible: map['isEligible'] as bool? ?? false,
      widevineSecurityLevel: map['widevineSecurityLevel'] as String? ?? 'UNKNOWN',
      hasSecureDecoders: map['hasSecureDecoders'] as bool? ?? false,
      secureDecoderCount: map['secureDecoderCount'] as int? ?? 0,
      reason: map['reason'] as String? ?? '',
      deviceInfo: Map<String, dynamic>.from(map['deviceInfo'] as Map? ?? {}),
    );
  }

  @override
  String toString() {
    return 'DeviceCapability(isEligible: $isEligible, level: $widevineSecurityLevel, reason: $reason)';
  }
}

/// Download progress event
class DownloadProgressEvent {
  /// Content ID
  final String contentId;

  /// Event type (queued, progress, completed, failed, paused, resumed)
  final String type;

  /// Progress percentage (0.0 to 1.0)
  final double progress;

  /// Downloaded bytes
  final int downloadedBytes;

  /// Total bytes (may be 0 if unknown)
  final int totalBytes;

  const DownloadProgressEvent({
    required this.contentId,
    required this.type,
    required this.progress,
    this.downloadedBytes = 0,
    this.totalBytes = 0,
  });

  factory DownloadProgressEvent.fromMap(Map<dynamic, dynamic> map) {
    return DownloadProgressEvent(
      contentId: map['contentId'] as String? ?? '',
      type: map['type'] as String? ?? '',
      progress: (map['progress'] as num?)?.toDouble() ?? 0.0,
      downloadedBytes: map['downloadedBytes'] as int? ?? 0,
      totalBytes: map['totalBytes'] as int? ?? 0,
    );
  }

  /// Progress percentage (0-100)
  int get progressPercent => (progress * 100).round();

  /// Whether download is complete
  bool get isComplete => type == 'completed';

  /// Whether download failed
  bool get isFailed => type == 'failed';

  @override
  String toString() {
    return 'DownloadProgressEvent(contentId: $contentId, type: $type, progress: ${progressPercent}%)';
  }
}

/// Download information
class DownloadInfo {
  final String contentId;
  final String title;
  final String manifestUrl;
  final String quality;
  final DownloadState state;
  final int downloadedBytes;
  final int totalBytes;
  final DateTime? downloadedAt;
  final DateTime? expiryDate;
  final Map<String, dynamic> metadata;

  const DownloadInfo({
    required this.contentId,
    required this.title,
    required this.manifestUrl,
    required this.quality,
    required this.state,
    this.downloadedBytes = 0,
    this.totalBytes = 0,
    this.downloadedAt,
    this.expiryDate,
    this.metadata = const {},
  });

  factory DownloadInfo.fromMap(Map<dynamic, dynamic> map) {
    return DownloadInfo(
      contentId: map['contentId'] as String? ?? '',
      title: map['title'] as String? ?? '',
      manifestUrl: map['manifestUrl'] as String? ?? '',
      quality: map['quality'] as String? ?? '720p',
      state: _parseState(map['state']),
      downloadedBytes: map['downloadedBytes'] as int? ?? 0,
      totalBytes: map['totalBytes'] as int? ?? 0,
      downloadedAt: _parseDate(map['downloadedAt']),
      expiryDate: _parseDate(map['expiryDate']),
      metadata: Map<String, dynamic>.from(map['metadata'] as Map? ?? {}),
    );
  }

  static DownloadState _parseState(dynamic value) {
    if (value is int) {
      return DownloadState.values[value.clamp(0, DownloadState.values.length - 1)];
    }
    if (value is String) {
      return DownloadState.values.firstWhere(
        (s) => s.name == value,
        orElse: () => DownloadState.none,
      );
    }
    return DownloadState.none;
  }

  static DateTime? _parseDate(dynamic value) {
    if (value == null) return null;
    if (value is int) return DateTime.fromMillisecondsSinceEpoch(value);
    if (value is double) return DateTime.fromMillisecondsSinceEpoch(value.toInt());
    return null;
  }

  /// Whether download has expired
  bool get isExpired {
    if (expiryDate == null) return false;
    return DateTime.now().isAfter(expiryDate!);
  }

  /// Progress percentage (0-100)
  int get progressPercent {
    if (totalBytes == 0) return 0;
    return ((downloadedBytes / totalBytes) * 100).round();
  }

  @override
  String toString() {
    return 'DownloadInfo(contentId: $contentId, title: $title, state: $state)';
  }
}

/// Playback event types
enum PlaybackEventType {
  playing,
  paused,
  buffering,
  ended,
  error,
  position,
  dismissed,
  nativePlayerClosed,
}

/// Playback event
class PlaybackEvent {
  final PlaybackEventType type;
  final String? contentId;
  final int? positionMs;
  final int? durationMs;
  final bool? isBuffering;
  final String? errorMessage;
  final bool? completed;

  const PlaybackEvent({
    required this.type,
    this.contentId,
    this.positionMs,
    this.durationMs,
    this.isBuffering,
    this.errorMessage,
    this.completed,
  });

  factory PlaybackEvent.fromMap(Map<dynamic, dynamic> map) {
    return PlaybackEvent(
      type: _parseType(map['type'] as String?),
      contentId: map['contentId'] as String?,
      positionMs: map['positionMs'] as int?,
      durationMs: map['durationMs'] as int?,
      isBuffering: map['isBuffering'] as bool?,
      errorMessage: map['message'] as String?,
      completed: map['completed'] as bool?,
    );
  }

  static PlaybackEventType _parseType(String? value) {
    if (value == null) return PlaybackEventType.error;
    return PlaybackEventType.values.firstWhere(
      (t) => t.name == value,
      orElse: () => PlaybackEventType.error,
    );
  }

  @override
  String toString() {
    return 'PlaybackEvent(type: $type, contentId: $contentId, position: $positionMs)';
  }
}
