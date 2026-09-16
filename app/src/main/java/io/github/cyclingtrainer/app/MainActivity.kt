package io.github.cyclingtrainer.app

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon as AndroidIcon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.cyclingtrainer.app.session.RideSessionService
import io.github.cyclingtrainer.app.session.SessionEngine
import io.github.cyclingtrainer.app.session.isLive
import io.github.cyclingtrainer.app.ui.DevicesScreen
import io.github.cyclingtrainer.app.ui.HistoryScreen
import io.github.cyclingtrainer.app.ui.PipHud
import io.github.cyclingtrainer.app.ui.Screen
import io.github.cyclingtrainer.app.ui.SettingsScreen
import io.github.cyclingtrainer.app.ui.TrainScreen
import io.github.cyclingtrainer.app.ui.WorkoutsScreen
import io.github.cyclingtrainer.app.ui.theme.CyclingTrainerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /**
     * Whether the activity is in the PiP window (or animating into it).
     *
     * Snapshot state read by the composition below, which layers [PipHud] on
     * top of the full UI rather than replacing it: swapping branches would
     * dispose `AppRoot` and with it the selected tab, course and device
     * sub-page, so expanding the window again would dump the athlete back on
     * the train home page.
     */
    private val inPip = mutableStateOf(false)

    /**
     * Session controls that live outside the activity's own UI: the PiP menu's
     * pause/resume action and the ride notification's 暂停/继续 + 停止 buttons.
     * All of them route into the same ViewModel entry points the train screen's
     * buttons use, so there is no second pause/resume path to keep in sync.
     *
     * Registered for the activity's lifetime, which is exactly as long as a
     * session can exist — the ride lives in this activity's ViewModel, so when
     * the activity goes away for good so does the session (and the service).
     */
    private val sessionActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RideSessionService.ACTION_TOGGLE_PAUSE -> when (vm.sessionPhase.value) {
                    SessionEngine.Phase.RUNNING -> vm.pauseWorkout()
                    SessionEngine.Phase.PAUSED -> vm.resumeWorkout()
                    else -> return
                }
                RideSessionService.ACTION_STOP -> vm.stopWorkout()
                else -> return
            }
            // Repaint the menu title/icon now; the session collector below
            // would get there too, one dispatch later.
            applyPipParams(vm.sessionPhase.value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // BLE permissions requested once up-front to make the permission
        // state predictable; each BLE screen also re-checks.
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                // The ongoing notification of the ride's foreground service.
                // Refusing it does not stop the service — the ride keeps its
                // screen-off guarantee, the notification is simply not drawn.
                Manifest.permission.POST_NOTIFICATIONS,
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

        // Package-scoped and unexported: only the PendingIntents this activity
        // hands to the system (PiP menu, ride notification) reach it.
        ContextCompat.registerReceiver(
            this,
            sessionActionReceiver,
            IntentFilter().apply {
                addAction(RideSessionService.ACTION_TOGGLE_PAUSE)
                addAction(RideSessionService.ACTION_STOP)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // PiP params and the screen-on flag both follow the session: a running
        // ride arms auto-enter and keeps the screen on, its end releases both.
        // StateFlow replays the current phase, so this also applies the initial
        // state — without a ride, Home backgrounds the app exactly as before
        // and the screen times out normally.
        lifecycleScope.launch {
            vm.sessionPhase.collect { phase ->
                applyPipParams(phase)
                // A live ride keeps the screen on: the phone is on the handlebar
                // and the system screen timeout would otherwise blank it
                // mid-ride. This is a window flag, not a wakelock — it needs no
                // permission and it ends the moment the ride does.
                if (phase.isLive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        setContent {
            val themeMode by vm.themeMode.collectAsState()
            CyclingTrainerTheme(themeMode = themeMode) {
                // One Surface for the whole window: it paints the scheme's
                // background and — more importantly — publishes the scheme's
                // *content* colour to every composable that does not name a
                // colour itself. MaterialTheme alone does not do that; the
                // Scaffold does it for the tab pages, but whatever is drawn
                // outside the Scaffold (the device sub-page, the PiP HUD) used
                // to fall back to LocalContentColor's black default and turned
                // invisible on a dark background.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // Always composed, PiP or not — see [inPip].
                        AppRoot(vm)
                        if (inPip.value) PipHud(vm, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    /**
     * Single place the PiP params are written. [PipPolicy] decides whether the
     * system may shrink the activity on its own; the menu action is only worth
     * offering while a ride can actually be paused or resumed.
     */
    private fun applyPipParams(phase: SessionEngine.Phase) {
        val autoEnter = PipPolicy.shouldAutoEnter(phase)
        val params = PictureInPictureParams.Builder()
            // Landscape bubble: the HUD is a row of big numbers, and the
            // system never shrinks a PiP window below a readable card.
            .setAspectRatio(Rational(16, 9))
            .setActions(if (autoEnter) listOf(pipToggleAction(phase)) else emptyList())
            .setAutoEnterEnabled(autoEnter)
            .build()
        // No-ops harmlessly when the system has PiP turned off; throws only if
        // the activity is no longer attached (teardown races).
        runCatching { setPictureInPictureParams(params) }
    }

    private fun pipToggleAction(phase: SessionEngine.Phase): RemoteAction {
        val paused = phase == SessionEngine.Phase.PAUSED
        return RemoteAction(
            // White-on-dark vectors: SystemUI draws these itself and does not
            // tint them from the app theme.
            AndroidIcon.createWithResource(
                this,
                if (paused) R.drawable.ic_pip_play else R.drawable.ic_pip_pause,
            ),
            if (paused) "继续" else "暂停",
            if (paused) "继续训练" else "暂停训练",
            PendingIntent.getBroadcast(
                this,
                0,
                // Same action the notification's 暂停/继续 button sends.
                Intent(RideSessionService.ACTION_TOGGLE_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /**
     * Android 15+ (this app targets 37) announces the entering animation
     * before the activity settles in PiP. Swapping to the HUD here is what
     * keeps the freshly shrunk full UI from flashing inside the bubble.
     * `isTransitioningToPip` is API 35 while the callback itself is API 34,
     * hence the version guard.
     */
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            pipState.isTransitioningToPip
        ) {
            inPip.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Safety net for the early swap above: a PiP gesture the user cancels
        // must never leave the HUD covering a fullscreen, resumed activity.
        if (!isInPictureInPictureMode) inPip.value = false
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sessionActionReceiver) }
        super.onDestroy()
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
    // and re-resolve it against the (re)loaded library when it arrives. Keyed
    // by the source document id rather than the display name, because two
    // courses may share a name — keying by name selected both at once.
    var selectedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }
    val workouts by vm.workouts.collectAsState()
    val selectedWorkout = remember(selectedWorkoutId, workouts) {
        val wanted = selectedWorkoutId ?: return@remember null
        workouts.firstOrNull { it.id == wanted }
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
                                icon = { ScreenIcon(s) },
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
                    onSelect = { w -> selectedWorkoutId = w.id },
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

/**
 * Bottom-bar icon for a destination.
 *
 * List/Settings come from material-icons-core (pulled in by material3);
 * FitnessCenter/History are local vectors in res/drawable, so the 34 MB
 * material-icons-extended artifact is not needed for four icons.
 */
@Composable
private fun ScreenIcon(s: Screen) {
    when (s) {
        Screen.TRAIN -> Icon(
            painter = painterResource(R.drawable.ic_fitness_center),
            contentDescription = s.title,
        )
        Screen.WORKOUTS -> Icon(Icons.AutoMirrored.Filled.List, contentDescription = s.title)
        Screen.HISTORY -> Icon(
            painter = painterResource(R.drawable.ic_history),
            contentDescription = s.title,
        )
        Screen.SETTINGS -> Icon(Icons.Filled.Settings, contentDescription = s.title)
    }
}
