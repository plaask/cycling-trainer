package io.github.cyclingtrainer.app.session

import io.github.cyclingtrainer.app.workout.Workout
import io.github.cyclingtrainer.app.workout.WorkoutSegment
import io.github.cyclingtrainer.app.workout.SegmentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives a .zwo workout against an ERG trainer.
 *
 * Ticks once per second. Each tick:
 *  - advances elapsed time,
 *  - when a [workout] is set, computes the target power (watts) from the
 *    workout power curve at the elapsed time (FTP-fraction x athlete FTP) and
 *    pushes it through [onTargetPower] (the caller sends it to the trainer
 *    over BLE),
 *  - when [workout] is null (free ride) no ERG target is pushed — the caller
 *    only records 1 Hz samples.
 *
 * The ticker runs on [scope]; it stops itself at workout end, or on pause/stop.
 */
class SessionEngine(
    private val workout: Workout?,
    private val ftpWatts: Int,
    private val onTargetPower: suspend (watts: Int) -> Unit,
    private val scope: CoroutineScope,
    private val onFinished: (() -> Unit)? = null,
    private val onTick: ((elapsedSeconds: Int) -> Unit)? = null,
) {
    enum class Phase { IDLE, RUNNING, PAUSED, FINISHED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Elapsed seconds in the current run (0 at start). */
    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    /** Total workout duration in seconds; 0 for free ride. */
    val totalSeconds: Int get() = workout?.totalDurationSeconds ?: 0

    /** Currently active segment for UI highlighting; null for free ride. */
    private val _currentSegment = MutableStateFlow<WorkoutSegment?>(null)
    val currentSegment: StateFlow<WorkoutSegment?> = _currentSegment.asStateFlow()

    private var tickerJob: Job? = null

    /** When false, the engine ticks/times but never pushes ERG targets
     *  (user rides free / manual resistance). Toggled from the train UI. */
    @Volatile
    var ergEnabled: Boolean = true

    val progress: Float
        get() = if (totalSeconds == 0) 0f
        else (_elapsedSeconds.value.toFloat() / totalSeconds).coerceIn(0f, 1f)

    /** Starts from zero (idempotent — restarts if already running). */
    fun start() {
        if (_phase.value == Phase.RUNNING) return
        tickerJob?.cancel()
        _elapsedSeconds.value = 0
        _phase.value = Phase.RUNNING
        tickerJob = scope.launch {
            // immediate first target so ERG engages without waiting 1s
            pushTarget(0)
            while (isActive) {
                delay(1000)
                // pause() only flips the phase; the ticker keeps looping so
                // resume() can pick up where it left off. Elapsed time does
                // not advance while paused.
                val wasPaused = _phase.value != Phase.RUNNING
                if (_phase.value == Phase.IDLE || _phase.value == Phase.FINISHED) break
                if (wasPaused) continue
                val next = _elapsedSeconds.value + 1
                val total = totalSeconds
                if (total > 0 && next >= total) {
                    _elapsedSeconds.value = total
                    // Record the final second before finishing: onTick drives
                    // the recorder's sample(), so skipping it here truncated
                    // every recorded ride by one sample (and its CSV row).
                    onTick?.invoke(total)
                    pushTarget(total - 1)
                    _phase.value = Phase.FINISHED
                    onFinished?.invoke()
                    break
                }
                _elapsedSeconds.value = next
                onTick?.invoke(next)
                pushTarget(next)
            }
        }
    }

    fun pause() {
        if (_phase.value == Phase.RUNNING) _phase.value = Phase.PAUSED
    }

    fun resume() {
        if (_phase.value == Phase.PAUSED) _phase.value = Phase.RUNNING
    }

    fun stop() {
        tickerJob?.cancel()
        _phase.value = Phase.IDLE
        _elapsedSeconds.value = 0
        _currentSegment.value = null
    }

    /** Convenience — what the trainer should be set to right now (watts). */
    fun targetPowerAt(elapsed: Int): Int {
        val w = workout ?: return 0
        val frac = w.powerFractionAt(elapsed.toDouble())
        return (frac * ftpWatts).toInt()
    }

    private suspend fun pushTarget(elapsed: Int) {
        if (workout == null) return  // free ride: no ERG target to push
        updateSegment(elapsed)
        // The target is always reported (caller decides whether to drive the
        // trainer), so the UI can show what the course asks even with ERG off.
        val target = targetPowerAt(elapsed)
        onTargetPower(target)
    }

    private fun updateSegment(elapsed: Int) {
        val segs = workout?.segments ?: return
        var t = 0
        for (seg in segs) {
            if (elapsed < t + seg.durationSeconds || seg === segs.last()) {
                _currentSegment.value = seg
                return
            }
            t += seg.durationSeconds
        }
    }
}
