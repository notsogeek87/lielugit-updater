package com.lielu.githubupdater

import java.io.File

/**
 * State of the update flow, exposed by [UpdateManager.state] as a `StateFlow`.
 * The library never shows anything: render this however you like (Compose, Views, nothing).
 */
public sealed class UpdateState {
    public data object Idle : UpdateState()
    public data object Checking : UpdateState()
    public data object UpToDate : UpdateState()

    public data class UpdateAvailable(val update: UpdateInfo) : UpdateState()

    public data class Downloading(val progress: DownloadProgress) : UpdateState()

    public data class Downloaded(val file: File) : UpdateState()

    public data object Installing : UpdateState()

    public data class Error(val error: UpdateError) : UpdateState()
}
