part of '../offline_media_player.dart';

/// Implementation of [OfflineMediaPlayer] using platform channels.
class _OfflineMediaPlayerImpl implements OfflineMediaPlayer {
  static final _OfflineMediaPlayerImpl _instance = _OfflineMediaPlayerImpl._();

  static _OfflineMediaPlayerImpl get instance => _instance;

  _OfflineMediaPlayerImpl._() {
    _progressChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        _progressController.add(DownloadProgressEvent.fromMap(event));
      }
    });

    _playbackChannel.receiveBroadcastStream().listen((event) {
      if (event is Map) {
        _playbackController.add(PlaybackEvent.fromMap(event));
      }
    });
  }

  static const _methodChannel = MethodChannel('com.raims.offline_media_player/methods');
  static const _progressChannel = EventChannel('com.raims.offline_media_player/progress');
  static const _playbackChannel = EventChannel('com.raims.offline_media_player/playback');

  final _progressController = StreamController<DownloadProgressEvent>.broadcast();
  final _playbackController = StreamController<PlaybackEvent>.broadcast();

  @override
  Stream<DownloadProgressEvent> get downloadProgressStream => _progressController.stream;

  @override
  Stream<PlaybackEvent> get playbackEventStream => _playbackController.stream;

  @override
  Future<bool> initialize({required String userId}) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('initialize', {
        'userId': userId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to initialize: $e');
      return false;
    }
  }

  @override
  Future<DeviceCapability> checkDeviceCapability() async {
    try {
      final result = await _methodChannel.invokeMethod<Map>('checkDeviceCapability');
      if (result != null) {
        return DeviceCapability.fromMap(result);
      }
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to check device capability: $e');
    }
    return const DeviceCapability(
      isEligible: true, // Assume eligible for non-DRM content
      widevineSecurityLevel: 'UNKNOWN',
      hasSecureDecoders: false,
      secureDecoderCount: 0,
      reason: 'Could not determine device capability',
      deviceInfo: {},
    );
  }

  @override
  Future<bool> startDownload({
    required String contentId,
    required String manifestUrl,
    required String title,
    DownloadQuality quality = DownloadQuality.hd720,
    String? licenseUrl,
    Map<String, String>? drmHeaders,
    Map<String, String>? authHeaders,
    Map<String, dynamic>? metadata,
  }) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('startDownload', {
        'contentId': contentId,
        'manifestUrl': manifestUrl,
        'title': title,
        'quality': quality.label,
        'licenseUrl': licenseUrl,
        'drmHeaders': drmHeaders != null ? jsonEncode(drmHeaders) : null,
        'authHeaders': authHeaders != null ? jsonEncode(authHeaders) : null,
        'metadata': metadata != null ? jsonEncode(metadata) : null,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to start download: $e');
      return false;
    }
  }

  @override
  Future<bool> pauseDownload(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('pauseDownload', {
        'contentId': contentId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to pause download: $e');
      return false;
    }
  }

  @override
  Future<bool> resumeDownload(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('resumeDownload', {
        'contentId': contentId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to resume download: $e');
      return false;
    }
  }

  @override
  Future<bool> cancelDownload(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('cancelDownload', {
        'contentId': contentId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to cancel download: $e');
      return false;
    }
  }

  @override
  Future<bool> removeDownload(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('removeDownload', {
        'contentId': contentId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to remove download: $e');
      return false;
    }
  }

  @override
  Future<bool> isDownloaded(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('isDownloaded', {
        'contentId': contentId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to check if downloaded: $e');
      return false;
    }
  }

  @override
  Future<DownloadState?> getDownloadState(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<Map>('getDownloadState', {
        'contentId': contentId,
      });
      if (result != null && result['state'] != null) {
        final stateValue = result['state'];
        if (stateValue is int) {
          return DownloadState.values[stateValue.clamp(0, DownloadState.values.length - 1)];
        }
      }
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to get download state: $e');
    }
    return null;
  }

  @override
  Future<List<DownloadInfo>> getAllDownloads() async {
    try {
      final result = await _methodChannel.invokeMethod<String>('getAllDownloads');
      if (result != null && result.isNotEmpty) {
        final List<dynamic> list = jsonDecode(result);
        return list.map((e) => DownloadInfo.fromMap(e as Map)).toList();
      }
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to get all downloads: $e');
    }
    return [];
  }

  @override
  Future<int> getTotalStorageUsed() async {
    try {
      final result = await _methodChannel.invokeMethod<int>('getTotalStorageUsed');
      return result ?? 0;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to get storage used: $e');
      return 0;
    }
  }

  @override
  Future<bool> renewLicense(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('renewLicense', {
        'contentId': contentId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to renew license: $e');
      return false;
    }
  }

  @override
  Future<bool> isLicenseValid(String contentId) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('isLicenseValid', {
        'contentId': contentId,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to check license: $e');
      return false;
    }
  }

  @override
  Future<bool> launchNativePlayer({
    required String contentId,
    required String title,
    int resumePositionMs = 0,
  }) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('launchNativePlayer', {
        'contentId': contentId,
        'contentTitle': title,
        'resumePositionMs': resumePositionMs,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to launch native player: $e');
      return false;
    }
  }

  @override
  Future<bool> playOffline({
    required String contentId,
    int resumePositionMs = 0,
  }) async {
    try {
      final result = await _methodChannel.invokeMethod<bool>('playOffline', {
        'contentId': contentId,
        'resumePositionMs': resumePositionMs,
      });
      return result ?? false;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to play offline: $e');
      return false;
    }
  }

  @override
  Future<void> pausePlayback() async {
    try {
      await _methodChannel.invokeMethod('pausePlayback');
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to pause playback: $e');
    }
  }

  @override
  Future<void> resumePlayback() async {
    try {
      await _methodChannel.invokeMethod('resumePlayback');
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to resume playback: $e');
    }
  }

  @override
  Future<void> stopPlayback() async {
    try {
      await _methodChannel.invokeMethod('stopPlayback');
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to stop playback: $e');
    }
  }

  @override
  Future<void> seekTo(int positionMs) async {
    try {
      await _methodChannel.invokeMethod('seekTo', {
        'positionMs': positionMs,
      });
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to seek: $e');
    }
  }

  @override
  Future<int> getCurrentPosition() async {
    try {
      final result = await _methodChannel.invokeMethod<int>('getCurrentPosition');
      return result ?? 0;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to get position: $e');
      return 0;
    }
  }

  @override
  Future<int> getDuration() async {
    try {
      final result = await _methodChannel.invokeMethod<int>('getDuration');
      return result ?? 0;
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to get duration: $e');
      return 0;
    }
  }

  @override
  Future<void> clearAllDownloads() async {
    try {
      await _methodChannel.invokeMethod('clearAllDownloads');
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to clear downloads: $e');
    }
  }

  @override
  Future<void> removeExpiredDownloads() async {
    try {
      await _methodChannel.invokeMethod('removeExpiredDownloads');
    } catch (e) {
      debugPrint('OfflineMediaPlayer: Failed to remove expired downloads: $e');
    }
  }
}
