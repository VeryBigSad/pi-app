package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun candidate(url: String) = UpdateCandidate(
        versionCode = 2L,
        versionName = "0.2.0",
        publishedAt = "2026-08-11T00:00:00Z",
        releasePageUrl = "https://example.com",
        apkUrl = url,
        apkSizeBytes = payload.size.toLong(),
        apkSha256 = sha,
        etag = "\"v2\"",
    )

    private fun downloader() = ApkDownloader(OkHttpClient()) { Long.MAX_VALUE }

    private fun files(): Pair<File, File> =
        File(folder.root, "candidate-2.apk") to File(folder.root, "candidate-2.apk.part")

    @Test
    fun full200Download() = runTest {
        val url = server.url("/app.apk").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)).setHeader("ETag", "\"v2\""))
        val (target, partial) = files()
        val outcome = downloader().download(candidate(url), target, partial)
        assertThat(outcome.bytesWritten).isEqualTo(payload.size.toLong())
        assertThat(outcome.sha256Hex).isEqualTo(sha)
        assertThat(target.readBytes()).isEqualTo(payload)
        assertThat(partial.exists()).isFalse()
    }

    @Test
    fun resumesWith206Append() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        val half = payload.size / 2
        partial.writeBytes(payload.copyOfRange(0, half))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(Buffer().write(payload.copyOfRange(half, payload.size)))
                .setHeader("ETag", "\"v2\"")
                .setHeader("Content-Range", "bytes $half-${payload.size - 1}/${payload.size}"),
        )
        val outcome = downloader().download(candidate(url), target, partial)
        assertThat(outcome.bytesWritten).isEqualTo(payload.size.toLong())
        assertThat(target.readBytes()).isEqualTo(payload)
        val request = server.takeRequest()
        assertThat(request.getHeader("Range")).isEqualTo("bytes=$half-")
        assertThat(request.getHeader("If-Range")).isEqualTo("\"v2\"")
    }

    @Test
    fun server200ToRangeRequestRestarts() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        partial.writeBytes(payload.copyOfRange(0, 100))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val outcome = downloader().download(candidate(url), target, partial)
        assertThat(outcome.bytesWritten).isEqualTo(payload.size.toLong())
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun range416WithCompletePartialFinalizes() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        partial.writeBytes(payload)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${payload.size}"))
        val outcome = downloader().download(candidate(url), target, partial)
        assertThat(outcome.bytesWritten).isEqualTo(payload.size.toLong())
        assertThat(target.readBytes()).isEqualTo(payload)
    }

    @Test
    fun range416WithShortPartialFails() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        partial.writeBytes(payload.copyOfRange(0, 10))
        server.enqueue(MockResponse().setResponseCode(416))
        val error = runCatching { downloader().download(candidate(url), target, partial) }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_SIZE_MISMATCH)
        assertThat(partial.exists()).isFalse()
    }

    @Test
    fun contentRangeStartMismatchFailsAndDropsPartial() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        val half = payload.size / 2
        partial.writeBytes(payload.copyOfRange(0, half))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(Buffer().write(payload.copyOfRange(0, half)))
                .setHeader("Content-Range", "bytes 0-${half - 1}/${payload.size}"),
        )
        val error = runCatching { downloader().download(candidate(url), target, partial) }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_FAILED)
        assertThat(partial.exists()).isFalse()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun contentRangeTotalMismatchFails() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        val half = payload.size / 2
        partial.writeBytes(payload.copyOfRange(0, half))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(Buffer().write(payload.copyOfRange(half, payload.size)))
                .setHeader("Content-Range", "bytes $half-${payload.size - 1}/${payload.size + 1}"),
        )
        val error = runCatching { downloader().download(candidate(url), target, partial) }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_FAILED)
    }

    @Test
    fun preflightCreditsExistingPartialBytes() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        val half = payload.size / 2
        partial.writeBytes(payload.copyOfRange(0, half))
        // Free space below sizeBytes*2, but sizeBytes*2 minus the partial fits.
        val tight = ApkDownloader(OkHttpClient()) { payload.size.toLong() * 2 - half }
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setBody(Buffer().write(payload.copyOfRange(half, payload.size)))
                .setHeader("Content-Range", "bytes $half-${payload.size - 1}/${payload.size}"),
        )
        val outcome = tight.download(candidate(url), target, partial)
        assertThat(outcome.bytesWritten).isEqualTo(payload.size.toLong())
    }

    @Test
    fun hashMismatchDeletesPartial() = runTest {
        val url = server.url("/app.apk").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(payload.size) { 7 })))
        val (target, partial) = files()
        val error = runCatching { downloader().download(candidate(url), target, partial) }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_HASH_MISMATCH)
        assertThat(partial.exists()).isFalse()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun oversizeStreamFails() = runTest {
        val url = server.url("/app.apk").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload + byteArrayOf(1))))
        val (target, partial) = files()
        val error = runCatching { downloader().download(candidate(url), target, partial) }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_SIZE_MISMATCH)
    }

    @Test
    fun redirectIsFollowed() = runTest {
        val url = server.url("/old.apk").toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/app.apk"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val (target, partial) = files()
        val outcome = downloader().download(candidate(url), target, partial)
        assertThat(outcome.bytesWritten).isEqualTo(payload.size.toLong())
    }

    @Test
    fun insufficientSpacePreflight() = runTest {
        val url = server.url("/app.apk").toString()
        val (target, partial) = files()
        val tight = ApkDownloader(OkHttpClient()) { payload.size.toLong() }
        val error = runCatching { tight.download(candidate(url), target, partial) }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_INSUFFICIENT_SPACE)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun serverErrorFails() = runTest {
        val url = server.url("/app.apk").toString()
        server.enqueue(MockResponse().setResponseCode(503))
        val (target, partial) = files()
        val error = runCatching { downloader().download(candidate(url), target, partial) }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.DOWNLOAD_FAILED)
    }
}
