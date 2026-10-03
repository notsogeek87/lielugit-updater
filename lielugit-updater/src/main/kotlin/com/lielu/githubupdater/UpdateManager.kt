package com.lielu.githubupdater

import android.content.Context
import android.content.Intent
import android.os.Build
import com.lielu.githubupdater.core.ApkDownloader
import com.lielu.githubupdater.core.ApkInstaller
import com.lielu.githubupdater.core.DownloadEvent
import com.lielu.githubupdater.core.GithubReleaseClient
import com.lielu.githubupdater.core.HttpTransport
import com.lielu.githubupdater.core.HttpUrlConnectionTransport
import com.lielu.githubupdater.core.InstalledApp
import com.lielu.githubupdater.core.SharedPreferencesUpdateStore
import com.lielu.githubupdater.core.UpdateChecker
import com.lielu.githubupdater.core.UpdateStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Entry point of the library: checks the latest GitHub Release of a repository, downloads its
 * APK and starts Android's installer. It has no UI; observe [state] to build your own.
 *
 * Failures are thrown as [UpdateException] (carrying an [UpdateError]) *and* published in [state].
 * Create one instance and keep it (e.g. in your `Application`/ViewModel) so [state] is shared.
 *
 * The APK must be signed with the same key as the installed app, or Android refuses the update.
 */
public class UpdateManager internal constructor(
    context: Context,
    config: UpdateConfig,
    transport: HttpTransport,
    store: UpdateStore,
) {
    public constructor(context: Context, config: UpdateConfig) : this(
        context.applicationContext,
        config,
        HttpUrlConnectionTransport(),
        SharedPreferencesUpdateStore(context),
    )

    private val appContext = context.applicationContext
    private val downloadsDir = File(appContext.cacheDir, ApkDownloader.DIRECTORY_NAME)

    private val checker = UpdateChecker(
        config = config,
        client = GithubReleaseClient(transport),
        store = store,
        installedApp = ::readInstalledApp,
        supportedAbis = { Build.SUPPORTED_ABIS.toList() },
    )
    private val downloader = ApkDownloader(transport, downloadsDir)
    private val installer = ApkInstaller(appContext, downloadsDir)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Current state of the update flow. */
    public val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Returns the newer release, or `null` if the app is up to date.
     *
     * At most one network check is made per [UpdateConfig.checkIntervalHours]; in between, the
     * previous answer is reused. Pass `force = true` to ignore that cache.
     *
     * @throws UpdateException on network, GitHub, version or APK-selection errors.
     */
    public suspend fun checkForUpdate(force: Boolean = false): UpdateInfo? {
        _state.value = UpdateState.Checking
        return try {
            val update = withContext(Dispatchers.IO) { checker.check(force) }
            _state.value = if (update == null) UpdateState.UpToDate else UpdateState.UpdateAvailable(update)
            update
        } catch (e: UpdateException) {
            _state.value = UpdateState.Error(e.error)
            throw e
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        }
    }

    /**
     * Downloads the APK of [update] into the app's private cache, publishing
     * [UpdateState.Downloading] along the way. Older downloads are deleted first.
     *
     * @throws UpdateException with [UpdateError.NetworkError], [UpdateError.DownloadError] or
     *   [UpdateError.ChecksumMismatch].
     */
    public suspend fun downloadUpdate(update: UpdateInfo): File {
        try {
            var file: File? = null
            downloader.download(update).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> _state.value = UpdateState.Downloading(event.value)
                    is DownloadEvent.Done -> file = event.file
                }
            }
            val apk = checkNotNull(file) { "Download flow completed without a file" }
            _state.value = UpdateState.Downloaded(apk)
            return apk
        } catch (e: UpdateException) {
            _state.value = UpdateState.Error(e.error)
            throw e
        } catch (e: CancellationException) {
            _state.value = UpdateState.UpdateAvailable(update)
            throw e
        }
    }

    /**
     * Starts Android's installer for [apkFile] (a file returned by [downloadUpdate]).
     * Android asks the user to confirm; the app is restarted by the system after the update.
     *
     * @throws UpdateException with [UpdateError.InstallationNotAllowed] if the user has not
     *   allowed this app to install unknown apps (see [openInstallPermissionSettings]), or
     *   [UpdateError.InstallationError].
     */
    public fun installUpdate(apkFile: File) {
        try {
            installer.install(apkFile)
            _state.value = UpdateState.Installing
        } catch (e: UpdateException) {
            _state.value = UpdateState.Error(e.error)
            throw e
        }
    }

    /** `true` if this app may start the installer ("Install unknown apps" granted, Android 8+). */
    public fun canInstallPackages(): Boolean = installer.canInstall()

    /**
     * Intent opening the Android screen where the user can allow this app to install unknown
     * apps. Launch it yourself (e.g. with an `ActivityResultLauncher`) to react to the result.
     */
    public fun installPermissionSettingsIntent(): Intent = installer.permissionSettingsIntent()

    /** Opens the screen of [installPermissionSettingsIntent]. */
    public fun openInstallPermissionSettings() {
        appContext.startActivity(installPermissionSettingsIntent())
    }

    /** Deletes the APKs downloaded by this library. */
    public suspend fun clearDownloads() {
        withContext(Dispatchers.IO) { downloader.clear() }
    }

    @Suppress("DEPRECATION")
    private fun readInstalledApp(): InstalledApp {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return InstalledApp(info.versionName, code)
    }
}
