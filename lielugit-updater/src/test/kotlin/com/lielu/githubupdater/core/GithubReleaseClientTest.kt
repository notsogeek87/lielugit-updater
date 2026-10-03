package com.lielu.githubupdater.core

import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GithubReleaseClientTest {

    private fun client(handler: (String) -> HttpResponse) = GithubReleaseClient(FakeTransport(handler))

    private fun errorOf(block: () -> Unit): UpdateError = assertFailsWith<UpdateException>(block = block).error

    @Test
    fun `parses a valid release`() {
        val transport = FakeTransport {
            response(
                releaseJson(
                    tag = "v1.4.0",
                    assets = listOf(asset("a.apk", size = 42, digest = "sha256:" + "AB".repeat(32))),
                ),
            )
        }
        val release = GithubReleaseClient(transport).fetchLatest("octo", "app")

        assertEquals("https://api.github.com/repos/octo/app/releases/latest", transport.requests.single())
        assertEquals("v1.4.0", release.tagName)
        assertEquals("Release v1.4.0", release.name)
        assertEquals("Notes", release.body)
        assertEquals("2026-01-02T03:04:05Z", release.publishedAt)
        val asset = release.assets.single()
        assertEquals("a.apk", asset.name)
        assertEquals(42L, asset.size)
        assertEquals("ab".repeat(32), asset.sha256)
    }

    @Test
    fun `null optional fields and ignored assets`() {
        val json = releaseJson(
            name = null,
            body = null,
            publishedAt = null,
            assets = listOf(
                asset("ok.apk"),
                """{"name":"pending.apk","state":"starter","browser_download_url":"https://x/pending.apk"}""",
            ),
        )
        val release = client { response(json) }.fetchLatest("o", "r")
        assertNull(release.name)
        assertNull(release.body)
        assertNull(release.publishedAt)
        assertEquals(listOf("ok.apk"), release.assets.map { it.name })
    }

    @Test
    fun `404 means release not found`() {
        val error = errorOf { client { response("{}", status = 404) }.fetchLatest("o", "r") }
        assertEquals(UpdateError.ReleaseNotFound, error)
    }

    @Test
    fun `other HTTP errors become GitHubApiError`() {
        listOf(500, 502, 503, 401, 422).forEach { code ->
            val error = errorOf { client { response("oops", status = code) }.fetchLatest("o", "r") }
            assertEquals(code, assertIs<UpdateError.GitHubApiError>(error).httpCode)
        }
    }

    @Test
    fun `403 with exhausted quota is a rate limit`() {
        val headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1760000000")
        val error = errorOf { client { response("{}", 403, headers) }.fetchLatest("o", "r") }
        assertEquals(UpdateError.RateLimit(1760000000L), error)
    }

    @Test
    fun `429 is a rate limit`() {
        val error = errorOf { client { response("{}", 429) }.fetchLatest("o", "r") }
        assertEquals(UpdateError.RateLimit(null), error)
    }

    @Test
    fun `plain 403 is not a rate limit`() {
        val headers = mapOf("x-ratelimit-remaining" to "12")
        val error = errorOf { client { response("{}", 403, headers) }.fetchLatest("o", "r") }
        assertIs<UpdateError.GitHubApiError>(error)
    }

    @Test
    fun `invalid JSON is reported, not thrown raw`() {
        listOf("not json", "", "[1,2]", "{\"tag_name\":").forEach { body ->
            val error = errorOf { client { response(body) }.fetchLatest("o", "r") }
            assertIs<UpdateError.GitHubApiError>(error, "body: $body")
        }
    }

    @Test
    fun `release without tag is rejected`() {
        val error = errorOf { client { response("""{"assets":[]}""") }.fetchLatest("o", "r") }
        assertIs<UpdateError.GitHubApiError>(error)
    }

    @Test
    fun `IO failures become NetworkError`() {
        val error = errorOf { client { throw IOException("Unable to resolve host") }.fetchLatest("o", "r") }
        assertEquals("Unable to resolve host", assertIs<UpdateError.NetworkError>(error).message)
    }

    @Test
    fun `owner and repository are url-encoded`() {
        val transport = FakeTransport { response(releaseJson()) }
        GithubReleaseClient(transport).fetchLatest("a b", "r/x")
        assertEquals("https://api.github.com/repos/a%20b/r%2Fx/releases/latest", transport.requests.single())
    }
}
