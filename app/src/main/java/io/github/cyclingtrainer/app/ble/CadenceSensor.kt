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
 */
class CadenceSensor(
    private val session: BluetoothLeManager.GattSession,
    private val scope: CoroutineScope,
) {
    // Crank state kept between notifications to compute rpm deltas.
    private val crankState = MutableStateFlow<CscCrankState?>(null)

    /** Live cadence in rpm from the CSC service. */
    val cadenceFlow: Flow<Double?> = session.notifications
        .filter { it.first == GattUuids.CSC_MEASUREMENT }
        .map { frame ->
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
        Log.i(TAG, "CSC 订阅成功 ($TAG)")
        Result.success(Unit)
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
