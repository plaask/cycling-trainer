package io.github.cyclingtrainer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cyclingtrainer.app.AppViewModel
import io.github.cyclingtrainer.app.R
import io.github.cyclingtrainer.app.session.HrZones
import io.github.cyclingtrainer.app.session.PowerZones
import io.github.cyclingtrainer.app.session.RideSample
import io.github.cyclingtrainer.app.session.SessionEngine
import io.github.cyclingtrainer.app.ui.charts.ChartLegend
import io.github.cyclingtrainer.app.ui.charts.RideChart
import io.github.cyclingtrainer.app.workout.Workout
import java.util.Locale

/**
 * Train screen — the home page. Shows the currently selected course (or a
 * free-ride prompt), a readiness summary of connected sensors, and the big
 * Start button. The Bluetooth entry in the top-right opens the device
 * sub-page.
 */
@Composable
fun TrainScreen(
    vm: AppViewModel,
    workout: Workout?,
    onOpenDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val power by vm.deviceManager.powerWatts.collectAsState()
    val hr by vm.deviceManager.heartRateBpm.collectAsState()
    val cadence by vm.deviceManager.cadenceRpm.collectAsState()
    val cadSrc by vm.deviceManager.cadenceSource.collectAsState()
    val phase by vm.sessionPhase.collectAsState()
    val target by vm.currentTargetWatts.collectAsState()
    val ergOn by vm.ergEnabled.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val totalSec by vm.totalSeconds.collectAsState()
    val ftp by vm.ftpWatts.collectAsState()
    val hrRef by vm.hrReference.collectAsState()
    val lthrBpm by vm.lthrBpm.collectAsState()
    val maxHrBpm by vm.maxHrBpm.collectAsState()
    // Reference value depends on which HR scale the user picked (LTHR 7-zone
    // vs MaxHR 5-zone) — previously the max-HR choice was ignored here.
    val hrRefBpm = if (hrRef == HrZones.Reference.LTHR) lthrBpm else maxHrBpm
    // Collect role addresses so the readiness chips recompose when a device
    // connects or disconnects (previously read .value directly and never
    // updated after a disconnect).
    val trainerAddr by vm.deviceManager.trainerAddress.collectAsState()
    val hrAddr by vm.deviceManager.hrAddress.collectAsState()
    val cscAddr by vm.deviceManager.cscAddress.collectAsState()

    val live = phase == SessionEngine.Phase.RUNNING || phase == SessionEngine.Phase.PAUSED

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Title row with device entry.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (live) "训练中" else "训练",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // Device entry: an opaque chip so the icon reads as a button
            // against any background.
            IconButton(
                onClick = onOpenDevices,
                modifier = Modifier.background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ),
            ) {
                // Local vector: the Bluetooth mark lives in material-icons-
                // extended, whose 34 MB is not worth one icon.
                Icon(
                    painter = painterResource(R.drawable.ic_bluetooth),
                    contentDescription = "设备连接",
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- idle: course picker / free ride ----
        if (phase == SessionEngine.Phase.IDLE || phase == SessionEngine.Phase.FINISHED) {
            IdleState(
                workout = workout,
                hasTrainer = trainerAddr != null,
                hasHr = hrAddr != null,
                hasCsc = cscAddr != null,
                power = power,
                hr = hr,
                cadence = cadence,
                cadSrc = cadSrc,
            )
        } else {
            // ---- live metrics ----
            // Target power shows regardless of ERG state: it is what the
            // course asks at this second (free ride has no course target).
            val hasCourse = workout != null
            Text("目标功率", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasCourse) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val targetWatts = target
                    Text(
                        "${targetWatts ?: "—"} W",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (targetWatts != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (targetWatts != null) {
                        Spacer(Modifier.padding(start = 8.dp))
                        // FTP % chip next to the big number.
                        val pct = (targetWatts.toDouble() / ftp.coerceAtLeast(1) * 100)
                        Text(
                            "${Math.round(pct)}% FTP",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text("自由骑行", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            // Live ride chart: course bars (when selected) + power/HR lines.
            RideChart(
                workout = workout,
                samples = vm.liveSamples,
                ftpWatts = ftp,
            )
            ChartLegend(Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricWithFtp("功率", power?.toString() ?: "—", "W", power, ftp)
                MetricWithHrZone("心率", hr?.toString() ?: "—", "bpm", hr, hrRef, hrRefBpm)
                MetricBox("踏频", cadence?.let { "%.0f".format(Locale.US, it) } ?: "—", cadSrc)
            }
            Spacer(Modifier.height(20.dp))
            if (totalSec > 0) {
                LinearProgressIndicator(
                    progress = { (elapsed.toFloat() / totalSec).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    timeStr(elapsed) + " / " + timeStr(totalSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    timeStr(elapsed) + " 已骑",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // ERG switch — drives the trainer's resistance to the course target.
        // Independent of the target display (which always shows the course ask).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (ergOn) "ERG 已开启" else "ERG 已关闭",
                style = MaterialTheme.typography.labelMedium,
                color = if (ergOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = ergOn,
                onCheckedChange = { vm.setErgEnabled(it) },
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            phaseLabel(phase),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (phase) {
                SessionEngine.Phase.IDLE, SessionEngine.Phase.FINISHED -> {
                    Button(onClick = { vm.startWorkout(workout) }) {
                        Text(if (workout != null) "开始训练" else "开始自由骑行")
                    }
                }
                SessionEngine.Phase.RUNNING -> {
                    OutlinedButton(onClick = { vm.pauseWorkout() }) { Text("暂停") }
                    OutlinedButton(onClick = { vm.stopWorkout() }) { Text("停止") }
                }
                SessionEngine.Phase.PAUSED -> {
                    Button(onClick = { vm.resumeWorkout() }) { Text("继续") }
                    OutlinedButton(onClick = { vm.stopWorkout() }) { Text("停止") }
                }
            }
        }
    }
}

/** Power metric with zone + FTP% under the number. */
@Composable
private fun MetricWithFtp(
    label: String, value: String, unit: String, watts: Int?, ftp: Int,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        val sub = when {
            watts != null && ftp > 0 ->
                "${PowerZones.zoneText(ftp, watts)} · " +
                    "${Math.round(watts.toDouble() / ftp * 100)}% FTP"
            else -> unit
        }
        Text(sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** HR metric with the zone text ("Z3") under the number. */
@Composable
private fun MetricWithHrZone(
    label: String, value: String, unit: String, hr: Int?, ref: HrZones.Reference, refBpm: Int,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        val sub = if (hr != null) HrZones.zoneText(ref, refBpm, hr) else unit
        Text(sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IdleState(
    workout: Workout?,
    hasTrainer: Boolean,
    hasHr: Boolean,
    hasCsc: Boolean,
    power: Int?,
    hr: Int?,
    cadence: Double?,
    cadSrc: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (workout != null) {
            Text(workout.name, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Text("${workout.totalDurationSeconds / 60} 分钟 · ${workout.segments.size} 段",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            workout.description.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        } else {
            Text("自由骑行", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Text("在下方“课程”里选择一门课，或直接开始自由骑行（仅记录，不控制阻力）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(20.dp))
        // Sensor readiness summary
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReadinessChip("骑行台", hasTrainer)
            ReadinessChip("心率带", hasHr)
            ReadinessChip("踏频器", hasCsc)
        }
        Spacer(Modifier.height(8.dp))
        if (!hasTrainer) {
            Text("未连骑行台：课程将只记录数据、不控制阻力。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }

        // Live sensor readouts while idle: connected devices show their
        // current values before a ride even starts.
        if (hasTrainer || hasHr || hasCsc) {
            Spacer(Modifier.height(16.dp))
            Text("设备实时读数", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricBox("功率", power?.toString() ?: "—", "W")
                MetricBox("心率", hr?.toString() ?: "—", "bpm")
                MetricBox("踏频", cadence?.let { "%.0f".format(Locale.US, it) } ?: "—", cadSrc)
            }
        }
    }
}

@Composable
private fun ReadinessChip(label: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        "$label ${if (ok) "✓" else "—"}",
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .background(
                color = if (ok) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun MetricBox(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(unit, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shared with PipHud, which shows the same clock in the PiP window. */
internal fun timeStr(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(Locale.US, m, s)
}

/** Shared with PipHud, which shows the same phase in the PiP window. */
internal fun phaseLabel(p: SessionEngine.Phase): String = when (p) {
    SessionEngine.Phase.IDLE -> "未开始"
    SessionEngine.Phase.RUNNING -> "训练中"
    SessionEngine.Phase.PAUSED -> "已暂停"
    SessionEngine.Phase.FINISHED -> "已完成 🎉"
}
