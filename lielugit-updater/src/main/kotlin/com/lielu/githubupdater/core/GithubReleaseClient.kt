package com.lielu.githubupdater.core

import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** Reads releases from the public GitHub REST API. No token is needed for public repositories. */
internal class GithubReleaseClient(
    private val transport: HttpTransport,
    private val apiBaseUrl: String = "https://api.github.com",
) {

    /** Fetches `GET /repos/{owner}/{repo}/releases/latest`. Throws [UpdateException]. */
    fun fetchLatest(owner: String, repository: String): GithubRelease {
        val url = "$apiBaseUrl/repos/${encode(owner)}/${encode(repository)}/releases/latest"
        val text = getText(url, API_HEADERS)
        return try {
            parse(text)
        } catch (e: JSONException) {
            throw UpdateException(UpdateError.GitHubApiError(200, "Invalid JSON from GitHub: ${e.message}"))
        }
    }

    /** Downloads a small text asset (checksum file). Throws [UpdateException]. */
    fun fetchSmallText(url: String): String = getText(url, mapOf("User-Agent" to USER_AGENT), maxBytes = 4096)

    private fun getText(url: String, headers: Map<String, String>, maxBytes: Int = 4 * 1024 * 1024): String {
        val response = try {
            transport.get(url, headers)
        } catch (e: IOException) {
            throw UpdateException(UpdateError.NetworkError(e.message ?: "Network unreachable", e))
        }
        response.use {
            if (!it.isSuccessful) throw UpdateException(errorFor(it))
            return try {
                val bytes = it.body.readNBytesCompat(maxBytes + 1)
                if (bytes.size > maxBytes) {
                    throw UpdateException(UpdateError.GitHubApiError(it.statusCode, "Response too large"))
                }
                String(bytes, Charsets.UTF_8)
            } catch (e: IOException) {
                throw UpdateException(UpdateError.NetworkError(e.message ?: "Connection lost", e))
            }
        }
    }

    private fun errorFor(response: HttpResponse): UpdateError {
        val code = response.statusCode
        val remaining = response.headers["x-ratelimit-remaining"]
        val reset = response.headers["x-ratelimit-reset"]?.toLongOrNull()
        return when {
            code == 429 || ((code == 403 || code == 401) && remaining == "0") -> UpdateError.RateLimit(reset)
            code == 404 -> UpdateError.ReleaseNotFound
            else -> UpdateError.GitHubApiError(code, "GitHub answered HTTP $code")
        }
    }

    private fun parse(text: String): GithubRelease {
        val json = JSONObject(text)
        val tag = json.optString("tag_name", "").ifEmpty {
            throw UpdateException(UpdateError.GitHubApiError(200, "Release has no tag_name"))
        }
        val assetsJson = json.optJSONArray("assets")
        val assets = buildList {
            for (i in 0 until (assetsJson?.length() ?: 0)) {
                val a = assetsJson!!.getJSONObject(i)
                val state = a.optString("state", "uploaded")
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                if (state != "uploaded" || name.isEmpty() || url.isEmpty()) continue
                add(
                    GithubAsset(
                        name = name,
                        size = if (a.has("size") && !a.isNull("size")) a.getLong("size") else null,
                        downloadUrl = url,
                        sha256 = a.optString("digest", "").removePrefix("sha256:").lowercase()
                            .takeIf { digest -> digest.length == 64 && digest.all { c -> c in HEX } },
                    ),
                )
            }
        }
        return GithubRelease(
            tagName = tag,
            name = json.optStringOrNull("name"),
            body = json.optStringOrNull("body"),
            publishedAt = json.optStringOrNull("published_at"),
            assets = assets,
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun encode(segment: String) = java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val USER_AGENT = "lielugit-updater"
        val API_HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to USER_AGENT,
        )
        private val HEX = "0123456789abcdef".toSet()
    }
}
