package io.github.cyclingtrainer.app.session

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Converts a recorded 1 Hz ride CSV (see [RideRecorder]) into a Garmin .FIT
 * activity file. Pure JVM — usable from unit tests without an Android device.
 *
 * The CSV header is: elapsed_s,power_w,cadence_rpm,heart_rate_bpm,target_w
 * The .fit keeps timestamp/power/cadence/heart-rate records plus lap/session
 * summaries; the ERG target_w column has no native FIT home and is dropped
 * (it stays in the CSV).
 *
 * Parsing is tolerant: blank or non-numeric cells (exports use "") become
 * null. The FIT timestamps are anchored at the ride's wall-clock start parsed
 * from the CSV file name (`yyyy-MM-dd_HH-mm-ss_<workout>.csv`, local time) —
 * matching what [RideRecorder] writes — so the exported file carries real
 * dates on importers.
 */
object FitExporter {

    /** First column of [RideRecorder.CSV_HEADER]; identifies a ride CSV. */
    private const val HEADER_PREFIX = "elapsed_s"

    private val NAME_STAMP = Regex("""^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})_""")

    /** Seconds between the Unix epoch and the FIT epoch (1989-12-31 UTC). */
    private const val FIT_EPOCH_OFFSET_SEC = 631065600L

    /** Parses the CSV file back into the in-memory sample list. */
    fun parseCsv(file: File): List<RideSample> = parseCsv(file.readText())

    fun parseCsv(text: String): List<RideSample> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        // Skip the header if present.
        val header = lines.first()
        val body = if (header.startsWith(HEADER_PREFIX)) lines.drop(1) else lines
        return body.mapNotNull { line ->
            val parts = line.split(",")
            val elapsed = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            RideSample(
                elapsedSeconds = elapsed,
                powerWatts = parts.getOrNull(1)?.trim()?.toIntOrNull(),
                cadenceRpm = parts.getOrNull(2)?.trim()?.toDoubleOrNull(),
                heartRateBpm = parts.getOrNull(3)?.trim()?.toIntOrNull(),
                targetWatts = parts.getOrNull(4)?.trim()?.toIntOrNull(),
            )
        }
    }

    /**
     * Rides are local-time stamped `yyyy-MM-dd_HH-mm-ss_<name>`; that stamp
     * gives the FIT start (seconds since the FIT epoch 1989-12-31 UTC).
     * Files not matching fall back to "recent now minus ride length".
     */
    fun startEpochFromFileName(fileName: String, samples: List<RideSample>): Long {
        val m = NAME_STAMP.find(fileName) ?: run {
            return (System.currentTimeMillis() / 1000) - samples.size
        }
        val stamp = "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]} " +
            "${m.groupValues[4]}:${m.groupValues[5]}:${m.groupValues[6]}"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        val localMillis = fmt.parse(stamp)?.time ?: return (System.currentTimeMillis() / 1000)
        return localMillis / 1000 - FIT_EPOCH_OFFSET_SEC
    }

    /**
     * Converts a recorded CSV ride into a sibling .fit file in the same dir.
     * @return the written .fit file
     */
    fun export(csvFile: File, serial: Long = System.currentTimeMillis()): File {
        val fitFile = File(csvFile.parentFile, "${csvFile.nameWithoutExtension}.fit")
        fitFile.writeBytes(toFitBytes(csvFile, serial))
        return fitFile
    }

    /**
     * Builds the .FIT bytes for a recorded CSV ride without writing anything —
     * callers (e.g. "export to Downloads") write them wherever they want.
     */
    fun toFitBytes(csvFile: File, serial: Long = System.currentTimeMillis()): ByteArray {
        val samples = parseCsv(csvFile)
        require(samples.isNotEmpty()) { "CSV 没有可导出的数据行" }
        val start = startEpochFromFileName(csvFile.name, samples)
        // Stamp format: yyyy-MM-dd_HH-mm-ss_<workout> — workout is the tail
        // after the third '_' (windows-safe basename of the ride).
        val workoutName = csvFile.nameWithoutExtension
            .split('_').drop(3).joinToString("_").takeIf { it.isNotBlank() }
        return FitWriter.encode(
            samples = samples,
            startEpochSec = start,
            serial = serial,
            workoutName = workoutName,
        )
    }
}
