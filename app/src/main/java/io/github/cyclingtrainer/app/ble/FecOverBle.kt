package io.github.cyclingtrainer.app.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * FE-C over BLE ("Tacx tunnel") trainer client.
 *
 * Tacx and ThinkRider smart trainers (X2/X5/X7…) do not expose FTMS; instead
 * they carry ANT+ FE-C frames over a private GATT service:
 *
 *   6e40fec1…  service
 *   6e40fec2…  FEC2 notify — trainer → app telemetry
 *   6e40fec3…  FEC3 write  — app → trainer control
 *
 * Frame format (13 bytes, from Auuki's real captures of a ThinkRider X7):
 *   [0]=0xA4 sync  [1]=0x09 len  [2]=0x4E broadcast  [3]=0x05 channel
 *   [4]=data page   [5..11] payload   [12]=XOR checksum of bytes 0..11
 *
 * Data pages:
 *   25 (0x19) Specific Trainer Data — cadence [6], power 12-bit [9..10]
 *   16 (0x10) General FE Data — speed [8..9] in 0.001 km/h? see parse below
 *   0x31      Set Target Power (ERG): target = watts / 0.25 (u16 LE), dup byte
 */
class FecOverBle(private val session: BluetoothLeManager.GattSession) {

    /** Live power (W) from FE-C page 25. */
    val powerFlow: Flow<Int?> = session.notifications
        .filter { it.first == GattUuids.FEC_TX }
        .map { FecParsers.page25Power(it.second) }

    /** Live cadence (rpm) from FE-C page 25. */
    val cadenceFlow: Flow<Int?> = session.notifications
        .filter { it.first == GattUuids.FEC_TX }
        .map { FecParsers.page25Cadence(it.second) }

    /** Live speed (km/h) from FE-C page 16 (or null). */
    val speedFlow: Flow<Double?> = session.notifications
        .filter { it.first == GattUuids.FEC_TX }
        .map { FecParsers.page16Speed(it.second) }

    private val txChar by lazy { session.characteristic(GattUuids.FEC_SERVICE, GattUuids.FEC_TX) }
    private val rxChar by lazy { session.characteristic(GattUuids.FEC_SERVICE, GattUuids.FEC_RX) }

    suspend fun connect(): Result<Unit> = runCatching {
        val tx = txChar ?: throw IllegalStateException("FE-C 隧道缺少 FEC2 特征")
        val rx = rxChar ?: throw IllegalStateException("FE-C 隧道缺少 FEC3 特征")
        if (!session.enableNotifications(tx)) {
            throw IllegalStateException("FE-C FEC2 订阅失败")
        }
        @Suppress("unused") val writeable = rx
        Result.success(Unit)
    }

    /**
     * Sets ERG target power. Frame: A4 09 4E 05 31 FF FF FF FF FF <u16LE:watts/0.25> <dup> XOR
     */
    suspend fun setTargetPower(watts: Int): Boolean {
        val rx = rxChar ?: return false
        val frame = FecParsers.targetPowerFrame(watts) ?: return false
        return session.write(rx, frame) == true
    }

    /** Stops ERG (free ride): target power 0 releases the trainer. */
    suspend fun setTargetPowerOff(): Boolean = setTargetPower(0)
}

/** Pure frame builders/parsers for the FE-C-over-BLE tunnel (unit-testable). */
object FecParsers {

    private const val SYNC = 0xA4
    private const val LEN = 0x09
    private const val TYPE_BROADCAST = 0x4E
    private const val CHANNEL = 0x05
    private const val PAGE_SPECIFIC_TRAINER = 0x19 // page 25
    private const val PAGE_GENERAL_FE = 0x10        // page 16
    private const val PAGE_SET_TARGET_POWER = 0x31  // control

    /** Page 25 (0x19) power: byte9 LSB, low nibble byte10 MSB; 0xFFF = invalid. */
    fun page25Power(frame: ByteArray): Int? {
        if (frame.size < 11 || (frame[4].toInt() and 0xFF) != PAGE_SPECIFIC_TRAINER) return null
        val power = (frame[9].toInt() and 0xFF) or ((frame[10].toInt() and 0x0F) shl 8)
        return if (power == 0xFFF) null else power
    }

    /** Page 25 (0x19) cadence: byte6; 0xFF = invalid. */
    fun page25Cadence(frame: ByteArray): Int? {
        if (frame.size < 7 || (frame[4].toInt() and 0xFF) != PAGE_SPECIFIC_TRAINER) return null
        val cad = frame[6].toInt() and 0xFF
        return if (cad == 0xFF) null else cad
    }

    /** Page 16 (0x10) speed: bytes 8-9 u16LE in 0.001 m/s; convert to km/h. */
    fun page16Speed(frame: ByteArray): Double? {
        if (frame.size < 10 || (frame[4].toInt() and 0xFF) != PAGE_GENERAL_FE) return null
        val raw = (frame[8].toInt() and 0xFF) or ((frame[9].toInt() and 0xFF) shl 8)
        if (raw == 0xFFFF) return null
        return raw * 0.001 * 3.6
    }

    /**
     * Builds a Set Target Power frame for [watts] with the XOR checksum.
     * Layout per Auuki's captured ThinkRider frames:
     *   [0]=0xA4 [1]=0x09 [2]=0x4E [3]=0x05 [4]=0x31
     *   [5..9]=0xFF [10]=lo(watts/0.25) [11]=hi(watts/0.25) [12]=XOR(0..11)
     */
    fun targetPowerFrame(watts: Int): ByteArray? {
        val scaled = (watts / 0.25).toInt()
        if (scaled < 0 || scaled > 0xFFFF) return null
        val frame = ByteArray(13)
        frame[0] = SYNC.toByte()
        frame[1] = LEN.toByte()
        frame[2] = TYPE_BROADCAST.toByte()
        frame[3] = CHANNEL.toByte()
        frame[4] = PAGE_SET_TARGET_POWER.toByte()
        frame[5] = 0xFF.toByte(); frame[6] = 0xFF.toByte(); frame[7] = 0xFF.toByte()
        frame[8] = 0xFF.toByte(); frame[9] = 0xFF.toByte()
        frame[10] = (scaled and 0xFF).toByte()
        frame[11] = ((scaled shr 8) and 0xFF).toByte()
        // XOR checksum over bytes 0..11
        var crc = 0
        for (i in 0 until 12) crc = crc xor (frame[i].toInt() and 0xFF)
        frame[12] = crc.toByte()
        return frame
    }
}
