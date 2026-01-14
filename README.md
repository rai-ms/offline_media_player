# Offline Media Player

A Flutter plugin for downloading and playing HLS/DASH streaming content offline with DRM support.

## Features

- **HLS/DASH Downloads** - Download streaming content for offline playback
- **Quality Selection** - Choose from 360p to 4K quality
- **DRM Support**
  - Widevine L1/L2/L3 on Android (ExoPlayer/Media3)
  - FairPlay on iOS (AVAssetDownloadURLSession)
- **Background Downloads** - Downloads continue even when app is in background
- **Progress Tracking** - Real-time download progress events
- **Offline Playback** - Native full-screen player with all controls
- **Storage Management** - Track storage usage and clean up old downloads
- **License Management** - Handle DRM license renewal and validation

## Platform Support

| Platform | Supported | DRM |
|----------|-----------|-----|
| Android  | ✅ | Widevine L1/L2/L3 |
| iOS      | ✅ | FairPlay (basic) |

## Installation

Add to your `pubspec.yaml`:

```yaml
dependencies:
  offline_media_player: ^1.0.0
```

Then run:

```bash
flutter pub get
```

## Usage

### Initialize

```dart
import 'package:offline_media_player/offline_media_player.dart';

// Initialize with user ID (for multi-user support)
await OfflineMediaPlayer.instance.initialize(userId: 'user123');
```

### Check Device Capability (for DRM content)

```dart
final capability = await OfflineMediaPlayer.instance.checkDeviceCapability();

if (!capability.isEligible) {
  print('Device not eligible for offline DRM: ${capability.reason}');
  print('Widevine Level: ${capability.widevineSecurityLevel}');
  return;
}
```

### Start Download

```dart
await OfflineMediaPlayer.instance.startDownload(
  contentId: 'movie_123',
  manifestUrl: 'https://example.com/video.m3u8',
  title: 'My Movie',
  quality: DownloadQuality.hd720,
  // Optional: for DRM content
  licenseUrl: 'https://license.server.com/license',
  drmHeaders: {'Authorization': 'Bearer token'},
  // Optional: auth headers for manifest/segments
  authHeaders: {'Authorization': 'Bearer token'},
  // Optional: store additional metadata
  metadata: {'poster': 'https://example.com/poster.jpg'},
);
```

### Listen to Download Progress

```dart
OfflineMediaPlayer.instance.downloadProgressStream.listen((event) {
  print('Content: ${event.contentId}');
  print('Type: ${event.type}'); // queued, progress, completed, failed
  print('Progress: ${event.progressPercent}%');
});
```

### Play Offline Content

```dart
// Launch native full-screen player
await OfflineMediaPlayer.instance.launchNativePlayer(
  contentId: 'movie_123',
  title: 'My Movie',
  resumePositionMs: 0, // Resume position in milliseconds
);

// Listen to playback events
OfflineMediaPlayer.instance.playbackEventStream.listen((event) {
  switch (event.type) {
    case PlaybackEventType.playing:
      print('Playing');
      break;
    case PlaybackEventType.paused:
      print('Paused');
      break;
    case PlaybackEventType.position:
      print('Position: ${event.positionMs}ms / ${event.durationMs}ms');
      break;
    case PlaybackEventType.ended:
      print('Playback ended');
      break;
    case PlaybackEventType.nativePlayerClosed:
      print('Player closed at position: ${event.positionMs}');
      break;
    case PlaybackEventType.error:
      print('Error: ${event.errorMessage}');
      break;
  }
});
```

### Manage Downloads

```dart
// Check if content is downloaded
final isDownloaded = await OfflineMediaPlayer.instance.isDownloaded('movie_123');

// Get all downloads
final downloads = await OfflineMediaPlayer.instance.getAllDownloads();
for (final download in downloads) {
  print('${download.title}: ${download.state}');
}

// Get storage used
final bytes = await OfflineMediaPlayer.instance.getTotalStorageUsed();
print('Storage used: ${bytes ~/ 1024 ~/ 1024} MB');

// Pause/Resume download
await OfflineMediaPlayer.instance.pauseDownload('movie_123');
await OfflineMediaPlayer.instance.resumeDownload('movie_123');

// Remove download
await OfflineMediaPlayer.instance.removeDownload('movie_123');

// Clear all downloads
await OfflineMediaPlayer.instance.clearAllDownloads();

// Remove expired downloads (30 days old)
await OfflineMediaPlayer.instance.removeExpiredDownloads();
```

## Android Setup

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Register the download service -->
<service
    android:name="com.raims.offline_media_player.OfflineHlsDownloadService"
    android:exported="false"
    android:foregroundServiceType="dataSync">
    <intent-filter>
        <action android:name="androidx.media3.exoplayer.downloadService.action.RESTART"/>
        <category android:name="android.intent.category.DEFAULT"/>
    </intent-filter>
</service>

<!-- Register the offline player activity -->
<activity
    android:name="com.raims.offline_media_player.OfflinePlayerActivity"
    android:configChanges="orientation|keyboardHidden|screenSize"
    android:screenOrientation="sensorLandscape"
    android:theme="@style/Theme.AppCompat.NoActionBar" />
```

## iOS Setup

Add to your `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
    <string>audio</string>
    <string>fetch</string>
    <string>processing</string>
</array>
```

## Download Quality Options

| Quality | Resolution | Bitrate |
|---------|------------|---------|
| sd360 | 360p | 0.5 Mbps |
| sd480 | 480p | 1 Mbps |
| hd720 | 720p | 2.5 Mbps |
| fullHd1080 | 1080p | 5 Mbps |
| qhd1440 | 1440p | 8 Mbps |
| uhd4k | 2160p | 15 Mbps |

## DRM Requirements

### Android (Widevine)
- **L1**: Hardware security - required for HD/4K DRM content
- **L2**: Partial hardware security
- **L3**: Software only - limited to SD content

### iOS (FairPlay)
- FairPlay Streaming support (basic implementation)
- Full FPS license server integration required for production

## Author

**Ashish Rai** ([@rai-ms](https://github.com/rai-ms))

## License

MIT License - see [LICENSE](LICENSE) file.
