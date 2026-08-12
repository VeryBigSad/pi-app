package io.github.verybigsad.pimobile.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.concurrent.atomic.AtomicLong
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PiMobileBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateStartupAndTimelineProfile() = baselineProfileRule.collect(
        packageName = BenchmarkContract.TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait()
        val runId = BenchmarkRunIds.next()
        startBenchmarkTimeline(runId)
        awaitBenchmarkTimeline(runId)
        scrollAwayFromLatest()
        runCatchUp(runId)
    }
}

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class PiMobileMacrobenchmark {
    @get:Rule
    val macrobenchmarkRule = MacrobenchmarkRule()

    private var activeRunId = 0L

    @Test
    fun coldStartupWithBaselineProfile() = macrobenchmarkRule.measureRepeated(
        packageName = BenchmarkContract.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = BenchmarkContract.ITERATIONS,
        startupMode = StartupMode.COLD,
    ) {
        startActivityAndWait()
    }

    @Test
    fun largeTimelineScrollAndCatchUpWithBaselineProfile() = macrobenchmarkRule.measureRepeated(
        packageName = BenchmarkContract.TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric(BenchmarkContract.CATCH_UP_TRACE_NAME, TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = BenchmarkContract.ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            activeRunId = BenchmarkRunIds.next()
            startBenchmarkTimeline(activeRunId)
            awaitBenchmarkTimeline(activeRunId)
        },
    ) {
        scrollAwayFromLatest()
        runCatchUp(activeRunId)
    }
}

private object BenchmarkContract {
    const val TARGET_PACKAGE = "io.github.verybigsad.pimobile"
    const val TARGET_ACTIVITY = "io.github.verybigsad.pimobile.MainActivity"
    const val EXTRA_SCENARIO = "io.github.verybigsad.pimobile.extra.BENCHMARK_SCENARIO"
    const val EXTRA_RUN_ID = "io.github.verybigsad.pimobile.extra.BENCHMARK_RUN_ID"
    const val TIMELINE_SCENARIO = "large_timeline"
    const val READY_DESCRIPTION = "Pi benchmark timeline ready"
    const val TIMELINE_DESCRIPTION = "Session timeline"
    const val JUMP_TO_LATEST_DESCRIPTION = "Scroll conversation to latest message"
    const val CATCH_UP_ACTION_DESCRIPTION = "Run deterministic benchmark catch-up"
    const val CATCH_UP_COMPLETE_DESCRIPTION = "Pi benchmark catch-up complete"
    const val CATCH_UP_TRACE_NAME = "PiBenchmarkCatchUp"
    const val ITERATIONS = 10
    const val UI_TIMEOUT_MILLIS = 10_000L
}

private object BenchmarkRunIds {
    private val nextId = AtomicLong(1L)

    fun next(): Long = nextId.getAndIncrement()
}

private fun MacrobenchmarkScope.startBenchmarkTimeline(runId: Long) {
    startActivityAndWait(
        Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(BenchmarkContract.TARGET_PACKAGE, BenchmarkContract.TARGET_ACTIVITY))
            .putExtra(BenchmarkContract.EXTRA_SCENARIO, BenchmarkContract.TIMELINE_SCENARIO)
            .putExtra(BenchmarkContract.EXTRA_RUN_ID, runId),
    )
}

private fun MacrobenchmarkScope.awaitBenchmarkTimeline(runId: Long) {
    check(
        device.wait(
            Until.hasObject(By.desc("${BenchmarkContract.READY_DESCRIPTION}:$runId")),
            BenchmarkContract.UI_TIMEOUT_MILLIS,
        ),
    ) { "Benchmark timeline did not become ready" }
    device.waitForIdle()
}

private fun MacrobenchmarkScope.scrollAwayFromLatest() {
    val timeline = requireNotNull(device.findObject(By.desc(BenchmarkContract.TIMELINE_DESCRIPTION))) {
        "Benchmark timeline is unavailable"
    }
    repeat(3) { timeline.fling(Direction.UP) }
    check(
        device.wait(
            Until.hasObject(By.descContains(BenchmarkContract.JUMP_TO_LATEST_DESCRIPTION)),
            BenchmarkContract.UI_TIMEOUT_MILLIS,
        ),
    ) { "Timeline did not leave the latest message" }
}

private fun MacrobenchmarkScope.runCatchUp(runId: Long) {
    val catchUp = requireNotNull(device.findObject(By.desc(BenchmarkContract.CATCH_UP_ACTION_DESCRIPTION))) {
        "Benchmark catch-up action is unavailable"
    }
    catchUp.click()
    check(
        device.wait(
            Until.hasObject(By.desc("${BenchmarkContract.CATCH_UP_COMPLETE_DESCRIPTION}:$runId")),
            BenchmarkContract.UI_TIMEOUT_MILLIS,
        ),
    ) { "Benchmark catch-up did not complete" }
}
