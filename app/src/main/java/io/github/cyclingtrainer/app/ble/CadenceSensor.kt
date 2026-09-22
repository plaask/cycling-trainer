package io.github.cyclingtrainer.app.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.map

/**
 * Cycling Speed & Cadence (0x1816) client. Computes crank cadence (rpm) from
 * cumulative-revolution deltas, per the CSC spec (1/1024 s event time ticks).
 *
 * Speed sensors and cadence sensors both advertise this service, so the UUID
 * alone does not prove a device is a cadence source: a wheel-only sensor sends
 * frames without the crank flag and would never produce an rpm. [connect]
 * therefore checks the CSC Feature characteristic and [sawCrankData] lets the
 * caller spot a speed sensor that omitted it.
 */
class CadenceSensor(
    private val session: BluetoothLeManager.GattSession,
    private val scope: CoroutineScope,
) {
    // Crank state kept between notifications to compute rpm deltas.
    private val crankState = MutableStateFlow<CscCrankState?>(null)

    /** True once any frame carried the crank-data flag (CSC flags bit1). */
    @Volatile var sawCrankData = false
        private set

    /** True once any frame carried wheel data (CSC flags bit0). */
    @Volatile var sawWheelData = false
        private set

    /** Live cadence in rpm from the CSC service. */
    val cadenceFlow: Flow<Double?> = session.notifications
        .filter { it.first == GattUuids.CSC_MEASUREMENT }
        .map { frame ->
            if (BtParsers.cscFrameHasCrank(frame.second)) sawCrankData = true
            if (frame.second.firstOrNull()?.toInt()?.and(0x01) != 0) sawWheelData = true
            val (next, rpm) = BtParsers.parseCscCrank(frame.second, crankState.value)
            crankState.value = next
            Log.d(TAG, "CSC 帧 len=${frame.second.size} hex=${frame.second.joinToString("") { "%02X".format(it) }} flags=${frame.second.firstOrNull()?.toInt()?.and(0xFF)} rpm=${if (rpm == null) "null" else "%.0f".format(rpm)}")
            rpm
        }

    val cadence: Flow<Double> = cadenceFlow.mapNotNull { it }

    private val measurementChar by lazy {
        session.characteristic(GattUuids.CSC_SERVICE, GattUuids.CSC_MEASUREMENT)
    }

    suspend fun connect(): Result<Unit> = runCatching {
        val ch = measurementChar
            ?: throw IllegalStateException("CSC measurement characteristic missing")
        val ok = session.enableNotifications(ch)
        if (!ok) throw IllegalStateException("踏频器通知订阅失败（CCCD 写入失败/超时）")
        rejectIfWheelOnly()
        Log.i(TAG, "CSC 订阅成功 ($TAG)")
        Result.success(Unit)
    }

    /**
     * Reads the CSC Feature characteristic (0x2A5C, bit0 = wheel revolutions
     * supported, bit1 = crank revolutions supported) and rejects devices that
     * do not declare crank support: those are speed sensors. Letting one take
     * the cadence role would show "CSC(—)" forever and lock out the trainer's
     * own cadence as the fallback source.
     *
     * The characteristic is optional in practice, so a missing/unreadable value
     * is not fatal — the frame-based check in DeviceManager covers that case.
     */
    private suspend fun rejectIfWheelOnly() {
        val feature = session.characteristic(GattUuids.CSC_SERVICE, GattUuids.CSC_FEATURE)
            ?: return
        val value = session.read(feature) ?: return
        if (value.size < 2) return
        val bits = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        val crank = bits and 0x02 != 0
        Log.i(TAG, "CSC Feature=0x%04X crank=%s".format(bits, crank))
        if (!crank) {
            throw IllegalStateException(
                "该设备只提供速度数据（速度计/处于速度模式），不能用作踏频源"
            )
        }
    }

    /** Re-writes the CCCD — some stacks/devices silently drop the first
     *  subscription; re-arming usually gets the stream going. */
    suspend fun resubscribe(): Boolean {
        val ch = measurementChar ?: return false
        val ok = session.enableNotifications(ch)
        Log.i(TAG, "CSC 重订阅: $ok")
        return ok
    }

    companion object {
        private const val TAG = "CyclingTrainer/CSC"
    }
}
