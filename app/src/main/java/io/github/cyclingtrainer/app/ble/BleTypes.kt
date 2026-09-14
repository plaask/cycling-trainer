package io.github.cyclingtrainer.app.ble

import java.util.UUID

/** Live values pushed from a connected BLE peripheral. */
data class SensorValues(
    val powerWatts: Int? = null,
    val cadenceRpm: Double? = null,
    val speedKmh: Double? = null,
    val heartRateBpm: Int? = null,
)

/** Connection state machine for one peripheral. */
enum class DeviceState { DISCONNECTED, CONNECTING, DISCOVERING, READY, CONTROL_ACQUIRED, FAILED }

/** A discovered BLE device candidate. */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Service UUIDs from the advertisement (may be empty — some trainers
     *  advertise nothing and only reveal their services after connecting). */
    val services: List<UUID>,
    /** Services actually discovered after connecting; may reveal a role
     *  (e.g. FTMS trainer) that the advertisement never carried. */
    val discoveredServices: List<UUID> = emptyList(),
) {
    /** All known services: advertised plus post-connect discovery. */
    val allServices: List<UUID> get() = services + discoveredServices
}

/** Opaque handle for a connected peripheral used by [BleConnection]. */
interface DeviceHandle {
    val address: String
    val name: String?
}
