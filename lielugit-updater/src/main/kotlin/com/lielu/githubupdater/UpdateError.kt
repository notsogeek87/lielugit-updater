package com.lielu.githubupdater

/** Everything that can go wrong in the update flow. Exhaustive `when` is supported. */
public sealed class UpdateError {

    /** Human readable (English) description, meant for logs, not for end users. */
    public abstract val message: String

    /** The network is unreachable or the connection failed/timed out. */
    public data class NetworkError(
        override val message: String,
        val cause: Throwable? = null,
    ) : UpdateError()

    /** GitHub answered with an unexpected HTTP status or an unparsable body. */
    public data class GitHubApiError(
        val httpCode: Int,
        override val message: String,
    ) : UpdateError()

    /** GitHub's unauthenticated rate limit (60 requests/hour/IP) is exhausted. */
    public data class RateLimit(
        /** Epoch seconds at which the quota resets, if GitHub said so. */
        val resetAtEpochSeconds: Long?,
    ) : UpdateError() {
        override val message: String = "GitHub API rate limit exceeded"
    }

    /** The repository does not exist, is private, or has no published release. */
    public data object ReleaseNotFound : UpdateError() {
        override val message: String = "No GitHub release found for this repository"
    }

    /** No APK in the release matches the pattern and the device architecture. */
    public data class ApkNotFound(override val message: String) : UpdateError()

    /** A release tag or the installed version name cannot be read as a version. */
    public data class InvalidVersion(val rawVersion: String?) : UpdateError() {
        override val message: String = "Cannot parse version \"${rawVersion.orEmpty()}\""
    }

    /** The APK download failed or was interrupted. */
    public data class DownloadError(
        override val message: String,
        val cause: Throwable? = null,
    ) : UpdateError()

    /** The downloaded file does not have the expected SHA-256. The file is deleted. */
    public data class ChecksumMismatch(
        val expected: String,
        val actual: String,
    ) : UpdateError() {
        override val message: String = "SHA-256 mismatch: expected $expected but got $actual"
    }

    /** The user has not allowed this app to install unknown apps (Android 8+). */
    public data object InstallationNotAllowed : UpdateError() {
        override val message: String =
            "This app is not allowed to install unknown apps. " +
                "Open the settings with UpdateManager.openInstallPermissionSettings()."
    }

    /** The system installer could not be started. */
    public data class InstallationError(
        override val message: String,
        val cause: Throwable? = null,
    ) : UpdateError()
}

/** Thrown by the suspending/blocking functions of [UpdateManager]; carries the typed [error]. */
public class UpdateException(public val error: UpdateError) : Exception(error.message) {
    init {
        when (error) {
            is UpdateError.NetworkError -> error.cause?.let { initCause(it) }
            is UpdateError.DownloadError -> error.cause?.let { initCause(it) }
            is UpdateError.InstallationError -> error.cause?.let { initCause(it) }
            else -> Unit
        }
    }
}
