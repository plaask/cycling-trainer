package io.github.cyclingtrainer.app.ble

import java.util.UUID

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
