package io.github.cyclingtrainer.app.ble

import kotlin.math.roundToInt

/**
 * Decoders for standard BLE fitness-service notifications.
 * All parsing is defensive: a malformed frame yields null instead of throwing.
 */
object BtParsers {

    /**
     * FTMS Indoor Bike Data (0x2AD2) — 20-byte base frame plus optional fields.
     *
     * Flag bits (little-endian bitmask, byte0):
     *   0x0001 more data       0x0002 instantaneous power (W, i16)
     *   0x0004 instantaneous cadence (rpm, u16)   0x0008 total distance
     *   0x0010 instantaneous speed (km/h * 100, u16)  ... (wheel/gear ignored)
     *
     * @return [PowerCadenceSpeed] or null if the frame has no power and no cadence.
     */
    fun parseIndoorBikeData(data: ByteArray): PowerCadenceSpeed? {
        if (data.size < 2) return null
        val flags = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        var pos = 2
        var power: Int? = null
        var cadence: Int? = null
        var speed100: Int? = null

        if (flags and 0x0001 != 0) pos += 1 // reserved (cumulative wheel revolutions etc.)

        if (flags and 0x0002 != 0) {
            if (data.size < pos + 2) return null
            power = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
        }
        if (flags and 0x0004 != 0) {
            if (data.size < pos + 2) return null
            cadence = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
        }
        if (flags and 0x0010 != 0) {
            if (data.size < pos + 2) return null
            speed100 = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        }
        return PowerCadenceSpeed(power, cadence, speed100?.let { it / 100.0 })
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
     * Crank: cumulative crank revolutions (u32 LE) + last crank event time (u16 LE, 1/1024 s).
     * Cadence (rpm) = 60 * 1024 * dRev / dTime; null until two crank samples.
     */
    fun parseCscCrank(data: ByteArray, prev: CscCrankState?): Pair<CscCrankState, Double?> {
        if (data.size < 2) return (prev ?: CscCrankState()) to null
        val flags = data[0].toInt() and 0xFF
        var pos = 1
        var rev: Long? = null
        var time: Int? = null
        if (flags and 0x02 != 0) { // crank data present
            if (data.size < pos + 6) return (prev ?: CscCrankState()) to null
            rev = (data[pos].toLong() and 0xFF) or ((data[pos + 1].toLong() and 0xFF) shl 8) or
                ((data[pos + 2].toLong() and 0xFF) shl 16) or ((data[pos + 3].toLong() and 0xFF) shl 24)
            time = (data[pos + 4].toInt() and 0xFF) or ((data[pos + 5].toInt() and 0xFF) shl 8)
            pos += 6
        }
        val newState = CscCrankState(rev ?: prev?.revolutions, time ?: prev?.lastEventTime)
        if (rev == null || time == null || prev == null ||
            prev.revolutions == null || prev.lastEventTime == null
        ) {
            return newState to null
        }
        var dRev = rev - prev.revolutions!!
        var dTime = time - prev.lastEventTime!!
        if (dRev < 0) dRev += (1L shl 32)
        if (dTime < 0) dTime += (1 shl 16) // 16-bit event-time wraparound
        if (dTime <= 0 || dRev <= 0) return newState to null
        // rpm = rev * 60 / (timeDelta * (1/1024 s))
        val rpm = dRev * 60.0 * 1024.0 / dTime
        return newState to rpm
    }

    /** Supported Power Range (0x2AD8): min i16 LE, max i16 LE (W). */
    fun parseSupportedPowerRange(data: ByteArray): Pair<Int, Int>? {
        if (data.size < 4) return null
        val min = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val max = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        return min to max
    }

    /** FTMS Feature (0x2ACC): 4 bytes, bit 6 of byte1 (0x40<<8=0x4000?) — see caller. */
    fun parseFtmsFeatures(data: ByteArray): Long? {
        if (data.size < 4) return null
        var v = 0L
        for (i in 0 until 4) v = v or ((data[i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}

data class PowerCadenceSpeed(
    val powerWatts: Int?,
    val cadenceRpm: Int?,
    val speedKmh: Double?,
) {
    val hasUseful: Boolean get() = powerWatts != null || cadenceRpm != null
}

data class CscCrankState(
    val revolutions: Long? = null,
    val lastEventTime: Int? = null,
)

fun Int.signed16(): Int = if (this and 0x8000 != 0) this - 0x10000 else this
