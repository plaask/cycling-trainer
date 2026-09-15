package io.github.cyclingtrainer.app

import io.github.cyclingtrainer.app.session.SessionEngine

/**
 * Decides whether the activity should be armed to auto-enter picture-in-picture.
 *
 * PiP is armed only while a ride is actually running or paused. The window
 * exists to keep the metrics glanceable, so entering it from the course list or
 * the settings page would be noise; the official Compose PiP guide takes the
 * same shape — one boolean that is fed into `setAutoEnterEnabled`.
 *
 * Paused counts as "live" on purpose: a paused ride is still the thing the
 * athlete came back to the phone for, and the PiP menu offers "resume" without
 * expanding the window.
 *
 * Deliberately free of `android.*` types so the policy is unit-testable on the
 * JVM (same reason HrZones / FitWriter are).
 */
object PipPolicy {
    fun shouldAutoEnter(phase: SessionEngine.Phase): Boolean =
        phase == SessionEngine.Phase.RUNNING || phase == SessionEngine.Phase.PAUSED
}
