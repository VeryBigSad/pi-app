package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class MetadataFetcherTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fetcher() = MetadataFetcher(OkHttpClient(), server.url("/update-v1.json").toString())

    @Test
    fun fetchesBoundedDocument() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":1}"))
        assertThat(fetcher().fetch().decodeToString()).isEqualTo("{\"ok\":1}")
    }

    @Test
    fun rejectsHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val error = runCatching { fetcher().fetch() }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_FETCH_FAILED)
    }

    @Test
    fun rejectsDeclaredOversize() = runTest {
        val body = "x".repeat(17 * 1024)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val error = runCatching { fetcher().fetch() }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_TOO_LARGE)
    }

    @Test
    fun rejectsStreamingOversize() = runTest {
        val body = Buffer().write(ByteArray(17 * 1024) { 'y'.code.toByte() })
        server.enqueue(MockResponse().setResponseCode(200).setBody(body).removeHeader("Content-Length"))
        val error = runCatching { fetcher().fetch() }.exceptionOrNull()
        assertThat((error as UpdateException).code).isEqualTo(UpdateError.METADATA_TOO_LARGE)
    }
}
