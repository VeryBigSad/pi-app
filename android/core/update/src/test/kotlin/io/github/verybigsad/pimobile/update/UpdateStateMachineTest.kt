package io.github.verybigsad.pimobile.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateStateMachineTest {
    private val candidate = UpdateCandidate(
        versionCode = 2L,
        versionName = "0.2.0",
        publishedAt = "2026-08-11T00:00:00Z",
        releasePageUrl = "https://example.com/r",
        apkUrl = "https://example.com/a.apk",
        apkSizeBytes = 100L,
        apkSha256 = "a".repeat(64),
    )

    @Test
    fun happyPathTransitions() {
        var state: UpdateState = UpdateState.Idle
        fun to(next: UpdateState) {
            UpdateStateMachine.requireTransition(state, next)
            state = next
        }
        to(UpdateState.Checking)
        to(UpdateState.Available(candidate))
        to(UpdateState.Downloading(candidate))
        to(UpdateState.Verifying(candidate))
        to(UpdateState.ReadyToInstall(candidate))
        to(UpdateState.Staging(candidate, 7))
        to(UpdateState.AwaitingSystemConfirmation(candidate, 7))
        to(UpdateState.Installing(candidate, 7))
        to(UpdateState.Installed(2L))
        assertThat(state).isInstanceOf(UpdateState.Installed::class.java)
    }

    @Test
    fun illegalTransitionThrows() {
        val error = runCatching {
            UpdateStateMachine.requireTransition(UpdateState.Idle, UpdateState.Installing(candidate, 7))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(UpdateException::class.java)
    }

    @Test
    fun disabledIsTerminal() {
        assertThat(UpdateStateMachine.canTransition(UpdateState.Disabled, UpdateState.Idle)).isFalse()
    }

    @Test
    fun downloadRequiresAvailable() {
        assertThat(UpdateStateMachine.canTransition(UpdateState.Idle, UpdateState.Downloading(candidate))).isFalse()
        assertThat(UpdateStateMachine.canTransition(UpdateState.Available(candidate), UpdateState.Downloading(candidate))).isTrue()
    }

    @Test
    fun installRequiresVerification() {
        assertThat(UpdateStateMachine.canTransition(UpdateState.Downloading(candidate), UpdateState.Staging(candidate, 1))).isFalse()
        assertThat(UpdateStateMachine.canTransition(UpdateState.Verifying(candidate), UpdateState.Staging(candidate, 1))).isFalse()
        assertThat(UpdateStateMachine.canTransition(UpdateState.ReadyToInstall(candidate), UpdateState.Staging(candidate, 1))).isTrue()
    }

    @Test
    fun failureRecoverableToIdle() {
        val failed = UpdateState.Failed(UpdateError.DOWNLOAD_FAILED, "x", candidate)
        assertThat(UpdateStateMachine.canTransition(failed, UpdateState.Idle)).isTrue()
    }

    @Test
    fun periodicCheckDuringActiveCandidateIsLegal() {
        // Periodic checks during an active candidate must be no-ops, never exceptions.
        assertThat(UpdateStateMachine.canTransition(UpdateState.Downloading(candidate), UpdateState.Checking)).isTrue()
        assertThat(UpdateStateMachine.canTransition(UpdateState.Paused(candidate), UpdateState.Checking)).isTrue()
        assertThat(UpdateStateMachine.canTransition(UpdateState.ReadyToInstall(candidate), UpdateState.Checking)).isTrue()
        assertThat(UpdateStateMachine.canTransition(UpdateState.Checking, UpdateState.Downloading(candidate))).isTrue()
        assertThat(UpdateStateMachine.canTransition(UpdateState.Checking, UpdateState.Paused(candidate))).isTrue()
        assertThat(UpdateStateMachine.canTransition(UpdateState.Checking, UpdateState.ReadyToInstall(candidate))).isTrue()
    }

    @Test
    fun pauseResumeCancelTransitions() {
        val downloading = UpdateState.Downloading(candidate)
        val paused = UpdateState.Paused(candidate)
        assertThat(UpdateStateMachine.canTransition(downloading, paused)).isTrue()
        assertThat(UpdateStateMachine.canTransition(paused, downloading)).isTrue()
        assertThat(UpdateStateMachine.canTransition(downloading, UpdateState.Idle)).isTrue()
        assertThat(UpdateStateMachine.canTransition(paused, UpdateState.Idle)).isTrue()
        assertThat(UpdateStateMachine.canTransition(paused, UpdateState.ReadyToInstall(candidate))).isFalse()
        assertThat(UpdateStateMachine.canTransition(UpdateState.Idle, paused)).isFalse()
    }
}
