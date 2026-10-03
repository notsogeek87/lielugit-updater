package com.lielu.githubupdater.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import java.io.File

/**
 * Hands a downloaded APK to Android's package installer through a `content://` URI served by the
 * library's own FileProvider. It never uses `file://` and never bypasses the system's prompts.
 */
internal class ApkInstaller(
    private val context: Context,
    private val directory: File,
) {
    private val authority = "${context.packageName}.lielugit.updater.fileprovider"

    /** `true` when the app may start the installer (always `true` before Android 8). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user grants "Install unknown apps" to this app. */
    fun permissionSettingsIntent(): Intent {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        return intent.addFlagsIfNeeded()
    }

    fun install(apk: File) {
        if (!canInstall()) throw UpdateException(UpdateError.InstallationNotAllowed)
        if (!apk.isFile) throw error("APK not found: ${apk.path}")
        if (!isInsideDownloads(apk)) throw error("Only APKs downloaded by this library can be installed")

        val uri = try {
            FileProvider.getUriForFile(context, authority, apk)
        } catch (e: IllegalArgumentException) {
            throw error("Cannot share the APK: ${e.message}", e)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlagsIfNeeded()
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw error("No package installer available", e)
        } catch (e: SecurityException) {
            throw error("The installer was refused: ${e.message}", e)
        }
    }

    private fun isInsideDownloads(apk: File): Boolean =
        apk.canonicalFile.parentFile == directory.canonicalFile

    /** A non-Activity context needs NEW_TASK to start an activity. */
    private fun Intent.addFlagsIfNeeded(): Intent =
        apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    private fun error(message: String, cause: Throwable? = null) =
        UpdateException(UpdateError.InstallationError(message, cause))

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
