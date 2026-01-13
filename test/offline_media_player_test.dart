import 'package:flutter_test/flutter_test.dart';
import 'package:offline_media_player/offline_media_player.dart';
import 'package:offline_media_player/offline_media_player_platform_interface.dart';
import 'package:offline_media_player/offline_media_player_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockOfflineMediaPlayerPlatform
    with MockPlatformInterfaceMixin
    implements OfflineMediaPlayerPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final OfflineMediaPlayerPlatform initialPlatform = OfflineMediaPlayerPlatform.instance;

  test('$MethodChannelOfflineMediaPlayer is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelOfflineMediaPlayer>());
  });

  test('getPlatformVersion', () async {
    OfflineMediaPlayer offlineMediaPlayerPlugin = OfflineMediaPlayer();
    MockOfflineMediaPlayerPlatform fakePlatform = MockOfflineMediaPlayerPlatform();
    OfflineMediaPlayerPlatform.instance = fakePlatform;

    expect(await offlineMediaPlayerPlugin.getPlatformVersion(), '42');
  });
}
