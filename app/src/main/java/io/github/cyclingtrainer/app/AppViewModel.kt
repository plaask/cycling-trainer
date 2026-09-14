package io.github.cyclingtrainer.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.cyclingtrainer.app.ble.BleDevice
import io.github.cyclingtrainer.app.ble.BluetoothLeManager
import io.github.cyclingtrainer.app.ble.DeviceManager
import io.github.cyclingtrainer.app.ble.GattUuids
import io.github.cyclingtrainer.app.session.HrZones
import io.github.cyclingtrainer.app.session.RideRecorder
import io.github.cyclingtrainer.app.session.RideSample
import io.github.cyclingtrainer.app.session.SessionEngine
import io.github.cyclingtrainer.app.ui.theme.ThemeMode
import io.github.cyclingtrainer.app.workout.CourseSource
import io.github.cyclingtrainer.app.workout.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Runtime permission request helper for BLE scanning. */
object Permissions {
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
}

private const val PREFS = "cycling_trainer_settings"
private const val KEY_FTP = "ftp_watts"
private const val KEY_THEME = "theme_mode"
private const val KEY_LTHR = "lthr_bpm"
private const val KEY_MAX_HR = "max_hr_bpm"
private const val KEY_HR_REF = "hr_reference"
private const val KEY_COURSE_TREE = "course_tree_uri"

/**
 * App-wide state + business logic holder.
 * Owns BLE, the loaded workout library, the active session engine + recorder,
 * and persisted user settings (FTP, theme mode).
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val ble = BluetoothLeManager(application)
    val deviceManager = DeviceManager(ble)

    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- settings ----
    /** Athlete FTP in watts, used to turn .zwo FTP-fractions into ERG targets. */
    val ftpWatts = MutableStateFlow(prefs.getInt(KEY_FTP, 200))
    /** Theme preference; persisted so it survives restarts. */
    val themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    )

    fun setFtp(watts: Int) {
        val v = watts.coerceIn(50, 600)
        ftpWatts.value = v
        prefs.edit().putInt(KEY_FTP, v).apply()
    }

    fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    // ---- heart-rate reference (LTHR or max HR) ----
    /** Lactate-threshold HR in bpm (persisted; used when [hrReference] is LTHR). */
    val lthrBpm = MutableStateFlow(prefs.getInt(KEY_LTHR, 170))
    /** Maximum HR in bpm (persisted; used when [hrReference] is MAX_HR). */
    val maxHrBpm = MutableStateFlow(prefs.getInt(KEY_MAX_HR, 190))
    /** Which of the two the athlete wants the HR zones based on. */
    val hrReference = MutableStateFlow(
        runCatching { HrZones.Reference.valueOf(prefs.getString(KEY_HR_REF, HrZones.Reference.LTHR.name)!!) }
            .getOrDefault(HrZones.Reference.LTHR)
    )

    fun setLthr(bpm: Int) {
        val v = bpm.coerceIn(80, 240)
        lthrBpm.value = v
        prefs.edit().putInt(KEY_LTHR, v).apply()
    }

    fun setMaxHr(bpm: Int) {
        val v = bpm.coerceIn(100, 260)
        maxHrBpm.value = v
        prefs.edit().putInt(KEY_MAX_HR, v).apply()
    }

    fun setHrReference(ref: HrZones.Reference) {
        hrReference.value = ref
        prefs.edit().putString(KEY_HR_REF, ref.name).apply()
    }

    // ---- workout library (from a user-picked public folder) ----
    private val _workouts = MutableStateFlow<List<Workout>>(emptyList())
    val workouts: StateFlow<List<Workout>> = _workouts.asStateFlow()

    /** Tree Uri of the chosen course folder (Documents/CyclingTrainer), if any. */
    val courseTreeUri = MutableStateFlow<String?>(prefs.getString(KEY_COURSE_TREE, null))

    init {
        // Auto-load courses from the persisted folder on every start.
        if (courseTreeUri.value != null) loadWorkouts()
    }

    override fun onCleared() {
        // Only release BLE when the ViewModel is truly discarded (activity
        // finished). On configuration changes (rotation) the ViewModel — and
        // therefore all device connections — survives, so riding keeps its
        // sensors and ERG control across portrait/landscape switches.
        deviceManager.release()
        super.onCleared()
    }

    private fun loadWorkouts() {
        val tree = courseTreeUri.value ?: run {
            _workouts.value = emptyList()
            return
        }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    CourseSource.load(
                        getApplication<Application>().contentResolver,
                        Uri.parse(tree),
                    )
                }.getOrDefault(emptyList())
            }
            _workouts.value = list
        }
    }

    /**
     * Persists the folder the user picked (and the read permission), then
     * loads its courses. The grant survives restarts, so subsequent app
     * launches read the folder automatically.
     */
    fun setCourseFolder(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        courseTreeUri.value = uri.toString()
        prefs.edit().putString(KEY_COURSE_TREE, uri.toString()).apply()
        loadWorkouts()
    }

    // ---- connection ----
    val scanning = MutableStateFlow(false)

    // ---- session ----
    private var engine: SessionEngine? = null
    private var recorder: RideRecorder? = null
    val sessionPhase = MutableStateFlow(SessionEngine.Phase.IDLE)
    val elapsedSeconds = MutableStateFlow(0)
    val totalSeconds = MutableStateFlow(0)

    /** Current course target (watts) shown in the train UI; null before a
     *  course starts / for free rides. Independent of [ergEnabled]: the UI
     *  always shows what the course asks. */
    val currentTargetWatts = MutableStateFlow<Int?>(null)

    /** 1 Hz ride samples collected so far, for the live ride chart. */
    val liveSamples: List<RideSample>
        get() = recorder?.snapshot() ?: emptyList()

    /**
     * Increments once per finished ride, after its CSV has been written.
     * The history list observes this to refresh itself — previously a ride
     * recorded on the train tab never appeared until the app restarted.
     */
    val recordedRides = MutableStateFlow(0)

    /** Whether ERG (trainer-driven resistance) is enabled during the session. */
    val ergEnabled = MutableStateFlow(true)

    /**
     * ERG on/off mid-session. When turned off the trainer is released; when
     * re-enabled the trainer must re-enter ERG mode (a fresh request-control /
     * start handshake) or the resistance stops responding — that was the
     * repeated-toggle bug.
     */
    fun setErgEnabled(on: Boolean) {
        if (ergEnabled.value == on) return
        ergEnabled.value = on
        engine?.ergEnabled = on
        if (on) {
            if (sessionPhase.value == SessionEngine.Phase.RUNNING ||
                sessionPhase.value == SessionEngine.Phase.PAUSED
            ) {
                viewModelScope.launch { deviceManager.startWorkout() }
            }
        } else {
            // ERG off mid-session: release the trainer. The target display
            // keeps showing the course ask (the UI no longer keys it off ERG).
            viewModelScope.launch { deviceManager.stopWorkout() }
        }
    }

    /**
     * Latest target power (watts) fed to the recorder. A StateFlow, not a
     * buffered SharedFlow: the engine pushes the first target at t=0 before
     * the recorder has started collecting, and a SharedFlow with no replay
     * dropped it, leaving the first CSV row's target empty.
     */
    private val targetChannel = MutableStateFlow<Int?>(null)

    fun toggleScan() {
        if (scanning.value) {
            ble.stopScan()
            scanning.value = false
        } else {
            // Clear stale results once per fresh scan session; the list is
            // kept after stop so already-seen devices stay reconnectable.
            ble.clearDevices()
            ble.startScan()
            scanning.value = true
        }
    }

    // ---- device connection (coordinated by MainActivity via these states) ----

    /** Addresses the UI shows as connected/selected per role. */
    val connectedTrainer = MutableStateFlow<String?>(null)
    val connectedHr = MutableStateFlow<String?>(null)
    val connectedCsc = MutableStateFlow<String?>(null)

    /**
     * User tapped connect on [dev] in the list. Decide the role from the
     * advertised (or post-connect discovered) services and trigger
     * [deviceManager.connectRole] for that role only.
     *
     * Connecting is strictly incremental: an already-connected trainer/strap
     * keeps its session — connecting the second device must not disconnect
     * the first (the old all-or-nothing connectAll() did exactly that).
     *
     * Devices with no recognised role are still connectable — some trainers
     * (ThinkRider X2 among them) advertise no FTMS service and only reveal
     * it after connecting. The role is re-evaluated from
     * [BleDevice.discoveredServices] once connected.
     *
     * [onDone] is invoked on the main thread once the whole connect attempt
     * finishes (success or failure) so the UI can clear its "connecting"
     * state — the old code cleared it synchronously right after launching,
     * making the tap look like a no-op.
     */
    fun connectDevice(dev: BleDevice, onDone: (Result<Unit>) -> Unit = {}) {
        val role = when {
            dev.allServices.any { it == GattUuids.HRS_SERVICE } -> "hr"
            dev.allServices.any { it == GattUuids.CSC_SERVICE } -> "csc"
            // FTMS/FE-C or unknown: try as trainer — the trainer may reveal
            // FTMS/FE-C on discovery. It is connected under the trainer role
            // if it does.
            else -> "trainer"
        }
        viewModelScope.launch {
            val result = deviceManager.connectRole(role, dev.address, dev.name)
            // The role that actually connected is reflected per-role (if the
            // device turned out not to expose its role, it simply stays off).
            if (deviceManager.trainerAddress.value == dev.address) {
                connectedTrainer.value = dev.address
            }
            if (deviceManager.hrAddress.value == dev.address) {
                connectedHr.value = dev.address
            }
            if (deviceManager.cscAddress.value == dev.address) {
                connectedCsc.value = dev.address
            }
            onDone(result)
        }
    }

    fun disconnectDevice(address: String) {
        viewModelScope.launch {
            deviceManager.disconnectDevice(address)
            if (connectedTrainer.value == address) connectedTrainer.value = null
            if (connectedHr.value == address) connectedHr.value = null
            if (connectedCsc.value == address) connectedCsc.value = null
        }
    }

    fun refreshWorkouts() = loadWorkouts()

    // ---- session control ----

    /**
     * Starts [workout] (null = free ride: no ERG target, recording only)
     * against the connected trainer (if any) + recorder.
     *
     * Guarded by the session phase rather than by a Job handle: the engine is
     * the single owner of "is a ride active", so a second Start while running
     * cannot build a parallel recorder/engine pair.
     */
    fun startWorkout(workout: Workout?) {
        if (sessionPhase.value == SessionEngine.Phase.RUNNING ||
            sessionPhase.value == SessionEngine.Phase.PAUSED
        ) {
            return
        }
        val mgr = deviceManager
        val ftp = ftpWatts.value.coerceAtLeast(50)
        val ctx = getApplication<Application>()

        // The recorder has no clock of its own: the engine's tick drives one
        // sample per session-second, so recorded elapsed time always matches
        // the engine (pauses are naturally skipped). A separate wall-clock
        // ticker used to drift ahead of the course, pushing the live chart's
        // curves past the right edge even without pausing.
        recorder = RideRecorder(
            outputDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "rides"),
            workoutName = workout?.name,
            powerFlow = mgr.powerWatts,
            cadenceFlow = mgr.cadenceRpm,
            hrFlow = mgr.heartRateBpm,
            targetFlow = targetChannel,
            scope = viewModelScope,
        ).also { it.start() }

        engine = SessionEngine(
            workout = workout,
            ftpWatts = ftp,
            onTargetPower = { watts ->
                currentTargetWatts.value = watts
                targetChannel.value = watts
                // Best-effort push to the trainer only while ERG is enabled;
                // the target itself is always reported so the display stays
                // live when ERG is off.
                if (ergEnabled.value) {
                    mgr.setTargetPower(watts)
                }
            },
            scope = viewModelScope,
            onFinished = { finishSession(SessionEngine.Phase.FINISHED) },
            onTick = { sec ->
                elapsedSeconds.value = sec
                recorder?.sample(sec)
            },
        ).also {
            // Must be set before start(): start() pushes the first target
            // immediately, and the engine gates that push on ergEnabled.
            it.ergEnabled = ergEnabled.value
            it.start()
        }
        sessionPhase.value = SessionEngine.Phase.RUNNING
        totalSeconds.value = workout?.totalDurationSeconds ?: 0
        if (ergEnabled.value) {
            viewModelScope.launch { mgr.startWorkout() }
        }
    }

    fun pauseWorkout() {
        engine?.pause()
        sessionPhase.value = SessionEngine.Phase.PAUSED
    }

    fun resumeWorkout() {
        engine?.resume()
        sessionPhase.value = SessionEngine.Phase.RUNNING
    }

    /**
     * Stops the ride and writes the CSV.
     *
     * The final phase is [SessionEngine.Phase.IDLE] and the elapsed time is
     * kept (not reset) so the finished ride's curves stay on screen until the
     * next Start.
     */
    fun stopWorkout() = finishSession(SessionEngine.Phase.IDLE)

    /**
     * Single exit point for a ride — reached both by the user tapping Stop and
     * by the engine hitting the end of the course. Keeping one path means the
     * CSV is always written exactly once: previously both callers nulled the
     * recorder independently, so stopping on the very last second could drop
     * the whole recording.
     */
    private fun finishSession(finalPhase: SessionEngine.Phase) {
        if (engine == null && recorder == null) return
        engine?.stop()
        engine = null
        val rec = recorder
        recorder = null
        sessionPhase.value = finalPhase
        currentTargetWatts.value = null
        viewModelScope.launch {
            runCatching { rec?.stop() }
            // Bump the counter only after the file exists, so a History screen
            // observing it can never list the rides directory too early.
            recordedRides.value += 1
            deviceManager.stopWorkout()
        }
    }
}
