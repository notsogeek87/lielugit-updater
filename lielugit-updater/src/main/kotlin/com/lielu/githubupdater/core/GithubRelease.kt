package com.lielu.githubupdater.core

/** The subset of a GitHub release this library cares about. */
internal data class GithubRelease(
    val tagName: String,
    val name: String?,
    val body: String?,
    val publishedAt: String?,
    val assets: List<GithubAsset>,
)

internal data class GithubAsset(
    val name: String,
    val size: Long?,
    val downloadUrl: String,
    /** Lower-case hex SHA-256 from GitHub's `digest` field (`sha256:<hex>`), if present. */
    val sha256: String?,
)
