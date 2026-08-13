package io.github.verybigsad.pimobile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BenchmarkTimelineHarnessTest {
    @Test
    fun onlyProfileableBenchmarkBuildTypesCanExposeTheFixture() {
        assertThat(isBenchmarkHarnessBuildType("benchmarkRelease")).isTrue()
        assertThat(isBenchmarkHarnessBuildType("nonMinifiedRelease")).isTrue()
        assertThat(isBenchmarkHarnessBuildType("release")).isFalse()
        assertThat(isBenchmarkHarnessBuildType("debug")).isFalse()
    }

    @Test
    fun fixtureModelsTenThousandHistoricalEventsWithFiveHundredRetainedMessages() {
        val state = BenchmarkTimelineFixture.initialSessionState()

        assertThat(BenchmarkTimelineFixture.HISTORY_EVENT_COUNT).isEqualTo(10_000)
        assertThat(state.conversation.finalizedMessages).hasSize(500)
        assertThat(state.conversation.finalizedMessages.first().appendOrdinal).isEqualTo(9_501L)
        assertThat(state.conversation.finalizedMessages.last().appendOrdinal).isEqualTo(10_000L)
        assertThat(state.conversation.cursor?.sequence?.text).isEqualTo("10000")
        assertThat(state.conversation.hasOlderMessages).isTrue()
    }

    @Test
    fun catchUpKeepsTheRetentionWindowAndAdvancesAtOneHundredEventsPerSecond() {
        val caughtUp = (1..BenchmarkTimelineFixture.CATCH_UP_EVENT_COUNT).fold(
            BenchmarkTimelineFixture.initialSessionState(),
        ) { state, eventIndex ->
            BenchmarkTimelineFixture.applyCatchUpEvent(state, eventIndex)
        }

        assertThat(BenchmarkTimelineFixture.CATCH_UP_EVENT_COUNT * BenchmarkTimelineFixture.CATCH_UP_INTERVAL_MILLIS)
            .isEqualTo(1_000L)
        assertThat(caughtUp.conversation.finalizedMessages).hasSize(500)
        assertThat(caughtUp.conversation.finalizedMessages.first().appendOrdinal).isEqualTo(9_601L)
        assertThat(caughtUp.conversation.finalizedMessages.last().appendOrdinal).isEqualTo(10_100L)
        assertThat(caughtUp.conversation.cursor?.sequence?.text).isEqualTo("10100")
    }
}
