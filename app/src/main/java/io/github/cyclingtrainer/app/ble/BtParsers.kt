package io.github.cyclingtrainer.app.ble

import kotlin.math.roundToInt

/**
 * Decoders for standard BLE fitness-service notifications.
 * All parsing is defensive: a malformed frame yields null instead of throwing.
 */
object BtParsers {

    /**
     * FTMS Indoor Bike Data (0x2AD2) — Flags (u16 LE) followed by the optional
     * fields in specification order.
     *
     * Flag bits (FTMS 1.0 §4.9.1.1, little-endian bitmask):
     *   0 More Data            0 = Instantaneous Speed IS present (inverted!)
     *   1 Average Speed        5 Resistance Level (s16)
     *   2 Instantaneous Cadence (u16)   6 Instantaneous Power (s16)
     *   3 Average Cadence      7 Average Power (s16)
     *   4 Total Distance (u24) 8 Expended Energy, 9 Heart Rate, 10 MET,
     *                          11 Elapsed Time, 12 Remaining Time
     *
     * Field order on the wire follows the specification table, NOT bit order:
     * speed -> average speed -> cadence -> average cadence -> distance ->
     * resistance -> power -> ... (this parser reads up to power).
     *
     * The previous implementation read power first, cadence second, and treated
     * bit 0 as "skip a byte", which misread every real frame — e.g. the capture
     * `44 02 52 03 5A 00 08 00 00` (flags 0x0244 = 8.5 km/h, 45 rpm, 8 W)
     * decoded as 850 W / 90 rpm.
     *
     * Cadence resolution: the profile gives Instantaneous Cadence a 0.5 rpm
     * LSB, so the raw value is halved. Note that some open-source trainers
     * (KBikeBLE, ESP32-FTMS-Bike) transmit whole rpm instead, which would read
     * back doubled — but the one real capture available only reconciles when
     * halved, so the specification wins here.
     *
     * @return [PowerCadenceSpeed] or null if the frame is malformed.
     */
    fun parseIndoorBikeData(data: ByteArray): PowerCadenceSpeed? {
        if (data.size < 2) return null
        val flags = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        var pos = 2

        // bit 0 == 0 -> instantaneous speed present (this is the inverted one)
        val speed100 = if (flags and 0x0001 == 0) readU16(data, pos)?.also { pos += 2 } else null
        if (flags and 0x0002 != 0) pos += 2 // average speed
        // Cadence is a u16 with a 0.5 rpm LSB per the profile; the real capture
        // below only reconciles with nRF Connect's "45.0 rpm" when halved.
        val cadence = if (flags and 0x0004 != 0) {
            readU16(data, pos)?.also { pos += 2 }?.let { it / 2 }
        } else null
        if (flags and 0x0008 != 0) pos += 2 // average cadence
        if (flags and 0x0010 != 0) pos += 3 // total distance (u24)
        if (flags and 0x0020 != 0) pos += 2 // resistance level (s16)
        val power = if (flags and 0x0040 != 0) readS16(data, pos) else null

        // A field the flags declare as present but that does not fit means the
        // frame is truncated: report nothing rather than a half-parsed reading.
        val speedMissing = flags and 0x0001 == 0 && speed100 == null
        val cadenceMissing = flags and 0x0004 != 0 && cadence == null
        val powerMissing = flags and 0x0040 != 0 && power == null
        if (speedMissing || cadenceMissing || powerMissing) return null

        return PowerCadenceSpeed(power, cadence, speed100?.let { it / 100.0 })
    }

    /** Little-endian u16 at [pos], or null when the frame is too short. */
    private fun readU16(data: ByteArray, pos: Int): Int? {
        if (pos < 0 || data.size < pos + 2) return null
        return (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
    }

    /** Little-endian signed i16 at [pos], or null when the frame is too short. */
    private fun readS16(data: ByteArray, pos: Int): Int? {
        val raw = readU16(data, pos) ?: return null
        return if (raw and 0x8000 != 0) raw - 0x10000 else raw
    }

    /**
     * Heart Rate Measurement (0x2A37):
     * byte0 flags: bit0 = 16-bit HR format, bit1 = contact supported, bit2 = contact detected
     * HR value follows as u8 or u16 LE.
     */
    fun parseHeartRate(data: ByteArray): Int? {
        if (data.size < 2) return null
        val flags = data[0].toInt() and 0xFF
        return if (flags and 0x01 != 0) {
            if (data.size < 3) null else (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            data[1].toInt() and 0xFF
        }
    }

    /**
     * Cycling Speed & Cadence Measurement (0x2A5B):
     * byte0 flags: bit0 = wheel data present, bit1 = crank data present
     *
     * Layout follows the specification table: wheel block first (u32 cumulative
     * wheel revolutions + u16 last wheel event time), crank block second (u32
     * cumulative crank revolutions + u16 last crank event time). A combo sensor
     * sets both flags (0x03), so the wheel block has to be skipped before the
     * crank block — otherwise wheel revolutions get read as crank revolutions.
     *
     * Crank revolutions are u32 per the specification, but some cheap sensors
     * (iGPSPORT CAD70 among them, confirmed on-device: 5-byte frames) truncate
     * them to u16: flags + u16 revolutions + u16 event time. A crank block of 4
     * remaining bytes is therefore read as that short layout, and its
     * revolution delta wraps at 2^16 instead of 2^32.
     *
     * Cadence (rpm) = 60 * 1024 * dRev / dTime; null until two crank samples.
     */
    fun parseCscCrank(data: ByteArray, prev: CscCrankState?): Pair<CscCrankState, Double?> {
        if (data.size < 2) return (prev ?: CscCrankState()) to null
        val flags = data[0].toInt() and 0xFF
        var pos = 1
        // Wheel block precedes the crank block when both are present.
        if (flags and 0x01 != 0) pos += 6
        var rev: Long? = null
        var time: Int? = null
        var shortRevs = false
        if (flags and 0x02 != 0) { // crank data present
            val remaining = data.size - pos
            when {
                remaining >= 6 -> {
                    rev = (data[pos].toLong() and 0xFF) or
                        ((data[pos + 1].toLong() and 0xFF) shl 8) or
                        ((data[pos + 2].toLong() and 0xFF) shl 16) or
                        ((data[pos + 3].toLong() and 0xFF) shl 24)
                    time = (data[pos + 4].toInt() and 0xFF) or
                        ((data[pos + 5].toInt() and 0xFF) shl 8)
                }
                remaining >= 4 -> {
                    // Non-standard short layout: u16 revolutions + u16 event time.
                    shortRevs = true
                    rev = ((data[pos].toLong() and 0xFF) or
                        ((data[pos + 1].toLong() and 0xFF) shl 8))
                    time = (data[pos + 2].toInt() and 0xFF) or
                        ((data[pos + 3].toInt() and 0xFF) shl 8)
                }
                else -> return (prev ?: CscCrankState()) to null
            }
        }
        val newState = CscCrankState(rev ?: prev?.revolutions, time ?: prev?.lastEventTime)
        if (rev == null || time == null || prev == null ||
            prev.revolutions == null || prev.lastEventTime == null
        ) {
            return newState to null
        }
        var dRev = rev - prev.revolutions!!
        var dTime = time - prev.lastEventTime!!
        if (dRev < 0) dRev += if (shortRevs) (1L shl 16) else (1L shl 32)
        if (dTime < 0) dTime += (1 shl 16) // 16-bit event-time wraparound
        if (dTime <= 0 || dRev <= 0) return newState to null
        // rpm = rev * 60 / (timeDelta * (1/1024 s))
        val rpm = dRev * 60.0 * 1024.0 / dTime
        return newState to rpm
    }

    /**
     * True when a CSC frame carries crank data (flags bit1). A wheel-only
     * speed sensor never sets it; used to tell a speed sensor apart from a
     * cadence sensor, which share the same CSC service UUID.
     */
    fun cscFrameHasCrank(data: ByteArray): Boolean =
        data.isNotEmpty() && (data[0].toInt() and 0x02) != 0

    /** Supported Power Range (0x2AD8): min i16 LE, max i16 LE (W). */
    fun parseSupportedPowerRange(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 4) return null
        val min = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val max = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        return min to max
    }
}

data class PowerCadenceSpeed(
    val powerWatts: Int?,
    val cadenceRpm: Int?,
    val speedKmh: Double?,
)

data class CscCrankState(
    val revolutions: Long? = null,
    val lastEventTime: Int? = null,
)
