package io.github.cyclingtrainer.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BtParsersTest {

    @Test
    fun `parse indoor bike power and cadence`() {
        // flags = power(0x02) | cadence(0x04) = 0x06, LE: [06 00]
        // power = 250 -> [FA 00], cadence = 90 -> [5A 00]
        val data = b(0x06, 0x00, 0xFA, 0x00, 0x5A, 0x00)
        val r = BtParsers.parseIndoorBikeData(data)
        assertNotNull(r)
        assertEquals(250, r!!.powerWatts)
        assertEquals(90, r.cadenceRpm)
    }

    @Test
    fun `parse indoor bike power only`() {
        // flags=0x02, power 1000 = 0x03E8
        val data = b(0x02, 0x00, 0xE8, 0x03)
        val r = BtParsers.parseIndoorBikeData(data)
        assertNotNull(r)
        assertEquals(1000, r!!.powerWatts)
        assertNull(r.cadenceRpm)
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
