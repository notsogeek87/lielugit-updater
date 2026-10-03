package com.lielu.githubupdater.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Scriptable [HttpTransport]: maps a URL to a response (or an exception). */
internal class FakeTransport(private val handler: (String) -> HttpResponse) : HttpTransport {
    val requests = mutableListOf<String>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += url
        return handler(url)
    }
}

internal fun response(
    body: String = "",
    status: Int = 200,
    headers: Map<String, String> = emptyMap(),
) = HttpResponse(status, headers, body.toByteArray().size.toLong(), ByteArrayInputStream(body.toByteArray()))

internal fun bytesResponse(
    bytes: ByteArray,
    status: Int = 200,
    announcedLength: Long? = bytes.size.toLong(),
    body: InputStream = ByteArrayInputStream(bytes),
) = HttpResponse(status, emptyMap(), announcedLength, body)

/** Delivers [bytes] then fails like a dropped connection. */
internal class BrokenStream(private val bytes: ByteArray) : InputStream() {
    private var position = 0

    override fun read(): Int = throw UnsupportedOperationException()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= bytes.size) throw IOException("Connection reset")
        val n = minOf(len, bytes.size - position)
        bytes.copyInto(b, off, position, position + n)
        position += n
        return n
    }
}

internal fun asset(
    name: String,
    size: Long = 1000,
    url: String = "https://github.com/o/r/releases/download/v1/$name",
    digest: String? = null,
): String = buildString {
    append("""{"name":"$name","size":$size,"state":"uploaded","browser_download_url":"$url"""")
    if (digest != null) append(""","digest":"$digest"""")
    append("}")
}

internal fun releaseJson(
    tag: String = "v1.2.0",
    assets: List<String> = listOf(asset("app-universal.apk")),
    name: String? = "Release $tag",
    body: String? = "Notes",
    publishedAt: String? = "2026-01-02T03:04:05Z",
): String = buildString {
    append("""{"tag_name":"$tag"""")
    append(""","name":${name?.let { "\"$it\"" } ?: "null"}""")
    append(""","body":${body?.let { "\"$it\"" } ?: "null"}""")
    append(""","published_at":${publishedAt?.let { "\"$it\"" } ?: "null"}""")
    append(""","assets":[${assets.joinToString(",")}]}""")
}

internal fun githubAsset(name: String) = GithubAsset(name, 1000, "https://example.com/$name", null)
