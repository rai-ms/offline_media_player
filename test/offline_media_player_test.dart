import 'package:flutter_test/flutter_test.dart';
import 'package:offline_media_player/offline_media_player.dart';

void main() {
  group('OfflineMediaPlayer', () {
    test('instance returns singleton', () {
      final instance1 = OfflineMediaPlayer.instance;
      final instance2 = OfflineMediaPlayer.instance;
      expect(identical(instance1, instance2), true);
    });

    test('DownloadQuality enum has correct values', () {
      expect(DownloadQuality.values.length, 6);
      expect(DownloadQuality.hd720.label, '720p');
      expect(DownloadQuality.hd720.bitrate, 2500000);
    });

    test('DownloadState enum has all states', () {
      expect(DownloadState.values, contains(DownloadState.none));
      expect(DownloadState.values, contains(DownloadState.queued));
      expect(DownloadState.values, contains(DownloadState.downloading));
      expect(DownloadState.values, contains(DownloadState.completed));
      expect(DownloadState.values, contains(DownloadState.failed));
      expect(DownloadState.values, contains(DownloadState.paused));
    });

    test('DeviceCapability model works correctly', () {
      const capability = DeviceCapability(
        isEligible: true,
        widevineSecurityLevel: 'L1',
        hasSecureDecoders: true,
        secureDecoderCount: 2,
        reason: 'Device supports offline DRM',
        deviceInfo: {'manufacturer': 'Samsung', 'model': 'Galaxy S21'},
      );

      expect(capability.isEligible, true);
      expect(capability.widevineSecurityLevel, 'L1');
      expect(capability.hasSecureDecoders, true);
      expect(capability.secureDecoderCount, 2);
    });

    test('DownloadProgressEvent model works correctly', () {
      const event = DownloadProgressEvent(
        contentId: 'test_123',
        type: 'progress',
        progress: 0.5,
        downloadedBytes: 1000,
        totalBytes: 2000,
      );

      expect(event.contentId, 'test_123');
      expect(event.type, 'progress');
      expect(event.progress, 0.5);
      expect(event.progressPercent, 50);
      expect(event.downloadedBytes, 1000);
      expect(event.isComplete, false);
    });

    test('DownloadInfo model works correctly', () {
      const info = DownloadInfo(
        contentId: 'movie_456',
        title: 'Test Movie',
        manifestUrl: 'https://example.com/video.m3u8',
        quality: '720p',
        state: DownloadState.completed,
        downloadedBytes: 100000,
        totalBytes: 100000,
      );

      expect(info.contentId, 'movie_456');
      expect(info.state, DownloadState.completed);
      expect(info.progressPercent, 100);
    });

    test('PlaybackEvent model works correctly', () {
      const event = PlaybackEvent(
        type: PlaybackEventType.playing,
        contentId: 'test_123',
        positionMs: 5000,
        durationMs: 120000,
      );

      expect(event.type, PlaybackEventType.playing);
      expect(event.contentId, 'test_123');
      expect(event.positionMs, 5000);
    });
  });
}
