package com.lielu.githubupdater.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP abstraction so the logic can be unit-tested without a network. */
internal interface HttpTransport {
    /** Performs a GET. Throws [IOException] if the server cannot be reached. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

internal open class HttpResponse(
    val statusCode: Int,
    /** Header names are lower-cased. */
    val headers: Map<String, String>,
    /** `Content-Length` when announced by the server. */
    val contentLength: Long?,
    /** Response body; for non-2xx statuses it is the error body. Always close the response. */
    val body: InputStream,
) : Closeable {
    val isSuccessful: Boolean get() = statusCode in 200..299

    override fun close() {
        try {
            body.close()
        } catch (_: IOException) {
        }
    }
}

/** [HttpTransport] backed by the platform's `HttpURLConnection` (no extra dependency). */
internal class HttpUrlConnectionTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true // GitHub redirects assets to its CDN (https)
            connection.requestMethod = "GET"
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?: "".byteInputStream()
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.lastOrNull().orEmpty() }
            val length = connection.contentLengthLong.takeIf { it >= 0 }

            return object : HttpResponse(code, responseHeaders, length, body) {
                override fun close() {
                    super.close()
                    connection.disconnect()
                }
            }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }
}
