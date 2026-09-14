package io.github.cyclingtrainer.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.cyclingtrainer.app.AppViewModel
import io.github.cyclingtrainer.app.Permissions
import io.github.cyclingtrainer.app.ble.BleDevice
import io.github.cyclingtrainer.app.ble.GattUuids

/**
 * Device discovery + connection screen, shown as a sub-page over the train
 * screen (top-right Bluetooth entry). One trainer (FTMS) + one HR strap
 * (HRS) + one cadence sensor (CSC).
 *
 * Connect taps show a real in-progress state and failures surface in a
 * dialog with the actual reason — the old behaviour (silent background
 * launch + a tiny grey error line) read as "nothing happened".
 */
@Composable
fun DevicesScreen(
    vm: AppViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // --- runtime permissions ---
    var granted by remember {
        mutableStateOf(hasBlePermissions(context))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = it.values.all { v -> v } }

    if (!granted) {
        LaunchedEffect(Unit) {
            launcher.launch(Permissions.required)
        }
    }

    DisposableEffect(Unit) { onDispose { vm.ble.stopScan() } }
    // Drop links the platform reports as connected but that died underneath
    // us (strap slept / trainer powered off) so a fresh connect opens a new
    // GATT session instead of hanging on the stale one.
    LaunchedEffect(Unit) { vm.ble.sweepStaleSessions() }

    val scanning by vm.scanning.collectAsState()
    val devices by vm.ble.devices.collectAsState()
    val error by vm.deviceManager.errorMessage.collectAsState()
    // Collect role addresses so row states (已连接/断开) recompose on change.
    val trainerAddr by vm.deviceManager.trainerAddress.collectAsState()
    val hrAddr by vm.deviceManager.hrAddress.collectAsState()
    val cscAddr by vm.deviceManager.cscAddress.collectAsState()
    var connectingAddr by remember { mutableStateOf<String?>(null) }
    var connectFailure by remember { mutableStateOf<String?>(null) }

    // Cache names of every device we have seen, so a row keeps its name even
    // after the device stops advertising (connected rows are synthesized).
    LaunchedEffect(devices) {
        devices.forEach { vm.deviceManager.rememberName(it.address, it.name) }
    }

    // The scan may never see a *connected* strap/trainer again (peripherals
    // stop advertising while connected). Always add the currently connected
    // role rows so the user can see/disconnect them after a reconnect —
    // otherwise the device page looked empty although the device was on.
    val knownAddrs = remember(devices, trainerAddr, hrAddr, cscAddr) {
        val map = devices.associateBy { it.address }.toMutableMap()
        listOf(trainerAddr, hrAddr, cscAddr).forEach { addr ->
            if (addr != null) {
                val existing = map[addr]
                if (existing == null) {
                    // Synthesize the row; the device may no longer advertise.
                    map[addr] = BleDevice(
                        address = addr,
                        name = vm.deviceManager.deviceName(addr),
                        rssi = 0,
                        services = emptyList(),
                        discoveredServices = emptyList(),
                    )
                } else {
                    // Device still advertises; remember its name for later.
                    vm.deviceManager.rememberName(addr, existing.name)
                }
            }
        }
        map.values.toList()
    }

    Column(
        // Opaque full-screen background: this screen overlays the train page
        // (Bluetooth entry), so it must not show the content underneath.
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回训练")
            }
            Text("设备连接", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (!vm.ble.isBluetoothEnabled) {
                        // could launch system BT enable intent; simplified: ask user
                    }
                    vm.toggleScan()
                },
                enabled = granted,
            ) {
                Text(if (scanning) "停止" else "扫描")
            }
        }

        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Spacer(Modifier.padding(start = 6.dp))
                Text("扫描中…", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (knownAddrs.isEmpty() && !scanning) {
            Text(
                "点击“扫描”发现设备。骑行台需通电；心率带/踏频器需开启。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Connected devices first (they are the ones in use), then
        // cycling-related kinds (trainer/HR/cadence), unknown ones last.
        val ranked = remember(knownAddrs, trainerAddr, hrAddr, cscAddr) {
            knownAddrs.sortedByDescending { dev ->
                val connected = dev.address == trainerAddr ||
                    dev.address == hrAddr ||
                    dev.address == cscAddr
                val kind = when (deviceKind(dev)) {
                    "trainer" -> 3
                    "hr" -> 2
                    "csc" -> 1
                    else -> 0
                }
                // rank = 4 bits connected + kind
                (if (connected) 8 else 0) + kind
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ranked, key = { it.address }) { dev ->
                DeviceRow(
                    dev = dev,
                    connecting = connectingAddr == dev.address,
                    connected = trainerAddr == dev.address ||
                        hrAddr == dev.address ||
                        cscAddr == dev.address,
                    connectedRole = when (dev.address) {
                        trainerAddr -> "骑行台"
                        hrAddr -> "心率带"
                        cscAddr -> "踏频器"
                        else -> null
                    },
                    onConnect = {
                        connectingAddr = dev.address
                        connectFailure = null
                        vm.connectDevice(dev) { result ->
                            connectingAddr = null
                            if (result.isFailure) {
                                connectFailure = result.exceptionOrNull()?.message
                                    ?: "连接失败"
                            }
                        }
                    },
                    onDisconnect = { vm.disconnectDevice(dev.address) },
                )
            }
        }
    }

    // Real failure reason, dialog so it cannot be missed.
    if (connectFailure != null) {
        AlertDialog(
            onDismissRequest = { connectFailure = null },
            title = { Text("连接失败") },
            text = { Text(connectFailure ?: "") },
            confirmButton = {
                TextButton(onClick = { connectFailure = null }) { Text("知道了") }
            },
        )
    }

    // Also keep the legacy inline error for issues raised outside connect
    // taps (e.g. control-permission lost). It appears above the list when set.
    error?.let { msg ->
        if (connectFailure == null) {
            Text(msg, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun hasBlePermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 31) {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}

/** Role label from advertised + discovered services; "" if unrecognised. */
private fun deviceKind(dev: BleDevice): String = when {
    dev.allServices.any { it == GattUuids.FTMS_SERVICE } ||
        dev.allServices.any { it == GattUuids.FEC_SERVICE } -> "trainer"
    dev.allServices.any { it == GattUuids.HRS_SERVICE } -> "hr"
    dev.allServices.any { it == GattUuids.CSC_SERVICE } -> "csc"
    else -> ""
}

private fun kindLabel(kind: String): String = when (kind) {
    "trainer" -> "骑行台"
    "hr" -> "心率带"
    "csc" -> "踏频器"
    else -> ""
}

@Composable
private fun DeviceRow(
    dev: BleDevice,
    connecting: Boolean,
    connected: Boolean,
    connectedRole: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val kind = deviceKind(dev)
    val label = connectedRole ?: kindLabel(kind)
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    dev.name ?: dev.address,
                    fontWeight = FontWeight.SemiBold,
                )
                if (label.isNotEmpty()) {
                    Text(label, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                } else {
                    Text("未识别类型（可尝试连接）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(dev.address, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                connected -> {
                    Text("已连接", color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = onDisconnect) { Text("断开") }
                }
                connecting -> CircularProgressIndicator(Modifier.size(20.dp))
                else -> Button(onClick = onConnect) { Text("连接") }
            }
        }
    }
}
