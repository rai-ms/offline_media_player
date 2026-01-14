# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2025-01-15

### Added
- Initial stable release
- HLS/DASH streaming content download support
- Quality selection (360p to 4K)
- Widevine DRM support (L1/L2/L3) on Android
- FairPlay DRM basic support on iOS
- Background download capability with foreground service
- Real-time download progress tracking via EventChannel
- Native full-screen video player with all controls
- Download management (pause, resume, remove)
- Storage usage tracking
- Multi-user support with userId parameter
- Configurable DRM license server URL
- Device capability checking for DRM eligibility
- Metadata storage for downloads
- Auto-cleanup of expired downloads (30 days)

### Platforms
- Android: API 21+ (Lollipop and above)
- iOS: 12.0+
