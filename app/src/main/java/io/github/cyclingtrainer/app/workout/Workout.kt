package io.github.cyclingtrainer.app.workout

/**
 * Parsed representation of a .zwo workout file (Zwift Workout Format).
 *
 * All target powers are stored as a fraction of the athlete's FTP (1.0 == 100 % FTP),
 * exactly as authored in the file. Durations are in seconds.
 *
 * A [WorkoutSegment] describes one interval of the ride with constant or linearly
 * ramping target power:
 *
 *  - If [powerStart] == [powerEnd] the segment is steady-state.
 *  - Otherwise the target power ramps linearly from [powerStart] to [powerEnd]
 *    across the segment duration.
 *
 * The workout starts at 0.0 seconds of elapsed time, so the target power at
 * any absolute time T can be evaluated by walking [Workout.segments].
 */
data class Workout(
    val name: String,
    val description: String,
    val author: String,
    val sportType: String,
    /** Ordered list of timed segments covering the whole workout, no gaps. */
    val segments: List<WorkoutSegment>,
) {
    /** Total duration in seconds. */
    val totalDurationSeconds: Int get() = segments.sumOf { it.durationSeconds }

    /**
     * Evaluates the target power (as FTP fraction) at elapsed time [seconds].
     * Times beyond the end clamp to the final segment's end power, times before
     * the start clamp to the first segment's start power.
     */
    fun powerFractionAt(seconds: Double): Double {
        if (segments.isEmpty()) return 0.0
        if (seconds <= 0.0) return segments.first().powerStart
        if (seconds >= totalDurationSeconds) return segments.last().powerEnd
        var t = 0.0
        for (seg in segments) {
            val segStart = t
            val segEnd = t + seg.durationSeconds
            if (seconds <= segEnd) {
                val f = if (seg.durationSeconds <= 0) 0.0
                else (seconds - segStart) / seg.durationSeconds
                return seg.powerStart + (seg.powerEnd - seg.powerStart) * f
            }
            t = segEnd
        }
        return segments.last().powerEnd
    }
}

data class WorkoutSegment(
    val name: String,
    val type: SegmentType,
    val durationSeconds: Int,
    /** Target power as fraction of FTP at segment start. */
    val powerStart: Double,
    /** Target power as fraction of FTP at segment end. */
    val powerEnd: Double,
    /** Optional target cadence in rpm. */
    val cadence: Int? = null,
)

enum class SegmentType {
    WARMUP,
    STEADY_STATE,
    INTERVAL, // on-phase of an IntervalsT repeat
    RECOVERY, // off-phase of an IntervalsT repeat
    COOLDOWN,
    FREE_RIDE, // <FreeRide>: occupies time, no ERG target (power 0)
}

/**
 * A single flat instruction produced by expanding a parsed workout:
 * every repeat of <IntervalsT> is unrolled into alternating on/off
 * steady-state instructions with absolute start times.
 */
data class WorkoutInstruction(
    val name: String,
    val type: SegmentType,
    val startSeconds: Double,
    val durationSeconds: Int,
    val powerStart: Double,
    val powerEnd: Double,
    val cadence: Int? = null,
)
