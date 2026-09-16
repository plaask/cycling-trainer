package io.github.cyclingtrainer.app.session

/**
 * What the ongoing ride notification says.
 *
 * Kept apart from the service and free of `android.*` so the wording can be
 * asserted from a plain JVM test — a notification built by a Service is not
 * something the unit-test suite can reach, but its text is the part that can
 * actually be wrong.
 */
data class RideNotificationState(
    val phase: SessionEngine.Phase,
    /** Current course target in watts; null for a free ride. */
    val targetWatts: Int?,
    val elapsedSec: Int,
    /** Course length in seconds; 0 for a free ride. */
    val totalSec: Int,
)

object RideNotificationText {
    fun title(state: RideNotificationState): String = when {
        state.phase == SessionEngine.Phase.PAUSED -> "训练已暂停"
        state.targetWatts != null -> "目标 ${state.targetWatts} W"
        else -> "自由骑行"
    }

    fun text(state: RideNotificationState): String =
        formatClockPair(state.elapsedSec, state.totalSec)
}
