package dev.better.airplay.presentation.player

/** tvOS-style time labels: "1:05", "1:02:03", and "-3:20" for remaining time. */
object TimeText {
    fun elapsed(ms: Long): String = format(ms.coerceAtLeast(0) / 1000)

    fun remaining(positionMs: Long, durationMs: Long): String =
        "−" + format(((durationMs - positionMs).coerceAtLeast(0) + 999) / 1000)   // true minus, as Apple

    private fun format(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
