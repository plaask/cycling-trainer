package io.github.cyclingtrainer.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin, coroutine-friendly wrapper over the official Android BluetoothGatt API.
 *
 * Design:
 *  - Every request/acknowledge operation (read, write, CCCD write) runs under a
 *    per-session [Mutex] and is confirmed by its own pending-slot in the GATT
 *    callback. Timeouts cancel stale requests. This keeps writes serialised and
 *    avoids interleaved control-point commands.
 *  - Notifications / indications arrive as hot [SharedFlow]s for consumers.
 *  - No third-party BLE library (handoff decision A).
 */
class BluetoothLeManager(
    private val appContext: Context,
) {
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? = bluetoothManager.adapter

    // ---- scanning ----
    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()
    @Volatile private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())

    // ---- sessions ----
    private val sessions = ConcurrentHashMap<String, GattSession>()
    val connectedDevices: List<GattSession> get() = sessions.values.filter { it.isConnected() }

    /**
     * Drops GattSessions whose link died underneath us (the Android stack
     * still reports them as connected after the peripheral goes to sleep /
     * drops radio). Role state on the calling side is reconciled afterwards
     * via [connectionState] flows, so a re-pair actually opens a fresh link.
     *
     * Sessions still being opened are never swept: a fresh session reports
     * DISCONNECTED until the stack answers, so a sweep during discovery would
     * tear down the very connection that is being established.
     */
    fun sweepStaleSessions() {
        sessions.values.forEach { s ->
            if (s.isOpening()) return@forEach
            if (!s.linkAlive()) {
                sessions.remove(s.address, s)
                s.dispose()
            }
        }
    }

    /** True while the Bluetooth stack really reports this session connected. */
    fun isLinkConnected(address: String): Boolean =
        sessions[address]?.isConnected() == true

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasPermissions(): Boolean {
        val connect = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val scan = if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        return connect && scan
    }

    // ================= SCANNING =================

    @SuppressLint("MissingPermission")
    fun startScan(serviceFilter: UUID? = null): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        if (!hasPermissions()) return false
        if (scanning) return true // already scanning
        // Don't wipe the list: previously seen devices stay visible/connectable
        // even while a fresh scan runs (and after stop).
        scanning = true
        val filters = if (serviceFilter == null) emptyList()
        else listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceFilter)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val ok = try {
            scanner.startScan(filters, settings, scanCallback)
            true
        } catch (e: Exception) {
            scanning = false
            false
        }
        return ok
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    /** Clears the discovered-device list (used when the user wants a fresh scan). */
    fun clearDevices() {
        _devices.value = emptyList()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device?.name
            val services = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val dev = BleDevice(result.device.address, name, result.rssi, services)
            val cur = _devices.value
            _devices.value = if (cur.any { it.address == dev.address })
                cur.map { if (it.address == dev.address) dev else it }
            else cur + dev
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    // ================= CONNECTION =================

    fun sessionFor(address: String): GattSession? = sessions[address]

    /**
     * Opens a GATT connection and waits for service discovery.
     * Active connects (autoConnect=false) are the norm for scanned trainers.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String): Result<GattSession> {
        val adapter = adapter ?: return Result.failure(IllegalStateException("No BT adapter"))
        if (!hasPermissions()) {
            return Result.failure(SecurityException("BLUETOOTH_CONNECT permission missing"))
        }
        // Stop any ongoing scan before connecting: scanning while opening a
        // GATT connection makes some stacks disconnect the link right after
        // connect (status 133), and it frees radio time for the connection.
        stopScan()
        // Drop sessions whose link died underneath us but that the stack still
        // reports as connected — otherwise we'd "reconnect" onto a dead link
        // and the device would never deliver data again.
        sweepStaleSessions()
        sessions[address]?.let {
            return if (it.isConnected()) Result.success(it)
            else Result.failure(IllegalStateException("connection in progress for $address"))
        }
        val device = adapter.getRemoteDevice(address)
        val session = GattSession(address, device)
        sessions[address] = session
        return try {
            session.open()
            // Fold post-connect discovery into the list so a trainer that
            // advertised no services gets its real role (FTMS etc.) shown.
            val discovered = session.discoveredServices
            if (discovered.isNotEmpty()) {
                _devices.value = _devices.value.map {
                    if (it.address == address) {
                        val merged = it.discoveredServices
                            .plus(discovered)
                            .distinct()
                        it.copy(discoveredServices = merged)
                    } else it
                }
            }
            Result.success(session)
        } catch (e: Exception) {
            sessions.remove(address)
            session.dispose()
            Result.failure(e)
        }
    }

    fun disconnect(address: String) {
        sessions.remove(address)?.dispose()
    }

    fun disconnectAll() {
        sessions.values.toList().forEach { it.dispose() }
        sessions.clear()
        stopScan()
    }

    // ================= SESSION =================

    /**
     * One connected peripheral. Request/response ops are mutex-serialised.
     */
    inner class GattSession(
        val address: String,
        private val device: BluetoothDevice,
    ) {
        private val opMutex = Mutex()
        private var gatt: BluetoothGatt? = null
        private val closed = AtomicBoolean(false)

        /** Set between connectGatt() and the end of service discovery. */
        @Volatile private var opening = false

        // pending slots — completed by callback handlers on the BT thread
        @Volatile private var pendingRead: CompletableDeferred<ByteArray?>? = null
        @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
        @Volatile private var pendingControlIndication: CompletableDeferred<ByteArray?>? = null

        /** Real services seen after GATT discovery (advertisement may be empty). */
        @Volatile private var _discoveredServices: List<UUID> = emptyList()
        val discoveredServices: List<UUID> get() = _discoveredServices

        private val _connectOpen = CompletableDeferred<Unit>()

        // Consumer-facing streams (hot; extraBufferCapacity avoids drop for fast sensors)
        val notifications: SharedFlow<Pair<UUID, ByteArray>> = MutableSharedFlow(extraBufferCapacity = 64)
        val indications: SharedFlow<Pair<UUID, ByteArray>> = MutableSharedFlow(extraBufferCapacity = 8)
        val connectionState: MutableStateFlow<Int> =
            MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)

        fun isConnected(): Boolean {
            val g = gatt ?: return false
            return bluetoothManager.getConnectionState(g.device, BluetoothProfile.GATT) ==
                BluetoothProfile.STATE_CONNECTED
        }

        /** Same as [isConnected] but tolerant of the platform lying during
         *  stack teardown right after a remote disconnect. */
        fun linkAlive(): Boolean {
            val g = gatt ?: return false
            if (connectionState.value == BluetoothProfile.STATE_DISCONNECTED) return false
            return isConnected()
        }

        @SuppressLint("MissingPermission")
        suspend fun open() {
            val g = device.connectGatt(
                appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            ) ?: throw IllegalStateException("connectGatt returned null for $address")
            gatt = g
            opening = true
            try {
                // Wait for service discovery success.
                withTimeoutOrNull(15_000) { _connectOpen.await() }
                    ?: throw IllegalStateException("connect/discovery timeout for $address")
            } finally {
                opening = false
            }
        }

        /** True while [open] is still waiting for connect + discovery. */
        fun isOpening(): Boolean = opening

        private val gattCallback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                connectionState.value = newState
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        g.discoverServices()
                    } else {
                        _connectOpen.completeExceptionally(
                            IllegalStateException("connect failed status=$status")
                        )
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectOpen.completeExceptionally(
                        IllegalStateException("disconnected status=$status")
                    )
                    failAllPending()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Snapshot the real services: some trainers advertise none
                    // and only reveal e.g. FTMS here after connecting.
                    _discoveredServices = g.services.map { it.uuid }
                    _connectOpen.complete(Unit)
                } else {
                    _connectOpen.completeExceptionally(
                        IllegalStateException("service discovery failed status=$status")
                    )
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
            ) {
                val slot = pendingRead ?: return
                pendingRead = null
                slot.complete(
                    if (status == BluetoothGatt.GATT_SUCCESS)
                        characteristic.value ?: ByteArray(0)
                    else null
                )
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
            ) {
                val slot = pendingWrite ?: return
                pendingWrite = null
                slot.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
            ) {
                // CCCD (notification/indication enable) writes are confirmed
                // here — without this callback every subscribe would time out.
                val slot = pendingWrite ?: return
                pendingWrite = null
                slot.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
            ) {
                val uuid = characteristic.uuid
                if (uuid == GattUuids.FTMS_FITNESS_MACHINE_CONTROL_POINT) {
                    pendingControlIndication?.let { slot ->
                        pendingControlIndication = null
                        slot.complete(value)
                    }
                    (indications as MutableSharedFlow).tryEmit(uuid to value)
                } else {
                    (notifications as MutableSharedFlow).tryEmit(uuid to value)
                }
            }
        }

        private fun failAllPending() {
            pendingRead?.let { it.complete(null); pendingRead = null }
            pendingWrite?.let { it.complete(false); pendingWrite = null }
            pendingControlIndication?.let { it.complete(null); pendingControlIndication = null }
        }

        // ---------- operations ----------

        @SuppressLint("MissingPermission")
        suspend fun read(ch: BluetoothGattCharacteristic): ByteArray? = opMutex.withLock {
            val g = gatt ?: return@withLock null
            val slot = CompletableDeferred<ByteArray?>()
            pendingRead = slot
            val ok = try { g.readCharacteristic(ch) } catch (e: Exception) { false }
            if (!ok) { pendingRead = null; return@withLock null }
            withTimeoutOrNull(5000) { slot.await() } ?: run { pendingRead?.let { it.complete(null) }; pendingRead = null; null }
        }

        @SuppressLint("MissingPermission")
        suspend fun write(ch: BluetoothGattCharacteristic, value: ByteArray): Boolean = opMutex.withLock {
            val g = gatt ?: return@withLock false
            val slot = CompletableDeferred<Boolean>()
            pendingWrite = slot
            val ok = try {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                true
            } catch (e: Exception) { false }
            if (!ok) { pendingWrite = null; return@withLock false }
            withTimeoutOrNull(5000) { slot.await() } ?: run { pendingWrite?.let { it.complete(false) }; pendingWrite = null; false }
        }

        /**
         * Subscribes to a characteristic's notifications. Characteristics that
         * use indications (e.g. the FTMS Control Point) must pass
         * [indicate] = true so the CCCD is written with 0x0002 — writing
         * 0x0001 to an indication-only CCCD is rejected and the subscription
         * silently never arrives.
         */
        @SuppressLint("MissingPermission")
        suspend fun enableNotifications(
            ch: BluetoothGattCharacteristic,
            indicate: Boolean = false,
        ): Boolean = opMutex.withLock {
            val g = gatt ?: return@withLock false
            val ok = try {
                g.setCharacteristicNotification(ch, true)
                true
            } catch (e: Exception) { false }
            if (!ok) return@withLock false
            val cccd = ch.getDescriptor(GattUuids.CCCD) ?: return@withLock false
            val slot = CompletableDeferred<Boolean>()
            pendingWrite = slot
            val enableValue = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok2 = try {
                g.writeDescriptor(cccd, enableValue)
                true
            } catch (e: Exception) { false }
            if (!ok2) { pendingWrite = null; return@withLock false }
            val success = withTimeoutOrNull(5000) { slot.await() } ?: false
            if (!success) { pendingWrite?.let { it.complete(false) } }
            success
        }

        /** Writes a command and waits for the resulting FTMS control-point indication. */
        suspend fun commandAndWait(
            ch: BluetoothGattCharacteristic, payload: ByteArray, timeoutMs: Long = 8000,
        ): ByteArray? = opMutex.withLock {
            val g = gatt ?: return@withLock null
            val cpSlot = CompletableDeferred<ByteArray?>()
            pendingControlIndication = cpSlot
            val wSlot = CompletableDeferred<Boolean>()
            pendingWrite = wSlot
            val ok = try {
                g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                true
            } catch (e: Exception) { false }
            if (!ok) { pendingWrite = null; pendingControlIndication = null; return@withLock null }
            val writeOk = withTimeoutOrNull(5000) { wSlot.await() } ?: false
            pendingWrite = null
            if (!writeOk) { pendingControlIndication = null; return@withLock null }
            withTimeoutOrNull(timeoutMs) { cpSlot.await() } ?: run {
                pendingControlIndication = null
                null
            }
        }

        /**
         * Writes a command without waiting for the control-point indication.
         *
         * Used for Set Target Power, which the session pushes once per second:
         * waiting up to 8 s for an indication while holding the write mutex
         * makes the ERG target lag behind the course (and lets commands pile
         * up) on trainers that do not acknowledge every target. The handshake
         * commands still use [commandAndWait] — those must be confirmed.
         */
        suspend fun writeCommand(
            ch: BluetoothGattCharacteristic, payload: ByteArray,
        ): Boolean = opMutex.withLock {
            val g = gatt ?: return@withLock false
            val wSlot = CompletableDeferred<Boolean>()
            pendingWrite = wSlot
            val ok = try {
                g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                true
            } catch (e: Exception) { false }
            if (!ok) { pendingWrite = null; return@withLock false }
            val writeOk = withTimeoutOrNull(5000) { wSlot.await() } ?: false
            pendingWrite = null
            writeOk
        }

        @SuppressLint("MissingPermission")
        fun dispose() {
            if (!closed.compareAndSet(false, true)) return
            failAllPending()
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
        }

        fun service(uuid: UUID) = gatt?.getService(uuid)
        fun characteristic(serviceUuid: UUID, charUuid: UUID): BluetoothGattCharacteristic? =
            gatt?.getService(serviceUuid)?.getCharacteristic(charUuid)
    }
}
