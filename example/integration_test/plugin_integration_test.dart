import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:offline_media_player/offline_media_player.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('OfflineMediaPlayer Integration Tests', () {
    testWidgets('initialize returns true', (WidgetTester tester) async {
      final result = await OfflineMediaPlayer.instance.initialize(
        userId: 'test_user_123',
      );
      expect(result, true);
    });

    testWidgets('checkDeviceCapability returns valid result', (WidgetTester tester) async {
      await OfflineMediaPlayer.instance.initialize(userId: 'test_user_123');

      final capability = await OfflineMediaPlayer.instance.checkDeviceCapability();

      expect(capability, isNotNull);
      expect(capability.widevineSecurityLevel, isNotEmpty);
    });

    testWidgets('isDownloaded returns false for non-existent content', (WidgetTester tester) async {
      await OfflineMediaPlayer.instance.initialize(userId: 'test_user_123');

      final isDownloaded = await OfflineMediaPlayer.instance.isDownloaded('non_existent_content');

      expect(isDownloaded, false);
    });

    testWidgets('getAllDownloads returns list', (WidgetTester tester) async {
      await OfflineMediaPlayer.instance.initialize(userId: 'test_user_123');

      final downloads = await OfflineMediaPlayer.instance.getAllDownloads();

      expect(downloads, isA<List<DownloadInfo>>());
    });

    testWidgets('getTotalStorageUsed returns valid value', (WidgetTester tester) async {
      await OfflineMediaPlayer.instance.initialize(userId: 'test_user_123');

      final storageUsed = await OfflineMediaPlayer.instance.getTotalStorageUsed();

      expect(storageUsed, greaterThanOrEqualTo(0));
    });
  });
}
