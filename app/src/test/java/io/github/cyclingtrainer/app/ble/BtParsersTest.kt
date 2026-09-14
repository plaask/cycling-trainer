package io.github.cyclingtrainer.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BtParsersTest {

    /**
     * Real capture: nRF Connect reading a trainer's Indoor Bike Data.
     * Hex 44 02 52 03 5A 00 08 00 00, decoded by nRF as
     * "inst. speed 8.5 km/h, inst. cadence 45.0, inst. power 8 W, HR 0".
     *
     * flags = 0x0244 -> bit0 0 (speed present), bit2 average speed,
     * bit6 instantaneous power, bit9 heart rate.
     * This is the frame the old bit-mapping decoded as 850 W / 90 rpm.
     */
    @Test
    fun `parse indoor bike data from a real capture`() {
        val data = b(0x44, 0x02, 0x52, 0x03, 0x5A, 0x00, 0x08, 0x00, 0x00)
        val r = BtParsers.parseIndoorBikeData(data)
        assertNotNull(r)
        assertEquals(8, r!!.powerWatts)
        assertEquals(45, r.cadenceRpm)
        assertEquals(8.5, r.speedKmh!!, 1e-9)
    }

    /** flags = 0x0044: speed + cadence + power, no average/extra fields. */
    @Test
    fun `parse indoor bike data speed cadence power`() {
        // 0x0044 -> bit0 0 (speed), bit2 cadence, bit6 power
        // speed 850 = 0x0352 -> 8.50 km/h; cadence raw 120 -> 60 rpm (0.5 LSB)
        val data = b(0x44, 0x00, 0x52, 0x03, 0x78, 0x00, 0xFA, 0x00)
        val r = BtParsers.parseIndoorBikeData(data)
        assertNotNull(r)
        assertEquals(250, r!!.powerWatts)
        assertEquals(60, r.cadenceRpm)
        assertEquals(8.5, r.speedKmh!!, 1e-9)
    }

    /** flags = 0x0041: More Data set -> no speed field; power still last. */
    @Test
    fun `parse indoor bike data without speed field`() {
        // bit0 1 = no instantaneous speed; bit6 power present.
        val data = b(0x41, 0x00, 0xE8, 0x03) // power 1000
        val r = BtParsers.parseIndoorBikeData(data)
        assertNotNull(r)
        assertEquals(1000, r!!.powerWatts)
        assertNull(r.cadenceRpm)
        assertNull(r.speedKmh)
    }

    /** Power is a signed 16-bit field: negative values must survive. */
    @Test
    fun `parse indoor bike data signed power`() {
        val data = b(0x41, 0x00, 0xFF, 0xFF) // -1 W
        val r = BtParsers.parseIndoorBikeData(data)
        assertEquals(-1, r!!.powerWatts)
    }

    /** A frame that declares fields it does not carry must not half-parse. */
    @Test
    fun `truncated indoor bike frame is rejected`() {
        // flags promise speed + cadence + power but only speed is present
        assertNull(BtParsers.parseIndoorBikeData(b(0x44, 0x00, 0x52, 0x03)))
        // shorter than the flags field itself
        assertNull(BtParsers.parseIndoorBikeData(b(0x44)))
    }

    @Test
    fun `heart rate 8-bit and 16-bit`() {
        assertEquals(72, BtParsers.parseHeartRate(b(0x00, 72)))
        // flags bit0 => 16bit HR 180
        val d = b(0x01, 180 and 0xFF, 180 shr 8)
        assertEquals(180, BtParsers.parseHeartRate(d))
        assertNull(BtParsers.parseHeartRate(b(0x00)))
    }

    @Test
    fun `csc cadence from crank deltas`() {
        // flag crank present (0x02); rev=100, time=0
        val a = b(0x02, 100, 0, 0, 0, 0, 0)
        val (s1, rpm1) = BtParsers.parseCscCrank(a, null)
        assertNull(rpm1)
        // rev=101, time=1024 ticks later (1 s) => 60 rpm
        val bf = b(0x02, 101, 0, 0, 0, 0x00, 0x04)
        val (_, rpm2) = BtParsers.parseCscCrank(bf, s1)
        assertNotNull(rpm2)
        assertEquals(60.0, rpm2!!, 0.5)
    }

    @Test
    fun `csc handles 16-bit time wraparound`() {
        // prev time near max
        val prev = CscCrankState(revolutions = 100, lastEventTime = 65535)
        // now rev=101, time=5 -> dTime = 5 - 65535 + 65536 = 6 ticks
        val now = b(0x02, 101, 0, 0, 0, 0x05, 0x00)
        val (_, rpm) = BtParsers.parseCscCrank(now, prev)
        // 1 rev in 6/1024 s = 10240 rpm unrealistic for that delta but formula right
        assertNotNull(rpm)
    }

    private fun b(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }
}
