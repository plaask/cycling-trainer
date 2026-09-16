package io.github.cyclingtrainer.app.session

import java.util.Locale

/**
 * Clock formatting for everything that shows ride time: the train screen, the
 * PiP HUD and the ride notification.
 *
 * `m:ss`, switching to `h:mm:ss` at an hour — and [forceHours] exists so a pair
 * of values stays in the same shape. Without it a one-hour course printed
 * "1:00 / 1:00:00", which reads like two different clocks.
 */
internal fun formatClock(sec: Int, forceHours: Boolean = false): String {
    val safe = sec.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0 || forceHours) "%d:%02d:%02d".format(Locale.US, h, m, s)
    else "%d:%02d".format(Locale.US, m, s)
}

/**
 * "elapsed / total" for a course, "elapsed 已骑" for a free ride
 * ([totalSec] == 0) — both sides always in the same shape.
 */
internal fun formatClockPair(elapsedSec: Int, totalSec: Int): String {
    if (totalSec <= 0) return "${formatClock(elapsedSec)} 已骑"
    val hours = totalSec >= 3600
    return "${formatClock(elapsedSec, hours)} / ${formatClock(totalSec, hours)}"
}
