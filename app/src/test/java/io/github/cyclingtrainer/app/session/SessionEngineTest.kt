package io.github.cyclingtrainer.app.session

import io.github.cyclingtrainer.app.workout.Workout
import io.github.cyclingtrainer.app.workout.WorkoutSegment
import io.github.cyclingtrainer.app.workout.SegmentType
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEngineTest {

    private fun rampWorkout(): Workout =
        Workout(
            name = "t", description = "", author = "", sportType = "bike",
            segments = listOf(
                WorkoutSegment("a", SegmentType.WARMUP, 10, 0.5, 1.0),
                WorkoutSegment("b", SegmentType.STEADY_STATE, 10, 1.0, 1.0),
            ),
        )

    @Test
    fun `target power follows ramp and steady`() = runTest {
        val targets = mutableListOf<Int>()
        val engine = SessionEngine(
            workout = rampWorkout(),
            ftpWatts = 200,
            onTargetPower = { targets.add(it) },
            scope = this,
        )
        engine.start()
        runCurrent()
        // first push at t=0 -> 0.5*200 = 100 W
        assertEquals(listOf(100), targets)
        advanceTimeBy(5000)
        runCurrent()
        // t=5 -> ramp 0.5->1.0 across 10 s => 0.75*200=150
        assertTrue(targets.contains(150))
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(SessionEngine.Phase.FINISHED, engine.phase.value)
        assertEquals(200, targets.last())
    }

    @Test
    fun `free ride with null workout never pushes a target and ticks forever`() = runTest {
        val targets = mutableListOf<Int>()
        val ticks = mutableListOf<Int>()
        val engine = SessionEngine(
            workout = null,
            ftpWatts = 200,
            onTargetPower = { targets.add(it) },
            scope = this,
            onFinished = { },
            onTick = { ticks.add(it) },
        )
        engine.start()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        // 10 ticks happened (t=1..10) and no ERG target was ever pushed.
        assertTrue(targets.isEmpty())
        assertEquals((1..10).toList(), ticks)
        assertEquals(SessionEngine.Phase.RUNNING, engine.phase.value)
        engine.stop()
        assertEquals(SessionEngine.Phase.IDLE, engine.phase.value)
    }

    @Test
    fun `erg flag does not stop engine targets - caller gates the trainer`() = runTest {
        // The engine always reports what the course asks; whether that is
        // pushed to the trainer is the caller's (AppViewModel) decision —
        // that keeps the target display alive while ERG is off.
        val targets = mutableListOf<Int>()
        val ticks = mutableListOf<Int>()
        val engine = SessionEngine(
            workout = rampWorkout(),
            ftpWatts = 200,
            onTargetPower = { targets.add(it) },
            scope = this,
            onFinished = { },
            onTick = { ticks.add(it) },
        )
        engine.ergEnabled = false
        engine.start()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        // targets keep flowing even with erg disabled (UI shows the ask)
        assertTrue(targets.isNotEmpty())
        assertEquals((1..5).toList(), ticks)
        assertEquals(SessionEngine.Phase.RUNNING, engine.phase.value)
        engine.stop()
    }

    @Test
    fun `pause then resume continues ticking without restart`() = runTest {
        val targets = mutableListOf<Int>()
        val ticks = mutableListOf<Int>()
        val engine = SessionEngine(
            workout = rampWorkout(),
            ftpWatts = 200,
            onTargetPower = { targets.add(it) },
            scope = this,
            onFinished = { },
            onTick = { ticks.add(it) },
        )
        engine.start()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        engine.pause()
        advanceTimeBy(5_000) // paused wall time must NOT advance elapsed
        runCurrent()
        engine.resume()
        advanceTimeBy(2_000)
        runCurrent()
        // elapsed kept advancing only while running: ticks are per elapsed sec
        assertTrue(ticks.size >= 3)
        assertEquals(SessionEngine.Phase.RUNNING, engine.phase.value)
        // after resume, targets continue flowing (not stuck paused)
        assertTrue(targets.size >= 4)
        engine.stop()
    }
}
