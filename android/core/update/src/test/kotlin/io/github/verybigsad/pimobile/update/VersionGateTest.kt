package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** versionCode is the sole ordering authority; high-water mark never moves backward. */
class VersionGateTest {
    @Test
    fun higherVersionCodeIsNewer() {
        assertThat(isNewer(candidate = 3, highWaterMark = 2, current = 1)).isTrue()
    }

    @Test
    fun equalToHighWaterMarkIsNotNewer() {
        assertThat(isNewer(candidate = 2, highWaterMark = 2, current = 1)).isFalse()
    }

    @Test
    fun lowerVersionNameButHigherVersionCodeWins() {
        // versionName is display-only.
        assertThat(isNewer(candidate = 10, highWaterMark = 9, current = 1)).isTrue()
    }

    @Test
    fun highWaterMarkStartsAtCurrentVersion() {
        val snapshot = PersistedUpdateSnapshot()
        val effective = maxOf(snapshot.highWaterMark, 7L)
        assertThat(effective).isEqualTo(7L)
        assertThat(isNewer(candidate = 7, highWaterMark = effective, current = 7)).isFalse()
    }

    private fun isNewer(candidate: Long, highWaterMark: Long, current: Long): Boolean =
        candidate > maxOf(highWaterMark, current)
}
