package io.github.cyclingtrainer.app.ble

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    // Live aggregates. Each reading expires after a few seconds without a new
    // frame, so a sleeping strap or a powered-off trainer reads as "—" instead
    // of freezing its last value (and writing it into the ride CSV).
    private val powerReading = Reading<Int>()
    private val hrReading = Reading<Int>()
    private val speedReading = Reading<Double>()
    private val cscReading = Reading<Double>()
    private val trainerCadenceReading = Reading<Double>()

    /** Monotonic milliseconds; bumped so stale readings re-evaluate. */
    private val freshnessTick = MutableStateFlow(nowMs())

    init {
        // Ticker that re-evaluates staleness even when no new frame arrives
        // (that is exactly the case where a value has to disappear).
        externalScope.launch {
            while (true) {
                delay(500)
                freshnessTick.value = nowMs()
            }
        }
    }

    val powerWatts: StateFlow<Int?> =
        combine(powerReading.state, freshnessTick) { _: Long, now: Long ->
            powerReading.value(now)
        }.stateIn(externalScope, SharingStarted.Eagerly, null)

    val heartRateBpm: StateFlow<Int?> =
        combine(hrReading.state, freshnessTick) { _: Long, now: Long ->
            hrReading.value(now)
        }.stateIn(externalScope, SharingStarted.Eagerly, null)

    /** Instantaneous trainer speed in km/h (FE-C page 16 / FTMS 0x2AD2). */
    val speedKmh: StateFlow<Double?> =
        combine(speedReading.state, freshnessTick) { _: Long, now: Long ->
            speedReading.value(now)
        }.stateIn(externalScope, SharingStarted.Eagerly, null)

    /** Protocol tag of the connected trainer for the UI (FTMS / FE-C / —). */
    private val trainerSourceTag = MutableStateFlow("—")

    /** Effective cadence: external CSC wins while attached; otherwise the
     *  trainer's own cadence fills in. */
    val cadenceRpm: StateFlow<Double?> = combine(
        cscReading.state, trainerCadenceReading.state, cscAddress, freshnessTick,
    ) { _: Long, _: Long, cscAddr: String?, now: Long ->
        if (cscAddr != null) cscReading.value(now) else trainerCadenceReading.value(now)
    }.stateIn(externalScope, SharingStarted.Eagerly, null)

    /** Cadence source tag for the UI. */
    val cadenceSource: StateFlow<String> = combine(
        cscReading.state, trainerCadenceReading.state, trainerSourceTag, cscAddress, freshnessTick,
    ) { _: Long, _: Long, tag: String, cscAddr: String?, now: Long ->
        val csc = cscReading.value(now)
        val t = trainerCadenceReading.value(now)
        when {
            cscAddr != null -> if (csc != null) "CSC" else "CSC(—)"
            t != null -> tag
            else -> "—"
        }
    }.stateIn(externalScope, SharingStarted.Eagerly, "—")

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
                    sawCscFrame = false
                    cscAddress.value = address
                    launchCadence(sensor, address)
                    watchSession(s, "csc")
                }
                else -> throw IllegalArgumentException("未知角色: $role")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            errorMessage.value = "${roleLabel(role)}: ${e.message ?: "连接失败"}"
            // A session that never became a role must not be left behind: the
            // device row would still offer "连接" while a live GATT session sat
            // in the manager with nothing reading it.
            runCatching { ble.disconnect(address) }
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
                if (p != null) powerReading.set(p, nowMs())
            }
        }
        externalScope.launch {
            t.cadenceFlow.collect { c ->
                if (c != null) trainerCadenceReading.set(c.toDouble(), nowMs())
            }
        }
        externalScope.launch {
            t.speedFlow.collect { s ->
                if (s != null) speedReading.set(s, nowMs())
            }
        }
    }

    /**
     * Watches a role's GATT connection state. When the link drops by itself
     * (strap sleeps / trainer powers off) the role address is cleared so the
     * UI stops showing "已连接"; the device page still lists it and can be
     * re-paired (it sweeps stale sessions first).
     *
     * The role's readings are dropped at the same moment: otherwise the last
     * power/heart-rate value would keep being displayed and recorded after the
     * device is gone.
     */
    private fun watchSession(s: BluetoothLeManager.GattSession, role: String) {
        externalScope.launch {
            s.connectionState.collect { st ->
                if (st == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    when (role) {
                        "trainer" -> if (trainerAddress.value == s.address) {
                            trainerAddress.value = null
                            trainerDriver = null
                            trainerSourceTag.value = "—"
                            clearTrainerReadings()
                        }
                        "hr" -> if (hrAddress.value == s.address) {
                            hrAddress.value = null
                            hrReading.clear()
                        }
                        "csc" -> if (cscAddress.value == s.address) {
                            cscAddress.value = null
                            cscReading.clear()
                        }
                    }
                }
            }
        }
    }

    private fun clearTrainerReadings() {
        powerReading.clear()
        speedReading.clear()
        trainerCadenceReading.clear()
    }

    private fun launchHeartRate(s: HeartRateSensor) {
        externalScope.launch {
            s.heartRateFlow.collect { hr -> if (hr != null) hrReading.set(hr, nowMs()) }
        }
    }

    private fun launchCadence(s: CadenceSensor, address: String) {
        externalScope.launch {
            s.cadenceFlow.collect { c ->
                // Any frame (even one without a usable delta) proves the
                // subscription works.
                sawCscFrame = true
                if (c != null) cscReading.set(c, nowMs())
            }
        }
        // Health net: many CSC sensors need a moment after subscribe; if the
        // first frame has not arrived shortly after connecting, re-arm the
        // notification once (some stacks drop the first CCCD write silently).
        // The check is "has any frame ever arrived", not "is a value present":
        // a rider who simply is not pedalling yet must not be read as a dead
        // sensor.
        externalScope.launch {
            kotlinx.coroutines.delay(2500)
            if (cscAddress.value == address && !sawCscFrame) {
                Log.i("DeviceManager", "CSC 无帧，尝试重订阅 $address")
                s.resubscribe()
            }
        }
        // Speed sensors also expose the CSC service and connect fine, but their
        // frames never carry the crank flag. Once frames are arriving and still
        // no crank data has shown up, the device is not a cadence source: say so
        // and release the role. Keeping it would lock out the trainer's own
        // cadence (a non-null cscAddress wins over the fallback), leaving the UI
        // on "CSC(—)" while the trainer could have supplied a real reading.
        // A genuine cadence sensor sets the flag on every frame, even while the
        // rider is not pedalling, so a stationary bike does not trigger this.
        externalScope.launch {
            kotlinx.coroutines.delay(8000)
            if (cscAddress.value == address && sawCscFrame && !s.sawCrankData) {
                Log.i("DeviceManager", "CSC 帧无曲柄数据，判定非踏频器 $address")
                errorMessage.value = "该设备只发送速度数据（速度计/处于速度模式），" +
                    "不能用作踏频源；若是双模传感器请切到踏频模式"
                ble.disconnect(address)
                cscAddress.value = null
                cscReading.clear()
            }
        }
    }

    /** Set as soon as any CSC frame is parsed, valid cadence or not. */
    @Volatile private var sawCscFrame = false

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
            trainerAddress.value = null; trainerSourceTag.value = "—"
            clearTrainerReadings()
        }
        if (hrAddress.value == address) {
            ble.disconnect(address); hrAddress.value = null; hrReading.clear()
        }
        if (cscAddress.value == address) {
            ble.disconnect(address); cscAddress.value = null; cscReading.clear()
        }
    }

    fun disconnectAll(clearError: Boolean = true) {
        ble.disconnectAll()
        trainerDriver = null
        trainerAddress.value = null
        hrAddress.value = null
        cscAddress.value = null
        trainerSourceTag.value = "—"
        clearTrainerReadings()
        hrReading.clear()
        cscReading.clear()
        if (clearError) errorMessage.value = null
    }

    fun release() {
        disconnectAll()
        externalScope.cancel()
    }
}

/** Monotonic milliseconds; immune to wall-clock jumps. */
private fun nowMs(): Long = System.nanoTime() / 1_000_000
