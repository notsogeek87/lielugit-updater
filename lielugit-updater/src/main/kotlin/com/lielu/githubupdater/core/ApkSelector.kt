package com.lielu.githubupdater.core

import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException

/**
 * Chooses the APK of a release. Rules, in order:
 *  1. Candidates are assets ending in `.apk` whose full name matches [pattern] (if any).
 *  2. An asset named for a CPU ABI the device does not support is **never** selected.
 *  3. Among the rest: the ABI best matching the device ([supportedAbis] is ordered by preference),
 *     then a `universal` APK, then a single APK that names no ABI.
 *  4. Anything ambiguous is an error rather than a guess.
 */
internal object ApkSelector {

    private enum class Abi(val id: String, val regex: Regex) {
        ARM64("arm64-v8a", token("arm64[-_]v8a|arm64")),
        ARMV7("armeabi-v7a", token("armeabi[-_]v7a|armv7a?|armeabi")),
        X86_64("x86_64", token("x86[-_]64")),
        X86("x86", token("x86|i686")),
        UNIVERSAL("universal", token("universal|fat"));
    }

    private fun token(alternatives: String) =
        Regex("(?<![A-Za-z0-9])(?:$alternatives)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    /** The ABI an asset name announces, or `null` when it names none. */
    private fun abiOf(fileName: String): Abi? {
        // Order matters: x86_64 must be tested before x86.
        val order = listOf(Abi.ARM64, Abi.ARMV7, Abi.X86_64, Abi.X86, Abi.UNIVERSAL)
        return order.firstOrNull { it.regex.containsMatchIn(fileName) }
    }

    fun select(assets: List<GithubAsset>, pattern: String?, supportedAbis: List<String>): GithubAsset {
        val regex = pattern?.let { Regex(it) }
        val candidates = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) && (regex == null || regex.matches(asset.name))
        }
        if (candidates.isEmpty()) {
            throw notFound(
                if (regex == null) "The release contains no .apk asset"
                else "No .apk asset matches the pattern \"$pattern\" " +
                    "(assets: ${assets.joinToString { it.name }.ifEmpty { "none" }})",
            )
        }

        val deviceAbis = supportedAbis.map { it.lowercase() }
        val tagged = candidates.map { it to abiOf(it.name) }
        val compatible = tagged.filter { (_, abi) ->
            abi == null || abi == Abi.UNIVERSAL || abi.id in deviceAbis
        }
        if (compatible.isEmpty()) {
            throw notFound(
                "No compatible APK: the candidates (${candidates.joinToString { it.name }}) " +
                    "target other CPU architectures than this device (${deviceAbis.joinToString()})",
            )
        }

        // Best architecture match first, following the device's own preference order.
        for (deviceAbi in deviceAbis) {
            val exact = compatible.filter { (_, abi) -> abi?.id == deviceAbi }
            if (exact.size == 1) return exact.single().first
            if (exact.size > 1) throw ambiguous(exact.map { it.first })
        }
        val universal = compatible.filter { (_, abi) -> abi == Abi.UNIVERSAL }
        if (universal.size == 1) return universal.single().first
        if (universal.size > 1) throw ambiguous(universal.map { it.first })

        val untagged = compatible.filter { (_, abi) -> abi == null }
        if (untagged.size == 1) return untagged.single().first
        throw ambiguous(untagged.map { it.first })
    }

    private fun ambiguous(assets: List<GithubAsset>) = notFound(
        "Several APKs match (${assets.joinToString { it.name }}). " +
            "Narrow it down with UpdateConfig.apkAssetNamePattern.",
    )

    private fun notFound(message: String) = UpdateException(UpdateError.ApkNotFound(message))
}
