package io.github.cyclingtrainer.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the FE-C over BLE frame format, cross-checked against Auuki's real
 * captures of a ThinkRider X7 Pro (page 25 telemetry) and the ANT+ FE-C
 * control-page layout used by pycycling/Tacx.
 */
class FecParsersTest {

    private fun frame(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    @Test
    fun `page 25 power 90 W with status nibble in msb`() {
        // [164,9,78,5,25,12,15,108,72,90,96,51,209] -> power 90 W (byte9=90, byte10=0x60: msb 0)
        val f = frame(0xA4, 0x09, 0x4E, 0x05, 0x19, 12, 15, 108, 72, 90, 0x60, 51, 209)
        assertEquals(90, FecParsers.page25Power(f))
    }

    @Test
    fun `page 25 power 300 W`() {
        // byte9=0x2C, byte10=0x61 -> power = 0x012C = 300
        val f = frame(0xA4, 0x09, 0x4E, 0x05, 0x19, 0, 0, 0, 0, 0x2C, 0x61, 0, 0)
        assertEquals(300, FecParsers.page25Power(f))
    }

    @Test
    fun `page 25 power 255 max and invalid 4095 null`() {
        val max = frame(0xA4, 0x09, 0x4E, 0x05, 0x19, 0, 0, 0, 0, 0xFF, 0x60, 0, 0)
        assertEquals(255, FecParsers.page25Power(max))
        val invalid = frame(0xA4, 0x09, 0x4E, 0x05, 0x19, 0, 0, 0, 0, 0xFF, 0x6F, 0, 0)
        assertNull(FecParsers.page25Power(invalid))
    }

    @Test
    fun `page 25 cadence 15 rpm`() {
        val f = frame(0xA4, 0x09, 0x4E, 0x05, 0x19, 16, 15, 103, 73, 18, 0x60, 51, 143)
        assertEquals(15, FecParsers.page25Cadence(f))
        val invalid = frame(0xA4, 0x09, 0x4E, 0x05, 0x19, 16, 0xFF, 0, 0, 0, 0x60, 0, 0)
        assertNull(FecParsers.page25Cadence(invalid))
    }

    @Test
    fun `not page 25 yields null`() {
        val f = frame(0xA4, 0x09, 0x4E, 0x05, 0x10, 0, 0, 0, 0, 90, 0x60, 0, 0)
        assertNull(FecParsers.page25Power(f))
    }

    @Test
    fun `page 16 speed conversion`() {
        // raw 5000 = 5.000 m/s = 18 km/h
        val f = frame(0xA4, 0x09, 0x4E, 0x05, 0x10, 0, 0, 0, 0x88, 0x13, 0, 0, 0)
        val speed = FecParsers.page16Speed(f)
        assertNotNull(speed)
        assertEquals(18.0, speed!!, 0.01)
    }

    @Test
    fun `target power frame layout and checksum`() {
        val f = FecParsers.targetPowerFrame(100) // 100 W -> 400/0.25? no: 100/0.25=400
        assertNotNull(f)
        assertEquals(13, f!!.size)
        assertEquals(0xA4, f[0].toInt() and 0xFF)
        assertEquals(0x09, f[1].toInt() and 0xFF)
        assertEquals(0x4E, f[2].toInt() and 0xFF)
        assertEquals(0x05, f[3].toInt() and 0xFF)
        assertEquals(0x31, f[4].toInt() and 0xFF)
        // scaled = 100 / 0.25 = 400 = 0x0190
        assertEquals(0x90, f[10].toInt() and 0xFF)
        assertEquals(0x01, f[11].toInt() and 0xFF)
        // checksum = XOR bytes 0..11
        var crc = 0
        for (i in 0 until 12) crc = crc xor (f[i].toInt() and 0xFF)
        assertEquals(crc, f[12].toInt() and 0xFF)
    }
}
