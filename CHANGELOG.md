# Changelog

All notable changes are documented here. The project follows
[Semantic Versioning](https://semver.org/) and the format of
[Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added
- First implementation: `UpdateManager` (check / download / install), `UpdateConfig`,
  `UpdateInfo`, `UpdateState`, `UpdateError`, `DownloadProgress`.
- GitHub Releases client (no token), SemVer-aware version comparison, ABI-aware APK selection.
- Download to the private cache with progress, size and SHA-256 verification.
- Installation through a `content://` URI (dedicated FileProvider) and "Install unknown apps"
  detection.
