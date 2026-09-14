package io.github.cyclingtrainer.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Freshness windows: a sensor that stops transmitting must stop reading as
 * live, otherwise its last value is displayed and written to the CSV forever.
 */
class FreshValueTest {

    @Test
    fun `value is fresh inside the window and null after it`() {
        val v = FreshValue<Int>(windowMs = 3000)
        v.set(200, nowMs = 1000)

        assertEquals(200, v.get(nowMs = 1000))
        assertEquals(200, v.get(nowMs = 4000)) // exactly at the edge is still fresh
        assertNull(v.get(nowMs = 4001))
    }

    @Test
    fun `expiring on read does not resurrect on a later read`() {
        val v = FreshValue<Int>(windowMs = 1000)
        v.set(150, nowMs = 0)

        assertNull(v.get(nowMs = 2000))
        assertNull(v.get(nowMs = 2001))
    }

    @Test
    fun `a new reading restarts the window`() {
        val v = FreshValue<Int>(windowMs = 1000)
        v.set(100, nowMs = 0)
        v.set(180, nowMs = 900)

        assertEquals(180, v.get(nowMs = 1500))
        assertNull(v.get(nowMs = 1901))
    }

    @Test
    fun `clear drops the reading immediately`() {
        val v = FreshValue<Int>(windowMs = 60_000)
        v.set(120, nowMs = 0)
        v.clear()

        assertNull(v.get(nowMs = 1))
    }

    @Test
    fun `reading publishes on set and clears on disconnect`() {
        val r = Reading<Int>()
        assertEquals(0L, r.state.value)
        assertNull(r.value(nowMs = 10))

        r.set(140, nowMs = 500)
        assertEquals(500L, r.state.value)
        assertEquals(140, r.value(nowMs = 500))

        r.clear()
        assertNull(r.value(nowMs = 500))
    }
}
