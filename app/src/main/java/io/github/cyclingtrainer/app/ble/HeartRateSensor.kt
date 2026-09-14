package io.github.cyclingtrainer.app.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** Heart Rate Service (0x180D) client for a connected session. */
class HeartRateSensor(private val session: BluetoothLeManager.GattSession) {

    val heartRateFlow: Flow<Int?> = session.notifications
        .filter { it.first == GattUuids.HRS_MEASUREMENT }
        .map { BtParsers.parseHeartRate(it.second) }

    /** Like [heartRateFlow] but skips nulls (convenience for UI). */
    val heartRate: Flow<Int> = heartRateFlow.mapNotNull { it }

    /** Subscribes to HRS Measurement notifications. */
    suspend fun connect(): Result<Unit> = runCatching {
        val ch = session.characteristic(GattUuids.HRS_SERVICE, GattUuids.HRS_MEASUREMENT)
            ?: throw IllegalStateException("HRS measurement characteristic missing")
        val ok = session.enableNotifications(ch)
        if (!ok) throw IllegalStateException("心率带通知订阅失败（CCCD 写入失败/超时）")
        Result.success(Unit)
    }
}
