package com.lielu.githubupdater.core

import com.lielu.githubupdater.UpdateConfig
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import com.lielu.githubupdater.UpdateInfo
import org.json.JSONException
import org.json.JSONObject

/** Facts about the installed app, read lazily from the PackageManager. */
internal class InstalledApp(val versionName: String?, val versionCode: Long?)

/**
 * Decides whether a newer release exists. Blocking: callers run it on an IO dispatcher.
 * Android-free on purpose, so it is covered by plain JVM unit tests.
 */
internal class UpdateChecker(
    private val config: UpdateConfig,
    private val client: GithubReleaseClient,
    private val store: UpdateStore,
    private val installedApp: () -> InstalledApp,
    private val supportedAbis: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cacheKey = "${config.githubOwner}/${config.githubRepository}|${config.apkAssetNamePattern}"

    /** Returns the update to offer, or `null` if the app is up to date. Throws [UpdateException]. */
    fun check(force: Boolean): UpdateInfo? {
        val installed = parseInstalled()
        if (!force) cachedResult(installed)?.let { return it.value }

        val release = client.fetchLatest(config.githubOwner, config.githubRepository)
        val remote = Version.parseOrNull(release.tagName)
            ?: throw UpdateException(UpdateError.InvalidVersion(release.tagName))

        val update = if (isNewer(remote, remote.buildNumber, installed)) buildUpdate(release, remote) else null
        save(update)
        return update
    }

    private class Installed(val version: Version, val code: Long?)

    private fun parseInstalled(): Installed {
        val app = installedApp()
        val version = Version.parseOrNull(app.versionName)
            ?: throw UpdateException(UpdateError.InvalidVersion(app.versionName))
        return Installed(version, app.versionCode)
    }

    private fun isNewer(remote: Version, remoteCode: Long?, installed: Installed): Boolean {
        val c = remote.compareTo(installed.version)
        if (c != 0) return c > 0
        // Same name: only an explicit, higher build number counts as newer.
        return remoteCode != null && installed.code != null && remoteCode > installed.code
    }

    private fun buildUpdate(release: GithubRelease, version: Version): UpdateInfo {
        val asset = ApkSelector.select(release.assets, config.apkAssetNamePattern, supportedAbis())
        if (!asset.downloadUrl.startsWith("https://")) {
            throw UpdateException(UpdateError.ApkNotFound("Refusing non-HTTPS download URL for ${asset.name}"))
        }
        return UpdateInfo(
            versionName = version.normalized,
            versionCode = version.buildNumber,
            releaseName = release.name,
            releaseNotes = release.body,
            downloadUrl = asset.downloadUrl,
            apkFileName = asset.name,
            apkSize = asset.size,
            publishedAt = release.publishedAt,
            sha256 = asset.sha256 ?: fetchSidecarChecksum(release, asset),
        )
    }

    /** Optional `<apk>.sha256` asset: `<64 hex>` optionally followed by a file name. */
    private fun fetchSidecarChecksum(release: GithubRelease, apk: GithubAsset): String? {
        val sidecar = release.assets.firstOrNull { it.name.equals("${apk.name}.sha256", ignoreCase = true) }
            ?: return null
        val token = client.fetchSmallText(sidecar.downloadUrl).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (!Regex("[0-9A-Fa-f]{64}").matches(token)) {
            throw UpdateException(UpdateError.GitHubApiError(200, "Invalid checksum file ${sidecar.name}"))
        }
        return token.lowercase()
    }

    // --- cache ---------------------------------------------------------------------------------

    private class Cached(val value: UpdateInfo?)

    private fun cachedResult(installed: Installed): Cached? {
        val interval = config.checkIntervalHours * MILLIS_PER_HOUR
        val age = clock() - store.lastCheckMillis
        // A negative age means the clock went backwards: do not trust the cache.
        if (interval <= 0 || age < 0 || age >= interval || store.cacheKey != cacheKey) return null
        val json = store.cachedUpdateJson ?: return Cached(null)
        val info = decode(json) ?: return null
        // The app may have been updated since: re-evaluate the cached release.
        val remote = Version.parseOrNull(info.versionName) ?: return null
        val stillNewer = isNewer(remote, info.versionCode, installed)
        return Cached(if (stillNewer) info else null)
    }

    private fun save(update: UpdateInfo?) {
        store.cacheKey = cacheKey
        store.lastCheckMillis = clock()
        store.cachedUpdateJson = update?.let(::encode)
    }

    private fun encode(u: UpdateInfo): String = JSONObject()
        .put("versionName", u.versionName)
        .put("versionCode", u.versionCode ?: JSONObject.NULL)
        .put("releaseName", u.releaseName ?: JSONObject.NULL)
        .put("releaseNotes", u.releaseNotes ?: JSONObject.NULL)
        .put("downloadUrl", u.downloadUrl)
        .put("apkFileName", u.apkFileName)
        .put("apkSize", u.apkSize ?: JSONObject.NULL)
        .put("publishedAt", u.publishedAt ?: JSONObject.NULL)
        .put("sha256", u.sha256 ?: JSONObject.NULL)
        .toString()

    private fun decode(json: String): UpdateInfo? = try {
        val o = JSONObject(json)
        fun str(k: String) = if (o.isNull(k)) null else o.getString(k)
        fun lng(k: String) = if (o.isNull(k)) null else o.getLong(k)
        UpdateInfo(
            versionName = o.getString("versionName"),
            versionCode = lng("versionCode"),
            releaseName = str("releaseName"),
            releaseNotes = str("releaseNotes"),
            downloadUrl = o.getString("downloadUrl"),
            apkFileName = o.getString("apkFileName"),
            apkSize = lng("apkSize"),
            publishedAt = str("publishedAt"),
            sha256 = str("sha256"),
        )
    } catch (_: JSONException) {
        null
    }

    private companion object {
        const val MILLIS_PER_HOUR = 3_600_000L
    }
}
