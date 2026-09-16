package io.github.cyclingtrainer.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ride notification is built by a Service, which the JVM test suite cannot
 * reach — but its text is the part that can be wrong, so it lives in a pure
 * function and is asserted here.
 */
class RideNotificationTest {

    private fun course(target: Int?, elapsed: Int, total: Int) = RideNotificationState(
        phase = SessionEngine.Phase.RUNNING,
        targetWatts = target,
        elapsedSec = elapsed,
        totalSec = total,
    )

    @Test
    fun `course ride shows the current target`() {
        val state = course(target = 230, elapsed = 754, total = 2700)
        assertEquals("目标 230 W", RideNotificationText.title(state))
        assertEquals("12:34 / 45:00", RideNotificationText.text(state))
    }

    @Test
    fun `free ride has no target to show`() {
        val state = course(target = null, elapsed = 65, total = 0)
        assertEquals("自由骑行", RideNotificationText.title(state))
        assertEquals("1:05 已骑", RideNotificationText.text(state))
    }

    @Test
    fun `paused ride says so, and keeps its clock`() {
        val state = course(target = 180, elapsed = 60, total = 3600)
            .copy(phase = SessionEngine.Phase.PAUSED)
        assertEquals("训练已暂停", RideNotificationText.title(state))
        // Both sides switch to h:mm:ss together — "1:00 / 1:00:00" would read
        // like two unrelated clocks.
        assertEquals("0:01:00 / 1:00:00", RideNotificationText.text(state))
    }

    @Test
    fun `clock rolls over into hours`() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:59", formatClock(59))
        assertEquals("1:00", formatClock(60))
        assertEquals("59:59", formatClock(3599))
        assertEquals("1:00:00", formatClock(3600))
        assertEquals("2:05:06", formatClock(7506))
    }

    @Test
    fun `negative elapsed does not print a broken clock`() {
        assertEquals("0:00", formatClock(-5))
    }

    @Test
    fun `a clock pair keeps one shape on both sides`() {
        assertEquals("12:34 / 45:00", formatClockPair(754, 2700))
        assertEquals("0:05:00 / 1:30:00", formatClockPair(300, 5400))
        assertEquals("1:05 已骑", formatClockPair(65, 0))
    }
}
