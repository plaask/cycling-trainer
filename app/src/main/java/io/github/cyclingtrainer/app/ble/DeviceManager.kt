package io.github.cyclingtrainer.app.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregates the target devices (trainer + HR strap + cadence sensor) into
 * live streams, applying the cadence-source priority rule:
 *
 *   1. external CSC cadence (iGPSPORT CAD70) — preferred
 *   2. fall back to the trainer's own cadence (FTMS or FE-C)
 *
 * The trainer slot accepts either a standard FTMS trainer (0x1826) or an
 * FE-C-over-BLE trainer (ThinkRider/Tacx tunnel, 6e40fec1) — the driver is
 * chosen after connecting based on the services the device actually exposes.
 */
class DeviceManager(
    val ble: BluetoothLeManager,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    // Role connections (address-level, exposed to UI).
    val trainerAddress = MutableStateFlow<String?>(null)
    val hrAddress = MutableStateFlow<String?>(null)
    val cscAddress = MutableStateFlow<String?>(null)

    /** Last known device names by address (scan + connect), so a connected
     *  device keeps its name even after it stops advertising. */
    private val knownNames = ConcurrentHashMap<String, String>()
    fun deviceName(address: String?): String? = address?.let { knownNames[it] }

    /** Records a name seen in a scan result / connection. */
    fun rememberName(address: String, name: String?) {
        if (name != null) knownNames[address] = name
    }

    // Live aggregates.
    val powerWatts = MutableStateFlow<Int?>(null)
    val heartRateBpm = MutableStateFlow<Int?>(null)

    // Cadence source priority: CSC preferred, trainer (FTMS/FE-C) fallback.
    private val cscCadence = MutableStateFlow<Double?>(null)
    private val trainerCadence = MutableStateFlow<Double?>(null)

    /** Protocol tag of the connected trainer for the UI (FTMS / FE-C / —). */
    private val trainerSourceTag = MutableStateFlow("—")

    /** Effective cadence: external CSC wins while attached; otherwise the
     *  trainer's own cadence fills in. */
    val cadenceRpm: StateFlow<Double?> = combine(
        cscCadence, trainerCadence, cscAddress,
    ) { csc, t, cscAddr ->
        if (cscAddr != null) csc else t
    }.stateIn(externalScope, SharingStarted.Eagerly, null)

    /** Cadence source tag for the UI. */
    val cadenceSource: StateFlow<String> = combine(
        cscCadence, trainerCadence, trainerSourceTag, cscAddress,
    ) { csc, t, tag, cscAddr ->
        when {
            cscAddr != null -> if (csc != null) "CSC" else "CSC(—)"
            t != null -> tag
            else -> "—"
        }
    }.stateIn(externalScope, SharingStarted.Eagerly, "—")

    val trainerReady = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)

    private var trainerDriver: TrainerDriver? = null

    /**
     * Connects a single role (trainer / hr / csc) without touching the other
     * roles' sessions. Role name = the concrete driver:
     *  - "trainer": auto-selects FTMS vs FE-C from the services the device
     *    actually exposes after connecting.
     *  - "hr": heart-rate strap.  - "csc": cadence sensor.
     *
     * The old connectAll() first disconnected EVERYTHING and reconnected all
     * three roles on every tap, so pairing a second device right after an app
     * restart tore down the first one (and vice versa). Connecting is now
     * strictly incremental: existing connections stay untouched.
     */
    suspend fun connectRole(
        role: String,
        address: String,
        name: String? = null,
    ): Result<Unit> {
        errorMessage.value = null
        if (name != null) knownNames[address] = name
        return try {
            val s = ble.connect(address).getOrThrow()
            when (role) {
                "trainer" -> {
                    val driver: TrainerDriver = when {
                        s.discoveredServices.any { it == GattUuids.FTMS_SERVICE } -> {
                            trainerSourceTag.value = "FTMS"
                            FtmsTrainerDriver(FtmsTrainer(s))
                        }
                        s.discoveredServices.any { it == GattUuids.FEC_SERVICE } -> {
                            trainerSourceTag.value = "FE-C"
                            FecTrainerDriver(FecOverBle(s))
                        }
                        else -> throw IllegalStateException(
                            "连接成功，但该设备既无 FTMS(1826) 也无 FE-C(fec1) 服务；实际: " +
                                s.discoveredServices.take(12)
                                    .joinToString(", ") { u -> u.toString().substring(4, 8) }
                        )
                    }
                    driver.connect().getOrThrow()
                    trainerAddress.value = address
                    trainerDriver = driver
                    trainerReady.value = true
                    launchTrainer(driver)
                    watchSession(s, "trainer")
                }
                "hr" -> {
                    val sensor = HeartRateSensor(s)
                    sensor.connect().getOrThrow()
                    hrAddress.value = address
                    launchHeartRate(sensor)
                    watchSession(s, "hr")
                }
                "csc" -> {
                    val sensor = CadenceSensor(s, externalScope)
                    sensor.connect().getOrThrow()
                    cscAddress.value = address
                    launchCadence(sensor, address)
                    watchSession(s, "csc")
                }
                else -> throw IllegalArgumentException("未知角色: $role")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            errorMessage.value = "${roleLabel(role)}: ${e.message ?: "连接失败"}"
            Result.failure(e)
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        "trainer" -> "骑行台"
        "hr" -> "心率带"
        "csc" -> "踏频器"
        else -> "设备"
    }

    private fun launchTrainer(t: TrainerDriver) {
        externalScope.launch {
            t.powerFlow.collect { p ->
                // FE-C interleaves telemetry pages; frames without power come
                // through as null and must not blank the last reading.
                if (p != null) powerWatts.value = p
            }
        }
        externalScope.launch {
            t.cadenceFlow.collect { c ->
                if (c != null) trainerCadence.value = c.toDouble()
            }
        }
    }

    /**
     * Watches a role's GATT connection state. When the link drops by itself
     * (strap sleeps / trainer powers off) the role address is cleared so the
     * UI stops showing "已连接"; the device page still lists it and can be
     * re-paired (it sweeps stale sessions first).
     */
    private fun watchSession(s: BluetoothLeManager.GattSession, role: String) {
        externalScope.launch {
            s.connectionState.collect { st ->
                if (st == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    when (role) {
                        "trainer" -> if (trainerAddress.value == s.address) {
                            trainerAddress.value = null
                            trainerReady.value = false
                            trainerDriver = null
                            trainerSourceTag.value = "—"
                        }
                        "hr" -> if (hrAddress.value == s.address) hrAddress.value = null
                        "csc" -> if (cscAddress.value == s.address) cscAddress.value = null
                    }
                }
            }
        }
    }

    private fun launchHeartRate(s: HeartRateSensor) {
        externalScope.launch {
            s.heartRateFlow.collect { hr -> if (hr != null) heartRateBpm.value = hr }
        }
    }

    private fun launchCadence(s: CadenceSensor, address: String) {
        externalScope.launch {
            s.cadenceFlow.collect { c -> if (c != null) cscCadence.value = c }
        }
        // Health net: many CSC sensors need a moment after subscribe; if the
        // first frame has not arrived shortly after connecting, re-arm the
        // notification once (some stacks drop the first CCCD write silently).
        externalScope.launch {
            kotlinx.coroutines.delay(2500)
            if (cscAddress.value == address && cscCadence.value == null) {
                Log.i("DeviceManager", "CSC 无帧，尝试重订阅 $address")
                s.resubscribe()
            }
        }
    }

    suspend fun setTargetPower(watts: Int): Boolean =
        trainerDriver?.setTargetPower(watts) ?: false

    suspend fun startWorkout(): Boolean =
        trainerDriver?.startErg() ?: false

    suspend fun stopWorkout(): Boolean =
        trainerDriver?.stopControl() ?: false

    /** Disconnects one role by address (no-op if not connected here). */
    fun disconnectDevice(address: String) {
        if (trainerAddress.value == address) {
            ble.disconnect(address); trainerDriver = null
            trainerAddress.value = null; trainerReady.value = false; trainerSourceTag.value = "—"
        }
        if (hrAddress.value == address) { ble.disconnect(address); hrAddress.value = null }
        if (cscAddress.value == address) { ble.disconnect(address); cscAddress.value = null }
        if (trainerAddress.value == null && hrAddress.value == null && cscAddress.value == null) {
            powerWatts.value = null
            heartRateBpm.value = null
            cscCadence.value = null
            trainerCadence.value = null
        }
    }

    fun disconnectAll(clearError: Boolean = true) {
        ble.disconnectAll()
        trainerDriver = null
        trainerAddress.value = null
        hrAddress.value = null
        cscAddress.value = null
        trainerReady.value = false
        trainerSourceTag.value = "—"
        powerWatts.value = null
        heartRateBpm.value = null
        cscCadence.value = null
        trainerCadence.value = null
        if (clearError) errorMessage.value = null
    }

    fun release() {
        disconnectAll()
        externalScope.cancel()
    }
}
