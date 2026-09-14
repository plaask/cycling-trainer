package io.github.cyclingtrainer.app.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ride-durability guarantees: the CSV must be complete at the end of a ride,
 * written exactly once even when Stop and the course end race, and rewritten
 * periodically so an interrupted ride still leaves a usable file.
 */
class RideRecorderTest {

    private fun recorderIn(dir: File): RideRecorder = RideRecorder(
        outputDir = dir,
        workoutName = "自由骑行",
        powerFlow = emptyFlow(),
        cadenceFlow = emptyFlow(),
        hrFlow = emptyFlow(),
        targetFlow = emptyFlow(),
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        writeDispatcher = Dispatchers.Unconfined,
    )

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "rriderec-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `stop is idempotent and keeps a single file`() = runBlocking {
        val dir = tempDir()
        try {
            val rec = recorderIn(dir)
            rec.start()
            (0..5).forEach { rec.sample(it) }

            val first = rec.stop()
            val bytesAfterFirst = first.readBytes()
            val second = rec.stop()

            assertEquals(first, second)
            assertArrayEqualsBytes(bytesAfterFirst, second.readBytes())
            assertEquals(1, dir.listFiles { f -> f.name.endsWith(".csv") }?.size)
            // header + one row per sample
            assertEquals(7, first.readLines().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `samples after stop are ignored`() = runBlocking {
        val dir = tempDir()
        try {
            val rec = recorderIn(dir)
            rec.start()
            (0..2).forEach { rec.sample(it) }
            val f = rec.stop()
            rec.sample(99) // must not grow the finished file

            assertEquals(4, f.readLines().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertArrayEqualsBytes(a: ByteArray, b: ByteArray) {
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun `csv serialisation writes header and blank cells for missing sensors`() {
        val rows = listOf(
            RideSample(0, 150, 85.0, 130, 150),
            RideSample(1, null, null, null, null),
            RideSample(2, 148, 86.4, 132, 150),
        )
        val lines = rideSamplesToCsv(rows).trimEnd('\n').lines()

        assertEquals(RideRecorder.CSV_HEADER, lines[0])
        assertEquals("0,150,85.0,130,150", lines[1])
        assertEquals("1,,,,", lines[2])
        assertEquals("2,148,86.4,132,150", lines[3])
    }

    @Test
    fun `csv is parseable by the FIT exporter`() {
        val rows = listOf(
            RideSample(0, 150, 85.0, 130, 150),
            RideSample(1, 152, 86.0, 131, 150),
        )
        val parsed = FitExporter.parseCsv(rideSamplesToCsv(rows))

        assertEquals(2, parsed.size)
        assertEquals(150, parsed[0].powerWatts)
        assertEquals(85.0, parsed[0].cadenceRpm!!, 1e-9)
        assertEquals(131, parsed[1].heartRateBpm)
        assertTrue(parsed[0].targetWatts == 150)
    }
}
