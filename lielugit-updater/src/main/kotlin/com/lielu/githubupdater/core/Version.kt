package com.lielu.githubupdater.core

/**
 * A parsed version following Semantic Versioning precedence rules (numeric segments compared as
 * numbers, a pre-release is lower than its release, build metadata ignored for ordering).
 */
internal class Version private constructor(
    private val numbers: List<Long>,
    private val preRelease: List<String>,
    /** Numeric SemVer build metadata (`1.2.3+45`), used as an Android versionCode hint. */
    val buildNumber: Long?,
) : Comparable<Version> {

    /** `1.2.3` or `1.2.3-beta.1`, without any tag prefix or build metadata. */
    val normalized: String =
        numbers.joinToString(".") + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    override fun compareTo(other: Version): Int {
        val size = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until size) {
            val c = (numbers.getOrElse(i) { 0L }).compareTo(other.numbers.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        // 1.0.0-rc.1 < 1.0.0
        if (preRelease.isEmpty() != other.preRelease.isEmpty()) {
            return if (preRelease.isEmpty()) 1 else -1
        }
        val n = minOf(preRelease.size, other.preRelease.size)
        for (i in 0 until n) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers have lower precedence than alphanumeric
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0

    override fun hashCode(): Int {
        val trimmed = numbers.dropLastWhile { it == 0L }
        return 31 * trimmed.hashCode() + preRelease.hashCode()
    }

    override fun toString(): String = normalized

    companion object {
        // Optional alphabetic prefix ("v", "release-", "app_v"), 1 to 4 numeric segments,
        // optional "-prerelease", optional "+build".
        private val PATTERN = Regex(
            """^[A-Za-z_\- ]*[vV]?(\d+(?:\.\d+){0,3})(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?(?:\+([0-9A-Za-z.\-]+))?$""",
        )

        /** Returns `null` when [raw] is not a recognizable version. */
        fun parseOrNull(raw: String?): Version? {
            val match = PATTERN.matchEntire(raw?.trim().orEmpty()) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toLongOrNull() ?: return null }
            val pre = match.groupValues[2].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            val build = match.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull()
            return Version(numbers, pre, build)
        }
    }
}
