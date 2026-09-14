package io.github.cyclingtrainer.app.ble

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FTMS trainer (0x1826) client for an already-open [BluetoothLeManager.GattSession].
 *
 * Handshake sequence (per handoff §5, verified against OpenBike/TrackMyIndoorWorkout
 * protocol notes, implemented from the spec rather than copied code):
 *
 *  1. subscribe to Control Point (0x2AD9) indications + Status (0x2ADA) notifications
 *  2. read Feature / Supported Power Range
 *  3. Request Control (0x00) -> expect response indication [0x80, 0x00, 0x01]
 *  4. Start/Resume (0x07)
 *  5. Set Target Power (0x05) -> int16 LE watts (clamped to power range)
 */
class FtmsTrainer(private val session: BluetoothLeManager.GattSession) {

    private val gatt = session

    private val controlPoint: BluetoothGattCharacteristic?
        get() = session.characteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_FITNESS_MACHINE_CONTROL_POINT)

    private val indoorBikeData: BluetoothGattCharacteristic?
        get() = session.characteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_INDOOR_BIKE_DATA)

    /** Live instantaneous power from Indoor Bike Data (0x2AD2). */
    val powerFlow: Flow<Int?> = session.notifications
        .filter { it.first == GattUuids.FTMS_INDOOR_BIKE_DATA }
        .map { BtParsers.parseIndoorBikeData(it.second)?.powerWatts }

    /** Live cadence (rpm) from Indoor Bike Data — may be null on trainers without it. */
    val cadenceFlow: Flow<Int?> = session.notifications
        .filter { it.first == GattUuids.FTMS_INDOOR_BIKE_DATA }
        .map { BtParsers.parseIndoorBikeData(it.second)?.cadenceRpm }

    val speedFlow: Flow<Double?> = session.notifications
        .filter { it.first == GattUuids.FTMS_INDOOR_BIKE_DATA }
        .map { BtParsers.parseIndoorBikeData(it.second)?.speedKmh }

    /** Machine status notifications (0x2ADA). */
    val statusFlow: Flow<Int> = session.notifications
        .filter { it.first == GattUuids.FTMS_FITNESS_MACHINE_STATUS }
        .map { if (it.second.isNotEmpty()) it.second[0].toInt() and 0xFF else -1 }

    /** True once control has been acquired. */
    @Volatile var hasControl: Boolean = false
        private set

    /** Whether this trainer exposes an FTMS Control Point at all (ERG-capable). */
    val controlPointAvailable: Boolean
        get() = controlPoint != null

    @Volatile var supportedPowerRange: Pair<Int, Int>? = null
        private set

    /** Prepares subscriptions; safe to call once after connect.
     *
     *  Data (Indoor Bike Data 0x2AD2) is mandatory — without it the device is
     *  not a usable FTMS trainer. The Control Point (0x2AD9) is optional:
     *  some trainers expose measurement data but not (yet) ERG control, and
     *  they should still connect for power/cadence instead of failing hard.
     *  [hasControl] then reports whether ERG control is available.
     */
    suspend fun connect(): Result<Unit> = runCatching {
        val g = session
        if (indoorBikeData == null) {
            val svcs = session.discoveredServices
                .take(20)
                .joinToString(", ") { u -> u.toString().substring(4, 8) }
            throw IllegalStateException(
                "该设备未提供标准骑行台数据服务(缺 0x2AD2)。实际发现服务: $svcs"
            )
        }
        val dataOk = g.enableNotifications(indoorBikeData!!)
        if (!dataOk) throw IllegalStateException("FTMS 数据订阅失败（CCCD 写入失败/超时）")
        if (controlPoint != null) {
            // Control Point is an indication characteristic: the CCCD must be
            // enabled with 0x0002 (indications), not 0x0001.
            g.enableNotifications(controlPoint!!, indicate = true)
        }
        session.characteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_TRAINING_STATUS)
            ?.let { g.enableNotifications(it) }
        session.characteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_FITNESS_MACHINE_STATUS)
            ?.let { g.enableNotifications(it) }
        // Optional feature/power-range reads
        val feature = session.characteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_FEATURE)
        feature?.let { session.read(it) }
        val pr = session.characteristic(GattUuids.FTMS_SERVICE, GattUuids.FTMS_SUPPORTED_POWER_RANGE)
        pr?.let { r ->
            session.read(r)?.let { bytes ->
                BtParsers.parseSupportedPowerRange(bytes)?.let { supportedPowerRange = it }
            }
        }
        Result.success(Unit)
    }

    /** Control-point request that expects a response indication. */
    private suspend fun request(payload: ByteArray): Boolean {
        val cp = controlPoint ?: return false
        val resp = session.commandAndWait(cp, payload) ?: return false
        return resp.size >= 3 &&
            (resp[0].toInt() and 0xFF) == 0x80 &&
            (resp[1].toInt() and 0xFF) == (payload[0].toInt() and 0xFF) &&
            (resp[2].toInt() and 0xFF) == FtmsResults.SUCCESS
    }

    /** Request Control (0x00). */
    suspend fun requestControl(): Boolean {
        val ok = request(byteArrayOf(FtmsOpcodes.REQUEST_CONTROL.toByte()))
        if (ok) hasControl = true
        return ok
    }

    /** Reset (0x01). */
    suspend fun reset(): Boolean = request(byteArrayOf(FtmsOpcodes.RESET.toByte()))

    /** Start/Resume (0x07). */
    suspend fun start(): Boolean = request(byteArrayOf(FtmsOpcodes.START_OR_RESUME.toByte()))

    /** Stop/Pause (0x08). */
    suspend fun stop(): Boolean = request(byteArrayOf(FtmsOpcodes.STOP_OR_PAUSE.toByte()))

    /**
     * Set Target Power (0x05) with int16 LE watts.
     * Clamps to the supported power range when known; returns false on failure.
     */
    suspend fun setTargetPower(watts: Int): Boolean {
        val w = watts.coerceIn(
            supportedPowerRange?.first ?: 0,
            supportedPowerRange?.second ?: 4000,
        )
        val payload = ByteArray(3)
        payload[0] = FtmsOpcodes.SET_TARGET_POWER.toByte()
        payload[1] = (w and 0xFF).toByte()
        payload[2] = ((w shr 8) and 0xFF).toByte()
        return request(payload)
    }

    /** Sends a control request and awaits up to [timeoutMs] for an indication. */
    suspend fun requestWithTimeout(
        payload: ByteArray, timeoutMs: Long = 8000,
    ): ByteArray? {
        val cp = controlPoint ?: return null
        return session.commandAndWait(cp, payload, timeoutMs)
    }
}
