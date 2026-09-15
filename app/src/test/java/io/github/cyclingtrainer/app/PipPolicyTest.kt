package io.github.cyclingtrainer.app

import io.github.cyclingtrainer.app.session.SessionEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picture-in-picture is armed exactly while a ride is live. Arming it while
 * idle would shrink the course list into a bubble the first time the athlete
 * presses Home after opening the app.
 */
class PipPolicyTest {

    @Test
    fun `running ride auto-enters picture-in-picture`() {
        assertTrue(PipPolicy.shouldAutoEnter(SessionEngine.Phase.RUNNING))
    }

    @Test
    fun `paused ride keeps picture-in-picture armed`() {
        // The window is how the athlete resumes without expanding it, and the
        // paused ride is still what they left the app for.
        assertTrue(PipPolicy.shouldAutoEnter(SessionEngine.Phase.PAUSED))
    }

    @Test
    fun `idle activity backgrounds normally`() {
        assertFalse(PipPolicy.shouldAutoEnter(SessionEngine.Phase.IDLE))
    }

    @Test
    fun `finished ride stops auto-entering`() {
        assertFalse(PipPolicy.shouldAutoEnter(SessionEngine.Phase.FINISHED))
    }
}
