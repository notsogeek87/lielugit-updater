package com.lielu.githubupdater.core

import com.lielu.githubupdater.DownloadProgress
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateException
import com.lielu.githubupdater.UpdateInfo
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class ApkDownloaderTest {

    private lateinit var dir: File
    private val payload = ByteArray(100_000) { (it % 251).toByte() }

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("updates", "").apply { delete() }.resolve(ApkDownloader.DIRECTORY_NAME)
    }

    @AfterTest
    fun tearDown() {
        dir.parentFile.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun update(
        name: String = "app-1.2.0.apk",
        size: Long? = payload.size.toLong(),
        sha256: String? = null,
        url: String = "https://example.com/$name",
    ) = UpdateInfo("1.2.0", null, null, null, url, name, size, null, sha256)

    private fun downloader(handler: (String) -> HttpResponse) = ApkDownloader(FakeTransport(handler), dir)

    private suspend fun ApkDownloader.run(update: UpdateInfo) = download(update).toList()

    private fun leftovers() = dir.listFiles().orEmpty().map { it.name }

    @Test
    fun `successful download writes the file and reports progress up to 100 percent`() = runTest {
        val events = downloader { bytesResponse(payload) }.run(update(sha256 = sha256(payload)))

        val done = assertIs<DownloadEvent.Done>(events.last())
        assertEquals("app-1.2.0.apk", done.file.name)
        assertContentEquals(payload, done.file.readBytes())
        assertEquals(listOf("app-1.2.0.apk"), leftovers())

        val progress = events.filterIsInstance<DownloadEvent.Progress>().map { it.value }
        assertEquals(DownloadProgress(0, payload.size.toLong(), 0), progress.first())
        assertEquals(DownloadProgress(payload.size.toLong(), payload.size.toLong(), 100), progress.last())
        val percentages = progress.map { it.percentage!! }
        assertEquals(percentages.sorted(), percentages)
    }

    @Test
    fun `unknown size gives progress without percentage`() = runTest {
        val events = downloader { bytesResponse(payload, announcedLength = null) }.run(update(size = null))
        val progress = events.filterIsInstance<DownloadEvent.Progress>().map { it.value }
        assertNull(progress.first().percentage)
        assertEquals(payload.size.toLong(), progress.last().downloadedBytes)
        assertIs<DownloadEvent.Done>(events.last())
    }

    @Test
    fun `interrupted download fails and leaves nothing behind`() = runTest {
        val broken = BrokenStream(payload.copyOf(30_000))
        val downloader = downloader { bytesResponse(payload, body = broken) }

        val error = assertFailsWith<UpdateException> { downloader.run(update()) }.error

        assertIs<UpdateError.DownloadError>(error)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `truncated download is detected from the announced length`() = runTest {
        val short = payload.copyOf(10_000)
        val downloader = downloader { bytesResponse(short, announcedLength = payload.size.toLong()) }

        val error = assertFailsWith<UpdateException> { downloader.run(update()) }.error

        assertIs<UpdateError.DownloadError>(error)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `size announced by GitHub is enforced`() = runTest {
        val downloader = downloader { bytesResponse(payload, announcedLength = null) }
        val error = assertFailsWith<UpdateException> { downloader.run(update(size = payload.size + 1L)) }.error
        assertIs<UpdateError.DownloadError>(error)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `wrong checksum is rejected and the file deleted`() = runTest {
        val expected = sha256(payload.reversedArray())
        val downloader = downloader { bytesResponse(payload) }

        val error = assertFailsWith<UpdateException> { downloader.run(update(sha256 = expected)) }.error

        val mismatch = assertIs<UpdateError.ChecksumMismatch>(error)
        assertEquals(expected, mismatch.expected)
        assertEquals(sha256(payload), mismatch.actual)
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun `checksum comparison ignores case`() = runTest {
        val events = downloader { bytesResponse(payload) }.run(update(sha256 = sha256(payload).uppercase()))
        assertIs<DownloadEvent.Done>(events.last())
    }

    @Test
    fun `HTTP error status is a download error`() = runTest {
        val error = assertFailsWith<UpdateException> {
            downloader { response("", status = 404) }.run(update())
        }.error
        assertIs<UpdateError.DownloadError>(error)
    }

    @Test
    fun `unreachable network is a network error`() = runTest {
        val error = assertFailsWith<UpdateException> {
            downloader { throw IOException("offline") }.run(update())
        }.error
        assertIs<UpdateError.NetworkError>(error)
    }

    @Test
    fun `old downloads are cleaned before a new one`() = runTest {
        dir.mkdirs()
        File(dir, "app-1.0.0.apk").writeText("old")
        File(dir, "app-1.1.0.apk.part").writeText("stale")

        downloader { bytesResponse(payload) }.run(update())

        assertEquals(listOf("app-1.2.0.apk"), leftovers())
    }

    @Test
    fun `cancelling mid-download removes the partial file`() = runTest {
        val downloader = downloader { bytesResponse(payload) }
        // Stop collecting after the first event: the flow is cancelled while the file is open.
        downloader.download(update()).first()
        assertTrue(leftovers().none { it.endsWith(".apk") })
    }

    @Test
    fun `unsafe urls and file names are refused`() = runTest {
        val downloader = downloader { bytesResponse(payload) }
        listOf(
            update(url = "http://example.com/a.apk"),
            update(name = "../evil.apk"),
            update(name = "sub/dir.apk"),
            update(name = "notanapk.txt"),
            update(name = ""),
        ).forEach {
            val error = assertFailsWith<UpdateException> { downloader.run(it) }.error
            assertIs<UpdateError.DownloadError>(error)
        }
    }
}
