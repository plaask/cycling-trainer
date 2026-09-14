package io.github.cyclingtrainer.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structure-level verification of the hand-rolled FIT writer: parses the
 * produced bytes with a small independent decoder (header, definition/data
 * records, CRC) and asserts the wire layout matches the FIT 2.0 spec.
 *
 * Full semantic validation happens on real importers (Golden Cheetah,
 * Garmin Connect); this catches layout/offset mistakes.
 */
class FitWriterTest {

    // ---- tiny independent FIT parser used only to verify the writer ----
    private class MiniFit(val bytes: ByteArray) {
        val headerSize = bytes[0].toInt()
        val protocol = bytes[1].toInt()
        val profile = u16(2)
        val dataSize = u32(4)
        val crcAt = headerSize + dataSize

        init {
            assertEquals(14, headerSize)
            assertEquals(0x20, protocol)
            assertEquals(0x2E, bytes[8].toInt()); assertEquals('F'.code, bytes[9].toInt())
            assertEquals('I'.code, bytes[10].toInt()); assertEquals('T'.code, bytes[11].toInt())
        }

        private fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or
            ((bytes[o + 1].toInt() and 0xFF) shl 8)
        private fun u32(o: Int): Long =
            (bytes[o].toLong() and 0xFF) or ((bytes[o + 1].toLong() and 0xFF) shl 8) or
                ((bytes[o + 2].toLong() and 0xFF) shl 16) or
                ((bytes[o + 3].toLong() and 0xFF) shl 24)

        fun crc16(len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                crc = crc xor (bytes[i].toInt() and 0xFF)
                for (j in 0 until 8) {
                    crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
                }
            }
            return crc
        }

        fun fileCrcValid(): Boolean = crc16(bytes.size - 2) == u16(bytes.size - 2)

        /**
         * Walks every message — definition *and* data — returning
         * (globalMsg, fieldNumbers) for each definition in file order.
         *
         * Data messages carry no length on the wire: their payload size comes
         * from the definition of the same local type, so the walker must record
         * it (see [payloadBytes]). Advancing the cursor on a data message is
         * mandatory — skipping that step spins forever on the first data
         * message, which is exactly how this helper used to hang the whole
         * `testDebugUnitTest` run.
         */
        fun definitions(): List<Pair<Int, List<Int>>> {
            // localType -> total payload bytes of that definition's data messages
            val payloadBytes = IntArray(16) { -1 }
            val out = ArrayList<Pair<Int, List<Int>>>()
            var i = headerSize
            while (i < dataSize + headerSize) {
                val h = bytes[i].toInt() and 0xFF
                val isDef = (h and 0x40) != 0
                val lt = h and 0x0F
                if (isDef) {
                    assertEquals(0, bytes[i + 2].toInt()) // little endian
                    val global = (bytes[i + 3].toInt() and 0xFF) or
                        ((bytes[i + 4].toInt() and 0xFF) shl 8)
                    val n = bytes[i + 5].toInt() and 0xFF
                    i += 6
                    val fields = ArrayList<Int>()
                    var size = 0
                    for (k in 0 until n) {
                        fields.add(bytes[i].toInt() and 0xFF)
                        size += bytes[i + 1].toInt() and 0xFF
                        i += 3
                    }
                    payloadBytes[lt] = size
                    out.add(global to fields)
                } else {
                    val size = payloadBytes[lt]
                    // No definition seen for this local type yet: the file is
                    // malformed. Stop instead of looping in place.
                    if (size < 0) break
                    i += 1 + size // data header byte + payload
                }
            }
            return out
        }
    }

    private fun sampleRows(n: Int): List<RideSample> = List(n) { i ->
        RideSample(
            elapsedSeconds = i,
            powerWatts = if (i % 2 == 0) 100 + i else null,
            cadenceRpm = 80.0 + i,
            heartRateBpm = 120 + (i % 40),
            targetWatts = 150,
        )
    }

    @Test
    fun `header is 14 bytes with FIT signature and valid CRCs`() {
        val bytes = FitWriter.encode(sampleRows(3), 1_000_000L, 42L, "tempo")
        val f = MiniFit(bytes)
        assertEquals(14, f.headerSize)
        // Bytes 8..11 carry ".FIT" (the leading '.' included).
        assertEquals(
            ".FIT".toByteArray().toList(),
            bytes.toList().subList(8, 12),
        )
        assertTrue(f.fileCrcValid())
        assertEquals(bytes.size - 14 - 2, f.dataSize.toInt())
    }

    @Test
    fun `message order fileId deviceInfo event records lap session activity`() {
        val bytes = FitWriter.encode(sampleRows(2), 1_000_000L, 7L, null)
        val defs = MiniFit(bytes).definitions()
        assertEquals(
            listOf(0, 23, 21, 20, 19, 18, 34),
            defs.map { it.first }
        )
    }

    @Test
    fun `record definition has heart rate cadence power with right field numbers`() {
        val bytes = FitWriter.encode(sampleRows(1), 5L, 1L, null)
        val record = MiniFit(bytes).definitions().first { it.first == 20 }.second
        // heart_rate=3, cadence=4, power=7 must be present & sorted ascending
        assertTrue(record.contains(3))
        assertTrue(record.contains(4))
        assertTrue(record.contains(7))
        assertEquals(record.sorted(), record)
    }

    @Test
    fun `exported csv round-trips through fit writer without throwing`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fitwriter-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val csv = File(dir, "2025-06-01_10-00-00_自由骑行.csv")
            csv.writeText(
                "elapsed_s,power_w,cadence_rpm,heart_rate_bpm,target_w\n" +
                    "0,150,85,130,150\n1,152,,135,\n2,148,86,132,\n"
            )
            val fit = FitExporter.export(csv)
            assertTrue(fit.exists())
            assertTrue(fit.length() > 0)
            assertTrue(MiniFit(fit.readBytes()).fileCrcValid())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `fit export rejects empty csv`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "fitwriter-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val csv = File(dir, "2025-01-01_00-00-00_x.csv")
            csv.writeText("")
            try {
                FitExporter.export(csv)
                assertFalse("expected failure", true)
            } catch (e: IllegalArgumentException) {
                // expected
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
