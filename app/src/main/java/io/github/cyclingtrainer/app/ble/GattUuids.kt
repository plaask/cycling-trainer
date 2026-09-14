package io.github.cyclingtrainer.app.ble

import java.util.UUID

/**
 * GATT UUIDs and protocol constants for the BLE layers.
 * See handoff: FTMS(0x1826) trainer + HRS(0x180D) + CSC(0x1816).
 */
object GattUuids {
    // Standard services
    val FTMS_SERVICE = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    val HRS_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val CSC_SERVICE = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

    // FTMS characteristics
    val FTMS_FEATURE = UUID.fromString("00002acc-0000-1000-8000-00805f9b34fb")           // read
    val FTMS_INDOOR_BIKE_DATA = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb") // notify
    val FTMS_TRAINING_STATUS = UUID.fromString("00002ad3-0000-1000-8000-00805f9b34fb")  // notify
    val FTMS_SUPPORTED_POWER_RANGE = UUID.fromString("00002ad8-0000-1000-8000-00805f9b34fb") // read
    val FTMS_SUPPORTED_RESISTANCE_RANGE = UUID.fromString("00002ad6-0000-1000-8000-00805f9b34fb") // read
    val FTMS_FITNESS_MACHINE_CONTROL_POINT = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb") // indicate
    val FTMS_FITNESS_MACHINE_STATUS = UUID.fromString("00002ada-0000-1000-8000-00805f9b34fb") // notify

    // HRS / CSC characteristics
    val HRS_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CSC_MEASUREMENT = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
    val CSC_FEATURE = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb")

    // Cycling Power Service (0x1818). Some trainers (ThinkRider X2/X7) expose
    // this instead of FTMS for the power/cadence stream.
    val CPS_SERVICE = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
    val CPS_MEASUREMENT = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")

    // FE-C over BLE ("Tacx" tunnel) — Tacx and ThinkRider smart trainers
    // shuttle ANT+ FE-C frames over this private GATT service for both
    // telemetry (page 16/25) and ERG control (page 0x31).
    val FEC_SERVICE = UUID.fromString("6e40fec1-b5a3-f393-e0a9-e50e24dcca9e")
    val FEC_TX = UUID.fromString("6e40fec2-b5a3-f393-e0a9-e50e24dcca9e") // notify trainer->app
    val FEC_RX = UUID.fromString("6e40fec3-b5a3-f393-e0a9-e50e24dcca9e") // write app->trainer

    // Descriptor
    val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** FTMS Control Point opcodes (client -> trainer). */
object FtmsOpcodes {
    const val REQUEST_CONTROL = 0x00
    const val RESET = 0x01
    const val SET_TARGET_SPEED = 0x02
    const val SET_TARGET_INCLINATION = 0x03
    const val SET_TARGET_RESISTANCE = 0x04
    const val SET_TARGET_POWER = 0x05
    const val SET_TARGET_HEART_RATE = 0x06
    const val START_OR_RESUME = 0x07
    const val STOP_OR_PAUSE = 0x08
    const val SET_TARGETED_TRAINING = 0x10
    const val RESPONSE = 0x80.toByte()
}

/** FTMS Control Point result codes (in [0x80, opcode, result] indications). */
object FtmsResults {
    const val SUCCESS = 0x01
    const val OPCODE_NOT_SUPPORTED = 0x02
    const val INVALID_PARAMETER = 0x03
    const val OPERATION_FAILED = 0x04
    const val CONTROL_NOT_PERMITTED = 0x05
}

/** FTMS Fitness Machine Status values (0x2ADA notify byte 0). */
object FtmsMachineStatus {
    const val RESERVED = 0x00
    const val STOPPED_OR_PAUSED = 0x01
    const val RUNNING = 0x02
    const val IN_USE_AND_RUNNING = 0x04 // informal; some trainers send 0x04 while running
    const val CONTROL_PERMISSION_LOST = 0xFF.toInt() // signed -1
}

/** Indoor Bike Data (0x2AD2) flag bits (little-endian 16-bit). */
object IndoorBikeFlags {
    const val INSTANTANEOUS_POWER = 0x0002
    const val INSTANTANEOUS_CADENCE = 0x0004
    const val INSTANTANEOUS_SPEED = 0x0010
    const val TOTAL_DISTANCE = 0x0001
}
