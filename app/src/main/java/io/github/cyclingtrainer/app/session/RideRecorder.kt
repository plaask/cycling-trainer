package io.github.cyclingtrainer.app.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Durability: the CSV is rewritten every [FLUSH_EVERY_SECONDS] of ride time,
 * not only on [stop]. Losing the process (crash, low-memory kill, or the user
 * swiping the app away) used to discard the entire ride; now the worst case is
 * losing the last few seconds. File writes run on [writeDispatcher].
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
    /** Injectable so tests can keep writes on their own scheduler. */
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Snap(
        val elapsed: Int, val power: Int?, val cadence: Double?,
        val hr: Int?, val target: Int?,
    )

    private val _samples = java.util.ArrayList<Snap>()

    /** Immutable snapshot of the samples collected so far (for charts). */
    fun snapshot(): List<RideSample> = synchronized(this) {
        _samples.map {
            RideSample(it.elapsed, it.power, it.cadence, it.hr, it.target)
        }
    }

    private var startedAtEpochMs = 0L
    private var started = false

    /** Set once [stop] has written the final file, making stop idempotent. */
    private var finished = false
    private var file: File? = null

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
     *
     * Every [FLUSH_EVERY_SECONDS] samples the CSV is rewritten in the
     * background so an interrupted ride still leaves a usable file behind.
     */
    fun sample(elapsed: Int) {
        val pending: Pair<File, String>? = synchronized(this) {
            if (finished) return
            _samples += Snap(elapsed, lastPower, lastCadence, lastHr, lastTarget)
            if (_samples.size % FLUSH_EVERY_SECONDS != 0) return
            // Pin the file name on the first flush so the final write lands in
            // the same file (fileFor() also derives it, but caching is safer).
            val dest = file ?: fileFor().also { file = it }
            dest to rideSamplesToCsv(snapshot())
        }
        if (pending != null) {
            val (dest, text) = pending
            scope.launch(writeDispatcher) { runCatching { dest.writeText(text) } }
        }
    }

    /**
     * Stops recording and writes the final CSV. Idempotent: repeated calls
     * (e.g. the session engine finishing while the user also taps Stop) return
     * the same file instead of writing twice or losing it.
     */
    suspend fun stop(): File {
        val pending: Pair<File, String> = synchronized(this) {
            finished = true
            started = false
            val f = file ?: fileFor().also { file = it }
            f to rideSamplesToCsv(snapshot())
        }
        val (dest, text) = pending
        withContext(writeDispatcher) {
            outputDir.mkdirs()
            dest.writeText(text)
        }
        return dest
    }

    /** Caller must hold the monitor (reads [startedAtEpochMs]). */
    private fun fileFor(): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(startedAtEpochMs))
        val safeName = (workoutName ?: "自由骑行")
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        return File(outputDir, "${stamp}_${safeName}.csv")
    }

    companion object {
        /** Seconds of ride time between incremental CSV rewrites. */
        const val FLUSH_EVERY_SECONDS = 30

        /** Header shared with [FitExporter], which recognises it and skips it. */
        const val CSV_HEADER = "elapsed_s,power_w,cadence_rpm,heart_rate_bpm,target_w"
    }
}

/**
 * Serialises samples to the ride CSV. Pure and free of I/O so the exact file
 * contents can be asserted from a plain unit test.
 */
fun rideSamplesToCsv(samples: List<RideSample>): String {
    val sb = StringBuilder()
    sb.append(RideRecorder.CSV_HEADER).append('\n')
    for (s in samples) {
        sb.append(s.elapsedSeconds).append(',')
            .append(s.powerWatts ?: "").append(',')
            .append(s.cadenceRpm?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
            .append(s.heartRateBpm ?: "").append(',')
            .append(s.targetWatts ?: "").append('\n')
    }
    return sb.toString()
}
