package com.lielu.githubupdater

/**
 * Progress of an APK download.
 *
 * @property downloadedBytes Bytes received so far.
 * @property totalBytes Total size if known, otherwise `null`.
 * @property percentage `0..100` when [totalBytes] is known, otherwise `null`.
 */
public data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val percentage: Int?,
)
