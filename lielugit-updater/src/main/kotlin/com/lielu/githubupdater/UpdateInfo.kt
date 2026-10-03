package com.lielu.githubupdater

/**
 * Description of an available update, built from a GitHub Release.
 *
 * @property versionName Normalized version, without tag prefix (`v1.2.3` and `release-1.2.3`
 *   both give `1.2.3`; a pre-release suffix is kept: `1.2.3-beta.1`).
 * @property versionCode Android version code if the tag carries it as SemVer build metadata
 *   (`v1.2.3+45` gives `45`), otherwise `null`. It is only a tie-breaker when two releases share
 *   the same [versionName].
 * @property releaseName Title of the release, if any.
 * @property releaseNotes Body of the release (Markdown), if any.
 * @property downloadUrl HTTPS URL of the selected APK asset.
 * @property apkFileName File name of the selected APK asset.
 * @property apkSize Size announced by GitHub, in bytes. Checked against the downloaded file.
 * @property publishedAt ISO-8601 publication date, as returned by GitHub.
 * @property sha256 Expected SHA-256 (lower-case hex) of the APK, when GitHub exposes it (asset
 *   `digest`) or the release ships a `<apk>.sha256` file. When present, the download is
 *   verified against it ([UpdateError.ChecksumMismatch]).
 */
public data class UpdateInfo(
    val versionName: String,
    val versionCode: Long?,
    val releaseName: String?,
    val releaseNotes: String?,
    val downloadUrl: String,
    val apkFileName: String,
    val apkSize: Long?,
    val publishedAt: String?,
    val sha256: String? = null,
)
