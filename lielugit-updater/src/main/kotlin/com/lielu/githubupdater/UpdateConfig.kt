package com.lielu.githubupdater

/**
 * Configuration of an [UpdateManager].
 *
 * @property githubOwner GitHub user or organization that owns the repository.
 * @property githubRepository Repository whose *latest* GitHub Release is checked.
 * @property apkAssetNamePattern Optional regular expression the **whole** asset file name must
 *   match (`Regex.matches`), e.g. `".*\\.apk"` or `"myapp-arm64-v8a\\.apk"`. When `null`, every
 *   asset ending in `.apk` is a candidate. Several candidates are resolved by CPU architecture,
 *   see the README ("Choosing the APK").
 * @property checkIntervalHours Minimum delay between two network checks. Within this delay
 *   [UpdateManager.checkForUpdate] answers from its cache, unless called with `force = true`.
 *   `0` disables the cache.
 */
public data class UpdateConfig(
    val githubOwner: String,
    val githubRepository: String,
    val apkAssetNamePattern: String? = null,
    val checkIntervalHours: Int = 24,
) {
    init {
        require(githubOwner.isNotBlank()) { "githubOwner must not be blank" }
        require(githubRepository.isNotBlank()) { "githubRepository must not be blank" }
        require(checkIntervalHours >= 0) { "checkIntervalHours must be >= 0" }
        // Fail fast on an invalid regular expression (throws PatternSyntaxException).
        apkAssetNamePattern?.let { Regex(it) }
    }
}
