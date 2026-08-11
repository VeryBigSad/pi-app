package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun roundtrip() {
        val store = UpdateStore(folder.root)
        val candidate = PersistedCandidate(
            versionCode = 5L,
            versionName = "0.5.0",
            publishedAt = "2026-08-11T00:00:00Z",
            releasePageUrl = "https://example.com",
            apkUrl = "https://example.com/a.apk",
            apkSizeBytes = 10L,
            apkSha256 = "a".repeat(64),
            verified = true,
            createdAtMillis = 1L,
        )
        store.write(PersistedUpdateSnapshot(highWaterMark = 5L, candidate = candidate, authorizedVersionCode = 5L))
        val restored = store.read()
        assertThat(restored.highWaterMark).isEqualTo(5L)
        assertThat(restored.candidate?.verified).isTrue()
        assertThat(restored.authorizedVersionCode).isEqualTo(5L)
    }

    @Test
    fun emptyStoreDefaults() {
        val store = UpdateStore(folder.root)
        assertThat(store.read().highWaterMark).isEqualTo(0L)
        assertThat(store.read().candidate).isNull()
    }

    @Test
    fun corruptedStateFailsClosedAndQuarantines() {
        val store = UpdateStore(folder.root)
        File(folder.root, "state.json").writeBytes("{ not json !!!".encodeToByteArray())
        val snapshot = store.read()
        assertThat(snapshot.candidate).isNull()
        assertThat(snapshot.highWaterMark).isEqualTo(0L)
        assertThat(File(folder.root, "state.json").exists()).isFalse()
        assertThat(folder.root.listFiles().orEmpty().any { it.name.startsWith("state.json.corrupt-") }).isTrue()
    }

    @Test
    fun mutateIsAtomic() {
        val store = UpdateStore(folder.root)
        store.mutate { it.copy(highWaterMark = 3L) }
        store.mutate { it.copy(highWaterMark = it.highWaterMark + 1) }
        assertThat(store.read().highWaterMark).isEqualTo(4L)
    }
}
