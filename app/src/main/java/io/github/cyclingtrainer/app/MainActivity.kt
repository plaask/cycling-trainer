package io.github.cyclingtrainer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.github.cyclingtrainer.app.ui.DevicesScreen
import io.github.cyclingtrainer.app.ui.HistoryScreen
import io.github.cyclingtrainer.app.ui.Screen
import io.github.cyclingtrainer.app.ui.SettingsScreen
import io.github.cyclingtrainer.app.ui.TrainScreen
import io.github.cyclingtrainer.app.ui.WorkoutsScreen
import io.github.cyclingtrainer.app.ui.theme.CyclingTrainerTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // BLE permissions requested once up-front to make the permission
        // state predictable; each BLE screen also re-checks.
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

        setContent {
            val themeMode by vm.themeMode.collectAsState()
            CyclingTrainerTheme(themeMode = themeMode) {
                AppRoot(vm)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    // Training is the home tab; a workout is only "selected" (from the
    // course tab) and started from here.
    var screen by rememberSaveable { mutableStateOf(Screen.TRAIN) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    // Selection survives rotation: keep the course identity in saved state,
    // and re-resolve it against the (re)loaded library when it arrives.
    var selectedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }
    val workouts by vm.workouts.collectAsState()
    val selectedWorkout = remember(selectedWorkoutId, workouts) {
        workouts.firstOrNull { it.name == selectedWorkoutId }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (!showDevices) {
                    NavigationBar {
                        Screen.entries.forEach { s ->
                            NavigationBarItem(
                                selected = screen == s,
                                onClick = { screen = s },
                                icon = { Icon(s.icon(), contentDescription = s.title) },
                                label = { Text(s.title) },
                            )
                        }
                    }
                }
            }
        ) { padding ->
            when (screen) {
                Screen.TRAIN -> TrainScreen(
                    vm = vm,
                    workout = selectedWorkout,
                    onOpenDevices = { showDevices = true },
                    modifier = Modifier.padding(padding),
                )
                Screen.WORKOUTS -> WorkoutsScreen(
                    vm = vm,
                    selected = selectedWorkout,
                    onSelect = { w -> selectedWorkoutId = w.name },
                    modifier = Modifier.padding(padding),
                )
                Screen.HISTORY -> HistoryScreen(vm, Modifier.padding(padding))
                Screen.SETTINGS -> SettingsScreen(vm, Modifier.padding(padding))
            }
        }

        // Devices is a full-screen sub-page over everything (train home's
        // Bluetooth entry). System back closes it.
        if (showDevices) {
            BackHandler { showDevices = false }
            DevicesScreen(vm, onClose = { showDevices = false })
        }
    }
}

private fun Screen.icon() = when (this) {
    Screen.TRAIN -> Icons.Filled.FitnessCenter
    Screen.WORKOUTS -> Icons.Filled.List
    Screen.HISTORY -> Icons.Filled.History
    Screen.SETTINGS -> Icons.Filled.Settings
}
