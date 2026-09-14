package io.github.cyclingtrainer.app.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One 1-Hz sample row. */
data class RideSample(
    val elapsedSeconds: Int,
    val powerWatts: Int?,
    val cadenceRpm: Double?,
    val heartRateBpm: Int?,
    val targetWatts: Int?,
)

/**
 * Ride recorder: subscribes to live sensor flows and records one sample per
 * [sample] call. The caller (AppViewModel) invokes [sample] once per engine
 * tick, so the recorded elapsed time always equals the session engine's
 * elapsed time — never a separate wall clock that could drift ahead of the
 * course (which used to push the live chart's curves past the right edge).
 *
 * The file is written under [outputDir] with a Zwift-like naming scheme:
 *   yyyy-MM-dd_HH-mm-ss_<workout>.csv
 */
class RideRecorder(
    private val outputDir: File,
    private val workoutName: String?,
    private val powerFlow: Flow<Int?>,
    private val cadenceFlow: Flow<Double?>,
    private val hrFlow: Flow<Int?>,
    private val targetFlow: Flow<Int?>,
    private val scope: CoroutineScope,
) {
    private data class Snap(
        val elapsed: Int, val power: Int?, val cadence: Double?,
        val hr: Int?, val target: Int?,
    )

    private val _samples = ArrayList<Snap>()

    /** Immutable snapshot of the samples collected so far (for charts). */
    @Synchronized
    fun snapshot(): List<RideSample> = synchronized(this) {
        _samples.map {
            RideSample(it.elapsed, it.power, it.cadence, it.hr, it.target)
        }
    }

    private var startedAtEpochMs = 0L
    private var started = false

    // latest seen sensor values
    private var lastPower: Int? = null
    private var lastCadence: Double? = null
    private var lastHr: Int? = null
    private var lastTarget: Int? = null

    fun start() {
        if (started) return
        started = true
        _samples.clear()
        startedAtEpochMs = System.currentTimeMillis()
        scope.launch { powerFlow.collect { lastPower = it } }
        scope.launch { cadenceFlow.collect { lastCadence = it } }
        scope.launch { hrFlow.collect { lastHr = it } }
        scope.launch { targetFlow.collect { lastTarget = it } }
    }

    /**
     * Records one sample for session second [elapsed]. Called once per engine
     * tick; the engine pauses between ticks during a paused session, so the
     * recorder naturally skips paused time (no separate clock to drift).
     */
    @Synchronized
    fun sample(elapsed: Int) {
        synchronized(this) {
            _samples += Snap(elapsed, lastPower, lastCadence, lastHr, lastTarget)
        }
    }

    /** Stops recording and writes the CSV. Returns the file. */
    fun stop(): File {
        started = false
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(startedAtEpochMs))
        val safeName = (workoutName ?: "自由骑行")
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val file = File(outputDir, "${stamp}_${safeName}.csv")
        outputDir.mkdirs()
        file.writeText(toCsv())
        return file
    }

    private fun toCsv(): String {
        val sb = StringBuilder()
        sb.append("elapsed_s,power_w,cadence_rpm,heart_rate_bpm,target_w\n")
        for (s in _samples) {
            sb.append(s.elapsed).append(',')
                .append(s.power ?: "").append(',')
                .append(s.cadence?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
                .append(s.hr ?: "").append(',')
                .append(s.target ?: "").append('\n')
        }
        return sb.toString()
    }
}
