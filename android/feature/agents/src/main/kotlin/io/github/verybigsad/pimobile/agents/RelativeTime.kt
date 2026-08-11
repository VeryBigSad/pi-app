package io.github.verybigsad.pimobile.agents

/**
 * Relative time labels for agent started/ended timestamps. Pure and deterministic so unit and
 * Compose tests can pin `nowEpochMillis`.
 */
object RelativeTime {
    fun relative(fromEpochMillis: Long, nowEpochMillis: Long): String {
        val deltaSeconds = ((nowEpochMillis - fromEpochMillis) / 1_000).coerceAtLeast(0)
        return when {
            deltaSeconds < 45 -> "just now"
            deltaSeconds < 90 -> "1 min ago"
            deltaSeconds < 3_600 -> "${deltaSeconds / 60} min ago"
            deltaSeconds < 5_400 -> "1 hr ago"
            deltaSeconds < 86_400 -> "${deltaSeconds / 3_600} hr ago"
            deltaSeconds < 129_600 -> "1 day ago"
            else -> "${deltaSeconds / 86_400} days ago"
        }
    }

    fun startedLabel(startedAtEpochMillis: Long, nowEpochMillis: Long): String =
        "started ${relative(startedAtEpochMillis, nowEpochMillis)}"

    fun endedLabel(endedAtEpochMillis: Long, nowEpochMillis: Long): String =
        "ended ${relative(endedAtEpochMillis, nowEpochMillis)}"
}
