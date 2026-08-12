package io.github.verybigsad.pimobile.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.state.PiAppState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledStackReplyWatchInstrumentedTest {
    @Test
    fun classifiesLostAuthenticationWithoutStateContents() {
        val watch = InstalledStackReplyWatch(SessionId("00000000-0000-4000-8000-000000000001"), null)

        assertEquals("E2E_FINAL_REPLY_AUTH_LOST", watch.failureCode(PiAppState()))
    }
}
