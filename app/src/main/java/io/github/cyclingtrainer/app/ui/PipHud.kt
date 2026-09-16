package io.github.cyclingtrainer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cyclingtrainer.app.AppViewModel
import io.github.cyclingtrainer.app.session.SessionEngine
import io.github.cyclingtrainer.app.session.formatClockPair
import java.util.Locale

/**
 * Picture-in-picture HUD: the only thing drawn while the activity is in the
 * PiP window. MainActivity layers it over the full UI instead of swapping it
 * in, so the tab / course selection underneath survives the round trip.
 *
 * A PiP window receives no ordinary taps — every control lives in the system's
 * PiP menu (pause/resume is a RemoteAction wired in MainActivity) — so this is
 * read-only, sized for the ~16:9 bubble the params ask for, and refreshed by
 * the same StateFlows the train screen uses.
 */
@Composable
fun PipHud(vm: AppViewModel, modifier: Modifier = Modifier) {
    val phase by vm.sessionPhase.collectAsState()
    val power by vm.deviceManager.powerWatts.collectAsState()
    val hr by vm.deviceManager.heartRateBpm.collectAsState()
    val cadence by vm.deviceManager.cadenceRpm.collectAsState()
    val target by vm.currentTargetWatts.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val totalSec by vm.totalSeconds.collectAsState()
    val ftp by vm.ftpWatts.collectAsState()

    val course = totalSec > 0

    // A Surface, not a plain Box with .background(): besides painting the
    // opaque card that hides the UI underneath, it is what publishes the
    // theme's content colour to everything inside. Without it the readouts
    // fell back to LocalContentColor's black default — invisible on the dark
    // background of the dark scheme, while their labels (which name a colour
    // explicitly) stayed readable.
    Surface(
        modifier = modifier
            .fillMaxSize()
            // The full UI stays composed — and hit-testable — underneath.
            // Taps in a PiP window are normally swallowed by the system's own
            // handler (the one that opens the PiP menu), but if one is ever
            // delivered to the window it must not land on the invisible
            // Pause/Stop buttons behind this card, so swallow everything here.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            val rideOver = phase == SessionEngine.Phase.IDLE || phase == SessionEngine.Phase.FINISHED
            if (rideOver) {
                // The window outlives the ride when the athlete leaves it open
                // after the course ends; stale numbers would be worse than none.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (phase == SessionEngine.Phase.FINISHED) "训练已结束" else "训练未开始",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // ---- top: what the course asks, or free ride ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (course) {
                            val pct = target?.let {
                                Math.round(it.toDouble() / ftp.coerceAtLeast(1) * 100)
                            }
                            Text(
                                "${target ?: "—"} W",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (target != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (pct != null) {
                                Spacer(Modifier.padding(start = 8.dp))
                                Text(
                                    "$pct% FTP",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                "自由骑行",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            phaseLabel(phase),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // ---- middle: live readings ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        PipMetric("功率", power?.toString() ?: "—", "W")
                        PipMetric("心率", hr?.toString() ?: "—", "bpm")
                        PipMetric("踏频", cadence?.let { "%.0f".format(Locale.US, it) } ?: "—", "rpm")
                    }

                    // ---- bottom: progress + clock ----
                    Column(Modifier.fillMaxWidth()) {
                        if (course) {
                            LinearProgressIndicator(
                                progress = { (elapsed.toFloat() / totalSec).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            formatClockPair(elapsed, if (course) totalSec else 0),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PipMetric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Colour comes from the Surface above (onBackground): the readouts are
        // the brightest thing in the window and must follow the colour scheme.
        Text(
            value,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
