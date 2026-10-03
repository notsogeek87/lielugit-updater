package com.lielu.githubupdater.core

import com.lielu.githubupdater.UpdateConfig
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UpdateCheckerTest {

    private var now = 1_000_000_000L
    private var installed = InstalledApp("1.0.0", 1)
    private var latestJson = releaseJson(tag = "v1.2.0")
    private var extra: (String) -> HttpResponse? = { null }
    private val transport = FakeTransport { url -> extra(url) ?: response(latestJson) }
    private val store = InMemoryUpdateStore()

    private fun checker(config: UpdateConfig = UpdateConfig("o", "r")) = UpdateChecker(
        config = config,
        client = GithubReleaseClient(transport),
        store = store,
        installedApp = { installed },
        supportedAbis = { listOf("arm64-v8a", "armeabi-v7a") },
        clock = { now },
    )

    private fun error(block: () -> Unit) = assertFailsWith<UpdateException>(block = block).error

    @Test
    fun `returns the update when the release is newer`() {
        val update = assertNotNull(checker().check(force = false))
        assertEquals("1.2.0", update.versionName)
        assertEquals("Release v1.2.0", update.releaseName)
        assertEquals("Notes", update.releaseNotes)
        assertEquals("app-universal.apk", update.apkFileName)
        assertEquals("https://github.com/o/r/releases/download/v1/app-universal.apk", update.downloadUrl)
        assertEquals(1000L, update.apkSize)
        assertEquals("2026-01-02T03:04:05Z", update.publishedAt)
    }

    @Test
    fun `returns null when up to date or ahead`() {
        installed = InstalledApp("1.2.0", 5)
        assertNull(checker().check(force = false))
        installed = InstalledApp("1.10.0", 5)
        assertNull(checker().check(force = true))
    }

    @Test
    fun `compares 1_10_0 above 1_9_0 using the tag`() {
        installed = InstalledApp("1.9.0", 1)
        latestJson = releaseJson(tag = "release-1.10.0")
        assertEquals("1.10.0", checker().check(force = true)?.versionName)
    }

    @Test
    fun `no apk lookup when already up to date`() {
        installed = InstalledApp("1.2.0", 1)
        latestJson = releaseJson(tag = "v1.2.0", assets = emptyList())
        assertNull(checker().check(force = false))
    }

    @Test
    fun `newer release without apk is an error`() {
        latestJson = releaseJson(tag = "v1.2.0", assets = emptyList())
        assertIs<UpdateError.ApkNotFound>(error { checker().check(force = false) })
    }

    @Test
    fun `unparsable tag is InvalidVersion`() {
        latestJson = releaseJson(tag = "nightly")
        assertEquals(UpdateError.InvalidVersion("nightly"), error { checker().check(force = false) })
    }

    @Test
    fun `unparsable installed version is InvalidVersion`() {
        installed = InstalledApp(null, 1)
        assertIs<UpdateError.InvalidVersion>(error { checker().check(force = false) })
    }

    @Test
    fun `same name with a higher version code in the tag is an update`() {
        installed = InstalledApp("1.2.0", 10)
        latestJson = releaseJson(tag = "v1.2.0+11")
        assertEquals(11L, checker().check(force = true)?.versionCode)
        latestJson = releaseJson(tag = "v1.2.0+10")
        assertNull(checker().check(force = true))
    }

    @Test
    fun `release errors are propagated`() {
        extra = { response("{}", 404) }
        assertEquals(UpdateError.ReleaseNotFound, error { checker().check(force = false) })
        extra = { response("{}", 429) }
        assertIs<UpdateError.RateLimit>(error { checker().check(force = false) })
    }

    // --- cache ---------------------------------------------------------------------------------

    @Test
    fun `second check within the interval does not hit the network`() {
        val checker = checker()
        val first = checker.check(force = false)
        now += 23 * 3_600_000L
        val second = checker.check(force = false)
        assertEquals(1, transport.requests.size)
        assertEquals(first, second)
    }

    @Test
    fun `an up-to-date answer is cached too`() {
        installed = InstalledApp("1.2.0", 1)
        val checker = checker()
        assertNull(checker.check(force = false))
        assertNull(checker.check(force = false))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `check after the interval refreshes`() {
        val checker = checker()
        checker.check(force = false)
        now += 24 * 3_600_000L
        checker.check(force = false)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `force ignores the cache`() {
        val checker = checker()
        checker.check(force = false)
        checker.check(force = true)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `interval of zero disables the cache`() {
        val checker = checker(UpdateConfig("o", "r", checkIntervalHours = 0))
        checker.check(force = false)
        checker.check(force = false)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `a clock moved backwards invalidates the cache`() {
        val checker = checker()
        checker.check(force = false)
        now -= 3_600_000L
        checker.check(force = false)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `cache is re-evaluated after the app was updated`() {
        val checker = checker()
        assertNotNull(checker.check(force = false))
        installed = InstalledApp("1.2.0", 2) // the user installed the update meanwhile
        assertNull(checker.check(force = false))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a different repository does not reuse the cache`() {
        checker().check(force = false)
        checker(UpdateConfig("o", "other")).check(force = false)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `failures are not cached`() {
        extra = { response("{}", 500) }
        val checker = checker()
        error { checker.check(force = false) }
        extra = { null }
        assertNotNull(checker.check(force = false))
    }

    // --- checksum ------------------------------------------------------------------------------

    @Test
    fun `sha256 comes from the asset digest`() {
        val digest = "ab".repeat(32)
        latestJson = releaseJson(assets = listOf(asset("app.apk", digest = "sha256:$digest")))
        assertEquals(digest, checker().check(force = true)?.sha256)
    }

    @Test
    fun `sha256 comes from a sidecar file when there is no digest`() {
        val hex = "CD".repeat(32)
        latestJson = releaseJson(
            assets = listOf(asset("app.apk"), asset("app.apk.sha256", url = "https://example.com/app.apk.sha256")),
        )
        extra = { url -> if (url.endsWith(".sha256")) response("$hex  app.apk\n") else null }
        assertEquals(hex.lowercase(), checker().check(force = true)?.sha256)
    }

    @Test
    fun `corrupt sidecar file is an error`() {
        latestJson = releaseJson(
            assets = listOf(asset("app.apk"), asset("app.apk.sha256", url = "https://example.com/app.apk.sha256")),
        )
        extra = { url -> if (url.endsWith(".sha256")) response("not a hash") else null }
        assertIs<UpdateError.GitHubApiError>(error { checker().check(force = true) })
    }

    @Test
    fun `no checksum source means no sha256`() {
        assertNull(checker().check(force = true)?.sha256)
    }

    @Test
    fun `non-https download url is refused`() {
        latestJson = releaseJson(assets = listOf(asset("app.apk", url = "http://example.com/app.apk")))
        assertIs<UpdateError.ApkNotFound>(error { checker().check(force = true) })
    }
}
