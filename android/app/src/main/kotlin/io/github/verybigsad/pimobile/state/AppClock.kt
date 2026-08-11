package io.github.verybigsad.pimobile.state

interface AppClock {
    fun nowEpochMillis(): Long
    fun nowMonotonicMillis(): Long
}

object SystemAppClock : AppClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
    override fun nowMonotonicMillis(): Long = android.os.SystemClock.elapsedRealtime()
}
