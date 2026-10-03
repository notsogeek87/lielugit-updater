package com.lielu.githubupdater.core

import com.lielu.githubupdater.DownloadProgress
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import com.lielu.githubupdater.UpdateInfo
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal sealed interface DownloadEvent {
    class Progress(val value: DownloadProgress) : DownloadEvent
    class Done(val file: File) : DownloadEvent
}

/**
 * Downloads an APK into [directory] (a sub-folder of the app's private cache).
 *
 * The bytes go to `<name>.part`; the file only receives its final name once its size and, when
 * known, its SHA-256 are verified. Whatever happens (error, cancellation) the partial file is
 * deleted, so [directory] never holds a truncated APK under a valid name. Downloads are not resumed.
 */
internal class ApkDownloader(
    private val transport: HttpTransport,
    private val directory: File,
) {

    /** Cold flow: [DownloadEvent.Progress]* then one [DownloadEvent.Done], or an [UpdateException]. */
    fun download(update: UpdateInfo): Flow<DownloadEvent> = flow {
        validate(update)
        prepareDirectory()
        val target = File(directory, update.apkFileName)
        val partial = File(directory, update.apkFileName + ".part")
        var completed = false
        try {
            val response = try {
                transport.get(update.downloadUrl, mapOf("User-Agent" to GithubReleaseClient.USER_AGENT))
            } catch (e: IOException) {
                throw UpdateException(UpdateError.NetworkError(e.message ?: "Network unreachable", e))
            }
            response.use {
                if (!it.isSuccessful) {
                    throw UpdateException(UpdateError.DownloadError("Download failed: HTTP ${it.statusCode}"))
                }
                val expectedSize = it.contentLength ?: update.apkSize
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastPercentage = -1
                var lastEmitted = 0L
                emit(DownloadEvent.Progress(progress(0, expectedSize)))

                partial.outputStream().buffered().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = read(it.body, buffer)
                        if (n < 0) break
                        write(out, buffer, n)
                        digest.update(buffer, 0, n)
                        downloaded += n

                        val current = progress(downloaded, expectedSize)
                        val changed = if (current.percentage != null) {
                            current.percentage != lastPercentage
                        } else {
                            downloaded - lastEmitted >= UNKNOWN_SIZE_STEP
                        }
                        if (changed) {
                            lastPercentage = current.percentage ?: lastPercentage
                            lastEmitted = downloaded
                            emit(DownloadEvent.Progress(current))
                        }
                    }
                }

                if (expectedSize != null && downloaded != expectedSize) {
                    throw UpdateException(
                        UpdateError.DownloadError("Download interrupted: got $downloaded of $expectedSize bytes"),
                    )
                }
                update.sha256?.let { expected ->
                    val actual = digest.digest().toHex()
                    if (!actual.equals(expected, ignoreCase = true)) {
                        throw UpdateException(UpdateError.ChecksumMismatch(expected.lowercase(), actual))
                    }
                }
                emit(DownloadEvent.Progress(progress(downloaded, downloaded)))
            }

            if (target.exists() && !target.delete() || !partial.renameTo(target)) {
                throw UpdateException(UpdateError.DownloadError("Cannot move the APK to ${target.name}"))
            }
            completed = true
            emit(DownloadEvent.Done(target))
        } finally {
            if (!completed) partial.delete()
        }
    }.flowOn(Dispatchers.IO)

    /** Deletes every previously downloaded file. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun validate(update: UpdateInfo) {
        if (!update.downloadUrl.startsWith("https://")) {
            throw UpdateException(UpdateError.DownloadError("Refusing non-HTTPS download URL"))
        }
        val name = update.apkFileName
        if (name.isEmpty() || name != File(name).name || name.startsWith(".") || !name.endsWith(".apk", true)) {
            throw UpdateException(UpdateError.DownloadError("Invalid APK file name \"$name\""))
        }
    }

    /** Creates the folder and removes leftovers of previous downloads (old versions, `.part`). */
    private fun prepareDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw UpdateException(UpdateError.DownloadError("Cannot create ${directory.path}"))
        }
        clear()
    }

    private fun read(input: java.io.InputStream, buffer: ByteArray): Int = try {
        input.read(buffer)
    } catch (e: IOException) {
        throw UpdateException(UpdateError.DownloadError("Download interrupted: ${e.message}", e))
    }

    private fun write(out: OutputStream, buffer: ByteArray, length: Int) {
        try {
            out.write(buffer, 0, length)
        } catch (e: IOException) {
            throw UpdateException(UpdateError.DownloadError("Cannot write the APK: ${e.message}", e))
        }
    }

    private fun progress(downloaded: Long, total: Long?): DownloadProgress {
        val percentage = total?.takeIf { it > 0 }?.let { (downloaded * 100 / it).toInt().coerceIn(0, 100) }
        return DownloadProgress(downloaded, total, percentage)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        /** Sub-folder of `Context.cacheDir`; must match `res/xml/lielugit_updater_file_paths.xml`. */
        const val DIRECTORY_NAME = "lielugit-updates"
        private const val BUFFER_SIZE = 16 * 1024
        private const val UNKNOWN_SIZE_STEP = 256 * 1024L
    }
}
