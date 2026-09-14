package io.github.cyclingtrainer.app.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Common interface for a controllable smart trainer, hiding whether the
 * underlying transport is standard FTMS (0x1826) or the FE-C-over-BLE tunnel
 * (ThinkRider/Tacx). DeviceManager talks to this instead of FtmsTrainer
 * directly.
 */
interface TrainerDriver {
    /** Live power (W) from the trainer's own readings. */
    val powerFlow: Flow<Int?>

    /** Live cadence (rpm) from the trainer's own readings. */
    val cadenceFlow: Flow<Int?>

    /** Subscribes to the trainer's measurement channels. */
    suspend fun connect(): Result<Unit>

    /** ERG control available (FTMS CP present / FE-C write char present). */
    val controlAvailable: Boolean

    /** Ask the trainer to hold [watts]. False if unsupported/failed. */
    suspend fun setTargetPower(watts: Int): Boolean

    /** Put the trainer into ERG mode before a workout (FTMS handshake; FE-C no-op). */
    suspend fun startErg(): Boolean

    /** Release ERG control / go back to free state. */
    suspend fun stopControl(): Boolean
}

/** Adapter over the standard FTMS trainer. */
class FtmsTrainerDriver(private val trainer: FtmsTrainer) : TrainerDriver {
    override val powerFlow = trainer.powerFlow
    override val cadenceFlow = trainer.cadenceFlow
    override suspend fun connect() = trainer.connect()
    override val controlAvailable: Boolean get() = trainer.controlPointAvailable

    override suspend fun setTargetPower(watts: Int): Boolean = trainer.setTargetPower(watts)

    override suspend fun startErg(): Boolean {
        if (!controlAvailable) return false
        val control = try {
            withTimeoutOrNull(10_000) { trainer.requestControl() } ?: false
        } catch (e: Exception) { false }
        if (!control) return false
        return try { trainer.start() } catch (e: Exception) { false }
    }

    override suspend fun stopControl(): Boolean = trainer.reset()
}

/** Adapter over the FE-C-over-BLE tunnel (ThinkRider X2/X5/X7, Tacx). */
class FecTrainerDriver(private val fec: FecOverBle) : TrainerDriver {
    override val powerFlow = fec.powerFlow
    override val cadenceFlow = fec.cadenceFlow
    override suspend fun connect() = fec.connect()
    override val controlAvailable: Boolean get() = true

    override suspend fun setTargetPower(watts: Int): Boolean = fec.setTargetPower(watts)

    // FE-C has no request-control handshake; ERG engages with the first
    // Set Target Power frame the session sends.
    override suspend fun startErg(): Boolean = true

    override suspend fun stopControl(): Boolean = fec.setTargetPowerOff()
}
