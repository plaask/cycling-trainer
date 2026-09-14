package io.github.cyclingtrainer.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.cyclingtrainer.app.session.RideSample
import io.github.cyclingtrainer.app.workout.SegmentType
import io.github.cyclingtrainer.app.workout.Workout

/**
 * Shared chart building blocks: the course power histogram (one bar per
 * segment, FTP fraction) and a live ride overlay of power / heart-rate
 * curves. Pure Canvas — no chart library, matching the rest of the app.
 */

private val WarmupColor = Color(0xFF8D9BB0)
private val SteadyColor = Color(0xFF3D9C6E)
private val IntervalColor = Color(0xFFE07B39)
private val RecoveryColor = Color(0xFF4A7FC1)
private val CooldownColor = Color(0xFF8D9BB0)
private val FreeRideColor = Color(0xFF6E6E6E)

private fun segmentColor(t: SegmentType): Color = when (t) {
    SegmentType.WARMUP -> WarmupColor
    SegmentType.STEADY_STATE -> SteadyColor
    SegmentType.INTERVAL -> IntervalColor
    SegmentType.RECOVERY -> RecoveryColor
    SegmentType.COOLDOWN -> CooldownColor
    SegmentType.FREE_RIDE -> FreeRideColor
}

val PowerCurveColor = Color(0xFF2E8B57)
val HrCurveColor = Color(0xFFC0392B)

/** One trapezoid of the course histogram; value is FTP-fraction (may exceed
 *  1). The top edge runs from [startFrac] to [endFrac], so ramps (warmup /
 *  cooldown) render as sloped trapezoids instead of flat rectangles. */
data class HistogramBar(
    val startSec: Float,
    val endSec: Float,
    val startFrac: Float,
    val endFrac: Float,
    val type: SegmentType,
)

/** Turns segments into trapezoids covering [0, total]. */
fun workoutBars(workout: Workout): List<HistogramBar> {
    val bars = ArrayList<HistogramBar>(workout.segments.size)
    var t = 0f
    for (seg in workout.segments) {
        val d = seg.durationSeconds.toFloat().coerceAtLeast(0f)
        bars += HistogramBar(t, t + d, seg.powerStart.toFloat(), seg.powerEnd.toFloat(), seg.type)
        t += d
    }
    return bars
}

/** Appends the sloped-top trapezoid for [b] to [path]. Height maps
 *  [b.startFrac]/[b.endFrac] through [yOfFrac] (0 = baseline). */
private fun androidx.compose.ui.graphics.Path.addBarTrapezoid(
    b: HistogramBar,
    totalSec: Int,
    w: Float,
    yOfFrac: (Float) -> Float,
): androidx.compose.ui.graphics.Path {
    val x0 = (b.startSec / totalSec) * w
    val x1 = (b.endSec / totalSec) * w
    moveTo(x0, yOfFrac(b.startFrac))
    lineTo(x1, yOfFrac(b.endFrac))
    lineTo(x1, yOfFrac(0f))
    lineTo(x0, yOfFrac(0f))
    close()
    return this
}

/** Legend chips: colored dot + label. */
@Composable
fun ChartLegend(modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendChip("功率", PowerCurveColor)
        LegendChip("心率", HrCurveColor)
        LegendChip("课程", SteadyColor)
    }
}

@Composable
private fun LegendChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 4.dp)
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Static per-segment histogram with minute ticks underneath. */
@Composable
fun WorkoutHistogram(
    workout: Workout,
    modifier: Modifier = Modifier,
) {
    val bars = workoutBars(workout)
    val totalSec = workout.totalDurationSeconds.coerceAtLeast(1)
    val maxFrac = (bars.maxOfOrNull { maxOf(it.startFrac, it.endFrac) } ?: 1f)
        .coerceAtLeast(0.2f)
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val w = size.width
            val h = size.height
            for (b in bars) {
                // trapezoid top edge follows the ramp; rectangle when flat
                val p = Path()
                p.addBarTrapezoid(b, totalSec, w) { frac ->
                    h - (frac / maxFrac) * h
                }
                drawPath(p, segmentColor(b.type))
            }
            drawLine(
                color = textColor.copy(alpha = 0.3f),
                start = Offset(0f, 0f),
                end = Offset(w, 0f),
                strokeWidth = 1f,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            listOf(0, totalSec / 2, totalSec).forEach { sec ->
                Text(
                    "${sec / 60}'",
                    fontSize = 10.sp,
                    color = textColor,
                    textAlign = when (sec) {
                        0 -> TextAlign.Start
                        totalSec -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Live ride chart: dim course bars underneath, then the recorded power
 * (green) and heart-rate (red) 1 Hz lines. Power shares the bar axis
 * (100 % FTP sits at half height); HR auto-scales to its own max.
 */
@Composable
fun RideChart(
    workout: Workout?,
    samples: List<RideSample>,
    ftpWatts: Int,
    modifier: Modifier = Modifier,
) {
    val bars = workout?.let { workoutBars(it) } ?: emptyList()
    val totalSec = workout?.totalDurationSeconds?.coerceAtLeast(1)
        ?: (samples.lastOrNull()?.elapsedSeconds?.plus(1) ?: 1)
    val ftp = ftpWatts.coerceAtLeast(1)
    val hrReadings = samples.mapNotNull { it.heartRateBpm }
    val hrMax = (hrReadings.maxOrNull() ?: 0).coerceAtLeast(1)

    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        // Clamp x to the canvas: the recorder only advances during a running
        // ride, but guard against any sample overshooting the course end.
        val xOf: (Float) -> Float = { sec ->
            ((sec / totalSec) * w).coerceIn(0f, w)
        }

        // bars: value 1.0 (FTP) -> half height, so power line overlays nicely
        if (bars.isNotEmpty()) {
            for (b in bars) {
                val p = Path()
                p.addBarTrapezoid(b, totalSec, w) { frac ->
                    h - (frac / 1.0f) * h * 0.5f
                }
                drawPath(p, segmentColor(b.type).copy(alpha = 0.30f))
            }
        }

        // power line (only where a reading exists)
        if (samples.any { it.powerWatts != null }) {
            val path = Path()
            var started = false
            for (s in samples) {
                val p = s.powerWatts ?: continue
                val y = h - (p.toFloat() / ftp).coerceIn(0f, 2f) * h * 0.5f
                val x = xOf(s.elapsedSeconds.toFloat())
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = PowerCurveColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // HR line on its own auto scale (red, right axis)
        if (hrReadings.isNotEmpty()) {
            val path = Path()
            var started = false
            for (s in samples) {
                val hr = s.heartRateBpm ?: continue
                val y = h - (hr.toFloat() / hrMax) * h * 0.85f
                val x = xOf(s.elapsedSeconds.toFloat())
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = HrCurveColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}
