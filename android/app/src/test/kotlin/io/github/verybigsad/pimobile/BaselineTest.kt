package io.github.verybigsad.pimobile

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineTest {
    @Test
    fun productionIdentityIsStable() {
        assertEquals("io.github.verybigsad.pimobile", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
    }
}
